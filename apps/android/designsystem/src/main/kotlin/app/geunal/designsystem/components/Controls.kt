package app.geunal.designsystem.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.geunal.designsystem.GeunalType
import app.geunal.designsystem.LocalDarkTheme
import app.geunal.designsystem.LocalNeutralColors
import app.geunal.designsystem.Metrics
import app.geunal.designsystem.R
import app.geunal.designsystem.Radius
import app.geunal.designsystem.Space

/**
 * The consent row of the sign-in screen: a 24-point box with corner 6 on the grouped background, the whole row one
 * checkbox for touch and for TalkBack (components.md §4).
 */
@Composable
fun CheckboxRow(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    title: String,
    modifier: Modifier = Modifier,
    detail: String? = null,
) {
    val neutrals = LocalNeutralColors.current
    val interaction = remember { MutableInteractionSource() }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier =
            modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(Radius.card))
                .background(neutrals.groupedBackground)
                .toggleable(
                    value = checked,
                    role = Role.Checkbox,
                    interactionSource = interaction,
                    indication = null,
                    onValueChange = onCheckedChange,
                ).pressScale(interaction)
                .heightIn(min = 72.dp)
                .padding(horizontal = Space.l, vertical = Space.s),
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier =
                Modifier
                    .size(Metrics.Checkbox.size)
                    .clip(RoundedCornerShape(Metrics.Checkbox.radius))
                    .background(if (checked) neutrals.ink else neutrals.background)
                    .border(
                        width = 1.5.dp,
                        color = if (checked) neutrals.ink else neutrals.secondaryText,
                        shape = RoundedCornerShape(Metrics.Checkbox.radius),
                    ),
        ) {
            if (checked) {
                Icon(
                    painterResource(R.drawable.ds_ic_check),
                    contentDescription = null,
                    tint = neutrals.background,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
        Column(modifier = Modifier.padding(start = Space.m)) {
            Text(title, style = GeunalType.bodyStrong, color = neutrals.ink)
            if (detail != null) {
                Text(detail, style = GeunalType.caption, color = neutrals.secondaryText)
            }
        }
    }
}

/** A Material switch in the design's colors: ink when on, the separator when off (components.md §4). */
@Composable
fun GeunalSwitch(
    checked: Boolean,
    onCheckedChange: ((Boolean) -> Unit)?,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val neutrals = LocalNeutralColors.current
    Switch(
        checked = checked,
        onCheckedChange = onCheckedChange,
        enabled = enabled,
        modifier = modifier,
        colors =
            SwitchDefaults.colors(
                checkedThumbColor = Color.White,
                checkedTrackColor = neutrals.ink,
                checkedBorderColor = neutrals.ink,
                uncheckedThumbColor = Color.White,
                uncheckedTrackColor = neutrals.separator,
                uncheckedBorderColor = neutrals.separator,
            ),
    )
}

/**
 * 월 · 주 · 목록 and the like: the fill track with the chosen segment on white and a small shadow (components.md §4).
 * Drawn here rather than with Material's segmented buttons, which have a different shape; TalkBack reads each segment
 * as a tab that is selected or not.
 */
@Composable
fun SegmentedControl(
    options: List<String>,
    selected: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val neutrals = LocalNeutralColors.current
    val dark = LocalDarkTheme.current
    Row(
        modifier =
            modifier
                .heightIn(min = Metrics.Segment.height)
                .clip(CircleShape)
                .background(neutrals.fill)
                .padding(Metrics.Segment.padding)
                .selectableGroup(),
        horizontalArrangement = Arrangement.spacedBy(0.dp),
    ) {
        options.forEachIndexed { index, label ->
            val isSelected = index == selected
            Box(
                contentAlignment = Alignment.Center,
                modifier =
                    Modifier
                        .weight(1f)
                        .heightIn(min = Metrics.Segment.height - Metrics.Segment.padding * 2)
                        .then(
                            if (isSelected) {
                                Modifier
                                    .shadow(2.dp, CircleShape, clip = false)
                                    .background(if (dark) neutrals.separator else Color.White, CircleShape)
                            } else {
                                Modifier
                            },
                        ).clip(CircleShape)
                        .selectable(selected = isSelected, role = Role.Tab, onClick = { onSelect(index) })
                        .padding(horizontal = Space.s),
            ) {
                Text(
                    label,
                    style = GeunalType.pill,
                    color = if (isSelected) neutrals.ink else neutrals.secondaryText,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

/** A choice chip: off is the fill with ink, on is ink with white (components.md §3). */
@Composable
fun ChoiceChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val neutrals = LocalNeutralColors.current
    val interaction = remember { MutableInteractionSource() }
    Box(
        contentAlignment = Alignment.Center,
        modifier =
            modifier
                .pressScale(interaction)
                .heightIn(min = Metrics.Chip.select)
                .clip(CircleShape)
                .background(if (selected) neutrals.ink else neutrals.fill)
                .selectable(
                    selected = selected,
                    role = Role.RadioButton,
                    interactionSource = interaction,
                    indication = null,
                    onClick = onClick,
                ).padding(PaddingValues(horizontal = 14.dp)),
    ) {
        Text(label, style = GeunalType.pill, color = if (selected) neutrals.background else neutrals.ink)
    }
}

/** A small status pill: 확정, 가안, D-5 (components.md §3). */
@Composable
fun StatusBadge(
    label: String,
    modifier: Modifier = Modifier,
    container: Color = LocalNeutralColors.current.ink,
    content: Color = LocalNeutralColors.current.background,
) {
    Box(
        contentAlignment = Alignment.Center,
        modifier =
            modifier
                .heightIn(min = Metrics.Chip.badge)
                .clip(CircleShape)
                .background(container)
                .padding(horizontal = 10.dp),
    ) {
        Text(label, style = GeunalType.caption.copy(fontWeight = GeunalType.pill.fontWeight), color = content)
    }
}
