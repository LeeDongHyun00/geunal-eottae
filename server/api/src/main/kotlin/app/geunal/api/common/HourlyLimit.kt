package app.geunal.api.common

import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Component
import java.time.Clock
import java.time.Duration

/**
 * The limits of api-server.md §9, counted in Redis per clock window (an hour unless a check names another) so every API
 * instance shares them. Past the limit a request gets 429 `rate_limited` with `Retry-After` up to the next window.
 */
@Component
class HourlyLimit(
    private val redis: StringRedisTemplate,
    private val clock: Clock,
) {
    /** Counts one request of [name] for [subject] (an address, a user, a group) against [limit] per [window]. */
    fun check(
        name: String,
        subject: String,
        limit: Int,
        window: Duration = HOUR,
    ) {
        val now = clock.instant().epochSecond
        val slot = now / window.seconds
        val key = "$name:$subject:${window.seconds}:$slot"
        val count = redis.opsForValue().increment(key) ?: 0
        if (count == 1L) redis.expire(key, window.multipliedBy(2))
        if (count > limit) {
            val retryAfter = (slot + 1) * window.seconds - now
            throw ApiException(
                HttpStatus.TOO_MANY_REQUESTS,
                "rate_limited",
                "at most $limit $name requests in ${window.seconds} seconds",
                headers = mapOf(HttpHeaders.RETRY_AFTER to retryAfter.coerceAtLeast(1).toString()),
            )
        }
    }

    companion object {
        val HOUR: Duration = Duration.ofHours(1)
        val MINUTE: Duration = Duration.ofMinutes(1)
    }
}
