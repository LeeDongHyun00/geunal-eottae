package app.geunal.api.auth

import app.geunal.api.support.ApiTest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.http.HttpMethod
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse

/** The App Links file and the way Android's Sign in with Apple comes back through this server (protocol §13). */
class AppLinksTests : ApiTest() {
    private val http: HttpClient = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build()

    private fun post(form: Map<String, String>): HttpResponse<String> {
        val body = form.entries.joinToString("&") { (k, v) -> "$k=${URLEncoder.encode(v, Charsets.UTF_8)}" }
        val request =
            HttpRequest
                .newBuilder(URI("http://localhost:$port/auth/apple/callback"))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build()
        return http.send(request, HttpResponse.BodyHandlers.ofString())
    }

    /** The fragment of a redirect, decoded the way the Android app reads it (`AppleCallback.parse`). */
    private fun fragment(location: String): Map<String, String> =
        location
            .substringAfter('#', "")
            .split('&')
            .filter { it.contains('=') }
            .associate { it.substringBefore('=') to URLDecoder.decode(it.substringAfter('='), Charsets.UTF_8) }

    @Test
    fun `serves the Android app's package and signing certificate without a session`() {
        val statements = api.send(HttpMethod.GET, "/.well-known/assetlinks.json").expect(200).json!!
        val statement = statements[0]

        assertThat(statements.size()).isEqualTo(1)
        assertThat(
            statement.path("relation").values().map { it.stringValue() },
        ).containsExactly("delegate_permission/common.handle_all_urls")
        assertThat(statement.path("target").path("namespace").stringValue()).isEqualTo("android_app")
        assertThat(statement.path("target").path("package_name").stringValue()).isEqualTo("app.geunal")
        assertThat(
            statement
                .path("target")
                .path("sha256_cert_fingerprints")
                .values()
                .map { it.stringValue() },
        ).containsExactly("DE:B8:C0:15:7E:58:20:68:36:81:A1:8D:74:0B:2D:17:6B:FC:AA:67:48:68:9E:5B:25:4B:2A:53:9C:52:1E:B8")
    }

    @Test
    fun `hands Apple's form post to the App Link in the fragment`() {
        val user = """{"name":{"firstName":"길동","lastName":"홍"}}"""
        val response = post(mapOf("code" to "c0de.with/odd+chars", "id_token" to "a.b.c", "state" to "st&te=1", "user" to user))

        assertThat(response.statusCode()).isEqualTo(303)
        val location = response.headers().firstValue("Location").orElseThrow()
        assertThat(location).startsWith("/auth/apple#")
        assertThat(location.substringAfter('#')).doesNotContain(" ", "{", "\"")
        assertThat(fragment(location))
            .containsEntry("code", "c0de.with/odd+chars")
            .containsEntry("id_token", "a.b.c")
            .containsEntry("state", "st&te=1")
            .containsEntry("user", user)
        assertThat(response.headers().firstValue("Cache-Control").orElse("")).contains("no-store")
    }

    @Test
    fun `passes Apple's error on and leaves out oversized or unknown fields`() {
        val response = post(mapOf("error" to "user_cancelled_authorize", "state" to "s", "id_token" to "x".repeat(5000), "extra" to "1"))

        assertThat(response.statusCode()).isEqualTo(303)
        assertThat(fragment(response.headers().firstValue("Location").orElseThrow()))
            .containsOnlyKeys("error", "state")
            .containsEntry("error", "user_cancelled_authorize")
    }

    @Test
    fun `the App Link address is a static page that runs no script`() {
        val request = HttpRequest.newBuilder(URI("http://localhost:$port/auth/apple")).GET().build()
        val response = http.send(request, HttpResponse.BodyHandlers.ofString())

        assertThat(response.statusCode()).isEqualTo(200)
        assertThat(response.headers().firstValue("Content-Type").orElse("")).startsWith("text/html")
        assertThat(response.headers().firstValue("Content-Security-Policy").orElse("")).contains("default-src 'none'")
        assertThat(response.body()).doesNotContain("<script", "location", "hash")
    }
}
