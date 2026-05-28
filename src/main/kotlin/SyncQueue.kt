import breez_sdk_spark.BreezSdk
import breez_sdk_spark.SyncWalletRequest
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

/**
 * Per-user wallet-sync queue. A failed send can leave leaves locked on the
 * Spark side and later returned in a state the local store doesn't reflect, and
 * no webhook fires for that transition. Enqueuing a sync after a failed payment
 * reconciles the local view so the next send sees the real leaf set.
 *
 * Shares all the dedup/worker machinery with [OptimizeQueue] via [UserWorkQueue].
 */
class SyncQueue(
    sdk: SdkAccess,
    concurrency: Int = 4,
    timeout: Duration = 2.minutes,
) : UserWorkQueue(sdk, "sync", concurrency, timeout) {

    override suspend fun work(sdk: BreezSdk): String {
        sdk.syncWallet(SyncWalletRequest)
        return "ok"
    }
}
