package app.geunal.api.ops

import io.micrometer.core.instrument.MeterRegistry
import org.slf4j.LoggerFactory
import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.dao.DataAccessException
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.time.Clock
import java.time.Duration
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException

/** `geunal.log-partitions.*`. */
@ConfigurationProperties("geunal.log-partitions")
data class LogPartitionProperties(
    /** Partitions kept ready beyond today, so the job can fail for this many days before a write finds none. */
    val daysAhead: Int = 7,
    /** How long a partition's DDL waits for its lock before giving up until the next run. */
    val lockTimeout: Duration = Duration.ofSeconds(2),
)

/**
 * `group_log` is partitioned by day of receipt (UTC), and a write for a day without a partition fails. So the
 * partitions of today and the next [LogPartitionProperties.daysAhead] days are made at startup and again every hour:
 * one failed run, or several days of them, costs nothing as long as a later one succeeds, and each failure is counted
 * for the alert of infra.md §8. A partition older than the 30-day mailbox is dropped whole (api-server.md §6, §8).
 *
 * There is no DEFAULT partition to catch a missing day: rows in it would make creating that day's partition fail
 * (or scan the default under an exclusive lock), and the drop-a-day retention would no longer cover them.
 */
@Component
class LogPartitions(
    private val jdbc: JdbcClient,
    private val clock: Clock,
    private val properties: LogPartitionProperties,
    private val metrics: MeterRegistry,
    transactions: PlatformTransactionManager,
) : ApplicationRunner {
    private val transaction = TransactionTemplate(transactions)

    override fun run(args: ApplicationArguments) {
        val today = LocalDate.now(clock)
        try {
            ensure(today)
        } catch (e: DataAccessException) {
            // Without today's partition nothing can be accepted, and refusing to start shows that at the deploy.
            // With it, the days ahead can wait for the hourly run.
            if (today !in existing()) throw e
        }
    }

    @Scheduled(cron = "0 5 * * * *", zone = "UTC")
    fun hourly() {
        try {
            ensure(LocalDate.now(clock))
        } catch (_: DataAccessException) {
            // Counted and logged where it failed; the next hour tries again.
        }
    }

    /**
     * Makes the missing partitions from [today] on and drops the expired ones. Safe to repeat and to run from several
     * instances at once. Every day is tried even when one fails; the first failure to create is thrown at the end. A
     * drop that fails is only counted, as an old partition left for another hour harms nothing.
     */
    fun ensure(today: LocalDate) {
        val present = existing().toSet()
        var failure: DataAccessException? = null
        for (ahead in 0L..properties.daysAhead) {
            val day = today.plusDays(ahead)
            // A day that is there is not touched at all, so the hourly run takes no lock on the table.
            if (day in present) continue
            try {
                create(day)
            } catch (e: DataAccessException) {
                failed("create", day, e)
                failure = failure ?: e
            }
        }
        val oldest = today.minusDays(RETENTION_DAYS)
        for (day in present.filter { it < oldest }) {
            try {
                ddl("DROP TABLE IF EXISTS ${name(day)}")
                log.info("dropped log partition {}", name(day))
            } catch (e: DataAccessException) {
                failed("drop", day, e)
            }
        }
        failure?.let { throw it }
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
            ddl(
                "CREATE TABLE IF NOT EXISTS ${name(day)} PARTITION OF group_log " +
                    "FOR VALUES FROM ('${day}T00:00:00Z') TO ('${day.plusDays(1)}T00:00:00Z')",
            )
        } catch (e: DataAccessException) {
            // Two instances starting together both try; the loser's error means the partition exists.
            if (existing().none { it == day }) throw e
        }
    }

    /**
     * Creating or dropping a partition takes an exclusive lock on `group_log` itself. Waiting for it behind a long
     * transaction would queue every commit and message behind this statement, so it gives up after the lock timeout
     * (set for this transaction only) and the next run tries again.
     */
    private fun ddl(statement: String) {
        transaction.executeWithoutResult {
            jdbc
                .sql("SELECT set_config('lock_timeout', :timeout, true)")
                .param("timeout", "${properties.lockTimeout.toMillis()}ms")
                .query(String::class.java)
                .single()
            jdbc.sql(statement).update()
        }
    }

    private fun failed(
        step: String,
        day: LocalDate,
        e: DataAccessException,
    ) {
        metrics.counter(FAILURES, "step", step).increment()
        // The statement is DDL built here from a date, so the cause carries nothing of a request.
        log.error("could not {} log partition {}: {}", step, name(day), e.mostSpecificCause.toString())
    }

    private fun name(day: LocalDate) = PREFIX + DAY.format(day)

    companion object {
        /** Counter of partition statements that failed, by `step` (`create`, `drop`). */
        const val FAILURES = "geunal.log.partitions.failures"
        private const val PREFIX = "group_log_"
        private const val RETENTION_DAYS = 30L
        private val DAY: DateTimeFormatter = DateTimeFormatter.BASIC_ISO_DATE
        private val log = LoggerFactory.getLogger(LogPartitions::class.java)
    }
}
