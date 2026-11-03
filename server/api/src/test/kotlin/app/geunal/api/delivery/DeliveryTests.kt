package app.geunal.api.delivery

import app.geunal.api.mls.MlsHeader
import app.geunal.api.support.ApiTest
import app.geunal.api.support.Mls
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

class DeliveryTests : ApiTest() {
    @Test
    fun `an Add commit stores the Welcome, activates the member and signals both sides`() {
        val owner = signIn()
        val joiner = signIn()
        val watcher = signIn()
        val groupId = groupWith(owner, watcher)

        inbox(owner.deviceId, joiner.deviceId, watcher.deviceId).use { signals ->
            val accepted = owner.addMember(groupId, 1, joiner)
            assertThat(accepted.long("seq")).isEqualTo(2)
            assertThat(accepted.long("epoch")).isEqualTo(1)
            val receivedAt = accepted.string("received_at")
            assertThat(Instant.parse(receivedAt)).isBetween(Instant.now().minusSeconds(60), Instant.now().plusSeconds(60))

            val heard = signals.drain().associateBy { it.channel }
            assertThat(heard.keys).containsExactlyInAnyOrder("d:${joiner.deviceId}", "d:${watcher.deviceId}")
            assertThat(heard["d:${joiner.deviceId}"]!!.type).isEqualTo("welcome")
            val entry = heard["d:${watcher.deviceId}"]!!.event
            assertThat(entry.path("t").stringValue()).isEqualTo("entry")
            assertThat(entry.path("g").stringValue()).isEqualTo(groupId.toString())
            assertThat(entry.path("s").longValue()).isEqualTo(2)
            assertThat(entry.path("kind").stringValue()).isEqualTo("commit")
            assertThat(entry.path("at").stringValue()).describedAs("the event carries the server time of the entry").isEqualTo(receivedAt)
            assertThat(entry.path("c").binaryValue()).isEqualTo(Mls.commit(groupId, 1))
        }

        val welcomes = joiner.get("/me/welcomes").expect(200)["welcomes"]
        assertThat(welcomes.size()).isEqualTo(1)
        assertThat(welcomes[0].path("group_id").stringValue()).isEqualTo(groupId.toString())
        assertThat(
            welcomes[0].path("seq").longValue(),
        ).describedAs("the Add commit's seq, where the new member starts reading").isEqualTo(2)
        assertThat(welcomes[0].path("data").binaryValue()).isEqualTo(Mls.welcome())
        assertThat(joiner.get("/me/welcomes").expect(200)["welcomes"].size()).describedAs("a Welcome is read once").isZero()

        val mine = joiner.get("/me/groups").expect(200)["groups"]
        assertThat(mine.size()).isEqualTo(1)
        assertThat(mine[0].path("epoch").longValue()).isEqualTo(2)
        assertThat(mine[0].path("last_seq").longValue()).isEqualTo(2)
        assertThat(mine[0].path("status").stringValue()).isEqualTo("active")
        assertThat(joiner.get("/groups/$groupId/group-info").expect(200).long("epoch")).isEqualTo(2)
    }

    @Test
    fun `only one commit wins an epoch`() {
        val owner = signIn()
        val member = signIn()
        val groupId = groupWith(owner, member)

        owner.commit(groupId, 1).expect(201)
        member.commit(groupId, 1).expectError(409, "epoch_conflict")
        member.commit(groupId, 2).expect(201)
        assertThat(member.get("/groups/$groupId/group-info").expect(200).long("epoch")).isEqualTo(3)
    }

    @Test
    fun `messages need the current epoch and get consecutive seqs`() {
        val owner = signIn()
        val member = signIn()
        val groupId = groupWith(owner, member)

        inbox(owner.deviceId, member.deviceId).use { signals ->
            val first = owner.message(groupId, 1).expect(201)
            assertThat(first.long("seq")).isEqualTo(2)
            assertThat(member.message(groupId, 1, size = 5000).expect(201).long("seq")).isEqualTo(3)
            owner.message(groupId, 0).expectError(409, "stale_epoch")
            owner.message(groupId, 2).expectError(409, "stale_epoch")

            val heard = signals.drain()
            assertThat(heard.map { it.channel }).containsExactly("d:${member.deviceId}", "d:${owner.deviceId}")
            assertThat(heard[0].event.path("c").binaryValue()).isEqualTo(Mls.privateMessage(groupId, 1))
            assertThat(heard[0].event.path("at").stringValue()).isEqualTo(first.string("received_at"))
            val logged = member.get("/groups/$groupId/log?after=1&limit=1").expect(200)["entries"][0]
            assertThat(
                logged.path("received_at").stringValue(),
            ).describedAs("201, event and log agree on the server time").isEqualTo(first.string("received_at"))
            assertThat(heard[1].event.has("c")).describedAs("ciphertext over 4 KB is fetched, not pushed").isFalse()
            assertThat(heard[1].event.path("kind").stringValue()).isEqualTo("app")
        }
    }

    @Test
    fun `checks the MLS header against the request`() {
        val owner = signIn()
        val groupId = groupWith(owner)
        val other = UUID.randomUUID()

        // A private message on the commit endpoint, a public one on the message endpoint.
        owner
            .post(
                "/groups/$groupId/commits",
                mapOf(
                    "epoch" to 0,
                    "commit" to Mls.privateMessage(groupId, 0),
                    "group_info" to Mls.groupInfo(groupId, 1),
                ),
            ).expectError(400, "invalid_request")
        owner
            .post(
                "/groups/$groupId/messages",
                mapOf("epoch" to 0, "message" to Mls.commit(groupId, 0), "alert" to "none"),
            ).expectError(400, "invalid_request")
        // Another group's bytes, another epoch's bytes, a proposal instead of a commit.
        owner
            .post(
                "/groups/$groupId/commits",
                mapOf(
                    "epoch" to 0,
                    "commit" to Mls.commit(other, 0),
                    "group_info" to Mls.groupInfo(groupId, 1),
                ),
            ).expectError(400, "invalid_request")
        owner
            .post(
                "/groups/$groupId/commits",
                mapOf(
                    "epoch" to 0,
                    "commit" to Mls.commit(groupId, 1),
                    "group_info" to Mls.groupInfo(groupId, 1),
                ),
            ).expectError(400, "invalid_request")
        owner
            .post(
                "/groups/$groupId/commits",
                mapOf(
                    "epoch" to 0,
                    "commit" to Mls.commit(groupId, 0, contentType = MlsHeader.CONTENT_PROPOSAL),
                    "group_info" to Mls.groupInfo(groupId, 1),
                ),
            ).expectError(400, "invalid_request")
        // GroupInfo must be for the epoch the commit creates.
        owner
            .post(
                "/groups/$groupId/commits",
                mapOf(
                    "epoch" to 0,
                    "commit" to Mls.commit(groupId, 0),
                    "group_info" to Mls.groupInfo(groupId, 0),
                ),
            ).expectError(400, "invalid_request")
        owner
            .post(
                "/groups/$groupId/messages",
                mapOf("epoch" to 0, "message" to Mls.privateMessage(other, 0), "alert" to "none"),
            ).expectError(400, "invalid_request")
        owner
            .post(
                "/groups/$groupId/messages",
                mapOf("epoch" to 0, "message" to byteArrayOf(0, 1), "alert" to "none"),
            ).expectError(400, "invalid_request")
        // Nothing was accepted.
        assertThat(owner.get("/groups/$groupId/log").expect(200).long("last_seq")).isZero()
    }

    @Test
    fun `a Welcome and its added devices come together`() {
        val owner = signIn()
        val joiner = signIn()
        val groupId = groupWith(owner)
        val base = mapOf("epoch" to 0, "commit" to Mls.commit(groupId, 0), "group_info" to Mls.groupInfo(groupId, 1))

        owner.post("/groups/$groupId/commits", base + ("welcome" to Mls.welcome())).expectError(400, "invalid_request")
        owner.post("/groups/$groupId/commits", base + ("added" to listOf(joiner.deviceId))).expectError(400, "invalid_request")
        owner
            .post(
                "/groups/$groupId/commits",
                base + ("added" to listOf(UUID.randomUUID())) + ("welcome" to Mls.welcome()),
            ).expectError(404, "not_found")
        owner
            .post(
                "/groups/$groupId/commits",
                base + ("added" to listOf(joiner.deviceId)) + ("welcome" to Mls.keyPackage()),
            ).expectError(400, "invalid_request")
        owner.post("/groups/$groupId/commits", base + ("commit" to Mls.commit(groupId, 0, size = 65537))).expectError(413, "too_large")
    }

    @Test
    fun `an external commit resyncs an active member and nobody else`() {
        val owner = signIn()
        val member = signIn()
        val stranger = signIn()
        val groupId = groupWith(owner, member)

        fun external(epoch: Long) =
            mapOf(
                "epoch" to epoch,
                "commit" to Mls.externalCommit(groupId, epoch),
                "group_info" to Mls.groupInfo(groupId, epoch + 1),
            )

        stranger.post("/groups/$groupId/commits", external(1)).expectError(403, "not_member")
        val accepted = member.post("/groups/$groupId/commits", external(1)).expect(201)
        assertThat(accepted.long("seq")).isEqualTo(2)
        owner.delete("/groups/$groupId/members/${member.userId}").expect(204)
        member.post("/groups/$groupId/commits", external(2)).expectError(403, "not_member")
    }

    @Test
    fun `a removed member can be added again by a commit`() {
        val owner = signIn()
        val member = signIn()
        val groupId = groupWith(owner, member)

        assertThat(member.get("/me/welcomes").expect(200)["welcomes"].size()).isEqualTo(1)
        owner.delete("/groups/$groupId/members/${member.userId}").expect(204)
        member.message(groupId, 1).expectError(403, "not_member")
        owner.addMember(groupId, 1, member)

        member.message(groupId, 2).expect(201)
        val welcomes = member.get("/me/welcomes").expect(200)["welcomes"]
        assertThat(welcomes.size()).isEqualTo(1)
        assertThat(welcomes[0].path("seq").longValue()).isEqualTo(2)
        val roles =
            owner.get("/groups/$groupId/members").expect(200)["members"].associate {
                it.path("user_id").stringValue() to
                    it.path("role").stringValue()
            }
        assertThat(roles[member.userId.toString()]).isEqualTo("member")
    }

    @Test
    fun `adding an already-active account's device keeps its role and join time`() {
        val owner = signIn("owner-with-new-phone")
        val member = signIn()
        val groupId = groupWith(owner, member)

        fun ownerRow() =
            member.get("/groups/$groupId/members").expect(200)["members"].first {
                it.path("user_id").stringValue() ==
                    owner.userId.toString()
            }
        val joinedBefore = ownerRow().path("joined_at").stringValue()
        // The owner switches phones; the member adds the new device by an Add commit.
        val replacement = replaceDevice("owner-with-new-phone")

        member.addMember(groupId, 1, replacement)

        val ownerRow = ownerRow()
        assertThat(ownerRow.path("role").stringValue()).isEqualTo("owner")
        assertThat(ownerRow.path("status").stringValue()).isEqualTo("active")
        assertThat(ownerRow.path("joined_at").stringValue()).isEqualTo(joinedBefore)
        assertThat(ownerRow.path("device_id").stringValue()).isEqualTo(replacement.deviceId.toString())
        replacement.delete("/groups/$groupId/members/${member.userId}").expect(204)
    }

    @Test
    fun `the log comes back in order, in pages, up to last_seq`() {
        val owner = signIn()
        val member = signIn()
        val groupId = groupWith(owner, member)
        repeat(4) { owner.message(groupId, 1).expect(201) }

        val all = member.get("/groups/$groupId/log").expect(200)
        assertThat(all.long("last_seq")).isEqualTo(5)
        assertThat(all["entries"].values().map { it.path("seq").longValue() }).containsExactly(1L, 2L, 3L, 4L, 5L)
        assertThat(all["entries"].values().map { it.path("kind").stringValue() }).containsExactly("commit", "app", "app", "app", "app")
        assertThat(all["entries"][0].path("author_id").stringValue()).isEqualTo(owner.userId.toString())
        assertThat(all["entries"][0].path("epoch").longValue()).isEqualTo(0)
        assertThat(all["entries"][1].path("epoch").longValue()).isEqualTo(1)
        assertThat(all["entries"][1].path("ciphertext").binaryValue()).isEqualTo(Mls.privateMessage(groupId, 1))
        assertThat(all["entries"][1].has("received_at")).isTrue()

        val page = member.get("/groups/$groupId/log?after=1&limit=2").expect(200)
        assertThat(page["entries"].values().map { it.path("seq").longValue() }).containsExactly(2L, 3L)
        assertThat(page.long("last_seq")).isEqualTo(5)
        assertThat(member.get("/groups/$groupId/log?after=5").expect(200)["entries"].size()).isZero()
        assertThat(member.get("/groups/$groupId/log?after=9").expect(200)["entries"].size()).isZero()
        member.get("/groups/$groupId/log?after=-1").expectError(400, "invalid_request")
        member.get("/groups/$groupId/log?after=x").expectError(400, "invalid_request")
        signIn().get("/groups/$groupId/log").expectError(403, "not_member")
    }

    @Test
    fun `a cursor behind what the mailbox still keeps is expired`() {
        val owner = signIn()
        val groupId = groupWith(owner)
        repeat(3) { owner.message(groupId, 0).expect(201) }
        // Stand in for the daily partition drop: the first two entries are gone.
        jdbc.sql("DELETE FROM group_log WHERE group_id = :g AND seq <= 2").param("g", groupId).update()

        owner.get("/groups/$groupId/log").expectError(410, "cursor_expired")
        owner.get("/groups/$groupId/log?after=1").expectError(410, "cursor_expired")
        val kept = owner.get("/groups/$groupId/log?after=2").expect(200)
        assertThat(kept["entries"].values().map { it.path("seq").longValue() }).containsExactly(3L)
        assertThat(kept.long("last_seq")).isEqualTo(3)
        assertThat(owner.get("/groups/$groupId/log?after=3").expect(200)["entries"].size()).isZero()
    }

    @Test
    fun `alert and cat follow the push rules`() {
        val owner = signIn()
        val groupId = groupWith(owner)

        owner.message(groupId, 0, alert = "none", cat = null).expect(201)
        owner.message(groupId, 0, alert = listOf(owner.userId), cat = "plan").expect(201)
        owner.message(groupId, 0, alert = "all", cat = null).expectError(400, "invalid_request")
        owner.message(groupId, 0, alert = "everyone", cat = "chat").expectError(400, "invalid_request")
        owner.message(groupId, 0, alert = listOf("nobody"), cat = "chat").expectError(400, "invalid_request")
        owner.message(groupId, 0, alert = "all", cat = "news").expectError(400, "invalid_request")
    }
}
