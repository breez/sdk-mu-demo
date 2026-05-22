package routes

import breez_sdk_spark.Payment
import breez_sdk_spark.PaymentDetails
import breez_sdk_spark.PaymentMethod
import breez_sdk_spark.PaymentStatus
import breez_sdk_spark.PaymentType
import kotlinx.serialization.Serializable

/**
 * Wire payload for a payment. The SDK's `Payment` is a UniFFI record whose
 * fields don't all serialize cleanly via `kotlinx.serialization`, and we want
 * a stable, client-friendly shape regardless. v1 is sats-only — amount/fees
 * are denominated in satoshis and fit comfortably in a Long.
 */
@Serializable
data class PaymentDto(
    val id: String,
    val type: String,        // "send" | "receive"
    val status: String,      // "completed" | "pending" | "failed"
    val method: String,      // "lightning" | "spark" | "deposit" | "withdraw" | "token" | "unknown"
    val amount_sats: Long,
    val fee_sats: Long,
    val timestamp: Long,
    val invoice: String? = null,        // bolt11 invoice for lightning payments
    val description: String? = null,    // lightning / spark invoice description
    val tx_id: String? = null,          // on-chain txid for deposit/withdraw
)

fun Payment.toDto(): PaymentDto {
    val typeStr = when (paymentType) {
        PaymentType.SEND -> "send"
        PaymentType.RECEIVE -> "receive"
    }
    val statusStr = when (status) {
        PaymentStatus.COMPLETED -> "completed"
        PaymentStatus.PENDING -> "pending"
        PaymentStatus.FAILED -> "failed"
    }
    val methodStr = when (method) {
        PaymentMethod.LIGHTNING -> "lightning"
        PaymentMethod.SPARK -> "spark"
        PaymentMethod.TOKEN -> "token"
        PaymentMethod.DEPOSIT -> "deposit"
        PaymentMethod.WITHDRAW -> "withdraw"
        PaymentMethod.UNKNOWN -> "unknown"
    }

    var invoice: String? = null
    var description: String? = null
    var txId: String? = null
    when (val d = details) {
        is PaymentDetails.Lightning -> {
            invoice = d.invoice
            description = d.description
        }
        is PaymentDetails.Spark -> {
            description = d.invoiceDetails?.description
        }
        is PaymentDetails.Deposit -> {
            txId = d.txId
        }
        is PaymentDetails.Withdraw -> {
            txId = d.txId
        }
        is PaymentDetails.Token -> Unit
        null -> Unit
    }

    return PaymentDto(
        id = id,
        type = typeStr,
        status = statusStr,
        method = methodStr,
        amount_sats = amount.longValue(),
        fee_sats = fees.longValue(),
        timestamp = timestamp.toLong(),
        invoice = invoice,
        description = description,
        tx_id = txId,
    )
}
