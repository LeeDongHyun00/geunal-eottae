package app.geunal.api.holidays

import org.slf4j.LoggerFactory
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.http.client.SimpleClientHttpRequestFactory
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import org.springframework.web.client.RestClient
import org.springframework.web.util.UriComponentsBuilder
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.time.Clock
import java.time.Duration
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * `geunal.holidays.*`: the 공공데이터포털 key for KASI's 특일 정보 API (`GEUNAL_HOLIDAYS_SERVICE_KEY`, the "Decoding"
 * form). Without it the daily sync does nothing and GET /holidays answers from what is stored.
 */
@ConfigurationProperties("geunal.holidays")
data class HolidayProperties(
    val serviceKey: String = "",
    val uri: String = "https://apis.data.go.kr/B090041/openapi/service/SpcdeInfoService/getRestDeInfo",
)

/** One public holiday as KASI lists it. */
data class Holiday(
    val day: LocalDate,
    val name: String,
    val substitute: Boolean,
)

/**
 * Keeps `holidays` current from KASI once a day (protocol §14), this year and next. The daily job is the only caller of
 * the API, so the public GET /holidays cannot spend the key's daily quota. A year is replaced only when its fetch is
 * complete: KASI answered for it and the list has both 신정 and 기독탄신일, which are announced with the rest of the
 * year. A year that fails keeps what the server had.
 */
@Component
class HolidaySync(
    private val jdbc: JdbcClient,
    private val properties: HolidayProperties,
    private val mapper: JsonMapper,
    private val clock: Clock,
    transactions: PlatformTransactionManager,
) {
    private val transaction = TransactionTemplate(transactions)

    private val client =
        RestClient
            .builder()
            .requestFactory(
                SimpleClientHttpRequestFactory().apply {
                    setConnectTimeout(TIMEOUT)
                    setReadTimeout(TIMEOUT)
                },
            ).build()

    @Scheduled(cron = "0 17 4 * * *", zone = "Asia/Seoul")
    fun daily() {
        if (properties.serviceKey.isBlank()) return
        val year = LocalDate.now(clock.withZone(SEOUL)).year
        for (it in listOf(year, year + 1)) sync(it)
    }

    /** Fetches [year] and, when the list is complete, replaces the stored year with it. True when it did. */
    fun sync(year: Int): Boolean {
        val days =
            try {
                fetch(year)
            } catch (e: RuntimeException) {
                log.warn("could not fetch the holidays of {}: {}", year, e.javaClass.simpleName)
                null
            }
        if (days == null || !complete(year, days)) return false
        transaction.executeWithoutResult {
            jdbc
                .sql("DELETE FROM holidays WHERE day BETWEEN :first AND :last")
                .param("first", LocalDate.of(year, 1, 1))
                .param("last", LocalDate.of(year, 12, 31))
                .update()
            for (day in days.distinctBy { it.day to it.name }) {
                jdbc
                    .sql("INSERT INTO holidays (day, name, is_substitute) VALUES (:day, :name, :substitute)")
                    .param("day", day.day)
                    .param("name", day.name)
                    .param("substitute", day.substitute)
                    .update()
            }
            jdbc
                .sql("INSERT INTO holiday_years (year) VALUES (:year) ON CONFLICT (year) DO UPDATE SET synced_at = now()")
                .param("year", year)
                .update()
        }
        log.info("stored {} holidays of {}", days.size, year)
        return true
    }

    /**
     * The holidays of [year] in one request, or null when KASI does not answer with a normal result. Its JSON gives
     * `item` as an array, a single object for one holiday, and an empty string for none.
     */
    private fun fetch(year: Int): List<Holiday>? {
        val uri =
            UriComponentsBuilder
                .fromUriString(properties.uri)
                .queryParam("solYear", year)
                .queryParam("numOfRows", MAX_ROWS)
                .queryParam("_type", "json")
                .queryParam("ServiceKey", "{key}")
                .encode()
                .buildAndExpand(mapOf("key" to properties.serviceKey))
                .toUri()
        val body =
            client
                .get()
                .uri(uri)
                .retrieve()
                .body(String::class.java) ?: return null
        val response = mapper.readTree(body).path("response")
        if (response.path("header").path("resultCode").asString() != "00") return null
        val item = response.path("body").path("items").path("item")
        val items =
            when {
                item.isArray -> item.values().toList()
                item.isObject -> listOf(item)
                else -> emptyList()
            }
        return items.filter { it.path("isHoliday").asString() == "Y" }.map(::holiday)
    }

    private fun holiday(item: JsonNode): Holiday {
        val name = item.path("dateName").asString().trim()
        return Holiday(LocalDate.parse(item.path("locdate").asString(), LOCDATE), name, name.contains("대체"))
    }

    private fun complete(
        year: Int,
        days: List<Holiday>,
    ): Boolean = days.any { it.day == LocalDate.of(year, 1, 1) } && days.any { it.day == LocalDate.of(year, 12, 25) }

    private companion object {
        val SEOUL: ZoneId = ZoneId.of("Asia/Seoul")
        val TIMEOUT: Duration = Duration.ofSeconds(10)
        val LOCDATE: DateTimeFormatter = DateTimeFormatter.BASIC_ISO_DATE
        const val MAX_ROWS = 100
        val log = LoggerFactory.getLogger(HolidaySync::class.java)
    }
}
