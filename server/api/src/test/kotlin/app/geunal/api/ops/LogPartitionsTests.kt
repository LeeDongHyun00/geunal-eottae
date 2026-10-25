package app.geunal.api.ops

import app.geunal.api.support.ApiTest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import java.time.LocalDate
import java.time.ZoneOffset

class LogPartitionsTests : ApiTest() {
    @Autowired
    lateinit var partitions: LogPartitions

    @Test
    fun `keeps today's and tomorrow's partitions and drops the one past 30 days`() {
        val today = LocalDate.now(ZoneOffset.UTC)

        fun partition(day: LocalDate) = "group_log_" + day.toString().replace("-", "")
        for (day in listOf(today.minusDays(31), today.minusDays(30))) {
            jdbc
                .sql(
                    "CREATE TABLE IF NOT EXISTS ${partition(
                        day,
                    )} PARTITION OF group_log FOR VALUES FROM ('${day}T00:00:00Z') TO ('${day.plusDays(1)}T00:00:00Z')",
                ).update()
        }

        partitions.ensure(today)

        val existing = partitions.existing()
        assertThat(existing).contains(today, today.plusDays(1), today.minusDays(30))
        assertThat(existing).doesNotContain(today.minusDays(31))
    }
}
