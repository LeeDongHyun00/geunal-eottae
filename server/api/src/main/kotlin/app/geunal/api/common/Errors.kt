package app.geunal.api.common

import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice

/** The one error body shape (protocol §11). Devices branch on [error]; [message] is for people reading logs. */
data class ErrorBody(
    val error: String,
    val message: String,
)

/** An error from the protocol §11 table, such as 409 `epoch_conflict` or 410 `cursor_expired`. */
class ApiException(
    val status: HttpStatus,
    val code: String,
    message: String,
) : RuntimeException(message)

@RestControllerAdvice
class ErrorHandler {
    @ExceptionHandler(ApiException::class)
    fun handle(e: ApiException): ResponseEntity<ErrorBody> = ResponseEntity.status(e.status).body(ErrorBody(e.code, e.message.orEmpty()))
}
