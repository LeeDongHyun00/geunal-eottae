package app.geunal.feature.account.support

import app.geunal.core.Provider
import java.io.File
import java.time.Instant
import java.time.format.DateTimeParseException

/** Whose session it was: a guest comes back by recovery code, a Kakao or Apple account by signing in. */
enum class AccountKind { GUEST, PROVIDER }

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
    private val lastProviderSignInFile get() = File(dir, "last-provider-sign-in")
    private val revocationShownFile get() = File(dir, "revocation-shown-at")
    private val knownAccountFile get() = File(dir, "known-account")
    private val sessionEndedFile get() = File(dir, "session-ended")

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
        get() = readInstant(pendingGuestFile)
        set(value) {
            if (value == null) pendingGuestFile.delete() else write(pendingGuestFile, value.toString())
        }

    /**
     * This device's last Kakao or Apple sign-in: which provider and when. Starting fresh without the code needs the
     * provider's proof once 30 minutes have passed (m1.md screen 21). Only the provider and the time are kept.
     */
    var lastProviderSignIn: ProviderSignInRecord?
        get() =
            lastProviderSignInFile.takeIf { it.exists() }?.readText()?.trim()?.split(' ')?.takeIf { it.size == 2 }?.let { (name, at) ->
                val provider = Provider.entries.firstOrNull { it.name == name } ?: return@let null
                try {
                    ProviderSignInRecord(provider, Instant.parse(at))
                } catch (e: DateTimeParseException) {
                    null
                }
            }
        set(value) {
            if (value == null) lastProviderSignInFile.delete() else write(lastProviderSignInFile, "${value.provider.name} ${value.at}")
        }

    /**
     * The revocation whose sheet was already shown (m1.md screen 23): the core keeps reporting it until the next sign-in,
     * and the sheet comes once.
     */
    var revocationShownAt: Instant?
        get() = readInstant(revocationShownFile)
        set(value) {
            if (value == null) revocationShownFile.delete() else write(revocationShownFile, value.toString())
        }

    /**
     * The kind of account this device last had a session for, so a session the core ended can say which way back
     * there is (m1.md decision 43). Only the kind is kept, never an id or a name.
     */
    var knownAccount: AccountKind?
        get() =
            knownAccountFile.takeIf { it.exists() }?.readText()?.trim()?.let { text ->
                AccountKind.entries.firstOrNull { it.name == text }
            }
        set(value) {
            if (value == null) knownAccountFile.delete() else write(knownAccountFile, value.name)
        }

    /** The toast for an ended session was shown; it comes once, until a sign-in clears it (decision 43). */
    var sessionEnded: Boolean
        get() = sessionEndedFile.exists()
        set(value) {
            if (value) write(sessionEndedFile, "") else sessionEndedFile.delete()
        }

    fun reset() {
        isFinished = false
        pendingGuestAgeConfirmedAt = null
        lastProviderSignIn = null
    }

    /** The account is gone: nothing of it is waited for or announced any more. */
    fun forgetAccount() {
        isFinished = false
        knownAccount = null
        sessionEnded = false
        revocationShownAt = null
        // The ended account's last sign-in decides nothing for the next one.
        lastProviderSignIn = null
    }

    private fun readInstant(file: File): Instant? =
        file.takeIf { it.exists() }?.readText()?.let {
            try {
                Instant.parse(it.trim())
            } catch (e: DateTimeParseException) {
                null
            }
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

/** A Kakao or Apple sign-in on this device. */
data class ProviderSignInRecord(
    val provider: Provider,
    val at: Instant,
)
