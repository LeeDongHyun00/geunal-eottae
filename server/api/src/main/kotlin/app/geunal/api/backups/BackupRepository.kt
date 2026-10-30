package app.geunal.api.backups

import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import java.sql.ResultSet
import java.time.Instant
import java.time.OffsetDateTime
import java.util.UUID

data class BackupRow(
    val id: Long,
    val objectKey: String,
    val size: Long,
    val completedAt: Instant?,
)

/** What `GET /me/recovery` returns and `PUT /me/recovery` checks proofs against (protocol §10). */
data class RecoveryRow(
    val recoveryId: ByteArray,
    val publicKey: ByteArray,
    val amkWrapped: ByteArray,
    val updatedAt: Instant,
)

/** Recovery material and the backup index; the sealed archives themselves are objects in the backups bucket. */
@Repository
class BackupRepository(
    private val jdbc: JdbcClient,
) {
    fun recovery(userId: UUID): RecoveryRow? =
        jdbc
            .sql("SELECT recovery_id, recovery_public_key, amk_wrapped, updated_at FROM recovery WHERE user_id = :user")
            .param("user", userId)
            .query { rs, _ ->
                RecoveryRow(
                    rs.getBytes("recovery_id"),
                    rs.getBytes("recovery_public_key"),
                    rs.getBytes("amk_wrapped"),
                    rs.instant("updated_at"),
                )
            }.optional()
            .orElse(null)

    /** The account that uses [recoveryId], if any: one code's material belongs to one account. */
    fun recoveryOwner(recoveryId: ByteArray): UUID? =
        jdbc
            .sql("SELECT user_id FROM recovery WHERE recovery_id = :id")
            .param("id", recoveryId)
            .query(UUID::class.java)
            .optional()
            .orElse(null)

    fun putRecovery(
        userId: UUID,
        recoveryId: ByteArray,
        publicKey: ByteArray,
        amkWrapped: ByteArray,
    ) {
        jdbc
            .sql(
                """
                INSERT INTO recovery (user_id, recovery_id, recovery_public_key, amk_wrapped) VALUES (:user, :id, :key, :amk)
                ON CONFLICT (user_id) DO UPDATE SET recovery_id = EXCLUDED.recovery_id,
                  recovery_public_key = EXCLUDED.recovery_public_key, amk_wrapped = EXCLUDED.amk_wrapped, updated_at = now()
                """,
            ).param("user", userId)
            .param("id", recoveryId)
            .param("key", publicKey)
            .param("amk", amkWrapped)
            .update()
    }

    fun start(
        userId: UUID,
        objectKey: String,
        size: Long,
    ): Long =
        jdbc
            .sql("INSERT INTO backups (user_id, object_key, size_bytes) VALUES (:user, :key, :size) RETURNING id")
            .param("user", userId)
            .param("key", objectKey)
            .param("size", size)
            .query(Long::class.java)
            .single()

    /** The account's backup row, locked so that one completion checks and marks it. */
    fun lockOf(
        userId: UUID,
        id: Long,
    ): BackupRow? =
        jdbc
            .sql("SELECT id, object_key, size_bytes, completed_at FROM backups WHERE id = :id AND user_id = :user FOR UPDATE")
            .param("id", id)
            .param("user", userId)
            .query(::row)
            .optional()
            .orElse(null)

    fun complete(id: Long): Instant =
        jdbc
            .sql("UPDATE backups SET completed_at = now() WHERE id = :id RETURNING completed_at")
            .param("id", id)
            .query { rs, _ -> rs.instant("completed_at") }
            .single()

    /** Completed backups, newest first. */
    fun completed(userId: UUID): List<BackupRow> =
        jdbc
            .sql(
                """
                SELECT id, object_key, size_bytes, completed_at FROM backups
                WHERE user_id = :user AND completed_at IS NOT NULL ORDER BY completed_at DESC, id DESC LIMIT :keep
                """,
            ).param("user", userId)
            .param("keep", KEEP)
            .query(::row)
            .list()

    /** Keeps the newest [KEEP] completed backups and queues the objects of the rest for deletion. */
    fun prune(userId: UUID) {
        jdbc
            .sql(
                """
                WITH old AS (
                  DELETE FROM backups WHERE user_id = :user AND completed_at IS NOT NULL AND id NOT IN (
                    SELECT id FROM backups WHERE user_id = :user AND completed_at IS NOT NULL ORDER BY completed_at DESC, id DESC LIMIT :keep
                  ) RETURNING object_key
                )
                INSERT INTO object_deletions (object_key, bucket) SELECT object_key, :bucket FROM old ON CONFLICT (object_key) DO NOTHING
                """,
            ).param("user", userId)
            .param("keep", KEEP)
            .param("bucket", ObjectStore.BACKUPS)
            .update()
    }

    /** Every backup of the account, finished or not, with its object queued for deletion: a new key or a deleted account. */
    fun deleteAll(userId: UUID) {
        jdbc
            .sql(
                """
                WITH gone AS (DELETE FROM backups WHERE user_id = :user RETURNING object_key)
                INSERT INTO object_deletions (object_key, bucket) SELECT object_key, :bucket FROM gone ON CONFLICT (object_key) DO NOTHING
                """,
            ).param("user", userId)
            .param("bucket", ObjectStore.BACKUPS)
            .update()
    }

    /** Uploads started more than a day ago and never completed (protocol §10). */
    fun dropAbandoned(): Int =
        jdbc
            .sql(
                """
                WITH gone AS (DELETE FROM backups WHERE completed_at IS NULL AND created_at < now() - interval '1 day' RETURNING object_key)
                INSERT INTO object_deletions (object_key, bucket) SELECT object_key, :bucket FROM gone ON CONFLICT (object_key) DO NOTHING
                """,
            ).param("bucket", ObjectStore.BACKUPS)
            .update()

    private fun row(
        rs: ResultSet,
        @Suppress("unused") index: Int,
    ) = BackupRow(
        id = rs.getLong("id"),
        objectKey = rs.getString("object_key"),
        size = rs.getLong("size_bytes"),
        completedAt = rs.getObject("completed_at", OffsetDateTime::class.java)?.toInstant(),
    )

    private fun ResultSet.instant(column: String): Instant = getObject(column, OffsetDateTime::class.java).toInstant()

    companion object {
        /** Completed backups kept per account (protocol §10). */
        const val KEEP = 3
    }
}
