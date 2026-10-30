package app.geunal.api.common

import org.slf4j.LoggerFactory
import org.springframework.beans.TypeMismatchException
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.http.converter.HttpMessageNotReadableException
import org.springframework.web.ErrorResponse
import org.springframework.web.bind.ServletRequestBindingException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice

/** The one error body shape (protocol §11). Devices branch on [error]; [message] is for people reading logs. */
data class ErrorBody(
    val error: String,
    val message: String,
)

/**
 * An error from the protocol §11 table, such as 409 `epoch_conflict` or 410 `cursor_expired`. [details] are the extra
 * fields a code carries next to `error` and `message`, such as `replaced_at` on 403 `device_revoked`.
 */
class ApiException(
    val status: HttpStatus,
    val code: String,
    message: String,
    val details: Map<String, String> = emptyMap(),
) : RuntimeException(message) {
    companion object {
        fun invalid(message: String) = ApiException(HttpStatus.BAD_REQUEST, "invalid_request", message)

        fun notFound(what: String) = ApiException(HttpStatus.NOT_FOUND, "not_found", "$what not found")

        fun notMember() = ApiException(HttpStatus.FORBIDDEN, "not_member", "not an active member of this group")

        fun tooLarge(
            what: String,
            max: Int,
        ) = ApiException(HttpStatus.PAYLOAD_TOO_LARGE, "too_large", "$what is larger than $max bytes")
    }
}

@RestControllerAdvice
class ErrorHandler {
    private val log = LoggerFactory.getLogger(ErrorHandler::class.java)

    @ExceptionHandler(ApiException::class)
    fun handle(e: ApiException): ResponseEntity<Any> = ResponseEntity.status(e.status).body(body(e))

    /** A body Jackson could not bind: wrong type, missing field, bad base64. The message may quote the body, so it stays out of the log. */
    @ExceptionHandler(HttpMessageNotReadableException::class)
    fun handle(
        @Suppress("unused") e: HttpMessageNotReadableException,
    ): ResponseEntity<Any> = handle(ApiException.invalid("the request body does not match the endpoint's schema"))

    /** A path variable or query parameter of the wrong shape, or a required one missing. */
    @ExceptionHandler(TypeMismatchException::class, ServletRequestBindingException::class)
    fun handleBinding(
        @Suppress("unused") e: Exception,
    ): ResponseEntity<Any> = handle(ApiException.invalid("a path or query parameter is missing or malformed"))

    /** Everything else, kept in the protocol's body shape. Spring MVC's own errors (404, 405, type mismatches) carry their status. */
    @ExceptionHandler(Exception::class)
    fun handle(e: Exception): ResponseEntity<Any> {
        val status = (e as? ErrorResponse)?.statusCode?.let { HttpStatus.resolve(it.value()) } ?: HttpStatus.INTERNAL_SERVER_ERROR
        val code =
            when {
                status == HttpStatus.NOT_FOUND -> "not_found"
                status == HttpStatus.PAYLOAD_TOO_LARGE -> "too_large"
                status.is4xxClientError -> "invalid_request"
                else -> "internal"
            }
        if (status.is5xxServerError) log.error("request failed", e)
        return handle(ApiException(status, code, if (status.is5xxServerError) "internal error" else "the request is malformed"))
    }

    companion object {
        /** The body for [e]: an [ErrorBody], or the same two fields followed by the code's details. */
        fun body(e: ApiException): Any =
            if (e.details.isEmpty()) {
                ErrorBody(e.code, e.message.orEmpty())
            } else {
                linkedMapOf<String, String>("error" to e.code, "message" to e.message.orEmpty()) + e.details
            }
    }
}
