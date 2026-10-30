package app.geunal.feature.calendar.nextup

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.geunal.core.Palette
import app.geunal.core.UpNext
import app.geunal.designsystem.GeunalType
import app.geunal.designsystem.LocalNeutralColors
import app.geunal.designsystem.Metrics
import app.geunal.designsystem.Motion
import app.geunal.designsystem.Space
import app.geunal.feature.calendar.R
import app.geunal.feature.calendar.support.CalendarColors
import app.geunal.feature.calendar.support.DayText
import app.geunal.feature.calendar.support.text
import app.geunal.designsystem.R as DesignR

/** ‹ thumbnail, title and when › (components.md §7), floating above the navigation bar. */
@Composable
fun NextUpBar(
    model: NextUpModel,
    onOpen: (UpNext) -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by model.state.collectAsStateWithLifecycle()
    val neutrals = LocalNeutralColors.current
    LaunchedEffect(model) { model.keepCurrent() }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier =
            modifier
                .fillMaxWidth()
                .height(Metrics.NextUpBar.height)
                .shadow(12.dp, RoundedCornerShape(Metrics.NextUpBar.radius), clip = false)
                .clip(RoundedCornerShape(Metrics.NextUpBar.radius))
                .background(neutrals.background)
                .border(1.dp, Color.Black.copy(alpha = 0.07f), RoundedCornerShape(Metrics.NextUpBar.radius))
                .padding(horizontal = 4.dp)
                .testTag("nextUp"),
    ) {
        Stepper(
            icon = DesignR.drawable.ds_ic_chevron_left,
            label = stringResource(R.string.next_up_previous),
            enabled = state.canGoBack,
            onClick = model::back,
        )
        AnimatedContent(
            targetState = state.current,
            transitionSpec = {
                (slideInHorizontally(tween(Motion.NEXT_UP_MS)) { it / 3 } + fadeIn(tween(Motion.NEXT_UP_MS))) togetherWith
                    (slideOutHorizontally(tween(Motion.NEXT_UP_MS)) { -it / 3 } + fadeOut(tween(Motion.NEXT_UP_MS)))
            },
            contentKey = { it?.source },
            modifier = Modifier.weight(1f),
            label = "nextUp",
        ) { item ->
            if (item == null) {
                Text(
                    stringResource(R.string.next_up_empty),
                    style = GeunalType.secondary,
                    color = neutrals.secondaryText,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = Space.xs),
                )
            } else {
                Content(item, onClick = { onOpen(item.source) })
            }
        }
        Stepper(
            icon = DesignR.drawable.ds_ic_chevron_right,
            label = stringResource(R.string.next_up_next),
            enabled = state.canGoForward,
            onClick = model::forward,
        )
    }
}

@Composable
private fun Stepper(
    icon: Int,
    label: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val neutrals = LocalNeutralColors.current
    // Off is drawn in tertiary text, never faded (components.md §1).
    IconButton(onClick = onClick, enabled = enabled, modifier = Modifier.size(Metrics.minHitTarget)) {
        Icon(painterResource(icon), contentDescription = label, tint = if (enabled) neutrals.ink else neutrals.tertiaryText)
    }
}

@Composable
private fun Content(
    item: NextUpModel.Item,
    onClick: () -> Unit,
) {
    val neutrals = LocalNeutralColors.current
    val title = item.title.text()
    val detail = item.detail.text()
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier =
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(Metrics.NextUpBar.thumbRadius))
                .clickable(role = Role.Button, onClick = onClick)
                .semantics(mergeDescendants = true) { contentDescription = "$title, $detail" }
                .padding(vertical = 4.dp),
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier =
                Modifier
                    .size(Metrics.NextUpBar.thumb)
                    .clip(RoundedCornerShape(Metrics.NextUpBar.thumbRadius))
                    .background(CalendarColors.marker(if (item.isBirthday) Palette.BLOSSOM else item.color)),
        ) {
            Icon(
                painterResource(DesignR.drawable.ds_ic_calendar),
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(18.dp),
            )
        }
        Spacer(Modifier.width(Space.s))
        Column(Modifier.weight(1f)) {
            Text(title, style = GeunalType.buttonSmall, color = neutrals.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(detail, style = GeunalType.caption, color = neutrals.secondaryText, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun NextUpModel.Title.text(): String =
    when (this) {
        is NextUpModel.Title.Event -> text
        is NextUpModel.Title.Birthday -> stringResource(R.string.next_up_birthday, name)
    }

@Composable
private fun NextUpModel.Detail.text(): String =
    when (this) {
        is NextUpModel.Detail.AllDay -> stringResource(R.string.next_up_all_day, day.text())
        is NextUpModel.Detail.Timed -> {
            val range = DayText.range(start, end)
            val until = until
            if (until == null) {
                stringResource(R.string.next_up_now, day.text(), range)
            } else {
                stringResource(R.string.next_up_timed, day.text(), range, until.text())
            }
        }
        is NextUpModel.Detail.Birthday -> day.text()
    }
