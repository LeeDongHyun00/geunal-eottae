package app.geunal.feature.calendar.support

import androidx.compose.ui.graphics.Color
import app.geunal.core.Palette
import app.geunal.designsystem.DeviceCalendarColor
import app.geunal.designsystem.GroupColor
import app.geunal.designsystem.GroupColors

private val byName: Map<String, GroupColor> = GroupColors.associateBy { it.name }

/** The design system's group color of the same name (docs/design/tokens.json `color.group`). */
val Palette.groupColor: GroupColor
    get() = byName.getValue(name.lowercase())

object CalendarColors {
    /**
     * The strong color an event's marker uses: the calendar's fifth tint, which is the same in both modes, or the one
     * device-calendar color when the core leaves the color empty (m1.md decision 17).
     */
    fun marker(palette: Palette?): Color = palette?.groupColor?.light?.last() ?: DeviceCalendarColor
}
