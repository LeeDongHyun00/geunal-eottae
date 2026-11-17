package app.geunal.api.ops

import app.geunal.api.accesslog.AccessLog
import app.geunal.api.backups.BackupRepository
import app.geunal.api.backups.ObjectStore
import org.slf4j.LoggerFactory
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.Duration

/**
 * The periodic deletions of api-server.md §8 that M1 needs: queued objects every ten minutes, and once a day the backup
 * uploads nobody completed, the sessions past their expiry, spent or not (a spent refresh token is kept only that long for reuse
 * detection; a replaced device keeps the tokens it can still hold), replacement tickets, a deleted account's refresh hashes, and access log rows that never reached
 * their bucket within the three months they are kept.
 */
@Component
class Cleanup(
    private val jdbc: JdbcClient,
    private val backups: BackupRepository,
    private val store: ObjectStore,
    private val accessLog: AccessLog,
) {
    private data class Queued(
        val key: String,
        val bucket: String,
    )

    /**
     * Empties `object_deletions` into the store. A row waits [UPLOAD_GRACE] after it was queued: an upload URL signed
     * just before the deletion lives 15 minutes, and a PUT it started could otherwise put the object back after the row
     * is gone. A row whose bucket this deployment does not have yet (the media buckets before M3) waits too; a store
     * that fails stops the round, and the next one tries again.
     */
    @Scheduled(fixedDelayString = "PT10M", initialDelayString = "PT2M")
    fun deleteObjects() {
        if (!store.configured) return
        val queued =
            jdbc
                .sql(
                    """
                    SELECT object_key, bucket FROM object_deletions WHERE enqueued_at < now() - CAST(:grace AS interval)
                    ORDER BY enqueued_at LIMIT 500
                    """,
                ).param("grace", "${UPLOAD_GRACE.toSeconds()} seconds")
                .query { rs, _ -> Queued(rs.getString("object_key"), rs.getString("bucket")) }
                .list()
        for (row in queued) {
            val bucket = store.buckets[row.bucket] ?: continue
            try {
                store.delete(bucket, row.key)
            } catch (e: RuntimeException) {
                log.warn("object storage refused a deletion in {}: {}", row.bucket, e.javaClass.simpleName)
                return
            }
            jdbc.sql("DELETE FROM object_deletions WHERE object_key = :key").param("key", row.key).update()
        }
    }

    @Scheduled(cron = "0 15 0 * * *", zone = "UTC")
    fun daily() {
        val abandoned = backups.dropAbandoned()
        val sessions =
            jdbc
                .sql(
                    "DELETE FROM sessions s USING devices d WHERE d.id = s.device_id AND d.revoked_at IS NULL AND s.expires_at < now()",
                ).update()
        // A replaced device keeps, for as long as the account lives, every refresh token it can still hold, so a phone
        // kept off any length of time still hears device_revoked. That is a token never spent, or the token an unspent
        // one was rotated from: a device whose refresh answer was lost holds the spent parent while the token minted
        // from it was never used. A spent token whose successor was spent too is one the device moved on from. The
        // rule follows rotated_from rather than counting the newest rows, which would drop a token the device holds
        // whenever it has more than one line of tokens (it signed in again with the same key).
        jdbc
            .sql(
                """
                DELETE FROM sessions s USING devices d WHERE d.id = s.device_id AND d.revoked_at IS NOT NULL
                  AND s.spent_at IS NOT NULL
                  AND NOT EXISTS (SELECT 1 FROM sessions next WHERE next.rotated_from = s.id AND next.spent_at IS NULL)
                """,
            ).update()
        val tickets = jdbc.sql("DELETE FROM replace_tickets WHERE expires_at < now()").update()
        jdbc.sql("DELETE FROM deleted_sessions WHERE expires_at < now()").update()
        // A Welcome nobody fetched in 30 days is past use: its Add commit has left the mailbox, and a device that comes
        // back later rejoins by an external commit (api-server.md §8).
        val welcomes = jdbc.sql("DELETE FROM welcomes WHERE created_at < now() - interval '30 days'").update()
        accessLog.expire()
        log.info(
            "dropped {} abandoned backup uploads, {} expired sessions, {} expired tickets and {} stale Welcomes",
            abandoned,
            sessions,
            tickets,
            welcomes,
        )
    }

    companion object {
        /** Longer than any upload URL (15 minutes) plus a slow 64 MiB PUT that started just before it expired. */
        val UPLOAD_GRACE: Duration = Duration.ofHours(1)
        private val log = LoggerFactory.getLogger(Cleanup::class.java)
    }
}
