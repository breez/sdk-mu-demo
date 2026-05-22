import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import java.security.MessageDigest
import javax.sql.DataSource

/**
 * Bearer-token auth.
 *
 *   1. `Authorization: Bearer <api_key>` → SHA-256 → `users.api_key_hash`
 *   2. matched row's `user_id` is the principal
 *   3. if the route has `{userId}`, it must equal the principal (else 403)
 *
 * Used as `val userId = call.requireUser(ds) ?: return@get` inside handlers.
 * Returning `null` means the response was already written (401/403); the
 * caller must abort.
 */
suspend fun ApplicationCall.requireUser(ds: DataSource): String? {
    val header = request.headers["Authorization"]
    if (header.isNullOrBlank() || !header.startsWith("Bearer ", ignoreCase = true)) {
        respondError(HttpStatusCode.Unauthorized, ErrorCodes.UNAUTHORIZED, "Missing or malformed Authorization header")
        return null
    }
    val token = header.substring("Bearer ".length).trim()
    if (token.isEmpty()) {
        respondError(HttpStatusCode.Unauthorized, ErrorCodes.UNAUTHORIZED, "Empty bearer token")
        return null
    }

    val hash = sha256Hex(token.toByteArray(Charsets.UTF_8))
    val principal = lookupUserByKeyHash(ds, hash)
    if (principal == null) {
        respondError(HttpStatusCode.Unauthorized, ErrorCodes.UNAUTHORIZED, "Invalid API key")
        return null
    }

    val pathUserId = parameters["userId"]
    if (pathUserId != null && pathUserId != principal) {
        respondError(HttpStatusCode.Forbidden, ErrorCodes.FORBIDDEN, "API key does not match {userId}")
        return null
    }
    return principal
}

private fun lookupUserByKeyHash(ds: DataSource, hash: String): String? {
    ds.connection.use { conn ->
        conn.prepareStatement("SELECT user_id FROM users WHERE api_key_hash = ?").use { ps ->
            ps.setString(1, hash)
            ps.executeQuery().use { rs ->
                return if (rs.next()) rs.getString(1) else null
            }
        }
    }
}

fun sha256Hex(input: ByteArray): String {
    val md = MessageDigest.getInstance("SHA-256")
    val digest = md.digest(input)
    val hex = StringBuilder(digest.size * 2)
    for (b in digest) {
        hex.append(HEX_CHARS[(b.toInt() ushr 4) and 0xf])
        hex.append(HEX_CHARS[b.toInt() and 0xf])
    }
    return hex.toString()
}

private const val HEX_CHARS = "0123456789abcdef"
