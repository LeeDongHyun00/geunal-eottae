package app.geunal.feature.account

import app.geunal.core.BackupView
import app.geunal.feature.account.security.RecoveryCodeModel
import app.geunal.feature.account.testing.AccountFakeCore
import app.geunal.feature.account.testing.CODE
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class RecoveryCodeModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val core = AccountFakeCore()

    private fun TestScope.model() = RecoveryCodeModel(core, scope = this, io = dispatcher)

    @Test
    fun readsTheTwelveWordsAndTheLastBackup() =
        runTest(dispatcher) {
            val last = BackupView(3, Instant.parse("2026-10-30T03:12:00Z"), 10u, true, 3u, 2u)
            core.backup = core.backup.copy(last = last)
            val model = model()

            model.load()

            assertEquals(CODE, model.state.value.words)
            assertEquals(last.createdAt, model.state.value.lastBackupAt)
            assertFalse(model.state.value.failed)
        }

    @Test
    fun recordsTheCodeWrittenDown() =
        runTest(dispatcher) {
            val model = model()
            model.load()

            assertTrue(model.markSaved())

            assertEquals("markRecoverySaved", core.calls.last())
            assertTrue(core.recovery.saved)
        }

    @Test
    fun saysSoWhenThereIsNoCodeToShow() =
        runTest(dispatcher) {
            core.recoveryCode = null
            val model = model()

            model.load()

            assertTrue(model.state.value.failed)
            assertTrue(
                model.state.value.words
                    .isEmpty(),
            )
        }
}
