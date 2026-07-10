package routes

import ErrorCodes
import SdkAccess
import SignerMismatchException
import breez_sdk_spark.GetPaymentRequest
import breez_sdk_spark.ListPaymentsRequest
import breez_sdk_spark.PaymentStatus
import breez_sdk_spark.PaymentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import javax.sql.DataSource
import kotlinx.serialization.Serializable
import requireUser
import respondError

private const val DEFAULT_LIMIT = 50
private const val MAX_LIMIT = 200

@Serializable
data class ListPaymentsResponse(
    val payments: List<PaymentDto>,
    val next_offset: Int?,
)

@Serializable
data class GetPaymentResponse(val payment: PaymentDto)

fun Route.payments(ds: DataSource, sdk: SdkAccess) {
    get("/users/{userId}/payments") {
        val userId = call.requireUser(ds) ?: return@get

        val offset = call.request.queryParameters["offset"]?.toIntOrNull()?.coerceAtLeast(0) ?: 0
        val limit = (call.request.queryParameters["limit"]?.toIntOrNull() ?: DEFAULT_LIMIT)
            .coerceIn(1, MAX_LIMIT)
        val typeFilter = call.request.queryParameters["type"]?.let {
            when (it.lowercase()) {
                "send" -> listOf(PaymentType.SEND)
                "receive" -> listOf(PaymentType.RECEIVE)
                else -> null
            }
        }
        val statusFilter = call.request.queryParameters["status"]?.let {
            when (it.lowercase()) {
                "completed" -> listOf(PaymentStatus.COMPLETED)
                "pending" -> listOf(PaymentStatus.PENDING)
                "failed" -> listOf(PaymentStatus.FAILED)
                else -> null
            }
        }

        val req = ListPaymentsRequest(
            typeFilter = typeFilter,
            statusFilter = statusFilter,
            assetFilter = null,
            paymentDetailsFilter = null,
            fromTimestamp = null,
            toTimestamp = null,
            offset = offset.toUInt(),
            limit = limit.toUInt(),
            sortAscending = false,
        )

        try {
            val resp = sdk.withUser(userId) { it.listPayments(req) }
            val dtos = resp.payments.map { it.toDto() }
            // `list_payments` returns up to `limit` items; if we filled the
            // page exactly, hint the client there may be more.
            val nextOffset = if (dtos.size == limit) offset + limit else null
            call.respond(ListPaymentsResponse(payments = dtos, next_offset = nextOffset))
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

    get("/users/{userId}/payments/{paymentId}") {
        val userId = call.requireUser(ds) ?: return@get
        val paymentId = call.parameters["paymentId"]
        if (paymentId.isNullOrBlank()) {
            call.respondError(HttpStatusCode.BadRequest, ErrorCodes.BAD_REQUEST, "missing paymentId")
            return@get
        }
        try {
            val resp = sdk.withUser(userId) { it.getPayment(GetPaymentRequest(paymentId = paymentId)) }
            call.respond(GetPaymentResponse(payment = resp.payment.toDto()))
        } catch (e: SignerMismatchException) {
            throw e // StatusPages → 409 signer_mismatch
        } catch (e: Exception) {
            val msg = e.message.orEmpty()
            val isNotFound = msg.contains("not found", ignoreCase = true) ||
                msg.contains("NotFound", ignoreCase = false)
            if (isNotFound) {
                call.respondError(HttpStatusCode.NotFound, ErrorCodes.NOT_FOUND, "payment $paymentId not found")
            } else {
                call.respondError(
                    HttpStatusCode.BadGateway,
                    ErrorCodes.UPSTREAM_UNAVAILABLE,
                    e.message ?: "SDK error",
                )
            }
        }
    }
}
