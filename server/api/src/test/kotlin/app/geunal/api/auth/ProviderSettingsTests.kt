package app.geunal.api.auth

import app.geunal.api.support.Api
import app.geunal.api.support.Client
import app.geunal.api.support.FakeProviders
import app.geunal.api.support.TestStack
import app.geunal.api.support.register
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.context.properties.bind.Binder
import org.springframework.boot.context.properties.source.ConfigurationPropertySource
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.core.env.StandardEnvironment
import org.springframework.core.env.SystemEnvironmentPropertySource
import org.springframework.http.HttpMethod
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import tools.jackson.databind.json.JsonMapper

/** The environment variables the server reads for its providers (the names the PR hands to infra). */
class ProviderEnvironmentTests {
    private fun bind(environment: Map<String, String>): Pair<AuthProperties, AppLinksProperties> {
        val source = SystemEnvironmentPropertySource(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME, environment)
        val binder = Binder(ConfigurationPropertySource.from(source)!!)
        val auth = binder.bindOrCreate("geunal.auth", AuthProperties::class.java)
        val appLinks = binder.bindOrCreate("geunal.app-links", AppLinksProperties::class.java)
        return auth to appLinks
    }

    @Test
    fun `reads every provider setting from its documented environment variable`() {
        val (auth, appLinks) =
            bind(
                mapOf(
                    "GEUNAL_AUTH_KAKAO_APP_KEY" to "app-key",
                    "GEUNAL_AUTH_KAKAO_ADMIN_KEY" to "admin-key",
                    "GEUNAL_AUTH_APPLE_TEAM_ID" to "TEAM123456",
                    "GEUNAL_AUTH_APPLE_KEY_ID" to "KEY1234567",
                    "GEUNAL_AUTH_APPLE_PRIVATE_KEY" to "pem",
                    "GEUNAL_AUTH_APPLE_REDIRECT_URI" to "https://api.example/auth/apple/callback",
                    "GEUNAL_AUTH_APPLE_APP_LINK" to "https://example/auth/apple",
                    "GEUNAL_AUTH_TICKET_TTL" to "20m",
                    "GEUNAL_APP_LINKS_ANDROID_PACKAGE" to "app.geunal.debug",
                    "GEUNAL_APP_LINKS_ANDROID_FINGERPRINTS" to "AA:BB,CC:DD",
                ),
            )

        assertThat(auth.kakao.appKey).isEqualTo("app-key")
        assertThat(auth.kakao.adminKey).isEqualTo("admin-key")
        assertThat(auth.kakao.enabled).isTrue()
        assertThat(auth.apple.teamId).isEqualTo("TEAM123456")
        assertThat(auth.apple.keyId).isEqualTo("KEY1234567")
        assertThat(auth.apple.privateKey).isEqualTo("pem")
        assertThat(auth.apple.redirectUri).isEqualTo("https://api.example/auth/apple/callback")
        assertThat(auth.apple.appLink).isEqualTo("https://example/auth/apple")
        assertThat(auth.apple.enabled).isTrue()
        assertThat(auth.ticketTtl.toMinutes()).isEqualTo(20)
        assertThat(appLinks.androidPackage).isEqualTo("app.geunal.debug")
        assertThat(appLinks.androidFingerprints).containsExactly("AA:BB", "CC:DD")
    }

    @Test
    fun `a provider without all of its settings stays off`() {
        assertThat(bind(mapOf("GEUNAL_AUTH_KAKAO_APP_KEY" to "app-key")).first.kakao.enabled).isFalse()
        val apple = mapOf("GEUNAL_AUTH_APPLE_TEAM_ID" to "T", "GEUNAL_AUTH_APPLE_KEY_ID" to "K", "GEUNAL_AUTH_APPLE_PRIVATE_KEY" to "P")
        assertThat(bind(apple).first.apple.enabled).isFalse()
        assertThat(bind(emptyMap()).first.apple.bundleId).isEqualTo("app.geunal.ios")
        assertThat(bind(emptyMap()).first.apple.serviceId).isEqualTo("app.geunal.signin")
    }

    @Test
    fun `reads the Sign in with Apple key as the p8 file gives it or with escaped line breaks`() {
        val pem = FakeProviders.applePrivateKeyPem

        assertThat(ProviderTokens.applePrivateKey(pem).algorithm).isEqualTo("EC")
        assertThat(ProviderTokens.applePrivateKey(pem.replace("\n", "\\n")).algorithm).isEqualTo("EC")
        assertThatThrownBy { ProviderTokens.applePrivateKey("-----BEGIN PRIVATE KEY-----\nnot a key\n-----END PRIVATE KEY-----") }
            .isInstanceOf(IllegalStateException::class.java)
    }
}

/** A server with no provider settings: Kakao and Apple are off and say so, while guests still sign in. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ProvidersOffTests {
    companion object {
        @JvmStatic
        @DynamicPropertySource
        fun stack(registry: DynamicPropertyRegistry) = TestStack.register(registry)
    }

    @Autowired
    lateinit var mapper: JsonMapper

    @Value("\${local.server.port}")
    var port: Int = 0

    @Test
    fun `answers 404 for Kakao, Apple and the stub, and still takes guests`() {
        val api = Api(port, mapper)
        val device = Client(api, "providers-off")
        val account = mapOf("age_confirmed_at" to "2026-10-31T00:00:00Z", "display_name" to "손님")

        for (provider in listOf("kakao", "apple")) {
            val nonce = device.nonce()
            val body = device.proof(nonce) + account + ("id_token" to FakeProviders.kakaoToken("1", device.binding(nonce)))
            api.send(HttpMethod.POST, "/auth/$provider", body = body).expectError(404, "not_found")
        }
        api.send(HttpMethod.POST, "/auth/stub", body = device.loginRequest(device.nonce())).expectError(404, "not_found")
        val guest = api.send(HttpMethod.POST, "/auth/guest", body = device.proof() + account).expect(201)
        device.adopt(guest)
        val link = mapOf("provider" to "kakao", "id_token" to "x", "nonce" to device.nonce())
        device.post("/auth/link", link).expectError(404, "not_found")
        val callback = api.send(HttpMethod.POST, "/auth/apple/callback")
        assertThat(callback.status).isIn(404, 415)
    }
}
