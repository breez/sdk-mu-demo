package routes

import ErrorCodes
import OptimizeQueue
import SdkAccess
import SignerMismatchException
import SignerMode
import AppConfig
import breez_sdk_spark.BuildTransferPackageOptions
import breez_sdk_spark.BuildUnsignedTransferPackageRequest
import breez_sdk_spark.EcdsaSignatureBytes
import breez_sdk_spark.ExternalIdentifier
import breez_sdk_spark.ExternalNewLeafKey
import breez_sdk_spark.ExternalOperatorPackage
import breez_sdk_spark.ExternalPrepareTransferRequest
import breez_sdk_spark.ExternalPreparedTransfer
import breez_sdk_spark.ExternalTreeNodeId
import breez_sdk_spark.OnchainConfirmationSpeed
import breez_sdk_spark.PaymentRequest
import breez_sdk_spark.PrepareSendPaymentRequest
import breez_sdk_spark.PrepareSendPaymentResponse
import breez_sdk_spark.PublishSignedTransferPackageRequest
import breez_sdk_spark.PublishSignedTransferPackageResponse
import breez_sdk_spark.SendPaymentMethod
import breez_sdk_spark.SendPaymentOptions
import breez_sdk_spark.SendPaymentRequest
import breez_sdk_spark.SignedTransferPackage
import breez_sdk_spark.TransferSignature
import breez_sdk_spark.UnsignedTransferPackage
import bytesToHex
import com.ionspin.kotlin.bignum.integer.BigInteger
import crockfordEncode
import hexToBytes
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import java.util.concurrent.ConcurrentHashMap
import javax.sql.DataSource
import kotlinx.serialization.Serializable
import org.slf4j.LoggerFactory
import randomBytes
import requireUser
import respondError

private val log = LoggerFactory.getLogger("Send")

@Serializable
data class PrepareBody(
    val payment_request: String,
    val amount_sats: Long? = null,
)

/**
 * Prepare response. The basic quote fields are always present. Under
 * SIGNER=turnkey the response also carries the client-signing material:
 *  - `kind` = "transfer" (the actual send → passkey approval) or "swap"
 *    (a denomination swap to the SSP → silent session stamp),
 *  - `sign_with` + `transfer` = what the client maps to a Turnkey
 *    SPARK_PREPARE_TRANSFER activity.
 * Under SIGNER=seed those are null and the client uses POST .../send.
 */
@Serializable
data class PrepareResult(
    val prepare_id: String,
    val method: String,         // "bolt11" | "onchain"
    val amount_sats: Long,
    val fee_sats: Long,
    val kind: String? = null,   // "transfer" | "swap" (turnkey only)
    val sign_with: String? = null,
    val transfer: TransferDto? = null,
    // The invoice/address the payment pays, shown at approval (turnkey). Read
    // from the prepare response, so it is set for the send leg and any swap that
    // precedes it alike.
    val destination: String? = null,
)

/** The fields of a Turnkey SPARK_PREPARE_TRANSFER `transfer` parameter, hex
 * where the activity wants hex. Mirrors the SDK's own FFI→Turnkey mapping. */
@Serializable
data class TransferDto(
    val transfer_id: String,
    val receiver_public_key: String,            // hex, 33-byte compressed
    val threshold: Long,
    val leaves: List<LeafDto>,
    val operator_recipients: List<OperatorRecipientDto>,
)

@Serializable
data class LeafDto(val leaf_id: String, val new_leaf_id: String)

@Serializable
data class OperatorRecipientDto(
    val operator_id: String,            // hex of the FROST identifier
    val encryption_public_key: String, // hex, 33-byte compressed
)

// --- seed-mode server send -------------------------------------------------

@Serializable
data class SendBody(val prepare_id: String)

@Serializable
data class SendResult(
    val payment_id: String,
    val status: String,
    val fee_sats: Long,
)

// --- turnkey-mode client-signed publish ------------------------------------

@Serializable
data class PublishBody(
    val prepare_id: String,
    val signed: SignedTransferDto,
)

/** The result of the client's Turnkey SPARK_PREPARE_TRANSFER, mapped back into
 * an `ExternalPreparedTransfer`. `transfer_user_signature` is the 64-byte
 * compact ECDSA signature (the client converts Turnkey's DER form). */
@Serializable
data class SignedTransferDto(
    val operator_packages: List<OperatorPackageDto>,
    val new_leaf_keys: List<NewLeafKeyDto>,
    val transfer_user_signature: String, // hex, 64-byte compact r||s
)

@Serializable
data class OperatorPackageDto(val operator_id: String, val encrypted_package: String)

@Serializable
data class NewLeafKeyDto(val leaf_id: String, val public_key: String)

@Serializable
data class PublishResult(
    val swap_completed: Boolean = false,
    val payment_id: String? = null,
    val status: String? = null,
    val fee_sats: Long? = null,
)

/**
 * Send is two steps. Under SIGNER=turnkey the steps are
 * `prepare` (build the unsigned transfer package, server-side, no keys) and
 * `publish` (submit the package the client signed with its passkey/session).
 * `publish` may report a denomination swap is still needed, in which case the
 * client re-prepares and signs again — driving the swap loop one HTTP round
 * trip at a time. Under SIGNER=seed the steps are the classic `prepare` +
 * `send` (the server signs).
 *
 * The prepared payload (and, for turnkey, the unsigned package) lives in an
 * in-memory cache keyed by a random `prepare_id`, bound to a `userId`, 60s TTL.
 * Build is a read-only dry-run (the SDK reserves leaves only at publish), so a
 * stale entry just fails publish and the client re-prepares.
 */
fun Route.send(ds: DataSource, sdk: SdkAccess, optimizer: OptimizeQueue, cfg: AppConfig) {
    val cache = PrepareCache()
    val turnkey = cfg.signer == SignerMode.TURNKEY

    post("/users/{userId}/payments/send/prepare") {
        val userId = call.requireUser(ds) ?: return@post
        val body = try {
            call.receive<PrepareBody>()
        } catch (e: Exception) {
            call.respondError(HttpStatusCode.BadRequest, ErrorCodes.BAD_REQUEST, "malformed body: ${e.message}")
            return@post
        }
        val amount: BigInteger? = body.amount_sats?.let { BigInteger.fromLong(it) }
        val prepareReq = PrepareSendPaymentRequest(
            paymentRequest = PaymentRequest.Input(input = body.payment_request),
            amount = amount,
            tokenIdentifier = null,
            conversionOptions = null,
            feePolicy = null,
        )

        // Seed mode: one connect to prepare; the server will sign at /send.
        if (!turnkey) {
            val prepared: PrepareSendPaymentResponse = try {
                sdk.withUser(userId) { it.prepareSendPayment(prepareReq) }
            } catch (e: SignerMismatchException) {
                throw e // StatusPages → 409 signer_mismatch
            } catch (e: Exception) {
                call.respondError(HttpStatusCode.BadGateway, ErrorCodes.UPSTREAM_UNAVAILABLE, e.message ?: "prepare failed")
                return@post
            }
            val (method, feeSats) = quoteOf(prepared) ?: run {
                call.respondError(HttpStatusCode.BadRequest, ErrorCodes.BAD_REQUEST, UNSUPPORTED_METHOD_MSG)
                return@post
            }
            val prepareId = "ps_" + crockfordEncode(randomBytes(16))
            cache.put(prepareId, PrepareEntry(userId = userId, prepared = prepared))
            call.respond(
                PrepareResult(
                    prepare_id = prepareId,
                    method = method,
                    amount_sats = prepared.amount.longValue(),
                    fee_sats = feeSats,
                )
            )
            return@post
        }

        // Turnkey mode: prepare AND build the unsigned package in a SINGLE
        // connect — each withUser rebuilds the SDK and runs the Turnkey signer
        // handshake, so doing both legs in one block (and reading the spark
        // address from the same users row, via withUserContext) avoids a second
        // full handshake and an extra query on the send hot path. Coop-exit
        // (onchain) needs a confirmation speed at build time; FAST mirrors seed.
        val built: BuiltPackage = try {
            sdk.withUserContext(userId) { s, ctx ->
                val prepared = s.prepareSendPayment(prepareReq)
                val (method, feeSats) = quoteOf(prepared) ?: throw UnsupportedPaymentMethod()
                val options = if (method == "onchain") {
                    BuildTransferPackageOptions.BitcoinAddress(OnchainConfirmationSpeed.FAST)
                } else {
                    null
                }
                val unsigned = s.buildUnsignedTransferPackage(
                    BuildUnsignedTransferPackageRequest(prepareResponse = prepared, options = options)
                )
                BuiltPackage(prepared, method, feeSats, unsigned, ctx.turnkeySparkAddress)
            }
        } catch (e: SignerMismatchException) {
            throw e
        } catch (e: UnsupportedPaymentMethod) {
            call.respondError(HttpStatusCode.BadRequest, ErrorCodes.BAD_REQUEST, UNSUPPORTED_METHOD_MSG)
            return@post
        } catch (e: Exception) {
            call.respondError(HttpStatusCode.BadGateway, ErrorCodes.UPSTREAM_UNAVAILABLE, e.message ?: "prepare failed")
            return@post
        }

        val (kind, prepareTransfer) = when (val unsigned = built.unsigned) {
            is UnsignedTransferPackage.Transfer -> "transfer" to unsigned.prepareTransfer
            is UnsignedTransferPackage.Swap -> "swap" to unsigned.prepareTransfer
            is UnsignedTransferPackage.Token -> {
                call.respondError(HttpStatusCode.BadRequest, ErrorCodes.BAD_REQUEST, "token sends are out of scope")
                return@post
            }
        }
        // The destination comes from the prepare response, not the package, so it
        // is the same whether this leg is the send or a denomination swap that
        // precedes it — the approval screen shows where the payment lands even
        // when a swap is signed first.
        val destination = built.prepared.paymentMethod.destination()

        val sparkAddress = built.sparkAddress ?: run {
            call.respondError(HttpStatusCode.InternalServerError, ErrorCodes.INTERNAL, "user has no turnkey spark address")
            return@post
        }

        val prepareId = "ps_" + crockfordEncode(randomBytes(16))
        cache.put(prepareId, PrepareEntry(userId = userId, prepared = built.prepared, unsigned = built.unsigned))

        call.respond(
            PrepareResult(
                prepare_id = prepareId,
                method = built.method,
                amount_sats = built.prepared.amount.longValue(),
                fee_sats = built.feeSats,
                kind = kind,
                sign_with = sparkAddress,
                transfer = prepareTransfer.toDto(),
                destination = destination,
            )
        )
    }

    // Seed mode: the server signs and sends.
    post("/users/{userId}/payments/send") {
        val userId = call.requireUser(ds) ?: return@post
        if (turnkey) {
            call.respondError(
                HttpStatusCode.Conflict,
                ErrorCodes.BAD_REQUEST,
                "this deployment signs sends client-side; use POST .../send/publish",
            )
            return@post
        }
        val body = try {
            call.receive<SendBody>()
        } catch (e: Exception) {
            call.respondError(HttpStatusCode.BadRequest, ErrorCodes.BAD_REQUEST, "malformed body: ${e.message}")
            return@post
        }

        val entry = cache.take(body.prepare_id)
        if (entry == null) {
            call.respondError(HttpStatusCode.NotFound, ErrorCodes.NOT_FOUND, "prepare_id not found or expired")
            return@post
        }
        if (entry.userId != userId) {
            call.respondError(HttpStatusCode.Forbidden, ErrorCodes.FORBIDDEN, "prepare_id does not belong to this user")
            return@post
        }

        val options: SendPaymentOptions? = when (entry.prepared.paymentMethod) {
            is SendPaymentMethod.Bolt11Invoice ->
                SendPaymentOptions.Bolt11Invoice(preferSpark = false, completionTimeoutSecs = null)
            is SendPaymentMethod.BitcoinAddress ->
                SendPaymentOptions.BitcoinAddress(confirmationSpeed = OnchainConfirmationSpeed.FAST)
            else -> null
        }
        val idempotencyKey = call.request.headers["Idempotency-Key"]?.takeIf { it.isNotBlank() }

        try {
            val resp = sdk.withUser(userId) {
                it.sendPayment(
                    SendPaymentRequest(prepareResponse = entry.prepared, options = options, idempotencyKey = idempotencyKey)
                )
            }
            optimizer.enqueue(userId)
            call.respond(
                SendResult(
                    payment_id = resp.payment.id,
                    status = resp.payment.toDto().status,
                    fee_sats = feeOf(entry.prepared, resp.payment.fees.longValue()),
                )
            )
        } catch (e: SignerMismatchException) {
            throw e
        } catch (e: Exception) {
            log.warn("send failed user={} type={}: {}", userId, e::class.simpleName, e.message, e)
            call.respondError(HttpStatusCode.BadGateway, ErrorCodes.UPSTREAM_UNAVAILABLE, e.message ?: "send failed")
        }
    }

    // Turnkey mode: the client signed SPARK_PREPARE_TRANSFER with its passkey
    // (send) or session (swap); the server publishes the signed package.
    post("/users/{userId}/payments/send/publish") {
        val userId = call.requireUser(ds) ?: return@post
        if (!turnkey) {
            call.respondError(
                HttpStatusCode.Conflict,
                ErrorCodes.BAD_REQUEST,
                "this deployment signs sends server-side; use POST .../send",
            )
            return@post
        }
        val body = try {
            call.receive<PublishBody>()
        } catch (e: Exception) {
            call.respondError(HttpStatusCode.BadRequest, ErrorCodes.BAD_REQUEST, "malformed body: ${e.message}")
            return@post
        }

        val entry = cache.take(body.prepare_id)
        if (entry == null) {
            call.respondError(HttpStatusCode.NotFound, ErrorCodes.NOT_FOUND, "prepare_id not found or expired")
            return@post
        }
        if (entry.userId != userId) {
            call.respondError(HttpStatusCode.Forbidden, ErrorCodes.FORBIDDEN, "prepare_id does not belong to this user")
            return@post
        }
        val unsigned = entry.unsigned ?: run {
            call.respondError(HttpStatusCode.BadRequest, ErrorCodes.BAD_REQUEST, "prepare_id has no unsigned package")
            return@post
        }

        val signedPackage = SignedTransferPackage(
            unsigned_ = unsigned,
            signature = TransferSignature.Transfer(signed_ = body.signed.toExternalPreparedTransfer()),
        )

        try {
            val resp = sdk.withUser(userId) {
                it.publishSignedTransferPackage(
                    PublishSignedTransferPackageRequest(signedPackage = signedPackage)
                )
            }
            when (resp) {
                is PublishSignedTransferPackageResponse.SwapCompleted -> {
                    // The denomination swap settled; the leaf set changed. The
                    // client re-prepares and signs the next package.
                    call.respond(PublishResult(swap_completed = true))
                }
                is PublishSignedTransferPackageResponse.PaymentSent -> {
                    optimizer.enqueue(userId)
                    call.respond(
                        PublishResult(
                            payment_id = resp.payment.id,
                            status = resp.payment.toDto().status,
                            fee_sats = feeOf(entry.prepared, resp.payment.fees.longValue()),
                        )
                    )
                }
            }
        } catch (e: SignerMismatchException) {
            throw e
        } catch (e: Exception) {
            log.warn("publish failed user={} type={}: {}", userId, e::class.simpleName, e.message, e)
            call.respondError(HttpStatusCode.BadGateway, ErrorCodes.UPSTREAM_UNAVAILABLE, e.message ?: "publish failed")
        }
    }
}

private const val UNSUPPORTED_METHOD_MSG =
    "spark/cross-chain payments are out of scope in v1; use a bolt11 invoice or bitcoin address"

/** Thrown inside the turnkey prepare block for an out-of-scope payment method so
 * the single connect can unwind and the handler can answer 400. */
private class UnsupportedPaymentMethod : RuntimeException()

/** The prepare + unsigned package produced in one turnkey connect, plus the
 * spark address read from the same users row. */
private data class BuiltPackage(
    val prepared: PrepareSendPaymentResponse,
    val method: String,
    val feeSats: Long,
    val unsigned: UnsignedTransferPackage,
    val sparkAddress: String?,
)

/** Quote method + fee from a prepared payment, or null for an out-of-scope
 * (spark/cross-chain) method. */
private fun quoteOf(prepared: PrepareSendPaymentResponse): Pair<String, Long>? =
    when (val pm = prepared.paymentMethod) {
        is SendPaymentMethod.Bolt11Invoice -> "bolt11" to pm.lightningFeeSats.toLong()
        is SendPaymentMethod.BitcoinAddress -> {
            val q = pm.feeQuote.speedFast
            "onchain" to (q.userFeeSat + q.l1BroadcastFeeSat).toLong()
        }
        else -> null
    }

/** Quote fee from the prepared method, falling back to the settled fee. */
private fun feeOf(prepared: PrepareSendPaymentResponse, fallback: Long): Long =
    when (val pm = prepared.paymentMethod) {
        is SendPaymentMethod.Bolt11Invoice -> pm.lightningFeeSats.toLong()
        is SendPaymentMethod.BitcoinAddress -> {
            val q = pm.feeQuote.speedFast
            (q.userFeeSat + q.l1BroadcastFeeSat).toLong()
        }
        else -> fallback
    }

// The destination the payment pays, as the user expressed it — the invoice or
// address they entered — for display at approval. Read from the prepare
// response so it is available for the send leg and any preceding swap alike.
private fun SendPaymentMethod.destination(): String? = when (this) {
    is SendPaymentMethod.Bolt11Invoice -> invoiceDetails.invoice.bolt11
    is SendPaymentMethod.BitcoinAddress -> address.address
    is SendPaymentMethod.SparkAddress -> address
    is SendPaymentMethod.SparkInvoice -> sparkInvoiceDetails.invoice
    is SendPaymentMethod.CrossChainAddress -> recipientAddress
}

// --- FFI <-> wire mapping (mirrors the SDK's Turnkey signer) ---------------

private fun ExternalPrepareTransferRequest.toDto(): TransferDto =
    TransferDto(
        transfer_id = transferId,
        receiver_public_key = bytesToHex(receiverPublicKey),
        threshold = threshold.toLong(),
        leaves = leaves.map { LeafDto(leaf_id = it.nodeId.id, new_leaf_id = it.newLeafId.id) },
        operator_recipients = operatorRecipients.map {
            // operatorId is the hex of the FROST identifier; encryptionPublicKey
            // is the operator's ECIES key — matching spark_signer.rs.
            OperatorRecipientDto(
                operator_id = bytesToHex(it.identifier.bytes),
                encryption_public_key = bytesToHex(it.publicKey),
            )
        },
    )

private fun SignedTransferDto.toExternalPreparedTransfer(): ExternalPreparedTransfer =
    ExternalPreparedTransfer(
        operatorPackages = operator_packages.map {
            ExternalOperatorPackage(
                operatorIdentifier = ExternalIdentifier(hexToBytes(it.operator_id)),
                encryptedPackage = hexToBytes(it.encrypted_package),
            )
        },
        newLeafKeys = new_leaf_keys.map {
            ExternalNewLeafKey(
                nodeId = ExternalTreeNodeId(it.leaf_id),
                newSigningPublicKey = hexToBytes(it.public_key),
            )
        },
        transferUserSignature = EcdsaSignatureBytes(hexToBytes(transfer_user_signature)),
    )

// --- prepare cache ---------------------------------------------------------

private const val PREPARE_TTL_MS = 60_000L

internal data class PrepareEntry(
    val userId: String,
    val prepared: PrepareSendPaymentResponse,
    /** The unsigned package the client signs (turnkey mode only). */
    val unsigned: UnsignedTransferPackage? = null,
    val createdAtMs: Long = System.currentTimeMillis(),
)

internal class PrepareCache {
    private val map = ConcurrentHashMap<String, PrepareEntry>()

    init {
        Thread({
            while (!Thread.currentThread().isInterrupted) {
                try {
                    Thread.sleep(30_000L)
                    val cutoff = System.currentTimeMillis() - PREPARE_TTL_MS
                    map.entries.removeIf { it.value.createdAtMs < cutoff }
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    break
                }
            }
        }, "prepare-cache-sweeper").apply { isDaemon = true }.start()
    }

    fun put(id: String, entry: PrepareEntry) {
        map[id] = entry
    }

    /** One-shot read. Returns null if missing or expired. Also removes the entry. */
    fun take(id: String): PrepareEntry? {
        val e = map.remove(id) ?: return null
        if (System.currentTimeMillis() - e.createdAtMs > PREPARE_TTL_MS) return null
        return e
    }
}
