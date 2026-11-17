package app.geunal.api.ops

import app.geunal.api.support.ApiTest
import io.micrometer.core.instrument.MeterRegistry
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import java.time.LocalDate
import java.time.ZoneOffset
import javax.sql.DataSource

class LogPartitionsTests : ApiTest() {
    @Autowired
    lateinit var partitions: LogPartitions

    @Autowired
    lateinit var dataSource: DataSource

    @Autowired
    lateinit var metrics: MeterRegistry

    private val today: LocalDate = LocalDate.now(ZoneOffset.UTC)

    @Test
    fun `keeps today's and tomorrow's partitions and drops the one past 30 days`() {
        for (day in listOf(today.minusDays(31), today.minusDays(30))) create(day)

        partitions.ensure(today)

        val existing = partitions.existing()
        assertThat(existing).contains(today, today.plusDays(1), today.minusDays(30))
        assertThat(existing).doesNotContain(today.minusDays(31))
    }

    @Test
    fun `keeps a week of partitions ahead, however often it runs`() {
        // A run that starts from a later day is what the days in between look like after failed runs.
        partitions.ensure(today.plusDays(3))
        partitions.ensure(today)
        partitions.ensure(today)

        assertThat(partitions.existing()).containsAll((0L..10L).map { today.plusDays(it) })
        assertThat(partitions.existing()).doesNotHaveDuplicates()
    }

    @Test
    fun `leaves an expired partition it cannot lock to the next run`() {
        val day = today.minusDays(40)
        create(day)
        val failuresBefore = dropFailures()

        dataSource.connection.use { reader ->
            // A long transaction that read the old partition: dropping it has to wait for this one to end.
            reader.autoCommit = false
            reader.createStatement().use { it.execute("LOCK TABLE ${partition(day)} IN ACCESS SHARE MODE") }

            partitions.ensure(today)

            assertThat(partitions.existing()).contains(day, today)
            assertThat(dropFailures()).isEqualTo(failuresBefore + 1)
            reader.rollback()
        }

        partitions.ensure(today)
        assertThat(partitions.existing()).doesNotContain(day)
    }

    private fun dropFailures(): Double = metrics.counter(LogPartitions.FAILURES, "step", "drop").count()

    private fun partition(day: LocalDate) = "group_log_" + day.toString().replace("-", "")

    private fun create(day: LocalDate) {
        jdbc
            .sql(
                "CREATE TABLE IF NOT EXISTS ${partition(day)} PARTITION OF group_log " +
                    "FOR VALUES FROM ('${day}T00:00:00Z') TO ('${day.plusDays(1)}T00:00:00Z')",
            ).update()
    }
}
