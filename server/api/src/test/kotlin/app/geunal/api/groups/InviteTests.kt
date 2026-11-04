package app.geunal.api.groups

import app.geunal.api.support.ApiTest
import app.geunal.api.support.Client
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant
import java.util.UUID

class InviteTests : ApiTest() {
    private fun Client.inviteCode(groupId: UUID): String = post("/groups/$groupId/invites").expect(201).string("code")

    private fun statusOf(
        viewer: Client,
        groupId: UUID,
        member: Client,
    ): String? =
        viewer
            .get("/groups/$groupId/members")
            .expect(200)["members"]
            .firstOrNull { it.path("user_id").stringValue() == member.userId.toString() }
            ?.path("status")
            ?.stringValue()

    @Test
    fun `a member makes a code that lasts seven days`() {
        val owner = signIn()
        val member = signIn()
        val groupId = groupWith(owner, member)

        val invite = member.post("/groups/$groupId/invites").expect(201)

        assertThat(invite.string("code")).matches("[0-9A-HJKMNP-TV-Z]{5}-[0-9A-HJKMNP-TV-Z]{5}")
        assertThat(invite.int("max_uses")).isEqualTo(20)
        val expires = Instant.parse(invite.string("expires_at"))
        assertThat(Duration.between(Instant.now(), expires)).isBetween(Duration.ofDays(7).minusMinutes(5), Duration.ofDays(7))
    }

    @Test
    fun `only active members make codes`() {
        val owner = signIn()
        val stranger = signIn()
        val groupId = groupWith(owner)

        stranger.post("/groups/$groupId/invites").expectError(403, "not_member")
        stranger.post("/groups/${UUID.randomUUID()}/invites").expectError(404, "not_found")
    }

    @Test
    fun `the preview shows the inviter and member count but no members`() {
        val owner = signIn()
        val member = signIn()
        val groupId = groupWith(owner, member)
        val code = owner.inviteCode(groupId)

        val preview = signIn().get("/invites/$code").expect(200)

        assertThat(preview.json!!.propertyNames()).containsExactlyInAnyOrder("inviter_name", "member_count", "expires_at", "full")
        assertThat(preview.string("inviter_name")).isEqualTo(owner.subject.take(40))
        assertThat(preview.int("member_count")).isEqualTo(2)
        assertThat(preview["full"].booleanValue()).isFalse()
    }

    @Test
    fun `joining makes the account pending and tells the group`() {
        val owner = signIn()
        val joiner = signIn()
        val groupId = groupWith(owner)
        val code = owner.inviteCode(groupId)

        inbox(owner.deviceId).use { signals ->
            val joined = joiner.post("/invites/$code/join").expect(200)
            assertThat(joined.uuid("group_id")).isEqualTo(groupId)
            assertThat(joined.string("status")).isEqualTo("pending")

            val heard = signals.drain()
            assertThat(heard.map { it.type }).containsExactly("membership")
            assertThat(
                heard
                    .single()
                    .event
                    .path("g")
                    .stringValue(),
            ).isEqualTo(groupId.toString())
        }
        assertThat(statusOf(owner, groupId, joiner)).isEqualTo("pending")
        assertThat(
            joiner
                .get("/me/groups")
                .expect(200)["groups"][0]
                .path("status")
                .stringValue(),
        ).isEqualTo("pending")

        // The pending account can now be added; the Add commit makes it active.
        owner.addUninvited(groupId, 0, joiner).expect(201)
        assertThat(statusOf(owner, groupId, joiner)).isEqualTo("active")
    }

    @Test
    fun `a code reads however it is typed`() {
        val owner = signIn()
        val groupId = groupWith(owner)
        val code = owner.inviteCode(groupId)
        val typed = code.lowercase().replace('0', 'o').replace('1', 'l')

        signIn().get("/invites/$typed").expect(200)
        signIn().post("/invites/${code.replace("-", "")}/join").expect(200)
        signIn().get("/invites/not-a-code").expectError(404, "not_found")
        signIn().post("/invites/ZZZZZ-ZZZZZ/join").expectError(404, "not_found")
    }

    @Test
    fun `joining again spends no use and keeps an active member as they are`() {
        val owner = signIn()
        val joiner = signIn()
        val groupId = groupWith(owner)
        val code = owner.inviteCode(groupId)

        joiner.post("/invites/$code/join").expect(200)
        assertThat(joiner.post("/invites/$code/join").expect(200).string("status")).isEqualTo("pending")
        owner.post("/invites/$code/join").expect(200).also { assertThat(it.string("status")).isEqualTo("active") }

        assertThat(usesOf(code)).isEqualTo(1)
        assertThat(statusOf(owner, groupId, owner)).isEqualTo("active")
    }

    @Test
    fun `an expired or used up code answers 410`() {
        val owner = signIn()
        val groupId = groupWith(owner)
        val expired = owner.inviteCode(groupId)
        val usedUp = owner.inviteCode(groupId)
        jdbc.sql("UPDATE group_invites SET expires_at = now() - interval '1 minute' WHERE code = :c").param("c", raw(expired)).update()
        jdbc.sql("UPDATE group_invites SET uses = max_uses WHERE code = :c").param("c", raw(usedUp)).update()

        signIn().post("/invites/$expired/join").expectError(410, "invite_expired")
        signIn().post("/invites/$usedUp/join").expectError(410, "invite_expired")
        assertThat(signIn().get("/invites/$usedUp").expect(200)["full"].booleanValue()).isTrue()
    }

    @Test
    fun `a group takes at most 20 members, pending ones included`() {
        val owner = signIn()
        val groupId = groupWith(owner)
        val code = owner.inviteCode(groupId)
        repeat(19) { signIn().post("/invites/$code/join").expect(200) }

        signIn().post("/invites/${owner.inviteCode(groupId)}/join").expectError(409, "group_full")
        assertThat(signIn().get("/invites/$code").expect(200)["full"].booleanValue()).isTrue()
    }

    @Test
    fun `an account is in at most 20 groups`() {
        val joiner = signIn()
        repeat(20) { joiner.post("/groups").expect(201) }

        joiner.post("/groups").expectError(409, "group_limit")
        val owner = signIn()
        joiner.post("/invites/${owner.inviteCode(groupWith(owner))}/join").expectError(409, "group_limit")

        // Leaving one frees its place.
        val left =
            joiner
                .get("/me/groups")
                .expect(200)["groups"][0]
                .path("id")
                .stringValue()
        joiner.delete("/groups/$left/members/${joiner.userId}").expect(204)
        joiner.post("/groups").expect(201)
    }

    @Test
    fun `a 1-to-1 takes no codes`() {
        val owner = signIn()
        val groupId = groupWith(owner)
        jdbc.sql("UPDATE groups SET kind = 'dm' WHERE id = :g").param("g", groupId).update()

        owner.post("/groups/$groupId/invites").expectError(400, "invalid_request")
    }

    private fun raw(code: String) = code.replace("-", "")

    private fun usesOf(code: String): Int =
        jdbc
            .sql("SELECT uses FROM group_invites WHERE code = :c")
            .param("c", raw(code))
            .query(Int::class.java)
            .single()
}
