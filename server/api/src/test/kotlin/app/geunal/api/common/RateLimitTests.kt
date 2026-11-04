package app.geunal.api.common

import app.geunal.api.support.ApiTest
import app.geunal.api.support.Mls
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.test.context.TestPropertySource

/** The limits of api-server.md §9 on messages, commits, key package claims, invites and new groups. */
@TestPropertySource(
    properties = [
        "geunal.rate-limits.messages-per-minute=60",
        "geunal.rate-limits.commits-per-minute=30",
        "geunal.rate-limits.claims-per-minute=30",
        "geunal.rate-limits.invite-uses-per-hour=10",
        "geunal.rate-limits.invites-per-hour=20",
        "geunal.rate-limits.groups-per-hour=10",
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
        adder.invite(adder.post("/groups").expect(201).uuid("id"), holder)
        holder.post("/me/key-packages", mapOf("key_packages" to List(100) { Mls.keyPackage() })).expect(200)

        val statuses = (1..61).map { adder.post("/devices/${holder.deviceId}/key-packages/claim").status }

        assertThat(statuses).contains(429)
        assertThat(statuses.filter { it == 200 }.size).isLessThanOrEqualTo(60)
    }

    @Test
    fun `answers 429 past 10 invite joins an hour from one address`() {
        val joiner = signIn()

        // Every test comes from the same address, so earlier ones may have spent some of the hour already.
        val statuses = (1..11).map { joiner.post("/invites/AAAAA-AAAAA/join").status }

        assertThat(statuses).contains(429)
        assertThat(statuses.filter { it == 404 }.size).isLessThanOrEqualTo(10)
    }

    @Test
    fun `answers 429 past 20 invite codes an hour from one user`() {
        val owner = signIn()
        val groupId = owner.post("/groups").expect(201).uuid("id")

        val statuses = (1..21).map { owner.post("/groups/$groupId/invites").status }

        assertThat(statuses.take(20)).containsOnly(201)
        assertThat(statuses.last()).isEqualTo(429)
    }

    @Test
    fun `answers 429 past 10 new groups an hour from one user`() {
        val owner = signIn()

        val statuses = (1..11).map { owner.post("/groups").status }

        assertThat(statuses.take(10)).containsOnly(201)
        assertThat(statuses.last()).isEqualTo(429)
    }
}
