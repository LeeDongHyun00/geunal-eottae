package app.geunal.feature.account

import app.geunal.core.CoreException
import app.geunal.core.LocalData
import app.geunal.core.LocalDataChoice
import app.geunal.feature.account.restore.RestoreMode
import app.geunal.feature.account.restore.RestoreModel
import app.geunal.feature.account.support.AccountNotice
import app.geunal.feature.account.support.DeviceAuthPurpose
import app.geunal.feature.account.testing.AccountFakeCore
import app.geunal.feature.account.testing.CODE
import app.geunal.feature.account.testing.FakeDeviceAuth
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

@OptIn(ExperimentalCoroutinesApi::class)
class RestoreModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val core = AccountFakeCore()
    private val deviceAuth = FakeDeviceAuth()
    private val notices = mutableListOf<AccountNotice>()

    private fun TestScope.model(
        mode: RestoreMode = RestoreMode.DEVICE_CHANGE,
        ageConfirmedAt: Instant? = null,
    ) = RestoreModel(mode, core, this, ageConfirmedAt, notify = { notices += it }, deviceAuth = deviceAuth, io = dispatcher)

    private fun TestScope.type(
        model: RestoreModel,
        index: Int,
        text: String,
    ) {
        model.focused(index)
        model.changed(index, text)
        advanceUntilIdle()
    }

    private fun TestScope.pasted(model: RestoreModel): RestoreModel {
        model.paste(CODE.joinToString(" "))
        advanceUntilIdle()
        return model
    }

    @Test
    fun takesAWholeWordAndMovesOn() =
        runTest(dispatcher) {
            val model = model()

            type(model, 0, "바람")

            assertEquals("바람", model.state.value.accepted[0])
            assertEquals(1, model.state.value.focused)
            assertEquals(1, model.state.value.acceptedCount)
        }

    @Test
    fun onlyHintsAtTheWordAPrefixLeadsTo() =
        runTest(dispatcher) {
            val model = model()

            type(model, 0, "가이")

            // The text stays as typed: on the way to "가입" the keyboard passes through "가이".
            val state = model.state.value
            assertEquals("가이", state.cells[0])
            assertNull(state.accepted[0])
            assertEquals("가이드", state.completions[0])
            assertEquals(0, state.focused)
        }

    @Test
    fun takesTheHintWhenThePersonLeavesTheCell() =
        runTest(dispatcher) {
            val model = model()
            type(model, 0, "가이")

            model.left(0)
            advanceUntilIdle()

            assertEquals("가이드", model.state.value.cells[0])
            assertEquals("가이드", model.state.value.accepted[0])
        }

    @Test
    fun keepsTheCellThePersonTappedWhenTheLastOneTakesItsHint() =
        runTest(dispatcher) {
            val model = model()
            type(model, 0, "가이")

            // The new cell takes the focus first, then the one that was left hears about it.
            model.focused(5)
            model.left(0)
            advanceUntilIdle()

            assertEquals("가이드", model.state.value.accepted[0])
            assertEquals(5, model.state.value.focused)
        }

    @Test
    fun countsOnlyItsOwnRewritesOfACell() =
        runTest(dispatcher) {
            val model = model()

            type(model, 0, "가이")
            assertEquals(0, model.state.value.rewrites[0])
            model.left(0)
            advanceUntilIdle()
            assertEquals(1, model.state.value.rewrites[0])
            model.paste(CODE.joinToString(" "))
            advanceUntilIdle()

            assertEquals(2, model.state.value.rewrites[0])
            assertEquals(1, model.state.value.rewrites[1])
        }

    @Test
    fun goesOnToTheNextCellOnTheKeyboardsNext() =
        runTest(dispatcher) {
            val model = model()
            type(model, 0, "가이")

            model.submitted(0)
            advanceUntilIdle()

            assertEquals("가이드", model.state.value.accepted[0])
            assertEquals(1, model.state.value.focused)
        }

    @Test
    fun marksAWordOutsideTheListOnceTheCellIsLeft() =
        runTest(dispatcher) {
            val model = model()
            type(model, 7, "바다")
            assertNull(model.state.value.problem)

            model.focused(8)
            model.left(7)
            advanceUntilIdle()

            assertEquals(RestoreModel.Problem.UnknownWord(7), model.state.value.problem)
            assertTrue(model.state.value.isUnknown(7))
        }

    @Test
    fun fillsAllTwelveFromAPastedCode() =
        runTest(dispatcher) {
            val model = model()

            model.paste("1. 바람 2. 연필 3. 구름 4. 달력 5. 열쇠 6. 우산\n7. 단추 8. 마당 9. 편지 10. 소풍 11. 거울 12. 하늘")
            advanceUntilIdle()

            assertEquals(
                CODE,
                model.state.value.accepted
                    .filterNotNull(),
            )
            assertTrue(model.state.value.isComplete)
            assertNull(model.state.value.focused)
        }

    @Test
    fun restoresWithTheCode() =
        runTest(dispatcher) {
            val model = pasted(model())

            model.start()
            advanceUntilIdle()

            assertEquals("restore " + CODE.joinToString(" "), core.calls.last())
            val phase = model.state.value.phase
            assertTrue("phase is $phase", phase is RestoreModel.Phase.Finished)
            assertEquals(3u, (phase as RestoreModel.Phase.Finished).summary.events)
            assertFalse(model.state.value.isWorking)
        }

    @Test
    fun asksAboutAnotherAccountsEventsFirst() =
        runTest(dispatcher) {
            core.otherAccountData = LocalData(events = 86u, people = 5u)
            val model = pasted(model())

            model.start()
            advanceUntilIdle()
            assertEquals(RestoreModel.Phase.AskingLocalData(LocalData(86u, 5u)), model.state.value.phase)
            model.choose(LocalDataChoice.DISCARD)
            advanceUntilIdle()

            assertEquals(LocalDataChoice.DISCARD, core.lastLocalData)
        }

    @Test
    fun restoresOnceWhenTheButtonIsTappedTwice() =
        runTest(dispatcher) {
            val model = pasted(model())

            model.start()
            model.start()
            advanceUntilIdle()

            assertEquals(1, core.calls.count { it.startsWith("restore") })
        }

    @Test
    fun takesTheAnswerAboutTheLocalEventsOnce() =
        runTest(dispatcher) {
            core.otherAccountData = LocalData(events = 86u, people = 5u)
            val model = pasted(model())
            model.start()
            advanceUntilIdle()

            model.choose(LocalDataChoice.KEEP)
            model.choose(LocalDataChoice.DISCARD)
            advanceUntilIdle()

            assertEquals(1, core.calls.count { it.startsWith("restore") })
            assertEquals(LocalDataChoice.KEEP, core.lastLocalData)
        }

    @Test
    fun saysTheCodeDoesNotOpenTheBackup() =
        runTest(dispatcher) {
            core.restoreResult = Result.failure(CoreException.WrongRecoveryCode())
            val model = pasted(model())

            model.start()
            advanceUntilIdle()

            assertEquals(RestoreModel.Problem.WrongCode, model.state.value.problem)
            assertEquals(RestoreModel.Phase.Entering, model.state.value.phase)
        }

    @Test
    fun sendsThePersonBackWhenTheTicketRanOut() =
        runTest(dispatcher) {
            core.restoreResult = Result.failure(CoreException.Api(401u, "token_expired", ""))
            val model = pasted(model())

            model.start()
            advanceUntilIdle()

            assertEquals(RestoreModel.Phase.TicketExpired, model.state.value.phase)
            assertEquals(listOf(AccountNotice.TICKET_EXPIRED), notices)
        }

    @Test
    fun asksWithTheBackupCountBeforeStartingFresh() =
        runTest(dispatcher) {
            val model = model(RestoreMode.DEVICE_CHANGE)
            model.openNoCode()

            model.startOver()
            advanceUntilIdle()

            assertEquals(
                RestoreModel.NoCode.ConfirmingWipe(backups = 2, lastAt = Instant.parse("2026-10-20T09:20:00Z")),
                model.state.value.noCode,
            )
            assertEquals(listOf("backups"), core.calls)
        }

    @Test
    fun startsFreshOnceTheOwnerPassesTheOsCheck() =
        runTest(dispatcher) {
            val model = model(RestoreMode.OFFER)
            model.openNoCode()
            model.startOver()
            advanceUntilIdle()

            model.confirmWipe()
            advanceUntilIdle()

            assertEquals(listOf(DeviceAuthPurpose.WIPE_BACKUPS), deviceAuth.asked)
            assertEquals(listOf("backups", "startFresh"), core.calls)
            assertEquals(RestoreModel.Phase.StartedFresh, model.state.value.phase)
            assertNull(model.state.value.noCode)
        }

    @Test
    fun deletesNothingWhenTheOsCheckIsCancelled() =
        runTest(dispatcher) {
            deviceAuth.passes = false
            val model = model(RestoreMode.DEVICE_CHANGE)
            model.openNoCode()
            model.startOver()
            advanceUntilIdle()
            val confirming = model.state.value.noCode

            model.confirmWipe()
            advanceUntilIdle()

            assertEquals(listOf("backups"), core.calls)
            assertEquals(confirming, model.state.value.noCode)
            assertFalse(model.state.value.isWorking)
        }

    @Test
    fun suggestsAScreenLockWhenThePhoneHasNone() =
        runTest(dispatcher) {
            deviceAuth.secure = false
            val model = model(RestoreMode.DEVICE_CHANGE)
            model.openNoCode()
            model.startOver()
            advanceUntilIdle()

            val confirming = model.state.value.noCode as RestoreModel.NoCode.ConfirmingWipe
            assertTrue(confirming.screenLockMissing)
            model.confirmWipe()
            advanceUntilIdle()
            assertEquals(RestoreModel.Phase.StartedFresh, model.state.value.phase)
        }

    @Test
    fun asksWithoutTheNumberWhenTheBackupsCannotBeRead() =
        runTest(dispatcher) {
            core.backupsResult = Result.failure(CoreException.Network("offline"))
            val model = model(RestoreMode.DEVICE_CHANGE)
            model.openNoCode()

            model.startOver()
            advanceUntilIdle()

            assertEquals(RestoreModel.NoCode.ConfirmingWipe(backups = null, lastAt = null), model.state.value.noCode)
        }

    @Test
    fun letsTheOfferWaitForTheCode() =
        runTest(dispatcher) {
            val model = model(RestoreMode.OFFER)
            model.openNoCode()

            model.later()

            assertEquals(RestoreModel.Phase.Later, model.state.value.phase)
            assertEquals(emptyList<String>(), core.calls)
        }

    @Test
    fun hasNoLaterWithAReplacementTicket() =
        runTest(dispatcher) {
            val model = model(RestoreMode.DEVICE_CHANGE)
            model.openNoCode()

            model.later()

            assertEquals(RestoreModel.Phase.Entering, model.state.value.phase)
        }

    @Test
    fun startsANewGuestAccountWithoutTheCode() =
        runTest(dispatcher) {
            val ticked = Instant.ofEpochSecond(1_793_000_000)
            val model = model(RestoreMode.GUEST_RETURN, ageConfirmedAt = ticked)
            model.openNoCode()

            model.startOver()
            advanceUntilIdle()

            assertEquals(listOf("guest"), core.calls)
            assertEquals(ticked, core.lastAccount?.ageConfirmedAt)
            assertEquals(RestoreModel.Phase.StartedFresh, model.state.value.phase)
        }

    @Test
    fun aNewGuestNeedsTheAgeTickFirst() =
        runTest(dispatcher) {
            val model = model(RestoreMode.GUEST_RETURN, ageConfirmedAt = null)
            model.openNoCode()

            model.startOver()
            advanceUntilIdle()

            assertEquals(emptyList<String>(), core.calls)
            assertEquals(RestoreModel.Phase.AgeCheckNeeded, model.state.value.phase)
            assertEquals(listOf(AccountNotice.AGE_CHECK_NEEDED), notices)
        }
}
