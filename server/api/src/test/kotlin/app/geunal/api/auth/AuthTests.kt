package app.geunal.api.auth

import app.geunal.api.ops.Cleanup
import app.geunal.api.support.ApiTest
import app.geunal.api.support.Client
import com.nimbusds.jose.crypto.ECDSAVerifier
import com.nimbusds.jose.jwk.JWKSet
import com.nimbusds.jwt.SignedJWT
import io.micrometer.core.instrument.MeterRegistry
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.HttpMethod
import java.time.Duration
import java.util.UUID

class AuthTests : ApiTest() {
    @Autowired
    lateinit var tokens: AccessTokens

    @Autowired
    lateinit var cleanup: Cleanup

    @Autowired
    lateinit var metrics: MeterRegistry

    @Test
    fun `signs in with a nonce proof and reaches me`() {
        val client = signIn(platform = "android")

        val me = client.get("/me").expect(200)
        assertThat(me.uuid("user_id")).isEqualTo(client.userId)
        assertThat(me["device"].path("id").stringValue()).isEqualTo(client.deviceId.toString())
        assertThat(me["device"].path("platform").stringValue()).isEqualTo("android")
        assertThat(me.int("key_packages")).isZero()
    }

    @Test
    fun `signing in again with the same subject and key keeps the account and the device`() {
        val first = signIn("same-person")
        val again = first.stubSignIn().expect(201)

        assertThat(again.uuid("user_id")).isEqualTo(first.userId)
        assertThat(again.uuid("device_id")).isEqualTo(first.deviceId)
        assertThat(again.string("result")).isEqualTo("registered")
        assertThat(again["new_account"].booleanValue()).isFalse()
        first.get("/me").expect(200)
    }

    @Test
    fun `another device of the same subject gets a ticket and registers nothing yet`() {
        val first = signIn("same-person-two-keys", platform = "android")
        val second = Client(api, "same-person-two-keys")

        val answer = second.stubSignIn().expect(200)

        assertThat(answer.string("result")).isEqualTo("replace_required")
        assertThat(answer.uuid("user_id")).isEqualTo(first.userId)
        assertThat(answer.long("expires_in")).isEqualTo(1800)
        assertThat(answer["active_device"].path("platform").stringValue()).isEqualTo("android")
        assertThat(answer["recovery"].booleanValue()).isFalse()
        assertThat(answer.json!!.has("access_token")).isFalse()
        first.get("/me").expect(200)
    }

    @Test
    fun `rejects a signature that does not verify`() {
        val client = Client(api, "forger")
        val nonce = api.send(HttpMethod.GET, "/auth/nonce").expect(200).string("nonce")
        val wrong = Client(api, "other").sign(nonce.toByteArray())

        api.send(HttpMethod.POST, "/auth/stub", body = client.loginRequest(nonce, signature = wrong)).expectError(400, "invalid_request")
    }

    @Test
    fun `rejects an unknown or reused nonce`() {
        val client = Client(api, "replayer")
        val nonce = api.send(HttpMethod.GET, "/auth/nonce").expect(200).string("nonce")

        api.send(HttpMethod.POST, "/auth/stub", body = client.loginRequest(nonce)).expect(201)
        api.send(HttpMethod.POST, "/auth/stub", body = client.loginRequest(nonce)).expectError(400, "invalid_request")
        api.send(HttpMethod.POST, "/auth/stub", body = client.loginRequest("made-up")).expectError(400, "invalid_request")
    }

    @Test
    fun `rejects a malformed sign-in body`() {
        api.send(HttpMethod.POST, "/auth/stub", body = mapOf("subject" to "x")).expectError(400, "invalid_request")
        val nonce = api.send(HttpMethod.GET, "/auth/nonce").expect(200).string("nonce")
        val badPlatform = Client(api, "web").loginRequest(nonce, platform = "web")
        api.send(HttpMethod.POST, "/auth/stub", body = badPlatform).expectError(400, "invalid_request")
    }

    @Test
    fun `publishes the JWKS that verifies the access token`() {
        val client = signIn()
        val jwks =
            JWKSet.parse(
                api
                    .send(HttpMethod.GET, "/.well-known/jwks.json")
                    .expect(200)
                    .json
                    .toString(),
            )
        val jwt = SignedJWT.parse(client.token)

        val key = jwks.getKeyByKeyId(jwt.header.keyID)
        assertThat(key).isNotNull
        assertThat(jwt.verify(ECDSAVerifier(key!!.toECKey()))).isTrue()
        assertThat(jwt.header.algorithm.name).isEqualTo("ES256")
        assertThat(jwt.jwtClaimsSet.subject).isEqualTo(client.userId.toString())
        assertThat(jwt.jwtClaimsSet.getStringClaim("did")).isEqualTo(client.deviceId.toString())
        val lifetime = jwt.jwtClaimsSet.expirationTime.time - jwt.jwtClaimsSet.issueTime.time
        assertThat(lifetime).isEqualTo(Duration.ofMinutes(15).toMillis())
    }

    @Test
    fun `rejects expired and foreign tokens with token_expired`() {
        val client = signIn()
        val expired = tokens.issue(Caller(client.userId, client.deviceId), Duration.ofSeconds(-1))

        api.send(HttpMethod.GET, "/me", token = expired).expectError(401, "token_expired")
        api.send(HttpMethod.GET, "/me", token = "not.a.jwt").expectError(401, "token_expired")
        api.send(HttpMethod.GET, "/me", token = client.token.dropLast(3) + "AAA").expectError(401, "token_expired")
    }

    @Test
    fun `rotates the refresh token`() {
        val client = signIn()
        val oldRefresh = client.refreshToken

        val refreshed = api.send(HttpMethod.POST, "/auth/refresh", body = mapOf("refresh_token" to oldRefresh)).expect(200)
        client.adopt(refreshed)
        assertThat(client.refreshToken).isNotEqualTo(oldRefresh)
        client.get("/me").expect(200)
    }

    @Test
    fun `a refresh token presented twice ends every session of its device`() {
        val client = signIn()
        val stolen = client.refreshToken
        client.adopt(api.send(HttpMethod.POST, "/auth/refresh", body = mapOf("refresh_token" to stolen)).expect(200))
        val other = client.stubSignIn().expect(201).string("refresh_token")
        pastTheGrace(stolen)

        api.send(HttpMethod.POST, "/auth/refresh", body = mapOf("refresh_token" to stolen)).expectError(401, "token_expired")

        api.send(HttpMethod.POST, "/auth/refresh", body = mapOf("refresh_token" to client.refreshToken)).expectError(401, "token_expired")
        api.send(HttpMethod.POST, "/auth/refresh", body = mapOf("refresh_token" to other)).expectError(401, "token_expired")
        // The access token lives out its 15 minutes; only the refresh tokens end.
        client.get("/me").expect(200)
    }

    @Test
    fun `a refresh whose answer was lost is retried with the same token and gets the same new one`() {
        val client = signIn()
        val kept = client.refreshToken
        val other = client.stubSignIn().expect(201).string("refresh_token")
        val refresh = { token: String -> api.send(HttpMethod.POST, "/auth/refresh", body = mapOf("refresh_token" to token)) }
        val retries = metrics.counter(AuthService.REFRESH_GRACE).count()

        // The answers to the first two never reach the device, which still holds the token it sent.
        val lost = List(2) { refresh(kept).expect(200) }
        val answer = refresh(kept).expect(200)
        client.adopt(answer)

        // One token came of it, however often it was asked for, and one session row; only the access tokens are new.
        assertThat(lost.map { it.string("refresh_token") }.toSet()).containsExactly(client.refreshToken)
        assertThat(client.refreshToken).isNotEqualTo(kept)
        assertThat(
            jdbc
                .sql("SELECT count(*) FROM sessions WHERE rotated_from = (SELECT id FROM sessions WHERE refresh_hash = :hash)")
                .param("hash", AuthService.sha256(kept))
                .query(Long::class.java)
                .single(),
        ).isEqualTo(1)
        assertThat(metrics.counter(AuthService.REFRESH_GRACE).count() - retries).isEqualTo(2.0)
        client.get("/me").expect(200)
        refresh(other).expect(200)
        client.adopt(refresh(client.refreshToken).expect(200))
        client.get("/me").expect(200)
    }

    @Test
    fun `a thief and the device within the grace share one token, and the second to rotate it is caught`() {
        val client = signIn()
        val stolen = client.refreshToken
        val refresh = { token: String -> api.send(HttpMethod.POST, "/auth/refresh", body = mapOf("refresh_token" to token)) }

        val thief = refresh(stolen).expect(200).string("refresh_token")
        client.adopt(refresh(stolen).expect(200))
        // Not two lines of tokens that would each rotate on unseen: both hold the one token the stolen one became.
        assertThat(client.refreshToken).isEqualTo(thief)

        client.adopt(refresh(client.refreshToken).expect(200))
        pastTheGrace(thief)
        refresh(thief).expectError(401, "token_expired")

        refresh(client.refreshToken).expectError(401, "token_expired")
        assertThat(
            jdbc
                .sql("SELECT count(*) FROM sessions WHERE device_id = :id")
                .param("id", client.deviceId)
                .query(Long::class.java)
                .single(),
        ).isZero()
    }

    @Test
    fun `a retry whose token cannot be computed again is answered as reuse`() {
        val client = signIn()
        val kept = client.refreshToken
        val refresh = { token: String -> api.send(HttpMethod.POST, "/auth/refresh", body = mapOf("refresh_token" to token)) }
        val lost = refresh(kept).expect(200).string("refresh_token")
        // As if the answer was minted under a signing key that has left the server since: the row is some other token's.
        jdbc
            .sql("UPDATE sessions SET refresh_hash = :other WHERE refresh_hash = :hash")
            .param("other", AuthService.sha256("minted under a key that is gone"))
            .param("hash", AuthService.sha256(lost))
            .update()

        // No second token is minted in its place, which would be a second line; the device signs in again.
        refresh(kept).expectError(401, "token_expired")

        refresh(lost).expectError(401, "token_expired")
    }

    @Test
    fun `a reused refresh token signs the device out`() {
        val client = signIn()
        val stolen = client.refreshToken
        val refresh = { token: String -> api.send(HttpMethod.POST, "/auth/refresh", body = mapOf("refresh_token" to token)) }
        client.adopt(refresh(stolen).expect(200))
        client.adopt(refresh(client.refreshToken).expect(200))

        // Seconds after its rotation, but the device has used the token that came from it: this is no retry.
        refresh(stolen).expectError(401, "token_expired")

        refresh(client.refreshToken).expectError(401, "token_expired")
    }

    @Test
    fun `two refreshes racing with one token both get the same token`() {
        repeat(8) {
            val client = signIn()
            val shared = client.refreshToken
            val refresh = { token: String -> api.send(HttpMethod.POST, "/auth/refresh", body = mapOf("refresh_token" to token)) }

            val replies = listOf(shared, shared).parallelStream().map { refresh(it) }.toList()

            val minted = replies.map { it.expect(200).string("refresh_token") }
            assertThat(minted.toSet()).hasSize(1)
            // Whichever answer the device keeps, its next refresh works.
            refresh(minted[0]).expect(200)
        }
    }

    @Test
    fun `an expired spent token ends nothing`() {
        val client = signIn()
        val spent = client.refreshToken
        client.adopt(api.send(HttpMethod.POST, "/auth/refresh", body = mapOf("refresh_token" to spent)).expect(200))
        jdbc
            .sql("UPDATE sessions SET expires_at = now() - interval '1 second' WHERE refresh_hash = :hash")
            .param("hash", AuthService.sha256(spent))
            .update()

        api.send(HttpMethod.POST, "/auth/refresh", body = mapOf("refresh_token" to spent)).expectError(401, "token_expired")

        api.send(HttpMethod.POST, "/auth/refresh", body = mapOf("refresh_token" to client.refreshToken)).expect(200)
    }

    @Test
    fun `a replay racing a refresh leaves no session behind`() {
        repeat(8) {
            val client = signIn()
            val spent = client.refreshToken
            val current =
                api
                    .send(
                        HttpMethod.POST,
                        "/auth/refresh",
                        body = mapOf("refresh_token" to spent),
                    ).expect(200)
                    .string("refresh_token")
            pastTheGrace(spent)

            val replies =
                listOf(current, spent)
                    .parallelStream()
                    .map { api.send(HttpMethod.POST, "/auth/refresh", body = mapOf("refresh_token" to it)) }
                    .toList()

            assertThat(replies[1].status).isEqualTo(401)
            // Whichever went first, a token the refresh minted must not outlive the detected reuse.
            if (replies[0].status == 200) {
                api
                    .send(HttpMethod.POST, "/auth/refresh", body = mapOf("refresh_token" to replies[0].string("refresh_token")))
                    .expectError(401, "token_expired")
            }
        }
    }

    @Test
    fun `the daily cleanup drops expired sessions and tickets`() {
        val client = signIn("cleanup-subject")
        val ticket = Client(api, "cleanup-subject").stubSignIn().expect(200).string("ticket")
        jdbc.sql("UPDATE sessions SET expires_at = now() - interval '1 second' WHERE device_id = :id").param("id", client.deviceId).update()
        jdbc
            .sql("UPDATE replace_tickets SET expires_at = now() - interval '1 second' WHERE ticket_hash = :hash")
            .param("hash", AuthService.sha256(ticket))
            .update()

        cleanup.daily()

        assertThat(
            jdbc
                .sql("SELECT count(*) FROM sessions WHERE device_id = :id")
                .param("id", client.deviceId)
                .query(Int::class.java)
                .single(),
        ).isZero()
        assertThat(
            jdbc
                .sql(
                    "SELECT count(*) FROM replace_tickets WHERE ticket_hash = :hash",
                ).param("hash", AuthService.sha256(ticket))
                .query(Int::class.java)
                .single(),
        ).isZero()
    }

    @Test
    fun `a new device replaces the old one, which is told to sign out`() {
        val old = signIn("two-phones")
        val oldToken = old.token
        val oldRefresh = old.refreshToken
        inbox(old.deviceId).use { signals ->
            val new = replaceDevice("two-phones", platform = "android")

            assertThat(signals.next().type).isEqualTo("revoke")
            val revoked = api.send(HttpMethod.GET, "/me", token = oldToken).expectError(403, "device_revoked")
            assertThat(revoked.string("replaced_by")).isEqualTo("android")
            assertThat(revoked.string("replaced_via")).isEqualTo("provider")
            assertThat(java.time.Instant.parse(revoked.string("replaced_at"))).isBeforeOrEqualTo(java.time.Instant.now())
            api.send(HttpMethod.POST, "/auth/refresh", body = mapOf("refresh_token" to oldRefresh)).expectError(403, "device_revoked")
            val devices = new.get("/users/${new.userId}/devices").expect(200)["devices"]
            assertThat(devices.size()).isEqualTo(1)
            assertThat(devices[0].path("id").stringValue()).isEqualTo(new.deviceId.toString())
        }
    }

    @Test
    fun `a replaced device makes the other members of its groups look again`() {
        val owner = signIn()
        val member = signIn("replacing")
        val groupId = groupWith(owner, member)
        inbox(owner.deviceId).use { signals ->
            replaceDevice("replacing")

            val membership = signals.next()
            assertThat(membership.type).isEqualTo("membership")
            assertThat(membership.event.path("g").stringValue()).isEqualTo(groupId.toString())
        }
    }

    @Test
    fun `unknown paths and ids answer in the protocol body`() {
        val client = signIn()

        client.get("/nothing-here").expectError(404, "not_found")
        client.get("/groups/not-a-uuid/members").expectError(400, "invalid_request")
        client.get("/groups/${UUID.randomUUID()}/members").expectError(404, "not_found")
    }

    /** Moves the moment [token] was spent to before the grace in which it would count as a retry of a lost answer. */
    private fun pastTheGrace(token: String) {
        jdbc
            .sql("UPDATE sessions SET spent_at = now() - interval '61 seconds' WHERE refresh_hash = :hash AND spent_at IS NOT NULL")
            .param("hash", AuthService.sha256(token))
            .update()
    }
}
