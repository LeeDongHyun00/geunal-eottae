package app.geunal.feature.calendar

import app.geunal.feature.calendar.quickentry.StripMath
import org.junit.Assert.assertEquals
import org.junit.Test

/** The 0–24 strip's arithmetic on a 480 wide strip: 10 per half hour. */
class StripMathTest {
    private val width = 480f
    private val reach = 22f

    @Test
    fun aTapTakesTheWholeHourUnderTheFinger() {
        // 09:40 lies in the 09:30 slot; the hour from 09:00 is chosen.
        assertEquals(18..19, StripMath.tapped(x = 193f, width = width))
        assertEquals(46..47, StripMath.tapped(x = 479f, width = width))
    }

    @Test
    fun dragsANewRangeEitherWay() {
        val drag = StripMath.startDrag(range = null, x = 180f, width = width, reach = reach)

        assertEquals(StripMath.Drag.New(18), drag)
        assertEquals(18..21, StripMath.dragged(null, drag, x = 215f, width = width))
        assertEquals(15..18, StripMath.dragged(null, drag, x = 150f, width = width))
    }

    @Test
    fun movesTheEndUnderTheFingerAndKeepsTheOther() {
        // 09:00–11:00 is slots 18..21; the upper handle sits at 220.
        val range = 18..21

        val upper = StripMath.startDrag(range, x = 230f, width = width, reach = reach)
        assertEquals(StripMath.Drag.Upper, upper)
        assertEquals(18..23, StripMath.dragged(range, upper, x = 240f, width = width))
        // Pressing the handle without moving leaves 11:00 where it was.
        assertEquals(range, StripMath.dragged(range, upper, x = 220f, width = width))
        // Dragged past the start, the range keeps at least half an hour.
        assertEquals(18..18, StripMath.dragged(range, upper, x = 100f, width = width))

        val lower = StripMath.startDrag(range, x = 175f, width = width, reach = reach)
        assertEquals(StripMath.Drag.Lower, lower)
        assertEquals(16..21, StripMath.dragged(range, lower, x = 160f, width = width))
        assertEquals(21..21, StripMath.dragged(range, lower, x = 300f, width = width))
    }

    @Test
    fun startsANewRangeAwayFromTheHandles() {
        assertEquals(StripMath.Drag.New(30), StripMath.startDrag(18..21, x = 305f, width = width, reach = reach))
    }

    @Test
    fun stepsForTalkBackStayInsideTheDay() {
        assertEquals(18..19, StripMath.moved(null, 30))
        assertEquals(30..33, StripMath.moved(18..21, 30))
        assertEquals(44..47, StripMath.moved(18..21, 46))
        assertEquals(18..22, StripMath.resized(18..21, 1))
        assertEquals(18..18, StripMath.resized(18..18, -1))
        assertEquals(46..47, StripMath.resized(46..47, 1))
    }
}
