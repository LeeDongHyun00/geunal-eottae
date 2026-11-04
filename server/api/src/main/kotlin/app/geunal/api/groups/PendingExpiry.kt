package app.geunal.api.groups

import app.geunal.api.realtime.MembershipEvent
import app.geunal.api.realtime.Signals
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.time.Duration

/**
 * Lets an invitation lapse after seven days (protocol §15). Hourly rather than with the daily cleanup, so a seat comes
 * back within an hour of the seventh day instead of up to a day later.
 */
@Component
class PendingExpiry(
    private val groups: GroupRepository,
    private val signals: Signals,
) {
    @Scheduled(fixedDelayString = "PT1H", initialDelayString = "PT5M")
    @Transactional
    fun run() {
        val expired = groups.expirePending(LIFETIME)
        expired.groupBy({ it.first }, { it.second }).forEach { (groupId, users) ->
            val recipients = groups.activeDeviceIds(groupId) + users.mapNotNull(groups::deviceOf)
            signals.send(recipients.distinct(), MembershipEvent(groupId))
        }
        if (expired.isNotEmpty()) log.info("let {} pending invitations lapse", expired.size)
    }

    companion object {
        val LIFETIME: Duration = Duration.ofDays(7)
        private val log = LoggerFactory.getLogger(PendingExpiry::class.java)
    }
}
