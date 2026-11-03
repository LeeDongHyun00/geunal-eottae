package app.geunal.api.auth

import app.geunal.api.support.ApiTest
import app.geunal.api.support.Client
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.http.HttpMethod

/** Replacement tickets (protocol §13 "로그인 결과"): what they open, how they are spent, and when they end. */
class ReplaceTests : ApiTest() {
    /** An account with an active device and a second device holding a ticket for it. */
    private fun ticket(subject: String): Pair<Client, String> {
        signIn(subject)
        val next = Client(api, subject)
        return next to next.stubSignIn().expect(200).string("ticket")
    }

    @Test
    fun `only the key the sign-in proved can spend the ticket`() {
        val (next, ticket) = ticket("ticket-signature")

        next
            .replace(
                ticket,
                signature = Client(api, "thief").sign("geunal replace v1$ticket".toByteArray()),
            ).expectError(400, "invalid_request")
        next.replace(ticket, signature = next.sign(ticket.toByteArray())).expectError(400, "invalid_request")
        // A bad signature leaves the ticket for the right one.
        next.replace(ticket).expect(201)
        next.replace(ticket).expectError(401, "token_expired")
        next.get("/me").expect(200)
    }

    @Test
    fun `an expired or unknown ticket is refused`() {
        val (next, ticket) = ticket("ticket-expired")
        jdbc
            .sql("UPDATE replace_tickets SET expires_at = now() - interval '1 second' WHERE ticket_hash = :hash")
            .param("hash", AuthService.sha256(ticket))
            .update()

        next.replace(ticket).expectError(401, "token_expired")
        next.replace("A".repeat(43)).expectError(401, "token_expired")
        api.send(HttpMethod.GET, "/me/recovery", token = ticket).expectError(401, "token_expired")
    }

    @Test
    fun `a ticket reads the restore endpoints and nothing else`() {
        val (_, ticket) = ticket("ticket-bearer")

        api.send(HttpMethod.GET, "/me", token = ticket).expectError(403, "replace_required")
        api.send(HttpMethod.POST, "/me/backups", token = ticket, body = mapOf("size" to 1)).expectError(403, "replace_required")
        api.send(HttpMethod.PUT, "/me/recovery", token = ticket, body = mapOf<String, Any>()).expectError(403, "replace_required")
        api.send(HttpMethod.GET, "/me/groups", token = ticket).expectError(403, "replace_required")
        for (path in listOf("/me/recovery", "/me/backups", "/me/backups/latest")) {
            val reply = api.send(HttpMethod.GET, path, token = ticket)
            assertThat(reply.status).describedAs(path).isNotIn(401, 403)
        }
    }

    @Test
    fun `a ticket is not an access token for the gateway either`() {
        val (_, ticket) = ticket("ticket-not-jwt")

        assertThat(ticket).doesNotContain(".")
        assertThat(ticket).hasSize(43)
        val stored =
            jdbc
                .sql(
                    "SELECT count(*) FROM replace_tickets WHERE ticket_hash = :hash",
                ).param("hash", ticket.toByteArray())
                .query(Int::class.java)
                .single()
        assertThat(stored).isZero()
    }

    @Test
    fun `the replaced device needs a ticket of its own to come back`() {
        val first = signIn("ticket-back-and-forth")
        val second = replaceDevice("ticket-back-and-forth", platform = "android")

        val back = first.stubSignIn().expect(200)

        assertThat(back["active_device"].path("platform").stringValue()).isEqualTo("android")
        first.replace(back.string("ticket")).expect(201)
        second.get("/me").expectError(403, "device_revoked")
        first.get("/me").expect(200)
    }
}
