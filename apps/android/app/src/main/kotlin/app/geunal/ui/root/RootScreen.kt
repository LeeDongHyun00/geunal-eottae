package app.geunal.ui.root

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.geunal.AppContainer
import app.geunal.AppPhase
import app.geunal.AppViewModel
import app.geunal.designsystem.LocalNeutralColors
import app.geunal.feature.account.onboarding.OnboardingDependencies
import app.geunal.feature.account.onboarding.OnboardingFlow
import app.geunal.ui.calendar.CalendarTab
import app.geunal.ui.me.MeScreen

/** Onboarding until the person reaches the calendar once, the tabs afterwards. */
@Composable
fun RootScreen(
    container: AppContainer,
    modifier: Modifier = Modifier,
) {
    val model =
        viewModel {
            AppViewModel(container::openCore, container.onboarding, container.changes.changes, container.connectivity.isOnline)
        }
    val phase by model.phase.collectAsStateWithLifecycle()
    LifecycleStartEffect(model) {
        model.appBecameActive()
        onStopOrDispose {}
    }
    val core = model.core
    val nextUp = model.nextUp
    when (val current = phase) {
        AppPhase.Starting -> Box(modifier.fillMaxSize().background(LocalNeutralColors.current.background))
        is AppPhase.Failed -> StartFailureScreen(reason = current.reason, onRetry = model::start, modifier = modifier)
        AppPhase.Onboarding ->
            if (core != null) {
                OnboardingFlow(
                    dependencies =
                        OnboardingDependencies(
                            core = core,
                            providers = container.providers,
                            permissions = container.permissions,
                            deviceAuth = container.deviceAuth,
                            isOnline = container.connectivity.isOnline,
                            toasts = model.toasts,
                            state = container.onboarding,
                            channel = container.channel,
                            legal = container.legal,
                        ),
                    onFinish = model::finishOnboarding,
                    modifier = modifier,
                )
            }
        AppPhase.Main ->
            if (core != null && nextUp != null) {
                MainScaffold(nextUp = nextUp, toasts = model.toasts, modifier = modifier) { tab, padding ->
                    when (tab) {
                        MainTab.CALENDAR -> CalendarTab(core, container.changes.changes, Modifier.padding(padding))
                        MainTab.ME ->
                            MeScreen(
                                core = core,
                                pendingGuest = container.onboarding.pendingGuestAgeConfirmedAt != null,
                                modifier = Modifier.padding(padding),
                            )
                    }
                }
            }
    }
}
