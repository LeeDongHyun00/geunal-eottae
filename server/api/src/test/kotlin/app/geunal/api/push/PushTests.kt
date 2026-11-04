package app.geunal.api.push

import app.geunal.api.support.ApiTest
import app.geunal.api.support.Client
import app.geunal.api.support.FakePush
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import tools.jackson.databind.JsonNode
import java.util.UUID

class PushTests : ApiTest() {
    private fun Client.registerToken(): String = "tok-${UUID.randomUUID()}".also { put("/me/push-token", mapOf("token" to it)).expect(204) }

    /** Pushes go out on virtual threads after the commit; wait for [count] to reach [token], then a moment for strays. */
    private fun pushesTo(
        token: String,
        count: Int = 1,
    ): List<FakePush.Sent> {
        val until = System.nanoTime() + 5_000_000_000
        while (FakePush.to(token).size < count && System.nanoTime() < until) Thread.sleep(20)
        Thread.sleep(100)
        return FakePush.to(token)
    }

    private fun json(sent: FakePush.Sent): JsonNode = mapper.readTree(sent.body)

    @Test
    fun `a message pushes to the other members on both platforms`() {
        val sender = signIn()
        val iphone = signIn(platform = "ios")
        val android = signIn(platform = "android")
        val groupId = groupWith(sender, iphone, android)
        val senderToken = sender.registerToken()
        val iosToken = iphone.registerToken()
        val androidToken = android.registerToken()

        val reply = sender.message(groupId, 2, cat = "plan").expect(201)

        val ios = pushesTo(iosToken).single()
        assertThat(ios.headers["apns-topic"]).isEqualTo("app.geunal.ios")
        assertThat(ios.headers["apns-push-type"]).isEqualTo("alert")
        assertThat(ios.headers["authorization"]).startsWith("bearer ")
        val apns = json(ios)
        assertThat(apns.path("aps").path("mutable-content").intValue()).isEqualTo(1)
        assertThat(apns.path("g").stringValue()).isEqualTo(groupId.toString())
        assertThat(apns.path("s").stringValue()).isEqualTo(reply.long("seq").toString())
        assertThat(apns.path("at").stringValue()).isEqualTo(reply.string("received_at"))
        assertThat(apns.path("c").stringValue()).isNotBlank()

        val fcm = json(pushesTo(androidToken).single()).path("message")
        assertThat(fcm.path("android").path("priority").stringValue()).isEqualTo("high")
        assertThat(fcm.has("notification")).isFalse()
        assertThat(fcm.path("data").propertyNames()).containsExactlyInAnyOrder("g", "s", "at", "c")
        assertThat(pushesTo(androidToken).single().headers["authorization"]).isEqualTo("Bearer fcm-access")

        assertThat(FakePush.to(senderToken)).isEmpty()
    }

    @Test
    fun `alert names, mutes and category switches decide who hears`() {
        val sender = signIn()
        val named = signIn()
        val unnamed = signIn()
        val groupId = groupWith(sender, named, unnamed)
        val namedToken = named.registerToken()
        val unnamedToken = unnamed.registerToken()

        sender.message(groupId, 2, alert = listOf(named.userId.toString()), cat = "plan").expect(201)
        assertThat(pushesTo(namedToken)).hasSize(1)
        assertThat(FakePush.to(unnamedToken)).isEmpty()

        sender.message(groupId, 2, alert = "none", cat = null).expect(201)
        named.patch("/groups/$groupId/members/me", mapOf("notify_chat" to false)).expect(200)
        sender.message(groupId, 2, alert = "all", cat = "chat").expect(201)
        assertThat(pushesTo(unnamedToken)).hasSize(1)
        assertThat(FakePush.to(namedToken)).hasSize(1)

        unnamed.patch("/groups/$groupId/members/me", mapOf("muted_until" to "2999-01-01T00:00:00Z")).expect(200)
        sender.message(groupId, 2, alert = "all", cat = "plan").expect(201)
        assertThat(pushesTo(namedToken, 2)).hasSize(2)
        assertThat(FakePush.to(unnamedToken)).hasSize(1)

        unnamed.patch("/groups/$groupId/members/me", mapOf("muted_until" to null)).expect(200)
        sender.message(groupId, 2, alert = "all", cat = "plan").expect(201)
        assertThat(pushesTo(unnamedToken, 2)).hasSize(2)
    }

    @Test
    fun `a token the platform calls dead is forgotten`() {
        val sender = signIn()
        val iphone = signIn(platform = "ios")
        val android = signIn(platform = "android")
        val groupId = groupWith(sender, iphone, android)
        val iosToken = iphone.registerToken().also { FakePush.dead += it }
        val androidToken = android.registerToken().also { FakePush.dead += it }

        sender.message(groupId, 2).expect(201)
        pushesTo(iosToken)
        pushesTo(androidToken)

        val tokens =
            jdbc
                .sql("SELECT push_token FROM devices WHERE id IN (:ids)")
                .param("ids", listOf(iphone.deviceId, android.deviceId))
                .query(String::class.java)
                .list()
        assertThat(tokens).containsOnlyNulls()
    }

    @Test
    fun `a large message leaves its ciphertext out`() {
        val sender = signIn()
        val member = signIn()
        val groupId = groupWith(sender, member)
        val token = member.registerToken()

        sender.message(groupId, 1, size = 4000).expect(201)

        assertThat(json(pushesTo(token).single()).has("c")).isFalse()
    }

    @Test
    fun `members set their own push settings`() {
        val owner = signIn()
        val member = signIn()
        val groupId = groupWith(owner, member)

        val set =
            member.patch("/groups/$groupId/members/me", mapOf("muted_until" to "2030-01-01T00:00:00Z", "notify_plan" to false)).expect(200)
        assertThat(set.string("muted_until")).isEqualTo("2030-01-01T00:00:00Z")
        assertThat(set["notify_chat"].booleanValue()).isTrue()
        assertThat(set["notify_plan"].booleanValue()).isFalse()
        val mine = member.get("/me/groups").expect(200)["groups"][0]
        assertThat(mine.path("notify_plan").booleanValue()).isFalse()
        assertThat(mine.path("muted_until").stringValue()).isEqualTo("2030-01-01T00:00:00Z")

        member.patch("/groups/$groupId/members/me", mapOf("role" to "owner")).expectError(400, "invalid_request")
        member.patch("/groups/$groupId/members/me", mapOf("notify_chat" to "no")).expectError(400, "invalid_request")
        member.patch("/groups/$groupId/members/me", mapOf("muted_until" to "tomorrow")).expectError(400, "invalid_request")
        signIn().patch("/groups/$groupId/members/me", mapOf("notify_chat" to false)).expectError(403, "not_member")
    }

    @Test
    fun `reads a service account as the alpha server's env file carries it`() {
        val account = FcmSender.ServiceAccount.parse(FakePush.serviceAccountAsFetched)

        assertThat(account.projectId).isEqualTo("geunal-test")
        assertThat(account.clientEmail).isEqualTo("geunal-push@geunal-test.iam.gserviceaccount.com")
        assertThat(account.keyId).isEqualTo("abc123")
        assertThat(FcmSender.ServiceAccount.parse(FakePush.serviceAccountJson).privateKey).isEqualTo(account.privateKey)
    }
}
