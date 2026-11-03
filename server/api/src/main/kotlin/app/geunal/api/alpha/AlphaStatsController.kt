package app.geunal.api.alpha

import app.geunal.api.auth.AccountRepository
import app.geunal.api.auth.Caller
import app.geunal.api.common.ApiException
import app.geunal.api.devices.DeviceRepository
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.http.HttpStatus
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.time.Clock
import java.time.LocalDate
import java.time.ZoneId

/**
 * `geunal.alpha-stats.enabled` (`GEUNAL_ALPHA_STATS_ENABLED`): the alpha server turns it on; any other server answers
 * 404, so a public build could not report even if it tried (protocol §14).
 */
@ConfigurationProperties("geunal.alpha-stats")
data class AlphaStatsProperties(
    val enabled: Boolean = false,
)

/** One Korean day of an opted-in tester's device: numbers only (protocol §14, kickoff §6). */
data class AlphaDay(
    val day: LocalDate,
    val sessions: Int,
    val events: Int,
    val suggested: Int,
    val crashes: Int,
)

data class AlphaStatsRequest(
    val stats: List<AlphaDay>,
)

/** `POST /me/alpha-stats`: the M1 gate's counts (plan.md §3). A day sent again replaces what the server had for it. */
@RestController
class AlphaStatsController(
    private val jdbc: JdbcClient,
    private val accounts: AccountRepository,
    private val devices: DeviceRepository,
    private val properties: AlphaStatsProperties,
    private val clock: Clock,
) {
    @PostMapping("/me/alpha-stats")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Transactional
    fun post(
        @AuthenticationPrincipal caller: Caller,
        @RequestBody body: AlphaStatsRequest,
    ) {
        if (!properties.enabled) throw ApiException.notFound("alpha stats")
        validate(body.stats)
        // Under the account lock, like every other write that a deletion's erase could race: no row outlives the account.
        accounts.lock(caller.userId)
        devices.current(caller.deviceId) ?: throw devices.revokedError(caller.deviceId)
        for (day in body.stats) {
            jdbc
                .sql(
                    """
                    INSERT INTO alpha_stats (user_id, day, sessions, events, suggested, crashes)
                    VALUES (:user, :day, :sessions, :events, :suggested, :crashes)
                    ON CONFLICT (user_id, day) DO UPDATE SET sessions = EXCLUDED.sessions, events = EXCLUDED.events,
                      suggested = EXCLUDED.suggested, crashes = EXCLUDED.crashes, updated_at = now()
                    """,
                ).param("user", caller.userId)
                .param("day", day.day)
                .param("sessions", day.sessions)
                .param("events", day.events)
                .param("suggested", day.suggested)
                .param("crashes", day.crashes)
                .update()
        }
    }

    private fun validate(days: List<AlphaDay>) {
        if (days.isEmpty() || days.size > MAX_DAYS) throw ApiException.invalid("stats must hold 1 to $MAX_DAYS days")
        if (days.distinctBy { it.day }.size != days.size) throw ApiException.invalid("each day may appear once")
        val today = LocalDate.now(clock.withZone(SEOUL))
        for (day in days) {
            // A device whose clock runs a little ahead may already be in tomorrow; anything later is not a real day yet.
            if (day.day < today.minusDays(MAX_DAYS.toLong()) || day.day > today.plusDays(1)) {
                throw ApiException.invalid("day must be one of the last $MAX_DAYS days in Asia/Seoul")
            }
            if (minOf(day.sessions, day.events, day.suggested, day.crashes) < 0) throw ApiException.invalid("counts cannot be negative")
            if (day.suggested > day.events) throw ApiException.invalid("suggested cannot exceed events")
        }
    }

    private companion object {
        /** A device keeps the days it could not send for this long and sends them together (protocol §14). */
        const val MAX_DAYS = 14
        val SEOUL: ZoneId = ZoneId.of("Asia/Seoul")
    }
}
