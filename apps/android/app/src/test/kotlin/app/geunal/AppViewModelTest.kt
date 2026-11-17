package app.geunal

import app.geunal.core.AccountView
import app.geunal.core.AlertRule
import app.geunal.core.AlertTarget
import app.geunal.core.CalendarKind
import app.geunal.core.CalendarView
import app.geunal.core.Change
import app.geunal.core.Core
import app.geunal.core.CoreException
import app.geunal.core.CoreInterface
import app.geunal.core.DayTime
import app.geunal.core.DeviceCalendarInput
import app.geunal.core.DeviceEventInput
import app.geunal.core.DeviceMirror
import app.geunal.core.InternalException
import app.geunal.core.LocalData
import app.geunal.core.NewAccount
import app.geunal.core.NoHandle
import app.geunal.core.Provider
import app.geunal.core.Revocation
import app.geunal.core.RevokedVia
import app.geunal.core.ScheduledAlert
import app.geunal.core.Session
import app.geunal.core.UpNext
import app.geunal.feature.account.onboarding.OnboardingStart
import app.geunal.feature.account.support.AccountKind
import app.geunal.feature.account.support.OnboardingState
import app.geunal.feature.calendar.alerts.AlertCenter
import app.geunal.feature.calendar.alerts.AlertRoute
import app.geunal.feature.calendar.alerts.AlertWords
import app.geunal.feature.calendar.alerts.PlannedAlert
import app.geunal.feature.calendar.calendars.DeviceCalendarSource
import app.geunal.feature.calendar.calendars.DeviceCalendarSummary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.time.Instant

@OptIn(ExperimentalCoroutinesApi::class)
class AppViewModelTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val dispatcher = StandardTestDispatcher()
    private val changes = MutableSharedFlow<Change>(extraBufferCapacity = 4)
    private val online = MutableStateFlow(true)
    private val onboarding by lazy { OnboardingState(folder.newFolder("onboarding")) }

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun viewModel(open: () -> CoreInterface) = AppViewModel(open, onboarding, changes, online, io = dispatcher)

    @Test
    fun startsWithOnboardingOnAFreshInstall() =
        runTest(dispatcher) {
            val viewModel = viewModel { FakeCore() }
            advanceUntilIdle()

            assertEquals(AppPhase.Onboarding, viewModel.phase.value)
        }

    @Test
    fun goesStraightToTheTabsOnceOnboardingFinished() =
        runTest(dispatcher) {
            onboarding.isFinished = true

            val viewModel = viewModel { FakeCore() }
            advanceUntilIdle()

            assertEquals(AppPhase.Main, viewModel.phase.value)
        }

    @Test
    fun namesWhyTheCoreDidNotOpenByKind() =
        runTest(dispatcher) {
            val store = viewModel { throw CoreException.Store("file is not a database") }
            val secrets = viewModel { throw CoreException.SecretStore("unreadable") }
            val panic = viewModel { throw InternalException("boom") }
            advanceUntilIdle()

            assertEquals(AppPhase.Failed(StartFailure.STORE), store.phase.value)
            assertEquals(AppPhase.Failed(StartFailure.SECRETS), secrets.phase.value)
            assertEquals(AppPhase.Failed(StartFailure.OTHER), panic.phase.value)
        }

    @Test
    fun opensTheCoreAgainOnRetry() =
        runTest(dispatcher) {
            var attempts = 0
            val viewModel =
                viewModel {
                    attempts++
                    if (attempts == 1) throw CoreException.Store("locked") else FakeCore()
                }
            advanceUntilIdle()

            // The failure screen stays while it tries again, and a second tap does not open the store twice.
            viewModel.start()
            assertEquals(AppPhase.Failed(StartFailure.STORE, retrying = true), viewModel.phase.value)
            viewModel.start()
            advanceUntilIdle()

            assertEquals(2, attempts)
            assertEquals(AppPhase.Onboarding, viewModel.phase.value)
        }

    @Test
    fun aRetryThatFailsAgainOffersAnotherTry() =
        runTest(dispatcher) {
            var attempts = 0
            val viewModel =
                viewModel {
                    attempts++
                    throw if (attempts == 1) CoreException.Store("locked") else CoreException.SecretStore("unreadable")
                }
            advanceUntilIdle()

            viewModel.start()
            advanceUntilIdle()
            assertEquals(AppPhase.Failed(StartFailure.SECRETS), viewModel.phase.value)

            viewModel.start()
            advanceUntilIdle()
            assertEquals(3, attempts)
        }

    @Test
    fun countsAForegroundThatCameBeforeTheCoreOnce() =
        runTest(dispatcher) {
            val core = FakeCore()
            val viewModel = viewModel { core }

            viewModel.appBecameActive()
            advanceUntilIdle()

            assertEquals(1, core.foregrounds)
            viewModel.appBecameActive()
            advanceUntilIdle()
            assertEquals(2, core.foregrounds)
        }

    @Test
    fun syncsEachTimeTheAppComesToTheFrontOnlineAndShrugsOffAFailure() =
        runTest(dispatcher) {
            val core = FakeCore()
            val viewModel = viewModel { core }
            advanceUntilIdle()

            viewModel.appBecameActive()
            advanceUntilIdle()
            assertEquals(1, core.syncs)

            core.syncError = CoreException.Network("offline")
            val reads = core.nextUpReads
            viewModel.appBecameActive()
            advanceUntilIdle()

            assertEquals(2, core.syncs)
            // The next-up bar still comes fresh after a sync that failed.
            assertEquals(reads + 1, core.nextUpReads)

            online.value = false
            viewModel.appBecameActive()
            advanceUntilIdle()
            assertEquals(2, core.syncs)
        }

    @Test
    fun aSyncThatFindsTheAccountDeletedStartsOverAndSaysSo() =
        runTest(dispatcher) {
            onboarding.isFinished = true
            val core = FakeCore()
            val viewModel = viewModel { core }
            advanceUntilIdle()
            assertEquals(AppPhase.Main, viewModel.phase.value)
            val round = viewModel.onboardingRound

            core.syncError = CoreException.Api(401u, "account_deleted", "")
            viewModel.appBecameActive()
            advanceUntilIdle()

            assertEquals(AppPhase.Onboarding, viewModel.phase.value)
            assertFalse(onboarding.isFinished)
            assertEquals("onboarding starts from the welcome screen", round + 1, viewModel.onboardingRound)
            assertEquals(AppNotice.ACCOUNT_DELETED, viewModel.notices.first())
        }

    @Test
    fun tellsWhatHoldsTheAccountsScreensWhenItEnds() =
        runTest(dispatcher) {
            onboarding.isFinished = true
            val viewModel = viewModel { FakeCore() }
            advanceUntilIdle()
            var ended = 0
            val watcher = launch { viewModel.accountEnded.collect { ended++ } }
            advanceUntilIdle()

            viewModel.accountDeleted()
            advanceUntilIdle()

            assertEquals(1, ended)
            watcher.cancel()
        }

    @Test
    fun anotherRefusalKeepsTheTabs() =
        runTest(dispatcher) {
            onboarding.isFinished = true
            val core = FakeCore()
            val viewModel = viewModel { core }
            advanceUntilIdle()

            core.syncError = CoreException.Api(401u, "token_expired", "")
            viewModel.appBecameActive()
            advanceUntilIdle()

            assertEquals(AppPhase.Main, viewModel.phase.value)
            assertTrue(onboarding.isFinished)
        }

    @Test
    fun anotherDevicesSignInShowsTheSheetOnceAndSignsOut() =
        runTest(dispatcher) {
            onboarding.isFinished = true
            val core = FakeCore()
            core.revoked = Revocation(Instant.parse("2026-10-25T09:20:00Z"), "ios", RevokedVia.PROVIDER)
            val viewModel = viewModel { core }
            var ended = 0
            val watcher = launch { viewModel.accountEnded.collect { ended++ } }
            advanceUntilIdle()

            assertEquals(core.revoked, viewModel.revocation.value)
            assertEquals(SignedOut.Revoked, viewModel.signedOut.value)
            assertEquals("the recovery code's screens close", 1, ended)

            viewModel.revocationShown()
            viewModel.refreshAccount()

            assertNull("the sheet comes once", viewModel.revocation.value)
            assertEquals(SignedOut.Revoked, viewModel.signedOut.value)
            watcher.cancel()
        }

    @Test
    fun anEndedSessionSaysSoOnceAndAgainWhenSignedIn() =
        runTest(dispatcher) {
            onboarding.isFinished = true
            val core = FakeCore()
            core.currentSession = Session("u1", "d1")
            core.currentAccount = AccountView("u1", "하린", listOf(Provider.KAKAO))
            val viewModel = viewModel { core }
            advanceUntilIdle()
            assertNull(viewModel.signedOut.value)

            core.currentSession = null
            changes.emit(Change.Account)
            advanceUntilIdle()
            viewModel.refreshAccount()

            assertEquals(SignedOut.SessionEnded(AccountKind.PROVIDER), viewModel.signedOut.value)
            assertEquals(AppNotice.SESSION_ENDED, viewModel.notices.first())

            core.currentSession = Session("u1", "d2")
            viewModel.refreshAccount()

            assertNull(viewModel.signedOut.value)
            assertEquals("no second session-ended toast came between", AppNotice.SIGNED_IN_AGAIN, viewModel.notices.first())
        }

    @Test
    fun aGuestsEndedSessionLeadsBackByCode() =
        runTest(dispatcher) {
            onboarding.isFinished = true
            onboarding.knownAccount = AccountKind.GUEST
            val viewModel = viewModel { FakeCore() }
            advanceUntilIdle()

            assertEquals(SignedOut.SessionEnded(AccountKind.GUEST), viewModel.signedOut.value)

            viewModel.returnAsGuest()
            assertEquals(OnboardingStart.GUEST_RETURN, viewModel.signingIn.value)
            viewModel.signInClosed()
            assertNull(viewModel.signingIn.value)
        }

    @Test
    fun asksAboutAnotherAccountsEventsOnlyWhileTheSignInIsNotUp() =
        runTest(dispatcher) {
            onboarding.isFinished = true
            val core = FakeCore()
            core.currentSession = Session("u1", "d1")
            core.otherAccount = LocalData(3u, 1u)
            val viewModel = viewModel { core }
            advanceUntilIdle()

            viewModel.signInAgain()
            viewModel.appBecameActive()
            advanceUntilIdle()
            assertNull("the sign-in over the tabs asks its own", viewModel.localData?.asking?.value)

            viewModel.signInClosed()
            viewModel.appBecameActive()
            advanceUntilIdle()
            assertEquals(LocalData(3u, 1u), viewModel.localData?.asking?.value)

            // The session ends under the sheet: no answer could close it any more.
            core.currentSession = null
            viewModel.refreshAccount()
            assertNull(viewModel.localData?.asking?.value)
        }

    @Test
    fun aDeviceThatNeverSignedInHasNothingEnded() =
        runTest(dispatcher) {
            onboarding.isFinished = true
            val viewModel = viewModel { FakeCore() }
            advanceUntilIdle()

            assertNull(viewModel.signedOut.value)
            assertFalse(onboarding.sessionEnded)
        }

    @Test
    fun makesTheGuestAccountAnOfflineStartOwesOnceOnline() =
        runTest(dispatcher) {
            onboarding.pendingGuestAgeConfirmedAt = Instant.ofEpochSecond(1_793_000_000)
            online.value = false
            val core = FakeCore()
            val viewModel = viewModel { core }
            viewModel.appBecameActive()
            advanceUntilIdle()
            assertEquals(0, core.guests)

            online.value = true
            viewModel.appBecameActive()
            advanceUntilIdle()

            assertEquals(1, core.guests)
            assertNull(onboarding.pendingGuestAgeConfirmedAt)
        }

    @Test
    fun refreshesTheNextUpBarWhenTheCalendarChanges() =
        runTest(dispatcher) {
            val core = FakeCore()
            val viewModel = viewModel { core }
            advanceUntilIdle()
            val before = core.nextUpReads

            changes.emit(Change.Calendar)
            advanceUntilIdle()

            assertEquals(before + 1, core.nextUpReads)
            assertEquals(AppPhase.Onboarding, viewModel.phase.value)
        }

    // The reminders and the device calendar mirror (docs/parts/android.md §4).

    private fun withDevice(
        open: () -> CoreInterface,
        center: FakeAlertCenter = FakeAlertCenter(),
        calendars: FakeDeviceCalendars = FakeDeviceCalendars(),
        signInInterrupted: () -> Boolean = { false },
    ) = AppViewModel(
        open,
        onboarding,
        changes,
        online,
        io = dispatcher,
        alertCenter = center,
        alertTexts = { alert, _ -> AlertWords(alert.title, "body") },
        deviceCalendars = calendars,
        signInInterrupted = signInInterrupted,
    )

    @Test
    fun schedulesTheRemindersOnStartAndAgainWhenTheCalendarChanges() =
        runTest(dispatcher) {
            onboarding.isFinished = true
            val core = FakeCore().apply { alerts = listOf(alert("a1")) }
            val center = FakeAlertCenter()
            val viewModel = withDevice({ core }, center)
            viewModel.appBecameActive()
            advanceUntilIdle()
            assertEquals(listOf("a1"), center.pending.map { it.id })

            core.alerts = listOf(alert("a1"), alert("a2"))
            changes.emit(Change.Calendar)
            advanceUntilIdle()

            assertEquals(listOf("a1", "a2"), center.pending.map { it.id })
        }

    @Test
    fun schedulesTheRemindersOnceOnboardingAllowedThem() =
        runTest(dispatcher) {
            val core = FakeCore().apply { alerts = listOf(alert("a1")) }
            val center = FakeAlertCenter().apply { allowed = false }
            val viewModel = withDevice({ core }, center)
            viewModel.appBecameActive()
            advanceUntilIdle()
            assertTrue(center.pending.isEmpty())

            center.allowed = true
            viewModel.finishOnboarding()
            advanceUntilIdle()

            assertEquals(listOf("a1"), center.pending.map { it.id })
        }

    @Test
    fun takesEveryReminderAwayWhenTheAccountIsDeleted() =
        runTest(dispatcher) {
            onboarding.isFinished = true
            val core = FakeCore().apply { alerts = listOf(alert("a1")) }
            val center = FakeAlertCenter()
            val viewModel = withDevice({ core }, center)
            viewModel.appBecameActive()
            advanceUntilIdle()

            viewModel.accountDeleted()
            advanceUntilIdle()

            assertEquals(1, center.removals)
            assertTrue(center.pending.isEmpty())
        }

    @Test
    fun takesEveryReminderAwayWhenAnotherDeviceSignedThisOneOut() =
        runTest(dispatcher) {
            onboarding.isFinished = true
            val core = FakeCore().apply { alerts = listOf(alert("a1")) }
            val center = FakeAlertCenter()
            withDevice({ core }, center)
            advanceUntilIdle()

            core.revoked = Revocation(Instant.parse("2026-11-04T09:00:00Z"), "ios", RevokedVia.PROVIDER)
            changes.emit(Change.Account)
            advanceUntilIdle()
            // The same revocation read again takes nothing more away.
            changes.emit(Change.Account)
            advanceUntilIdle()

            assertEquals(1, center.removals)
        }

    @Test
    fun mirrorsThePhonesCalendarsAgainOnComingToTheFrontAndOnTheirChange() =
        runTest(dispatcher) {
            onboarding.isFinished = true
            val core = FakeCore()
            val calendars = FakeDeviceCalendars()
            val viewModel = withDevice({ core }, calendars = calendars)
            advanceUntilIdle()
            assertEquals(1, viewModel.mirror?.importAll())
            assertEquals(1, core.mirrors.size)

            calendars.events = 2
            viewModel.appBecameActive()
            advanceUntilIdle()
            assertEquals(2, core.mirrors.size)

            calendars.events = 3
            calendars.changed()
            advanceUntilIdle()
            assertEquals(3, core.mirrors.size)
        }

    @Test
    fun keepsATappedReminderUntilTheTabsTakeIt() =
        runTest(dispatcher) {
            val viewModel = viewModel { FakeCore() }

            viewModel.open(AlertRoute.Person("p1"))
            assertEquals(AlertRoute.Person("p1"), viewModel.route.value)

            viewModel.routeTaken()
            assertNull(viewModel.route.value)
        }

    @Test
    fun saysWhenASignInWasCutOffByTheProcessEnding() =
        runTest(dispatcher) {
            val viewModel = withDevice({ FakeCore() }, signInInterrupted = { true })
            advanceUntilIdle()

            assertEquals(AppNotice.SIGN_IN_INTERRUPTED, viewModel.notices.first())
        }

    @Test
    fun aPanicWhileReadingTheAccountShowsNothingRatherThanCrashing() =
        runTest(dispatcher) {
            onboarding.isFinished = true
            val core = FakeCore().apply { accountPanics = true }
            val viewModel = viewModel { core }
            advanceUntilIdle()

            viewModel.refreshAccount()
            advanceUntilIdle()

            assertEquals(AppPhase.Main, viewModel.phase.value)
            assertNull(viewModel.revocation.value)
        }

    private fun alert(id: String) =
        ScheduledAlert(
            id = id,
            fireAt = Instant.parse("2099-01-01T00:00:00Z"),
            target = AlertTarget.Event(id, null),
            title = "저녁",
            start = DayTime("2099-01-01", 600u),
            allDay = false,
            end = DayTime("2099-01-01", 660u),
        )

    private class FakeAlertCenter : AlertCenter {
        var allowed = true
        var removals = 0
        val pending = mutableListOf<PlannedAlert>()

        override fun allowsAlerts(): Boolean = allowed

        override fun scheduled(): List<PlannedAlert> = pending.toList()

        override fun schedule(alert: PlannedAlert) {
            pending += alert
        }

        override fun cancel(ids: List<String>) {
            pending.removeAll { it.id in ids }
        }

        override fun rearm() = Unit

        override fun removeAll() {
            removals++
            pending.clear()
        }
    }

    /** A phone with one calendar of [events] events. */
    private class FakeDeviceCalendars : DeviceCalendarSource {
        var events = 1
        var changed: () -> Unit = {}

        override fun granted(): Boolean = true

        override fun calendars(): List<DeviceCalendarSummary> = listOf(DeviceCalendarSummary("1", "개인", events))

        override fun read(ids: List<String>): List<DeviceCalendarInput> =
            listOf(
                DeviceCalendarInput(
                    "1",
                    "개인",
                    List(events) { DeviceEventInput("$it@1", "회의", false, DayTime("2026-11-07", 600u), DayTime("2026-11-07", 660u)) },
                ),
            )

        override fun openSettings() = Unit

        override fun observeChanges(action: () -> Unit) {
            changed = action
        }
    }

    private class FakeCore : Core(NoHandle) {
        var alerts: List<ScheduledAlert> = emptyList()

        override fun scheduledAlerts(limit: UInt): List<ScheduledAlert> = alerts

        val mirrors = mutableListOf<List<DeviceCalendarInput>>()
        var calendarList: List<CalendarView> = emptyList()

        override fun calendars(): List<CalendarView> = calendarList

        override fun mirrorDeviceCalendars(calendars: List<DeviceCalendarInput>): DeviceMirror {
            mirrors += calendars
            calendarList =
                calendars.map {
                    CalendarView("device:${it.sourceId}", it.name, null, CalendarKind.DEVICE, false, true, AlertRule.Inherit, 0u, 0u)
                }
            return DeviceMirror(calendars.size.toUInt(), 0u)
        }

        /** A panic in the core while the account is read, which UniFFI hands over as [InternalException]. */
        var accountPanics = false

        var foregrounds = 0
        var guests = 0
        var nextUpReads = 0
        var syncs = 0
        var syncError: CoreException? = null

        override suspend fun sync() {
            syncs++
            syncError?.let { throw it }
        }

        override fun recordAppForeground() {
            foregrounds++
        }

        var currentSession: Session? = null
        var currentAccount: AccountView? = null
        var revoked: Revocation? = null

        override fun session(): Session? = currentSession

        override fun account(): AccountView? {
            if (accountPanics) throw InternalException("boom")
            return currentAccount
        }

        override fun revocation(): Revocation? = revoked

        var otherAccount: LocalData? = null

        override fun localDataOfOtherAccount(): LocalData? = otherAccount

        override suspend fun signInGuest(account: NewAccount): Session {
            guests++
            return Session("u1", "d1")
        }

        override fun nextUp(count: UShort): List<UpNext> {
            nextUpReads++
            return emptyList()
        }
    }
}
