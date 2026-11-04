package app.geunal.api.ops

import app.geunal.api.support.ApiTest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired

/** api-server.md §8: the daily cleanup drops Welcomes nobody fetched for 30 days. */
class WelcomeCleanupTests : ApiTest() {
    @Autowired
    lateinit var cleanup: Cleanup

    @Test
    fun `the daily cleanup drops Welcomes older than 30 days and keeps newer ones`() {
        val owner = signIn()
        val joiner = signIn()
        val stale = groupWith(owner, joiner)
        val fresh = groupWith(owner, joiner)
        jdbc
            .sql("UPDATE welcomes SET created_at = now() - interval '31 days' WHERE group_id = :group")
            .param("group", stale)
            .update()

        cleanup.daily()

        val left = joiner.get("/me/welcomes").expect(200)["welcomes"]
        val groups = (0 until left.size()).map { left[it].path("group_id").stringValue() }
        assertThat(groups).containsExactly(fresh.toString())
    }
}
