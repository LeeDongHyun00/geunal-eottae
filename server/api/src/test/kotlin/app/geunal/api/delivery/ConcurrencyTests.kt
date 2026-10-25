package app.geunal.api.delivery

import app.geunal.api.support.ApiTest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.concurrent.Executors

/** api-server.md §11: the two races the delivery service exists to settle. */
class ConcurrencyTests : ApiTest() {
    @Test
    fun `exactly one of 50 concurrent commits for the same epoch is accepted`() {
        val owner = signIn()
        val member = signIn()
        val groupId = groupWith(owner, member)
        val writers = listOf(owner, member)

        val statuses =
            Executors.newVirtualThreadPerTaskExecutor().use { pool ->
                (0 until 50).map { i -> pool.submit<Int> { writers[i % 2].commit(groupId, 1).status } }.map { it.get() }
            }

        assertThat(statuses.count { it == 201 }).isEqualTo(1)
        assertThat(statuses.count { it == 409 }).isEqualTo(49)
        assertThat(owner.get("/groups/$groupId/log").expect(200).long("last_seq")).isEqualTo(2)
    }

    @Test
    fun `1000 concurrent messages get seqs with no gap and no duplicate`() {
        val owner = signIn()
        val a = signIn()
        val b = signIn()
        val groupId = groupWith(owner, a, b)
        val writers = listOf(owner, a, b)

        val seqs =
            Executors.newVirtualThreadPerTaskExecutor().use { pool ->
                (0 until 1000)
                    .map { i ->
                        pool.submit<Long> { writers[i % 3].message(groupId, 2).expect(201).long("seq") }
                    }.map { it.get() }
            }

        // Two Add commits took seqs 1 and 2.
        assertThat(seqs.sorted()).isEqualTo((3L..1002L).toList())
        val log = owner.get("/groups/$groupId/log?after=2").expect(200)
        assertThat(log["entries"].size()).isEqualTo(500)
        assertThat(log.long("last_seq")).isEqualTo(1002)
    }
}
