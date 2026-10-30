package app.geunal.feature.calendar.month

import android.content.res.Resources
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.geunal.core.BirthdayItem
import app.geunal.core.DayCell
import app.geunal.core.EventItem
import app.geunal.designsystem.GeunalType
import app.geunal.designsystem.LocalDarkTheme
import app.geunal.designsystem.LocalNeutralColors
import app.geunal.designsystem.Metrics
import app.geunal.designsystem.Radius
import app.geunal.designsystem.Space
import app.geunal.designsystem.components.pressScale
import app.geunal.feature.calendar.R
import app.geunal.feature.calendar.support.CalendarColors
import app.geunal.feature.calendar.support.DayTitle
import app.geunal.feature.calendar.support.EventFill
import app.geunal.feature.calendar.support.KoreanCalendar
import app.geunal.feature.calendar.support.dayOfWeek
import app.geunal.feature.calendar.support.text
import java.time.DayOfWeek
import app.geunal.designsystem.R as DesignR

/**
 * The month grid (m1.md screen 4, boards/Month): the weekday row, then a page per month that follows the finger
 * sideways. A day is one button that opens the quick entry.
 */
@Composable
fun MonthView(
    model: MonthModel,
    pager: PagerState,
    onDay: (DayCell) -> Unit,
    /** A tap on an event bar opens the editor. */
    onEvent: (EventItem) -> Unit,
    /** A tap on the "연차" mark of a long weekend's leave day. */
    onLeave: (DayCell) -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by model.state.collectAsStateWithLifecycle()
    Column(modifier) {
        WeekdayRow(Modifier.padding(horizontal = Space.xs))
        HorizontalPager(
            state = pager,
            beyondViewportPageCount = 1,
            key = { model.months[it] },
            // Six weeks always fit, so a five-week month leaves the last row empty rather than moving the bars below.
            modifier = Modifier.height(Metrics.Grid.monthCell * 6 + Space.xl),
        ) { page ->
            val month = model.months[page]
            LaunchedEffect(month) { model.load(month) }
            MonthPage(
                weeks = state.weeks(month),
                today = state.today,
                showsHint = month == state.currentMonth && state.showsFirstRunHint,
                onDay = onDay,
                onEvent = onEvent,
                onLeave = onLeave,
            )
        }
    }
}

/** 일 to 토, Sunday red and Saturday blue (tone.md "날짜와 시간"). The cells read their own weekday, so TalkBack skips it. */
@Composable
fun WeekdayRow(modifier: Modifier = Modifier) {
    val names = stringArrayResource(R.array.calendar_weekday_short)
    Row(modifier.fillMaxWidth().clearAndSetSemantics {}) {
        names.forEachIndexed { index, name ->
            Text(
                name,
                style = GeunalType.caption.copy(fontWeight = FontWeight.SemiBold, fontSize = 12.sp),
                color = DayColors.weekday(index),
                modifier = Modifier.weight(1f).heightIn(min = 24.dp).wrapContentSize(),
            )
        }
    }
}

@Composable
private fun MonthPage(
    weeks: List<List<DayCell>>,
    today: String,
    showsHint: Boolean,
    onDay: (DayCell) -> Unit,
    onEvent: (EventItem) -> Unit,
    onLeave: (DayCell) -> Unit,
) {
    val neutrals = LocalNeutralColors.current
    Column(Modifier.padding(horizontal = Space.xs)) {
        weeks.forEach { week ->
            val hasToday = week.any { it.day == today }
            Row(
                Modifier
                    // The hint hangs below today's row, over the next one.
                    .zIndex(if (hasToday) 1f else 0f)
                    .drawBehind { drawLine(neutrals.separator, Offset.Zero, Offset(size.width, 0f), strokeWidth = 1.dp.toPx()) },
            ) {
                week.forEach { cell ->
                    Box(Modifier.weight(1f)) {
                        MonthCell(cell = cell, today = today, onClick = { onDay(cell) }, onEvent = onEvent, onLeave = { onLeave(cell) })
                        if (showsHint && cell.day == today) FirstRunHint(Modifier.align(Alignment.BottomCenter))
                    }
                }
            }
        }
    }
}

/** "날짜를 누르면 추천이 떠요" under today's cell while the calendar is still empty (m1.md screen 4). */
@Composable
private fun FirstRunHint(modifier: Modifier = Modifier) {
    val neutrals = LocalNeutralColors.current
    Box(
        modifier
            .wrapContentSize(unbounded = true, align = Alignment.TopCenter)
            .padding(top = 6.dp),
    ) {
        Text(
            stringResource(R.string.calendar_first_run_hint),
            style = GeunalType.caption.copy(fontWeight = FontWeight.SemiBold),
            color = neutrals.background,
            maxLines = 1,
            modifier =
                Modifier
                    .clip(RoundedCornerShape(Radius.cell))
                    .background(neutrals.ink)
                    .padding(horizontal = Space.s, vertical = Space.xs)
                    .testTag("month.hint"),
        )
    }
}

/** Text colors for days: Sunday and holidays red, Saturday blue (tone.md "날짜와 시간"). */
object DayColors {
    /** [column] is 0 for Sunday through 6 for Saturday. */
    @Composable
    fun weekday(column: Int): Color {
        val neutrals = LocalNeutralColors.current
        return when (column) {
            0 -> neutrals.sunday
            6 -> neutrals.saturday
            else -> neutrals.tertiaryText
        }
    }

    @Composable
    fun date(
        weekday: DayOfWeek,
        holiday: Boolean,
    ): Color {
        val neutrals = LocalNeutralColors.current
        return when {
            holiday || weekday == DayOfWeek.SUNDAY -> neutrals.sunday
            weekday == DayOfWeek.SATURDAY -> neutrals.saturday
            else -> neutrals.ink
        }
    }
}

/** What the lines of a cell hold: birthdays then events, with "+N" for what does not fit. */
sealed interface CellLine {
    data class Birthday(
        val item: BirthdayItem,
    ) : CellLine

    data class Event(
        val item: EventItem,
    ) : CellLine

    data class More(
        val count: Int,
    ) : CellLine

    companion object {
        /**
         * Three lines at most and two under a holiday or a leave mark; when they do not fit the last line becomes "+N"
         * for what is left (m1.md decision 29).
         */
        fun of(cell: DayCell): List<CellLine> {
            val items = cell.birthdays.map(::Birthday) + cell.events.map(::Event)
            val room = if (cell.holidays.isEmpty() && cell.leaveBridge == null) 3 else 2
            if (items.size <= room) return items
            return items.take(room - 1) + More(items.size - (room - 1))
        }
    }
}

/**
 * One day of the month grid (components.md §8): the date (today in an ink circle), the holiday in red, birthdays, and
 * up to three lines of event bars with "+N". The whole cell is one button that TalkBack reads as a sentence ("10월 30일
 * 금요일, 일정 2개, 민수 생일"); the bars are not read one by one (m1.md screen 4 "접근성"). A bar and the leave mark take
 * their own taps before the cell's quick entry, and TalkBack reaches them as the cell's actions.
 */
@Composable
private fun MonthCell(
    cell: DayCell,
    today: String,
    onClick: () -> Unit,
    onEvent: (EventItem) -> Unit,
    onLeave: () -> Unit,
) {
    val neutrals = LocalNeutralColors.current
    val resources = LocalResources.current
    val isToday = cell.day == today
    val label = cellLabel(cell, today, resources)
    val leaveLabel = stringResource(R.string.calendar_add_leave)
    val interaction = remember { MutableInteractionSource() }
    Column(
        verticalArrangement = Arrangement.spacedBy(2.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier =
            Modifier
                .fillMaxWidth()
                .height(Metrics.Grid.monthCell)
                .pressScale(interaction)
                .clipToBounds()
                .clickable(interactionSource = interaction, indication = null, onClick = onClick)
                // Ahead of clearAndSetSemantics, which drops whatever comes after it in the chain, a tag included.
                .testTag("month.day.${cell.day}")
                .clearAndSetSemantics {
                    contentDescription = label
                    role = Role.Button
                    customActions =
                        cell.events.map { event ->
                            CustomAccessibilityAction(event.title) {
                                onEvent(event)
                                true
                            }
                        } +
                        listOfNotNull(
                            cell.leaveBridge?.let {
                                CustomAccessibilityAction(leaveLabel) {
                                    onLeave()
                                    true
                                }
                            },
                        )
                }.padding(top = 4.dp, start = 1.dp, end = 1.dp),
    ) {
        DateMark(cell, isToday)
        cell.holidays.firstOrNull()?.let {
            Text(
                it.name,
                style = GeunalType.micro.copy(fontSize = 10.sp),
                color = neutrals.sunday,
                maxLines = 1,
                overflow = TextOverflow.Clip,
            )
        }
        if (cell.leaveBridge != null) LeaveMark(onClick = onLeave)
        CellLine.of(cell).forEach { line ->
            when (line) {
                is CellLine.Birthday -> BirthdayLine(line.item.name)
                is CellLine.Event -> EventBar(line.item, onClick = { onEvent(line.item) })
                is CellLine.More ->
                    Text(
                        stringResource(R.string.calendar_more, line.count),
                        style = GeunalType.micro,
                        color = neutrals.secondaryText,
                        modifier = Modifier.fillMaxWidth().padding(start = 2.dp),
                    )
            }
        }
    }
}

private fun cellLabel(
    cell: DayCell,
    today: String,
    resources: Resources,
): String {
    val parts = mutableListOf<String>()
    DayTitle.of(cell.day, today)?.let { parts += it.text(resources) }
    if (cell.day == today) parts += resources.getString(R.string.calendar_today)
    parts += cell.holidays.map { it.name }
    if (cell.events.isNotEmpty()) parts += resources.getString(R.string.calendar_events_count, cell.events.size)
    parts += cell.birthdays.map { resources.getString(R.string.calendar_birthday, it.name) }
    cell.leaveBridge?.let { parts += resources.getString(R.string.calendar_leave_bridge, it.daysOff.toInt()) }
    return parts.joinToString(", ")
}

@Composable
private fun DateMark(
    cell: DayCell,
    isToday: Boolean,
) {
    val neutrals = LocalNeutralColors.current
    val day = KoreanCalendar.parse(cell.day)?.dayOfMonth ?: 0
    val color =
        when {
            isToday -> neutrals.background
            !cell.inMonth -> neutrals.tertiaryText
            else -> DayColors.date(cell.weekday.dayOfWeek, holiday = cell.holidays.isNotEmpty())
        }
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier.size(24.dp).then(if (isToday) Modifier.background(neutrals.ink, CircleShape) else Modifier),
    ) {
        Text(
            "$day",
            style = GeunalType.caption.copy(fontWeight = if (cell.inMonth || isToday) FontWeight.SemiBold else FontWeight.Normal),
            color = color,
        )
    }
}

/** The leave day of a long weekend (rust-core.md §6 "공휴일과 연차 다리"), a dashed "연차" mark. */
@Composable
private fun LeaveMark(onClick: () -> Unit) {
    val sunday = LocalNeutralColors.current.sunday
    Text(
        stringResource(R.string.calendar_leave),
        style = GeunalType.micro.copy(fontSize = 10.sp),
        color = sunday,
        modifier =
            Modifier
                .clickable(onClick = onClick)
                .height(14.dp)
                .drawBehind {
                    drawRoundRect(
                        color = sunday,
                        cornerRadius = CornerRadius(size.height / 2),
                        style = Stroke(width = 1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(2.dp.toPx(), 2.dp.toPx()))),
                    )
                }.padding(horizontal = 4.dp),
    )
}

@Composable
fun BirthdayLine(
    name: String,
    modifier: Modifier = Modifier,
) {
    val neutrals = LocalNeutralColors.current
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier.fillMaxWidth().heightIn(min = Metrics.Grid.eventBar).padding(horizontal = 2.dp),
    ) {
        Icon(painterResource(DesignR.drawable.ds_ic_gift), contentDescription = null, tint = neutrals.ink, modifier = Modifier.size(9.dp))
        Spacer(Modifier.width(2.dp))
        Text(name, style = GeunalType.micro, color = neutrals.ink, maxLines = 1, overflow = TextOverflow.Clip)
    }
}

/**
 * An event bar (components.md §8): 16 high, radius 4, the calendar's second tint with ink text in 11. [onClick] opens
 * the event; the bar is a button of its own wherever its row does not speak for it.
 */
@Composable
fun EventBar(
    event: EventItem,
    onClick: (() -> Unit)? = null,
) {
    val dark = LocalDarkTheme.current
    val ink = LocalNeutralColors.current.ink
    Box(
        contentAlignment = Alignment.CenterStart,
        modifier =
            Modifier
                .fillMaxWidth()
                .height(Metrics.Grid.eventBar)
                .clip(RoundedCornerShape(Radius.strip))
                .then(if (onClick != null) Modifier.clickable(role = Role.Button, onClick = onClick) else Modifier),
    ) {
        EventFill(event.color, event.soft)
        Text(
            event.title,
            style = GeunalType.micro,
            color = CalendarColors.bar(event.color, dark, ink).text,
            maxLines = 1,
            overflow = TextOverflow.Clip,
            modifier = Modifier.padding(horizontal = 2.dp),
        )
    }
}
