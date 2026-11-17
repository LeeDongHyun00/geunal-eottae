package app.geunal.api.common

import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus

class ErrorHandlerTests {
    @Test
    fun `turns an ApiException into the protocol error body with its status`() {
        val metrics = SimpleMeterRegistry()
        val response = ErrorHandler(metrics).handle(ApiException(HttpStatus.CONFLICT, "epoch_conflict", "another commit won"))

        assertThat(response.statusCode).isEqualTo(HttpStatus.CONFLICT)
        assertThat(response.body).isEqualTo(ErrorBody("epoch_conflict", "another commit won"))
        assertThat(metrics.counter(ErrorHandler.CONFLICTS, "status", "409", "code", "epoch_conflict").count()).isEqualTo(1.0)
    }
}
