package app.geunal.api.auth

import app.geunal.api.support.ApiTest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.http.HttpMethod
import org.springframework.test.context.TestPropertySource

/**
 * `refresh-reuse-grace: 0` turns the lost-answer exception off: a spent refresh token is a replay however soon, and is
 * not answered with the token it rotated to.
 */
@TestPropertySource(properties = ["geunal.auth.refresh-reuse-grace=0s"])
class RefreshGraceOffTests : ApiTest() {
    private fun refresh(token: String) = api.send(HttpMethod.POST, "/auth/refresh", body = mapOf("refresh_token" to token))

    @Test
    fun `a token presented again right after its rotation ends the device's sessions`() {
        val client = signIn()
        val spent = client.refreshToken
        client.adopt(refresh(spent).expect(200))

        refresh(spent).expectError(401, "token_expired")

        refresh(client.refreshToken).expectError(401, "token_expired")
    }

    @Test
    fun `of two refreshes racing with one token only one gets tokens, and they do not last`() {
        // The one that waits for the device row began before the other spent the token; it must not pass for that.
        repeat(16) {
            val shared = signIn().refreshToken

            val replies = listOf(shared, shared).parallelStream().map { refresh(it) }.toList()

            assertThat(replies.map { it.status }).containsExactlyInAnyOrder(200, 401)
            refresh(replies.single { it.status == 200 }.string("refresh_token")).expectError(401, "token_expired")
        }
    }
}
