package app.geunal.feature.account.security

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.os.PersistableBundle

/**
 * The recovery code on the clipboard (m1.md screen 13, docs/security/app-review.md §2 "클립보드"). The clip is marked
 * sensitive, so Android 13's preview and keyboards hide it, and it is taken off after a minute or when the screen
 * goes away, whichever comes first. Clearing works from the background too: Android 10 and later keep only reading
 * from an app that is not in front (AOSP ClipboardService: "Writing is allowed without focus").
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

    /**
     * Takes the code off if it is still what the clipboard holds; something copied since stays. In the background the
     * clipboard cannot be read, and the code is taken off unchecked rather than left there past its minute.
     */
    fun clear() {
        val manager = manager ?: return
        val description = manager.primaryClipDescription
        if (!mayClear(readable = description != null, label = description?.label)) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            manager.clearPrimaryClip()
        } else {
            manager.setPrimaryClip(ClipData.newPlainText("", ""))
        }
    }

    /**
     * A code copied before the app's process ended stays on the clipboard: the minute's clearing died with it. On the
     * next start, once the app is in front and may read the clipboard, our own clip goes. Only when it can be read:
     * unlike [clear], a clipboard that cannot be read is left alone, since whatever it holds was not copied just now.
     */
    fun clearLeftover() {
        val manager = manager ?: return
        val description = manager.primaryClipDescription ?: return
        if (!isLeftover(description.label)) return
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

        /**
         * Whether [clear] goes ahead: always when the clipboard cannot be read (the app is in the background, or it
         * holds nothing), and otherwise only for our own clip, so something copied since is left alone.
         */
        internal fun mayClear(
            readable: Boolean,
            label: CharSequence?,
        ): Boolean = !readable || label == LABEL

        /** Only the code under our own label is a leftover; anything else on the clipboard is the person's. */
        internal fun isLeftover(label: CharSequence?): Boolean = label == LABEL
    }
}
