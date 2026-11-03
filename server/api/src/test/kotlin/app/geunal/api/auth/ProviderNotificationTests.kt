package app.geunal.api.auth

import app.geunal.api.support.ApiTest
import app.geunal.api.support.Client
import app.geunal.api.support.FakeProviders
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.http.HttpMethod
import java.time.Duration
import java.time.Instant
import java.util.UUID

/** Kakao's unlink webhook and Apple's server-to-server notifications (protocol §13 "제공자 쪽에서 끊길 때"). */
class ProviderNotificationTests : ApiTest() {
    private val admin = mapOf("Authorization" to "KakaoAK ${FakeProviders.KAKAO_ADMIN_KEY}")

    private fun kakaoAccount(): Pair<Client, String> {
        val sub = (5_000_000_000L + (Math.random() * 1e9).toLong()).toString()
        val account = Client(api, "unlink-kakao")
        account.providerSignIn("kakao") { FakeProviders.kakaoToken(sub, it) }.expect(201)
        return account to sub
    }

    private fun appleAccount(): Pair<Client, String> {
        val sub = "unlink-apple-${UUID.randomUUID()}"
        val account = Client(api, sub)
        account.providerSignIn("apple") { FakeProviders.appleToken(sub, it) }.expect(201)
        return account to sub
    }

    private fun providers(account: Client): List<String> =
        account
            .get("/me")
            .expect(200)["providers"]
            .values()
            .map { it.stringValue() }

    private fun notify(payload: String) = api.send(HttpMethod.POST, "/auth/apple/notifications", body = mapOf("payload" to payload))

    @Test
    fun `ignores a provider unlink notification it cannot verify`() {
        val (kakao, kakaoSub) = kakaoAccount()
        for (authorization in listOf(
            null,
            "KakaoAK wrong",
            "Bearer ${FakeProviders.KAKAO_ADMIN_KEY}",
            "KakaoAK ${FakeProviders.KAKAO_APP_KEY}",
        )) {
            val headers = authorization?.let { mapOf("Authorization" to it) } ?: emptyMap()
            api
                .send(HttpMethod.GET, "/auth/kakao/unlinked?user_id=$kakaoSub&referrer_type=UNLINK_FROM_APPS", headers = headers)
                .expectError(401, "provider_token_invalid")
        }
        assertThat(providers(kakao)).containsExactly("kakao")

        val (apple, appleSub) = appleAccount()
        val stranger = RSAKeyGenerator(2048).keyID(FakeProviders.appleKey.keyID).generate()
        listOf(
            FakeProviders.appleNotification("consent-revoked", appleSub, key = stranger),
            FakeProviders.appleNotification("consent-revoked", appleSub, audience = "app.someone.else"),
            FakeProviders.appleNotification("consent-revoked", appleSub, issuer = "https://appleid.example.com"),
            FakeProviders.appleNotification("consent-revoked", appleSub, issuedAt = Instant.now().minus(Duration.ofDays(2))),
            "not-a-jwt",
        ).forEach { notify(it).expectError(401, "provider_token_invalid") }
        assertThat(providers(apple)).containsExactly("apple")
    }

    @Test
    fun `a provider sign-in after an unlink returns to the same account`() {
        val (kakao, kakaoSub) = kakaoAccount()
        api
            .send(
                HttpMethod.POST,
                "/auth/kakao/unlinked?app_id=1&user_id=$kakaoSub&referrer_type=UNLINK_FROM_APPS",
                headers = admin,
            ).expect(200)
        // Kakao may send it again; an unknown member number is fine too.
        api
            .send(
                HttpMethod.GET,
                "/auth/kakao/unlinked?app_id=1&user_id=$kakaoSub&referrer_type=UNLINK_FROM_APPS",
                headers = admin,
            ).expect(200)
        api.send(HttpMethod.GET, "/auth/kakao/unlinked?app_id=1&user_id=1&referrer_type=ACCOUNT_DELETE", headers = admin).expect(200)
        assertThat(providers(kakao)).isEmpty()

        val back = kakao.providerSignIn("kakao") { FakeProviders.kakaoToken(kakaoSub, it) }.expect(201)
        assertThat(back.uuid("user_id")).isEqualTo(kakao.userId)
        assertThat(back["new_account"].booleanValue()).isFalse()
        assertThat(providers(kakao)).containsExactly("kakao")

        val (apple, appleSub) = appleAccount()
        val revoked = FakeProviders.appleNotification("consent-revoked", appleSub)
        notify(FakeProviders.appleNotification("email-disabled", appleSub)).expect(200)
        assertThat(providers(apple)).containsExactly("apple")
        notify(revoked).expect(200)
        assertThat(providers(apple)).isEmpty()

        apple.providerSignIn("apple") { FakeProviders.appleToken(appleSub, it) }.expect(201).also {
            assertThat(it.uuid("user_id")).isEqualTo(apple.userId)
        }
        // The same notification again counts for nothing: a captured one cannot cut the restored link.
        notify(revoked).expect(200)
        assertThat(providers(apple)).containsExactly("apple")
    }
}
