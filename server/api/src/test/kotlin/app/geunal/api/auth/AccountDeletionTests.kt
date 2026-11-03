package app.geunal.api.auth

import app.geunal.api.ops.Cleanup
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
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.util.UUID
import java.util.concurrent.CompletableFuture

/** `DELETE /me` (protocol §13 "계정 삭제", api-server.md §11): proofs by account kind and what one transaction takes. */
class AccountDeletionTests : ApiTest() {
    @Autowired
    lateinit var revocations: ProviderRevocations

    @Autowired
    lateinit var cleanup: Cleanup

    @Autowired
    lateinit var transactions: PlatformTransactionManager

    /**
     * Holds [userId]'s account lock, as a deletion does, until [request] waits on it; then commits what deletion leaves
     * (no links, devices or recovery material, a placeholder row) and returns the request's answer.
     */
    private fun whileDeleting(
        userId: UUID,
        request: () -> Reply,
    ): Reply {
        lateinit var pending: CompletableFuture<Reply>
        TransactionTemplate(transactions).executeWithoutResult {
            jdbc
                .sql("SELECT id FROM users WHERE id = :id FOR UPDATE")
                .param("id", userId)
                .query()
                .listOfRows()
            pending = CompletableFuture.supplyAsync(request)
            val deadline = System.nanoTime() + 10_000_000_000L
            // pg_locks, unlike pg_stat_activity, is not a snapshot taken once per transaction.
            val waiting = "SELECT count(*) FROM pg_locks WHERE NOT granted AND pid <> pg_backend_pid()"
            while (jdbc.sql(waiting).query(Int::class.java).single() == 0) {
                check(System.nanoTime() < deadline && !pending.isDone) { "the request never waited on the account lock" }
                Thread.sleep(20)
            }
            for (sql in listOf(
                "DELETE FROM recovery WHERE user_id = :id",
                "DELETE FROM provider_links WHERE user_id = :id",
                "DELETE FROM devices WHERE user_id = :id",
                "UPDATE users SET display_name = NULL, age_confirmed_at = NULL, deleted_at = now() WHERE id = :id",
            )) {
                jdbc.sql(sql).param("id", userId).update()
            }
        }
        return pending.get()
    }

    private fun guest(): Client {
        val device = Client(api, "guest-${UUID.randomUUID()}")
        val body = device.proof() + mapOf("age_confirmed_at" to "2026-10-31T00:00:00Z", "display_name" to "지울 사람")
        device.adopt(api.send(HttpMethod.POST, "/auth/guest", body = body).expect(201))
        return device
    }

    /** The words a device signs to delete its account (protocol §13). */
    private fun deleteWords(nonce: String) = "geunal delete v1$nonce".toByteArray(Charsets.US_ASCII)

    /**
     * `DELETE /me` as the device sends it: [body]'s proof with the device key's signature over its nonce, or over a
     * fresh one when the proof needs none (Apple).
     */
    private fun delete(
        account: Client,
        body: Map<String, Any?>,
    ): Reply {
        val nonce = body["nonce"] as String? ?: account.nonce()
        val signed = body + mapOf("nonce" to nonce, "device_signature" to account.sign(deleteWords(nonce)))
        return api.send(HttpMethod.DELETE, "/me", account.token, signed)
    }

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
        val code = RecoveryCode().also { account.putRecovery(it.upload()).expect(204) }
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
        val refresh = account.refreshToken

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
        // A device that lost the 204 learns from its tokens that the deletion went through.
        api.send(HttpMethod.GET, "/me", token = token).expectError(401, "account_deleted")
        api.send(HttpMethod.POST, "/auth/refresh", body = mapOf("refresh_token" to refresh)).expectError(401, "account_deleted")
        assertThat(count("SELECT count(*) FROM deleted_sessions WHERE refresh_hash = :id", AuthService.sha256(refresh))).isEqualTo(1)
    }

    @Test
    fun `a deleted account's refresh token answers account_deleted only until it would have expired`() {
        val account = guest()
        val code = RecoveryCode().also { account.putRecovery(it.upload()).expect(204) }
        val refresh = account.refreshToken
        delete(account, guestProof(account, code)).expect(204)
        jdbc
            .sql("UPDATE deleted_sessions SET expires_at = now() - interval '1 second' WHERE refresh_hash = :hash")
            .param("hash", AuthService.sha256(refresh))
            .update()

        api.send(HttpMethod.POST, "/auth/refresh", body = mapOf("refresh_token" to refresh)).expectError(401, "token_expired")
        cleanup.daily()
        assertThat(count("SELECT count(*) FROM deleted_sessions WHERE refresh_hash = :id", AuthService.sha256(refresh))).isZero()
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
        val code = RecoveryCode().also { account.putRecovery(it.upload()).expect(204) }

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
        val code = RecoveryCode().also { account.putRecovery(it.upload()).expect(204) }
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

    @Test
    fun `a sign-in that waited on a deletion signs up afresh instead of reviving the placeholder`() {
        val subject = "delete-race-${UUID.randomUUID()}"
        val deleted = signIn(subject)
        val device = Client(api, subject)

        val answer = whileDeleting(deleted.userId) { device.stubSignIn() }.expect(201)

        assertThat(answer["new_account"].booleanValue()).isTrue()
        assertThat(answer.uuid("user_id")).isNotEqualTo(deleted.userId)
        assertThat(count("SELECT count(*) FROM devices WHERE user_id = :id", deleted.userId)).isZero()
    }

    @Test
    fun `a recovery that waited on a deletion is refused`() {
        val account = guest()
        val code = RecoveryCode().also { account.putRecovery(it.upload()).expect(204) }
        val device = Client(api, "recover-race")
        val nonce = device.nonce()
        val body = device.proof(nonce) + mapOf("recovery_id" to code.id, "recovery_signature" to code.sign("geunal recover v1", nonce))

        whileDeleting(account.userId) { api.send(HttpMethod.POST, "/auth/recover", body = body) }.expectError(401, "recovery_invalid")

        assertThat(count("SELECT count(*) FROM devices WHERE user_id = :id", account.userId)).isZero()
    }

    @Test
    fun `a queued Kakao unlink is dropped once the same Kakao account signs up again`() {
        val sub = (8_000_000_000L + (Math.random() * 1e9).toLong()).toString()
        val account = Client(api, "kakao-again")
        account.providerSignIn("kakao") { FakeProviders.kakaoToken(sub, it) }.expect(201)
        FakeProviders.kakaoUnlinkStatus = 503
        try {
            val nonce = account.nonce()
            delete(account, mapOf("nonce" to nonce, "kakao_id_token" to FakeProviders.kakaoToken(sub, account.binding(nonce)))).expect(204)
        } finally {
            FakeProviders.kakaoUnlinkStatus = 200
        }
        Client(api, "kakao-again-new").providerSignIn("kakao") { FakeProviders.kakaoToken(sub, it) }.expect(201)
        jdbc.sql("UPDATE provider_revocations SET next_attempt_at = now() WHERE target = :sub").param("sub", sub).update()

        revocations.retry()

        assertThat(count("SELECT count(*) FROM provider_revocations WHERE target = :id", sub)).isZero()
        assertThat(FakeProviders.callsTo("/kakao/unlink").count { it.form["target_id"] == sub }).isEqualTo(1)
    }

    @Test
    fun `refuses to delete an account or start over without the device key's signature`() {
        val guest = guest()
        val code = RecoveryCode().also { guest.putRecovery(it.upload()).expect(204) }
        val kakaoSub = (6_000_000_000L + (Math.random() * 1e9).toLong()).toString()
        val kakao = Client(api, "signed-kakao")
        kakao.providerSignIn("kakao") { FakeProviders.kakaoToken(kakaoSub, it) }.expect(201)
        val appleSub = "signed-apple-${UUID.randomUUID()}"
        val apple = Client(api, appleSub)
        apple.providerSignIn("apple") { FakeProviders.appleToken(appleSub, it) }.expect(201)
        val appleCode = FakeProviders.appleCode(appleSub)
        // Each account's own proof over a nonce: what a stolen session could gather without the device key.
        val proofs: List<Pair<Client, (String) -> Map<String, Any?>>> =
            listOf(
                guest to { nonce -> mapOf("recovery_signature" to code.sign("geunal delete v1", nonce)) },
                kakao to { nonce -> mapOf("kakao_id_token" to FakeProviders.kakaoToken(kakaoSub, kakao.binding(nonce))) },
                apple to { _ -> mapOf("apple_authorization_code" to appleCode) },
            )
        val stranger = Client(api, "signed-stranger")

        for ((account, proof) in proofs) {
            fun send(
                nonce: String,
                signature: ByteArray?,
            ) = api.send(HttpMethod.DELETE, "/me", account.token, proof(nonce) + mapOf("nonce" to nonce, "device_signature" to signature))

            send(account.nonce(), null).expectError(403, "reauth_required")
            account.nonce().let { send(it, stranger.sign(deleteWords(it))).expectError(403, "reauth_required") }
            account.nonce().let { send(it, account.sign("geunal new key v1$it".toByteArray())).expectError(403, "reauth_required") }
            send(account.nonce(), account.sign(deleteWords(account.nonce()))).expectError(403, "reauth_required")
            // A good signature spends its nonce even when the rest fails, so it cannot be sent again.
            val spent = account.nonce()
            api
                .send(
                    HttpMethod.DELETE,
                    "/me",
                    account.token,
                    mapOf(
                        "nonce" to spent,
                        "device_signature" to account.sign(deleteWords(spent)),
                    ),
                ).expectError(403, "reauth_required")
            send(spent, account.sign(deleteWords(spent))).expectError(403, "reauth_required")
            account.get("/me").expect(200)
        }
        // Apple was never asked: a request the device did not sign stops before the code is exchanged.
        assertThat(FakeProviders.callsTo("/apple/token").count { it.form["code"] == appleCode }).isZero()

        // Starting over, within the 30 minutes of the Kakao sign-in: the device's signature is what stays missing.
        val fresh = RecoveryCode()
        kakao.putRecovery(RecoveryCode().upload()).expect(204)
        val unsigned = fresh.upload() + mapOf("new_key" to true, "nonce" to kakao.nonce())
        kakao.put("/me/recovery", unsigned).expectError(403, "reauth_required")
        val asUpload = kakao.nonce()
        val uploadWords = ("geunal recovery upload v1$asUpload").toByteArray() + fresh.id + fresh.publicKey + fresh.amkWrapped
        kakao
            .put(
                "/me/recovery",
                fresh.upload() + mapOf("new_key" to true, "nonce" to asUpload, "device_signature" to kakao.sign(uploadWords)),
            ).expectError(403, "reauth_required")
        assertThat(kakao.get("/me/recovery").expect(200).bytes("amk_wrapped")).isNotEqualTo(fresh.amkWrapped)

        for ((account, proof) in proofs) {
            val nonce = account.nonce()
            api
                .send(
                    HttpMethod.DELETE,
                    "/me",
                    account.token,
                    proof(nonce) + mapOf("nonce" to nonce, "device_signature" to account.sign(deleteWords(nonce))),
                ).expect(204)
        }
    }
}
