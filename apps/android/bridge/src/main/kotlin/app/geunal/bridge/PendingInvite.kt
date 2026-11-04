package app.geunal.bridge

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

/**
 * The invite someone came in with and has not acted on yet. It outlives the process because the invite usually arrives
 * before sign-in and onboarding finish (or before install, through the referrer), and the group screens take it from
 * here once the account is ready. Kept under `noBackupFilesDir` (AppStorage): a code is a way into a group and has no
 * business on another phone.
 */
class PendingInvite(
    private val dir: File,
) {
    private val inviteFile = File(dir, "invite")
    private val referrerFile = File(dir, "referrer-read")
    private val state = MutableStateFlow(read())

    /**
     * The invite waiting to be used, or null: the whole link an App Link brought, or the bare code from the referrer.
     * Either goes to the core's `parse_invite` as it is.
     */
    val invite: StateFlow<String?> = state.asStateFlow()

    /** Whether the Install Referrer was already read; it describes the install, so it is read once. */
    val referrerRead: Boolean get() = referrerFile.exists()

    /** Keeps [invite]; a newer one replaces an older one, since the newest link is the one the person just tapped. */
    @Synchronized
    fun offer(invite: String) {
        dir.mkdirs()
        inviteFile.writeText(invite)
        state.value = invite
    }

    /** Forgets the invite once it was used or declined, or when the account ends. */
    @Synchronized
    fun clear() {
        inviteFile.delete()
        state.value = null
    }

    fun markReferrerRead() {
        dir.mkdirs()
        referrerFile.createNewFile()
    }

    private fun read(): String? = inviteFile.takeIf { it.exists() }?.readText()?.takeIf { it.isNotEmpty() }
}
