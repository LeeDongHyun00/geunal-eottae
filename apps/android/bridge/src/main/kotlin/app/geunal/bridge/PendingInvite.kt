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
    private val codeFile = File(dir, "code")
    private val referrerFile = File(dir, "referrer-read")
    private val state = MutableStateFlow(read())

    /** The code waiting to be used, or null. */
    val code: StateFlow<String?> = state.asStateFlow()

    /** Whether the Install Referrer was already read; it describes the install, so it is read once. */
    val referrerRead: Boolean get() = referrerFile.exists()

    /** Keeps [code]; a newer invite replaces an older one, since the newest link is the one the person just tapped. */
    @Synchronized
    fun offer(code: String) {
        dir.mkdirs()
        codeFile.writeText(code)
        state.value = code
    }

    /** Forgets the code once it was used or declined, or when the account ends. */
    @Synchronized
    fun clear() {
        codeFile.delete()
        state.value = null
    }

    fun markReferrerRead() {
        dir.mkdirs()
        referrerFile.createNewFile()
    }

    private fun read(): String? = codeFile.takeIf { it.exists() }?.readText()?.takeIf { it.isNotEmpty() }
}
