package app.geunal.feature.calendar.calendars

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.geunal.core.CalendarView
import app.geunal.designsystem.GeunalType
import app.geunal.designsystem.LocalNeutralColors
import app.geunal.designsystem.Metrics
import app.geunal.designsystem.Radius
import app.geunal.designsystem.Space
import app.geunal.designsystem.components.GeunalSwitch
import app.geunal.designsystem.components.NoticeCard
import app.geunal.designsystem.components.NoticeKind
import app.geunal.designsystem.components.SettingsDivider
import app.geunal.designsystem.components.SettingsGroup
import app.geunal.designsystem.label
import app.geunal.feature.calendar.R
import app.geunal.feature.calendar.support.DIMMED
import app.geunal.feature.calendar.support.EventFill
import app.geunal.feature.calendar.support.SwitchRow
import app.geunal.feature.calendar.support.groupColor
import kotlinx.coroutines.launch
import app.geunal.designsystem.R as DesignR

/**
 * 캘린더 관리 (m1.md screen 15, boards/Calendars), over the tabs: ‹ 나 · ＋, the title and why colors stay on this
 * screen, then "내 캘린더 · N개" with a color square (striped when soft), the name, its color and a show switch per
 * calendar, and "＋ 새 캘린더". A row opens the calendar sheet (screen 16). Under it "기기 캘린더", when the app hands
 * over its [mirror].
 */
@Composable
fun CalendarsScreen(
    model: CalendarsModel,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    mirror: DeviceCalendarMirror? = null,
) {
    val state by model.state.collectAsStateWithLifecycle()
    val editing by model.editing.collectAsStateWithLifecycle()
    val neutrals = LocalNeutralColors.current
    LaunchedEffect(model) { model.start() }
    Column(
        modifier
            .fillMaxSize()
            .background(neutrals.background)
            .statusBarsPadding()
            .navigationBarsPadding(),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().height(56.dp).padding(horizontal = Space.xxs),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier =
                    Modifier
                        .heightIn(min = Metrics.minHitTarget)
                        .clip(RoundedCornerShape(Radius.cell))
                        .clickable(role = Role.Button, onClick = onClose)
                        .padding(end = Space.xs)
                        .testTag("calendars.back"),
            ) {
                Icon(painterResource(DesignR.drawable.ds_ic_chevron_left), contentDescription = null, tint = neutrals.ink)
                Text(stringResource(R.string.me_back), style = GeunalType.body, color = neutrals.ink)
            }
            Spacer(Modifier.weight(1f))
            IconButton(onClick = { model.edit(null) }, modifier = Modifier.testTag("calendars.add")) {
                Icon(
                    painterResource(DesignR.drawable.ds_ic_add),
                    contentDescription = stringResource(R.string.calendars_new),
                    tint = neutrals.ink,
                )
            }
        }
        Column(
            verticalArrangement = Arrangement.spacedBy(Space.m),
            modifier =
                Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = Space.l)
                    .padding(bottom = Space.l),
        ) {
            Text(
                stringResource(R.string.calendars_title),
                style = GeunalType.largeTitle,
                color = neutrals.ink,
                modifier = Modifier.semantics { heading() },
            )
            Text(stringResource(R.string.calendars_note), style = GeunalType.secondary, color = neutrals.secondaryText)
            if (state.visibilityFailed) NoticeCard(stringResource(R.string.calendars_change_failed), kind = NoticeKind.Problem)
            if (state.hasLoaded) {
                Text(
                    stringResource(R.string.calendars_mine, state.calendars.size),
                    style = GeunalType.caption.copy(fontWeight = FontWeight.SemiBold),
                    color = neutrals.secondaryText,
                    modifier = Modifier.padding(start = Space.m, top = Space.xs).semantics { heading() },
                )
                SettingsGroup {
                    state.calendars.forEach { calendar ->
                        CalendarRow(
                            calendar = calendar,
                            onOpen = { model.edit(calendar) },
                            onShow = { model.setVisible(calendar.id, it) },
                        )
                        SettingsDivider()
                    }
                    NewCalendarRow(onClick = { model.edit(null) })
                }
            }
            if (mirror != null) DeviceCalendarsGroup(mirror)
        }
    }
    editing?.let { sheet -> CalendarEditSheet(model = sheet, onDismiss = model::closeEditor) }
}

/** A calendar: its square, name and color; the row opens the sheet and the switch shows or hides its events. */
@Composable
private fun CalendarRow(
    calendar: CalendarView,
    onOpen: () -> Unit,
    onShow: (Boolean) -> Unit,
) {
    val neutrals = LocalNeutralColors.current
    val resources = LocalResources.current
    val colorName =
        calendar.color
            ?.groupColor
            ?.label(resources)
            .orEmpty()
    val show = stringResource(R.string.calendars_show, calendar.name)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Space.s),
        modifier = Modifier.fillMaxWidth().heightIn(min = Metrics.Row.twoLine).padding(end = Space.s),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Space.s),
            modifier =
                Modifier
                    .weight(1f)
                    .heightIn(min = Metrics.Row.twoLine)
                    .clickable(role = Role.Button, onClick = onOpen)
                    .padding(start = Space.m, top = Space.xs, bottom = Space.xs)
                    .testTag("calendars.row.${calendar.id}"),
        ) {
            Box(Modifier.size(14.dp).clip(RoundedCornerShape(4.dp))) { EventFill(calendar.color, calendar.soft) }
            Column {
                Text(calendar.name, style = GeunalType.body, color = neutrals.ink)
                // "바다 · 일정 42개", with "소프트 캘린더" after a soft one's (m1.md screen 15).
                val count = calendar.eventCount.toInt()
                Text(
                    listOfNotNull(
                        colorName,
                        if (count > 0) stringResource(R.string.calendars_event_count, count) else null,
                        if (calendar.soft) stringResource(R.string.calendars_soft) else null,
                    ).joinToString(" · "),
                    style = GeunalType.caption,
                    color = neutrals.secondaryText,
                )
            }
        }
        GeunalSwitch(
            checked = calendar.visible,
            onCheckedChange = null,
            modifier =
                Modifier
                    .minimumInteractiveComponentSize()
                    .toggleable(value = calendar.visible, role = Role.Switch, onValueChange = onShow)
                    .semantics { contentDescription = show }
                    .testTag("calendars.show.${calendar.id}"),
        )
    }
}

/**
 * 기기 캘린더 (m1.md screen 15): the import switch, a checkbox for each of the phone's calendars, and what importing
 * means. The switch asks the system for access the first time; refused, it gives way to "설정에서 켜기". While the
 * switch is off, the list dims.
 */
@Composable
private fun DeviceCalendarsGroup(mirror: DeviceCalendarMirror) {
    val state by mirror.state.collectAsStateWithLifecycle()
    val neutrals = LocalNeutralColors.current
    val scope = rememberCoroutineScope()
    val asking =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            scope.launch { mirror.accessAnswered(granted) }
        }
    // On opening, and on coming back from the settings app where access may have been given or taken back.
    LifecycleResumeEffect(mirror) {
        scope.launch { mirror.load() }
        onPauseOrDispose { }
    }
    Text(
        stringResource(R.string.calendars_device),
        style = GeunalType.caption.copy(fontWeight = FontWeight.SemiBold),
        color = neutrals.secondaryText,
        modifier = Modifier.padding(start = Space.m, top = Space.xs).semantics { heading() },
    )
    SettingsGroup {
        if (state.access == DeviceCalendarAccess.DENIED) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Space.s),
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .heightIn(min = Metrics.Row.twoLine)
                        .clickable(role = Role.Button, onClick = mirror::openSettings)
                        .padding(horizontal = Space.m, vertical = Space.xs)
                        .testTag("calendars.device.settings"),
            ) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.calendars_device_import), style = GeunalType.body, color = neutrals.ink)
                    Text(stringResource(R.string.calendars_device_import_note), style = GeunalType.caption, color = neutrals.secondaryText)
                }
                Text(stringResource(R.string.calendars_device_settings), style = GeunalType.secondary, color = neutrals.ink)
            }
        } else {
            SwitchRow(
                title = stringResource(R.string.calendars_device_import),
                note = stringResource(R.string.calendars_device_import_note),
                checked = state.isOn,
                onChange = { on -> scope.launch { if (mirror.setOn(on)) asking.launch(Manifest.permission.READ_CALENDAR) } },
                modifier = Modifier.testTag("calendars.device.import"),
            )
        }
        if (state.loaded && state.access == DeviceCalendarAccess.GRANTED && state.calendars.isEmpty()) {
            SettingsDivider()
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().heightIn(min = Metrics.Row.setting).padding(horizontal = Space.m),
            ) {
                Text(stringResource(R.string.calendars_device_none), style = GeunalType.secondary, color = neutrals.secondaryText)
            }
        }
        state.calendars.forEach { calendar ->
            SettingsDivider()
            DeviceCalendarRow(
                calendar = calendar,
                ticked = calendar.id in state.selected,
                enabled = state.isOn && !state.isWorking,
                dimmed = !state.isOn,
                onToggle = { scope.launch { mirror.toggle(calendar.id) } },
            )
        }
    }
    if (state.failed) NoticeCard(stringResource(R.string.calendars_device_failed), kind = NoticeKind.Problem)
    Text(
        stringResource(R.string.calendars_device_note),
        style = GeunalType.caption,
        color = neutrals.secondaryText,
        modifier = Modifier.padding(horizontal = Space.xs),
    )
}

/** ☑ 계정 · 개인 · 일정 9개. */
@Composable
private fun DeviceCalendarRow(
    calendar: DeviceCalendarSummary,
    ticked: Boolean,
    enabled: Boolean,
    dimmed: Boolean,
    onToggle: () -> Unit,
) {
    val neutrals = LocalNeutralColors.current
    val box = RoundedCornerShape(4.dp)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Space.s),
        modifier =
            Modifier
                .fillMaxWidth()
                .heightIn(min = Metrics.Row.twoLine)
                .toggleable(value = ticked, enabled = enabled, role = Role.Checkbox, onValueChange = { onToggle() })
                .alpha(if (dimmed) DIMMED else 1f)
                .padding(horizontal = Space.m, vertical = Space.xs)
                .testTag("calendars.device.${calendar.id}"),
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier =
                Modifier
                    .size(20.dp)
                    .clip(box)
                    .then(if (ticked) Modifier.background(neutrals.ink) else Modifier.border(1.5.dp, neutrals.tertiaryText, box)),
        ) {
            if (ticked) {
                Icon(
                    painterResource(DesignR.drawable.ds_ic_check),
                    contentDescription = null,
                    tint = neutrals.background,
                    modifier = Modifier.size(16.dp),
                )
            }
        }
        Column {
            Text(calendar.name, style = GeunalType.body, color = neutrals.ink)
            Text(
                stringResource(R.string.calendars_event_count, calendar.eventCount),
                style = GeunalType.caption,
                color = neutrals.secondaryText,
            )
        }
    }
}

@Composable
private fun NewCalendarRow(onClick: () -> Unit) {
    val neutrals = LocalNeutralColors.current
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Space.s),
        modifier =
            Modifier
                .fillMaxWidth()
                .heightIn(min = Metrics.Row.setting)
                .clickable(role = Role.Button, onClick = onClick)
                .padding(horizontal = Space.m)
                .testTag("calendars.new"),
    ) {
        Icon(
            painterResource(DesignR.drawable.ds_ic_add),
            contentDescription = null,
            tint = neutrals.ink,
            modifier = Modifier.size(18.dp),
        )
        Text(stringResource(R.string.calendars_new), style = GeunalType.body, color = neutrals.ink)
    }
}
