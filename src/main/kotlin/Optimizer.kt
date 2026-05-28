import breez_sdk_spark.BreezSdk
import breez_sdk_spark.OptimizationMode
import breez_sdk_spark.OptimizationOutcome
import breez_sdk_spark.OptimizeLeavesRequest
import breez_sdk_spark.SdkException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

/**
 * Per-user leaf-optimization queue. Each event that may have left the wallet
 * with sub-optimal leaves (incoming sync from a webhook, outgoing payment)
 * enqueues the user id; the shared [UserWorkQueue] machinery drains it and runs
 * `optimizeLeaves` in `Full` mode, a `suspend` call that returns once the run
 * finishes (`Completed`; `roundsExecuted == 0` means already optimal).
 */
class OptimizeQueue(
    sdk: SdkAccess,
    concurrency: Int = 4,
    timeout: Duration = 5.minutes,
) : UserWorkQueue(sdk, "optimize", concurrency, timeout) {

    override suspend fun work(sdk: BreezSdk): String {
        // Concurrency note: an in-flight Full run reserves leaves and can make
        // a concurrent send for the same user wait. For lower send latency
        // under load, switch to `OptimizeLeavesRequest(mode = SINGLE_ROUND)`
        // and loop while the outcome is `InProgress`, breaking out if a send is
        // pending for this user — round-granular cancellation bounds the wait
        // to one swap. Not done here to keep the reference impl simple.
        val outcome = sdk.optimizeLeaves(OptimizeLeavesRequest(mode = OptimizationMode.FULL)).outcome
        return "outcome=${outcome.label()}"
    }

    override fun handleException(userId: String, e: Exception): Boolean {
        // Expected, benign: the SDK preempted this run to free leaves for a
        // concurrent payment. Not a failure — the next event re-enqueues.
        if (e is SdkException.OptimizationCancelled) {
            log.info("optimize preempted by payment user={}", userId)
            return true
        }
        return false
    }
}

private fun OptimizationOutcome.label(): String = when (this) {
    is OptimizationOutcome.Completed -> "completed(rounds=${roundsExecuted})"
    is OptimizationOutcome.InProgress -> "in_progress"
}
