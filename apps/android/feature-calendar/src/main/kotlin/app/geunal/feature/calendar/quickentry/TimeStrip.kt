package app.geunal.feature.calendar.quickentry

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.geunal.designsystem.GeunalType
import app.geunal.designsystem.LocalNeutralColors
import app.geunal.designsystem.Metrics
import app.geunal.designsystem.Radius
import app.geunal.feature.calendar.R
import app.geunal.feature.calendar.support.DayText
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * The 0–24 strip of the quick entry (m1.md screen 5, components.md §8 "선택 범위"): drag across it to choose a range
 * in half hours, then drag either end to adjust it. A tap picks the hour under the finger. [range] holds half-hour
 * slots from midnight, both ends included; `null` is all day.
 */
@Composable
fun TimeStrip(
    range: IntRange?,
    onRange: (IntRange?) -> Unit,
    modifier: Modifier = Modifier,
) {
    val neutrals = LocalNeutralColors.current
    val current by rememberUpdatedState(range)
    val update by rememberUpdatedState(onRange)
    val label = stringResource(R.string.quick_entry_strip)
    val value = range?.let { DayText.range(it.first * 30, (it.last + 1) * 30) } ?: stringResource(R.string.calendar_all_day)
    val longer = stringResource(R.string.quick_entry_longer)
    val shorter = stringResource(R.string.quick_entry_shorter)
    val allDay = stringResource(R.string.quick_entry_all_day)
    Column(
        verticalArrangement = Arrangement.spacedBy(6.dp),
        modifier =
            modifier
                // Ahead of clearAndSetSemantics, which drops whatever comes after it in the chain, a tag included.
                .testTag("quickEntry.strip")
                // One control for TalkBack: swiping up and down moves the range by half an hour, like iOS's adjustable.
                .clearAndSetSemantics {
                    contentDescription = label
                    stateDescription = value
                    progressBarRangeInfo =
                        ProgressBarRangeInfo((range?.first ?: 0).toFloat(), 0f..(SLOTS - 1).toFloat(), steps = SLOTS - 2)
                    setProgress { target ->
                        update(StripMath.moved(current, target.roundToInt()))
                        true
                    }
                    customActions =
                        listOf(
                            CustomAccessibilityAction(longer) {
                                update(StripMath.resized(current, 1))
                                true
                            },
                            CustomAccessibilityAction(shorter) {
                                update(StripMath.resized(current, -1))
                                true
                            },
                            CustomAccessibilityAction(allDay) {
                                update(null)
                                true
                            },
                        )
                },
    ) {
        BoxWithConstraints(
            Modifier
                .fillMaxWidth()
                .height(Metrics.Grid.handleHeight)
                .clip(RoundedCornerShape(Radius.input))
                .background(neutrals.emptyCell)
                .drawBehind {
                    // A line every hour, a little stronger every six.
                    for (hour in 1 until 24) {
                        val x = size.width * hour / 24
                        drawLine(
                            neutrals.separator,
                            Offset(x, 0f),
                            Offset(x, size.height),
                            strokeWidth =
                                (
                                    if (hour % 6 ==
                                        0
                                    ) {
                                        1.5.dp
                                    } else {
                                        1.dp
                                    }
                                ).toPx(),
                        )
                    }
                }.pointerInput(Unit) {
                    val reach = HANDLE_REACH.toPx()
                    val slop = TAP_SLOP.toPx()
                    awaitEachGesture {
                        val down = awaitFirstDown()
                        val width = size.width.toFloat()
                        val drag = StripMath.startDrag(current, down.position.x, width, reach)
                        down.consume()
                        var moved = false
                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull { it.id == down.id } ?: break
                            if (!change.pressed) {
                                if (drag is StripMath.Drag.New && !moved) update(StripMath.tapped(change.position.x, width))
                                change.consume()
                                break
                            }
                            if (abs(change.position.x - down.position.x) > slop) moved = true
                            if (moved || drag !is StripMath.Drag.New) {
                                StripMath.dragged(current, drag, change.position.x, width)?.let { update(it) }
                            }
                            change.consume()
                        }
                    }
                },
        ) {
            val step: Dp = maxWidth / SLOTS
            if (range == null) {
                Text(
                    stringResource(R.string.quick_entry_strip_hint),
                    style = GeunalType.caption,
                    color = neutrals.secondaryText,
                    modifier = Modifier.fillMaxSize().wrapContentSize(),
                )
            } else {
                val start = step * range.first
                val end = step * (range.last + 1)
                Box(
                    Modifier
                        .offset(x = start)
                        .width(maxOf(end - start, 8.dp))
                        .fillMaxHeight()
                        .clip(RoundedCornerShape(Radius.input))
                        .background(neutrals.ink.copy(alpha = 0.08f))
                        .border(2.dp, neutrals.ink, RoundedCornerShape(Radius.input)),
                )
                Handle(Modifier.offset(x = start - Metrics.Grid.handleWidth / 2))
                Handle(Modifier.offset(x = end - Metrics.Grid.handleWidth / 2))
            }
        }
        Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
            listOf(0, 6, 12, 18, 24).forEach { hour ->
                Text("$hour", style = GeunalType.micro.copy(fontFeatureSettings = "tnum"), color = neutrals.tertiaryText)
            }
        }
    }
}

/** A selection handle (components.md §8: 20 × 44 with a 4 × 24 ink grip). */
@Composable
private fun Handle(modifier: Modifier = Modifier) {
    val ink = LocalNeutralColors.current.ink
    Box(contentAlignment = Alignment.Center, modifier = modifier.size(Metrics.Grid.handleWidth, Metrics.Grid.handleHeight)) {
        Box(Modifier.size(4.dp, 24.dp).background(ink, CircleShape))
    }
}

private const val SLOTS = QuickEntryModel.SLOTS

/** How far from a handle a press still takes that end rather than starting a new range. */
private val HANDLE_REACH = 22.dp

/** Movement under this is a tap. */
private val TAP_SLOP = 4.dp

/** The strip's arithmetic, apart from the drawing so it can be tested on the JVM. */
internal object StripMath {
    sealed interface Drag {
        data class New(
            val anchor: Int,
        ) : Drag

        data object Lower : Drag

        data object Upper : Drag
    }

    /** The half-hour slot under [x], 0 to 47. */
    fun slot(
        x: Float,
        width: Float,
    ): Int = (x / width * SLOTS).toInt().coerceIn(0, SLOTS - 1)

    /**
     * The half-hour boundary nearest to [x], 0 to 48. An end is a boundary, not a slot: pressing the 11:00 handle of
     * 09:00–11:00 must leave it at 11:00.
     */
    fun boundary(
        x: Float,
        width: Float,
    ): Int = (x / width * SLOTS).roundToInt().coerceIn(0, SLOTS)

    /** A press within a handle's reach adjusts that end; anywhere else starts a new range. */
    fun startDrag(
        range: IntRange?,
        x: Float,
        width: Float,
        reach: Float,
    ): Drag {
        if (range != null) {
            val step = width / SLOTS
            if (abs(x - (range.last + 1) * step) <= reach) return Drag.Upper
            if (abs(x - range.first * step) <= reach) return Drag.Lower
        }
        return Drag.New(slot(x, width))
    }

    fun dragged(
        range: IntRange?,
        drag: Drag,
        x: Float,
        width: Float,
    ): IntRange? =
        when (drag) {
            is Drag.New -> {
                val here = slot(x, width)
                minOf(drag.anchor, here)..maxOf(drag.anchor, here)
            }
            Drag.Lower -> range?.let { minOf(boundary(x, width), it.last)..it.last }
            Drag.Upper -> range?.let { it.first..(maxOf(boundary(x, width), it.first + 1) - 1) }
        }

    /** A tap takes the whole hour it lands on. */
    fun tapped(
        x: Float,
        width: Float,
    ): IntRange {
        val hour = slot(x, width) / 2 * 2
        return hour..minOf(hour + 1, SLOTS - 1)
    }

    /** TalkBack's slider steps: the range moves to start at [first], keeping its length; nothing chosen yet is 09:00–10:00. */
    fun moved(
        range: IntRange?,
        first: Int,
    ): IntRange {
        if (range == null) return DEFAULT
        val length = range.last - range.first
        val start = first.coerceIn(0, SLOTS - 1 - length)
        return start..(start + length)
    }

    /** "30분 늘리기" and "30분 줄이기": the end moves, never past midnight or before the start. */
    fun resized(
        range: IntRange?,
        delta: Int,
    ): IntRange {
        if (range == null) return DEFAULT
        return range.first..(range.last + delta).coerceIn(range.first, SLOTS - 1)
    }

    private val DEFAULT = 18..19
}
