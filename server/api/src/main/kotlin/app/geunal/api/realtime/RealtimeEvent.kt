package app.geunal.api.realtime

import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.annotation.JsonPropertyOrder
import java.time.Instant
import java.util.UUID

/** Server-to-device events (protocol §7). The gateway reads only `t`; the device reads the rest. */
@JsonPropertyOrder("t")
sealed interface RealtimeEvent {
    val t: String
}

/**
 * A new log entry. [at] is the server's `received_at`, the clock every time rule runs on (protocol §4), so a device can
 * process [c] without reading the log; [c] carries the ciphertext only when it is 4 KB or less.
 */
data class EntryEvent(
    val g: UUID,
    val s: Long,
    val kind: String,
    val at: Instant,
    @get:JsonInclude(JsonInclude.Include.NON_NULL) val c: ByteArray?,
) : RealtimeEvent {
    override val t: String get() = "entry"

    companion object {
        const val INLINE_LIMIT = 4096

        fun of(
            groupId: UUID,
            seq: Long,
            kind: String,
            receivedAt: Instant,
            ciphertext: ByteArray,
        ) = EntryEvent(groupId, seq, kind, receivedAt, ciphertext.takeIf { it.size <= INLINE_LIMIT })
    }
}

/** A state blob was overwritten; the device fetches it. */
data class StateEvent(
    val g: UUID,
    val kind: String,
    val owner: UUID,
    val epoch: Long,
) : RealtimeEvent {
    override val t: String get() = "state"
}

/** The member list of a group changed; the device fetches it and, if it is first, uploads the Remove commit. */
data class MembershipEvent(
    val g: UUID,
) : RealtimeEvent {
    override val t: String get() = "membership"
}

/** A Welcome waits in `GET /me/welcomes`. */
data object WelcomeEvent : RealtimeEvent {
    override val t: String get() = "welcome"
}

/** This device was replaced or its account deleted: close the connection and sign out. */
data object RevokeEvent : RealtimeEvent {
    override val t: String get() = "revoke"
}
