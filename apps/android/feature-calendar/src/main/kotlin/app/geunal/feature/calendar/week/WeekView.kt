package app.geunal.feature.calendar.week

import android.content.res.Resources
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.geunal.core.TimedBlock
import app.geunal.core.WeekDay
import app.geunal.designsystem.GeunalType
import app.geunal.designsystem.LocalDarkTheme
import app.geunal.designsystem.LocalNeutralColors
import app.geunal.designsystem.Metrics
import app.geunal.designsystem.Radius
import app.geunal.designsystem.Space
import app.geunal.designsystem.components.pressScale
import app.geunal.feature.calendar.R
import app.geunal.feature.calendar.month.BirthdayLine
import app.geunal.feature.calendar.month.DayColors
import app.geunal.feature.calendar.month.EventBar
import app.geunal.feature.calendar.support.CalendarColors
import app.geunal.feature.calendar.support.DayText
import app.geunal.feature.calendar.support.DayTitle
import app.geunal.feature.calendar.support.EventFill
import app.geunal.feature.calendar.support.KoreanCalendar
import app.geunal.feature.calendar.support.column
import app.geunal.feature.calendar.support.dayOfWeek
import app.geunal.feature.calendar.support.text
import app.geunal.feature.calendar.support.weekdayShort
import kotlinx.coroutines.delay
import java.time.Instant
import kotlin.math.roundToInt

/**
 * The week view (m1.md screen 7, boards/Week): the days with their dates, the all-day row, and a 0–24 time axis that
 * opens at 8 o'clock. Pages follow the finger sideways a week at a time.
 */
@Composable
fun WeekView(
    model: WeekModel,
    pager: PagerState,
    /** A tap on a date in the header row. */
    onDay: (WeekDay) -> Unit,
    /** A long press on an empty hour: the day and the hour. */
    onSlot: (WeekDay, Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by model.state.collectAsStateWithLifecycle()
    HorizontalPager(
        state = pager,
        beyondViewportPageCount = 1,
        key = { model.weeks[it] },
        modifier = modifier,
    ) { page ->
        val week = model.weeks[page]
        LaunchedEffect(week) { model.load(week) }
        // The red line moves with the clock while the page is up.
        val nowMinute by produceState(model.nowMinute(week), week) {
            while (true) {
                delay(MINUTE_MS - Instant.now().toEpochMilli() % MINUTE_MS)
                value = model.nowMinute(week)
            }
        }
        val days = state.pages[week]
        if (days == null) {
            Spacer(Modifier.fillMaxSize())
        } else {
            WeekPage(days, state.today, nowMinute, onDay, onSlot)
        }
    }
}

@Composable
private fun WeekPage(
    days: List<WeekDay>,
    today: String,
    nowMinute: Int?,
    onDay: (WeekDay) -> Unit,
    onSlot: (WeekDay, Int) -> Unit,
) {
    val hourPx = with(LocalDensity.current) { Metrics.Grid.weekHour.toPx() }
    val scroll = rememberScrollState()
    LaunchedEffect(Unit) { scroll.scrollTo((OPENING_HOUR * hourPx).roundToInt()) }
    Column(Modifier.fillMaxSize()) {
        DateRow(days, today, onDay)
        AllDayRow(days)
        Column(Modifier.fillMaxSize().verticalScroll(scroll)) {
            BoxWithConstraints(Modifier.fillMaxWidth().height(Metrics.Grid.weekHour * 24)) {
                val column = (maxWidth - GUTTER) / 7
                HourLines()
                SlotTargets(days, column, onSlot)
                days.forEachIndexed { index, day ->
                    day.timed.forEach { block ->
                        val rect = BlockRect.of(block, index, column)
                        TimedBlockView(block, Modifier.offset(rect.x, rect.y).size(rect.width, rect.height))
                    }
                }
                val todayIndex = days.indexOfFirst { it.day == today }
                if (nowMinute != null && todayIndex >= 0) {
                    NowLine(
                        width = maxWidth - GUTTER,
                        dotX = column * todayIndex,
                        modifier = Modifier.offset(x = GUTTER, y = Metrics.Grid.weekHour * (nowMinute / 60f) - 4.dp),
                    )
                }
            }
            Spacer(Modifier.height(Space.s))
        }
    }
}

/** Where a block sits: its day's column split by the side-by-side columns the core gave it, from its start minute. */
internal data class BlockRect(
    val x: Dp,
    val y: Dp,
    val width: Dp,
    val height: Dp,
) {
    companion object {
        /** Never shorter than one line of title, and a point inside each edge so neighbours stay apart. */
        fun of(
            block: TimedBlock,
            day: Int,
            column: Dp,
        ): BlockRect {
            val columns = block.columns.toInt().coerceAtLeast(1)
            val width = column / columns
            val start = block.startMinute.toInt()
            val minutes = block.endMinute.toInt() - start
            return BlockRect(
                x = GUTTER + column * day + width * block.column.toInt() + 1.dp,
                y = Metrics.Grid.weekHour * (start / 60f) + 1.dp,
                width = width - 2.dp,
                height = maxOf(Metrics.Grid.weekHour * (minutes / 60f) - 2.dp, 18.dp),
            )
        }
    }
}

@Composable
private fun DateRow(
    days: List<WeekDay>,
    today: String,
    onDay: (WeekDay) -> Unit,
) {
    val neutrals = LocalNeutralColors.current
    val resources = LocalResources.current
    Row(Modifier.fillMaxWidth().padding(top = Space.xxs)) {
        Spacer(Modifier.width(GUTTER))
        days.forEach { day ->
            val isToday = day.day == today
            val weekday = day.weekday.dayOfWeek
            val holiday = day.holidays.isNotEmpty()
            val interaction = remember { MutableInteractionSource() }
            val label = dateLabel(day, today, resources)
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(4.dp),
                modifier =
                    Modifier
                        .weight(1f)
                        .heightIn(min = 56.dp)
                        .pressScale(interaction)
                        .clickable(interactionSource = interaction, indication = null) { onDay(day) }
                        .clearAndSetSemantics {
                            contentDescription = label
                            role = Role.Button
                        }.testTag("week.day.${day.day}"),
            ) {
                Text(
                    weekdayShort(resources, weekday),
                    style = GeunalType.caption.copy(fontSize = 12.sp, fontWeight = FontWeight.SemiBold),
                    color = if (holiday) neutrals.sunday else DayColors.weekday(weekday.column),
                )
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier.size(32.dp).then(if (isToday) Modifier.background(neutrals.ink, CircleShape) else Modifier),
                ) {
                    Text(
                        "${KoreanCalendar.parse(day.day)?.dayOfMonth ?: 0}",
                        style = GeunalType.bodyStrong.copy(fontFeatureSettings = "tnum"),
                        color = if (isToday) neutrals.background else DayColors.date(weekday, holiday),
                    )
                }
            }
        }
    }
}

private fun dateLabel(
    day: WeekDay,
    today: String,
    resources: Resources,
): String {
    val parts = mutableListOf<String>()
    DayTitle.of(day.day, today)?.let { parts += it.text(resources) }
    if (day.day == today) parts += resources.getString(R.string.calendar_today)
    parts += day.holidays.map { it.name }
    val count = day.allDay.size + day.timed.size + day.hidden.toInt()
    if (count > 0) parts += resources.getString(R.string.calendar_events_count, count)
    parts += day.birthdays.map { resources.getString(R.string.calendar_birthday, it.name) }
    return parts.joinToString(", ")
}

/**
 * Birthdays and all-day events above the axis, with the "+N" of timed events that did not fit three columns. TalkBack
 * hears all of it from the date buttons above, so the row is quiet.
 */
@Composable
private fun AllDayRow(days: List<WeekDay>) {
    val neutrals = LocalNeutralColors.current
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 26.dp)
            .drawBehind {
                val line = 1.dp.toPx()
                drawLine(neutrals.separator, Offset(0f, 0f), Offset(size.width, 0f), line)
                drawLine(neutrals.separator, Offset(0f, size.height), Offset(size.width, size.height), line)
            }.padding(vertical = 4.dp)
            .clearAndSetSemantics {},
    ) {
        Text(
            stringResource(R.string.calendar_all_day),
            style = GeunalType.micro.copy(fontWeight = FontWeight.Normal),
            color = neutrals.tertiaryText,
            modifier = Modifier.width(GUTTER).padding(start = Space.xs, top = 1.dp),
        )
        days.forEach { day ->
            Column(verticalArrangement = Arrangement.spacedBy(2.dp), modifier = Modifier.weight(1f).padding(horizontal = 1.dp)) {
                day.birthdays.forEach { BirthdayLine(it.name) }
                day.allDay.forEach { EventBar(it) }
                if (day.hidden > 0u) {
                    Text(
                        stringResource(R.string.calendar_more, day.hidden.toInt()),
                        style = GeunalType.micro,
                        color = neutrals.secondaryText,
                        modifier = Modifier.align(Alignment.CenterHorizontally),
                    )
                }
            }
        }
    }
}

/** An hour line every 48 with its number in the gutter, quiet for TalkBack. */
@Composable
private fun HourLines() {
    val neutrals = LocalNeutralColors.current
    Column(Modifier.fillMaxWidth().clearAndSetSemantics {}) {
        repeat(24) { hour ->
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(Metrics.Grid.weekHour)
                    .drawBehind {
                        val x = GUTTER.toPx()
                        drawLine(neutrals.fill, Offset(x, 0f), Offset(size.width, 0f), 1.dp.toPx())
                    },
            ) {
                if (hour > 0) {
                    Text(
                        "$hour",
                        style = GeunalType.micro.copy(fontWeight = FontWeight.Normal, fontFeatureSettings = "tnum"),
                        color = neutrals.tertiaryText,
                        modifier = Modifier.padding(start = Space.xs).offset(y = (-7).dp),
                    )
                }
            }
        }
    }
}

/**
 * Long-pressing an empty hour opens the quick entry at that hour (m1.md screen 7). A press on a block is not an empty
 * hour, so it is left alone.
 */
@Composable
private fun SlotTargets(
    days: List<WeekDay>,
    column: Dp,
    onSlot: (WeekDay, Int) -> Unit,
) {
    val current by rememberUpdatedState(days)
    val open by rememberUpdatedState(onSlot)
    val density = LocalDensity.current
    Box(
        Modifier
            .fillMaxSize()
            .clearAndSetSemantics {}
            .pointerInput(column) {
                detectTapGestures(
                    onLongPress = { position ->
                        val x = with(density) { position.x.toDp() }
                        val y = with(density) { position.y.toDp() }
                        if (x < GUTTER) return@detectTapGestures
                        val index = ((x - GUTTER) / column).toInt()
                        val day = current.getOrNull(index) ?: return@detectTapGestures
                        val onBlock =
                            day.timed.any { block ->
                                val rect = BlockRect.of(block, index, column)
                                x >= rect.x && x <= rect.x + rect.width && y >= rect.y && y <= rect.y + rect.height
                            }
                        if (!onBlock) open(day, (y / Metrics.Grid.weekHour).toInt().coerceIn(0, 23))
                    },
                )
            },
    )
}

/**
 * An event block (components.md §8): radius 8, tint 2 with a 2 calendar-color edge on the left, the title in 12 · 600
 * and the start time in 11 when there is room.
 */
@Composable
private fun TimedBlockView(
    block: TimedBlock,
    modifier: Modifier = Modifier,
) {
    val event = block.event
    val dark = LocalDarkTheme.current
    val ink = LocalNeutralColors.current.ink
    val text = CalendarColors.bar(event.color, dark, ink).text
    val start = block.startMinute.toInt()
    val end = block.endMinute.toInt()
    val label = "${event.title}, ${DayText.range(start, end)}"
    val tall = Metrics.Grid.weekHour * ((end - start) / 60f) >= 44.dp
    Box(
        modifier
            .clip(RoundedCornerShape(Radius.cell))
            .clearAndSetSemantics { contentDescription = label },
    ) {
        EventFill(event.color, event.soft)
        Box(Modifier.fillMaxHeight().width(2.dp).background(CalendarColors.marker(event.color)))
        Column(Modifier.padding(start = 6.dp, end = 3.dp, top = 3.dp, bottom = 3.dp)) {
            Text(
                event.title,
                style = GeunalType.micro.copy(fontSize = 12.sp, letterSpacing = (-0.3).sp),
                color = text,
                maxLines = 3,
                overflow = TextOverflow.Clip,
            )
            if (tall) {
                Text(
                    DayText.clock(if (event.allDay) 0 else start),
                    style = GeunalType.micro.copy(fontWeight = FontWeight.Normal, fontFeatureSettings = "tnum"),
                    color = text,
                )
            }
        }
    }
}

/** The red line at the current time with a dot where today's column starts (m1.md screen 7). */
@Composable
private fun NowLine(
    width: Dp,
    dotX: Dp,
    modifier: Modifier = Modifier,
) {
    val red = LocalNeutralColors.current.sunday
    Box(modifier.width(width).height(8.dp).clearAndSetSemantics {}) {
        Box(
            Modifier
                .align(Alignment.CenterStart)
                .fillMaxWidth()
                .height(1.5.dp)
                .background(red),
        )
        Box(Modifier.offset(x = dotX - 4.dp).size(8.dp).background(red, CircleShape))
    }
}

/** The time axis gutter on the left (boards/Week). */
internal val GUTTER = 40.dp

/** Where the axis opens: the boards show 8 o'clock at the top. */
private const val OPENING_HOUR = 8

private const val MINUTE_MS = 60_000L
