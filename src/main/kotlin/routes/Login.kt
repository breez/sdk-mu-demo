package routes

import AppConfig
import ErrorCodes
import SignerMode
import TurnkeySessionVerifier
import crockfordEncode
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import javax.sql.DataSource
import kotlinx.serialization.Serializable
import org.slf4j.LoggerFactory
import randomBytes
import respondError
import sha256Hex

private val log = LoggerFactory.getLogger("Login")

@Serializable
data class LoginRequest(val session_jwt: String)

@Serializable
data class LoginResponse(
    val user_id: String,
    val api_key: String, // freshly minted, shown once
    val turnkey_sub_org_id: String,
)

/**
 * `POST /login` (SIGNER=turnkey only) — re-authenticate an existing wallet with
 * its passkey and mint a fresh api_key.
 *
 * The api_key is the demo's HTTP bearer token; it's minted once at `POST /users`
 * and only its hash is stored, so a client that lost it (cleared storage, new
 * device) can't read it back — it must prove control of the wallet and get a new
 * one. The proof is a Turnkey **session JWT**: the browser logs in with the
 * passkey (sole root of the sub-org), which yields a Turnkey-signed JWT naming
 * the sub-org. We verify that signature, map the sub-org to its user, and rotate
 * the api_key. Minting a new key invalidates the previous one (single active key
 * per user) — fine for this demo.
 */
fun Route.login(ds: DataSource, cfg: AppConfig) {
    post("/login") {
        if (cfg.signer != SignerMode.TURNKEY) {
            call.respondError(
                HttpStatusCode.Conflict,
                ErrorCodes.BAD_REQUEST,
                "login requires SIGNER=turnkey; seed deployments have no passkey to authenticate with",
            )
            return@post
        }
        val body = try {
            call.receive<LoginRequest>()
        } catch (e: Exception) {
            call.respondError(HttpStatusCode.BadRequest, ErrorCodes.BAD_REQUEST, "malformed body: ${e.message}")
            return@post
        }

        val claims = try {
            TurnkeySessionVerifier.verify(body.session_jwt)
        } catch (e: Exception) {
            log.info("login rejected: {}", e.message)
            call.respondError(HttpStatusCode.Unauthorized, ErrorCodes.UNAUTHORIZED, "invalid or expired session")
            return@post
        }

        val apiKey = "mu_" + crockfordEncode(randomBytes(32))
        val keyHash = sha256Hex(apiKey.toByteArray(Charsets.UTF_8))
        val userId = ds.connection.use { conn ->
            conn.prepareStatement(
                "UPDATE users SET api_key_hash = ? WHERE turnkey_sub_org_id = ? RETURNING user_id",
            ).use { ps ->
                ps.setString(1, keyHash)
                ps.setString(2, claims.organizationId)
                ps.executeQuery().use { rs ->
                    if (rs.next()) rs.getString("user_id") else null
                }
            }
        }
        if (userId == null) {
            call.respondError(HttpStatusCode.NotFound, ErrorCodes.NOT_FOUND, "no wallet found for this passkey")
            return@post
        }

        log.info("login user={} sub_org={}", userId, claims.organizationId)
        call.respond(
            LoginResponse(
                user_id = userId,
                api_key = apiKey,
                turnkey_sub_org_id = claims.organizationId,
            ),
        )
    }
}
