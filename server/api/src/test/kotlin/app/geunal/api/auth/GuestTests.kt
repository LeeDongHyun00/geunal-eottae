package app.geunal.api.auth

import app.geunal.api.support.ApiTest
import app.geunal.api.support.Client
import app.geunal.api.support.FakeProviders
import app.geunal.api.support.Reply
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.http.HttpMethod
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.SecureRandom
import java.security.Signature
import java.util.UUID

/** Guest accounts, their return by recovery code and their link to Kakao or Apple (protocol §13). */
class GuestTests : ApiTest() {
    /** A recovery signing key and ID as the core derives them from a code; the server only ever sees the public half. */
    private class Code {
        val id: ByteArray = ByteArray(16).also(SecureRandom()::nextBytes)
        val keyPair: KeyPair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
        val publicKey: ByteArray = keyPair.public.encoded.copyOfRange(12, 44)

        fun sign(
            label: String,
            nonce: String,
        ): ByteArray =
            Signature.getInstance("Ed25519").run {
                initSign(keyPair.private)
                update((label + nonce).toByteArray(Charsets.US_ASCII))
                sign()
            }
    }

    private fun guest(
        device: Client = Client(api, "guest-${UUID.randomUUID()}"),
        platform: String = "ios",
    ): Client {
        val body = device.proof(platform = platform) + mapOf("age_confirmed_at" to "2026-10-31T00:00:00Z", "display_name" to "손님")
        val answer = api.send(HttpMethod.POST, "/auth/guest", body = body).expect(201)
        assertThat(answer.string("result")).isEqualTo("registered")
        assertThat(answer["new_account"].booleanValue()).isTrue()
        assertThat(answer["recovery"].booleanValue()).isFalse()
        device.adopt(answer)
        return device
    }

    /** Stores recovery material as `PUT /me/recovery` would; this PR's endpoints only read it. */
    private fun store(
        account: Client,
        code: Code,
    ) {
        jdbc
            .sql("INSERT INTO recovery (user_id, recovery_id, recovery_public_key, amk_wrapped) VALUES (:user, :id, :key, :amk)")
            .param("user", account.userId)
            .param("id", code.id)
            .param("key", code.publicKey)
            .param("amk", ByteArray(72))
            .update()
    }

    private fun recover(
        device: Client,
        code: Code,
        nonce: String = device.nonce(),
        recoveryId: ByteArray = code.id,
        signature: ByteArray = code.sign("geunal recover v1", nonce),
        platform: String = "android",
    ): Reply {
        val body = device.proof(nonce, platform) + mapOf("recovery_id" to recoveryId, "recovery_signature" to signature)
        return api.send(HttpMethod.POST, "/auth/recover", body = body).also { if (it.status == 201) device.adopt(it) }
    }

    @Test
    fun `a guest is always a new account with no provider`() {
        val one = guest()
        val two = guest()

        assertThat(two.userId).isNotEqualTo(one.userId)
        val me = one.get("/me").expect(200)
        assertThat(me["providers"].size()).isZero()
        assertThat(me["recovery"].booleanValue()).isFalse()
        assertThat(me.string("display_name")).isEqualTo("손님")
    }

    @Test
    fun `a guest needs the age confirmation, a display name and a valid proof`() {
        val device = Client(api, "guest-bad")
        val account = mapOf("age_confirmed_at" to "2026-10-31T00:00:00Z", "display_name" to "손님")

        api.send(HttpMethod.POST, "/auth/guest", body = device.proof() + mapOf("display_name" to "손님")).expectError(400, "invalid_request")
        api
            .send(
                HttpMethod.POST,
                "/auth/guest",
                body = device.proof() + account + ("display_name" to " "),
            ).expectError(400, "invalid_request")
        api.send(HttpMethod.POST, "/auth/guest", body = device.proof(platform = "web") + account).expectError(400, "invalid_request")
        val nonce = device.nonce()
        val forged = device.proof(nonce) + ("signature" to Client(api, "other").sign(nonce.toByteArray()))
        api.send(HttpMethod.POST, "/auth/guest", body = forged + account).expectError(400, "invalid_request")
    }

    @Test
    fun `a guest comes back on another device with the recovery code and the old device hears how`() {
        val old = guest()
        val code = Code().also { store(old, it) }
        val oldToken = old.token
        val phone = Client(api, "guest-new-phone")

        inbox(old.deviceId).use { signals ->
            val answer = recover(phone, code).expect(201)

            assertThat(answer.uuid("user_id")).isEqualTo(old.userId)
            assertThat(answer["new_account"].booleanValue()).isFalse()
            assertThat(answer["recovery"].booleanValue()).isTrue()
            assertThat(signals.next().type).isEqualTo("revoke")
        }
        val revoked = api.send(HttpMethod.GET, "/me", token = oldToken).expectError(403, "device_revoked")
        assertThat(revoked.string("replaced_via")).isEqualTo("recovery")
        assertThat(revoked.string("replaced_by")).isEqualTo("android")
        phone.get("/me").expect(200)
    }

    @Test
    fun `the same device coming back with the code keeps its device`() {
        val device = guest()
        val code = Code().also { store(device, it) }
        val before = device.deviceId

        recover(device, code).expect(201)

        assertThat(device.deviceId).isEqualTo(before)
    }

    @Test
    fun `a wrong code, an unknown account or a linked account cannot come back`() {
        val account = guest()
        val code = Code().also { store(account, it) }
        val device = Client(api, "guest-thief")

        recover(device, code, signature = Code().sign("geunal recover v1", "x")).expectError(401, "recovery_invalid")
        recover(device, Code()).expectError(401, "recovery_invalid")
        // Signed for deletion, or over a nonce other than the proof's.
        val nonce = device.nonce()
        recover(device, code, nonce = nonce, signature = code.sign("geunal delete v1", nonce)).expectError(401, "recovery_invalid")
        recover(device, code, signature = code.sign("geunal recover v1", "another-nonce")).expectError(401, "recovery_invalid")
        recover(device, code, recoveryId = ByteArray(15)).expectError(400, "invalid_request")
        account.get("/me").expect(200)

        link(account, "kakao", "kakao-${UUID.randomUUID()}").expect(200)
        recover(device, code).expectError(401, "recovery_invalid")
        account.get("/me").expect(200)
    }

    @Test
    fun `an account whose provider link was cut comes back like a guest`() {
        val sub = "cut-${UUID.randomUUID()}"
        val account = Client(api, "kakao-cut-guest")
        account.providerSignIn("kakao") { FakeProviders.kakaoToken(sub, it) }.expect(201)
        val code = Code().also { store(account, it) }
        recover(Client(api, "no"), code).expectError(401, "recovery_invalid")
        jdbc.sql("UPDATE provider_links SET unlinked_at = now() WHERE sub = :sub").param("sub", sub).update()

        val back = recover(Client(api, "kakao-cut-new-phone"), code).expect(201)

        assertThat(back.uuid("user_id")).isEqualTo(account.userId)
    }

    private fun link(
        account: Client,
        provider: String,
        sub: String,
        nonce: String = account.nonce(),
        boundTo: Client = account,
    ): Reply {
        val token =
            when (provider) {
                "kakao" -> FakeProviders.kakaoToken(sub, boundTo.binding(nonce))
                else -> FakeProviders.appleToken(sub, boundTo.binding(nonce))
            }
        return account.post("/auth/link", mapOf("provider" to provider, "id_token" to token, "nonce" to nonce))
    }

    @Test
    fun `a guest links Kakao and then signs in with it`() {
        val account = guest()
        val sub = "link-${UUID.randomUUID()}"

        val linked = link(account, "kakao", sub).expect(200)

        assertThat(linked["providers"].values().map { it.stringValue() }).containsExactly("kakao")
        assertThat(
            account
                .get("/me")
                .expect(200)["providers"]
                .values()
                .map { it.stringValue() },
        ).containsExactly("kakao")
        val again = account.providerSignIn("kakao", account = emptyMap()) { FakeProviders.kakaoToken(sub, it) }.expect(201)
        assertThat(again.uuid("user_id")).isEqualTo(account.userId)
        assertThat(again.uuid("device_id")).isEqualTo(account.deviceId)
    }

    @Test
    fun `linking refuses a provider account in use, a second link and a token for another key`() {
        val taken = "taken-${UUID.randomUUID()}"
        Client(api, "owner-of-taken").providerSignIn("kakao") { FakeProviders.kakaoToken(taken, it) }.expect(201)
        val account = guest()

        link(account, "kakao", taken).expectError(409, "account_exists")
        link(account, "kakao", "x-${UUID.randomUUID()}", boundTo = Client(api, "elsewhere")).expectError(401, "provider_token_invalid")
        link(account, "apple", "x-${UUID.randomUUID()}", nonce = "never-issued").expectError(400, "invalid_request")
        link(account, "line", "x").expectError(400, "invalid_request")

        val sub = "mine-${UUID.randomUUID()}"
        link(account, "apple", sub).expect(200)
        link(account, "apple", sub).expectError(409, "already_linked")
        link(account, "kakao", "second-${UUID.randomUUID()}").expectError(409, "already_linked")
        assertThat(
            account
                .get("/me")
                .expect(200)["providers"]
                .values()
                .map { it.stringValue() },
        ).containsExactly("apple")
    }

    @Test
    fun `linking needs the session`() {
        api
            .send(
                HttpMethod.POST,
                "/auth/link",
                body = mapOf("provider" to "kakao", "id_token" to "x", "nonce" to "y"),
            ).expectError(401, "token_expired")
    }
}
