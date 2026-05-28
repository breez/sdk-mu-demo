package routes

import ErrorCodes
import OptimizeQueue
import SdkAccess
import breez_sdk_spark.SyncWalletRequest
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlinx.serialization.Serializable
import org.slf4j.LoggerFactory
import respondError

private val log = LoggerFactory.getLogger("Webhooks")

@Serializable
data class WebhookAck(val ok: Boolean)

/**
 * `POST /webhooks/sdk/{userId}` — SSP-delivered event.
 *
 *   1. HMAC-SHA256(raw body, WEBHOOK_SECRET) compared (constant-time) to
 *      `X-Spark-Signature`. Mismatch → 401.
 *   2. On match, run a sync for the addressed user.
 *   3. Ack 200. Errors → 5xx so the SSP retries; sync is idempotent.
 *
 * NB: no auth header; the bearer-auth middleware doesn't apply. This route
 * is open + signature-verified.
 */
fun Route.webhooks(webhookSecret: String, sdk: SdkAccess, optimizer: OptimizeQueue) {
    val secretBytes = webhookSecret.toByteArray(Charsets.UTF_8)

    post("/webhooks/sdk/{userId}") {
        val userId = call.parameters["userId"]
        if (userId.isNullOrBlank()) {
            call.respondError(HttpStatusCode.BadRequest, ErrorCodes.BAD_REQUEST, "missing userId")
            return@post
        }

        val raw = try {
            call.receive<ByteArray>()
        } catch (e: Exception) {
            call.respondError(HttpStatusCode.BadRequest, ErrorCodes.BAD_REQUEST, "could not read body: ${e.message}")
            return@post
        }

        val sigHeader = call.request.headers["X-Spark-Signature"]
        if (sigHeader.isNullOrBlank()) {
            call.respondError(HttpStatusCode.Unauthorized, ErrorCodes.UNAUTHORIZED, "missing X-Spark-Signature")
            return@post
        }
        val sigHex = sigHeader.removePrefix("sha256=").trim()
        val expected = hmacSha256Hex(secretBytes, raw)
        if (!constantTimeEquals(sigHex.lowercase(), expected)) {
            call.respondError(HttpStatusCode.Unauthorized, ErrorCodes.UNAUTHORIZED, "bad signature")
            return@post
        }

        try {
            sdk.withUser(userId) { it.syncWallet(SyncWalletRequest) }
        } catch (e: Exception) {
            // Return 5xx so the SSP retries the delivery.
            log.warn("webhook sync failed user={}: {}", userId, e.message)
            call.respondError(
                HttpStatusCode.InternalServerError,
                ErrorCodes.UPSTREAM_UNAVAILABLE,
                e.message ?: "sync failed",
            )
            return@post
        }

        // Incoming payment likely changed the leaf set — queue optimization.
        // Runs asynchronously; never blocks the SSP ack.
        optimizer.enqueue(userId)

        log.info("webhook handled user={} body_bytes={}", userId, raw.size)
        call.respond(WebhookAck(ok = true))
    }
}

private fun hmacSha256Hex(key: ByteArray, message: ByteArray): String {
    val mac = Mac.getInstance("HmacSHA256")
    mac.init(SecretKeySpec(key, "HmacSHA256"))
    val digest = mac.doFinal(message)
    val sb = StringBuilder(digest.size * 2)
    for (b in digest) {
        sb.append(HEX[(b.toInt() ushr 4) and 0xf])
        sb.append(HEX[b.toInt() and 0xf])
    }
    return sb.toString()
}

/**
 * Constant-time comparison. We compare hex strings (same length when both
 * are SHA-256 hashes) by byte; `MessageDigest.isEqual` is documented
 * constant-time since Java 7.
 */
private fun constantTimeEquals(a: String, b: String): Boolean {
    val ba = a.toByteArray(Charsets.UTF_8)
    val bb = b.toByteArray(Charsets.UTF_8)
    return MessageDigest.isEqual(ba, bb)
}

private const val HEX = "0123456789abcdef"
