package app.geunal.api.common

import app.geunal.api.support.ApiTest
import app.geunal.api.support.Mls
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.test.context.TestPropertySource

/** The per-minute limits of api-server.md §9 on messages, commits and key package claims. */
@TestPropertySource(
    properties = [
        "geunal.rate-limits.messages-per-minute=60",
        "geunal.rate-limits.commits-per-minute=30",
        "geunal.rate-limits.claims-per-minute=30",
    ],
)
class RateLimitTests : ApiTest() {
    @Test
    fun `answers 429 past 60 messages a minute in one group`() {
        val owner = signIn()
        val member = signIn()
        val groupId = groupWith(owner, member)
        val other = groupWith(owner)

        // The minute may turn over mid-test, which only lets more through; the limit shows within twice its count.
        val replies = (1..121).map { owner.message(groupId, 1) }
        val limited = replies.first { it.status != 201 }
        limited.expectError(429, "rate_limited")
        assertThat(limited.headers.getFirst("Retry-After")!!.toInt()).isBetween(1, 60)

        // Per user and per group: the other member and the other group are not held back.
        member.message(groupId, 1).expect(201)
        owner.message(other, 0).expect(201)
    }

    @Test
    fun `answers 429 past 30 commits a minute in one group`() {
        val owner = signIn()
        val groupId = groupWith(owner)

        val statuses = (0L..61L).map { owner.commit(groupId, it).status }

        assertThat(statuses).contains(429)
        assertThat(statuses.filter { it == 201 }.size).isLessThanOrEqualTo(60)
    }

    @Test
    fun `answers 429 past 30 key package claims a minute`() {
        val adder = signIn()
        val holder = signIn()
        holder.post("/me/key-packages", mapOf("key_packages" to List(100) { Mls.keyPackage() })).expect(200)

        val statuses = (1..61).map { adder.post("/devices/${holder.deviceId}/key-packages/claim").status }

        assertThat(statuses).contains(429)
        assertThat(statuses.filter { it == 200 }.size).isLessThanOrEqualTo(60)
    }
}
