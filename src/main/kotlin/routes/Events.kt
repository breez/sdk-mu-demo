package routes

import Envelope
import EventBus
import io.ktor.server.application.call
import io.ktor.server.routing.Route
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import javax.sql.DataSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import lookupUserByKeyHash
import org.slf4j.LoggerFactory
import sha256Hex

private val log = LoggerFactory.getLogger("Events")

private val eventsJson = Json {
    encodeDefaults = true
    classDiscriminator = "type"
}

/**
 * `GET /users/{userId}/events` — WebSocket upgrade carrying [Envelope]
 * frames. Auth via `?api_key=...` (browsers can't set Authorization on
 * `new WebSocket()`).
 *
 * Per-connection outbox of 64. If it fills (slow / dead client that
 * hasn't read), we close 1008 so the client reconnects and refetches via
 * REST. The bus's own per-subscriber buffer absorbs short publish bursts;
 * the outbox is the boundary at which we give up.
 */
fun Route.events(ds: DataSource, bus: EventBus) {
    webSocket("/users/{userId}/events") {
        val pathUserId = call.parameters["userId"]
        if (pathUserId.isNullOrBlank()) {
            close(CloseReason(CloseReason.Codes.PROTOCOL_ERROR, "missing userId"))
            return@webSocket
        }
        val apiKey = call.request.queryParameters["api_key"]
        if (apiKey.isNullOrBlank()) {
            close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "missing api_key"))
            return@webSocket
        }
        val principal = lookupUserByKeyHash(ds, sha256Hex(apiKey.toByteArray(Charsets.UTF_8)))
        if (principal == null || principal != pathUserId) {
            close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "forbidden"))
            return@webSocket
        }

        val outbox = Channel<Envelope>(capacity = 64)
        val pump = launch {
            try {
                bus.subscribe(pathUserId).collect { ev ->
                    val r = outbox.trySend(ev)
                    if (r.isFailure) {
                        outbox.close()
                        throw CancellationException("outbox overflow")
                    }
                }
            } catch (_: CancellationException) {
                // expected on overflow / WS close
            }
        }

        var overflowed = false
        try {
            for (ev in outbox) {
                send(Frame.Text(eventsJson.encodeToString(Envelope.serializer(), ev)))
            }
            // outbox.close() inside pump → loop exits naturally on overflow
            overflowed = true
        } finally {
            pump.cancelAndJoin()
            if (overflowed) {
                close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "buffer overflow"))
                log.info("ws closed (overflow) user={}", pathUserId)
            }
        }
    }
}
