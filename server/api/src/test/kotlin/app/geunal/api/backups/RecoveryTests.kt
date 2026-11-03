package app.geunal.api.backups

import app.geunal.api.support.ApiTest
import app.geunal.api.support.Client
import app.geunal.api.support.FakeProviders
import app.geunal.api.support.RecoveryCode
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.http.HttpMethod
import tools.jackson.databind.json.JsonMapper
import java.nio.file.Files
import java.nio.file.Path
import java.util.HexFormat
import java.util.UUID

/** `PUT` and `GET /me/recovery` (protocol §10 "복구 코드"): who may store, overwrite and start fresh. */
class RecoveryTests : ApiTest() {
    @Autowired
    lateinit var redis: StringRedisTemplate

    private fun guest(): Client {
        val device = Client(api, "guest-${UUID.randomUUID()}")
        val body = device.proof() + mapOf("age_confirmed_at" to "2026-10-31T00:00:00Z", "display_name" to "손님")
        device.adopt(api.send(HttpMethod.POST, "/auth/guest", body = body).expect(201))
        return device
    }

    private fun kakao(sub: String = "k-${UUID.randomUUID()}"): Client =
        Client(api, "kakao-$sub").also { c -> c.providerSignIn("kakao") { FakeProviders.kakaoToken(sub, it) }.expect(201) }

    /** A backup row and the object key it names, as if one had been completed. */
    private fun backup(account: Client): String {
        val key = UUID.randomUUID().toString()
        jdbc
            .sql("INSERT INTO backups (user_id, object_key, size_bytes, completed_at) VALUES (:user, :key, 100, now())")
            .param("user", account.userId)
            .param("key", key)
            .update()
        return key
    }

    private fun backups(account: Client): Int =
        jdbc
            .sql("SELECT count(*) FROM backups WHERE user_id = :user")
            .param("user", account.userId)
            .query(Int::class.java)
            .single()

    private fun queued(key: String): Boolean =
        jdbc
            .sql(
                "SELECT count(*) FROM object_deletions WHERE object_key = :key AND bucket = 'backups'",
            ).param("key", key)
            .query(Int::class.java)
            .single() ==
            1

    private fun pastTheWindow(account: Client) {
        jdbc
            .sql(
                "UPDATE devices SET provider_auth_at = now() - interval '31 minutes' WHERE id = :id",
            ).param("id", account.deviceId)
            .update()
    }

    private fun rotate(
        account: Client,
        old: RecoveryCode,
        new: RecoveryCode,
    ): Map<String, Any> {
        val nonce = account.nonce()
        return new.upload() + mapOf("nonce" to nonce, "proof" to old.sign("geunal recovery rotate v1", nonce))
    }

    @Test
    fun `the first upload needs no proof and the account then says it has recovery material`() {
        val account = guest()
        val code = RecoveryCode()
        account.get("/me/recovery").expectError(404, "not_found")

        account.putRecovery(code.upload()).expect(204)

        val stored = account.get("/me/recovery").expect(200)
        assertThat(stored.bytes("amk_wrapped")).isEqualTo(code.amkWrapped)
        assertThat(stored.string("updated_at")).isNotBlank()
        assertThat(account.get("/me").expect(200)["recovery"].booleanValue()).isTrue()
    }

    @Test
    fun `rejects material of the wrong lengths`() {
        val account = guest()
        val code = RecoveryCode()

        account.putRecovery(code.upload() + ("amk_wrapped" to ByteArray(71))).expectError(400, "invalid_request")
        account.putRecovery(code.upload() + ("recovery_id" to ByteArray(17))).expectError(400, "invalid_request")
        account.putRecovery(code.upload() + ("recovery_public_key" to ByteArray(31))).expectError(400, "invalid_request")
        account.put("/me/recovery", mapOf("amk_wrapped" to ByteArray(72))).expectError(400, "invalid_request")
    }

    @Test
    fun `overwriting needs the current code's signature over a fresh nonce`() {
        val account = guest()
        val old = RecoveryCode().also { account.putRecovery(it.upload()).expect(204) }
        val new = RecoveryCode()

        // A retried first upload finds the material stored and needs the proof too.
        account.putRecovery(old.upload()).expectError(403, "recovery_proof_required")
        account.putRecovery(rotate(account, new, new)).expectError(403, "recovery_proof_required")
        val nonce = account.nonce()
        account
            .putRecovery(new.upload() + mapOf("nonce" to nonce, "proof" to old.sign("geunal delete v1", nonce)))
            .expectError(403, "recovery_proof_required")
        account
            .putRecovery(
                new.upload() + mapOf("nonce" to "never-issued", "proof" to old.sign("geunal recovery rotate v1", "never-issued")),
            ).expectError(403, "recovery_proof_required")
        assertThat(account.get("/me/recovery").expect(200).bytes("amk_wrapped")).isEqualTo(old.amkWrapped)

        val proven = rotate(account, old, new)
        account.putRecovery(proven).expect(204)
        assertThat(account.get("/me/recovery").expect(200).bytes("amk_wrapped")).isEqualTo(new.amkWrapped)
        // The nonce is spent.
        account.putRecovery(proven).expectError(403, "recovery_proof_required")
    }

    @Test
    fun `accepts the core's rotate signature from the recovery vectors`() {
        val vectors = JsonMapper.builder().build().readTree(Files.readString(Path.of("..", "..", "core", "vectors", "recovery.json")))
        val case = vectors.path("codes")[0]
        val hex = HexFormat.of()
        val nonce = case.path("nonce").stringValue()
        val account = guest()
        val stored =
            mapOf(
                "amk_wrapped" to hex.parseHex(case.path("wrapped_amk").stringValue()),
                "recovery_id" to hex.parseHex(case.path("recovery_id").stringValue()),
                "recovery_public_key" to hex.parseHex(case.path("recovery_public_key").stringValue()),
            )
        account.putRecovery(stored).expect(204)
        // The vector signs a fixed nonce; issue that one as GET /auth/nonce would.
        redis.opsForValue().set("nonce:$nonce", "1")

        val again = stored + mapOf("nonce" to nonce, "proof" to hex.parseHex(case.path("rotate_signature").stringValue()))
        account.putRecovery(again).expect(204)
    }

    @Test
    fun `a recovery ID another account uses is a conflict`() {
        val code = RecoveryCode()
        guest().putRecovery(code.upload()).expect(204)

        guest().putRecovery(code.upload()).expectError(409, "recovery_conflict")
    }

    @Test
    fun `a provider account starts fresh within 30 minutes of its sign-in, and the backups go`() {
        val account = kakao()
        RecoveryCode().also { account.putRecovery(it.upload()).expect(204) }
        val key = backup(account)
        val fresh = RecoveryCode()

        account.putRecovery(fresh.upload() + ("new_key" to true)).expect(204)

        assertThat(account.get("/me/recovery").expect(200).bytes("amk_wrapped")).isEqualTo(fresh.amkWrapped)
        assertThat(backups(account)).isZero()
        assertThat(queued(key)).isTrue()
    }

    @Test
    fun `past the 30 minutes starting fresh needs a fresh provider proof for this account`() {
        val sub = "k-${UUID.randomUUID()}"
        val account = kakao(sub)
        val old = RecoveryCode().also { account.putRecovery(it.upload()).expect(204) }
        backup(account)
        pastTheWindow(account)
        // Refreshing does not reopen the window.
        account.adopt(api.send(HttpMethod.POST, "/auth/refresh", body = mapOf("refresh_token" to account.refreshToken)).expect(200))
        val fresh = RecoveryCode()

        account.putRecovery(fresh.upload() + ("new_key" to true)).expectError(403, "reauth_required")
        val other = account.nonce()
        account
            .putRecovery(
                fresh.upload() +
                    mapOf(
                        "new_key" to true,
                        "nonce" to other,
                        "kakao_id_token" to FakeProviders.kakaoToken("someone-else", account.binding(other)),
                    ),
            ).expectError(403, "reauth_required")
        val stranger = account.nonce()
        account
            .putRecovery(
                fresh.upload() +
                    mapOf(
                        "new_key" to true,
                        "nonce" to stranger,
                        "kakao_id_token" to FakeProviders.kakaoToken(sub, Client(api, "x").binding(stranger)),
                    ),
            ).expectError(403, "reauth_required")
        assertThat(account.get("/me/recovery").expect(200).bytes("amk_wrapped")).isEqualTo(old.amkWrapped)
        assertThat(backups(account)).isEqualTo(1)

        val nonce = account.nonce()
        val proof = mapOf("new_key" to true, "nonce" to nonce, "kakao_id_token" to FakeProviders.kakaoToken(sub, account.binding(nonce)))
        account.putRecovery(fresh.upload() + proof).expect(204)
        assertThat(account.get("/me/recovery").expect(200).bytes("amk_wrapped")).isEqualTo(fresh.amkWrapped)
        assertThat(backups(account)).isZero()
    }

    @Test
    fun `an Apple account proves itself with a fresh authorization code and keeps its Apple tokens`() {
        for (platform in listOf("ios", "android")) {
            val sub = "a-${UUID.randomUUID()}"
            val audience = if (platform == "ios") FakeProviders.APPLE_BUNDLE_ID else FakeProviders.APPLE_SERVICE_ID
            val account = Client(api, "apple-$sub")
            account.providerSignIn("apple", platform = platform) { FakeProviders.appleToken(sub, it, audience = audience) }.expect(201)
            RecoveryCode().also { account.putRecovery(it.upload()).expect(204) }
            pastTheWindow(account)
            val fresh = RecoveryCode()

            account
                .putRecovery(
                    fresh.upload() + mapOf("new_key" to true, "apple_authorization_code" to "made-up"),
                ).expectError(403, "reauth_required")
            val code = FakeProviders.appleCode(sub, platform)
            account.putRecovery(fresh.upload() + mapOf("new_key" to true, "apple_authorization_code" to code)).expect(204)

            val exchange = FakeProviders.callsTo("/apple/token").last { it.form["code"] == code }
            assertThat(exchange.form["client_id"]).describedAs(platform).isEqualTo(audience)
            assertThat(exchange.form["redirect_uri"]).describedAs(platform).isEqualTo(
                if (platform ==
                    "android"
                ) {
                    FakeProviders.APPLE_REDIRECT_URI
                } else {
                    null
                },
            )
            assertThat(FakeProviders.callsTo("/apple/revoke").map { it.form["token"] }).doesNotContain("r-$sub")
        }
    }

    @Test
    fun `a guest cannot start fresh without the code`() {
        val account = guest()
        RecoveryCode().also { account.putRecovery(it.upload()).expect(204) }
        backup(account)

        account.putRecovery(RecoveryCode().upload() + ("new_key" to true)).expectError(403, "reauth_required")

        assertThat(backups(account)).isEqualTo(1)
    }

    @Test
    fun `a replacement ticket reads the material but cannot write it`() {
        val sub = "ticket-${UUID.randomUUID()}"
        val account = signIn(sub)
        val code = RecoveryCode().also { account.putRecovery(it.upload()).expect(204) }
        val ticket =
            Client(api, sub)
                .stubSignIn()
                .expect(200)
                .also { assertThat(it["recovery"].booleanValue()).isTrue() }
                .string("ticket")

        assertThat(api.send(HttpMethod.GET, "/me/recovery", token = ticket).expect(200).bytes("amk_wrapped")).isEqualTo(code.amkWrapped)
        api.send(HttpMethod.PUT, "/me/recovery", token = ticket, body = RecoveryCode().upload()).expectError(403, "replace_required")
    }

    @Test
    fun `refuses recovery material without the device key's signature over it`() {
        val account = guest()
        val code = RecoveryCode()
        val other = RecoveryCode()

        fun words(
            nonce: String,
            material: RecoveryCode,
            label: String = "geunal recovery upload v1",
        ) = (label + nonce).toByteArray() + material.id + material.publicKey + material.amkWrapped

        fun send(
            body: Map<String, Any?>,
            nonce: String,
            signature: ByteArray?,
        ) = account.put("/me/recovery", body + mapOf("nonce" to nonce, "device_signature" to signature))

        // A stolen session cannot put its own code in first, and nobody can swap the material under the signature.
        send(code.upload(), account.nonce(), null).expectError(403, "recovery_proof_required")
        account.nonce().let { send(code.upload(), it, Client(api, "x").sign(words(it, code))).expectError(403, "recovery_proof_required") }
        account.nonce().let { send(code.upload(), it, account.sign(words(it, other))).expectError(403, "recovery_proof_required") }
        account.nonce().let {
            send(code.upload(), it, account.sign(words(it, code, "geunal new key v1"))).expectError(403, "recovery_proof_required")
        }
        send(code.upload(), "never-issued", account.sign(words("never-issued", code))).expectError(403, "recovery_proof_required")
        account.get("/me/recovery").expectError(404, "not_found")

        account.putRecovery(code.upload()).expect(204)

        // Overwriting: the current code's proof does not make up for material the device did not sign.
        val nonce = account.nonce()
        val proof = mapOf("proof" to code.sign("geunal recovery rotate v1", nonce))
        send(other.upload() + proof, nonce, account.sign(words(nonce, code))).expectError(403, "recovery_proof_required")
        assertThat(account.get("/me/recovery").expect(200).bytes("amk_wrapped")).isEqualTo(code.amkWrapped)
        account.putRecovery(rotate(account, code, other)).expect(204)
        assertThat(account.get("/me/recovery").expect(200).bytes("amk_wrapped")).isEqualTo(other.amkWrapped)
    }
}
