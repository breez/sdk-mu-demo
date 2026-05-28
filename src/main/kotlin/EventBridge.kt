import breez_sdk_spark.EventListener
import breez_sdk_spark.SdkEvent
import org.slf4j.LoggerFactory

/**
 * SDK → bus bridge. Attached inside every `SdkAccess.withUser` so events
 * emitted by that per-request SDK instance flow into the user's bus
 * partition. Events outside the relayed set (see [Envelope.fromSdk]) are
 * dropped.
 */
class EventBridge(
    private val userId: String,
    private val bus: EventBus,
) : EventListener {
    private val log = LoggerFactory.getLogger(javaClass)

    override suspend fun onEvent(e: SdkEvent) {
        Envelope.fromSdk(e)?.let {
            bus.publish(userId, it)
            log.info("event published user={} type={}", userId, it::class.simpleName)
        }
    }
}
