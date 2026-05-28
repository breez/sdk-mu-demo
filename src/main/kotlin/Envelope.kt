import breez_sdk_spark.SdkEvent
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import routes.DepositDto
import routes.PaymentDto
import routes.toDto

/**
 * Wire shape for the events stream. Polymorphic via the `"type"`
 * discriminator (configured on the Json instance in routes/Events.kt).
 *
 * Variants intentionally cover only what the v1 UI consumes:
 * payment-lifecycle transitions and deposit state changes. `Synced`,
 * `Optimization`, `LightningAddressChanged` are filtered out at the
 * bridge (see [fromSdk]).
 */
@Serializable
sealed class Envelope {
    @Serializable
    @SerialName("payment_succeeded")
    data class PaymentSucceeded(val payment: PaymentDto) : Envelope()

    @Serializable
    @SerialName("payment_pending")
    data class PaymentPending(val payment: PaymentDto) : Envelope()

    @Serializable
    @SerialName("payment_failed")
    data class PaymentFailed(val payment: PaymentDto) : Envelope()

    @Serializable
    @SerialName("new_deposits")
    data class NewDeposits(val deposits: List<DepositDto>) : Envelope()

    @Serializable
    @SerialName("claimed_deposits")
    data class ClaimedDeposits(val deposits: List<DepositDto>) : Envelope()

    @Serializable
    @SerialName("unclaimed_deposits")
    data class UnclaimedDeposits(val deposits: List<DepositDto>) : Envelope()

    companion object {
        fun fromSdk(e: SdkEvent): Envelope? = when (e) {
            is SdkEvent.PaymentSucceeded -> PaymentSucceeded(e.payment.toDto())
            is SdkEvent.PaymentPending -> PaymentPending(e.payment.toDto())
            is SdkEvent.PaymentFailed -> PaymentFailed(e.payment.toDto())
            is SdkEvent.NewDeposits -> NewDeposits(e.newDeposits.map { it.toDto() })
            is SdkEvent.ClaimedDeposits -> ClaimedDeposits(e.claimedDeposits.map { it.toDto() })
            is SdkEvent.UnclaimedDeposits -> UnclaimedDeposits(e.unclaimedDeposits.map { it.toDto() })
            else -> null
        }
    }
}
