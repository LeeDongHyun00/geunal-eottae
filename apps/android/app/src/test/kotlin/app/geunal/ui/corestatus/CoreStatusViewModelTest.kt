package app.geunal.ui.corestatus

import app.geunal.core.Change
import app.geunal.core.ChatMessage
import app.geunal.core.CoreException
import app.geunal.core.CoreInterface
import app.geunal.core.CoreListener
import app.geunal.core.GroupInfo
import app.geunal.core.GroupStateView
import app.geunal.core.MemberAvailability
import app.geunal.core.Mode
import app.geunal.core.RealtimeEvent
import app.geunal.core.Session
import app.geunal.core.Stage
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

    /** Only the two members the status screen calls are real; the rest of the M0 surface is not reached here. */
    private class FakeCore(
        private val syncError: CoreException? = null,
    ) : CoreInterface {
        override fun cipherVersion(): String = "4.7.0 community"

        override suspend fun sync() {
            syncError?.let { throw it }
        }

        override fun session(): Session? = null

        override suspend fun signInStub(
            subject: String,
            displayName: String,
            platform: String,
        ): Session = unused()

        override suspend fun createGroup(name: String?): String = unused()

        override suspend fun addMembers(
            groupId: String,
            userIds: List<String>,
        ) = unused()

        override suspend fun removeMember(
            groupId: String,
            userId: String,
        ) = unused()

        override suspend fun sendText(
            groupId: String,
            body: String,
        ): String = unused()

        override suspend fun requestDate(
            groupId: String,
            day: String,
            mode: Mode,
            start: UShort?,
            end: UShort?,
            activity: String?,
            note: String?,
            leaderOptions: Boolean,
        ): String = unused()

        override suspend fun respond(
            groupId: String,
            appt: String,
            accept: Boolean,
        ) = unused()

        override suspend fun openPoll(
            groupId: String,
            appt: String,
            stage: Stage,
            options: List<String>,
        ) = unused()

        override suspend fun vote(
            groupId: String,
            appt: String,
            stage: Stage,
            option: String?,
        ) = unused()

        override suspend fun closePoll(
            groupId: String,
            appt: String,
            stage: Stage,
        ) = unused()

        override suspend fun cancel(
            groupId: String,
            appt: String,
        ) = unused()

        override suspend fun setAvailability(
            groupId: String,
            windowStart: String,
            days: UShort,
            bits: ByteArray,
        ) = unused()

        override fun groups(): List<GroupInfo> = unused()

        override fun members(groupId: String): List<String> = unused()

        override fun groupState(groupId: String): GroupStateView = unused()

        override fun messages(groupId: String): List<ChatMessage> = unused()

        override fun groupAvailability(groupId: String): List<MemberAvailability> = unused()

        override suspend fun connectRealtime(gatewayBase: String) = unused()

        override suspend fun nextRealtimeEvent(timeoutMs: ULong): RealtimeEvent? = unused()

        override suspend fun disconnectRealtime() = unused()

        private fun unused(): Nothing = error("the status screen does not call this")
    }
}
