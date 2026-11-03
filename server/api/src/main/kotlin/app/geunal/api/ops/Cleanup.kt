package app.geunal.api.ops

import app.geunal.api.backups.BackupRepository
import app.geunal.api.backups.ObjectStore
import org.slf4j.LoggerFactory
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.Duration

/**
 * The periodic deletions of api-server.md §8 that M1 needs: queued objects every ten minutes, and once a day the backup
 * uploads nobody completed, the sessions past their expiry, spent or not (a spent refresh token is kept only that long
 * for reuse detection), replacement tickets, and a deleted account's refresh hashes.
 */
@Component
class Cleanup(
    private val jdbc: JdbcClient,
    private val backups: BackupRepository,
    private val store: ObjectStore,
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
        val sessions = jdbc.sql("DELETE FROM sessions WHERE expires_at < now()").update()
        val tickets = jdbc.sql("DELETE FROM replace_tickets WHERE expires_at < now()").update()
        jdbc.sql("DELETE FROM deleted_sessions WHERE expires_at < now()").update()
        log.info("dropped {} abandoned backup uploads, {} expired sessions and {} expired tickets", abandoned, sessions, tickets)
    }

    companion object {
        /** Longer than any upload URL (15 minutes) plus a slow 64 MiB PUT that started just before it expired. */
        val UPLOAD_GRACE: Duration = Duration.ofHours(1)
        private val log = LoggerFactory.getLogger(Cleanup::class.java)
    }
}
