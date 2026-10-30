package app.geunal.feature.account.onboarding

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.geunal.designsystem.GeunalType
import app.geunal.designsystem.GroupColor
import app.geunal.designsystem.GroupColors
import app.geunal.designsystem.LocalNeutralColors
import app.geunal.designsystem.Metrics
import app.geunal.designsystem.Radius
import app.geunal.designsystem.Space
import app.geunal.designsystem.components.ButtonKind
import app.geunal.designsystem.components.GeunalButton
import app.geunal.feature.account.R
import app.geunal.designsystem.R as DesignR

/**
 * Screen 1: what the app is, in one strip. The preview card is static data: three groups' free people across the day,
 * each hour in one of the group's five tints.
 */
@Composable
fun WelcomeScreen(
    onStart: () -> Unit,
    onInviteCode: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val neutrals = LocalNeutralColors.current
    Column(
        modifier =
            modifier
                .fillMaxSize()
                .background(neutrals.background)
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(horizontal = Metrics.Button.sideInset),
    ) {
        Column(
            Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState()),
        ) {
            Spacer(Modifier.height(Space.xxl))
            Text(stringResource(R.string.welcome_app_name), style = GeunalType.bodyStrong, color = neutrals.ink)
            Spacer(Modifier.height(Space.xxl))
            PreviewCard()
            Spacer(Modifier.height(Space.xxxl))
            Text(stringResource(R.string.welcome_title), style = GeunalType.largeTitle, color = neutrals.ink)
            Spacer(Modifier.height(Space.m))
            Text(stringResource(R.string.welcome_body), style = GeunalType.secondary, color = neutrals.secondaryText)
            Spacer(Modifier.height(Space.l))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    painterResource(DesignR.drawable.ds_ic_lock),
                    contentDescription = null,
                    tint = neutrals.secondaryText,
                    modifier = Modifier.size(16.dp),
                )
                Spacer(Modifier.width(Space.xs))
                Text(stringResource(R.string.welcome_reassurance), style = GeunalType.caption, color = neutrals.secondaryText)
            }
            Spacer(Modifier.height(Space.xl))
        }
        GeunalButton(
            text = stringResource(R.string.welcome_start),
            onClick = onStart,
            modifier = Modifier.testTag("welcome.start"),
        )
        GeunalButton(
            text = stringResource(R.string.welcome_invite_code),
            onClick = onInviteCode,
            kind = ButtonKind.Text,
            modifier = Modifier.fillMaxWidth().padding(vertical = Space.xs),
        )
    }
}

/** The grape, blossom and mint groups of the design board, hour by hour (tint 0 is "nobody free"). */
private class PreviewRow(
    val name: Int,
    val color: GroupColor,
    val hours: List<Int>,
)

private val previewRows: List<PreviewRow> =
    listOf(
        PreviewRow(
            R.string.welcome_preview_group_weekend,
            GroupColors.first { it.name == "grape" },
            listOf(5, 5, 5, 4, 3, 2, 2, 2, 3, 2, 3, 4, 3, 5, 5, 5, 5, 5, 5, 4, 4, 4, 4, 4),
        ),
        PreviewRow(
            R.string.welcome_preview_group_family,
            GroupColors.first { it.name == "blossom" },
            listOf(5, 5, 5, 5, 5, 5, 5, 5, 4, 4, 3, 3, 2, 2, 1, 1, 2, 2, 3, 3, 3, 5, 5, 5),
        ),
        PreviewRow(
            R.string.welcome_preview_group_cafe,
            GroupColors.first { it.name == "mint" },
            listOf(5, 5, 5, 5, 5, 5, 4, 4, 3, 2, 1, 1, 2, 2, 3, 3, 1, 1, 2, 2, 3, 4, 5, 5),
        ),
    )

@Composable
private fun PreviewCard() {
    val neutrals = LocalNeutralColors.current
    val description = stringResource(R.string.welcome_preview_description)
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(Radius.card + 12.dp))
                .background(neutrals.groupedBackground)
                .padding(Space.l)
                .semantics(mergeDescendants = true) { contentDescription = description },
        verticalArrangement = Arrangement.spacedBy(Space.s),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                stringResource(R.string.welcome_preview_day),
                style = GeunalType.title2,
                color = neutrals.saturday,
                modifier = Modifier.weight(1f),
            )
            Text(stringResource(R.string.welcome_preview_hours), style = GeunalType.caption, color = neutrals.secondaryText)
        }
        previewRows.forEach { row ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Spacer(Modifier.size(8.dp).clip(CircleShape).background(row.color.light.last()))
                Spacer(Modifier.width(Space.xs))
                Text(stringResource(row.name), style = GeunalType.buttonSmall, color = neutrals.ink)
            }
            Strip(color = row.color, hours = row.hours, empty = neutrals.emptyCell)
        }
    }
}

/** A 0–24 h strip: one cell per hour in the group's tint, white ticks every six hours (components.md §8). */
@Composable
private fun Strip(
    color: GroupColor,
    hours: List<Int>,
    empty: Color,
) {
    Canvas(Modifier.fillMaxWidth().height(22.dp)) {
        val radius = CornerRadius(11.dp.toPx())
        val clip = Path().apply { addRoundRect(RoundRect(0f, 0f, size.width, size.height, radius)) }
        clipPath(clip) {
            val cell = size.width / hours.size
            hours.forEachIndexed { index, tint ->
                drawRect(
                    color = if (tint == 0) empty else color.light[tint - 1],
                    topLeft = Offset(index * cell, 0f),
                    size = Size(cell + 0.5f, size.height),
                )
            }
            for (hour in listOf(6, 12, 18)) {
                drawRect(Color.White, topLeft = Offset(hour * cell - 0.5.dp.toPx(), 0f), size = Size(1.dp.toPx(), size.height))
            }
        }
    }
}
