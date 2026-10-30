package app.geunal.designsystem.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import app.geunal.designsystem.GeunalType
import app.geunal.designsystem.LocalDarkTheme
import app.geunal.designsystem.LocalNeutralColors
import app.geunal.designsystem.Metrics
import app.geunal.designsystem.R
import app.geunal.designsystem.Radius
import app.geunal.designsystem.Space
import app.geunal.designsystem.problemBackground

/** A group of setting rows on the grouped background, with a 1-point separator between rows (components.md §5). */
@Composable
fun SettingsGroup(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier =
            modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(Radius.card))
                .background(LocalNeutralColors.current.groupedBackground),
        content = content,
    )
}

/** The separator between two rows of a [SettingsGroup], inset like the row text. */
@Composable
fun SettingsDivider() {
    HorizontalDivider(
        thickness = 1.dp,
        color = LocalNeutralColors.current.separator,
        modifier = Modifier.padding(start = Space.m),
    )
}

/** A 52-point row: title, value in secondary text, and a chevron when it opens something (components.md §5). */
@Composable
fun SettingsRow(
    title: String,
    modifier: Modifier = Modifier,
    value: String? = null,
    valueColor: Color = LocalNeutralColors.current.secondaryText,
    titleColor: Color = LocalNeutralColors.current.ink,
    onClick: (() -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    val neutrals = LocalNeutralColors.current
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier =
            modifier
                .fillMaxWidth()
                .heightIn(min = Metrics.Row.setting)
                .then(if (onClick != null) Modifier.clickable(role = Role.Button, onClick = onClick) else Modifier)
                .padding(horizontal = Space.m),
    ) {
        Text(title, style = GeunalType.body, color = titleColor, modifier = Modifier.weight(1f))
        if (value != null) {
            Text(value, style = GeunalType.body, color = valueColor)
        }
        if (trailing != null) {
            Spacer(Modifier.width(Space.xs))
            trailing()
        } else if (onClick != null) {
            Spacer(Modifier.width(Space.xxs))
            Icon(
                painterResource(R.drawable.ds_ic_chevron_right),
                contentDescription = null,
                tint = neutrals.tertiaryText,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

/** How a notice card reads: calm for reassurance, problem in red, offline in gray (m1.md screen 21's states). */
enum class NoticeKind { Calm, Problem, Offline }

/**
 * An information card: a small icon and a short text. Problem cards use the red of the error states; offline cards
 * stay gray because nothing is wrong with what the person did.
 */
@Composable
fun NoticeCard(
    text: String,
    modifier: Modifier = Modifier,
    kind: NoticeKind = NoticeKind.Calm,
    icon: Painter? = null,
) {
    val neutrals = LocalNeutralColors.current
    val dark = LocalDarkTheme.current
    val (background, foreground) =
        when (kind) {
            NoticeKind.Calm -> Color.Transparent to neutrals.secondaryText
            NoticeKind.Problem -> problemBackground(dark) to neutrals.sunday
            NoticeKind.Offline -> neutrals.groupedBackground to neutrals.secondaryText
        }
    val shownIcon =
        icon ?: when (kind) {
            NoticeKind.Calm -> painterResource(R.drawable.ds_ic_lock)
            NoticeKind.Problem -> painterResource(R.drawable.ds_ic_warning)
            NoticeKind.Offline -> painterResource(R.drawable.ds_ic_wifi_off)
        }
    Row(
        verticalAlignment = Alignment.Top,
        modifier =
            modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(Radius.input))
                .background(background)
                .padding(horizontal = Space.m, vertical = Space.s),
    ) {
        Icon(shownIcon, contentDescription = null, tint = foreground, modifier = Modifier.padding(top = 2.dp).size(18.dp))
        Spacer(Modifier.width(Space.s))
        Text(text, style = GeunalType.secondary, color = if (kind == NoticeKind.Calm) neutrals.secondaryText else foreground)
    }
}
