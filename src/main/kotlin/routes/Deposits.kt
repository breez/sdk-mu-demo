package routes

import ErrorCodes
import SdkAccess
import SignerMismatchException
import breez_sdk_spark.ClaimDepositRequest
import breez_sdk_spark.DepositInfo
import breez_sdk_spark.Fee
import breez_sdk_spark.ListUnclaimedDepositsRequest
import breez_sdk_spark.RefundDepositRequest
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import javax.sql.DataSource
import kotlinx.serialization.Serializable
import requireUser
import respondError

@Serializable
data class DepositDto(
    val txid: String,
    val vout: Long,
    val amount_sats: Long,
    val is_mature: Boolean,
    val refund_tx: String? = null,
    val refund_tx_id: String? = null,
    val claim_error: String? = null,
)

fun DepositInfo.toDto(): DepositDto = DepositDto(
    txid = txid,
    vout = vout.toLong(),
    amount_sats = amountSats.toLong(),
    is_mature = isMature,
    refund_tx = refundTx,
    refund_tx_id = refundTxId,
    claim_error = claimError?.toString(),
)

@Serializable
data class ListDepositsResponse(val deposits: List<DepositDto>)

@Serializable
data class ClaimDepositResponseBody(val payment: PaymentDto)

@Serializable
data class RefundDepositBody(
    val destination_address: String,
    val fee_rate_sat_vb: Long,
)

@Serializable
data class RefundDepositResponseBody(
    val tx_id: String,
    val tx_hex: String,
)

fun Route.deposits(ds: DataSource, sdk: SdkAccess) {
    get("/users/{userId}/deposits/unclaimed") {
        val userId = call.requireUser(ds) ?: return@get
        try {
            // ListUnclaimedDepositsRequest is generated as a Kotlin `object`
            // (empty UniFFI record) — passed by name, no `()`.
            val resp = sdk.withUser(userId) { it.listUnclaimedDeposits(ListUnclaimedDepositsRequest) }
            call.respond(ListDepositsResponse(deposits = resp.deposits.map { it.toDto() }))
        } catch (e: SignerMismatchException) {
            throw e // StatusPages → 409 signer_mismatch
        } catch (e: Exception) {
            call.respondError(
                HttpStatusCode.BadGateway,
                ErrorCodes.UPSTREAM_UNAVAILABLE,
                e.message ?: "SDK error",
            )
        }
    }

    post("/users/{userId}/deposits/{outpoint}/claim") {
        val userId = call.requireUser(ds) ?: return@post
        val outpoint = parseOutpoint(call.parameters["outpoint"]) ?: run {
            call.respondError(
                HttpStatusCode.BadRequest,
                ErrorCodes.BAD_REQUEST,
                "outpoint must be of the form '<txid>:<vout>'",
            )
            return@post
        }

        try {
            val resp = sdk.withUser(userId) {
                it.claimDeposit(
                    ClaimDepositRequest(
                        txid = outpoint.first,
                        vout = outpoint.second,
                        maxFee = null,
                    )
                )
            }
            call.respond(ClaimDepositResponseBody(payment = resp.payment.toDto()))
        } catch (e: SignerMismatchException) {
            throw e // StatusPages → 409 signer_mismatch
        } catch (e: Exception) {
            call.respondError(
                HttpStatusCode.BadGateway,
                ErrorCodes.UPSTREAM_UNAVAILABLE,
                e.message ?: "claim failed",
            )
        }
    }

    post("/users/{userId}/deposits/{outpoint}/refund") {
        val userId = call.requireUser(ds) ?: return@post
        val outpoint = parseOutpoint(call.parameters["outpoint"]) ?: run {
            call.respondError(
                HttpStatusCode.BadRequest,
                ErrorCodes.BAD_REQUEST,
                "outpoint must be of the form '<txid>:<vout>'",
            )
            return@post
        }
        val body = try {
            call.receive<RefundDepositBody>()
        } catch (e: Exception) {
            call.respondError(HttpStatusCode.BadRequest, ErrorCodes.BAD_REQUEST, "malformed body: ${e.message}")
            return@post
        }

        try {
            val resp = sdk.withUser(userId) {
                it.refundDeposit(
                    RefundDepositRequest(
                        txid = outpoint.first,
                        vout = outpoint.second,
                        destinationAddress = body.destination_address,
                        fee = Fee.Rate(satPerVbyte = body.fee_rate_sat_vb.toULong()),
                    )
                )
            }
            call.respond(RefundDepositResponseBody(tx_id = resp.txId, tx_hex = resp.txHex))
        } catch (e: SignerMismatchException) {
            throw e // StatusPages → 409 signer_mismatch
        } catch (e: Exception) {
            call.respondError(
                HttpStatusCode.BadGateway,
                ErrorCodes.UPSTREAM_UNAVAILABLE,
                e.message ?: "refund failed",
            )
        }
    }
}

/**
 * Parse `<txid>:<vout>` into the pieces. txid is 64 lowercase hex chars; vout
 * is a non-negative u32. Returns `null` on malformed input — caller maps to 400.
 */
private fun parseOutpoint(raw: String?): Pair<String, UInt>? {
    if (raw.isNullOrBlank()) return null
    val parts = raw.split(':', limit = 2)
    if (parts.size != 2) return null
    val txid = parts[0]
    if (txid.length != 64 || !txid.all { it.isDigit() || it in 'a'..'f' || it in 'A'..'F' }) return null
    val vout = parts[1].toUIntOrNull() ?: return null
    return txid.lowercase() to vout
}
