package app.geunal.feature.calendar.editor

import android.content.res.Resources
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.TimePickerDialog
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.geunal.core.EditScope
import app.geunal.designsystem.GeunalType
import app.geunal.designsystem.LocalNeutralColors
import app.geunal.designsystem.Metrics
import app.geunal.designsystem.Radius
import app.geunal.designsystem.Space
import app.geunal.designsystem.components.GeunalButton
import app.geunal.designsystem.components.GeunalSwitch
import app.geunal.designsystem.components.NoticeCard
import app.geunal.designsystem.components.NoticeKind
import app.geunal.designsystem.components.pressScale
import app.geunal.feature.calendar.R
import app.geunal.feature.calendar.support.CalendarColors
import app.geunal.feature.calendar.support.DayText
import app.geunal.feature.calendar.support.KoreanCalendar
import app.geunal.feature.calendar.support.RepeatText
import app.geunal.feature.calendar.support.text
import app.geunal.feature.calendar.support.weekdayShort
import java.time.Instant
import java.time.ZoneOffset
import app.geunal.designsystem.R as DesignR

/** The questions the editor asks in a sheet (m1.md decision 4: never a platform alert). */
private enum class EditorQuestion { SAVE_SCOPE, DELETE, CUSTOM_REPEAT }

/**
 * The event editor (m1.md screen 9, boards/Editor), full-screen above the tabs: 취소 · 일정 편집 · 저장, then the title
 * and calendar, the times, repeat and alert, and "일정 삭제". A device calendar's event opens read-only. [onClose] runs
 * on 취소 and after a save or delete went through.
 */
@Composable
fun EditorScreen(
    model: EditorModel,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by model.state.collectAsStateWithLifecycle()
    val neutrals = LocalNeutralColors.current
    var question by rememberSaveable { mutableStateOf<EditorQuestion?>(null) }
    LaunchedEffect(model) { model.start() }
    val enabled = state.hasLoaded && !state.readOnly && state.problem != EditorModel.Problem.NOT_FOUND
    val save = { scope: EditScope -> model.launchSave(scope) { if (it) onClose() } }
    val delete = { scope: EditScope -> model.launchDelete(scope) { if (it) onClose() } }
    Column(
        modifier
            .fillMaxSize()
            .background(neutrals.groupedBackground)
            .statusBarsPadding()
            .navigationBarsPadding()
            .imePadding(),
    ) {
        EditorTopBar(
            title = stringResource(if (model.isNew) R.string.editor_new else R.string.editor_edit),
            readOnly = state.readOnly,
            canSave = state.canSave,
            onCancel = onClose,
            onSave = { if (model.asksScope) question = EditorQuestion.SAVE_SCOPE else save(EditScope.THIS_AND_FOLLOWING) },
        )
        Column(
            verticalArrangement = Arrangement.spacedBy(Space.m),
            modifier =
                Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = Space.m, vertical = Space.s),
        ) {
            if (state.readOnly) {
                NoticeCard(
                    text = stringResource(R.string.editor_device),
                    kind = NoticeKind.Offline,
                    icon = painterResource(DesignR.drawable.ds_ic_lock),
                )
            }
            when (state.problem) {
                EditorModel.Problem.NOT_FOUND -> NoticeCard(stringResource(R.string.editor_not_found), kind = NoticeKind.Problem)
                EditorModel.Problem.SAVE_FAILED -> NoticeCard(stringResource(R.string.calendar_save_failed), kind = NoticeKind.Problem)
                EditorModel.Problem.DELETE_FAILED -> NoticeCard(stringResource(R.string.editor_delete_failed), kind = NoticeKind.Problem)
                null -> Unit
            }
            TitleCard(state, model, enabled)
            TimeCard(state, model, enabled)
            RepeatCard(state, model, enabled, onCustom = { question = EditorQuestion.CUSTOM_REPEAT })
            AttachmentsCard()
            state.selectedCalendar?.let { calendar ->
                Text(
                    stringResource(R.string.editor_sharing, calendar.name),
                    style = GeunalType.caption.copy(lineHeight = 19.sp),
                    color = neutrals.secondaryText,
                    modifier = Modifier.padding(horizontal = Space.xxs),
                )
            }
            if (!model.isNew && enabled) {
                DeleteButton(onClick = { question = EditorQuestion.DELETE })
            }
        }
    }
    when (question) {
        EditorQuestion.SAVE_SCOPE ->
            ScopeSheet(
                title = stringResource(R.string.editor_scope_save),
                thisOnly = stringResource(R.string.editor_scope_this_only),
                following = stringResource(R.string.editor_scope_following),
                destructive = false,
                onChoose = {
                    question = null
                    save(it)
                },
                onDismiss = { question = null },
            )
        EditorQuestion.DELETE ->
            if (model.asksScope) {
                ScopeSheet(
                    title = stringResource(R.string.editor_scope_delete),
                    thisOnly = stringResource(R.string.editor_scope_delete_this_only),
                    following = stringResource(R.string.editor_scope_delete_following),
                    destructive = true,
                    onChoose = {
                        question = null
                        delete(it)
                    },
                    onDismiss = { question = null },
                )
            } else {
                DeleteSheet(
                    onConfirm = {
                        question = null
                        delete(EditScope.THIS_AND_FOLLOWING)
                    },
                    onDismiss = { question = null },
                )
            }
        EditorQuestion.CUSTOM_REPEAT ->
            CustomRepeatSheet(
                recurrence = state.recurrence,
                startDay = state.startDay,
                onDone = {
                    model.setRecurrence(it)
                    question = null
                },
                onDismiss = { question = null },
            )
        null -> Unit
    }
}

/** 취소 (or 닫기 when nothing can change) · the title · the ink "저장" pill. */
@Composable
private fun EditorTopBar(
    title: String,
    readOnly: Boolean,
    canSave: Boolean,
    onCancel: () -> Unit,
    onSave: () -> Unit,
) {
    val neutrals = LocalNeutralColors.current
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier =
            Modifier
                .fillMaxWidth()
                .height(56.dp)
                .padding(start = Space.xs, end = Space.s),
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier =
                Modifier
                    .heightIn(min = Metrics.minHitTarget)
                    .widthIn(min = Metrics.minHitTarget)
                    .clip(RoundedCornerShape(Radius.cell))
                    .clickable(role = Role.Button, onClick = onCancel)
                    .padding(horizontal = Space.xs)
                    .testTag("editor.cancel"),
        ) {
            Text(
                stringResource(if (readOnly) R.string.editor_close else R.string.editor_cancel),
                style = GeunalType.body,
                color = neutrals.secondaryText,
            )
        }
        Text(
            title,
            style = GeunalType.bodyStrong,
            color = neutrals.ink,
            textAlign = TextAlign.Center,
            modifier = Modifier.weight(1f).semantics { heading() },
        )
        if (readOnly) {
            Spacer(Modifier.width(64.dp))
        } else {
            GeunalButton(
                text = stringResource(R.string.editor_save),
                onClick = onSave,
                enabled = canSave,
                height = Metrics.Button.smallPill,
                fullWidth = false,
                modifier = Modifier.testTag("editor.save"),
            )
        }
    }
}

@Composable
private fun EditorCard(content: @Composable () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Radius.input))
            .background(LocalNeutralColors.current.background),
    ) { content() }
}

@Composable
private fun CardDivider() {
    HorizontalDivider(thickness = 1.dp, color = LocalNeutralColors.current.fill)
}

@Composable
private fun TitleCard(
    state: EditorModel.State,
    model: EditorModel,
    enabled: Boolean,
) {
    val neutrals = LocalNeutralColors.current
    val placeholder = stringResource(R.string.editor_title)
    val style = GeunalType.bodyStrong.copy(fontSize = 20.sp, color = neutrals.ink)
    var calendarsOpen by remember { mutableStateOf(false) }
    EditorCard {
        BasicTextField(
            value = state.title,
            onValueChange = model::setTitle,
            enabled = enabled,
            singleLine = true,
            textStyle = style,
            cursorBrush = SolidColor(neutrals.ink),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            modifier =
                Modifier
                    .fillMaxWidth()
                    .height(56.dp)
                    // An empty field says what it is for; a filled one reads what was typed.
                    .semantics { if (state.title.isEmpty()) contentDescription = placeholder }
                    .testTag("editor.title"),
            decorationBox = { field ->
                Box(contentAlignment = Alignment.CenterStart, modifier = Modifier.padding(horizontal = Space.m)) {
                    if (state.title.isEmpty()) Text(placeholder, style = style, color = neutrals.tertiaryText)
                    field()
                }
            },
        )
        CardDivider()
        Box {
            val selected = state.selectedCalendar
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Space.xs),
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp)
                        .clickable(enabled = enabled, role = Role.Button) { calendarsOpen = true }
                        .padding(horizontal = Space.m)
                        .testTag("editor.calendar"),
            ) {
                Text(
                    stringResource(R.string.editor_calendar),
                    style = GeunalType.body,
                    color = neutrals.ink,
                    modifier = Modifier.weight(1f),
                )
                Box(Modifier.size(10.dp).background(CalendarColors.marker(selected?.color), CircleShape))
                Text(selected?.name.orEmpty(), style = GeunalType.body, color = neutrals.secondaryText)
                Chevron()
            }
            DropdownMenu(expanded = calendarsOpen, onDismissRequest = { calendarsOpen = false }) {
                state.ownCalendars.forEach { calendar ->
                    DropdownMenuItem(
                        text = { Text(calendar.name, style = GeunalType.body) },
                        leadingIcon = {
                            Box(
                                Modifier.size(Metrics.Chip.dot).background(CalendarColors.marker(calendar.color), CircleShape),
                            )
                        },
                        trailingIcon = { if (calendar.id == selected?.id) Check() },
                        onClick = {
                            model.setCalendar(calendar.id)
                            calendarsOpen = false
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun TimeCard(
    state: EditorModel.State,
    model: EditorModel,
    enabled: Boolean,
) {
    val neutrals = LocalNeutralColors.current
    val (endDay, endMinute) = state.shownEnd
    EditorCard {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier =
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .toggleable(value = state.allDay, enabled = enabled, role = Role.Switch, onValueChange = model::setAllDay)
                    .padding(horizontal = Space.m)
                    .testTag("editor.allDay"),
        ) {
            Text(stringResource(R.string.editor_all_day), style = GeunalType.body, color = neutrals.ink, modifier = Modifier.weight(1f))
            // The row takes the tap and reads as the switch; the switch only shows the state.
            GeunalSwitch(checked = state.allDay, onCheckedChange = null, enabled = enabled)
        }
        CardDivider()
        TimeRow(
            label = stringResource(R.string.editor_start),
            day = state.startDay,
            minute = state.startMinute,
            allDay = state.allDay,
            enabled = enabled,
            firstDay = null,
            onDay = { model.setStart(it, state.startMinute) },
            onMinute = { model.setStart(state.startDay, it) },
            tag = "editor.start",
        )
        CardDivider()
        TimeRow(
            label = stringResource(R.string.editor_end),
            day = endDay,
            minute = endMinute,
            allDay = state.allDay,
            enabled = enabled,
            firstDay = state.startDay,
            onDay = { model.setEnd(it, endMinute) },
            onMinute = { model.setEnd(endDay, it) },
            tag = "editor.end",
        )
    }
}

/** "시작" with its date chip and, unless all day, its time chip; each opens the platform picker in Korean days. */
@Composable
private fun TimeRow(
    label: String,
    day: String,
    minute: Int,
    allDay: Boolean,
    enabled: Boolean,
    firstDay: String?,
    onDay: (String) -> Unit,
    onMinute: (Int) -> Unit,
    tag: String,
) {
    val resources = LocalResources.current
    var picking by remember { mutableStateOf<Picker?>(null) }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Space.xs),
        modifier =
            Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .padding(horizontal = Space.m),
    ) {
        Text(label, style = GeunalType.body, color = LocalNeutralColors.current.ink, modifier = Modifier.weight(1f))
        val dayText = chipDay(day, resources)
        PickerChip(
            text = dayText,
            description = "$label $dayText",
            clickLabel = stringResource(R.string.editor_change_day),
            enabled = enabled,
            onClick = { picking = Picker.DAY },
            modifier = Modifier.testTag("$tag.day"),
        )
        if (!allDay) {
            val time = DayText.clock(minute)
            PickerChip(
                text = time,
                description = "$label $time",
                clickLabel = stringResource(R.string.editor_change_time),
                enabled = enabled,
                onClick = { picking = Picker.TIME },
                modifier = Modifier.testTag("$tag.time"),
            )
        }
    }
    when (picking) {
        Picker.DAY ->
            DayPickerDialog(
                initial = day,
                firstDay = firstDay,
                onPick = {
                    picking = null
                    onDay(it)
                },
                onDismiss = { picking = null },
            )
        Picker.TIME ->
            MinutePickerDialog(
                title = label,
                initial = minute,
                onPick = {
                    picking = null
                    onMinute(it)
                },
                onDismiss = { picking = null },
            )
        null -> Unit
    }
}

private enum class Picker { DAY, TIME }

@Composable
internal fun PickerChip(
    text: String,
    description: String,
    clickLabel: String,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val neutrals = LocalNeutralColors.current
    Box(
        contentAlignment = Alignment.Center,
        modifier =
            modifier
                .heightIn(min = Metrics.Chip.select)
                .clip(RoundedCornerShape(Radius.cell))
                .background(neutrals.fill)
                .clickable(enabled = enabled, role = Role.Button, onClickLabel = clickLabel, onClick = onClick)
                .semantics { contentDescription = description }
                .padding(horizontal = 10.dp, vertical = 6.dp),
    ) {
        Text(text, style = GeunalType.body, color = if (enabled) neutrals.ink else neutrals.secondaryText)
    }
}

/** The date picker in the core's days: Material's picker works in UTC midnights, which carry no time zone to shift. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DayPickerDialog(
    initial: String,
    firstDay: String?,
    onPick: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val selectable =
        remember(firstDay) {
            object : SelectableDates {
                override fun isSelectableDate(utcTimeMillis: Long): Boolean = firstDay == null || utcDay(utcTimeMillis) >= firstDay
            }
        }
    val picker = rememberDatePickerState(initialSelectedDateMillis = utcMillis(initial), selectableDates = selectable)
    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(
                onClick = { picker.selectedDateMillis?.let { onPick(utcDay(it)) } ?: onDismiss() },
                modifier = Modifier.testTag("picker.done"),
            ) { Text(stringResource(R.string.editor_done)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.editor_cancel)) } },
    ) { DatePicker(state = picker) }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun MinutePickerDialog(
    title: String,
    initial: Int,
    onPick: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    val picker = rememberTimePickerState(initialHour = initial / 60 % 24, initialMinute = initial % 60, is24Hour = true)
    TimePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(
                onClick = { onPick(picker.hour * 60 + picker.minute) },
                modifier = Modifier.testTag("picker.done"),
            ) { Text(stringResource(R.string.editor_done)) }
        },
        title = { Text(title) },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.editor_cancel)) } },
    ) { TimePicker(state = picker) }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RepeatCard(
    state: EditorModel.State,
    model: EditorModel,
    enabled: Boolean,
    onCustom: () -> Unit,
) {
    val neutrals = LocalNeutralColors.current
    val resources = LocalResources.current
    var alertsOpen by remember { mutableStateOf(false) }
    EditorCard {
        Column(
            verticalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.padding(horizontal = Space.m, vertical = 14.dp),
        ) {
            Text(stringResource(R.string.editor_repeat), style = GeunalType.body, color = neutrals.ink)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                model.repeatChoices().forEachIndexed { index, recurrence ->
                    OutlineChip(
                        label =
                            recurrence?.let { RepeatText.of(it, state.startDay).text(resources) }
                                ?: stringResource(R.string.editor_repeat_none),
                        selected = recurrence == state.recurrence,
                        enabled = enabled,
                        role = Role.RadioButton,
                        onClick = { model.setRecurrence(recurrence) },
                        modifier = Modifier.testTag("editor.repeat.$index"),
                    )
                }
                OutlineChip(
                    label = stringResource(R.string.editor_repeat_custom),
                    selected = false,
                    enabled = enabled,
                    role = Role.Button,
                    muted = true,
                    onClick = onCustom,
                    modifier = Modifier.testTag("editor.customRepeat"),
                )
            }
        }
        CardDivider()
        Box {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Space.xs),
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp)
                        .clickable(enabled = enabled, role = Role.Button) { alertsOpen = true }
                        .padding(horizontal = Space.m)
                        .testTag("editor.alert"),
            ) {
                Text(stringResource(R.string.editor_alert), style = GeunalType.body, color = neutrals.ink, modifier = Modifier.weight(1f))
                Text(
                    AlertText.value(state.alert, state.inheritedAlert, state.allDay).text(resources),
                    style = GeunalType.body,
                    color = neutrals.secondaryText,
                )
                Chevron()
            }
            DropdownMenu(expanded = alertsOpen, onDismissRequest = { alertsOpen = false }) {
                AlertText.choices(state.allDay).forEach { rule ->
                    DropdownMenuItem(
                        text = { Text(AlertText.of(rule, state.allDay).text(resources), style = GeunalType.body) },
                        trailingIcon = { if (rule == state.alert) Check() },
                        onClick = {
                            model.setAlert(rule)
                            alertsOpen = false
                        },
                    )
                }
            }
        }
    }
}

/** An outlined chip (boards/Editor: the repeat choices), ink when chosen. */
@Composable
private fun OutlineChip(
    label: String,
    selected: Boolean,
    enabled: Boolean,
    role: Role,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    muted: Boolean = false,
) {
    val neutrals = LocalNeutralColors.current
    val interaction = remember { MutableInteractionSource() }
    Box(
        contentAlignment = Alignment.Center,
        modifier =
            modifier
                .heightIn(min = Metrics.minHitTarget)
                .pressScale(interaction)
                .selectable(
                    selected = selected,
                    enabled = enabled,
                    role = role,
                    interactionSource = interaction,
                    indication = null,
                    onClick = onClick,
                ),
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier =
                Modifier
                    .heightIn(min = Metrics.Chip.select)
                    .then(
                        if (selected) {
                            Modifier.background(neutrals.ink, CircleShape)
                        } else {
                            Modifier.border(1.dp, neutrals.separator, CircleShape)
                        },
                    ).padding(horizontal = Space.s),
        ) {
            Text(
                label,
                style = GeunalType.pill,
                color =
                    when {
                        selected -> neutrals.background
                        muted || !enabled -> neutrals.secondaryText
                        else -> neutrals.ink
                    },
            )
        }
    }
}

/** Attachments arrive with M3; the card says so instead of offering a button that does nothing. */
@Composable
private fun AttachmentsCard() {
    val neutrals = LocalNeutralColors.current
    Column(
        verticalArrangement = Arrangement.spacedBy(6.dp),
        modifier =
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(Radius.input))
                .background(neutrals.background)
                .semantics(mergeDescendants = true) {}
                .padding(horizontal = Space.m, vertical = 14.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.editor_attachments), style = GeunalType.body, color = neutrals.tertiaryText)
            Text(
                stringResource(R.string.editor_attachments_milestone),
                style = GeunalType.caption.copy(fontWeight = FontWeight.SemiBold),
                color = neutrals.secondaryText,
            )
        }
        Text(
            stringResource(R.string.editor_attachments_limits),
            style = GeunalType.caption.copy(fontSize = 12.sp),
            color = neutrals.secondaryText,
        )
    }
}

@Composable
private fun DeleteButton(onClick: () -> Unit) {
    val neutrals = LocalNeutralColors.current
    val interaction = remember { MutableInteractionSource() }
    Box(
        contentAlignment = Alignment.Center,
        modifier =
            Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .pressScale(interaction)
                .clip(RoundedCornerShape(Radius.input))
                .background(neutrals.background)
                .clickable(interactionSource = interaction, indication = null, role = Role.Button, onClick = onClick)
                .testTag("editor.delete"),
    ) {
        Text(stringResource(R.string.editor_delete), style = GeunalType.body, color = neutrals.sunday)
    }
}

@Composable
private fun Chevron() {
    Icon(
        painterResource(DesignR.drawable.ds_ic_chevron_right),
        contentDescription = null,
        tint = LocalNeutralColors.current.tertiaryText,
        modifier = Modifier.size(18.dp),
    )
}

@Composable
private fun Check(color: Color = LocalNeutralColors.current.ink) {
    Icon(painterResource(DesignR.drawable.ds_ic_check), contentDescription = null, tint = color, modifier = Modifier.size(18.dp))
}

/** "10월 30일 (금)" as boards/Editor writes it, or with the year in another year than today's. */
internal fun chipDay(
    day: String,
    resources: Resources,
    today: String = KoreanCalendar.day(Instant.now()),
): String {
    val date = KoreanCalendar.parse(day) ?: return day
    val weekday = weekdayShort(resources, date.dayOfWeek)
    return if (KoreanCalendar.parse(today)?.year == date.year) {
        resources.getString(R.string.editor_day, date.monthValue, date.dayOfMonth, weekday)
    } else {
        // The year as text: a localized integer would read "2,027년".
        resources.getString(R.string.calendar_day_other_year, date.year.toString(), date.monthValue, date.dayOfMonth)
    }
}

private fun utcMillis(day: String): Long? =
    KoreanCalendar
        .parse(day)
        ?.atStartOfDay(ZoneOffset.UTC)
        ?.toInstant()
        ?.toEpochMilli()

private fun utcDay(millis: Long): String =
    Instant
        .ofEpochMilli(millis)
        .atZone(ZoneOffset.UTC)
        .toLocalDate()
        .toString()
