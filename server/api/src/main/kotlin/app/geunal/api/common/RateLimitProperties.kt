package app.geunal.api.common

import org.springframework.boot.context.properties.ConfigurationProperties

/** `geunal.rate-limits.*`: the limits of api-server.md §9 on what a signed-in device writes or takes. */
@ConfigurationProperties("geunal.rate-limits")
data class RateLimitProperties(
    /** Messages one user sends to one group. */
    val messagesPerMinute: Int = 60,
    /** Commits one group takes, from all its members together. */
    val commitsPerMinute: Int = 30,
    /** Key packages one user takes from others' devices. */
    val claimsPerMinute: Int = 30,
    /** Invite codes looked up or joined from one client address, each counted on its own. */
    val inviteUsesPerHour: Int = 10,
    /** Invite codes one user makes. */
    val invitesPerHour: Int = 20,
    /** Groups one user creates. */
    val groupsPerHour: Int = 10,
)
