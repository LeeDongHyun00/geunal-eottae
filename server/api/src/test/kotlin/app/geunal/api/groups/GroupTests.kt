package app.geunal.api.groups

import app.geunal.api.support.ApiTest
import app.geunal.api.support.Mls
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.UUID

class GroupTests : ApiTest() {
    @Test
    fun `creates a group with its creator as owner`() {
        val owner = signIn()

        val group = owner.post("/groups").expect(201)
        assertThat(group.long("epoch")).isZero()
        assertThat(group.long("last_seq")).isZero()
        assertThat(group.string("kind")).isEqualTo("circle")

        val mine = owner.get("/me/groups").expect(200)["groups"]
        assertThat(mine.size()).isEqualTo(1)
        assertThat(mine[0].path("id").stringValue()).isEqualTo(group.string("id"))
        assertThat(mine[0].path("role").stringValue()).isEqualTo("owner")
        assertThat(mine[0].path("status").stringValue()).isEqualTo("active")

        val members = owner.get("/groups/${group.string("id")}/members").expect(200)["members"]
        assertThat(members.size()).isEqualTo(1)
        assertThat(members[0].path("user_id").stringValue()).isEqualTo(owner.userId.toString())
        assertThat(members[0].path("device_id").stringValue()).isEqualTo(owner.deviceId.toString())
    }

    @Test
    fun `keeps the member list to active members`() {
        val owner = signIn()
        val stranger = signIn()
        val groupId = groupWith(owner)

        stranger.get("/groups/$groupId/members").expectError(403, "not_member")
        stranger.get("/groups/${UUID.randomUUID()}/members").expectError(404, "not_found")
    }

    @Test
    fun `publishes the GroupInfo of the current epoch`() {
        val owner = signIn()
        val groupId = owner.post("/groups").expect(201).uuid("id")

        owner.get("/groups/$groupId/group-info").expectError(404, "not_found")
        val info = Mls.groupInfo(groupId, 0)
        owner.put("/groups/$groupId/group-info", mapOf("epoch" to 0, "group_info" to info)).expect(204)
        val served = owner.get("/groups/$groupId/group-info").expect(200)
        assertThat(served.long("epoch")).isZero()
        assertThat(served.bytes("group_info")).isEqualTo(info)

        // The bytes say epoch 1 but the request says 0.
        owner
            .put(
                "/groups/$groupId/group-info",
                mapOf("epoch" to 0, "group_info" to Mls.groupInfo(groupId, 1)),
            ).expectError(400, "invalid_request")
        // Consistent, but the group is still at epoch 0.
        owner
            .put(
                "/groups/$groupId/group-info",
                mapOf("epoch" to 1, "group_info" to Mls.groupInfo(groupId, 1)),
            ).expectError(409, "stale_epoch")
        // Another group's GroupInfo.
        owner
            .put(
                "/groups/$groupId/group-info",
                mapOf("epoch" to 0, "group_info" to Mls.groupInfo(UUID.randomUUID(), 0)),
            ).expectError(400, "invalid_request")
    }

    @Test
    fun `a member leaves and everyone involved hears membership`() {
        val owner = signIn()
        val leaver = signIn()
        val other = signIn()
        val groupId = groupWith(owner, leaver, other)

        inbox(owner.deviceId, leaver.deviceId, other.deviceId).use { signals ->
            leaver.delete("/groups/$groupId/members/${leaver.userId}").expect(204)

            val heard = signals.drain()
            assertThat(heard.map { it.type }).containsOnly("membership")
            assertThat(
                heard.map { it.channel },
            ).containsExactlyInAnyOrder("d:${owner.deviceId}", "d:${leaver.deviceId}", "d:${other.deviceId}")
            assertThat(
                heard
                    .first()
                    .event
                    .path("g")
                    .stringValue(),
            ).isEqualTo(groupId.toString())
        }
        val statuses =
            owner.get("/groups/$groupId/members").expect(200)["members"].associate {
                it.path("user_id").stringValue() to
                    it.path("status").stringValue()
            }
        assertThat(statuses[leaver.userId.toString()]).isEqualTo("removed")
        leaver.get("/groups/$groupId/members").expectError(403, "not_member")
        leaver.message(groupId, 2).expectError(403, "not_member")
        assertThat(leaver.get("/me/groups").expect(200)["groups"].size()).isZero()
        leaver.delete("/groups/$groupId/members/${leaver.userId}").expectError(403, "not_member")
    }

    @Test
    fun `only the owner removes others`() {
        val owner = signIn()
        val a = signIn()
        val b = signIn()
        val groupId = groupWith(owner, a, b)

        a.delete("/groups/$groupId/members/${b.userId}").expectError(403, "not_owner")
        owner.delete("/groups/$groupId/members/${b.userId}").expect(204)
        owner.delete("/groups/$groupId/members/${b.userId}").expectError(404, "not_found")
        owner.delete("/groups/$groupId/members/${UUID.randomUUID()}").expectError(404, "not_found")
    }

    @Test
    fun `the longest-standing active member succeeds an owner who leaves`() {
        val owner = signIn()
        val first = signIn()
        val second = signIn()
        val groupId = groupWith(owner, first, second)

        owner.delete("/groups/$groupId/members/${owner.userId}").expect(204)

        val roles =
            first.get("/groups/$groupId/members").expect(200)["members"].associate {
                it.path("user_id").stringValue() to
                    it.path("role").stringValue()
            }
        assertThat(roles[first.userId.toString()]).isEqualTo("owner")
        assertThat(roles[second.userId.toString()]).isEqualTo("member")
        first.delete("/groups/$groupId/members/${second.userId}").expect(204)
    }
}
