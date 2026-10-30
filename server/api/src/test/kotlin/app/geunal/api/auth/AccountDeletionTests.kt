package app.geunal.api.auth

import app.geunal.api.support.ApiTest
import app.geunal.api.support.Client
import app.geunal.api.support.FakeProviders
import app.geunal.api.support.Mls
import app.geunal.api.support.RecoveryCode
import app.geunal.api.support.Reply
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.HttpMethod
import java.util.UUID

/** `DELETE /me` (protocol §13 "계정 삭제", api-server.md §11): proofs by account kind and what one transaction takes. */
class AccountDeletionTests : ApiTest() {
    @Autowired
    lateinit var revocations: ProviderRevocations

    private fun guest(): Client {
        val device = Client(api, "guest-${UUID.randomUUID()}")
        val body = device.proof() + mapOf("age_confirmed_at" to "2026-10-31T00:00:00Z", "display_name" to "지울 사람")
        device.adopt(api.send(HttpMethod.POST, "/auth/guest", body = body).expect(201))
        return device
    }

    private fun delete(
        account: Client,
        body: Map<String, Any?>,
    ): Reply = api.send(HttpMethod.DELETE, "/me", account.token, body)

    private fun guestProof(
        account: Client,
        code: RecoveryCode,
        label: String = "geunal delete v1",
    ): Map<String, Any?> {
        val nonce = account.nonce()
        return mapOf("nonce" to nonce, "recovery_signature" to code.sign(label, nonce))
    }

    private fun count(
        sql: String,
        id: Any,
    ): Int =
        jdbc
            .sql(sql)
            .param("id", id)
            .query(Int::class.java)
            .single()

    @Test
    fun `a guest deletes with the recovery key and only the log and a placeholder stay`() {
        val account = guest()
        val member = signIn()
        val groupId = groupWith(account, member)
        val code = RecoveryCode().also { account.put("/me/recovery", it.upload()).expect(204) }
        account.message(groupId, 1).expect(201)
        account.put("/groups/$groupId/state/availability", mapOf("epoch" to 1, "ciphertext" to ByteArray(64))).expect(200)
        account
            .put(
                "/groups/$groupId/state/group_meta",
                mapOf("epoch" to 1, "ciphertext" to ByteArray(64), "base_version" to 0),
            ).expect(200)
        account.post("/me/key-packages", mapOf("key_packages" to listOf(Mls.keyPackage()))).expect(200)
        val backupKey = UUID.randomUUID().toString()
        jdbc
            .sql("INSERT INTO backups (user_id, object_key, size_bytes, completed_at) VALUES (:id, :key, 10, now())")
            .param("id", account.userId)
            .param("key", backupKey)
            .update()
        val mediaKey = UUID.randomUUID().toString()
        jdbc
            .sql(
                "INSERT INTO media_objects (object_key, scope, uploader_id, size_bytes, expires_at) VALUES (:key, 'personal', :id, 10, now() + interval '1 year')",
            ).param("key", mediaKey)
            .param("id", account.userId)
            .update()
        val token = account.token

        inbox(member.deviceId, account.deviceId).use { signals ->
            delete(account, guestProof(account, code)).expect(204)

            val received = signals.drain()
            assertThat(received.filter { it.channel == "d:${member.deviceId}" }.map { it.type }).contains("membership")
            assertThat(received.filter { it.channel == "d:${account.deviceId}" }.map { it.type }).contains("revoke")
        }
        val id = account.userId
        assertThat(count("SELECT count(*) FROM devices WHERE user_id = :id", id)).isZero()
        assertThat(count("SELECT count(*) FROM sessions WHERE device_id = :id", account.deviceId)).isZero()
        assertThat(count("SELECT count(*) FROM key_packages WHERE device_id = :id", account.deviceId)).isZero()
        assertThat(count("SELECT count(*) FROM recovery WHERE user_id = :id", id)).isZero()
        assertThat(count("SELECT count(*) FROM backups WHERE user_id = :id", id)).isZero()
        assertThat(count("SELECT count(*) FROM media_objects WHERE uploader_id = :id", id)).isZero()
        assertThat(count("SELECT count(*) FROM group_members WHERE user_id = :id", id)).isZero()
        assertThat(count("SELECT count(*) FROM group_state WHERE owner_id = :id AND kind = 'availability'", id)).isZero()
        assertThat(count("SELECT count(*) FROM group_state WHERE group_id = :id AND kind = 'group_meta'", groupId)).isEqualTo(1)
        assertThat(count("SELECT count(*) FROM object_deletions WHERE object_key = :id AND bucket = 'backups'", backupKey)).isEqualTo(1)
        assertThat(
            count("SELECT count(*) FROM object_deletions WHERE object_key = :id AND bucket = 'media-personal'", mediaKey),
        ).isEqualTo(1)
        assertThat(count("SELECT count(*) FROM group_log WHERE author_id = :id", id)).isEqualTo(2)
        val placeholder =
            jdbc
                .sql("SELECT display_name, age_confirmed_at IS NULL AS no_age, deleted_at IS NOT NULL AS deleted FROM users WHERE id = :id")
                .param("id", id)
                .query { rs, _ -> Triple(rs.getString("display_name"), rs.getBoolean("no_age"), rs.getBoolean("deleted")) }
                .single()
        assertThat(placeholder).isEqualTo(Triple(null, true, true))
        // The member who stayed owns the group now and no longer sees the deleted account.
        val members = member.get("/groups/$groupId/members").expect(200)["members"]
        assertThat(members.values().map { it.path("user_id").stringValue() }).containsExactly(member.userId.toString())
        assertThat(members[0].path("role").stringValue()).isEqualTo("owner")
        assertThat(
            member
                .get("/groups/$groupId/log")
                .expect(200)["entries"]
                .values()
                .map { it.path("author_id").stringValue() },
        ).contains(id.toString())
        api.send(HttpMethod.GET, "/me", token = token).expectError(403, "device_revoked")
    }

    @Test
    fun `a guest without recovery material stores it first`() {
        val account = guest()

        delete(account, mapOf("nonce" to account.nonce(), "recovery_signature" to ByteArray(64))).expectError(409, "recovery_missing")
        account.get("/me").expect(200)
    }

    @Test
    fun `without the right proof nothing is deleted and the session stays`() {
        val account = guest()
        val code = RecoveryCode().also { account.put("/me/recovery", it.upload()).expect(204) }

        delete(account, emptyMap()).expectError(403, "reauth_required")
        api.send(HttpMethod.DELETE, "/me", account.token).expectError(403, "reauth_required")
        delete(account, guestProof(account, RecoveryCode())).expectError(403, "reauth_required")
        delete(account, guestProof(account, code, label = "geunal recover v1")).expectError(403, "reauth_required")
        delete(account, mapOf("nonce" to "never-issued", "recovery_signature" to code.sign("geunal delete v1", "never-issued")))
            .expectError(403, "reauth_required")
        delete(
            account,
            mapOf("kakao_id_token" to FakeProviders.kakaoToken("x", "y"), "nonce" to account.nonce()),
        ).expectError(403, "reauth_required")

        account.get("/me").expect(200)
        assertThat(count("SELECT count(*) FROM recovery WHERE user_id = :id", account.userId)).isEqualTo(1)
    }

    @Test
    fun `a Kakao account deletes with a fresh ID token and is unlinked from Kakao`() {
        val sub = (5_000_000_000L + (Math.random() * 1e9).toLong()).toString()
        val account = Client(api, "kakao-delete")
        account.providerSignIn("kakao") { FakeProviders.kakaoToken(sub, it) }.expect(201)

        delete(account, emptyMap()).expectError(403, "reauth_required")
        val other = account.nonce()
        delete(account, mapOf("nonce" to other, "kakao_id_token" to FakeProviders.kakaoToken("someone-else", account.binding(other))))
            .expectError(403, "reauth_required")
        val stranger = account.nonce()
        delete(account, mapOf("nonce" to stranger, "kakao_id_token" to FakeProviders.kakaoToken(sub, Client(api, "x").binding(stranger))))
            .expectError(403, "reauth_required")
        assertThat(FakeProviders.callsTo("/kakao/unlink").map { it.form["target_id"] }).doesNotContain(sub)

        val nonce = account.nonce()
        delete(account, mapOf("nonce" to nonce, "kakao_id_token" to FakeProviders.kakaoToken(sub, account.binding(nonce)))).expect(204)

        val unlink = FakeProviders.callsTo("/kakao/unlink").single { it.form["target_id"] == sub }
        assertThat(unlink.authorization).isEqualTo("KakaoAK ${FakeProviders.KAKAO_ADMIN_KEY}")
        assertThat(unlink.form["target_id_type"]).isEqualTo("user_id")
        assertThat(count("SELECT count(*) FROM provider_revocations WHERE target = :id", sub)).isZero()

        // The same Kakao account signing in again is a new account.
        val again = Client(api, "kakao-after-delete").providerSignIn("kakao") { FakeProviders.kakaoToken(sub, it) }.expect(201)
        assertThat(again["new_account"].booleanValue()).isTrue()
        assertThat(again.uuid("user_id")).isNotEqualTo(account.userId)
    }

    @Test
    fun `an Apple account deletes with a fresh code, whose tokens are revoked at once`() {
        for (platform in listOf("ios", "android")) {
            val sub = "apple-delete-${UUID.randomUUID()}"
            val audience = if (platform == "ios") FakeProviders.APPLE_BUNDLE_ID else FakeProviders.APPLE_SERVICE_ID
            val account = Client(api, sub)
            account.providerSignIn("apple", platform = platform) { FakeProviders.appleToken(sub, it, audience = audience) }.expect(201)

            delete(
                account,
                mapOf("apple_authorization_code" to FakeProviders.appleCode("someone-else", platform)),
            ).expectError(403, "reauth_required")
            delete(account, mapOf("apple_authorization_code" to "made-up")).expectError(403, "reauth_required")
            account.get("/me").expect(200)

            delete(account, mapOf("apple_authorization_code" to FakeProviders.appleCode(sub, platform))).expect(204)

            val revoke = FakeProviders.callsTo("/apple/revoke").single { it.form["token"] == "r-$sub" }
            assertThat(revoke.form["client_id"]).describedAs(platform).isEqualTo(audience)
            assertThat(revoke.form["token_type_hint"]).isEqualTo("refresh_token")
            assertThat(revoke.form["client_secret"]).isNotBlank()
            assertThat(count("SELECT count(*) FROM provider_revocations WHERE client_id = :id", audience)).isZero()
        }
    }

    @Test
    fun `a Kakao unlink that fails is retried until Kakao takes it`() {
        val sub = (7_000_000_000L + (Math.random() * 1e9).toLong()).toString()
        val account = Client(api, "kakao-retry")
        account.providerSignIn("kakao") { FakeProviders.kakaoToken(sub, it) }.expect(201)
        FakeProviders.kakaoUnlinkStatus = 503
        try {
            val nonce = account.nonce()
            delete(account, mapOf("nonce" to nonce, "kakao_id_token" to FakeProviders.kakaoToken(sub, account.binding(nonce)))).expect(204)
            assertThat(count("SELECT attempts FROM provider_revocations WHERE target = :id", sub)).isEqualTo(1)
        } finally {
            FakeProviders.kakaoUnlinkStatus = 200
        }
        jdbc.sql("UPDATE provider_revocations SET next_attempt_at = now() WHERE target = :sub").param("sub", sub).update()

        revocations.retry()

        assertThat(count("SELECT count(*) FROM provider_revocations WHERE target = :id", sub)).isZero()
        assertThat(FakeProviders.callsTo("/kakao/unlink").count { it.form["target_id"] == sub }).isEqualTo(2)
    }

    @Test
    fun `an account whose provider cut the link deletes like a guest`() {
        val sub = "cut-${UUID.randomUUID()}"
        val account = Client(api, "kakao-cut-delete")
        account.providerSignIn("kakao") { FakeProviders.kakaoToken(sub, it) }.expect(201)
        val code = RecoveryCode().also { account.put("/me/recovery", it.upload()).expect(204) }
        jdbc.sql("UPDATE provider_links SET unlinked_at = now() WHERE sub = :sub").param("sub", sub).update()

        delete(account, guestProof(account, code)).expect(204)

        assertThat(FakeProviders.callsTo("/kakao/unlink").map { it.form["target_id"] }).doesNotContain(sub)
    }

    @Test
    fun `a replacement ticket cannot delete the account`() {
        val subject = "delete-ticket-${UUID.randomUUID()}"
        signIn(subject)
        val ticket = Client(api, subject).stubSignIn().expect(200).string("ticket")

        api.send(HttpMethod.DELETE, "/me", ticket, emptyMap<String, Any>()).expectError(403, "replace_required")
    }
}
