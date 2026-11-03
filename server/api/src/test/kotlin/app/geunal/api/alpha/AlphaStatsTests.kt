package app.geunal.api.alpha

import app.geunal.api.auth.AccountRepository
import app.geunal.api.auth.Caller
import app.geunal.api.common.ApiException
import app.geunal.api.devices.DeviceRepository
import app.geunal.api.support.ApiTest
import app.geunal.api.support.Client
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import java.time.Clock
import java.time.LocalDate
import java.time.ZoneId

/** `POST /me/alpha-stats` (protocol §14): an opted-in tester's daily counts, a day sent again replacing the last. */
class AlphaStatsTests : ApiTest() {
    @Autowired
    lateinit var accounts: AccountRepository

    @Autowired
    lateinit var devices: DeviceRepository

    private val today: LocalDate get() = LocalDate.now(ZoneId.of("Asia/Seoul"))

    private fun day(
        date: LocalDate,
        sessions: Int = 3,
        events: Int = 2,
        suggested: Int = 1,
        crashes: Int = 0,
    ) = mapOf("day" to date.toString(), "sessions" to sessions, "events" to events, "suggested" to suggested, "crashes" to crashes)

    private fun send(
        tester: Client,
        vararg days: Map<String, Any>,
    ) = tester.post("/me/alpha-stats", mapOf("stats" to days.toList()))

    /** day, sessions, events, suggested, crashes: what the server holds for the tester, oldest day first. */
    private fun stored(tester: Client): List<List<Any>> =
        jdbc
            .sql("SELECT day, sessions, events, suggested, crashes FROM alpha_stats WHERE user_id = :id ORDER BY day")
            .param("id", tester.userId)
            .query { rs, _ ->
                listOf(
                    rs.getObject("day", LocalDate::class.java),
                    rs.getInt("sessions"),
                    rs.getInt("events"),
                    rs.getInt("suggested"),
                    rs.getInt("crashes"),
                )
            }.list()

    @Test
    fun `stores the days sent and a day sent again replaces it`() {
        val tester = signIn()
        val oldest = today.minusDays(14)

        send(tester, day(today.minusDays(1)), day(oldest, sessions = 5, events = 4, suggested = 4, crashes = 1)).expect(204)
        // The next day the device learns of a crash and sends the day again.
        send(tester, day(today.minusDays(1), sessions = 7, events = 3, suggested = 0, crashes = 2)).expect(204)

        assertThat(stored(tester)).containsExactly(listOf(oldest, 5, 4, 4, 1), listOf(today.minusDays(1), 7, 3, 0, 2))
    }

    @Test
    fun `refuses what protocol 14 does not allow and stores nothing`() {
        val tester = signIn()
        val refused =
            listOf(
                emptyList(),
                (0L..14L).map { day(today.minusDays(it)) },
                listOf(day(today.minusDays(15))),
                listOf(day(today.plusDays(2))),
                listOf(day(today, events = 1, suggested = 2)),
                listOf(day(today, crashes = -1)),
                listOf(day(today), day(today, sessions = 9)),
                listOf(mapOf("day" to today.toString(), "sessions" to 1)),
            )

        for (stats in refused) {
            tester.post("/me/alpha-stats", mapOf("stats" to stats)).expectError(400, "invalid_request")
        }

        assertThat(stored(tester)).isEmpty()
    }

    @Test
    fun `a replacement ticket cannot send counts`() {
        val subject = "alpha-ticket-${java.util.UUID.randomUUID()}"
        signIn(subject)
        val ticket = Client(api, subject).stubSignIn().expect(200).string("ticket")

        api.send(HttpMethod.POST, "/me/alpha-stats", ticket, mapOf("stats" to listOf(day(today)))).expectError(403, "replace_required")
    }

    @Test
    fun `a server without the alpha flag answers 404`() {
        val tester = signIn()
        val off = AlphaStatsController(jdbc, accounts, devices, AlphaStatsProperties(enabled = false), Clock.systemUTC())

        assertThatThrownBy { off.post(Caller(tester.userId, tester.deviceId), AlphaStatsRequest(listOf(AlphaDay(today, 1, 0, 0, 0)))) }
            .isInstanceOfSatisfying(ApiException::class.java) { assertThat(it.status).isEqualTo(HttpStatus.NOT_FOUND) }
        assertThat(stored(tester)).isEmpty()
    }
}
