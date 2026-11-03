package app.geunal.api.common

import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Component
import java.time.Clock
import java.time.Duration

/**
 * The per-address limits of api-server.md §9, counted in Redis per clock hour so every API instance shares them. Past
 * the limit a request gets 429 `rate_limited` with `Retry-After` up to the next hour.
 */
@Component
class HourlyLimit(
    private val redis: StringRedisTemplate,
    private val clock: Clock,
) {
    fun check(
        name: String,
        address: String,
        perHour: Int,
    ) {
        val now = clock.instant().epochSecond
        val hour = now / HOUR.seconds
        val key = "$name:$address:$hour"
        val count = redis.opsForValue().increment(key) ?: 0
        if (count == 1L) redis.expire(key, HOUR.multipliedBy(2))
        if (count > perHour) {
            val retryAfter = (hour + 1) * HOUR.seconds - now
            throw ApiException(
                HttpStatus.TOO_MANY_REQUESTS,
                "rate_limited",
                "at most $perHour $name requests an hour",
                headers = mapOf(HttpHeaders.RETRY_AFTER to retryAfter.coerceAtLeast(1).toString()),
            )
        }
    }

    private companion object {
        val HOUR: Duration = Duration.ofHours(1)
    }
}
