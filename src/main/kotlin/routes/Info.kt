package routes

import ErrorCodes
import SdkAccess
import breez_sdk_spark.GetInfoRequest
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import javax.sql.DataSource
import kotlinx.serialization.Serializable
import requireUser
import respondError

@Serializable
data class InfoResponse(val balance_sats: Long)

/**
 * `GET /users/{userId}/info` — local balance read.
 *
 * `ensureSynced=false`: server mode rejects `true` (no background sync). The
 * local DB is kept fresh by the webhook handler's `syncWallet` (Phase 2);
 * defensive sync here would just add latency.
 */
fun Route.info(ds: DataSource, sdk: SdkAccess) {
    get("/users/{userId}/info") {
        val userId = call.requireUser(ds) ?: return@get
        try {
            val resp = sdk.withUser(userId) { it.getInfo(GetInfoRequest(ensureSynced = false)) }
            call.respond(InfoResponse(balance_sats = resp.balanceSats.toLong()))
        } catch (e: Exception) {
            call.respondError(
                HttpStatusCode.BadGateway,
                ErrorCodes.UPSTREAM_UNAVAILABLE,
                e.message ?: "SDK error",
            )
        }
    }
}
