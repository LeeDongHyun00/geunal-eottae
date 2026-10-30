package app.geunal.feature.calendar.quickentry

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
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.geunal.core.Palette
import app.geunal.core.Suggestion
import app.geunal.designsystem.GeunalType
import app.geunal.designsystem.LocalNeutralColors
import app.geunal.designsystem.Metrics
import app.geunal.designsystem.Radius
import app.geunal.designsystem.Space
import app.geunal.designsystem.components.GeunalButton
import app.geunal.designsystem.components.GeunalSheet
import app.geunal.designsystem.components.pressScale
import app.geunal.feature.calendar.R
import app.geunal.feature.calendar.support.CalendarColors
import app.geunal.feature.calendar.support.ChipTime
import app.geunal.feature.calendar.support.text
import app.geunal.designsystem.R as DesignR

/**
 * The quick entry sheet (m1.md screen 5, boards/QuickEntry): the day as the title, five suggestion chips that save at
 * once, then a title and a range on the 0–24 strip, with the calendar and repeat chips and "저장" at the bottom. A chip
 * closes the sheet before the core answers; "저장" keeps it up until then, so a refusal leaves what was typed.
 */
@Composable
fun QuickEntrySheet(
    model: QuickEntryModel,
    actions: EventActions,
    onDismiss: () -> Unit,
) {
    val state by model.state.collectAsStateWithLifecycle()
    // Between "저장" and the core's answer: a second tap must not add the event twice.
    var saving by remember { mutableStateOf(false) }
    LaunchedEffect(model) { model.load() }
    val save = {
        val input = model.typedEvent()
        if (!saving && input != null) {
            saving = true
            actions.launchAdd(input, fromSuggestion = false) { added ->
                saving = false
                if (added) onDismiss()
            }
        }
    }
    GeunalSheet(onDismiss = onDismiss) {
        Column(
            Modifier
                .fillMaxWidth()
                .height(sheetHeight())
                .padding(horizontal = Metrics.Sheet.paddingSide)
                .padding(bottom = Metrics.Sheet.paddingBottom),
        ) {
            Column(
                verticalArrangement = Arrangement.spacedBy(18.dp),
                modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(top = Space.s),
            ) {
                Header(model, onClose = onDismiss)
                if (state.suggestions.isNotEmpty()) {
                    Suggestions(state.suggestions, color = model::color) { suggestion ->
                        onDismiss()
                        actions.launchAdd(suggestion.event, fromSuggestion = true)
                    }
                }
                Box(Modifier.fillMaxWidth().height(1.dp).background(LocalNeutralColors.current.separator))
                Typed(state, model, onDone = save)
            }
            BottomRow(state, model, saving = saving, onSave = save)
        }
    }
}

/**
 * 552 high in the board, grabber included; it grows with the font scale as iOS's does with Dynamic Type, never past
 * 90% of the window, and the content scrolls inside it when it does not fit.
 */
@Composable
private fun sheetHeight() =
    with(LocalDensity.current) {
        val window =
            LocalWindowInfo.current.containerSize.height
                .toDp()
        val board = (SHEET_HEIGHT - Metrics.Sheet.paddingTop - Metrics.Sheet.grabberHeight) * fontScale.coerceAtLeast(1f)
        minOf(board, window * Metrics.Sheet.MAX_HEIGHT_RATIO)
    }

@Composable
private fun Header(
    model: QuickEntryModel,
    onClose: () -> Unit,
) {
    val neutrals = LocalNeutralColors.current
    Row(verticalAlignment = Alignment.Top) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.weight(1f)) {
            Text(
                model.heading?.text() ?: model.day,
                style = GeunalType.title2.copy(fontSize = 22.sp, letterSpacing = (-0.02).em),
                color = neutrals.ink,
                modifier = Modifier.semantics { heading() },
            )
            if (model.notes.isNotEmpty()) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Icon(
                        painterResource(DesignR.drawable.ds_ic_gift),
                        contentDescription = null,
                        tint = neutrals.secondaryText,
                        modifier = Modifier.size(14.dp),
                    )
                    Text(model.notes.joinToString(" · "), style = GeunalType.caption.copy(fontSize = 14.sp), color = neutrals.secondaryText)
                }
            }
        }
        IconButton(
            onClick = onClose,
            modifier = Modifier.offset(x = 12.dp, y = (-6).dp).size(Metrics.minHitTarget).testTag("quickEntry.close"),
        ) {
            Icon(
                painterResource(DesignR.drawable.ds_ic_close),
                contentDescription = stringResource(R.string.quick_entry_close),
                tint = neutrals.secondaryText,
                modifier = Modifier.size(22.dp),
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Suggestions(
    suggestions: List<Suggestion>,
    color: (Suggestion) -> Palette?,
    onPick: (Suggestion) -> Unit,
) {
    val neutrals = LocalNeutralColors.current
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Icon(
                painterResource(DesignR.drawable.ds_ic_sparkle),
                contentDescription = null,
                tint = neutrals.secondaryText,
                modifier = Modifier.size(14.dp),
            )
            Text(
                stringResource(R.string.quick_entry_suggestions),
                style = GeunalType.caption.copy(fontWeight = FontWeight.SemiBold),
                color = neutrals.secondaryText,
            )
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(Space.xs), verticalArrangement = Arrangement.spacedBy(Space.xs)) {
            suggestions.forEachIndexed { index, suggestion ->
                SuggestionChip(
                    suggestion,
                    color = color(suggestion),
                    onClick = { onPick(suggestion) },
                    modifier = Modifier.testTag("quickEntry.chip.$index"),
                )
            }
        }
    }
}

/**
 * A suggestion chip (components.md §3): white with a 1 border, the calendar's dot, the title in 15 · 600 and the time
 * in 15. It saves as soon as it is tapped.
 */
@Composable
private fun SuggestionChip(
    suggestion: Suggestion,
    color: Palette?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val neutrals = LocalNeutralColors.current
    val interaction = remember { MutableInteractionSource() }
    val event = suggestion.event
    val hint = stringResource(R.string.quick_entry_chip_hint)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Space.xs),
        modifier =
            modifier
                .pressScale(interaction)
                .heightIn(min = Metrics.Chip.suggestion)
                .clip(CircleShape)
                .background(neutrals.background)
                .border(1.dp, neutrals.separator, CircleShape)
                .clickable(interactionSource = interaction, indication = null, role = Role.Button, onClickLabel = hint, onClick = onClick)
                .padding(horizontal = Space.m),
    ) {
        Box(Modifier.size(Metrics.Chip.dot).background(CalendarColors.marker(color), CircleShape))
        Text(event.title, style = GeunalType.buttonSmall, color = neutrals.ink)
        if (!event.allDay) {
            Text(
                ChipTime.of(event.start.minute.toInt(), chipEnd(suggestion)),
                style = GeunalType.secondary.copy(fontFeatureSettings = "tnum"),
                color = neutrals.secondaryText,
            )
        }
    }
}

/**
 * The end as the chip says it: midnight at the end of the day reads 24, while a range past midnight gives the next
 * day's hour ("22–02").
 */
internal fun chipEnd(suggestion: Suggestion): Int {
    val event = suggestion.event
    val end = event.end.minute.toInt()
    return if (event.end.day != event.start.day && end == 0) 1440 else end
}

@Composable
private fun Typed(
    state: QuickEntryModel.State,
    model: QuickEntryModel,
    onDone: () -> Unit,
) {
    val neutrals = LocalNeutralColors.current
    Column(verticalArrangement = Arrangement.spacedBy(Space.s)) {
        Text(
            stringResource(R.string.quick_entry_typed),
            style = GeunalType.caption.copy(fontWeight = FontWeight.SemiBold),
            color = neutrals.secondaryText,
        )
        val placeholder = stringResource(R.string.quick_entry_title)
        BasicTextField(
            value = state.title,
            onValueChange = model::setTitle,
            singleLine = true,
            textStyle = GeunalType.body.copy(color = neutrals.ink),
            cursorBrush = SolidColor(neutrals.ink),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { onDone() }),
            modifier =
                Modifier
                    .fillMaxWidth()
                    // An empty field says what it is for; a filled one reads what was typed.
                    .semantics { if (state.title.isEmpty()) contentDescription = placeholder }
                    .testTag("quickEntry.title"),
            decorationBox = { field ->
                Box(
                    contentAlignment = Alignment.CenterStart,
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .height(48.dp)
                            .clip(RoundedCornerShape(Radius.input))
                            .background(neutrals.groupedBackground)
                            .padding(horizontal = Space.m),
                ) {
                    if (state.title.isEmpty()) Text(placeholder, style = GeunalType.body, color = neutrals.tertiaryText)
                    field()
                }
            },
        )
        TimeStrip(range = state.range, onRange = model::setRange)
    }
}

@Composable
private fun BottomRow(
    state: QuickEntryModel.State,
    model: QuickEntryModel,
    saving: Boolean,
    onSave: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Space.xs),
        modifier = Modifier.fillMaxWidth().padding(top = Space.s),
    ) {
        CalendarMenu(state, model)
        RepeatMenu(state, model)
        Spacer(Modifier.weight(1f))
        GeunalButton(
            text = stringResource(if (state.needsTitle) R.string.quick_entry_needs_title else R.string.quick_entry_save),
            onClick = onSave,
            enabled = state.canSave && !saving,
            height = Metrics.Button.secondary,
            fullWidth = false,
            modifier = Modifier.testTag("quickEntry.save"),
        )
    }
}

@Composable
private fun CalendarMenu(
    state: QuickEntryModel.State,
    model: QuickEntryModel,
) {
    var open by remember { mutableStateOf(false) }
    val selected = state.selectedCalendar
    val label = stringResource(R.string.quick_entry_calendar)
    Box {
        MenuChip(
            onClick = { open = true },
            label = label,
            value = selected?.name.orEmpty(),
            modifier = Modifier.testTag("quickEntry.calendar"),
        ) {
            Box(Modifier.size(Metrics.Chip.dot).background(CalendarColors.marker(selected?.color), CircleShape))
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            state.calendars.forEach { calendar ->
                DropdownMenuItem(
                    text = { Text(calendar.name, style = GeunalType.body) },
                    leadingIcon = { Box(Modifier.size(Metrics.Chip.dot).background(CalendarColors.marker(calendar.color), CircleShape)) },
                    onClick = {
                        model.setCalendar(calendar.id)
                        open = false
                    },
                )
            }
        }
    }
}

@Composable
private fun RepeatMenu(
    state: QuickEntryModel.State,
    model: QuickEntryModel,
) {
    var open by remember { mutableStateOf(false) }
    val none = stringResource(R.string.calendar_repeat_none)
    val label = stringResource(R.string.quick_entry_repeat)
    val ink = LocalNeutralColors.current.ink
    Box {
        MenuChip(
            onClick = { open = true },
            label = label,
            value = model.label(of = state.repeat)?.text() ?: none,
            modifier = Modifier.testTag("quickEntry.repeat"),
        ) {
            Icon(painterResource(DesignR.drawable.ds_ic_repeat), contentDescription = null, tint = ink, modifier = Modifier.size(14.dp))
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            QuickEntryModel.Repeat.entries.forEach { choice ->
                DropdownMenuItem(
                    text = { Text(model.label(of = choice)?.text() ?: none, style = GeunalType.body) },
                    onClick = {
                        model.setRepeat(choice)
                        open = false
                    },
                )
            }
        }
    }
}

/** The calendar and repeat chips at the bottom (boards/QuickEntry): the fill pill, 44 high, that opens a menu. */
@Composable
private fun MenuChip(
    onClick: () -> Unit,
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    leading: @Composable () -> Unit,
) {
    val neutrals = LocalNeutralColors.current
    val interaction = remember { MutableInteractionSource() }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        modifier =
            modifier
                .pressScale(interaction)
                .heightIn(min = Metrics.Button.secondary)
                .clip(CircleShape)
                .background(neutrals.fill)
                .clickable(interactionSource = interaction, indication = null, role = Role.Button, onClick = onClick)
                // TalkBack reads what the chip chooses and what it holds, "캘린더, 개인", not the dot or icon. After the
                // click in the chain, so the click stays on the node.
                .clearAndSetSemantics {
                    contentDescription = label
                    stateDescription = value
                }.padding(horizontal = 14.dp),
    ) {
        leading()
        Text(value, style = GeunalType.pill, color = neutrals.ink, maxLines = 1)
    }
}

/** The board's sheet height, grabber included. */
private val SHEET_HEIGHT = 552.dp
