import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow

/**
 * Per-user, in-process event fan-out. The publisher side is [EventBridge]
 * (attached inside every `SdkAccess.withUser`); the subscriber side is the
 * WS handler at `GET /users/{id}/events`.
 *
 * Lazy-created per userId. Never evicted — flows are tiny and a restart
 * resets the whole map. Single-machine only; multi-machine swaps in Redis
 * pubsub / Postgres LISTEN-NOTIFY.
 */
class EventBus {
    private val flows = ConcurrentHashMap<String, MutableSharedFlow<Envelope>>()

    fun publish(userId: String, envelope: Envelope) {
        // tryEmit always succeeds under DROP_OLDEST; the buffer absorbs
        // short bursts, slow subscribers lose the oldest events. Publishers
        // (sync / send / claim handlers) never block.
        flowFor(userId).tryEmit(envelope)
    }

    fun subscribe(userId: String): SharedFlow<Envelope> = flowFor(userId)

    private fun flowFor(userId: String): MutableSharedFlow<Envelope> =
        flows.computeIfAbsent(userId) {
            MutableSharedFlow(
                replay = 0,
                extraBufferCapacity = 64,
                onBufferOverflow = BufferOverflow.DROP_OLDEST,
            )
        }
}
