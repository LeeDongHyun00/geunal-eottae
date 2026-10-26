package app.geunal.feature.account.testing

import app.geunal.core.AuthResult
import app.geunal.core.BackupView
import app.geunal.core.Core
import app.geunal.core.CoreException
import app.geunal.core.LocalData
import app.geunal.core.LocalDataChoice
import app.geunal.core.NewAccount
import app.geunal.core.NoHandle
import app.geunal.core.ProgressListener
import app.geunal.core.RestoreSummary
import app.geunal.core.Session
import app.geunal.core.SignInNonce
import app.geunal.feature.account.onboarding.SystemPermissions
import app.geunal.feature.account.providers.ProviderCredential
import app.geunal.feature.account.providers.ProviderSignIn
import app.geunal.feature.account.support.DeviceAuth
import app.geunal.feature.account.support.DeviceAuthPurpose
import java.time.Instant
import java.util.Collections

/** The words of the example code in docs/design/m1.md screen 21, in order. */
val CODE = listOf("바람", "연필", "구름", "달력", "열쇠", "우산", "단추", "마당", "편지", "소풍", "거울", "하늘")

/**
 * The account side of the core, without a Rust handle, the way UniFFI provides for fakes: it records the calls the
 * screens make and answers as each field says. Any method it does not override fails, so a screen that starts calling
 * something new shows up in its tests.
 */
open class AccountFakeCore : Core(NoHandle) {
    val calls: MutableList<String> = Collections.synchronizedList(mutableListOf())
    var lastAccount: NewAccount? = null
    var lastLocalData: LocalDataChoice? = null

    var nonce = SignInNonce(nonce = "n1", providerNonce = "p1")
    var authResult: Result<AuthResult> =
        Result.success(AuthResult.Registered(Session("u1", "d1"), newAccount = true, recovery = false))
    var guestResult: Result<Session> = Result.success(Session("u1", "d1"))
    var currentSession: Session? = null

    /** Two thousand and forty-eight in the core; these are enough to type the example and to hint "가이" → "가이드". */
    var words: List<String> = CODE + "가이드"
    var otherAccountData: LocalData? = null
    var restoreResult: Result<RestoreSummary> =
        Result.success(
            RestoreSummary(
                events = 3u,
                people = 2u,
                calendars = 1u,
                shiftTemplates = 0u,
                backupAt = Instant.parse("2026-10-20T09:20:00Z"),
                replacedDevice = true,
                groups = 0u,
            ),
        )
    var startFreshResult: Result<Session> = Result.success(Session("u1", "d2"))
    var backupsResult: Result<List<BackupView>> =
        Result.success(
            listOf(
                BackupView(3, Instant.parse("2026-10-20T09:20:00Z"), size = 10u, automatic = true, events = 3u, people = 2u),
                BackupView(2, Instant.parse("2026-10-13T09:20:00Z"), size = 10u, automatic = true, events = 3u, people = 2u),
            ),
        )
    var statsOn = false
    var alphaStatsError: CoreException? = null

    override suspend fun signInNonce(): SignInNonce {
        calls += "nonce"
        return nonce
    }

    override suspend fun signInKakao(
        nonce: String,
        idToken: String,
        account: NewAccount,
    ): AuthResult {
        calls += "kakao $nonce $idToken"
        lastAccount = account
        return authResult.getOrThrow()
    }

    override suspend fun signInApple(
        nonce: String,
        idToken: String,
        account: NewAccount,
    ): AuthResult {
        calls += "apple $nonce $idToken"
        lastAccount = account
        return authResult.getOrThrow()
    }

    override suspend fun signInGuest(account: NewAccount): Session {
        calls += "guest"
        lastAccount = account
        return guestResult.getOrThrow()
    }

    override fun session(): Session? = currentSession

    override fun checkRecoveryWord(text: String): String? = text.trim().takeIf { it in words }

    override fun recoveryCompletions(prefix: String): List<String> = words.filter { it.startsWith(prefix.trim()) }

    override fun splitRecoveryCode(text: String): List<String> =
        text.split(Regex("[\\s.,]+|\\d+")).filter { it.isNotBlank() }

    override fun localDataOfOtherAccount(): LocalData? = otherAccountData

    override suspend fun restoreFromCode(
        words: List<String>,
        localData: LocalDataChoice,
        progress: ProgressListener?,
    ): RestoreSummary {
        calls += "restore " + words.joinToString(" ")
        lastLocalData = localData
        progress?.onProgress(5u, 10u)
        return restoreResult.getOrThrow()
    }

    override suspend fun startFresh(): Session {
        calls += "startFresh"
        return startFreshResult.getOrThrow()
    }

    override suspend fun backups(): List<BackupView> {
        calls += "backups"
        return backupsResult.getOrThrow()
    }

    override fun alphaStatsEnabled(): Boolean = statsOn

    override fun setAlphaStats(enabled: Boolean) {
        alphaStatsError?.let { throw it }
        statsOn = enabled
    }
}

/** Kakao and Apple answering at once: the provider nonce they got is kept, and `result` is what they hand back. */
class FakeProviders : ProviderSignIn {
    val nonces = mutableListOf<String>()
    var result: Result<ProviderCredential> = Result.success(ProviderCredential(idToken = "token", displayName = "하린"))

    override suspend fun kakao(providerNonce: String): ProviderCredential {
        nonces += providerNonce
        return result.getOrThrow()
    }

    override suspend fun apple(providerNonce: String): ProviderCredential {
        nonces += providerNonce
        return result.getOrThrow()
    }
}

/** The OS permissions as fields; opening the settings is only counted. */
class FakePermissions(
    var calendar: Boolean = false,
    var calendars: Int = 3,
    var notificationsNeedAsking: Boolean = true,
    var notifications: Boolean = false,
) : SystemPermissions {
    var settingsOpened = 0

    override fun calendarGranted(): Boolean = calendar

    override fun deviceCalendarCount(): Int = calendars

    override fun notificationsNeedAsking(): Boolean = notificationsNeedAsking

    override fun notificationsEnabled(): Boolean = notifications

    override fun openAppSettings() {
        settingsOpened++
    }

    override fun openNotificationSettings() {
        settingsOpened++
    }
}

/** The OS owner check: `passes` decides the answer, and every prompt is counted. */
class FakeDeviceAuth(
    var secure: Boolean = true,
    var passes: Boolean = true,
) : DeviceAuth {
    val asked = mutableListOf<DeviceAuthPurpose>()

    override fun isDeviceSecure(): Boolean = secure

    override suspend fun confirm(purpose: DeviceAuthPurpose): Boolean {
        if (!secure) return true
        asked += purpose
        return passes
    }
}
