package app.geunal.api.push

import app.geunal.api.common.afterCommit
import jakarta.annotation.PreDestroy
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.util.UUID
import java.util.concurrent.Executors

/**
 * Sends the pushes of an application message (protocol §8) once its transaction commits, on virtual threads so the
 * request never waits on Apple or Google. A failure only costs the hurry-up: the device still syncs by seq. Nothing
 * of a token or payload reaches the log.
 */
@Component
class Push(
    private val repository: PushRepository,
    senders: List<PushSender>,
) {
    private val senders = senders.filter { it.enabled }.associateBy { it.platform }
    private val executor = Executors.newVirtualThreadPerTaskExecutor()

    init {
        log.info(
            "push senders on: {}",
            this.senders.keys
                .sorted()
                .ifEmpty { listOf("none") },
        )
    }

    /** [users] null is `alert: "all"`; [cat] is the message's `chat` or `plan`. Call inside the message's transaction. */
    fun message(
        groupId: UUID,
        senderId: UUID,
        cat: String,
        users: List<UUID>?,
        payload: PushPayload,
    ) {
        if (senders.isEmpty()) return
        val targets = repository.targets(groupId, senderId, cat, users).filter { it.platform in senders }
        if (targets.isEmpty()) return
        afterCommit { targets.forEach { target -> executor.submit { deliver(target, payload) } } }
    }

    private fun deliver(
        target: PushTarget,
        payload: PushPayload,
    ) {
        try {
            when (senders.getValue(target.platform).send(target, payload)) {
                Delivery.SENT -> {}
                Delivery.DEAD -> repository.forget(target)
                Delivery.FAILED -> log.warn("a {} push was not accepted", target.platform)
            }
        } catch (e: RuntimeException) {
            log.warn("a {} push failed: {}", target.platform, e.javaClass.simpleName)
        }
    }

    @PreDestroy
    fun close() = executor.close()

    private companion object {
        val log = LoggerFactory.getLogger(Push::class.java)
    }
}
