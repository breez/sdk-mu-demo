import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.respond
import kotlinx.serialization.Serializable

@Serializable
data class ApiError(val error: ErrorBody) {
    constructor(code: String, message: String) : this(ErrorBody(code, message))

    @Serializable
    data class ErrorBody(val code: String, val message: String)
}

object ErrorCodes {
    const val UNAUTHORIZED = "unauthorized"
    const val FORBIDDEN = "forbidden"
    const val NOT_FOUND = "not_found"
    const val BAD_REQUEST = "bad_request"
    const val UPSTREAM_UNAVAILABLE = "upstream_unavailable"
    const val INTERNAL = "internal"
}

suspend fun ApplicationCall.respondError(status: HttpStatusCode, code: String, message: String) {
    respond(status, ApiError(code, message))
}
