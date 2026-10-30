package app.geunal.feature.account

import app.geunal.core.CoreException
import app.geunal.feature.account.backup.BackupModel
import app.geunal.feature.account.testing.AccountFakeCore
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BackupModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val core = AccountFakeCore()

    private fun TestScope.model() = BackupModel(core, scope = this, io = dispatcher)

    @Test
    fun backsUpNowAndSaysItJustDid() =
        runTest(dispatcher) {
            val model = model()
            model.load()
            assertTrue(model.state.value.canBackUp)

            model.backUpNow()

            val state = model.state.value
            assertNull("the progress bar goes away", state.progress)
            assertTrue(state.justMade)
            assertEquals(4L, state.status?.last?.id)
            assertTrue(core.calls.contains("backupNow"))
        }

    @Test
    fun keepsALineWhenItCannotGetThrough() =
        runTest(dispatcher) {
            core.backupNowResult = Result.failure(CoreException.Store("offline"))
            val model = model()
            model.load()

            model.backUpNow()

            assertTrue(model.state.value.failed)
            assertFalse(model.state.value.justMade)
            assertNull(model.state.value.progress)
        }

    @Test
    fun waitsForACodePutOff() =
        runTest(dispatcher) {
            core.backup = core.backup.copy(ready = false)
            val model = model()
            model.load()

            assertTrue(model.state.value.waitsForCode)
            assertFalse(model.state.value.canBackUp)
            assertFalse("no reminder to write down a code not entered yet", model.state.value.codeNotSaved)
            model.backUpNow()
            assertFalse(core.calls.contains("backupNow"))
        }

    @Test
    fun asksForTheCodeToBeWrittenDown() =
        runTest(dispatcher) {
            val model = model()
            model.load()
            assertTrue(model.state.value.codeNotSaved)

            core.recovery = core.recovery.copy(saved = true)
            model.load()
            assertFalse(model.state.value.codeNotSaved)
        }

    @Test
    fun turnsTheAutomaticBackupOff() =
        runTest(dispatcher) {
            val model = model()
            model.load()

            model.setAuto(false)
            advanceUntilIdle()

            assertEquals("setAutoBackup false", core.calls.last { it.startsWith("setAutoBackup") })
            assertEquals(
                false,
                model.state.value.status
                    ?.auto,
            )
        }

    @Test
    fun listsTheBackupsNewestFirst() =
        runTest(dispatcher) {
            val model = model()
            model.load()

            assertEquals(
                listOf(3L, 2L),
                model.state.value.backups
                    .map { it.id },
            )
        }
}
