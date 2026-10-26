package app.geunal.ui.corestatus

import app.geunal.core.Change
import app.geunal.core.Core
import app.geunal.core.CoreException
import app.geunal.core.CoreListener
import app.geunal.core.NoHandle
import app.geunal.core.Session
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
            val viewModel = CoreStatusViewModel({ FakeCore() }, dispatcher)
            advanceUntilIdle()

            assertEquals(
                CoreStatus(store = Step.Done, cipherVersion = "4.7.0 community", sync = Step.Done, changes = 0),
                viewModel.status.value,
            )
        }

    @Test
    fun showsWhyTheStoreDidNotOpen() =
        runTest(dispatcher) {
            val viewModel = CoreStatusViewModel({ throw CoreException.Store("file is not a database") }, dispatcher)
            advanceUntilIdle()

            val status = viewModel.status.value
            assertEquals(Step.Failed("reason=file is not a database"), status.store)
            assertNull(status.cipherVersion)
            assertEquals(Step.Pending, status.sync)
        }

    @Test
    fun showsAFailedSyncAfterASuccessfulOpen() =
        runTest(dispatcher) {
            val core = FakeCore(syncError = CoreException.Store("offline"))
            val viewModel = CoreStatusViewModel({ core }, dispatcher)
            advanceUntilIdle()

            assertEquals(Step.Done, viewModel.status.value.store)
            assertEquals(Step.Failed("reason=offline"), viewModel.status.value.sync)
        }

    @Test
    fun countsTheChangesTheListenerHears() =
        runTest(dispatcher) {
            var listener: CoreListener? = null
            val viewModel =
                CoreStatusViewModel({
                    listener = it
                    FakeCore()
                }, dispatcher)
            advanceUntilIdle()

            val heard = checkNotNull(listener)
            heard.onChange(Change.Calendar)
            heard.onChange(Change.Group("g1"))
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

        override fun session(): Session? = null
    }
}
