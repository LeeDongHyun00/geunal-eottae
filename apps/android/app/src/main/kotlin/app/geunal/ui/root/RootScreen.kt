package app.geunal.ui.root

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation3.runtime.NavEntry
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import androidx.navigation3.ui.NavDisplay
import app.geunal.AppContainer
import app.geunal.AppPhase
import app.geunal.AppViewModel
import app.geunal.core.CoreInterface
import app.geunal.core.UpNext
import app.geunal.designsystem.LocalNeutralColors
import app.geunal.designsystem.components.ToastCenter
import app.geunal.feature.account.onboarding.OnboardingDependencies
import app.geunal.feature.account.onboarding.OnboardingFlow
import app.geunal.feature.account.restore.KeepLocalSheet
import app.geunal.feature.account.signedout.RevokedSheet
import app.geunal.feature.calendar.alerts.DeviceNotificationAccess
import app.geunal.feature.calendar.editor.EditorTarget
import app.geunal.feature.calendar.people.DeviceContactsReader
import app.geunal.feature.calendar.screen.CalendarCover
import app.geunal.feature.calendar.screen.CalendarCoverContent
import app.geunal.feature.calendar.screen.CalendarCovers
import app.geunal.feature.calendar.support.KoreanCalendar
import app.geunal.ui.calendar.CalendarCoversViewModel
import app.geunal.ui.calendar.CalendarTab
import app.geunal.ui.me.MeCover
import app.geunal.ui.me.MeCoverContent
import app.geunal.ui.me.MeCovers
import app.geunal.ui.me.MeScreen
import java.time.Instant

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
                timing = container.timing,
            )
        }
    val phase by model.phase.collectAsStateWithLifecycle()
    val resources = LocalResources.current
    LaunchedEffect(model) {
        // An invite kept for this account must not greet the next one on this phone. A session that only ended keeps
        // it: the same person signs in again and still means to join.
        model.accountEnded.collect { container.invites.clear() }
    }
    LaunchedEffect(model, resources) {
        model.notices.collect { model.toasts.show(resources.getString(it.text)) }
    }
    LifecycleStartEffect(model) {
        model.appBecameActive()
        onStopOrDispose {}
    }
    val core = model.core
    val nextUp = model.nextUp
    when (val current = phase) {
        AppPhase.Starting -> Box(modifier.fillMaxSize().background(LocalNeutralColors.current.background))
        is AppPhase.Failed ->
            StartFailureScreen(reason = current.reason, retrying = current.retrying, onRetry = model::start, modifier = modifier)
        AppPhase.Onboarding ->
            if (core != null) {
                OnboardingFlow(
                    dependencies = onboardingDependencies(container, core, model),
                    onFinish = model::finishOnboarding,
                    modifier = modifier,
                    round = model.onboardingRound,
                )
            }
        AppPhase.Main ->
            if (core != null && nextUp != null) {
                val signingIn by model.signingIn.collectAsStateWithLifecycle()
                val revocation by model.revocation.collectAsStateWithLifecycle()
                val signedOut by model.signedOut.collectAsStateWithLifecycle()
                model.localData?.let { question ->
                    val asking by question.asking.collectAsStateWithLifecycle()
                    val answering by question.isWorking.collectAsStateWithLifecycle()
                    asking?.let { KeepLocalSheet(it, answering, model::answerLocalData) }
                }
                revocation?.let {
                    RevokedSheet(
                        revocation = it,
                        onSignIn = {
                            model.revocationShown()
                            model.signInAgain()
                        },
                        onCalendarOnly = model::revocationShown,
                    )
                }
                val context = LocalContext.current.applicationContext
                val coverModel =
                    viewModel {
                        CalendarCoversViewModel(
                            core,
                            DeviceContactsReader(context),
                            DeviceNotificationAccess(context),
                            container.deviceAuth,
                            container.providers,
                            model.accountEnded,
                            model::accountDeleted,
                        )
                    }
                val covers = coverModel.covers
                val me = coverModel.me
                val online by container.connectivity.isOnline.collectAsStateWithLifecycle()
                Box(modifier.fillMaxSize()) {
                    CoveredTabs(
                        covers = covers,
                        me = me,
                        toasts = model.toasts,
                        online = online,
                        // Under the sign-in the tabs stay composed, so closing it comes back to the same tab and screen;
                        // a screen reader meanwhile hears only the sign-in.
                        modifier = if (signingIn != null) Modifier.clearAndSetSemantics {} else Modifier,
                    ) {
                        MainScaffold(
                            nextUp = nextUp,
                            toasts = model.toasts,
                            // An event opens in the editor, a birthday on 사람·생일 with that person (m1.md §2.6).
                            onOpenItem = { item ->
                                when (item) {
                                    is UpNext.Event -> covers.edit(EditorTarget.Existing(item.item.eventId, item.item.occurrence))
                                    is UpNext.Birthday -> covers.people(focus = item.item.personId)
                                }
                            },
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
                                        signedOut = signedOut,
                                        onSignIn = model::signInAgain,
                                        onReturnAsGuest = model::returnAsGuest,
                                        onShifts = { covers.stamp(KoreanCalendar.month(KoreanCalendar.day(Instant.now()))) },
                                        onPeople = { covers.people() },
                                        onCalendars = covers::calendars,
                                        onAlerts = covers::alerts,
                                        onRecoveryCode = me::recoveryCode,
                                        onBackup = me::backup,
                                        onDeleteAccount = me::deleteAccount,
                                        modifier = Modifier.padding(padding),
                                    )
                            }
                        }
                    }
                    signingIn?.let { start ->
                        // Over the tabs, with "닫기": the tabs come back when it closes or the account comes through.
                        OnboardingFlow(
                            dependencies = onboardingDependencies(container, core, model),
                            onFinish = model::signedInAgain,
                            // Takes every touch, so none reaches the tabs drawn under it.
                            modifier = Modifier.fillMaxSize().pointerInput(Unit) { detectTapGestures {} },
                            round = model.signInRound,
                            start = start,
                            onClose = model::signInClosed,
                        )
                    }
                }
            }
    }
}

private fun onboardingDependencies(
    container: AppContainer,
    core: CoreInterface,
    model: AppViewModel,
) = OnboardingDependencies(
    core = core,
    providers = container.providers,
    permissions = container.permissions,
    deviceAuth = container.deviceAuth,
    isOnline = container.connectivity.isOnline,
    toasts = model.toasts,
    state = container.onboarding,
    channel = container.channel,
    legal = container.legal,
    timing = container.timing,
)

/** The tabs' place in the back stack, under the covers. */
private data object TabsRoute

/**
 * The tabs, with the covers over them full-screen like iOS's (m1.md screens 9 and 10): the calendar's, then 나's
 * account screens. Each kind opens only from the tabs, so one never lies over the other. Back takes the top cover away;
 * with none, it leaves the app as before.
 */
@Composable
private fun CoveredTabs(
    covers: CalendarCovers,
    me: MeCovers,
    toasts: ToastCenter,
    online: Boolean,
    modifier: Modifier = Modifier,
    tabs: @Composable () -> Unit,
) {
    NavDisplay(
        backStack = listOf<Any>(TabsRoute) + covers.stack + me.stack,
        onBack = { if (!me.back()) covers.back() },
        modifier = modifier,
        entryDecorators = listOf(rememberSaveableStateHolderNavEntryDecorator()),
        entryProvider = { route ->
            if (route is CalendarCover) {
                // 나's screens (사람·생일, 캘린더 관리, 알림) are pushed sideways; the editor and the stamp screen rise like
                // iOS's covers.
                val transitions = if (route.pushed) pushTransitions else coverTransitions
                NavEntry(route, metadata = transitions) { CalendarCoverContent(route, covers, toasts) }
            } else if (route is MeCover) {
                NavEntry(route, metadata = pushTransitions) {
                    MeCoverContent(route, me, toasts, online)
                }
            } else {
                NavEntry(route) { tabs() }
            }
        },
    )
}

/** A pushed screen comes in from the end and leaves the same way. */
private val pushTransitions: Map<String, Any> =
    NavDisplay.transitionSpec {
        slideInHorizontally(initialOffsetX = { it }) togetherWith ExitTransition.KeepUntilTransitionsFinished
    } +
        NavDisplay.popTransitionSpec {
            EnterTransition.None togetherWith slideOutHorizontally(targetOffsetX = { it })
        } +
        NavDisplay.predictivePopTransitionSpec {
            EnterTransition.None togetherWith slideOutHorizontally(targetOffsetX = { it })
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
