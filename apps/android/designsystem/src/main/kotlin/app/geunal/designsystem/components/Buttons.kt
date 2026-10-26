package app.geunal.designsystem.components

import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.geunal.designsystem.GeunalType
import app.geunal.designsystem.LocalNeutralColors
import app.geunal.designsystem.Metrics

/** The kinds of components.md §2. Each has one height and one pair of colors, and draws "off" the same way. */
enum class ButtonKind {
    /** One per screen, at the bottom: 시작하기, 저장, 캘린더 열기. */
    Primary,

    /** Next to the primary one: 자세히, 다음에. */
    Secondary,

    /** Cancels and deletions: red text on the fill, never a filled red. */
    Danger,

    /** Text only: 취소, 나중에 할게요. */
    Text,

    /** The small pill in headers and cards: 붙여넣기, 복사. */
    SmallPill,

    /** A pill on a grouped card: the screen background with ink, like an answered permission card (m1.md screen 3). */
    Surface,

    /**
     * The one filled red button, for the step that deletes what cannot come back once confirmed ("백업 3개 지우고 새로
     * 시작", m1.md screen 21). Elsewhere danger stays red text on the fill.
     */
    DangerFilled,
}

/**
 * A button of the design system. Turned off, it draws the fill with tertiary text and no transparency, and the label
 * should say why (components.md §1), so callers pass the label that explains it.
 */
@Composable
fun GeunalButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    kind: ButtonKind = ButtonKind.Primary,
    enabled: Boolean = true,
    height: Dp = defaultHeight(kind),
    leadingIcon: Painter? = null,
    /** Bottom buttons fill the width (components.md §2); pills and text buttons are as wide as their label. */
    fullWidth: Boolean =
        kind == ButtonKind.Primary || kind == ButtonKind.Secondary || kind == ButtonKind.Danger || kind == ButtonKind.DangerFilled,
) {
    val neutrals = LocalNeutralColors.current
    val (container, content) =
        when (kind) {
            ButtonKind.Primary -> neutrals.ink to neutrals.background
            ButtonKind.Secondary, ButtonKind.SmallPill -> neutrals.fill to neutrals.ink
            ButtonKind.Danger -> neutrals.fill to neutrals.sunday
            ButtonKind.Text -> Color.Transparent to neutrals.ink
            ButtonKind.Surface -> neutrals.background to neutrals.ink
            ButtonKind.DangerFilled -> neutrals.sunday to Color.White
        }
    val disabledContainer = if (kind == ButtonKind.Text) Color.Transparent else neutrals.fill
    val interaction = remember { MutableInteractionSource() }
    Button(
        onClick = onClick,
        enabled = enabled,
        shape = CircleShape,
        interactionSource = interaction,
        colors =
            ButtonDefaults.buttonColors(
                containerColor = container,
                contentColor = content,
                disabledContainerColor = disabledContainer,
                disabledContentColor = neutrals.tertiaryText,
            ),
        elevation = null,
        contentPadding = PaddingValues(horizontal = if (height < Metrics.Button.primary) 16.dp else 22.dp),
        modifier =
            modifier
                .pressScale(interaction)
                .then(if (fullWidth) Modifier.fillMaxWidth() else Modifier)
                .heightIn(min = height)
                .defaultMinSize(minWidth = Metrics.minHitTarget),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
            if (leadingIcon != null) {
                Icon(leadingIcon, contentDescription = null, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
            }
            Text(text = text, style = labelStyle(kind, height), textAlign = TextAlign.Center)
        }
    }
}

private fun defaultHeight(kind: ButtonKind): Dp =
    when (kind) {
        ButtonKind.Primary, ButtonKind.Danger, ButtonKind.DangerFilled, ButtonKind.Secondary -> Metrics.Button.primary
        ButtonKind.Text -> Metrics.Button.text
        ButtonKind.SmallPill -> Metrics.Button.smallPill
        ButtonKind.Surface -> 40.dp
    }

/** 17 · 600 on the 52-point buttons, 15 · 600 on shorter ones, 14 · 600 on the small pill (components.md §2). */
private fun labelStyle(
    kind: ButtonKind,
    height: Dp,
): TextStyle =
    when {
        kind == ButtonKind.Text -> GeunalType.body.copy(lineHeight = GeunalType.button.lineHeight)
        kind == ButtonKind.SmallPill -> GeunalType.pill
        height < Metrics.Button.primary -> GeunalType.buttonSmall
        else -> GeunalType.button
    }
