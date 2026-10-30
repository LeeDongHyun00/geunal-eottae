package app.geunal

import app.geunal.core.Change
import app.geunal.core.Core
import app.geunal.core.CoreException
import app.geunal.core.CoreInterface
import app.geunal.core.InternalException
import app.geunal.core.NewAccount
import app.geunal.core.NoHandle
import app.geunal.core.Session
import app.geunal.core.UpNext
import app.geunal.feature.account.support.OnboardingState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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

            viewModel.start()
            advanceUntilIdle()

            assertEquals(2, attempts)
            assertEquals(AppPhase.Onboarding, viewModel.phase.value)
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
    fun syncsEachTimeTheAppComesToTheFrontAndShrugsOffAFailure() =
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

    private class FakeCore : Core(NoHandle) {
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

        override fun session(): Session? = null

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
