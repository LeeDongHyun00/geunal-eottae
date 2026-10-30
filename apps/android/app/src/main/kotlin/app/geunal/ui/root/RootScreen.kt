package app.geunal.ui.root

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
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
import androidx.navigation3.runtime.NavEntry
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import androidx.navigation3.ui.NavDisplay
import app.geunal.AppContainer
import app.geunal.AppPhase
import app.geunal.AppViewModel
import app.geunal.designsystem.LocalNeutralColors
import app.geunal.feature.account.onboarding.OnboardingDependencies
import app.geunal.feature.account.onboarding.OnboardingFlow
import app.geunal.feature.calendar.editor.EditorTarget
import app.geunal.feature.calendar.screen.CalendarCover
import app.geunal.feature.calendar.screen.CalendarCoverContent
import app.geunal.feature.calendar.screen.CalendarCovers
import app.geunal.ui.calendar.CalendarCoversViewModel
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
            AppViewModel(
                openCore = container::openCore,
                onboarding = container.onboarding,
                changes = container.changes.changes,
                isOnline = container.connectivity.isOnline,
                screenReaderRunning = container.screenReaderRunning,
            )
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
                val covers = viewModel { CalendarCoversViewModel(core) }.covers
                CoveredTabs(covers = covers, modifier = modifier) {
                    MainScaffold(
                        nextUp = nextUp,
                        toasts = model.toasts,
                        onOpenEvent = { covers.edit(EditorTarget.Existing(it.eventId, it.occurrence)) },
                    ) { tab, padding ->
                        when (tab) {
                            MainTab.CALENDAR ->
                                CalendarTab(
                                    core,
                                    container.changes.changes,
                                    model.toasts,
                                    covers,
                                    Modifier.padding(padding),
                                )
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
}

/** The tabs' place in the back stack, under the covers. */
private data object TabsRoute

/**
 * The tabs, with the calendar's covers over them full-screen like iOS's (m1.md screens 9 and 10). Back takes the top
 * cover away; with none, it leaves the app as before.
 */
@Composable
private fun CoveredTabs(
    covers: CalendarCovers,
    modifier: Modifier = Modifier,
    tabs: @Composable () -> Unit,
) {
    NavDisplay(
        backStack = listOf<Any>(TabsRoute) + covers.stack,
        onBack = covers::back,
        modifier = modifier,
        entryDecorators = listOf(rememberSaveableStateHolderNavEntryDecorator()),
        entryProvider = { route ->
            if (route is CalendarCover) {
                NavEntry(route, metadata = coverTransitions) { CalendarCoverContent(route, covers) }
            } else {
                NavEntry(route) { tabs() }
            }
        },
    )
}

/** A cover rises from the bottom over the tabs and sinks back. */
private val coverTransitions: Map<String, Any> =
    NavDisplay.transitionSpec {
        slideInVertically(initialOffsetY = { it }) togetherWith ExitTransition.KeepUntilTransitionsFinished
    } +
        NavDisplay.popTransitionSpec {
            EnterTransition.None togetherWith slideOutVertically(targetOffsetY = { it })
        } +
        NavDisplay.predictivePopTransitionSpec {
            EnterTransition.None togetherWith slideOutVertically(targetOffsetY = { it })
        }
