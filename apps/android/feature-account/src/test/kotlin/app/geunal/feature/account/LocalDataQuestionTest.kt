package app.geunal.feature.account

import app.geunal.core.CoreException
import app.geunal.core.LocalData
import app.geunal.core.LocalDataChoice
import app.geunal.core.Session
import app.geunal.feature.account.onboarding.LocalDataQuestion
import app.geunal.feature.account.support.AccountNotice
import app.geunal.feature.account.testing.AccountFakeCore
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** m1.md screen 24 after an account is decided without a restore. */
class LocalDataQuestionTest {
    private val dispatcher = StandardTestDispatcher()
    private val core = AccountFakeCore()
    private val notices = mutableListOf<AccountNotice>()
    private val question = LocalDataQuestion(core, notify = { notices += it }, io = dispatcher)
    private var wentOn = false

    @Test
    fun goesOnAtOnceWithNothingToAsk() =
        runTest(dispatcher) {
            core.currentSession = Session("u2", "d1")

            question.ask { wentOn = true }

            assertTrue(wentOn)
            assertNull(question.asking.value)
        }

    @Test
    fun waitsForASessionBeforeAsking() =
        runTest(dispatcher) {
            // After a deletion or a sign-out the core still knows the old owner's events, but takes an answer only from
            // a signed-in account.
            core.otherAccountData = LocalData(86u, 5u)

            question.ask { wentOn = true }

            assertTrue(wentOn)
            assertNull(question.asking.value)
            assertTrue(core.settled.isEmpty())
        }

    @Test
    fun asksThenSettlesTheAnswerAndGoesOn() =
        runTest(dispatcher) {
            core.currentSession = Session("u2", "d1")
            core.otherAccountData = LocalData(86u, 5u)

            question.ask { wentOn = true }
            assertEquals(LocalData(86u, 5u), question.asking.value)
            assertFalse(wentOn)

            question.answer(LocalDataChoice.DISCARD)

            assertEquals(listOf(LocalDataChoice.DISCARD), core.settled)
            assertNull(question.asking.value)
            assertTrue(wentOn)
        }

    @Test
    fun keepsAskingWhenTheAnswerCouldNotBeSaved() =
        runTest(dispatcher) {
            core.currentSession = Session("u2", "d1")
            core.otherAccountData = LocalData(1u, 0u)
            core.settleError = CoreException.Store("full")
            question.ask { wentOn = true }

            question.answer(LocalDataChoice.KEEP)

            assertNotNull(question.asking.value)
            assertFalse(wentOn)
            assertEquals(listOf(AccountNotice.SOMETHING_FAILED), notices)
        }

    @Test
    fun aSessionThatEndedUnderTheSheetTakesItAway() =
        runTest(dispatcher) {
            core.currentSession = Session("u2", "d1")
            core.otherAccountData = LocalData(1u, 0u)
            question.ask { wentOn = true }
            core.settleError = CoreException.NotSignedIn()

            question.answer(LocalDataChoice.KEEP)

            assertNull("no sheet left that no answer can close", question.asking.value)
            assertFalse(wentOn)
            assertTrue(notices.isEmpty())
        }
}
