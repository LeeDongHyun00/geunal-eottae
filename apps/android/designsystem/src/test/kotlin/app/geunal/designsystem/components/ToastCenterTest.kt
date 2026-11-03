package app.geunal.designsystem.components

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ToastCenterTest {
    @Test
    fun runsTheActionAndClosesAtOnce() =
        runTest {
            val center = ToastCenter(backgroundScope)
            var undone = 0

            center.show("추가했어요", ToastAction("되돌리기") { undone++ })
            center.performAction()

            assertEquals(1, undone)
            assertNull(center.current.value)
        }

    @Test
    fun aNewToastReplacesTheOldOne() =
        runTest {
            val center = ToastCenter(backgroundScope)

            center.show("하나")
            center.show("둘")

            assertEquals("둘", center.current.value?.message)
        }

    @Test
    fun goesAwayByItselfAfterFiveSeconds() =
        runTest {
            val center = ToastCenter(backgroundScope)

            center.show("추가했어요")
            advanceTimeBy(4_999)
            runCurrent()
            assertEquals("추가했어요", center.current.value?.message)
            advanceTimeBy(2)
            runCurrent()

            assertNull(center.current.value)
        }

    private val proposal = Proposal("매주 금요일 반복으로 바꿀까요?", "괜찮아요", "반복으로 바꾸기")

    @Test
    fun theQuestionOutlivesItsToast() =
        runTest {
            val center = ToastCenter(backgroundScope)

            center.show("카페 마감을 추가했어요", null, proposal) {}
            advanceTimeBy(5_001)
            runCurrent()
            assertNull(center.current.value)
            assertEquals(proposal, center.question.value?.proposal)
            advanceTimeBy(5_000)
            runCurrent()

            assertNull(center.question.value)
        }

    @Test
    fun undoTakesTheQuestionWithIt() =
        runTest {
            val center = ToastCenter(backgroundScope)
            var undone = 0
            var accepted = 0
            center.show("카페 마감을 추가했어요", ToastAction("되돌리기") { undone++ }, proposal) { accepted++ }

            center.performAction()

            assertEquals(1, undone)
            assertNull(center.question.value)
            center.acceptProposal()
            assertEquals(0, accepted)
        }

    @Test
    fun answeringClosesOnlyTheCard() =
        runTest {
            val center = ToastCenter(backgroundScope)
            var accepted = 0
            center.show("카페 마감을 추가했어요", ToastAction("되돌리기") {}, proposal) { accepted++ }

            center.declineProposal()
            assertNull(center.question.value)
            assertEquals("카페 마감을 추가했어요", center.current.value?.message)
            center.show("카페 마감을 추가했어요", ToastAction("되돌리기") {}, proposal) { accepted++ }
            center.acceptProposal()

            assertEquals(1, accepted)
            assertNull(center.question.value)
        }

    @Test
    fun aScreenReaderKeepsTheQuestionUntilItIsAnswered() =
        runTest {
            val center = ToastCenter(backgroundScope, screenReaderRunning = { true })

            center.show("카페 마감을 추가했어요", null, proposal) {}
            advanceTimeBy(60_000)
            runCurrent()

            assertEquals(proposal, center.question.value?.proposal)
        }

    @Test
    fun aNewToastDropsTheOldQuestion() =
        runTest {
            val center = ToastCenter(backgroundScope)
            center.show("카페 마감을 추가했어요", null, proposal) {}

            center.show("되돌렸어요")

            assertNull(center.question.value)
        }

    @Test
    fun aNewToastGetsItsOwnFiveSeconds() =
        runTest {
            val center = ToastCenter(backgroundScope)

            center.show("하나")
            advanceTimeBy(3_000)
            center.show("둘")
            advanceTimeBy(3_000)
            runCurrent()

            assertEquals("둘", center.current.value?.message)
        }
}
