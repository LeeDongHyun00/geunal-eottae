package app.geunal.api.ops

import org.slf4j.LoggerFactory
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

/**
 * The daily deletions of what has run out: sessions past their expiry, spent or not (a spent refresh token is kept only
 * that long for reuse detection), and replacement tickets.
 */
@Component
class Cleanup(
    private val jdbc: JdbcClient,
) {
    @Scheduled(cron = "0 15 0 * * *", zone = "UTC")
    fun daily() {
        val sessions = jdbc.sql("DELETE FROM sessions WHERE expires_at < now()").update()
        val tickets = jdbc.sql("DELETE FROM replace_tickets WHERE expires_at < now()").update()
        log.info("dropped {} expired sessions and {} expired tickets", sessions, tickets)
    }

    private companion object {
        val log = LoggerFactory.getLogger(Cleanup::class.java)
    }
}
