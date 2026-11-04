package app.geunal.api.push

import java.time.Instant
import java.time.format.DateTimeFormatter
import java.util.Base64
import java.util.UUID

/**
 * What a push carries (protocol §8): `{g, s, at, c?}`. The ciphertext rides along only while its base64 text fits in
 * [MAX_INLINE] characters, which keeps an APNs payload under its 4 KB limit; without it the device fetches the entry.
 */
data class PushPayload(
    val groupId: UUID,
    val seq: Long,
    val receivedAt: Instant,
    val ciphertext: ByteArray,
) {
    val inline: String? =
        Base64.getEncoder().encodeToString(ciphertext).takeIf { it.length <= MAX_INLINE }

    /** The fields both platforms carry, as strings since FCM data values are strings. */
    fun fields(): Map<String, String> =
        buildMap {
            put("g", groupId.toString())
            put("s", seq.toString())
            put("at", DateTimeFormatter.ISO_INSTANT.format(receivedAt))
            inline?.let { put("c", it) }
        }

    /**
     * An alert the iOS notification service extension rewrites after decrypting (`mutable-content`). The text is a
     * localization key the app defines, so the server holds no user-facing words.
     */
    fun apns(): Map<String, Any> =
        mapOf("aps" to mapOf("alert" to mapOf("loc-key" to APNS_PLACEHOLDER), "mutable-content" to 1, "sound" to "default")) + fields()

    /** A data-only, high-priority message the Android handler turns into a notification. */
    fun fcm(token: String): Map<String, Any> =
        mapOf("message" to mapOf("token" to token, "data" to fields(), "android" to mapOf("priority" to "high")))

    override fun equals(other: Any?): Boolean =
        other is PushPayload &&
            groupId == other.groupId &&
            seq == other.seq &&
            receivedAt == other.receivedAt &&
            ciphertext.contentEquals(other.ciphertext)

    override fun hashCode(): Int = 31 * groupId.hashCode() + seq.hashCode()

    companion object {
        const val MAX_INLINE = 3072
        const val APNS_PLACEHOLDER = "push.placeholder"
    }
}
