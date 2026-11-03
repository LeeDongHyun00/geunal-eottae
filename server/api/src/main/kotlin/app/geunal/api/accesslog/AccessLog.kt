package app.geunal.api.accesslog

import app.geunal.api.auth.Caller
import app.geunal.api.backups.ObjectStore
import org.slf4j.LoggerFactory
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import org.springframework.web.context.request.RequestContextHolder
import org.springframework.web.context.request.ServletRequestAttributes
import tools.jackson.databind.json.JsonMapper
import java.io.ByteArrayOutputStream
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.UUID
import java.util.zip.GZIPOutputStream

/** What an access log line records: a session starting or going on (api-server.md §10). */
enum class AccessEvent(
    val id: String,
) {
    SIGN_IN("sign_in"),
    REFRESH("refresh"),
}

/**
 * The access log the law asks of a communications service (통신비밀보호법 시행령 제41조, api-server.md §10): every
 * sign-in and token refresh with its time, account, device and the address it came from. A row goes into
 * `access_log_outbox` in the transaction that issues the tokens, so tokens never go out unlogged; [ship] then moves the
 * rows into the `access-logs` bucket, whose 92-day lifecycle rule ends them (infra.md §9). The address is kept here
 * and nowhere else in the API's logs.
 */
@Component
class AccessLog(
    private val jdbc: JdbcClient,
    private val store: ObjectStore,
    private val mapper: JsonMapper,
    transactions: PlatformTransactionManager,
) {
    private val transaction = TransactionTemplate(transactions)

    private data class Row(
        val id: Long,
        val at: OffsetDateTime,
        val event: String,
        val userId: UUID,
        val deviceId: UUID,
        val ip: String,
    )

    /**
     * Records [event] for [caller] with the client address of the current request. Behind Caddy that is the
     * `X-Forwarded-For` it sets, which Tomcat takes from a private-network peer only (`server.forward-headers-strategy`).
     */
    fun record(
        event: AccessEvent,
        caller: Caller,
    ) {
        val request = (RequestContextHolder.getRequestAttributes() as? ServletRequestAttributes)?.request
        val ip = checkNotNull(request?.remoteAddr) { "tokens are issued only while answering a request" }
        jdbc
            .sql("INSERT INTO access_log_outbox (event, user_id, device_id, ip) VALUES (:event, :user, :device, :ip)")
            .param("event", event.id)
            .param("user", caller.userId)
            .param("device", caller.deviceId)
            .param("ip", ip.take(MAX_ADDRESS))
            .update()
    }

    /**
     * Writes the waiting rows to the bucket as gzipped JSON lines, one object per [BATCH], and removes them. An object
     * is named by its rows' ids, so a batch retried after its delete failed overwrites the same object. A store that
     * fails leaves the rows for the next round; without storage (a local stack) they wait until the daily cleanup.
     */
    @Scheduled(fixedDelayString = "\${geunal.access-log.ship-every:PT5M}", initialDelayString = "\${geunal.access-log.ship-every:PT5M}")
    fun ship() {
        if (!store.configured) return
        val bucket = store.buckets.getValue(ObjectStore.ACCESS_LOGS)
        try {
            while (shipBatch(bucket) == BATCH) continue
        } catch (e: RuntimeException) {
            log.warn("could not write the access log to object storage: {}", e.javaClass.simpleName)
        }
    }

    /** Rows past the three months the law asks for that never reached the bucket. */
    fun expire(): Int = jdbc.sql("DELETE FROM access_log_outbox WHERE at < now() - interval '92 days'").update()

    private fun shipBatch(bucket: String): Int =
        transaction.execute {
            val rows =
                jdbc
                    .sql(
                        "SELECT id, at, event, user_id, device_id, ip FROM access_log_outbox ORDER BY id LIMIT :batch FOR UPDATE SKIP LOCKED",
                    ).param("batch", BATCH)
                    .query { rs, _ ->
                        Row(
                            rs.getLong("id"),
                            rs.getObject("at", OffsetDateTime::class.java),
                            rs.getString("event"),
                            rs.getObject("user_id", UUID::class.java),
                            rs.getObject("device_id", UUID::class.java),
                            rs.getString("ip"),
                        )
                    }.list()
            if (rows.isNotEmpty()) {
                val first = rows.first()
                val key = "${DAY.format(first.at.withOffsetSameInstant(ZoneOffset.UTC))}/${first.id}-${rows.last().id}.jsonl.gz"
                store.put(bucket, key, gzip(rows), "application/gzip")
                jdbc.sql("DELETE FROM access_log_outbox WHERE id IN (:ids)").param("ids", rows.map { it.id }).update()
            }
            rows.size
        }

    private fun gzip(rows: List<Row>): ByteArray {
        val bytes = ByteArrayOutputStream()
        GZIPOutputStream(bytes).bufferedWriter().use { out ->
            for (row in rows) {
                val line =
                    linkedMapOf(
                        "at" to row.at.withOffsetSameInstant(ZoneOffset.UTC).toString(),
                        "event" to row.event,
                        "user_id" to row.userId.toString(),
                        "device_id" to row.deviceId.toString(),
                        "ip" to row.ip,
                    )
                out.write(mapper.writeValueAsString(line))
                out.write("\n")
            }
        }
        return bytes.toByteArray()
    }

    private companion object {
        const val BATCH = 1000

        /** Longer than any IPv6 text form; whatever Tomcat hands over is stored as it is, only bounded. */
        const val MAX_ADDRESS = 64
        val DAY: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy/MM/dd")
        val log = LoggerFactory.getLogger(AccessLog::class.java)
    }
}
