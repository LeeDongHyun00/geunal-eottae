package app.geunal.feature.account.security

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.os.PersistableBundle

/**
 * The recovery code on the clipboard (m1.md screen 13, docs/security/app-review.md §2 "클립보드"). The clip is marked
 * sensitive, so Android 13's preview and keyboards hide it, and it is taken off after a minute or when the screen
 * goes away, whichever comes first: an app may clear the clipboard only while it is in front, hence both.
 */
class SensitiveClipboard(
    context: Context,
) {
    private val manager = context.getSystemService(ClipboardManager::class.java)

    fun copy(text: String) {
        val clip = ClipData.newPlainText(LABEL, text)
        // The key of ClipDescription.EXTRA_IS_SENSITIVE, which Android 13 names; older versions keep it unread.
        clip.description.extras = PersistableBundle().apply { putBoolean(EXTRA_IS_SENSITIVE, true) }
        manager?.setPrimaryClip(clip)
    }

    /** Takes the code off if it is still what the clipboard holds; something copied since stays. */
    fun clear() {
        val manager = manager ?: return
        if (manager.primaryClipDescription?.label != LABEL) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            manager.clearPrimaryClip()
        } else {
            manager.setPrimaryClip(ClipData.newPlainText("", ""))
        }
    }

    companion object {
        /** A label that says nothing of what the clip is, since some keyboards show it. */
        private const val LABEL = "geunal"
        private const val EXTRA_IS_SENSITIVE = "android.content.extra.IS_SENSITIVE"

        /** How long the code may stay on the clipboard. */
        const val CLEAR_AFTER_MS = 60_000L
    }
}
