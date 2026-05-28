import breez_sdk_spark.BreezSdk
import kotlin.time.Duration
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
 * Per-user background work queue. An event that needs a follow-up SDK call for
 * a user (leaf optimization after a payment, wallet sync after a failed send)
 * enqueues the user id; a small pool of worker coroutines drains the queue and
 * runs the subclass's [work] over a fresh per-user SDK.
 *
 * Dedup is critical: a burst of events must not fire N runs for the same user.
 * We track every userId that is queued or in-flight in a single mutex-guarded
 * set; `enqueue` is a no-op if the id is already there.
 *
 * State is purely in-memory. Restart drops queued jobs; the next event for that
 * user re-enqueues. Good enough for a reference deployment.
 */
abstract class UserWorkQueue(
    private val sdk: SdkAccess,
    private val name: String,
    concurrency: Int,
    private val timeout: Duration,
) {
    protected val log = LoggerFactory.getLogger(javaClass)
    // Independent scope on Dispatchers.IO. We can't inherit from the caller's
    // scope: in Main.kt the boot runs inside `runBlocking` whose single-thread
    // dispatcher is then pinned by `start(wait = true)`, so coroutines
    // launched on it never run.
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob() + CoroutineName("$name-queue"))
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

    /** Enqueue a job for `userId`. No-op if already queued or running. */
    suspend fun enqueue(userId: String) {
        val claimed = mutex.withLock { inflight.add(userId) }
        if (!claimed) {
            log.info("{} enqueue user={} dropped (already queued/running)", name, userId)
            return
        }
        // trySend (not send) so this never suspends: the channel is UNLIMITED,
        // so it always succeeds. A suspending send could be cancelled with the
        // caller's request coroutine between the claim above and delivery here,
        // orphaning userId in `inflight` and silently dropping all future
        // jobs for that user. Roll back the claim if send ever fails.
        if (channel.trySend(userId).isFailure) {
            mutex.withLock { inflight.remove(userId) }
            log.warn("{} enqueue user={} dropped (channel send failed)", name, userId)
        }
    }

    private suspend fun runOnce(userId: String) {
        val started = System.currentTimeMillis()
        try {
            // `work` is a uniffi `suspend` call: it suspends this coroutine
            // until the run terminates (it does NOT pin an IO thread), and
            // `withTimeout` cancellation propagates into it (uniffi drops the
            // Rust future). The timeout is a circuit-breaker bounding a run
            // whose future never completes — e.g. an upstream hang — from
            // holding a semaphore permit indefinitely.
            val label = withTimeout(timeout) {
                sdk.withUser(userId) { work(it) }
            }
            val elapsedMs = System.currentTimeMillis() - started
            log.info("{} user={} {} elapsed_ms={}", name, userId, label, elapsedMs)
        } catch (e: TimeoutCancellationException) {
            log.warn("{} timeout user={} after_ms={}", name, userId, System.currentTimeMillis() - started)
        } catch (e: Exception) {
            if (!handleException(userId, e)) {
                log.warn("{} failed user={}: {}", name, userId, e.message)
            }
        } finally {
            mutex.withLock { inflight.remove(userId) }
        }
    }

    /** Run the work for one user. Returns a short label for the success log line. */
    protected abstract suspend fun work(sdk: BreezSdk): String

    /**
     * Hook for subclass-specific benign exceptions. Return true if the
     * exception was handled (suppresses the generic `failed` warn).
     */
    protected open fun handleException(userId: String, e: Exception): Boolean = false
}
