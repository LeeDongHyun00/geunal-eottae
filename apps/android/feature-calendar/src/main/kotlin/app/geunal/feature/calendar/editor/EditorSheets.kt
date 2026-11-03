package app.geunal.feature.calendar.editor

import androidx.compose.foundation.background
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
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.geunal.core.EditScope
import app.geunal.core.Frequency
import app.geunal.core.Recurrence
import app.geunal.core.Weekday
import app.geunal.designsystem.GeunalType
import app.geunal.designsystem.LocalNeutralColors
import app.geunal.designsystem.Metrics
import app.geunal.designsystem.Space
import app.geunal.designsystem.components.ButtonKind
import app.geunal.designsystem.components.GeunalButton
import app.geunal.designsystem.components.GeunalSheet
import app.geunal.feature.calendar.R
import app.geunal.feature.calendar.support.KoreanCalendar
import app.geunal.feature.calendar.support.RepeatText
import app.geunal.feature.calendar.support.column
import app.geunal.feature.calendar.support.dayOfWeek
import app.geunal.feature.calendar.support.text
import app.geunal.feature.calendar.support.weekdayLong
import app.geunal.feature.calendar.support.weekdayShort
import app.geunal.designsystem.R as DesignR

/**
 * "이 일정만 · 이후 모두" for an occurrence of a repeating event (m1.md screen 9 "동작"), asked in a sheet, never an alert
 * (m1.md decision 4).
 */
@Composable
internal fun ScopeSheet(
    title: String,
    thisOnly: String,
    following: String,
    destructive: Boolean,
    onChoose: (EditScope) -> Unit,
    onDismiss: () -> Unit,
) {
    GeunalSheet(onDismiss = onDismiss) {
        SheetColumn {
            SheetTitle(title)
            Column(verticalArrangement = Arrangement.spacedBy(Space.xs)) {
                GeunalButton(
                    text = thisOnly,
                    onClick = { onChoose(EditScope.THIS_ONLY) },
                    kind = if (destructive) ButtonKind.Danger else ButtonKind.Secondary,
                    modifier = Modifier.testTag("scope.thisOnly"),
                )
                GeunalButton(
                    text = following,
                    onClick = { onChoose(EditScope.THIS_AND_FOLLOWING) },
                    kind = if (destructive) ButtonKind.Danger else ButtonKind.Primary,
                    modifier = Modifier.testTag("scope.following"),
                )
                GeunalButton(
                    text = stringResource(R.string.editor_cancel),
                    onClick = onDismiss,
                    kind = ButtonKind.Text,
                    fullWidth = true,
                )
            }
        }
    }
}

/** The question before deleting an event that does not repeat. */
@Composable
internal fun DeleteSheet(
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    GeunalSheet(onDismiss = onDismiss) {
        SheetColumn {
            SheetTitle(stringResource(R.string.editor_delete_question))
            Text(
                stringResource(R.string.editor_delete_body),
                style = GeunalType.secondary,
                color = LocalNeutralColors.current.secondaryText,
            )
            Column(verticalArrangement = Arrangement.spacedBy(Space.xs)) {
                GeunalButton(
                    text = stringResource(R.string.editor_delete),
                    onClick = onConfirm,
                    kind = ButtonKind.Danger,
                    modifier = Modifier.testTag("delete.confirm"),
                )
                GeunalButton(
                    text = stringResource(R.string.editor_cancel),
                    onClick = onDismiss,
                    kind = ButtonKind.Text,
                    fullWidth = true,
                )
            }
        }
    }
}

/** "직접 정하기": weekly on the chosen weekdays, every one to four weeks. */
@Composable
internal fun CustomRepeatSheet(
    recurrence: Recurrence?,
    startDay: String,
    onDone: (Recurrence) -> Unit,
    onDismiss: () -> Unit,
) {
    val neutrals = LocalNeutralColors.current
    val resources = LocalResources.current
    val weekly = recurrence?.takeIf { it.frequency == Frequency.WEEKLY }
    var weekdays by remember {
        val start = KoreanCalendar.weekday(startDay)
        val own = weekly?.weekdays.orEmpty().toSet()
        mutableStateOf(own.ifEmpty { Weekday.entries.filter { it.dayOfWeek == start }.toSet() })
    }
    var interval by remember { mutableIntStateOf((weekly?.interval?.toInt() ?: 1).coerceIn(1, MAX_INTERVAL)) }
    val rule =
        Recurrence(
            frequency = Frequency.WEEKLY,
            interval = interval.toUShort(),
            weekdays = weekdays.sortedBy { it.dayOfWeek.column },
            until = null,
            count = null,
        )
    GeunalSheet(onDismiss = onDismiss) {
        SheetColumn {
            SheetTitle(stringResource(R.string.editor_repeat_custom))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Weekday.entries.sortedBy { it.dayOfWeek.column }.forEach { weekday ->
                    val on = weekday in weekdays
                    val long = weekdayLong(resources, weekday.dayOfWeek)
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier =
                            Modifier
                                .weight(1f)
                                .heightIn(min = Metrics.minHitTarget)
                                .aspectRatio(1f)
                                .clip(CircleShape)
                                .background(if (on) neutrals.ink else neutrals.fill)
                                .toggleable(value = on, role = Role.Checkbox) {
                                    // At least one day stays chosen: a weekly rule without days would fall on the start's.
                                    weekdays = if (on && weekdays.size > 1) weekdays - weekday else weekdays + weekday
                                }.semantics { contentDescription = long }
                                .testTag("customRepeat.${weekday.name.lowercase()}"),
                    ) {
                        Text(
                            weekdayShort(resources, weekday.dayOfWeek),
                            style = GeunalType.pill,
                            color = if (on) neutrals.background else neutrals.ink,
                        )
                    }
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    RepeatText.of(rule, startDay).text(resources),
                    style = GeunalType.body,
                    color = neutrals.ink,
                    modifier = Modifier.weight(1f).testTag("customRepeat.summary"),
                )
                IconButton(onClick = { interval -= 1 }, enabled = interval > 1) {
                    Icon(
                        painterResource(DesignR.drawable.ds_ic_remove),
                        contentDescription = stringResource(R.string.editor_interval_fewer),
                        tint = if (interval > 1) neutrals.ink else neutrals.tertiaryText,
                        modifier = Modifier.size(22.dp),
                    )
                }
                IconButton(
                    onClick = { interval += 1 },
                    enabled = interval < MAX_INTERVAL,
                    modifier = Modifier.testTag("customRepeat.more"),
                ) {
                    Icon(
                        painterResource(DesignR.drawable.ds_ic_add),
                        contentDescription = stringResource(R.string.editor_interval_more),
                        tint = if (interval < MAX_INTERVAL) neutrals.ink else neutrals.tertiaryText,
                        modifier = Modifier.size(22.dp),
                    )
                }
            }
            GeunalButton(
                text = stringResource(R.string.editor_done),
                onClick = { onDone(rule) },
                modifier = Modifier.testTag("customRepeat.done"),
            )
        }
    }
}

/** Every one to four weeks, like iOS's stepper. */
private const val MAX_INTERVAL = 4

@Composable
internal fun SheetColumn(content: @Composable () -> Unit) {
    Column(
        verticalArrangement = Arrangement.spacedBy(Space.m),
        modifier =
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Metrics.Sheet.paddingSide)
                .padding(top = Space.l, bottom = Metrics.Sheet.paddingBottom),
    ) { content() }
}

@Composable
internal fun SheetTitle(text: String) {
    Text(
        text,
        style = GeunalType.title2,
        color = LocalNeutralColors.current.ink,
        modifier = Modifier.semantics { heading() },
    )
}
