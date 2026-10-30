package app.geunal.feature.calendar.shift

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.geunal.designsystem.GeunalType
import app.geunal.designsystem.GroupColors
import app.geunal.designsystem.LocalNeutralColors
import app.geunal.designsystem.Metrics
import app.geunal.designsystem.Radius
import app.geunal.designsystem.Space
import app.geunal.designsystem.components.ButtonKind
import app.geunal.designsystem.components.GeunalButton
import app.geunal.designsystem.components.GeunalSheet
import app.geunal.designsystem.components.NoticeCard
import app.geunal.designsystem.components.NoticeKind
import app.geunal.designsystem.components.SettingsRow
import app.geunal.designsystem.components.StatusBadge
import app.geunal.designsystem.label
import app.geunal.feature.calendar.R
import app.geunal.feature.calendar.editor.AlertText
import app.geunal.feature.calendar.editor.MinutePickerDialog
import app.geunal.feature.calendar.editor.text
import app.geunal.feature.calendar.support.DayText
import app.geunal.feature.calendar.support.groupColor
import app.geunal.designsystem.R as DesignR

/**
 * The template sheet (m1.md screen 18, boards/TemplateEdit): the preview card, the name, start and end, calendar and
 * alert, the midnight note, "저장" and the red "템플릿 삭제". [onSaved] and [onDeleted] get the template's id.
 */
@Composable
fun TemplateEditSheet(
    model: TemplateEditModel,
    onSaved: (String) -> Unit,
    onDeleted: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val state by model.state.collectAsStateWithLifecycle()
    val neutrals = LocalNeutralColors.current
    LaunchedEffect(model) { model.start() }
    GeunalSheet(onDismiss = onDismiss, dismissible = !state.isWorking) {
        Column(
            verticalArrangement = Arrangement.spacedBy(Space.m),
            modifier =
                Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = Metrics.Sheet.paddingSide)
                    .padding(top = Space.l, bottom = Metrics.Sheet.paddingBottom),
        ) {
            Header(onClose = onDismiss)
            Preview(state)
            NameField(state, model)
            Rows(state, model)
            Text(
                stringResource(R.string.template_midnight),
                style =
                    GeunalType.secondary.copy(
                        fontSize = 14.sp,
                        fontWeight = if (state.crossesMidnight) FontWeight.SemiBold else FontWeight.Normal,
                    ),
                color = if (state.crossesMidnight) neutrals.ink else neutrals.secondaryText,
                modifier = Modifier.padding(horizontal = Space.xs).testTag("template.midnight"),
            )
            PlusRow()
            if (state.failed) {
                NoticeCard(stringResource(R.string.calendar_save_failed), kind = NoticeKind.Problem)
            }
            GeunalButton(
                text = stringResource(R.string.editor_save),
                onClick = { model.launchSave(onSaved) },
                enabled = state.canSave,
                modifier = Modifier.testTag("template.save"),
            )
            if (!model.isNew) {
                GeunalButton(
                    text = stringResource(R.string.template_delete),
                    onClick = { model.launchDelete(onDeleted) },
                    enabled = !state.isWorking,
                    kind = ButtonKind.Danger,
                    modifier = Modifier.testTag("template.delete"),
                )
            }
        }
    }
}

@Composable
private fun Header(onClose: () -> Unit) {
    val neutrals = LocalNeutralColors.current
    Row(verticalAlignment = Alignment.Top) {
        Column(verticalArrangement = Arrangement.spacedBy(Space.xxs), modifier = Modifier.weight(1f)) {
            Text(
                stringResource(R.string.shift_edit_template),
                style = GeunalType.title2,
                color = neutrals.ink,
                modifier = Modifier.semantics { heading() },
            )
            Text(
                stringResource(R.string.template_subtitle),
                style = GeunalType.secondary.copy(fontSize = 14.sp),
                color = neutrals.secondaryText,
            )
        }
        IconButton(onClick = onClose) {
            Icon(
                painterResource(DesignR.drawable.ds_ic_close),
                contentDescription = stringResource(R.string.editor_close),
                tint = neutrals.secondaryText,
            )
        }
    }
}

/** "달력에 이렇게 찍혀요": the bar in the template's color, the name, the hours and the length. */
@Composable
private fun Preview(state: TemplateEditModel.State) {
    val neutrals = LocalNeutralColors.current
    val resources = LocalResources.current
    val hours = state.lengthMinutes / 60
    val minutes = state.lengthMinutes % 60
    val length =
        if (minutes == 0) {
            resources.getString(R.string.template_hours, hours)
        } else {
            resources.getString(R.string.template_hours_minutes, hours, minutes)
        }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Space.s),
        modifier =
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(Radius.card))
                .background(neutrals.groupedBackground)
                .semantics(mergeDescendants = true) {}
                .padding(Space.m)
                .testTag("template.preview"),
    ) {
        Box(
            Modifier
                .size(width = 8.dp, height = 40.dp)
                .clip(RoundedCornerShape(Radius.strip))
                .background(ShiftColors.chip(state.previewColor, state.tint).fill),
        )
        Column(verticalArrangement = Arrangement.spacedBy(2.dp), modifier = Modifier.weight(1f)) {
            Text(state.trimmedName.ifEmpty { "—" }, style = GeunalType.bodyStrong, color = neutrals.ink)
            Text(
                DayText.range(state.startMinute, state.endMinute) + " · " + length,
                style = GeunalType.secondary,
                color = neutrals.secondaryText,
            )
        }
        Text(stringResource(R.string.template_preview), style = GeunalType.caption, color = neutrals.secondaryText)
    }
}

@Composable
private fun NameField(
    state: TemplateEditModel.State,
    model: TemplateEditModel,
) {
    val neutrals = LocalNeutralColors.current
    val label = stringResource(R.string.template_name)
    val placeholder = stringResource(R.string.template_name_placeholder)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Space.s),
        modifier =
            Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .clip(RoundedCornerShape(Radius.input))
                .background(neutrals.groupedBackground)
                .padding(horizontal = Space.m),
    ) {
        Text(label, style = GeunalType.body, color = neutrals.secondaryText)
        BasicTextField(
            value = state.name,
            onValueChange = model::setName,
            singleLine = true,
            textStyle = GeunalType.body.copy(color = neutrals.ink),
            cursorBrush = SolidColor(neutrals.ink),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            modifier =
                Modifier
                    .weight(1f)
                    .semantics { contentDescription = label }
                    .testTag("template.name"),
            decorationBox = { field ->
                Box(contentAlignment = Alignment.CenterStart) {
                    if (state.name.isEmpty()) Text(placeholder, style = GeunalType.body, color = neutrals.tertiaryText)
                    field()
                }
            },
        )
    }
}

@Composable
private fun Rows(
    state: TemplateEditModel.State,
    model: TemplateEditModel,
) {
    val resources = LocalResources.current
    var picking by remember { mutableStateOf<TemplateTime?>(null) }
    var calendarsOpen by remember { mutableStateOf(false) }
    var alertsOpen by remember { mutableStateOf(false) }
    val start = stringResource(R.string.editor_start)
    val end = stringResource(R.string.editor_end)
    Column {
        TimeRow(start, state.startMinute, onClick = { picking = TemplateTime.START }, tag = "template.start")
        RowDivider()
        TimeRow(end, state.endMinute, onClick = { picking = TemplateTime.END }, tag = "template.end")
        RowDivider()
        Box {
            SettingsRow(
                title = stringResource(R.string.editor_calendar),
                value = calendarValue(state),
                onClick = { calendarsOpen = true },
                modifier = Modifier.testTag("template.calendar"),
            )
            DropdownMenu(expanded = calendarsOpen, onDismissRequest = { calendarsOpen = false }) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.template_calendar_new), style = GeunalType.body) },
                    onClick = {
                        model.setCalendar(null)
                        calendarsOpen = false
                    },
                )
                state.ownCalendars.forEach { calendar ->
                    DropdownMenuItem(
                        text = { Text(calendar.name, style = GeunalType.body) },
                        onClick = {
                            model.setCalendar(calendar.id)
                            calendarsOpen = false
                        },
                    )
                }
            }
        }
        RowDivider()
        Box {
            SettingsRow(
                title = stringResource(R.string.editor_alert),
                value = AlertText.of(state.alert, allDay = false).text(resources),
                onClick = { alertsOpen = true },
                modifier = Modifier.testTag("template.alert"),
            )
            DropdownMenu(expanded = alertsOpen, onDismissRequest = { alertsOpen = false }) {
                AlertText.choices(allDay = false).forEach { rule ->
                    DropdownMenuItem(
                        text = { Text(AlertText.of(rule, allDay = false).text(resources), style = GeunalType.body) },
                        onClick = {
                            model.setAlert(rule)
                            alertsOpen = false
                        },
                    )
                }
            }
        }
    }
    when (picking) {
        TemplateTime.START ->
            MinutePickerDialog(
                title = start,
                initial = state.startMinute,
                onPick = {
                    picking = null
                    model.setStart(it)
                },
                onDismiss = { picking = null },
            )
        TemplateTime.END ->
            MinutePickerDialog(
                title = end,
                initial = state.endMinute,
                onPick = {
                    picking = null
                    model.setEnd(it)
                },
                onDismiss = { picking = null },
            )
        null -> Unit
    }
}

private enum class TemplateTime { START, END }

/** "시작 08:00 ›", a row like the calendar and alert below it (boards/TemplateEdit). */
@Composable
private fun TimeRow(
    label: String,
    minute: Int,
    onClick: () -> Unit,
    tag: String,
) {
    SettingsRow(
        title = label,
        value = DayText.clock(minute),
        onClick = onClick,
        modifier = Modifier.testTag(tag),
    )
}

@Composable
private fun RowDivider() {
    HorizontalDivider(thickness = 1.dp, color = LocalNeutralColors.current.fill)
}

/** "알바 · 민트": the calendar and its color, or the '알바' the core makes when none is picked. */
@Composable
private fun calendarValue(state: TemplateEditModel.State): String {
    val resources = LocalResources.current
    val calendar = state.selectedCalendar ?: return stringResource(R.string.template_calendar_default)
    val color = calendar.color?.groupColor?.label(resources) ?: return calendar.name
    return stringResource(R.string.template_calendar_value, calendar.name, color)
}

/** The Plus feature the board shows: hours and expected pay of the template's shifts, not in M1. */
@Composable
private fun PlusRow() {
    val neutrals = LocalNeutralColors.current
    val grape = GroupColors.first { it.name == "grape" }
    Row(
        verticalAlignment = Alignment.Top,
        modifier =
            Modifier
                .fillMaxWidth()
                .semantics(mergeDescendants = true) {}
                .padding(horizontal = Space.m),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(2.dp), modifier = Modifier.weight(1f)) {
            Text(stringResource(R.string.template_plus), style = GeunalType.body, color = neutrals.tertiaryText)
            Text(
                stringResource(R.string.template_plus_body),
                style = GeunalType.secondary.copy(fontSize = 14.sp),
                color = neutrals.tertiaryText,
            )
        }
        StatusBadge(stringResource(R.string.template_plus_badge), container = grape.light.first(), content = grape.light.last())
    }
}
