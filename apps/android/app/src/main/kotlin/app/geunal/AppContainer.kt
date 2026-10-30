package app.geunal

import android.content.Context
import app.geunal.bridge.AppStorage
import app.geunal.bridge.ChangeListener
import app.geunal.bridge.ConnectivityMonitor
import app.geunal.bridge.KeystoreSecretStore
import app.geunal.core.Core
import app.geunal.core.CoreInterface
import app.geunal.feature.account.onboarding.DevicePermissions
import app.geunal.feature.account.onboarding.LegalLinks
import app.geunal.feature.account.onboarding.SystemPermissions
import app.geunal.feature.account.providers.AppleWebSignIn
import app.geunal.feature.account.providers.KakaoSignIn
import app.geunal.feature.account.providers.ProviderSignIn
import app.geunal.feature.account.providers.SystemProviderSignIn
import app.geunal.feature.account.support.BuildChannel
import app.geunal.feature.account.support.DeviceAuth
import app.geunal.feature.account.support.OnboardingState
import app.geunal.feature.account.support.SystemDeviceAuth

/**
 * The app-wide dependencies, wired by hand: with this few modules a DI framework has nothing to earn
 * (docs/parts/android.md §1). The provider keys, the domain and the links come from Gradle properties at build time and
 * are never in the repository (app/build.gradle.kts).
 */
class AppContainer(
    context: Context,
) {
    private val storage = AppStorage(context)
    private val secrets = KeystoreSecretStore(storage.secretsDir)

    val apiBase: String = BuildConfig.API_BASE
    val channel: BuildChannel = BuildChannel.of(BuildConfig.CHANNEL)
    val legal = LegalLinks(terms = BuildConfig.TERMS_URL, privacy = BuildConfig.PRIVACY_URL)

    /** The core keeps the listener it was opened with for its lifetime, so the whole app shares this one. */
    val changes = ChangeListener()
    val connectivity = ConnectivityMonitor(context)
    val onboarding = OnboardingState(storage.onboardingDir)
    val permissions: SystemPermissions = DevicePermissions(context)
    val deviceAuth: DeviceAuth = SystemDeviceAuth(context)
    val providers: ProviderSignIn =
        SystemProviderSignIn(
            KakaoSignIn(BuildConfig.KAKAO_APP_KEY),
            AppleWebSignIn(BuildConfig.APPLE_SERVICE_ID, apiBase, BuildConfig.APP_LINK_HOST),
        )

    /** Opens the core. Blocking (it opens the SQLCipher database), so callers move it off the main thread. */
    fun openCore(): CoreInterface = Core.open(storage.storeDir.path, apiBase, changes, secrets)
}
