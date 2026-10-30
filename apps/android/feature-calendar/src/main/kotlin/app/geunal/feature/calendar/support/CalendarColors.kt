package app.geunal.feature.calendar.support

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.unit.dp
import app.geunal.core.Palette
import app.geunal.designsystem.DeviceCalendarColor
import app.geunal.designsystem.GroupColor
import app.geunal.designsystem.GroupColors
import app.geunal.designsystem.LocalDarkTheme
import app.geunal.designsystem.LocalNeutralColors

private val byName: Map<String, GroupColor> = GroupColors.associateBy { it.name }

/** The design system's group color of the same name (docs/design/tokens.json `color.group`). */
val Palette.groupColor: GroupColor
    get() = byName.getValue(name.lowercase())

/** Events from device calendars all share cocoa (m1.md decision 17); the core leaves their color empty. */
private val deviceCalendar: GroupColor get() = byName.getValue("cocoa")

/** What an event bar or block is painted with. */
data class BarColors(
    val fill: Color,
    val text: Color,
)

object CalendarColors {
    /**
     * The strong color an event's marker uses: the calendar's fifth tint, which is the same in both modes, or the one
     * device-calendar color when the core leaves the color empty (m1.md decision 17).
     */
    fun marker(palette: Palette?): Color = palette?.groupColor?.light?.last() ?: DeviceCalendarColor

    /**
     * An event bar or block (components.md §8): the second tint with ink text in light mode. In dark mode the calendar
     * colors keep their light values, so the bar turns to the fifth tint with white text (m1.md §4 "어두운 모드").
     */
    fun bar(
        palette: Palette?,
        dark: Boolean,
        ink: Color,
    ): BarColors {
        val color = palette?.groupColor ?: deviceCalendar
        return if (dark) BarColors(color.light.last(), Color.White) else BarColors(color.light[1], ink)
    }
}

/**
 * The fill of an event bar or block. A soft calendar's events are striped like the boards (135° stripes over the
 * lightest tint), so they read as "mine only, free to groups" without a legend.
 */
@Composable
fun EventFill(
    palette: Palette?,
    soft: Boolean,
    modifier: Modifier = Modifier,
) {
    val dark = LocalDarkTheme.current
    val ink = LocalNeutralColors.current.ink
    if (!soft) {
        Box(modifier.fillMaxSize().background(CalendarColors.bar(palette, dark, ink).fill))
        return
    }
    val color = palette?.groupColor ?: deviceCalendar
    val base = if (dark) color.light.last() else color.light.first()
    val stripe = if (dark) Color.Black.copy(alpha = 0.2f) else Color.White.copy(alpha = 0.35f)
    Canvas(modifier.fillMaxSize()) {
        drawRect(base)
        // 4 dp stripes every 8 dp across them, as diagonals long enough to cross the rectangle.
        val width = 4.dp.toPx()
        val step = 8.dp.toPx() * 1.4142135f
        clipRect {
            var offset = -size.height
            while (offset < size.width) {
                drawLine(stripe, Offset(offset, size.height), Offset(offset + size.height, 0f), strokeWidth = width)
                offset += step
            }
        }
    }
}
