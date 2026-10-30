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
