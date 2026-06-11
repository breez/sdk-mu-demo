package routes

import ErrorCodes
import SdkAccess
import SignerMismatchException
import breez_sdk_spark.ReceivePaymentMethod
import breez_sdk_spark.ReceivePaymentRequest
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import javax.sql.DataSource
import kotlinx.serialization.Serializable
import requireUser
import respondError

@Serializable
data class ReceiveRequestBody(
    val method: String,                 // "bolt11" | "onchain"
    val amount_sats: Long? = null,
    val description: String? = null,
    val expiry_secs: Int? = null,       // bolt11 only; SDK max is 30 days
)

@Serializable
data class ReceiveResponseBody(
    val payment_request: String,        // bolt11 invoice string, or btc address
    val fee_sats: Long,
)

fun Route.receive(ds: DataSource, sdk: SdkAccess) {
    post("/users/{userId}/payments/receive") {
        val userId = call.requireUser(ds) ?: return@post

        val body = try {
            call.receive<ReceiveRequestBody>()
        } catch (e: Exception) {
            call.respondError(HttpStatusCode.BadRequest, ErrorCodes.BAD_REQUEST, "malformed body: ${e.message}")
            return@post
        }

        val method: ReceivePaymentMethod = when (body.method.lowercase()) {
            "bolt11" -> ReceivePaymentMethod.Bolt11Invoice(
                description = body.description ?: "",
                amountSats = body.amount_sats?.toULong(),
                expirySecs = body.expiry_secs?.toUInt(),
                paymentHash = null,
            )
            "onchain" -> ReceivePaymentMethod.BitcoinAddress(newAddress = null)
            else -> {
                call.respondError(
                    HttpStatusCode.BadRequest,
                    ErrorCodes.BAD_REQUEST,
                    "method must be 'bolt11' or 'onchain'",
                )
                return@post
            }
        }

        try {
            val resp = sdk.withUser(userId) {
                it.receivePayment(ReceivePaymentRequest(paymentMethod = method))
            }
            call.respond(
                ReceiveResponseBody(
                    payment_request = resp.paymentRequest,
                    fee_sats = resp.fee.longValue(),
                )
            )
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
}
