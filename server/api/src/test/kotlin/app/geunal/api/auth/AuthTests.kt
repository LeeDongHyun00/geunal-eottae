package app.geunal.api.auth

import app.geunal.api.support.ApiTest
import app.geunal.api.support.Client
import com.nimbusds.jose.crypto.ECDSAVerifier
import com.nimbusds.jose.jwk.JWKSet
import com.nimbusds.jwt.SignedJWT
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.HttpMethod
import java.time.Duration
import java.util.UUID

class AuthTests : ApiTest() {
    @Autowired
    lateinit var tokens: AccessTokens

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
    fun `signing in again with the same subject keeps the account`() {
        val first = signIn("same-person")
        val second = Client(api, "same-person").also { it.login() }

        assertThat(second.userId).isEqualTo(first.userId)
        assertThat(second.deviceId).isNotEqualTo(first.deviceId)
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
        api.send(HttpMethod.POST, "/auth/refresh", body = mapOf("refresh_token" to oldRefresh)).expectError(401, "token_expired")
    }

    @Test
    fun `a new device replaces the old one, which is told to sign out`() {
        val old = signIn("two-phones")
        val oldToken = old.token
        val oldRefresh = old.refreshToken
        inbox(old.deviceId).use { signals ->
            val new = Client(api, "two-phones").also { it.login() }

            assertThat(signals.next().type).isEqualTo("revoke")
            api.send(HttpMethod.GET, "/me", token = oldToken).expectError(403, "device_revoked")
            api.send(HttpMethod.POST, "/auth/refresh", body = mapOf("refresh_token" to oldRefresh)).expectError(401, "token_expired")
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
            Client(api, "replacing").login()

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
}
