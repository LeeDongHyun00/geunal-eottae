package app.geunal.feature.calendar.agenda

import android.content.res.Resources
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.geunal.core.AgendaDay
import app.geunal.core.EventItem
import app.geunal.core.EventSource
import app.geunal.core.Palette
import app.geunal.designsystem.GeunalType
import app.geunal.designsystem.LocalNeutralColors
import app.geunal.designsystem.Metrics
import app.geunal.designsystem.Space
import app.geunal.designsystem.components.ButtonKind
import app.geunal.designsystem.components.GeunalButton
import app.geunal.feature.calendar.R
import app.geunal.feature.calendar.support.CalendarColors
import app.geunal.feature.calendar.support.DayText
import app.geunal.feature.calendar.support.DayTitle
import app.geunal.feature.calendar.support.EventFill
import app.geunal.feature.calendar.support.RepeatText
import app.geunal.feature.calendar.support.text
import kotlinx.coroutines.launch

/**
 * The list view (m1.md screen 8, boards/List): a heading per day, then a 52 row per event or birthday with the
 * calendar's dot, a caption ("학교 · 매주 월·수") and the time. It opens at today; the weeks before sit above it in grey,
 * and more weeks load at either end.
 */
@Composable
fun AgendaView(
    model: AgendaModel,
    onDay: (AgendaDay) -> Unit,
    onAddToday: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by model.state.collectAsStateWithLifecycle()
    val list = rememberLazyListState()
    val scope = rememberCoroutineScope()
    // The top edge loads earlier weeks only after the list has moved to today, or it would load at once.
    var settled by remember { mutableStateOf(false) }
    val past = state.past
    val upcoming = state.upcoming
    LaunchedEffect(state.hasLoaded) {
        if (!state.hasLoaded || settled) return@LaunchedEffect
        // The first day from today on, or the empty state, which both sit right after the past days.
        list.scrollToItem((if (state.canLoadEarlier) 1 else 0) + past.size)
        settled = true
    }
    LazyColumn(
        state = list,
        contentPadding = PaddingValues(start = Space.l, end = Space.l, bottom = Space.l),
        modifier = modifier.fillMaxSize().testTag("agenda"),
    ) {
        if (state.canLoadEarlier) {
            item(key = "earlier") {
                LaunchedEffect(settled) { if (settled) scope.launch { model.loadEarlier() } }
                Box(Modifier.fillMaxWidth().heightIn(min = 1.dp))
            }
        }
        past.forEach { day ->
            item(key = day.day) { AgendaSection(day, state.today, past = true, onDay = onDay) }
        }
        if (state.hasLoaded && state.isEmpty) {
            item(key = "empty") { EmptyAgenda(onAddToday) }
        }
        upcoming.forEach { day ->
            item(key = day.day) { AgendaSection(day, state.today, past = false, onDay = onDay) }
        }
        if (state.hasLoaded && state.canLoadMore) {
            item(key = "more") {
                LaunchedEffect(state.days.size) { model.loadMore() }
                Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxWidth().heightIn(min = Metrics.minHitTarget)) {
                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = LocalNeutralColors.current.tertiaryText)
                }
            }
        }
    }
}

@Composable
private fun EmptyAgenda(onAddToday: () -> Unit) {
    val neutrals = LocalNeutralColors.current
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(Space.m),
        modifier = Modifier.fillMaxWidth().padding(vertical = Space.xxxl),
    ) {
        Text(stringResource(R.string.calendar_agenda_empty), style = GeunalType.body, color = neutrals.secondaryText)
        GeunalButton(
            text = stringResource(R.string.calendar_agenda_add_today),
            onClick = onAddToday,
            kind = ButtonKind.Secondary,
            height = Metrics.Button.secondary,
            fullWidth = false,
            modifier = Modifier.testTag("agenda.addToday"),
        )
    }
}

@Composable
private fun AgendaSection(
    day: AgendaDay,
    today: String,
    past: Boolean,
    onDay: (AgendaDay) -> Unit,
) {
    val neutrals = LocalNeutralColors.current
    val resources = LocalResources.current
    val color =
        when {
            past -> neutrals.tertiaryText
            day.day == today || day.holidays.isNotEmpty() -> neutrals.sunday
            else -> neutrals.secondaryText
        }
    Column(Modifier.testTag("agenda.day.${day.day}")) {
        Text(
            heading(day, today, resources),
            style = GeunalType.caption.copy(fontWeight = FontWeight.SemiBold),
            color = color,
            modifier =
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = 32.dp)
                    .clickable(onClickLabel = stringResource(R.string.calendar_agenda_add_hint)) { onDay(day) }
                    .semantics { heading() }
                    .padding(top = 10.dp, bottom = 4.dp),
        )
        day.birthdays.forEach { birthday ->
            AgendaRow(
                dot = Dot.Ring,
                title = stringResource(R.string.calendar_birthday, birthday.name),
                caption = stringResource(R.string.calendar_agenda_people),
                time = stringResource(R.string.calendar_all_day),
                past = past,
            )
        }
        day.events.forEach { event ->
            AgendaRow(
                dot = if (event.soft) Dot.Soft(event.color) else Dot.Solid(event.color),
                title = event.title,
                caption = caption(event, resources),
                time = if (event.allDay) stringResource(R.string.calendar_all_day) else DayText.clock(event.start.minute.toInt()),
                past = past,
            )
        }
    }
}

/** "오늘 · 10월 25일 일요일", with the day's holidays after a middle dot. */
private fun heading(
    day: AgendaDay,
    today: String,
    resources: Resources,
): String {
    val title = DayTitle.of(day.day, today)?.text(resources) ?: day.day
    val head = if (day.day == today) resources.getString(R.string.calendar_agenda_today, title) else title
    return (listOf(head) + day.holidays.map { it.name }).joinToString(" · ")
}

/** "학교 · 매주 월·수", "운동 · 소프트 캘린더", "기기 캘린더 · 읽기 전용", or the calendar alone. */
internal fun caption(
    event: EventItem,
    resources: Resources,
): String {
    if (event.source is EventSource.Device) return resources.getString(R.string.calendar_agenda_device)
    if (event.soft) return resources.getString(R.string.calendar_agenda_soft, event.calendarName)
    val recurrence = event.recurrence ?: return event.calendarName
    val rule = RepeatText.of(recurrence, event.start.day).text(resources)
    return resources.getString(R.string.calendar_agenda_caption, event.calendarName, rule)
}

private sealed interface Dot {
    data class Solid(
        val palette: Palette?,
    ) : Dot

    data class Soft(
        val palette: Palette?,
    ) : Dot

    /** A birthday: an ink ring (boards/List). */
    data object Ring : Dot
}

@Composable
private fun AgendaRow(
    dot: Dot,
    title: String,
    caption: String,
    time: String,
    past: Boolean,
) {
    val neutrals = LocalNeutralColors.current
    val label = "$title, $caption, $time"
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Space.s),
        modifier =
            Modifier
                .fillMaxWidth()
                .heightIn(min = 52.dp)
                .drawBehind { drawLine(neutrals.fill, Offset(0f, size.height), Offset(size.width, size.height), 1.dp.toPx()) }
                .clearAndSetSemantics { contentDescription = label },
    ) {
        Box(Modifier.size(10.dp).alpha(if (past) 0.4f else 1f)) {
            when (dot) {
                is Dot.Solid -> Box(Modifier.fillMaxSize().background(CalendarColors.marker(dot.palette), CircleShape))
                is Dot.Soft -> Box(Modifier.fillMaxSize().clip(CircleShape)) { EventFill(dot.palette, soft = true) }
                Dot.Ring -> Box(Modifier.fillMaxSize().border(1.5.dp, neutrals.ink, CircleShape))
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(1.dp), modifier = Modifier.weight(1f)) {
            Text(title, style = GeunalType.bodyStrong, color = if (past) neutrals.tertiaryText else neutrals.ink, maxLines = 1)
            Text(caption, style = GeunalType.caption, color = if (past) neutrals.tertiaryText else neutrals.secondaryText, maxLines = 1)
        }
        Text(
            time,
            style = GeunalType.secondary.copy(fontFeatureSettings = "tnum"),
            color = if (past) neutrals.tertiaryText else neutrals.secondaryText,
        )
    }
}
