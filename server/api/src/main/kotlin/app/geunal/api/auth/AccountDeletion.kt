package app.geunal.api.auth

import app.geunal.api.backups.BackupRepository
import app.geunal.api.common.ApiException
import app.geunal.api.common.afterCommit
import app.geunal.api.devices.DeviceRepository
import app.geunal.api.groups.GroupRepository
import app.geunal.api.realtime.MembershipEvent
import app.geunal.api.realtime.RevokeEvent
import app.geunal.api.realtime.Signals
import org.springframework.http.HttpStatus
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

/** `DELETE /me`: the proof that fits the account's kind (protocol §13 "계정 삭제"); the others stay empty. */
data class DeleteAccountRequest(
    val nonce: String? = null,
    val recoverySignature: ByteArray? = null,
    val kakaoIdToken: String? = null,
    val appleAuthorizationCode: String? = null,
)

@RestController
class AccountController(
    private val deletion: AccountDeletion,
) {
    @DeleteMapping("/me")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun delete(
        @AuthenticationPrincipal caller: Caller,
        @RequestBody(required = false) body: DeleteAccountRequest?,
    ) = deletion.delete(caller, body ?: DeleteAccountRequest())
}

/**
 * Deletes an account, the one call that cannot be undone, so the active device proves the account again: a guest (or
 * an account whose provider cut the link) with its recovery key, a Kakao account with a fresh ID token, an Apple
 * account with a fresh authorization code. A stolen session alone gets 403 `reauth_required` and changes nothing.
 */
@Service
class AccountDeletion(
    private val jdbc: JdbcClient,
    private val accounts: AccountRepository,
    private val devices: DeviceRepository,
    private val groups: GroupRepository,
    private val backups: BackupRepository,
    private val nonces: Nonces,
    private val proofs: ProviderProofs,
    private val revocations: ProviderRevocations,
    private val signals: Signals,
) {
    @Transactional
    fun delete(
        caller: Caller,
        request: DeleteAccountRequest,
    ) {
        accounts.lock(caller.userId)
        // Checked again under the account lock: a guest's recovery may just have replaced this device.
        val device = devices.current(caller.deviceId) ?: throw devices.revokedError(caller.deviceId)
        val links = accounts.links(caller.userId)
        val proof =
            if (links.isEmpty()) {
                val recovery =
                    backups.recovery(caller.userId)
                        ?: throw ApiException(HttpStatus.CONFLICT, "recovery_missing", "upload the recovery material first, then delete")
                val message = DELETE_LABEL + request.nonce.orEmpty().toByteArray(Charsets.US_ASCII)
                val signature = request.recoverySignature
                if (signature == null ||
                    !nonces.spend(request.nonce) ||
                    !Ed25519.verify(recovery.publicKey, message, signature)
                ) {
                    throw reauthRequired()
                }
                null
            } else {
                proofs.prove(links, device, request.kakaoIdToken, request.nonce, request.appleAuthorizationCode) ?: throw reauthRequired()
            }
        erase(caller.userId)
        val queued = revocations.queue(links[Provider.KAKAO], proof?.apple)
        afterCommit { revocations.attempt(queued) }
    }

    /**
     * One transaction: identifying data, devices with their sessions, key packages and Welcomes, recovery material,
     * backups, memberships, the account's own state blobs, uploaded media and alpha counts; objects are queued for
     * deletion. Log entries the account wrote stay until their partitions are dropped, so every remaining member keeps
     * the same group state, and the placeholder row keeps their `author_id` pointing somewhere. A group's shared
     * `group_meta` stays even when this account sealed it last.
     */
    private fun erase(userId: UUID) {
        val groupIds = groups.activeGroupIdsOf(userId)
        val deviceIds =
            jdbc
                .sql("SELECT id FROM devices WHERE user_id = :user AND revoked_at IS NULL")
                .param("user", userId)
                .query(UUID::class.java)
                .list()
                .filterNotNull()
        backups.deleteAll(userId)
        for (sql in ERASE) jdbc.sql(sql).param("user", userId).update()
        for (groupId in groupIds) {
            groups.succeedOwner(groupId)
            signals.send(groups.activeDeviceIds(groupId), MembershipEvent(groupId))
        }
        signals.send(deviceIds, RevokeEvent)
    }

    private fun reauthRequired() =
        ApiException(HttpStatus.FORBIDDEN, "reauth_required", "deleting the account needs a fresh proof for its kind")

    private companion object {
        val DELETE_LABEL = "geunal delete v1".toByteArray(Charsets.US_ASCII)

        /** In order; devices last but one, since their sessions, key packages and Welcomes go with them. */
        val ERASE =
            listOf(
                """
                INSERT INTO object_deletions (object_key, bucket)
                SELECT object_key, CASE scope WHEN 'chat' THEN 'media-chat' WHEN 'appt' THEN 'media-appt' WHEN 'personal' THEN 'media-personal'
                  ELSE 'group-assets' END
                FROM media_objects WHERE uploader_id = :user
                ON CONFLICT (object_key) DO NOTHING
                """,
                "DELETE FROM media_objects WHERE uploader_id = :user",
                "DELETE FROM recovery WHERE user_id = :user",
                "DELETE FROM group_state WHERE owner_id = :user AND kind <> 'group_meta'",
                "DELETE FROM group_members WHERE user_id = :user",
                "DELETE FROM blocks WHERE blocker_id = :user OR blocked_id = :user",
                "DELETE FROM subscriptions WHERE user_id = :user",
                "DELETE FROM alpha_stats WHERE user_id = :user",
                "DELETE FROM replace_tickets WHERE user_id = :user",
                "DELETE FROM provider_links WHERE user_id = :user",
                "DELETE FROM devices WHERE user_id = :user",
                "UPDATE users SET display_name = NULL, age_confirmed_at = NULL, deleted_at = now() WHERE id = :user",
            )
    }
}
