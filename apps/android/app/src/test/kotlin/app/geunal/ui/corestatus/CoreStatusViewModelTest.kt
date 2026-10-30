package app.geunal.ui.corestatus

import app.geunal.core.Change
import app.geunal.core.Core
import app.geunal.core.CoreException
import app.geunal.core.NoHandle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CoreStatusViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun showsTheCipherVersionAndASuccessfulSync() =
        runTest(dispatcher) {
            val viewModel = CoreStatusViewModel(FakeCore(), emptyFlow(), dispatcher)
            advanceUntilIdle()

            assertEquals(CoreStatus(cipherVersion = "4.7.0 community", sync = Step.Done, changes = 0), viewModel.status.value)
        }

    @Test
    fun namesAFailedSyncByKindOnly() =
        runTest(dispatcher) {
            val viewModel =
                CoreStatusViewModel(FakeCore(syncError = CoreException.Network("connect to 10.0.2.2 refused")), emptyFlow(), dispatcher)
            advanceUntilIdle()

            assertEquals(Step.Failed("Network"), viewModel.status.value.sync)
        }

    @Test
    fun countsTheChangesTheListenerHears() =
        runTest(dispatcher) {
            val changes = MutableSharedFlow<Change>(extraBufferCapacity = 4)
            val viewModel = CoreStatusViewModel(FakeCore(), changes, dispatcher)
            advanceUntilIdle()

            changes.emit(Change.Calendar)
            changes.emit(Change.Group("g1"))
            advanceUntilIdle()

            assertEquals(2, viewModel.status.value.changes)
        }

    /**
     * Subclasses the generated `Core` without a Rust handle, the way UniFFI provides for fakes, and overrides only what
     * the status screen calls; any other method fails. That keeps this fake unchanged as the core's surface grows.
     */
    private class FakeCore(
        private val syncError: CoreException? = null,
    ) : Core(NoHandle) {
        override fun cipherVersion(): String = "4.7.0 community"

        override suspend fun sync() {
            syncError?.let { throw it }
        }
    }
}
