package app.geunal.feature.calendar.calendars

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.geunal.core.AlertRule
import app.geunal.core.Palette
import app.geunal.designsystem.GeunalType
import app.geunal.designsystem.LocalNeutralColors
import app.geunal.designsystem.Metrics
import app.geunal.designsystem.Radius
import app.geunal.designsystem.Space
import app.geunal.designsystem.components.ButtonKind
import app.geunal.designsystem.components.GeunalButton
import app.geunal.designsystem.components.GeunalSheet
import app.geunal.designsystem.components.NoticeCard
import app.geunal.designsystem.components.NoticeKind
import app.geunal.designsystem.components.SettingsDivider
import app.geunal.designsystem.components.SettingsGroup
import app.geunal.designsystem.label
import app.geunal.feature.calendar.R
import app.geunal.feature.calendar.editor.AlertText
import app.geunal.feature.calendar.editor.DeleteSheet
import app.geunal.feature.calendar.editor.text
import app.geunal.feature.calendar.support.MenuRow
import app.geunal.feature.calendar.support.SwitchRow
import app.geunal.feature.calendar.support.groupColor
import app.geunal.designsystem.R as DesignR

/**
 * The calendar sheet (m1.md screen 16, boards/CalendarEdit): the name, the twelve colors in six columns, the soft
 * switch with what it means, the default alert, "저장", and "캘린더 삭제" with its question for one that exists. The
 * last calendar left keeps the delete dimmed with why.
 */
@Composable
fun CalendarEditSheet(
    model: CalendarEditModel,
    onDismiss: () -> Unit,
) {
    val state by model.state.collectAsStateWithLifecycle()
    val neutrals = LocalNeutralColors.current
    val resources = LocalResources.current
    var confirmsDelete by remember { mutableStateOf(false) }
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
                        model.calendar?.name ?: stringResource(R.string.calendars_new),
                        style = GeunalType.title2,
                        color = neutrals.ink,
                        modifier = Modifier.semantics { heading() },
                    )
                    val colorName = state.color.groupColor.label(resources)
                    Text(
                        if (state.soft) stringResource(R.string.calendars_soft_detail, colorName) else colorName,
                        style = GeunalType.caption,
                        color = neutrals.secondaryText,
                    )
                }
                IconButton(onClick = onDismiss) {
                    Icon(
                        painterResource(DesignR.drawable.ds_ic_close),
                        contentDescription = stringResource(R.string.calendar_edit_close),
                        tint = neutrals.secondaryText,
                    )
                }
            }
            NameField(state.name, model::setName)
            ColorGrid(state.color, model::setColor)
            SettingsGroup {
                SwitchRow(
                    title = stringResource(R.string.calendar_edit_soft),
                    note = stringResource(R.string.calendar_edit_soft_note),
                    checked = state.soft,
                    onChange = model::setSoft,
                    modifier = Modifier.testTag("calendarEdit.soft"),
                )
                SettingsDivider()
                MenuRow(
                    title = stringResource(R.string.calendar_edit_alert),
                    value = alertText(state.defaultAlert, state.settingsAlert),
                    choices = CALENDAR_ALERTS,
                    label = { rule -> alertChoice(rule, state.settingsAlert) },
                    onPick = model::setDefaultAlert,
                    modifier = Modifier.testTag("calendarEdit.alert"),
                )
            }
            state.problem?.let { problem ->
                NoticeCard(
                    stringResource(
                        if (problem ==
                            CalendarEditModel.Problem.DELETE_FAILED
                        ) {
                            R.string.editor_delete_failed
                        } else {
                            R.string.calendar_save_failed
                        },
                    ),
                    kind = NoticeKind.Problem,
                )
            }
            GeunalButton(
                text = stringResource(if (state.trimmedName.isEmpty()) R.string.calendar_edit_needs_name else R.string.calendar_edit_save),
                onClick = model::launchSave,
                enabled = state.canSave,
                modifier = Modifier.testTag("calendarEdit.save"),
            )
            if (!model.isNew) {
                val count = model.calendar?.eventCount?.toInt() ?: 0
                GeunalButton(
                    text =
                        if (count > 0) {
                            stringResource(R.string.calendar_edit_delete, count)
                        } else {
                            stringResource(R.string.calendar_edit_delete_empty)
                        },
                    onClick = { confirmsDelete = true },
                    enabled = model.canDelete && !state.isWorking,
                    kind = ButtonKind.Danger,
                    modifier = Modifier.testTag("calendarEdit.delete"),
                )
                if (model.isLast) {
                    Text(
                        stringResource(R.string.calendar_edit_last),
                        style = GeunalType.caption,
                        color = neutrals.secondaryText,
                        modifier = Modifier.fillMaxWidth().testTag("calendarEdit.last"),
                    )
                }
            }
        }
    }
    if (confirmsDelete) {
        DeleteSheet(
            title = stringResource(R.string.calendar_delete_question, model.calendar?.name.orEmpty()),
            body =
                (model.calendar?.eventCount?.toInt() ?: 0).let { count ->
                    if (count >
                        0
                    ) {
                        stringResource(R.string.calendar_delete_body, count)
                    } else {
                        stringResource(R.string.calendar_delete_body_empty)
                    }
                },
            confirm = stringResource(R.string.calendar_delete_confirm),
            onConfirm = {
                confirmsDelete = false
                model.launchDelete()
            },
            onDismiss = { confirmsDelete = false },
        )
    }
}

@Composable
private fun NameField(
    name: String,
    onName: (String) -> Unit,
) {
    val neutrals = LocalNeutralColors.current
    val label = stringResource(R.string.calendar_edit_name)
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
            singleLine = true,
            textStyle = GeunalType.body.copy(color = neutrals.ink),
            cursorBrush = SolidColor(neutrals.ink),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            modifier =
                Modifier
                    .weight(1f)
                    .semantics { contentDescription = label }
                    .testTag("calendarEdit.name"),
            decorationBox = { field ->
                Box(contentAlignment = Alignment.CenterStart) {
                    if (name.isEmpty()) Text(label, style = GeunalType.body, color = neutrals.tertiaryText)
                    field()
                }
            },
        )
    }
}

/** The twelve colors in six columns: the chosen one has a white ring inside an ink one and a check (components.md). */
@Composable
private fun ColorGrid(
    chosen: Palette,
    onChoose: (Palette) -> Unit,
) {
    val neutrals = LocalNeutralColors.current
    val resources = LocalResources.current
    Column(verticalArrangement = Arrangement.spacedBy(Space.s), modifier = Modifier.selectableGroup()) {
        Palette.entries.chunked(COLUMNS).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(Space.s)) {
                row.forEach { palette ->
                    val selected = palette == chosen
                    val color = palette.groupColor
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier =
                            Modifier
                                .weight(1f)
                                .aspectRatio(1f)
                                .heightIn(min = Metrics.minHitTarget)
                                .clip(CircleShape)
                                .selectable(selected = selected, role = Role.RadioButton) { onChoose(palette) }
                                .semantics { contentDescription = color.label(resources) }
                                .testTag("calendarEdit.color.${palette.name.lowercase()}"),
                    ) {
                        Box(
                            Modifier
                                .size(36.dp)
                                .then(if (selected) Modifier.border(2.dp, neutrals.ink, CircleShape) else Modifier)
                                .padding(if (selected) 3.dp else 0.dp)
                                .background(color.light.last(), CircleShape),
                            contentAlignment = Alignment.Center,
                        ) {
                            if (selected) {
                                Icon(
                                    painterResource(DesignR.drawable.ds_ic_check),
                                    contentDescription = null,
                                    tint = Color.White,
                                    modifier = Modifier.size(16.dp),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/** The row's value: a calendar that inherits rings at the notification settings' default, which it says. */
@Composable
private fun alertText(
    rule: AlertRule,
    settingsAlert: Int?,
): String =
    if (rule is AlertRule.Inherit) alertChoice(rule, settingsAlert) else AlertText.of(rule, allDay = false).text(LocalResources.current)

@Composable
private fun alertChoice(
    rule: AlertRule,
    settingsAlert: Int?,
): String {
    val resources = LocalResources.current
    if (rule !is AlertRule.Inherit) return AlertText.of(rule, allDay = false).text(resources)
    val value = settingsAlert?.let { AlertText.of(it, allDay = false).text(resources) } ?: resources.getString(R.string.editor_alert_off)
    return stringResource(R.string.calendar_edit_alert_inherit, value)
}

/** The default alert of a calendar's timed events: the notification settings' default, none, or before the start. */
private val CALENDAR_ALERTS =
    listOf(
        AlertRule.Inherit,
        AlertRule.Off,
        AlertRule.Before(0),
        AlertRule.Before(10),
        AlertRule.Before(30),
        AlertRule.Before(60),
        AlertRule.Before(1440),
    )

private const val COLUMNS = 6
