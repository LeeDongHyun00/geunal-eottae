package app.geunal.feature.calendar.shift

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.geunal.core.Palette
import app.geunal.core.ShiftTemplate
import app.geunal.designsystem.GeunalType
import app.geunal.designsystem.GroupColors
import app.geunal.designsystem.LocalDarkTheme
import app.geunal.designsystem.LocalNeutralColors
import app.geunal.designsystem.Metrics
import app.geunal.designsystem.Radius
import app.geunal.designsystem.Space
import app.geunal.designsystem.components.GeunalButton
import app.geunal.designsystem.components.NoticeCard
import app.geunal.designsystem.components.NoticeKind
import app.geunal.designsystem.components.pressScale
import app.geunal.feature.calendar.R
import app.geunal.feature.calendar.month.DayColors
import app.geunal.feature.calendar.month.WeekdayRow
import app.geunal.feature.calendar.support.BarColors
import app.geunal.feature.calendar.support.DayText
import app.geunal.feature.calendar.support.DayTitle
import app.geunal.feature.calendar.support.Heading
import app.geunal.feature.calendar.support.KoreanCalendar
import app.geunal.feature.calendar.support.groupColor
import app.geunal.feature.calendar.support.text
import app.geunal.feature.calendar.support.title
import app.geunal.designsystem.R as DesignR

/**
 * The shift stamp screen (m1.md screen 10, boards/Shift), full-screen above the tabs: 취소 · 교대 찍기 · 완료, the month
 * with the stamped templates, and the template panel. Taps stay a draft until "완료"; [onClose] runs on 취소 and after
 * the days were saved.
 */
@Composable
fun ShiftStampScreen(
    model: ShiftStampModel,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by model.state.collectAsStateWithLifecycle()
    val editing by model.editing.collectAsStateWithLifecycle()
    val neutrals = LocalNeutralColors.current
    val haptics = LocalHapticFeedback.current
    LaunchedEffect(model) { model.start() }
    Column(
        modifier
            .fillMaxSize()
            .background(neutrals.background)
            .statusBarsPadding(),
    ) {
        StampTopBar(
            canFinish = state.hasChanges && !state.isSaving,
            onCancel = onClose,
            onDone = { model.launchCommit { if (it) onClose() } },
        )
        MonthBar(month = state.month, onStep = model::step)
        WeekdayRow(Modifier.padding(horizontal = Space.xs))
        Column(
            Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Space.xs),
        ) {
            if (state.failed) {
                NoticeCard(
                    stringResource(R.string.calendar_save_failed),
                    kind = NoticeKind.Problem,
                    modifier = Modifier.padding(vertical = Space.xs),
                )
            }
            state.weeks.forEach { week ->
                Box {
                    Row {
                        week.forEach { day ->
                            StampCell(
                                day = day,
                                inMonth = KoreanCalendar.month(day) == state.month,
                                templates = state.templates(on = day),
                                enabled = state.selectedTemplate != null && !state.isSaving,
                                onClick = {
                                    model.tap(day)
                                    haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
                                },
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                    Box(Modifier.fillMaxWidth().height(1.dp).background(neutrals.separator))
                }
            }
        }
        TemplatePanel(state = state, model = model)
    }
    editing?.let { template ->
        TemplateEditSheet(
            model = template,
            onSaved = model::launchTemplateSaved,
            onDeleted = model::launchTemplateDeleted,
            onDismiss = model::closeTemplate,
        )
    }
}

@Composable
private fun StampTopBar(
    canFinish: Boolean,
    onCancel: () -> Unit,
    onDone: () -> Unit,
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
                    .testTag("shift.cancel"),
        ) {
            Text(stringResource(R.string.editor_cancel), style = GeunalType.body, color = neutrals.secondaryText)
        }
        Text(
            stringResource(R.string.shift_title),
            style = GeunalType.bodyStrong,
            color = neutrals.ink,
            textAlign = TextAlign.Center,
            modifier = Modifier.weight(1f).semantics { heading() },
        )
        GeunalButton(
            text = stringResource(R.string.editor_done),
            onClick = onDone,
            enabled = canFinish,
            height = Metrics.Button.smallPill,
            fullWidth = false,
            modifier = Modifier.testTag("shift.done"),
        )
    }
}

/** ‹ 11월 › at the left, like boards/Shift. */
@Composable
private fun MonthBar(
    month: String,
    onStep: (Long) -> Unit,
) {
    val neutrals = LocalNeutralColors.current
    val resources = LocalResources.current
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = Space.xs)) {
        IconButton(onClick = { onStep(-1) }, modifier = Modifier.testTag("shift.previousMonth")) {
            Icon(
                painterResource(DesignR.drawable.ds_ic_chevron_left),
                contentDescription = stringResource(R.string.shift_previous_month),
                tint = neutrals.ink,
            )
        }
        Text(
            Heading.month(month)?.title(resources).orEmpty(),
            style = GeunalType.title2,
            color = neutrals.ink,
            textAlign = TextAlign.Center,
            modifier = Modifier.widthIn(min = 56.dp).semantics { heading() },
        )
        IconButton(onClick = { onStep(1) }, modifier = Modifier.testTag("shift.nextMonth")) {
            Icon(
                painterResource(DesignR.drawable.ds_ic_chevron_right),
                contentDescription = stringResource(R.string.shift_next_month),
                tint = neutrals.ink,
            )
        }
    }
}

/** A day of the stamp grid: the date and the stamped template as a chip in its color. */
@Composable
private fun StampCell(
    day: String,
    inMonth: Boolean,
    templates: List<ShiftTemplate>,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val neutrals = LocalNeutralColors.current
    val resources = LocalResources.current
    val date = KoreanCalendar.parse(day)
    val label = (listOfNotNull(DayTitle.of(day, day)?.text(resources)) + templates.map { it.name }).joinToString(", ")
    val interaction = remember { MutableInteractionSource() }
    Column(
        verticalArrangement = Arrangement.spacedBy(3.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier =
            modifier
                .heightIn(min = 60.dp)
                .pressScale(interaction)
                .clickable(interactionSource = interaction, indication = null, enabled = enabled, onClick = onClick)
                // Ahead of clearAndSetSemantics, which drops whatever comes after it in the chain, a tag included.
                .testTag("shift.day.$day")
                .clearAndSetSemantics {
                    contentDescription = label
                    role = Role.Button
                }.padding(top = 5.dp, start = 2.dp, end = 2.dp, bottom = 4.dp),
    ) {
        Text(
            "${date?.dayOfMonth ?: 0}",
            style = GeunalType.caption.copy(fontWeight = FontWeight.SemiBold),
            color = if (inMonth && date != null) DayColors.date(date.dayOfWeek, holiday = false) else neutrals.tertiaryText,
        )
        templates.forEach { template ->
            val colors = ShiftColors.chip(template.color, template.tint.toInt())
            Box(
                contentAlignment = Alignment.Center,
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .heightIn(min = 18.dp)
                        .clip(RoundedCornerShape(Radius.strip))
                        .background(colors.fill),
            ) {
                Text(
                    template.name,
                    style = GeunalType.micro,
                    color = colors.text,
                    maxLines = 1,
                    overflow = TextOverflow.Clip,
                )
            }
        }
    }
}

@Composable
private fun TemplatePanel(
    state: ShiftStampModel.State,
    model: ShiftStampModel,
) {
    val neutrals = LocalNeutralColors.current
    val resources = LocalResources.current
    val shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)
    Column(
        verticalArrangement = Arrangement.spacedBy(Space.s),
        modifier =
            Modifier
                .fillMaxWidth()
                .shadow(
                    16.dp,
                    shape,
                    clip = false,
                    ambientColor = Color.Black.copy(alpha = 0.1f),
                    spotColor = Color.Black.copy(alpha = 0.1f),
                ).background(neutrals.background, shape)
                .navigationBarsPadding()
                .padding(start = Space.m, end = Space.m, top = 18.dp, bottom = Space.l),
    ) {
        if (state.hasLoaded && state.templates.isEmpty()) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(Space.s),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.shift_first_template), style = GeunalType.bodyStrong, color = neutrals.ink)
                GeunalButton(
                    text = stringResource(R.string.shift_new_template),
                    onClick = { model.editTemplate(null) },
                    modifier = Modifier.testTag("shift.firstTemplate"),
                )
            }
            return@Column
        }
        Row(verticalAlignment = Alignment.Bottom, modifier = Modifier.padding(horizontal = Space.xxs)) {
            Text(
                stringResource(R.string.shift_pick),
                style = GeunalType.bodyStrong,
                color = neutrals.ink,
                modifier = Modifier.weight(1f),
            )
            Text(
                stringResource(R.string.shift_count, Heading.month(state.month)?.title(resources).orEmpty(), state.countThisMonth),
                style = GeunalType.caption,
                color = neutrals.secondaryText,
            )
        }
        // Two columns of templates, then the dashed "새 템플릿". Past two and a half rows they scroll, so many templates
        // never squeeze the grid above out of the screen.
        val cells: List<ShiftTemplate?> = state.templates + null
        Column(
            verticalArrangement = Arrangement.spacedBy(Space.s),
            modifier = Modifier.heightIn(max = TEMPLATES_MAX_HEIGHT).verticalScroll(rememberScrollState()),
        ) {
            cells.chunked(2).forEach { pair ->
                Row(horizontalArrangement = Arrangement.spacedBy(Space.xs)) {
                    pair.forEach { template ->
                        if (template == null) {
                            NewTemplateButton(onClick = { model.editTemplate(null) }, modifier = Modifier.weight(1f))
                        } else {
                            TemplateCard(
                                template = template,
                                selected = template.id == state.selectedTemplateId,
                                onSelect = { model.select(template.id) },
                                onEdit = { model.editTemplate(template) },
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                    if (pair.size == 1) Spacer(Modifier.weight(1f))
                }
            }
        }
        state.selectedTemplate?.let { state.calendarName(of = it) }?.let { calendar ->
            Text(
                stringResource(R.string.shift_calendar_note, calendar),
                style = GeunalType.caption.copy(lineHeight = 19.sp),
                color = neutrals.secondaryText,
                modifier = Modifier.padding(horizontal = Space.xxs),
            )
        }
    }
}

/** A template in the panel (boards/Shift): its color bar, name and hours; an ink ring and a check when chosen. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TemplateCard(
    template: ShiftTemplate,
    selected: Boolean,
    onSelect: () -> Unit,
    onEdit: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val neutrals = LocalNeutralColors.current
    val editLabel = stringResource(R.string.shift_edit_template)
    val interaction = remember { MutableInteractionSource() }
    val shape = RoundedCornerShape(16.dp)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        modifier =
            modifier
                .heightIn(min = 60.dp)
                .pressScale(interaction)
                .clip(shape)
                .background(neutrals.background)
                .border(if (selected) 2.dp else 1.dp, if (selected) neutrals.ink else neutrals.separator, shape)
                // A long press opens the template, like iOS; TalkBack offers the same as an action.
                .combinedClickable(
                    interactionSource = interaction,
                    indication = null,
                    role = Role.RadioButton,
                    onLongClickLabel = editLabel,
                    onLongClick = onEdit,
                    onClick = onSelect,
                ).semantics {
                    this.selected = selected
                    customActions =
                        listOf(
                            CustomAccessibilityAction(editLabel) {
                                onEdit()
                                true
                            },
                        )
                }.padding(horizontal = 14.dp)
                .testTag("shift.template.${template.name}"),
    ) {
        Box(
            Modifier
                .size(width = 12.dp, height = 36.dp)
                .clip(RoundedCornerShape(Radius.strip))
                .background(ShiftColors.chip(template.color, template.tint.toInt()).fill),
        )
        Column(Modifier.weight(1f)) {
            Text(
                template.name,
                style = GeunalType.bodyStrong.copy(fontSize = 16.sp),
                color = neutrals.ink,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                DayText.range(template.startMinute.toInt(), template.endMinute.toInt()),
                style = GeunalType.caption,
                color = neutrals.secondaryText,
            )
        }
        if (selected) {
            Icon(
                painterResource(DesignR.drawable.ds_ic_check),
                contentDescription = null,
                tint = neutrals.ink,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

@Composable
private fun NewTemplateButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val neutrals = LocalNeutralColors.current
    val interaction = remember { MutableInteractionSource() }
    val dash = neutrals.tertiaryText
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
        modifier =
            modifier
                .heightIn(min = 60.dp)
                .pressScale(interaction)
                .drawBehind {
                    drawRoundRect(
                        color = dash,
                        cornerRadius = CornerRadius(16.dp.toPx()),
                        style =
                            Stroke(
                                width = 1.5.dp.toPx(),
                                pathEffect = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 3.dp.toPx())),
                            ),
                    )
                }.clip(RoundedCornerShape(16.dp))
                .clickable(interactionSource = interaction, indication = null, role = Role.Button, onClick = onClick)
                .testTag("shift.newTemplate"),
    ) {
        Icon(
            painterResource(DesignR.drawable.ds_ic_add),
            contentDescription = null,
            tint = neutrals.secondaryText,
            modifier = Modifier.size(18.dp),
        )
        Spacer(Modifier.width(Space.xxs))
        Text(stringResource(R.string.shift_new_template), style = GeunalType.buttonSmall, color = neutrals.secondaryText)
    }
}

/** Template colors: the calendar color in the template's tint (1 lightest – 5 darkest), white text from tint 4. */
object ShiftColors {
    @Composable
    fun chip(
        palette: Palette?,
        tint: Int,
    ): BarColors {
        val level = tint.coerceIn(1, 5)
        // A new template's calendar is the '알바' the core makes later; the board draws it in mint.
        val group = palette?.groupColor ?: GroupColors.first { it.name == "mint" }
        val ramp = if (LocalDarkTheme.current) group.dark else group.light
        return BarColors(ramp[level - 1], if (level >= 4) Color.White else LocalNeutralColors.current.ink)
    }
}

/** About two and a half rows of template cards. */
private val TEMPLATES_MAX_HEIGHT = 172.dp
