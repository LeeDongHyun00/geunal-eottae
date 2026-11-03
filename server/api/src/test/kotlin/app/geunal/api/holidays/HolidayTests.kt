package app.geunal.api.holidays

import app.geunal.api.support.ApiTest
import app.geunal.api.support.FakeKasi
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.HttpMethod
import java.time.LocalDate
import java.time.ZoneId
import java.util.concurrent.atomic.AtomicInteger

/** `GET /holidays` and its daily sync (protocol §14): whole years the server knows, from KASI only by the daily job. */
class HolidayTests : ApiTest() {
    @Autowired
    lateinit var sync: HolidaySync

    /** A different address per test (JUnit makes an instance per test), so one test's hourly count never reaches another. */
    private val address = "198.51.100.${addresses.incrementAndGet()}"

    private fun get(
        from: Int,
        to: Int,
        headers: Map<String, String> = emptyMap(),
    ) = api.send(HttpMethod.GET, "/holidays?from_year=$from&to_year=$to", headers = headers + ("X-Forwarded-For" to address))

    private val year = LocalDate.now(ZoneId.of("Asia/Seoul")).year

    private fun fullYear(y: Int) =
        FakeKasi.items(
            listOf(
                "${y}0101" to "1월1일",
                "${y}0505" to "어린이날",
                "${y}0505" to "부처님오신날",
                "${y}0506" to "대체공휴일",
                "${y}1225" to "기독탄신일",
            ),
        )

    @Test
    fun `answers the whole years the server knows and leaves out the rest`() {
        FakeKasi.answers[year] = fullYear(year)
        // Next year announced only in part: no 기독탄신일 yet.
        FakeKasi.answers[year + 1] = FakeKasi.items(listOf("${year + 1}0101" to "1월1일"))

        assertThat(sync.sync(year)).isTrue()
        assertThat(sync.sync(year + 1)).isFalse()

        val answer = get(year, year + 1).expect(200)
        assertThat(answer["years"].values().map { it.intValue() }).containsExactly(year)
        val holidays =
            answer["holidays"].values().map {
                Triple(it.path("day").stringValue(), it.path("name").stringValue(), it.path("substitute").booleanValue())
            }
        assertThat(holidays).containsExactly(
            Triple("$year-01-01", "1월1일", false),
            Triple("$year-05-05", "부처님오신날", false),
            Triple("$year-05-05", "어린이날", false),
            Triple("$year-05-06", "대체공휴일", true),
            Triple("$year-12-25", "기독탄신일", false),
        )
        // The key went out whole, signs and all; and no request reached KASI from the endpoint.
        assertThat(FakeKasi.requests.last()["ServiceKey"]).isEqualTo(FakeKasi.SERVICE_KEY)
        val calls = FakeKasi.requests.size
        get(year, year)
        assertThat(FakeKasi.requests.size).isEqualTo(calls)
    }

    @Test
    fun `a failed or odd answer keeps the year as it was`() {
        FakeKasi.answers[year] = fullYear(year)
        assertThat(sync.sync(year)).isTrue()

        FakeKasi.answers[year] = FakeKasi.items(listOf("${year}0101" to "1월1일", "${year}1225" to "기독탄신일"), resultCode = "22")
        assertThat(sync.sync(year)).isFalse()
        FakeKasi.answers[year] = FakeKasi.items(emptyList())
        assertThat(sync.sync(year)).isFalse()
        FakeKasi.answers[year] = "<OpenAPI_ServiceResponse>SERVICE ERROR</OpenAPI_ServiceResponse>"
        assertThat(sync.sync(year)).isFalse()

        assertThat(get(year, year).expect(200)["holidays"].size()).isEqualTo(5)
    }

    @Test
    fun `takes whole years, at most two, up to next year`() {
        for ((from, to) in listOf(year to year - 1, year to year + 2, year + 2 to year + 2, 1999 to 1999)) {
            get(from, to).expectError(400, "invalid_request")
        }
        get(year + 1, year + 1).expect(200)
    }

    @Test
    fun `carries an ETag and a Cache-Control, and answers 304 to the same ETag`() {
        val first = get(year, year).expect(200)
        val tag = first.headers.eTag!!
        assertThat(first.headers.cacheControl).contains("max-age=3600")

        val again = get(year, year, mapOf("If-None-Match" to tag))
        assertThat(again.status).isEqualTo(304)
        get(year, year, mapOf("If-None-Match" to "\"other\"")).expect(200)
    }

    @Test
    fun `answers 60 an hour to one address`() {
        repeat(60) { get(year, year).expect(200) }

        val refused = get(year, year).expectError(429, "rate_limited")
        assertThat(refused.headers.getFirst("Retry-After")!!.toLong()).isBetween(1, 3600)
    }

    private companion object {
        val addresses = AtomicInteger()
    }
}
