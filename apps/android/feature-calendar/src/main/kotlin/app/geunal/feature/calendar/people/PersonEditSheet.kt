package app.geunal.feature.calendar.people

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.geunal.core.AlertRule
import app.geunal.designsystem.GeunalType
import app.geunal.designsystem.LocalNeutralColors
import app.geunal.designsystem.Metrics
import app.geunal.designsystem.Radius
import app.geunal.designsystem.Space
import app.geunal.designsystem.components.ButtonKind
import app.geunal.designsystem.components.GeunalButton
import app.geunal.designsystem.components.GeunalSheet
import app.geunal.designsystem.components.GeunalSwitch
import app.geunal.designsystem.components.NoticeCard
import app.geunal.designsystem.components.NoticeKind
import app.geunal.designsystem.components.SegmentedControl
import app.geunal.designsystem.components.SettingsRow
import app.geunal.feature.calendar.R
import app.geunal.feature.calendar.editor.AlertText
import app.geunal.feature.calendar.editor.DeleteSheet
import app.geunal.feature.calendar.editor.text
import app.geunal.feature.calendar.support.Josa
import app.geunal.feature.calendar.support.KoreanCalendar
import app.geunal.feature.calendar.support.text
import java.time.Instant
import app.geunal.designsystem.R as DesignR

/**
 * The person sheet (m1.md screen 17, boards/PersonEdit): the name, 양력·음력, the birthday and the year, the alert and
 * the suggestion switch, "연락처에서 가져오기" for a new person ([onImportContacts], the import in its place as §2.4
 * draws it), "저장", and "이 사람 지우기" with its question for one that exists.
 */
@Composable
fun PersonEditSheet(
    model: PersonEditModel,
    onImportContacts: () -> Unit,
    onDismiss: () -> Unit,
) {
    val state by model.state.collectAsStateWithLifecycle()
    val neutrals = LocalNeutralColors.current
    var picking by remember { mutableStateOf<PersonPicker?>(null) }
    var confirmsDelete by remember { mutableStateOf(false) }
    val editable = !model.readOnly
    LaunchedEffect(model) { model.start() }
    GeunalSheet(onDismiss = onDismiss, dismissible = !state.isWorking) {
        Column(
            verticalArrangement = Arrangement.spacedBy(Space.m),
            modifier =
                Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = Metrics.Sheet.paddingSide)
                    .padding(top = Space.s, bottom = Metrics.Sheet.paddingBottom),
        ) {
            Row(verticalAlignment = Alignment.Top) {
                Column(verticalArrangement = Arrangement.spacedBy(Space.xxs), modifier = Modifier.weight(1f)) {
                    Text(
                        if (model.isNew) stringResource(R.string.person_add) else model.person?.name.orEmpty(),
                        style = GeunalType.title2,
                        color = neutrals.ink,
                        modifier = Modifier.semantics { heading() },
                    )
                    Text(stringResource(R.string.person_note), style = GeunalType.caption, color = neutrals.secondaryText)
                }
                IconButton(onClick = onDismiss) {
                    Icon(
                        painterResource(DesignR.drawable.ds_ic_close),
                        contentDescription = stringResource(R.string.person_close),
                        tint = neutrals.secondaryText,
                    )
                }
            }
            if (model.readOnly) NoticeCard(stringResource(R.string.person_shared), kind = NoticeKind.Calm)
            NameField(state.name, editable, model::setName)
            SegmentedControl(
                options = listOf(stringResource(R.string.person_solar), stringResource(R.string.person_lunar)),
                selected = if (state.lunar) 1 else 0,
                onSelect = { if (editable) model.setLunar(it == 1) },
                modifier = Modifier.fillMaxWidth().testTag("person.calendar"),
            )
            BirthdayRows(state, editable, onPick = { picking = it })
            AlertAndSuggest(state, model)
            if (model.isNew) {
                GeunalButton(
                    text = stringResource(R.string.person_from_contacts),
                    onClick = onImportContacts,
                    kind = ButtonKind.SmallPill,
                    modifier = Modifier.testTag("person.fromContacts"),
                )
            }
            if (state.failed) NoticeCard(stringResource(R.string.calendar_save_failed), kind = NoticeKind.Problem)
            GeunalButton(
                // Says what is missing rather than only greying out (m1.md screen 17 "이름 비어 있으면 저장 꺼짐").
                text = stringResource(if (state.trimmedName.isEmpty()) R.string.person_needs_name else R.string.person_save),
                onClick = model::launchSave,
                enabled = state.canSave,
                modifier = Modifier.testTag("person.save"),
            )
            if (!model.isNew && editable) {
                GeunalButton(
                    text = stringResource(R.string.person_delete),
                    onClick = { confirmsDelete = true },
                    enabled = !state.isWorking,
                    kind = ButtonKind.Danger,
                    modifier = Modifier.testTag("person.delete"),
                )
            }
        }
    }
    when (picking) {
        PersonPicker.BIRTHDAY ->
            MonthDayDialog(
                lunar = state.lunar,
                month = state.month,
                day = state.day,
                leap = state.leapMonth,
                onPick = { month, day, leap ->
                    picking = null
                    model.setBirthday(month, day, leap)
                },
                onDismiss = { picking = null },
            )
        PersonPicker.YEAR ->
            YearDialog(
                year = state.year,
                onPick = {
                    picking = null
                    model.setYear(it)
                },
                onDismiss = { picking = null },
            )
        null -> Unit
    }
    if (confirmsDelete) {
        val name = model.person?.name.orEmpty()
        DeleteSheet(
            title =
                stringResource(
                    if (Josa.endsInConsonant(name)) R.string.person_delete_after_consonant else R.string.person_delete_after_vowel,
                    name,
                ),
            body = stringResource(R.string.person_delete_body),
            confirm = stringResource(R.string.person_delete_confirm),
            onConfirm = {
                confirmsDelete = false
                model.launchDelete()
            },
            onDismiss = { confirmsDelete = false },
        )
    }
}

private enum class PersonPicker { BIRTHDAY, YEAR }

@Composable
private fun NameField(
    name: String,
    enabled: Boolean,
    onName: (String) -> Unit,
) {
    val neutrals = LocalNeutralColors.current
    val label = stringResource(R.string.person_name)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Space.s),
        modifier =
            Modifier
                .fillMaxWidth()
                .heightIn(min = 52.dp)
                .clip(RoundedCornerShape(Radius.input))
                .background(neutrals.groupedBackground)
                .padding(horizontal = Space.m),
    ) {
        Text(label, style = GeunalType.body, color = neutrals.secondaryText)
        BasicTextField(
            value = name,
            onValueChange = onName,
            enabled = enabled,
            singleLine = true,
            textStyle = GeunalType.body.copy(color = neutrals.ink),
            cursorBrush = SolidColor(neutrals.ink),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            modifier =
                Modifier
                    .weight(1f)
                    .semantics { contentDescription = label }
                    .testTag("person.name"),
            decorationBox = { field ->
                Box(contentAlignment = Alignment.CenterStart) {
                    if (name.isEmpty()) Text(label, style = GeunalType.body, color = neutrals.tertiaryText)
                    field()
                }
            },
        )
    }
}

@Composable
private fun BirthdayRows(
    state: PersonEditModel.State,
    editable: Boolean,
    onPick: (PersonPicker) -> Unit,
) {
    val neutrals = LocalNeutralColors.current
    val resources = LocalResources.current
    Column {
        SettingsRow(
            title = stringResource(R.string.person_birthday),
            value = state.birthday?.let { PeopleText.birthday(it, resources) } ?: stringResource(R.string.person_pick),
            onClick = if (editable) ({ onPick(PersonPicker.BIRTHDAY) }) else null,
            modifier = Modifier.testTag("person.birthday"),
        )
        HorizontalDivider(thickness = 1.dp, color = neutrals.fill)
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier =
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = Metrics.Row.twoLine)
                    .then(
                        if (editable) Modifier.selectable(selected = false, role = Role.Button) { onPick(PersonPicker.YEAR) } else Modifier,
                    ).padding(horizontal = Space.m)
                    .testTag("person.year"),
        ) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.person_year), style = GeunalType.body, color = neutrals.ink)
                Text(stringResource(R.string.person_year_note), style = GeunalType.caption, color = neutrals.secondaryText)
            }
            Text(
                state.year?.let { stringResource(R.string.person_year_value, it.toString()) } ?: stringResource(R.string.person_pick),
                style = GeunalType.body,
                color = neutrals.secondaryText,
            )
        }
        if (state.lunar && state.birthday != null) {
            Text(
                PeopleText.lunarNote(state.solarThisYear, resources),
                style = GeunalType.caption,
                color = neutrals.secondaryText,
                modifier = Modifier.padding(horizontal = Space.m, vertical = Space.xs).testTag("person.lunarNote"),
            )
        }
    }
}

@Composable
private fun AlertAndSuggest(
    state: PersonEditModel.State,
    model: PersonEditModel,
) {
    val neutrals = LocalNeutralColors.current
    val resources = LocalResources.current
    var alertsOpen by remember { mutableStateOf(false) }
    val alertText = { rule: AlertRule -> birthdayAlert(rule).text(resources) }
    Column {
        Box {
            SettingsRow(
                title = stringResource(R.string.person_alert),
                value = alertText(state.alert),
                onClick = { alertsOpen = true },
                modifier = Modifier.testTag("person.alert"),
            )
            DropdownMenu(expanded = alertsOpen, onDismissRequest = { alertsOpen = false }) {
                PERSON_ALERTS.forEach { rule ->
                    val choice =
                        if (rule is AlertRule.Inherit) {
                            stringResource(
                                R.string.person_alert_inherit_choice,
                                alertText(rule),
                            )
                        } else {
                            alertText(rule)
                        }
                    DropdownMenuItem(
                        text = { Text(choice, style = GeunalType.body) },
                        onClick = {
                            model.setAlert(rule)
                            alertsOpen = false
                        },
                    )
                }
            }
        }
        HorizontalDivider(thickness = 1.dp, color = neutrals.fill)
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier =
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = Metrics.Row.twoLine)
                    .toggleable(value = state.suggest, role = Role.Switch, onValueChange = model::setSuggest)
                    .padding(horizontal = Space.m)
                    .testTag("person.suggest"),
        ) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.person_suggest), style = GeunalType.body, color = neutrals.ink)
                Text(
                    stringResource(
                        R.string.person_suggest_note,
                        state.trimmedName.ifEmpty { stringResource(R.string.person_suggest_someone) },
                    ),
                    style = GeunalType.caption,
                    color = neutrals.secondaryText,
                )
            }
            GeunalSwitch(checked = state.suggest, onCheckedChange = null)
        }
    }
}

/**
 * A birthday's alert (rust-core.md §6): the notification settings' 당일 09:00, the evening before, a week before in the
 * morning, or none. A birthday counts from 00:00 of its day, like an all-day event.
 */
private val PERSON_ALERTS = listOf(AlertRule.Inherit, AlertRule.Before(360), AlertRule.Before(9540), AlertRule.Off)

/** `Inherit` reads as the settings' default, 당일 09:00, which is what it rings at unless the setting is off. */
private fun birthdayAlert(rule: AlertRule): AlertText =
    if (rule is AlertRule.Inherit) AlertText.of(-540, allDay = true) else AlertText.of(rule, allDay = true)

/**
 * The month and the day of a birthday, as chips: a lunar month has at most 30 days and may be a leap month, a solar
 * one has its own length with 29 February kept for leap-year birthdays.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun MonthDayDialog(
    lunar: Boolean,
    month: Int?,
    day: Int?,
    leap: Boolean,
    onPick: (month: Int, day: Int, leap: Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    val neutrals = LocalNeutralColors.current
    var chosenMonth by remember { mutableIntStateOf(month ?: 1) }
    var chosenDay by remember { mutableIntStateOf(day ?: 1) }
    var chosenLeap by remember { mutableStateOf(leap) }
    val days = if (lunar) 30 else SOLAR_DAYS[chosenMonth - 1]
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.person_birthday)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(Space.s), modifier = Modifier.verticalScroll(rememberScrollState())) {
                Text(
                    stringResource(R.string.person_month),
                    style = GeunalType.caption.copy(fontWeight = FontWeight.SemiBold),
                    color = neutrals.secondaryText,
                )
                FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    (1..12).forEach { value ->
                        NumberChip(value, value == chosenMonth, tag = "person.month.$value") {
                            chosenMonth = value
                            chosenDay = chosenDay.coerceAtMost(if (lunar) 30 else SOLAR_DAYS[value - 1])
                        }
                    }
                }
                Text(
                    stringResource(R.string.person_day),
                    style = GeunalType.caption.copy(fontWeight = FontWeight.SemiBold),
                    color = neutrals.secondaryText,
                )
                FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    (1..days).forEach { value ->
                        NumberChip(value, value == chosenDay, tag = "person.day.$value") { chosenDay = value }
                    }
                }
                if (lunar) LeapMonthBox(checked = chosenLeap, onChecked = { chosenLeap = it })
            }
        },
        confirmButton = {
            TextButton(onClick = {
                onPick(chosenMonth, chosenDay.coerceAtMost(days), lunar && chosenLeap)
            }, modifier = Modifier.testTag("person.pickDone")) {
                Text(stringResource(R.string.editor_done))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.editor_cancel)) } },
    )
}

@Composable
private fun NumberChip(
    value: Int,
    selected: Boolean,
    tag: String,
    onClick: () -> Unit,
) {
    val neutrals = LocalNeutralColors.current
    Box(
        contentAlignment = Alignment.Center,
        modifier =
            Modifier
                .size(Metrics.minHitTarget)
                .clip(CircleShape)
                .background(if (selected) neutrals.ink else neutrals.fill)
                .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
                .testTag(tag),
    ) {
        Text("$value", style = GeunalType.pill, color = if (selected) neutrals.background else neutrals.ink)
    }
}

/** The year of birth, newest first, or "모름". */
@Composable
private fun YearDialog(
    year: Int?,
    onPick: (Int?) -> Unit,
    onDismiss: () -> Unit,
) {
    val neutrals = LocalNeutralColors.current
    val thisYear = KoreanCalendar.date(Instant.now()).year
    val years: List<Int?> = listOf(null) + (thisYear downTo FIRST_YEAR).toList()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.person_year)) },
        text = {
            LazyColumn(Modifier.heightIn(max = 360.dp)) {
                items(years) { value ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .heightIn(min = Metrics.minHitTarget)
                                .selectable(selected = value == year, role = Role.RadioButton) { onPick(value) }
                                .padding(horizontal = Space.xs)
                                .testTag("person.year.${value ?: "unknown"}"),
                    ) {
                        Text(
                            value?.let { stringResource(R.string.person_year_value, it.toString()) }
                                ?: stringResource(R.string.person_year_none),
                            style = if (value == year) GeunalType.bodyStrong else GeunalType.body,
                            color = neutrals.ink,
                        )
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.editor_cancel)) } },
    )
}

/** Days in each solar month, with 29 for February: a birthday on 29 February is kept. */
private val SOLAR_DAYS = listOf(31, 29, 31, 30, 31, 30, 31, 31, 30, 31, 30, 31)

private const val FIRST_YEAR = 1900

/** 윤달: a lunar birthday in a leap month falls in that month's leap twin (rust-core.md §6). */
@Composable
private fun LeapMonthBox(
    checked: Boolean,
    onChecked: (Boolean) -> Unit,
) {
    val neutrals = LocalNeutralColors.current
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.toggleable(value = checked, role = Role.Checkbox, onValueChange = onChecked).testTag("person.leap"),
    ) {
        Checkbox(checked = checked, onCheckedChange = null, colors = CheckboxDefaults.colors(checkedColor = neutrals.ink))
        Column {
            Text(stringResource(R.string.person_leap), style = GeunalType.body, color = neutrals.ink)
            Text(stringResource(R.string.person_leap_note), style = GeunalType.caption, color = neutrals.secondaryText)
        }
    }
}
