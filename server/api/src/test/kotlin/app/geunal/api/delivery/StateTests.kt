package app.geunal.api.delivery

import app.geunal.api.support.ApiTest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.UUID
import java.util.concurrent.Executors

class StateTests : ApiTest() {
    private fun blob(seed: Int) = ByteArray(64) { (it + seed).toByte() }

    @Test
    fun `overwrites an owner's blob and signals the others`() {
        val owner = signIn()
        val member = signIn()
        val groupId = groupWith(owner, member)

        inbox(member.deviceId).use { signals ->
            assertThat(
                owner.put("/groups/$groupId/state/availability", mapOf("epoch" to 1, "ciphertext" to blob(1))).expect(200).long("version"),
            ).isEqualTo(1)
            assertThat(
                owner.put("/groups/$groupId/state/availability", mapOf("epoch" to 1, "ciphertext" to blob(2))).expect(200).long("version"),
            ).isEqualTo(2)
            member.put("/groups/$groupId/state/member_profile", mapOf("epoch" to 0, "ciphertext" to blob(3))).expect(200)

            val heard = signals.drain()
            assertThat(heard.map { it.type }).containsExactly("state", "state")
            assertThat(heard[0].event.path("g").stringValue()).isEqualTo(groupId.toString())
            assertThat(heard[0].event.path("kind").stringValue()).isEqualTo("availability")
            assertThat(heard[0].event.path("owner").stringValue()).isEqualTo(owner.userId.toString())
            assertThat(heard[0].event.path("epoch").longValue()).isEqualTo(1)
        }

        val states = member.get("/groups/$groupId/state").expect(200)["states"]
        assertThat(states.size()).isEqualTo(2)
        val availability = states.first { it.path("kind").stringValue() == "availability" }
        assertThat(availability.path("owner_id").stringValue()).isEqualTo(owner.userId.toString())
        assertThat(availability.path("ciphertext").binaryValue()).isEqualTo(blob(2))
        assertThat(availability.path("version").longValue()).isEqualTo(2)
        assertThat(availability.path("epoch").longValue()).isEqualTo(1)
        assertThat(availability.has("updated_at")).isTrue()
    }

    @Test
    fun `never goes back to an older epoch or ahead of the group`() {
        val owner = signIn()
        val groupId = groupWith(owner)

        owner.put("/groups/$groupId/state/availability", mapOf("epoch" to 1, "ciphertext" to blob(1))).expectError(400, "invalid_request")
        owner.put("/groups/$groupId/state/availability", mapOf("epoch" to 0, "ciphertext" to blob(1))).expect(200)
        owner.commit(groupId, 0).expect(201)
        owner.put("/groups/$groupId/state/availability", mapOf("epoch" to 1, "ciphertext" to blob(2))).expect(200)
        owner.put("/groups/$groupId/state/availability", mapOf("epoch" to 0, "ciphertext" to blob(3))).expectError(409, "stale_epoch")
        owner
            .put(
                "/groups/$groupId/state/availability",
                mapOf("epoch" to 1, "ciphertext" to ByteArray(16385)),
            ).expectError(413, "too_large")
        owner.put("/groups/$groupId/state/mood", mapOf("epoch" to 1, "ciphertext" to blob(1))).expectError(404, "not_found")
        signIn().put("/groups/$groupId/state/availability", mapOf("epoch" to 1, "ciphertext" to blob(1))).expectError(403, "not_member")
    }

    @Test
    fun `group_meta is one shared slot guarded by base_version`() {
        val owner = signIn()
        val member = signIn()
        val groupId = groupWith(owner, member)
        val path = "/groups/$groupId/state/group_meta"

        owner.put(path, mapOf("epoch" to 1, "ciphertext" to blob(1))).expectError(400, "invalid_request")
        owner.put(path, mapOf("epoch" to 1, "ciphertext" to blob(1), "base_version" to 3)).expectError(409, "state_conflict")
        assertThat(
            owner.put(path, mapOf("epoch" to 1, "ciphertext" to blob(1), "base_version" to 0)).expect(200).long("version"),
        ).isEqualTo(1)
        owner.put(path, mapOf("epoch" to 1, "ciphertext" to blob(2), "base_version" to 0)).expectError(409, "state_conflict")
        assertThat(
            member.put(path, mapOf("epoch" to 1, "ciphertext" to blob(3), "base_version" to 1)).expect(200).long("version"),
        ).isEqualTo(2)
        owner.put(path, mapOf("epoch" to 1, "ciphertext" to blob(4), "base_version" to 1)).expectError(409, "state_conflict")
        member.commit(groupId, 1).expect(201)
        owner.put(path, mapOf("epoch" to 2, "ciphertext" to blob(5), "base_version" to 2)).expect(200)
        member.put(path, mapOf("epoch" to 1, "ciphertext" to blob(6), "base_version" to 3)).expectError(409, "stale_epoch")

        val metas = owner.get("/groups/$groupId/state").expect(200)["states"].filter { it.path("kind").stringValue() == "group_meta" }
        assertThat(metas).hasSize(1)
        assertThat(metas[0].path("owner_id").stringValue()).isEqualTo(owner.userId.toString())
        assertThat(metas[0].path("version").longValue()).isEqualTo(3)
        assertThat(metas[0].path("ciphertext").binaryValue()).isEqualTo(blob(5))
    }

    @Test
    fun `two members writing group_meta from the same version, one wins and one gets 409`() {
        val owner = signIn()
        val member = signIn()
        val groupId = groupWith(owner, member)
        val path = "/groups/$groupId/state/group_meta"
        owner.put(path, mapOf("epoch" to 1, "ciphertext" to blob(0), "base_version" to 0)).expect(200)

        val pool = Executors.newVirtualThreadPerTaskExecutor()
        val replies =
            listOf(owner, member)
                .map { writer ->
                    pool.submit<Int> { writer.put(path, mapOf("epoch" to 1, "ciphertext" to blob(9), "base_version" to 1)).status }
                }.map { it.get() }
        assertThat(replies).containsExactlyInAnyOrder(200, 409)
    }

    @Test
    fun `an unknown group is not found before anything else`() {
        signIn().get("/groups/${UUID.randomUUID()}/state").expectError(404, "not_found")
    }
}
