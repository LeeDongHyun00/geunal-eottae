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
    /** State blobs of any kind one user writes to one group: a re-seal after each epoch is a few at a time. */
    val statesPerMinute: Int = 60,
    /**
     * Of those, the shared group_meta slot, which every member may overwrite: at most one re-seal per commit, and a
     * group takes 30 commits a minute.
     */
    val groupMetaPerMinute: Int = 30,
)
