package app.geunal.feature.account.support

import java.io.File
import java.time.Instant
import java.time.format.DateTimeParseException

/**
 * What onboarding remembers between launches: that the person reached the calendar once, and the tick time of a guest
 * who started offline.
 *
 * It lives in files next to the core's store under `noBackupFilesDir`, not in SharedPreferences. Auto backup and
 * device-to-device transfer copy SharedPreferences to a new phone but never the store or the Keystore key, so a flag
 * there would send the new phone straight to the tabs with no account and no way to restore (docs/security/
 * app-review.md §1 "기기 이전"). Here the state and the store always travel, or stay, together.
 */
class OnboardingState(
    private val dir: File,
) {
    private val finishedFile get() = File(dir, "finished")
    private val pendingGuestFile get() = File(dir, "pending-guest-age-confirmed-at")

    /** The person reached the calendar once; later launches skip onboarding. */
    var isFinished: Boolean
        get() = finishedFile.exists()
        set(value) {
            if (value) write(finishedFile, "") else finishedFile.delete()
        }

    /**
     * A guest who started offline: when they ticked "만 14세 이상이에요", kept until the guest account can be made on the
     * first connection (m1.md screen 2, decision 16). Only the time is kept, never a birth date.
     */
    var pendingGuestAgeConfirmedAt: Instant?
        get() =
            pendingGuestFile.takeIf { it.exists() }?.readText()?.let {
                try {
                    Instant.parse(it.trim())
                } catch (e: DateTimeParseException) {
                    null
                }
            }
        set(value) {
            if (value == null) pendingGuestFile.delete() else write(pendingGuestFile, value.toString())
        }

    fun reset() {
        isFinished = false
        pendingGuestAgeConfirmedAt = null
    }

    private fun write(
        file: File,
        text: String,
    ) {
        dir.mkdirs()
        val partial = File(dir, file.name + ".partial")
        partial.writeText(text)
        if (!partial.renameTo(file)) {
            partial.delete()
            error("could not save ${file.name}")
        }
    }
}
