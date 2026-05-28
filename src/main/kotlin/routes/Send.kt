package routes

import ErrorCodes
import OptimizeQueue
import SdkAccess
import SyncQueue
import breez_sdk_spark.OnchainConfirmationSpeed
import breez_sdk_spark.PrepareSendPaymentRequest
import breez_sdk_spark.PrepareSendPaymentResponse
import breez_sdk_spark.SendPaymentMethod
import breez_sdk_spark.SendPaymentOptions
import breez_sdk_spark.SendPaymentRequest
import com.ionspin.kotlin.bignum.integer.BigInteger
import crockfordEncode
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import java.util.concurrent.ConcurrentHashMap
import javax.sql.DataSource
import kotlinx.serialization.Serializable
import randomBytes
import requireUser
import respondError

@Serializable
data class PrepareBody(
    val payment_request: String,
    val amount_sats: Long? = null,
)

@Serializable
data class PrepareResult(
    val prepare_id: String,
    val method: String,         // "bolt11" | "onchain"
    val amount_sats: Long,
    val fee_sats: Long,
)

@Serializable
data class SendBody(val prepare_id: String)

@Serializable
data class SendResult(
    val payment_id: String,
    val status: String,
    val fee_sats: Long,
)

/**
 * Two-step send: prepare returns a quote and stores the prepare response in
 * an in-memory cache keyed by a random `prepare_id`. Send looks it up and
 * confirms. 60s TTL — process restart or expiry means the client re-prepares.
 *
 * Prepare cache is per-process; we explicitly bind each entry to a `userId`
 * so a leaked id can't be confirmed under a different principal.
 */
fun Route.send(ds: DataSource, sdk: SdkAccess, optimizer: OptimizeQueue, syncer: SyncQueue) {
    val cache = PrepareCache()

    post("/users/{userId}/payments/send/prepare") {
        val userId = call.requireUser(ds) ?: return@post
        val body = try {
            call.receive<PrepareBody>()
        } catch (e: Exception) {
            call.respondError(HttpStatusCode.BadRequest, ErrorCodes.BAD_REQUEST, "malformed body: ${e.message}")
            return@post
        }
        val amount: BigInteger? = body.amount_sats?.let { BigInteger.fromLong(it) }

        val prepared: PrepareSendPaymentResponse = try {
            sdk.withUser(userId) {
                it.prepareSendPayment(
                    PrepareSendPaymentRequest(
                        paymentRequest = body.payment_request,
                        amount = amount,
                        tokenIdentifier = null,
                        conversionOptions = null,
                        feePolicy = null,
                    )
                )
            }
        } catch (e: Exception) {
            call.respondError(
                HttpStatusCode.BadGateway,
                ErrorCodes.UPSTREAM_UNAVAILABLE,
                e.message ?: "prepare failed",
            )
            return@post
        }

        val (method, feeSats) = when (val pm = prepared.paymentMethod) {
            is SendPaymentMethod.Bolt11Invoice -> "bolt11" to pm.lightningFeeSats.toLong()
            is SendPaymentMethod.BitcoinAddress -> {
                val q = pm.feeQuote.speedFast
                "onchain" to (q.userFeeSat + q.l1BroadcastFeeSat).toLong()
            }
            is SendPaymentMethod.SparkAddress, is SendPaymentMethod.SparkInvoice -> {
                call.respondError(
                    HttpStatusCode.BadRequest,
                    ErrorCodes.BAD_REQUEST,
                    "spark payments are out of scope in v1; use a bolt11 invoice or bitcoin address",
                )
                return@post
            }
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
    }

    post("/users/{userId}/payments/send") {
        val userId = call.requireUser(ds) ?: return@post
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

        // Returns as soon as the SSP accepts the payment; terminal status
        // (succeeded/failed) is delivered via the WS events stream.
        val options: SendPaymentOptions? = when (entry.prepared.paymentMethod) {
            is SendPaymentMethod.Bolt11Invoice ->
                SendPaymentOptions.Bolt11Invoice(preferSpark = false, completionTimeoutSecs = null)
            is SendPaymentMethod.BitcoinAddress ->
                SendPaymentOptions.BitcoinAddress(confirmationSpeed = OnchainConfirmationSpeed.FAST)
            else -> null
        }

        // Idempotency-Key passes straight through to the SDK. SDK requires a
        // valid UUID; client is responsible for that. v1 is a passthrough.
        val idempotencyKey = call.request.headers["Idempotency-Key"]?.takeIf { it.isNotBlank() }

        try {
            val resp = sdk.withUser(userId) {
                it.sendPayment(
                    SendPaymentRequest(
                        prepareResponse = entry.prepared,
                        options = options,
                        idempotencyKey = idempotencyKey,
                    )
                )
            }
            val feeSats = when (val pm = entry.prepared.paymentMethod) {
                is SendPaymentMethod.Bolt11Invoice -> pm.lightningFeeSats.toLong()
                is SendPaymentMethod.BitcoinAddress -> {
                    val q = pm.feeQuote.speedFast
                    (q.userFeeSat + q.l1BroadcastFeeSat).toLong()
                }
                else -> resp.payment.fees.longValue()
            }
            // Outgoing payment likely changed the leaf set — queue optimization.
            optimizer.enqueue(userId)

            call.respond(
                SendResult(
                    payment_id = resp.payment.id,
                    // Reuse the canonical PaymentStatus → wire-string mapping
                    // so /send and the events stream never diverge.
                    status = resp.payment.toDto().status,
                    fee_sats = feeSats,
                )
            )
        } catch (e: Exception) {
            // A failed send can leave leaves locked then returned by Spark in a
            // state the local store doesn't reflect, and no webhook fires for
            // that transition. Sync to reconcile before the next send attempt.
            syncer.enqueue(userId)
            call.respondError(
                HttpStatusCode.BadGateway,
                ErrorCodes.UPSTREAM_UNAVAILABLE,
                e.message ?: "send failed",
            )
        }
    }
}

// --- prepare cache ---------------------------------------------------------

private const val PREPARE_TTL_MS = 60_000L

internal data class PrepareEntry(
    val userId: String,
    val prepared: PrepareSendPaymentResponse,
    val createdAtMs: Long = System.currentTimeMillis(),
)

internal class PrepareCache {
    private val map = ConcurrentHashMap<String, PrepareEntry>()

    init {
        // Background sweeper: drops expired entries every 30s. A daemon
        // thread so JVM shutdown isn't blocked. Cheap — entries are small.
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
