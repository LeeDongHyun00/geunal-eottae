package app.geunal.api.auth

import app.geunal.api.support.ApiTest
import app.geunal.api.support.Client
import app.geunal.api.support.FakeProviders
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.http.HttpMethod
import java.time.Duration
import java.util.UUID

/** Kakao and Apple sign-in against [FakeProviders]' JWKS (protocol §13 "로그인 방법", "로그인 결과"). */
class ProviderSignInTests : ApiTest() {
    private fun kakaoSub() = (100_000_000L + (Math.random() * 1e9).toLong()).toString()

    private fun appleSub() = "001234.${UUID.randomUUID().toString().replace("-", "")}.0123"

    @Test
    fun `a first Kakao sign-in creates the account and registers the device`() {
        val sub = kakaoSub()
        val device = Client(api, "kakao-$sub")

        val answer = device.providerSignIn("kakao") { FakeProviders.kakaoToken(sub, it) }.expect(201)

        assertThat(answer.string("result")).isEqualTo("registered")
        assertThat(answer["new_account"].booleanValue()).isTrue()
        assertThat(answer["recovery"].booleanValue()).isFalse()
        assertThat(answer.long("expires_in")).isEqualTo(900)
        val me = device.get("/me").expect(200)
        assertThat(me["providers"].values().map { it.stringValue() }).containsExactly("kakao")
        assertThat(me["recovery"].booleanValue()).isFalse()
        assertThat(me.string("display_name")).isEqualTo("kakao-$sub".take(40))
    }

    @Test
    fun `the same Kakao account on the same device comes back to its account and device`() {
        val sub = kakaoSub()
        val device = Client(api, "kakao-again")
        device.providerSignIn("kakao") { FakeProviders.kakaoToken(sub, it) }.expect(201)
        val first = device.deviceId

        val again = device.providerSignIn("kakao", account = emptyMap()) { FakeProviders.kakaoToken(sub, it) }.expect(201)

        assertThat(again["new_account"].booleanValue()).isFalse()
        assertThat(again.uuid("device_id")).isEqualTo(first)
    }

    @Test
    fun `the same Kakao account on another device needs a replacement`() {
        val sub = kakaoSub()
        val phone = Client(api, "kakao-phone")
        phone.providerSignIn("kakao", platform = "android") { FakeProviders.kakaoToken(sub, it) }.expect(201)
        val tablet = Client(api, "kakao-tablet")

        val answer = tablet.providerSignIn("kakao", account = emptyMap()) { FakeProviders.kakaoToken(sub, it) }.expect(200)

        assertThat(answer.string("result")).isEqualTo("replace_required")
        assertThat(answer.uuid("user_id")).isEqualTo(phone.userId)
        assertThat(answer["active_device"].path("platform").stringValue()).isEqualTo("android")
        assertThat(answer.string("ticket")).hasSize(43)
        phone.get("/me").expect(200)

        tablet.replace(answer.string("ticket")).expect(201)
        assertThat(tablet.userId).isEqualTo(phone.userId)
        phone.get("/me").expectError(403, "device_revoked")
    }

    @Test
    fun `a new account needs the age confirmation and a display name`() {
        val sub = kakaoSub()
        val device = Client(api, "kakao-new")

        device
            .providerSignIn("kakao", account = mapOf("display_name" to "새 사람")) { FakeProviders.kakaoToken(sub, it) }
            .expectError(400, "invalid_request")
        device
            .providerSignIn("kakao", account = mapOf("age_confirmed_at" to "2026-10-31T00:00:00Z")) { FakeProviders.kakaoToken(sub, it) }
            .expectError(400, "invalid_request")
        device
            .providerSignIn("kakao", account = mapOf("age_confirmed_at" to "어제", "display_name" to "새 사람")) {
                FakeProviders.kakaoToken(sub, it)
            }.expectError(400, "invalid_request")
        val tooLong = mapOf("age_confirmed_at" to "2026-10-31T00:00:00Z", "display_name" to "가".repeat(41))
        device.providerSignIn("kakao", account = tooLong) { FakeProviders.kakaoToken(sub, it) }.expectError(400, "invalid_request")
    }

    @Test
    fun `rejects a Kakao token that is not ours, expired, forged or bound to another nonce or key`() {
        val device = Client(api, "kakao-bad-tokens")
        val other = Client(api, "kakao-other-key")
        val sub = kakaoSub()
        val cases =
            mapOf<String, (nonce: String) -> String>(
                "another app" to { FakeProviders.kakaoToken(sub, device.binding(it), audience = "someone-elses-app-key") },
                "another issuer" to { FakeProviders.kakaoToken(sub, device.binding(it), issuer = "https://kauth.example") },
                "expired" to { FakeProviders.kakaoToken(sub, device.binding(it), expiresIn = Duration.ofMinutes(-5)) },
                "unpublished key" to { FakeProviders.kakaoToken(sub, device.binding(it), key = FakeProviders.strangerKey) },
                "no nonce" to { FakeProviders.kakaoToken(sub, null) },
                "the raw nonce" to { FakeProviders.kakaoToken(sub, it) },
                "another nonce" to { FakeProviders.kakaoToken(sub, device.binding("some-other-nonce")) },
                "another device key" to { FakeProviders.kakaoToken(sub, other.binding(it)) },
                "an Apple token" to { FakeProviders.appleToken(sub, device.binding(it)) },
                "not a JWT" to { "not.a.jwt" },
            )
        for ((name, token) in cases) {
            val nonce = device.nonce()
            val body = device.proof(nonce) + device.newAccount() + ("id_token" to token(nonce))
            val reply = api.send(HttpMethod.POST, "/auth/kakao", body = body)
            assertThat(reply.status).describedAs(name).isEqualTo(401)
            assertThat(reply.string("error")).describedAs(name).isEqualTo("provider_token_invalid")
        }
    }

    @Test
    fun `a token bound to one device does not sign in another`() {
        val sub = kakaoSub()
        val owner = Client(api, "kakao-owner")
        val thief = Client(api, "kakao-thief")
        val nonce = owner.nonce()
        val token = FakeProviders.kakaoToken(sub, owner.binding(nonce))

        val body = thief.proof(nonce) + thief.newAccount() + ("id_token" to token)
        api.send(HttpMethod.POST, "/auth/kakao", body = body).expectError(401, "provider_token_invalid")
    }

    @Test
    fun `the device proof is checked before the provider token`() {
        val device = Client(api, "kakao-proof")
        val nonce = device.nonce()
        val forged = device.proof(nonce) + ("signature" to Client(api, "other").sign(nonce.toByteArray()))
        val body = forged + device.newAccount() + ("id_token" to FakeProviders.kakaoToken(kakaoSub(), device.binding(nonce)))

        api.send(HttpMethod.POST, "/auth/kakao", body = body).expectError(400, "invalid_request")
        // The nonce is spent either way.
        val retry = device.proof(nonce) + device.newAccount() + ("id_token" to FakeProviders.kakaoToken(kakaoSub(), device.binding(nonce)))
        api.send(HttpMethod.POST, "/auth/kakao", body = retry).expectError(400, "invalid_request")
    }

    @Test
    fun `Apple signs in with the bundle ID on iOS and the services ID on Android`() {
        val sub = appleSub()
        val iphone = Client(api, "apple-iphone")
        iphone.providerSignIn("apple") { FakeProviders.appleToken(sub, it, audience = FakeProviders.APPLE_BUNDLE_ID) }.expect(201)
        assertThat(
            iphone
                .get("/me")
                .expect(200)["providers"]
                .values()
                .map { it.stringValue() },
        ).containsExactly("apple")

        val android = Client(api, "apple-android")
        val answer =
            android
                .providerSignIn("apple", account = emptyMap(), platform = "android") {
                    FakeProviders.appleToken(sub, it, audience = FakeProviders.APPLE_SERVICE_ID)
                }.expect(200)
        assertThat(answer.uuid("user_id")).isEqualTo(iphone.userId)

        Client(api, "apple-elsewhere")
            .providerSignIn("apple") { FakeProviders.appleToken(appleSub(), it, audience = "com.example.other") }
            .expectError(401, "provider_token_invalid")
        Client(api, "apple-as-kakao")
            .providerSignIn("apple") { FakeProviders.kakaoToken(appleSub(), it) }
            .expectError(401, "provider_token_invalid")
    }

    @Test
    fun `a Kakao and an Apple account with the same subject are different accounts`() {
        val sub = "shared-${UUID.randomUUID()}"
        val kakao = Client(api, "same-sub-kakao").also { c -> c.providerSignIn("kakao") { FakeProviders.kakaoToken(sub, it) }.expect(201) }
        val apple = Client(api, "same-sub-apple").also { c -> c.providerSignIn("apple") { FakeProviders.appleToken(sub, it) }.expect(201) }

        assertThat(apple.userId).isNotEqualTo(kakao.userId)
    }

    @Test
    fun `a cut provider link still finds its account, and signing in clears the cut`() {
        val sub = kakaoSub()
        val device = Client(api, "kakao-cut")
        device.providerSignIn("kakao") { FakeProviders.kakaoToken(sub, it) }.expect(201)
        jdbc.sql("UPDATE provider_links SET unlinked_at = now() WHERE provider = 'kakao' AND sub = :sub").param("sub", sub).update()
        assertThat(device.get("/me").expect(200)["providers"].size()).isZero()

        val again = device.providerSignIn("kakao", account = emptyMap()) { FakeProviders.kakaoToken(sub, it) }.expect(201)

        assertThat(again.uuid("user_id")).isEqualTo(device.userId)
        assertThat(again["new_account"].booleanValue()).isFalse()
        assertThat(
            device
                .get("/me")
                .expect(200)["providers"]
                .values()
                .map { it.stringValue() },
        ).containsExactly("kakao")
    }

    @Test
    fun `two first sign-ins of one provider account make one account`() {
        val sub = kakaoSub()
        val devices = List(6) { Client(api, "kakao-race-$it") }
        val bodies =
            devices.map { d ->
                val nonce = d.nonce()
                d.proof(nonce) + d.newAccount() + ("id_token" to FakeProviders.kakaoToken(sub, d.binding(nonce)))
            }

        val replies = bodies.parallelStream().map { api.send(HttpMethod.POST, "/auth/kakao", body = it) }.toList()

        assertThat(replies.map { it.status }.sorted()).containsExactly(200, 200, 200, 200, 200, 201)
        assertThat(replies.map { it.uuid("user_id") }.toSet()).hasSize(1)
    }
}
