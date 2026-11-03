package app.geunal.api.auth

import app.geunal.api.support.Api
import app.geunal.api.support.Client
import app.geunal.api.support.FakeProviders
import app.geunal.api.support.TestStack
import app.geunal.api.support.register
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.http.HttpMethod
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import tools.jackson.databind.json.JsonMapper
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.util.concurrent.atomic.AtomicInteger

/** Sign-ins and nonces per client address an hour (api-server.md §9), here 5 so a test can reach the limit. */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = ["geunal.auth.stub=true", "geunal.auth.sign-ins-per-hour=5"],
)
class SignInLimitTests {
    companion object {
        private val addresses = AtomicInteger()

        @JvmStatic
        @DynamicPropertySource
        fun stack(registry: DynamicPropertyRegistry) {
            TestStack.register(registry)
            FakeProviders.register(registry)
        }
    }

    @Autowired
    lateinit var mapper: JsonMapper

    @Value("\${local.server.port}")
    var port: Int = 0

    /** An address of this test alone, so the count in the shared Redis starts at zero. */
    private val from = mapOf("X-Forwarded-For" to "203.0.113.${addresses.incrementAndGet()}")

    @Test
    fun `counts nonces and sign-ins together, per address`() {
        val api = Api(port, mapper)
        val device = Client(api, "limited")
        val nonce = { api.send(HttpMethod.GET, "/auth/nonce", headers = from) }

        repeat(2) { nonce().expect(200) }
        // The same endpoint under a percent-encoded spelling is the same endpoint, and counts.
        val encoded =
            HttpRequest
                .newBuilder(URI("http://localhost:$port/auth/%6eonce"))
                .header("X-Forwarded-For", from.values.single())
                .build()
        assertThat(HttpClient.newHttpClient().send(encoded, HttpResponse.BodyHandlers.discarding()).statusCode()).isEqualTo(200)
        val first = nonce().expect(200).string("nonce")
        api.send(HttpMethod.POST, "/auth/stub", body = device.loginRequest(first), headers = from).expect(201)

        val refused = nonce().expectError(429, "rate_limited")
        assertThat(refused.headers.getFirst("Retry-After")!!.toLong()).isBetween(1, 3600)
        api.send(HttpMethod.POST, "/auth/guest", body = emptyMap<String, Any>(), headers = from).expectError(429, "rate_limited")
        // Another address is counted on its own; the App Link page and refreshing are not sign-ins.
        api.send(HttpMethod.GET, "/auth/nonce", headers = mapOf("X-Forwarded-For" to "203.0.113.200")).expect(200)
        val page =
            HttpRequest
                .newBuilder(URI("http://localhost:$port/auth/apple"))
                .header("X-Forwarded-For", from.values.single())
                .build()
        assertThat(HttpClient.newHttpClient().send(page, HttpResponse.BodyHandlers.discarding()).statusCode()).isEqualTo(200)
        api.send(HttpMethod.POST, "/auth/refresh", body = mapOf("refresh_token" to "x"), headers = from).expectError(401, "token_expired")
    }
}
