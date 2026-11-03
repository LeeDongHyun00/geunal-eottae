package app.geunal.api.holidays

import app.geunal.api.common.ApiException
import jakarta.servlet.http.HttpServletRequest
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.http.CacheControl
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import tools.jackson.databind.json.JsonMapper
import java.security.MessageDigest
import java.time.Clock
import java.time.Duration
import java.time.LocalDate
import java.time.ZoneId
import java.util.HexFormat

data class HolidayItem(
    val day: LocalDate,
    val name: String,
    val substitute: Boolean,
)

/** `years`: the requested years the server knows whole; `holidays`: every holiday of those years. */
data class HolidayYears(
    val years: List<Int>,
    val holidays: List<HolidayItem>,
)

/**
 * `GET /holidays` (protocol §14), without a session. Whole years only, at most two: a device replaces each year it
 * receives, so part of a year would drop the rest. It reads the stored table and nothing else.
 */
@RestController
class HolidayController(
    private val jdbc: JdbcClient,
    private val redis: StringRedisTemplate,
    private val mapper: JsonMapper,
    private val clock: Clock,
) {
    @GetMapping("/holidays")
    fun holidays(
        @RequestParam("from_year") from: Int,
        @RequestParam("to_year") to: Int,
        request: HttpServletRequest,
    ): ResponseEntity<HolidayYears> {
        val thisYear = LocalDate.now(clock.withZone(SEOUL)).year
        if (from < FIRST_YEAR || from > thisYear + 1 || to < from || to > from + 1) {
            throw ApiException.invalid("from_year must be $FIRST_YEAR to next year, and to_year from_year or the year after")
        }
        limit(request.remoteAddr)
        val years =
            jdbc
                .sql("SELECT year FROM holiday_years WHERE year BETWEEN :from AND :to ORDER BY year")
                .param("from", from)
                .param("to", to)
                .query(Int::class.java)
                .list()
                .filterNotNull()
        val holidays =
            if (years.isEmpty()) {
                emptyList()
            } else {
                // Names in code-point order keep the body, and with it the ETag, the same under any database collation.
                jdbc
                    .sql(
                        """
                        SELECT day, name, is_substitute FROM holidays
                        WHERE extract(year FROM day)::integer IN (:years) ORDER BY day, name COLLATE "C"
                        """,
                    ).param("years", years)
                    .query {
                        rs,
                        _,
                        ->
                        HolidayItem(rs.getObject("day", LocalDate::class.java), rs.getString("name"), rs.getBoolean("is_substitute"))
                    }.list()
            }
        val body = HolidayYears(years, holidays)
        val tag = "\"" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(mapper.writeValueAsBytes(body)), 0, 16) + "\""
        val cache = CacheControl.maxAge(CACHE_FOR).cachePublic()
        if (request.getHeader(HttpHeaders.IF_NONE_MATCH)?.split(",")?.any { it.trim() == tag } == true) {
            return ResponseEntity
                .status(HttpStatus.NOT_MODIFIED)
                .eTag(tag)
                .cacheControl(cache)
                .build()
        }
        return ResponseEntity
            .ok()
            .eTag(tag)
            .cacheControl(cache)
            .body(body)
    }

    /** 60 an hour for each address (api-server.md §9), counted per clock hour. */
    private fun limit(address: String) {
        val hour = clock.instant().epochSecond / HOUR.seconds
        val key = "holidays:$address:$hour"
        val count = redis.opsForValue().increment(key) ?: 0
        if (count == 1L) redis.expire(key, HOUR.multipliedBy(2))
        if (count > PER_HOUR) {
            val retryAfter = (hour + 1) * HOUR.seconds - clock.instant().epochSecond
            throw ApiException(
                HttpStatus.TOO_MANY_REQUESTS,
                "rate_limited",
                "at most $PER_HOUR holiday requests an hour",
                headers = mapOf(HttpHeaders.RETRY_AFTER to retryAfter.coerceAtLeast(1).toString()),
            )
        }
    }

    private companion object {
        const val FIRST_YEAR = 2000
        const val PER_HOUR = 60
        val HOUR: Duration = Duration.ofHours(1)
        val CACHE_FOR: Duration = Duration.ofHours(1)
        val SEOUL: ZoneId = ZoneId.of("Asia/Seoul")
    }
}
