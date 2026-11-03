package app.geunal.feature.calendar

import app.geunal.core.AddedEvent
import app.geunal.core.CoreException
import app.geunal.core.RepeatHint
import app.geunal.core.Weekday
import app.geunal.designsystem.components.ToastCenter
import app.geunal.feature.calendar.quickentry.EventActions
import app.geunal.feature.calendar.testing.CalendarFakeCore
import app.geunal.feature.calendar.testing.Samples
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EventActionsTest {
    private val dispatcher = StandardTestDispatcher()
    private val core = CalendarFakeCore()
    private val cafe = Samples.input("카페 마감", "2026-10-30", 1020, 1320)

    private fun TestScope.actions(toasts: ToastCenter) = EventActions(core, toasts, PlainTexts, scope = this, io = dispatcher)

    @Test
    fun saysWhatWasAddedWithAnUndoThatDeletesIt() =
        runTest(dispatcher) {
            val toasts = ToastCenter(backgroundScope)
            val actions = actions(toasts)

            assertTrue(actions.add(cafe, fromSuggestion = true))

            val toast = toasts.current.value!!
            assertEquals("카페 마감을(를) 추가했어요", toast.message)
            assertEquals("되돌리기", toast.action?.label)
            assertNull(toasts.question.value)

            toasts.performAction()
            advanceUntilIdle()

            assertEquals(listOf("add 카페 마감 2026-10-30 1020-1320 suggested", "delete e9 THIS_AND_FOLLOWING"), core.calls)
            assertEquals("되돌렸어요", toasts.current.value?.message)
        }

    @Test
    fun asksOnTheThirdSameWeekdayAndMakesItWeekly() =
        runTest(dispatcher) {
            core.added = Result.success(AddedEvent("e9", RepeatHint(Weekday.FRIDAY, 3u)))
            val toasts = ToastCenter(backgroundScope)
            val actions = actions(toasts)

            actions.add(cafe, fromSuggestion = false)
            assertEquals(
                "매주 FRIDAY 반복으로 바꿀까요?",
                toasts.question.value
                    ?.proposal
                    ?.message,
            )

            toasts.acceptProposal()
            advanceUntilIdle()

            assertEquals("update e9 WEEKLY", core.calls.last())
            assertEquals("매주 FRIDAY로 바꿨어요", toasts.current.value?.message)
            assertNull(toasts.question.value)
        }

    @Test
    fun saysSoWhenTheCoreRefuses() =
        runTest(dispatcher) {
            core.added = Result.failure(CoreException.Invalid("title"))
            val toasts = ToastCenter(backgroundScope)

            assertFalse(actions(toasts).add(cafe, fromSuggestion = false))
            assertEquals("저장하지 못했어요", toasts.current.value?.message)

            core.added = Result.success(AddedEvent("e9", null))
            core.deleteError = CoreException.Store("busy")
            actions(toasts).undo("e9")
            assertEquals("되돌리지 못했어요", toasts.current.value?.message)
        }

    @Test
    fun finishesAnAddAfterTheSheetHasGone() =
        runTest(dispatcher) {
            val toasts = ToastCenter(backgroundScope)
            var saved: Boolean? = null

            actions(toasts).launchAdd(cafe, fromSuggestion = false) { saved = it }
            advanceUntilIdle()

            assertEquals(true, saved)
            assertEquals("카페 마감을(를) 추가했어요", toasts.current.value?.message)
        }
}
