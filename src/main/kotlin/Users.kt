import breez_sdk_spark.RegisterWebhookRequest
import breez_sdk_spark.UpdateUserSettingsRequest
import breez_sdk_spark.WebhookEventType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import java.security.SecureRandom
import javax.sql.DataSource
import kotlinx.serialization.Serializable
import org.slf4j.LoggerFactory

private val log = LoggerFactory.getLogger("Users")

@Serializable
data class CreateUserResponse(
    val user_id: String,
    val api_key: String, // shown once
)

/**
 * `POST /users` — mints a fresh wallet identity.
 *
 *   user_id      = ULID
 *   api_key      = `mu_` + Crockford-base32(32 random bytes)   (shown once)
 *   stored       = SHA-256(api_key) hex
 *
 * Provisioning order matters: we flip the wallet to private mode and
 * register the SSP webhook *before* inserting the user row. If either
 * step fails we 502 and never persist the user, so the client retries
 * with a fresh ULID instead of being left with a public-mode wallet or
 * a row that gets no payment events. The SDK-side state (private flag,
 * webhook registration) lands on a wallet whose userId we never return —
 * unreachable and effectively garbage.
 */
fun Route.users(ds: DataSource, sdk: SdkAccess, cfg: AppConfig) {
    post("/users") {
        val userId = newUlid()
        val apiKey = "mu_" + crockfordEncode(randomBytes(32))
        val keyHash = sha256Hex(apiKey.toByteArray(Charsets.UTF_8))

        val webhookId: String = try {
            sdk.withUser(userId) {
                it.updateUserSettings(
                    UpdateUserSettingsRequest(
                        sparkPrivateModeEnabled = true,
                        stableBalanceActiveLabel = null,
                    )
                )
                it.registerWebhook(
                    RegisterWebhookRequest(
                        url = "${cfg.publicBaseUrl}/webhooks/sdk/$userId",
                        secret = cfg.webhookSecret,
                        eventTypes = listOf(
                            WebhookEventType.LightningReceiveFinished,
                            WebhookEventType.LightningSendFinished,
                            WebhookEventType.CoopExitFinished,
                            WebhookEventType.StaticDepositFinished,
                        ),
                    )
                ).webhookId
            }
        } catch (e: Exception) {
            log.warn("user provisioning failed for {}: {}", userId, e.message)
            call.respondError(
                HttpStatusCode.BadGateway,
                ErrorCodes.UPSTREAM_UNAVAILABLE,
                "user provisioning failed, please retry",
            )
            return@post
        }

        ds.connection.use { conn ->
            conn.prepareStatement(
                "INSERT INTO users (user_id, api_key_hash, webhook_id) VALUES (?, ?, ?)"
            ).use { ps ->
                ps.setString(1, userId)
                ps.setString(2, keyHash)
                ps.setString(3, webhookId)
                ps.executeUpdate()
            }
        }

        call.respond(CreateUserResponse(user_id = userId, api_key = apiKey))
    }
}

// --- ids -------------------------------------------------------------------

private const val CROCKFORD = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"

private val RNG = SecureRandom()

internal fun randomBytes(n: Int): ByteArray = ByteArray(n).also { RNG.nextBytes(it) }

/**
 * 26-char Crockford-base32 ULID. The first 10 chars encode the 48-bit
 * timestamp (millis); the last 16 encode 80 bits of randomness.
 */
internal fun newUlid(): String {
    val out = CharArray(26)
    var t = System.currentTimeMillis()
    for (i in 9 downTo 0) {
        out[i] = CROCKFORD[(t and 0x1F).toInt()]
        t = t ushr 5
    }
    val rand = randomBytes(10)
    var bitBuf = 0
    var bitCount = 0
    var idx = 10
    for (b in rand) {
        bitBuf = (bitBuf shl 8) or (b.toInt() and 0xFF)
        bitCount += 8
        while (bitCount >= 5) {
            bitCount -= 5
            out[idx++] = CROCKFORD[(bitBuf ushr bitCount) and 0x1F]
        }
    }
    return String(out)
}

/** Crockford base32, no padding. */
internal fun crockfordEncode(bytes: ByteArray): String {
    val out = StringBuilder()
    var bitBuf = 0
    var bitCount = 0
    for (b in bytes) {
        bitBuf = (bitBuf shl 8) or (b.toInt() and 0xFF)
        bitCount += 8
        while (bitCount >= 5) {
            bitCount -= 5
            out.append(CROCKFORD[(bitBuf ushr bitCount) and 0x1F])
        }
    }
    if (bitCount > 0) {
        out.append(CROCKFORD[(bitBuf shl (5 - bitCount)) and 0x1F])
    }
    return out.toString()
}
