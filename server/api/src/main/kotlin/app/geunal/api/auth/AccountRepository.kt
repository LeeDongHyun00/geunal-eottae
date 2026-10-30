package app.geunal.api.auth

import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import java.time.Instant
import java.time.OffsetDateTime
import java.util.UUID

/** A provider account's link to one of ours; [active] is false while the provider says the user cut it. */
data class ProviderLink(
    val userId: UUID,
    val active: Boolean,
)

/** What `POST /auth/recover` checks a guest's recovery signature with. */
data class RecoveryKey(
    val userId: UUID,
    val publicKey: ByteArray,
)

/** A live replacement ticket: the account and the device key and platform its sign-in proved. */
data class TicketRow(
    val userId: UUID,
    val platform: String,
    val signatureKey: ByteArray,
)

/** Accounts, their provider links and replacement tickets (protocol §13). */
@Repository
class AccountRepository(
    private val jdbc: JdbcClient,
) {
    /**
     * Serializes sign-ins that name the same provider account until the transaction ends, so two first sign-ins
     * cannot both create an account for it.
     */
    fun lockIdentity(
        provider: Provider,
        sub: String,
    ) {
        jdbc
            .sql("SELECT pg_advisory_xact_lock(hashtextextended(:key, 0))")
            .param("key", "${provider.id}:$sub")
            .query()
            .listOfRows()
    }

    /** Serializes device registration for one account: at most one active device (api-server.md §6). */
    fun lock(userId: UUID) {
        jdbc
            .sql("SELECT id FROM users WHERE id = :id FOR UPDATE")
            .param("id", userId)
            .query(UUID::class.java)
            .optional()
    }

    fun create(
        displayName: String,
        ageConfirmedAt: OffsetDateTime,
    ): UUID =
        jdbc
            .sql("INSERT INTO users (display_name, age_confirmed_at) VALUES (:name, :age) RETURNING id")
            .param("name", displayName)
            .param("age", ageConfirmedAt)
            .query(UUID::class.java)
            .single()

    fun link(
        provider: Provider,
        sub: String,
    ): ProviderLink? =
        jdbc
            .sql(
                "SELECT user_id, unlinked_at IS NULL AS active FROM provider_links WHERE provider = :provider::provider_kind AND sub = :sub",
            ).param("provider", provider.id)
            .param("sub", sub)
            .query { rs, _ -> ProviderLink(rs.getObject("user_id", UUID::class.java), rs.getBoolean("active")) }
            .optional()
            .orElse(null)

    /** Clears a cut link: signing in with the same provider account comes back to the same account (protocol §13). */
    fun restore(
        provider: Provider,
        sub: String,
    ) {
        jdbc
            .sql("UPDATE provider_links SET unlinked_at = NULL WHERE provider = :provider::provider_kind AND sub = :sub")
            .param("provider", provider.id)
            .param("sub", sub)
            .update()
    }

    /** Links [userId] to the provider account; a cut link of the same provider that named another `sub` gives way. */
    fun addLink(
        userId: UUID,
        provider: Provider,
        sub: String,
    ) {
        jdbc
            .sql(
                """
                INSERT INTO provider_links (provider, sub, user_id) VALUES (:provider::provider_kind, :sub, :user)
                ON CONFLICT (user_id, provider) DO UPDATE SET sub = EXCLUDED.sub, linked_at = now(), unlinked_at = NULL
                """,
            ).param("provider", provider.id)
            .param("sub", sub)
            .param("user", userId)
            .update()
    }

    /** `kakao`, `apple`: the links the account signs in with now. A guest, a stub account or a cut link has none. */
    fun providersOf(userId: UUID): List<String> =
        jdbc
            .sql(
                """
                SELECT provider::text FROM provider_links
                WHERE user_id = :user AND unlinked_at IS NULL AND provider IN ('kakao', 'apple')
                ORDER BY provider
                """,
            ).param("user", userId)
            .query(String::class.java)
            .list()
            .filterNotNull()

    /** The provider accounts the account signs in with now, by provider: what a deletion or "새로 시작" proves again. */
    fun links(userId: UUID): Map<Provider, String> =
        jdbc
            .sql(
                "SELECT provider::text AS provider, sub FROM provider_links WHERE user_id = :user AND unlinked_at IS NULL AND provider IN ('kakao', 'apple')",
            ).param("user", userId)
            .query { rs, _ -> Provider.linkable(rs.getString("provider"))!! to rs.getString("sub") }
            .list()
            .toMap()

    /** The account was deleted: its row is a placeholder, and its tokens answer 401 `account_deleted`. */
    fun isDeleted(userId: UUID): Boolean =
        jdbc
            .sql("SELECT EXISTS (SELECT 1 FROM users WHERE id = :user AND deleted_at IS NOT NULL)")
            .param("user", userId)
            .query(Boolean::class.java)
            .single()

    /** A refresh token of a deleted account's session, before it would have expired. */
    fun isDeletedSession(refreshHash: ByteArray): Boolean =
        jdbc
            .sql("SELECT EXISTS (SELECT 1 FROM deleted_sessions WHERE refresh_hash = :hash AND expires_at > now())")
            .param("hash", refreshHash)
            .query(Boolean::class.java)
            .single()

    fun hasRecovery(userId: UUID): Boolean =
        jdbc
            .sql("SELECT EXISTS (SELECT 1 FROM recovery WHERE user_id = :user)")
            .param("user", userId)
            .query(Boolean::class.java)
            .single()

    fun recoveryKey(recoveryId: ByteArray): RecoveryKey? =
        jdbc
            .sql("SELECT user_id, recovery_public_key FROM recovery WHERE recovery_id = :id")
            .param("id", recoveryId)
            .query { rs, _ -> RecoveryKey(rs.getObject("user_id", UUID::class.java), rs.getBytes("recovery_public_key")) }
            .optional()
            .orElse(null)

    fun addTicket(
        hash: ByteArray,
        userId: UUID,
        platform: String,
        signatureKey: ByteArray,
        expiresAt: Instant,
    ) {
        jdbc
            .sql(
                """
                INSERT INTO replace_tickets (ticket_hash, user_id, platform, signature_key, expires_at)
                VALUES (:hash, :user, :platform, :key, :expires)
                """,
            ).param("hash", hash)
            .param("user", userId)
            .param("platform", platform)
            .param("key", signatureKey)
            .param("expires", OffsetDateTime.ofInstant(expiresAt, java.time.ZoneOffset.UTC))
            .update()
    }

    /** The ticket while it lives, locked so that one replacement spends it. */
    fun ticket(hash: ByteArray): TicketRow? =
        jdbc
            .sql("SELECT user_id, platform, signature_key FROM replace_tickets WHERE ticket_hash = :hash AND expires_at > now() FOR UPDATE")
            .param("hash", hash)
            .query { rs, _ -> TicketRow(rs.getObject("user_id", UUID::class.java), rs.getString("platform"), rs.getBytes("signature_key")) }
            .optional()
            .orElse(null)

    /** The account a live ticket stands for: the bearer of `GET /me/recovery` and the backup reads. */
    fun ticketHolder(hash: ByteArray): UUID? =
        jdbc
            .sql("SELECT user_id FROM replace_tickets WHERE ticket_hash = :hash AND expires_at > now()")
            .param("hash", hash)
            .query(UUID::class.java)
            .optional()
            .orElse(null)

    fun spendTicket(hash: ByteArray) {
        jdbc.sql("DELETE FROM replace_tickets WHERE ticket_hash = :hash").param("hash", hash).update()
    }
}
