package app.geunal.api.common

import io.micrometer.core.instrument.MeterRegistry
import org.slf4j.LoggerFactory
import org.springframework.dao.DataAccessException
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.data.redis.core.script.DefaultRedisScript
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Component
import java.time.Clock
import java.time.Duration
import java.util.concurrent.atomic.AtomicLong

/**
 * The limits of api-server.md §9, counted in Redis per clock window (an hour unless a check names another) so every API
 * instance shares them. Past the limit a request gets 429 `rate_limited` with `Retry-After` up to the next window.
 *
 * These are load limits, not security decisions, so when Redis cannot be reached the request goes through uncounted
 * (fail open): the database is the record, and an outage of the counter must not turn every message, commit and state
 * write into a 500. Each such pass is counted in [FAIL_OPEN]. What decides who a caller is fails closed instead and
 * does not go through here: a sign-in nonce (Nonces) and Apple's notification replay marker (ProviderNotifications)
 * answer an error while Redis is away.
 */
@Component
class HourlyLimit(
    private val redis: StringRedisTemplate,
    private val clock: Clock,
    private val metrics: MeterRegistry,
) {
    // One script so the count and its expiry are set together: an INCR whose EXPIRE was lost (a crash or a dropped
    // connection between the two) would leave a counter that never expires.
    private val count =
        DefaultRedisScript(
            "local n = redis.call('INCR', KEYS[1]) if n == 1 then redis.call('EXPIRE', KEYS[1], ARGV[1]) end return n",
            Long::class.javaObjectType,
        )
    private val skipUntil = AtomicLong(System.nanoTime())
    private val warnedAt = AtomicLong(System.nanoTime() - WARN_EVERY.toNanos())

    /** Counts one request of [name] for [subject] (an address, a user, a group) against [limit] per [window]. */
    fun check(
        name: String,
        subject: String,
        limit: Int,
        window: Duration = HOUR,
    ) {
        val now = clock.instant().epochSecond
        val slot = now / window.seconds
        val count = increment("$name:$subject:${window.seconds}:$slot", window.multipliedBy(2)) ?: return
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

    /** The new count, or null when Redis did not answer and the request passes uncounted. */
    private fun increment(
        key: String,
        ttl: Duration,
    ): Long? {
        val started = System.nanoTime()
        // After a failure Redis is left alone for a moment: a client that is reconnecting holds each command until
        // its timeout, and every write waiting that long on a counter would be an outage of its own.
        if (started - skipUntil.get() >= 0) {
            try {
                return redis.execute(count, listOf(key), ttl.seconds.toString())
            } catch (e: DataAccessException) {
                skipUntil.set(System.nanoTime() + SKIP_AFTER_FAILURE.toNanos())
                val last = warnedAt.get()
                if (started - last >= WARN_EVERY.toNanos() && warnedAt.compareAndSet(last, started)) {
                    // The key names an address, a user or a group, so only the kind of failure is logged.
                    log.warn("rate limits are not counted while Redis does not answer: {}", e.javaClass.simpleName)
                }
            }
        }
        metrics.counter(FAIL_OPEN).increment()
        return null
    }

    companion object {
        val HOUR: Duration = Duration.ofHours(1)
        val MINUTE: Duration = Duration.ofMinutes(1)
        val DAY: Duration = Duration.ofDays(1)

        /** Counter of requests let through uncounted because Redis did not answer. */
        const val FAIL_OPEN = "geunal.rate.limit.fail.open"
        private val SKIP_AFTER_FAILURE: Duration = Duration.ofSeconds(5)
        private val WARN_EVERY: Duration = Duration.ofMinutes(1)
        private val log = LoggerFactory.getLogger(HourlyLimit::class.java)
    }
}
