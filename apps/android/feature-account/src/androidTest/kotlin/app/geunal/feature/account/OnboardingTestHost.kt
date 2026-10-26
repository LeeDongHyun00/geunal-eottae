package app.geunal.feature.account

import androidx.compose.ui.test.junit4.ComposeContentTestRule
import app.geunal.designsystem.GeunalTheme
import app.geunal.designsystem.components.ToastCenter
import app.geunal.feature.account.onboarding.LegalLinks
import app.geunal.feature.account.onboarding.OnboardingDependencies
import app.geunal.feature.account.onboarding.OnboardingFlow
import app.geunal.feature.account.support.BuildChannel
import app.geunal.feature.account.support.OnboardingState
import app.geunal.feature.account.testing.AccountFakeCore
import app.geunal.feature.account.testing.FakeDeviceAuth
import app.geunal.feature.account.testing.FakePermissions
import app.geunal.feature.account.testing.FakeProviders
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.flow.MutableStateFlow
import java.io.File

/** Onboarding on the fakes, with nothing of the real core, providers or OS prompts. */
class OnboardingTestHost(
    dir: File,
) {
    val core = AccountFakeCore()
    val providers = FakeProviders()
    val permissions = FakePermissions()
    val deviceAuth = FakeDeviceAuth()
    val onboarding = OnboardingState(dir)
    var finished = 0
        private set

    fun show(rule: ComposeContentTestRule) {
        val dependencies =
            OnboardingDependencies(
                core = core,
                providers = providers,
                permissions = permissions,
                deviceAuth = deviceAuth,
                isOnline = MutableStateFlow(true),
                toasts = ToastCenter(MainScope()),
                state = onboarding,
                channel = BuildChannel.ALPHA,
                legal = LegalLinks(terms = "https://example.invalid/terms", privacy = "https://example.invalid/privacy"),
            )
        rule.setContent {
            GeunalTheme {
                OnboardingFlow(dependencies = dependencies, onFinish = { finished++ })
            }
        }
    }
}
