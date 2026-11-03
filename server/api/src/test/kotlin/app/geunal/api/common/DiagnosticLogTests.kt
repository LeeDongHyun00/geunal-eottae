package app.geunal.api.common

import app.geunal.api.support.ApiTest
import app.geunal.api.support.Client
import app.geunal.api.support.FakeProviders
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.boot.test.system.CapturedOutput
import org.springframework.boot.test.system.OutputCaptureExtension
import org.springframework.http.HttpMethod
import java.util.UUID

/** What the logs say when something goes wrong in the field, and that they say nothing more (api-server.md §10). */
@ExtendWith(OutputCaptureExtension::class)
class DiagnosticLogTests : ApiTest() {
    @Test
    fun `a refused Apple deletion names the failed check and Apple's error code, and no value`(output: CapturedOutput) {
        val sub = "diag-apple-${UUID.randomUUID()}"
        val account = Client(api, sub)
        account.providerSignIn("apple") { FakeProviders.appleToken(sub, it) }.expect(201)
        val nonce = account.nonce()
        val code = "made-up-${UUID.randomUUID()}"
        val body =
            mapOf(
                "nonce" to nonce,
                "device_signature" to account.sign("geunal delete v1$nonce".toByteArray()),
                "apple_authorization_code" to code,
            )

        api.send(HttpMethod.DELETE, "/me", account.token, body).expectError(403, "reauth_required")

        assertThat(output.out).contains("Apple refused a code exchange for ios: 400 invalid_grant")
        assertThat(output.out).contains("deletion refused: the apple proof did not verify")
        assertThat(output.out)
            .doesNotContain(code)
            .doesNotContain(nonce)
            .doesNotContain(sub)
            .doesNotContain(account.token)
    }

    @Test
    fun `a code from another Apple account is told apart from a refused exchange`(output: CapturedOutput) {
        val sub = "diag-apple-${UUID.randomUUID()}"
        val other = "diag-other-${UUID.randomUUID()}"
        val account = Client(api, sub)
        account.providerSignIn("apple") { FakeProviders.appleToken(sub, it) }.expect(201)
        val nonce = account.nonce()
        val body =
            mapOf(
                "nonce" to nonce,
                "device_signature" to account.sign("geunal delete v1$nonce".toByteArray()),
                "apple_authorization_code" to FakeProviders.appleCode(other),
            )

        api.send(HttpMethod.DELETE, "/me", account.token, body).expectError(403, "reauth_required")

        assertThat(output.out).contains("Apple's code names another Apple account than the one linked")
        assertThat(output.out).doesNotContain(sub).doesNotContain(other)
    }

    @Test
    fun `each request is one line with its route pattern, status and time`(output: CapturedOutput) {
        val account = signIn()

        account.get("/users/${account.userId}/devices").expect(200)

        assertThat(output.out).containsPattern("GET /users/\\{id}/devices 200 \\d+ms")
        assertThat(output.out).doesNotContain(account.userId.toString())
    }
}
