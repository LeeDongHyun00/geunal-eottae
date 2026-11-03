package app.geunal.feature.calendar.screen

import android.content.res.Resources
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.geunal.core.AlertRule
import app.geunal.core.BirthdayItem
import app.geunal.core.DayTime
import app.geunal.core.EventInput
import app.geunal.core.EventItem
import app.geunal.core.Holiday
import app.geunal.designsystem.GeunalType
import app.geunal.designsystem.LocalNeutralColors
import app.geunal.designsystem.Space
import app.geunal.designsystem.components.SegmentedControl
import app.geunal.feature.calendar.R
import app.geunal.feature.calendar.agenda.AgendaView
import app.geunal.feature.calendar.editor.EditorTarget
import app.geunal.feature.calendar.month.MonthView
import app.geunal.feature.calendar.quickentry.QuickEntryModel
import app.geunal.feature.calendar.quickentry.QuickEntrySheet
import app.geunal.feature.calendar.support.Heading
import app.geunal.feature.calendar.support.KoreanCalendar
import app.geunal.feature.calendar.support.caption
import app.geunal.feature.calendar.support.title
import app.geunal.feature.calendar.week.WeekView
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import java.time.Duration
import java.time.Instant
import app.geunal.designsystem.R as DesignR

/**
 * The calendar tab (m1.md screens 4–8): the header with the 월·주·목록 segment, the chosen view, and the quick entry
 * sheet a day opens. The view last shown comes back on the next launch (m1.md §2.2). [onEdit] and [onStamp] open the
 * editor and the shift stamp screen over the tabs.
 */
@Composable
fun CalendarScreen(
    model: CalendarViewModel,
    onEdit: (EditorTarget) -> Unit,
    onStamp: (month: String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val kind by model.kind.collectAsStateWithLifecycle()
    val monthState by model.month.state.collectAsStateWithLifecycle()
    val weekState by model.week.state.collectAsStateWithLifecycle()
    val agendaState by model.agenda.state.collectAsStateWithLifecycle()
    val resources = LocalResources.current
    var entry by remember { mutableStateOf<QuickEntryModel?>(null) }
    val open = { day: String, notes: List<String>, range: IntRange? -> entry = model.quickEntry(day, notes, range) }
    val edit = { event: EventItem -> onEdit(EditorTarget.Existing(event.eventId, event.occurrence)) }
    val leave = resources.getString(R.string.calendar_leave)
    // Coming back to the app, or midnight while it is in front: a new today means reading the views again.
    LifecycleResumeEffect(model) {
        model.refreshIfNewDay()
        onPauseOrDispose { }
    }
    LaunchedEffect(model) {
        while (true) {
            delay(untilMidnight())
            model.refreshIfNewDay()
        }
    }
    val heading =
        when (kind) {
            CalendarViewKind.MONTH -> Heading.month(monthState.visibleMonth)
            CalendarViewKind.WEEK -> Heading.week(weekState.visibleWeek)
            CalendarViewKind.LIST -> KoreanCalendar.parse(agendaState.today)?.let { Heading.Upcoming(it.year) }
        }
    Column(modifier.fillMaxSize()) {
        CalendarHeader(
            kind = kind,
            caption = heading?.caption(resources).orEmpty(),
            title = heading?.title(resources).orEmpty(),
            onTitle = if (kind == CalendarViewKind.LIST) null else model::showToday,
            onSelect = model::select,
            onNewEvent = if (kind == CalendarViewKind.LIST) null else ({ onEdit(EditorTarget.New(model.newEventDay())) }),
            onStamp = { onStamp(model.stampMonth()) },
        )
        when (kind) {
            CalendarViewKind.MONTH -> {
                // Made with the view, so a switch from the week view opens on the month that week was in.
                val monthPager =
                    rememberPagerState(initialPage = model.month.index(of = monthState.visibleMonth)) { model.month.months.size }
                FollowPager(
                    monthPager,
                    target = model.month.index(of = monthState.visibleMonth),
                ) { model.month.show(model.month.months[it]) }
                // Six weeks of 88 are taller than a small phone; the page scrolls rather than squeezing the cells.
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                    MonthView(
                        model = model.month,
                        pager = monthPager,
                        onDay = { cell -> open(cell.day, notes(cell.birthdays, cell.holidays, resources), null) },
                        onEvent = edit,
                        // The leave mark fills an all-day '연차' (rust-core.md §6 "공휴일과 연차 다리").
                        onLeave = { cell -> onEdit(EditorTarget.New(cell.day, leaveInput(cell.day, leave))) },
                    )
                }
            }
            CalendarViewKind.WEEK -> {
                val weekPager = rememberPagerState(initialPage = model.week.index(of = weekState.visibleWeek)) { model.week.weeks.size }
                FollowPager(weekPager, target = model.week.index(of = weekState.visibleWeek)) { model.week.show(model.week.weeks[it]) }
                WeekView(
                    model = model.week,
                    pager = weekPager,
                    onDay = { day -> open(day.day, notes(day.birthdays, day.holidays, resources), null) },
                    onSlot = { day, hour -> open(day.day, notes(day.birthdays, day.holidays, resources), (hour * 2)..(hour * 2 + 1)) },
                    onEvent = edit,
                    modifier = Modifier.weight(1f),
                )
            }
            CalendarViewKind.LIST ->
                AgendaView(
                    model = model.agenda,
                    onDay = { day -> open(day.day, notes(day.birthdays, day.holidays, resources), null) },
                    onAddToday = { open(model.today, emptyList(), null) },
                    onEvent = edit,
                    modifier = Modifier.weight(1f),
                )
        }
    }
    entry?.let { quickEntry ->
        QuickEntrySheet(
            model = quickEntry,
            actions = model.actions,
            onDismiss = { entry = null },
            onMore = { input ->
                entry = null
                onEdit(EditorTarget.New(quickEntry.day, input))
            },
        )
    }
}

/**
 * Keeps a pager and its model on the same page both ways: a swipe tells the model, and the model's own moves (the
 * header's "오늘로", a switch of view) turn the pager.
 */
@Composable
private fun FollowPager(
    pager: PagerState,
    target: Int,
    onSettled: (Int) -> Unit,
) {
    LaunchedEffect(pager) {
        snapshotFlow { pager.settledPage }.distinctUntilChanged().collect(onSettled)
    }
    LaunchedEffect(target) {
        if (pager.settledPage != target) pager.animateScrollToPage(target)
    }
}

/** An all-day '연차' on [day], in the first own calendar the editor picks. */
private fun leaveInput(
    day: String,
    title: String,
) = EventInput(
    calendarId = "",
    title = title,
    allDay = true,
    start = DayTime(day, 0u),
    end = DayTime(day, 0u),
    recurrence = null,
    alert = AlertRule.Inherit,
    note = null,
)

/** The line under the quick entry's title: "민수 생일 · 개천절". */
private fun notes(
    birthdays: List<BirthdayItem>,
    holidays: List<Holiday>,
    resources: Resources,
): List<String> = birthdays.map { resources.getString(R.string.calendar_birthday, it.name) } + holidays.map { it.name }

private fun untilMidnight(): Long {
    val now = Instant.now()
    val tomorrow = KoreanCalendar.addDays(KoreanCalendar.day(now), 1)?.let(KoreanCalendar::startOf) ?: return DAY_MS
    // A second past midnight, so the new day is surely here.
    return Duration.between(now, tomorrow).toMillis().coerceAtLeast(0) + 1_000
}

private const val DAY_MS = 86_400_000L

/**
 * The header of the three views (boards/Month, Week, List): the year (13 · 600) over the month or range (32), the
 * segment, and "＋", a menu of a new event and the shift stamp screen (m1.md §2.3). Tapping the title goes back to
 * today.
 */
@Composable
private fun CalendarHeader(
    kind: CalendarViewKind,
    caption: String,
    title: String,
    onTitle: (() -> Unit)?,
    onSelect: (CalendarViewKind) -> Unit,
    onNewEvent: (() -> Unit)?,
    onStamp: () -> Unit,
) {
    val neutrals = LocalNeutralColors.current
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Space.xxs),
        modifier =
            Modifier
                .fillMaxWidth()
                .heightIn(min = 64.dp)
                .padding(start = Space.l, end = Space.xs),
    ) {
        Column(
            modifier =
                Modifier
                    .weight(1f)
                    .then(
                        if (onTitle != null) {
                            Modifier.clickable(onClickLabel = stringResource(R.string.calendar_to_today), onClick = onTitle)
                        } else {
                            Modifier
                        },
                    ).semantics(mergeDescendants = true) { heading() }
                    .testTag("calendar.title"),
        ) {
            Text(
                caption,
                style = GeunalType.caption.copy(fontWeight = FontWeight.SemiBold, fontSize = 13.sp),
                color = neutrals.secondaryText,
            )
            Text(title, style = GeunalType.largeTitle, color = neutrals.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Spacer(Modifier.width(Space.xs))
        SegmentedControl(
            options =
                listOf(
                    stringResource(R.string.calendar_view_month),
                    stringResource(R.string.calendar_view_week),
                    stringResource(R.string.calendar_view_list),
                ),
            selected = kind.ordinal,
            onSelect = { onSelect(CalendarViewKind.entries[it]) },
            modifier = Modifier.width(SEGMENT_WIDTH).testTag("calendar.segment"),
        )
        if (onNewEvent != null) {
            AddMenu(onNewEvent = onNewEvent, onStamp = onStamp)
        }
    }
}

@Composable
private fun AddMenu(
    onNewEvent: () -> Unit,
    onStamp: () -> Unit,
) {
    val ink = LocalNeutralColors.current.ink
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }, modifier = Modifier.testTag("calendar.add")) {
            Icon(
                painterResource(DesignR.drawable.ds_ic_add),
                contentDescription = stringResource(R.string.calendar_add_menu),
                tint = ink,
                modifier = Modifier.size(24.dp),
            )
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.calendar_add), style = GeunalType.body) },
                leadingIcon = { Icon(painterResource(DesignR.drawable.ds_ic_calendar), contentDescription = null, tint = ink) },
                onClick = {
                    open = false
                    onNewEvent()
                },
                modifier = Modifier.testTag("calendar.add.event"),
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.calendar_stamp_shifts), style = GeunalType.body) },
                leadingIcon = { Icon(painterResource(DesignR.drawable.ds_ic_grid), contentDescription = null, tint = ink) },
                onClick = {
                    open = false
                    onStamp()
                },
                modifier = Modifier.testTag("calendar.add.shifts"),
            )
        }
    }
}

/** Three short labels, as wide as the boards draw the segment. */
private val SEGMENT_WIDTH = 132.dp
