package app.geunal.api.ops

import org.slf4j.LoggerFactory
import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.dao.DataAccessException
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.Clock
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException

/**
 * `group_log` is partitioned by day of receipt (UTC). Today's and tomorrow's partitions must exist before anything is
 * accepted, so they are made at startup and after each midnight; a partition older than the 30-day mailbox is dropped
 * whole (api-server.md §6, §8).
 */
@Component
class LogPartitions(
    private val jdbc: JdbcClient,
    private val clock: Clock,
) : ApplicationRunner {
    override fun run(args: ApplicationArguments) = ensure(LocalDate.now(clock))

    @Scheduled(cron = "0 5 0 * * *", zone = "UTC")
    fun daily() = ensure(LocalDate.now(clock))

    fun ensure(today: LocalDate) {
        create(today)
        create(today.plusDays(1))
        val oldest = today.minusDays(RETENTION_DAYS)
        for (day in existing().filter { it < oldest }) {
            jdbc.sql("DROP TABLE IF EXISTS ${name(day)}").update()
            log.info("dropped log partition {}", name(day))
        }
    }

    /** Days that have a partition, read back from the catalog. */
    fun existing(): List<LocalDate> =
        jdbc
            .sql(
                """
                SELECT c.relname FROM pg_inherits i JOIN pg_class c ON c.oid = i.inhrelid JOIN pg_class p ON p.oid = i.inhparent
                WHERE p.relname = 'group_log'
                """,
            ).query(String::class.java)
            .list()
            .mapNotNull { name ->
                try {
                    LocalDate.parse(name?.removePrefix(PREFIX), DAY)
                } catch (_: DateTimeParseException) {
                    null
                }
            }

    private fun create(day: LocalDate) {
        try {
            jdbc
                .sql(
                    "CREATE TABLE IF NOT EXISTS ${name(
                        day,
                    )} PARTITION OF group_log FOR VALUES FROM ('${day}T00:00:00Z') TO ('${day.plusDays(1)}T00:00:00Z')",
                ).update()
        } catch (e: DataAccessException) {
            // Two instances starting together both try; the loser's error means the partition exists.
            if (existing().none { it == day }) throw e
        }
    }

    private fun name(day: LocalDate) = PREFIX + DAY.format(day)

    private companion object {
        const val PREFIX = "group_log_"
        const val RETENTION_DAYS = 30L
        val DAY: DateTimeFormatter = DateTimeFormatter.BASIC_ISO_DATE
        val log = LoggerFactory.getLogger(LogPartitions::class.java)
    }
}
