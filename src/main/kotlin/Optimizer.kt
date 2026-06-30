import breez_sdk_spark.OptimizationMode
import breez_sdk_spark.OptimizationOutcome
import breez_sdk_spark.OptimizeLeavesRequest
import breez_sdk_spark.SdkException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeout
import org.slf4j.LoggerFactory

/**
 * Per-user leaf-optimization queue. Each event that may have left the wallet
 * with sub-optimal leaves (incoming sync from a webhook, outgoing payment)
 * enqueues the user id. A small pool of worker coroutines drains the queue
 * and runs `optimizeLeaves` in `Full` mode, a `suspend` call that returns
 * once the run finishes (`Completed`; `roundsExecuted == 0` means already
 * optimal).
 *
 * Dedup is critical: a burst of payments must not fire N optimizations for
 * the same user. We track every userId that is queued or in-flight in a
 * single mutex-guarded set; `enqueue` is a no-op if the id is already there.
 *
 * State is purely in-memory. Restart drops queued jobs; the next payment for
 * that user re-enqueues. Good enough for a reference deployment.
 */
class OptimizeQueue(
    private val sdk: SdkAccess,
    // Leaf optimization is a swap, which needs SPARK_PREPARE_TRANSFER. Under
    // turnkey delegated access the server's key is denied that activity (only
    // the user's client can sign it), and the SDK exposes no way to hand the
    // optimization package out for client signing — so background optimization
    // is disabled in that mode and enqueue is a no-op.
    private val enabled: Boolean = true,
    concurrency: Int = 4,
    private val timeout: Duration = 5.minutes,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    // Independent scope on Dispatchers.IO. We can't inherit from the caller's
    // scope: in Main.kt the boot runs inside `runBlocking` whose single-thread
    // dispatcher is then pinned by `start(wait = true)`, so coroutines
    // launched on it never run.
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob() + CoroutineName("optimize-queue"))
    private val channel = Channel<String>(capacity = Channel.UNLIMITED)
    private val mutex = Mutex()
    private val inflight = HashSet<String>()
    private val permits = Semaphore(concurrency)

    init {
        scope.launch {
            for (userId in channel) {
                // One coroutine per job; the semaphore caps how many run
                // concurrently. Launching here (rather than awaiting the
                // permit inline) keeps the consumer free to pick up the
                // next id even when all permits are taken.
                scope.launch { permits.withPermit { runOnce(userId) } }
            }
        }
    }

    /** Enqueue an optimize job for `userId`. No-op if disabled, or if already
     * queued or running. */
    suspend fun enqueue(userId: String) {
        if (!enabled) return
        val claimed = mutex.withLock { inflight.add(userId) }
        if (!claimed) {
            log.info("optimize enqueue user={} dropped (already queued/running)", userId)
            return
        }
        // trySend (not send) so this never suspends: the channel is UNLIMITED,
        // so it always succeeds. A suspending send could be cancelled with the
        // caller's request coroutine between the claim above and delivery here,
        // orphaning userId in `inflight` and silently dropping all future
        // optimizes for that user. Roll back the claim if send ever fails.
        if (channel.trySend(userId).isFailure) {
            mutex.withLock { inflight.remove(userId) }
            log.warn("optimize enqueue user={} dropped (channel send failed)", userId)
        }
    }

    private suspend fun runOnce(userId: String) {
        val started = System.currentTimeMillis()
        try {
            // `optimizeLeaves` is a uniffi `suspend` call: it suspends this
            // coroutine until the run terminates (it does NOT pin an IO
            // thread), and `withTimeout` cancellation propagates into it
            // (uniffi drops the Rust future). The timeout is a circuit-breaker
            // bounding a run whose future never completes — e.g. an upstream
            // hang — from holding a semaphore permit indefinitely.
            //
            // Concurrency note: an in-flight Full run reserves leaves and
            // can make a concurrent send for the same user wait. For lower
            // send latency under load, switch to
            // `OptimizeLeavesRequest(mode = SINGLE_ROUND)` and loop while the
            // outcome is `InProgress`, breaking out if a send is pending for
            // this user — round-granular cancellation bounds the wait to one
            // swap. Not done here to keep the reference impl simple.
            val outcome = withTimeout(timeout) {
                sdk.withUser(userId) {
                    it.optimizeLeaves(OptimizeLeavesRequest(mode = OptimizationMode.FULL)).outcome
                }
            }
            val elapsedMs = System.currentTimeMillis() - started
            log.info("optimize user={} outcome={} elapsed_ms={}", userId, outcome.label(), elapsedMs)
        } catch (e: TimeoutCancellationException) {
            log.warn("optimize timeout user={} after_ms={}", userId, System.currentTimeMillis() - started)
        } catch (e: SdkException.OptimizationCancelled) {
            // Expected, benign: the SDK preempted this run to free leaves for a
            // concurrent payment. Not a failure — the next event re-enqueues.
            log.info("optimize preempted by payment user={}", userId)
        } catch (e: Exception) {
            log.warn("optimize failed user={}: {}", userId, e.message)
        } finally {
            mutex.withLock { inflight.remove(userId) }
        }
    }
}

private fun OptimizationOutcome.label(): String = when (this) {
    is OptimizationOutcome.Completed -> "completed(rounds=${roundsExecuted})"
    is OptimizationOutcome.InProgress -> "in_progress"
}
