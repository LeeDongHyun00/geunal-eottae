package app.geunal.api.realtime

import org.slf4j.LoggerFactory
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.stereotype.Component
import org.springframework.transaction.support.TransactionSynchronization
import org.springframework.transaction.support.TransactionSynchronizationManager
import tools.jackson.databind.json.JsonMapper
import java.util.UUID

/**
 * Tells the gateway what changed: one PUBLISH on `d:{device_id}` per device, with the protocol §7 event JSON plus
 * `t` for the event name. The database is the record and the signal is only a hurry-up, so a signal is sent after the
 * transaction commits and a Redis failure is logged, never raised.
 */
@Component
class Signals(
    private val redis: StringRedisTemplate,
    private val mapper: JsonMapper,
) {
    fun channel(deviceId: UUID): String = "d:$deviceId"

    fun encode(event: RealtimeEvent): String = mapper.writeValueAsString(event)

    /** Publishes once the surrounding transaction commits, or at once outside a transaction. */
    fun send(
        deviceIds: Collection<UUID>,
        event: RealtimeEvent,
    ) {
        if (deviceIds.isEmpty()) return
        val payload = encode(event)
        afterCommit { deviceIds.forEach { publish(channel(it), payload) } }
    }

    private fun publish(
        channel: String,
        payload: String,
    ) {
        try {
            redis.convertAndSend(channel, payload)
        } catch (e: RuntimeException) {
            log.warn("could not publish to {}: {}", channel, e.toString())
        }
    }

    private fun afterCommit(block: () -> Unit) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) return block()
        TransactionSynchronizationManager.registerSynchronization(
            object : TransactionSynchronization {
                override fun afterCommit() = block()
            },
        )
    }

    private companion object {
        val log = LoggerFactory.getLogger(Signals::class.java)
    }
}
