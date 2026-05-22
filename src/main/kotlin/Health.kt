import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import javax.sql.DataSource
import kotlinx.serialization.Serializable

@Serializable
data class HealthBody(val status: String)

fun Route.health(ds: DataSource) {
    get("/healthz") { call.respond(HealthBody("ok")) }

    get("/readyz") {
        val ok = try {
            ds.connection.use { c ->
                c.prepareStatement("SELECT 1").use { it.executeQuery().use { rs -> rs.next() } }
            }
        } catch (_: Exception) {
            false
        }
        if (ok) {
            call.respond(HealthBody("ready"))
        } else {
            call.respond(HttpStatusCode.ServiceUnavailable, HealthBody("not_ready"))
        }
    }
}
