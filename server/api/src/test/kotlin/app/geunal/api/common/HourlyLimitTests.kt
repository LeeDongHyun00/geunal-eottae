package app.geunal.api.common

import app.geunal.api.support.TestStack
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.data.redis.connection.RedisStandaloneConfiguration
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory
import org.springframework.data.redis.core.StringRedisTemplate
import java.net.ServerSocket
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

/** The counter itself, without the API around it: its expiry, and what it does when Redis is away. */
class HourlyLimitTests {
    private val clock = Clock.fixed(Instant.parse("2026-11-17T10:00:30Z"), ZoneOffset.UTC)

    private fun redis(
        host: String,
        port: Int,
    ): StringRedisTemplate {
        val client = LettuceClientConfiguration.builder().commandTimeout(Duration.ofSeconds(1)).build()
        val factory = LettuceConnectionFactory(RedisStandaloneConfiguration(host, port), client)
        factory.afterPropertiesSet()
        factory.start()
        return StringRedisTemplate(factory)
    }

    @Test
    fun `counts up to the limit and sets the expiry with the first count`() {
        val redis = redis(TestStack.redisHost, TestStack.redisPort)
        val limit = HourlyLimit(redis, clock, SimpleMeterRegistry())
        val subject = UUID.randomUUID().toString()

        repeat(3) { limit.check("unit", subject, 3, HourlyLimit.MINUTE) }

        assertThatThrownBy { limit.check("unit", subject, 3, HourlyLimit.MINUTE) }
            .isInstanceOfSatisfying(ApiException::class.java) {
                assertThat(it.code).isEqualTo("rate_limited")
                assertThat(it.headers["Retry-After"]).isEqualTo("30")
            }
        val key = "unit:$subject:60:${clock.instant().epochSecond / 60}"
        assertThat(redis.opsForValue().get(key)).isEqualTo("4")
        assertThat(redis.getExpire(key)).isBetween(1L, 120L)
    }

    @Test
    fun `lets requests through uncounted while Redis does not answer`() {
        val closed = ServerSocket(0).use { it.localPort }
        val metrics = SimpleMeterRegistry()
        val limit = HourlyLimit(redis("127.0.0.1", closed), clock, metrics)

        repeat(5) { limit.check("unit", "anyone", 1, HourlyLimit.MINUTE) }

        assertThat(metrics.counter(HourlyLimit.FAIL_OPEN).count()).isEqualTo(5.0)
    }
}
