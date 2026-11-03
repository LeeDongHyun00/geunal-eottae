package app.geunal.ui.root

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CollectionInfo
import androidx.compose.ui.semantics.CollectionItemInfo
import androidx.compose.ui.semantics.collectionInfo
import androidx.compose.ui.semantics.collectionItemInfo
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import app.geunal.R
import app.geunal.StartFailure
import app.geunal.designsystem.GeunalType
import app.geunal.designsystem.LocalNeutralColors
import app.geunal.designsystem.Radius
import app.geunal.designsystem.Space
import app.geunal.designsystem.components.GeunalButton

/**
 * The core could not open its store (m1.md §4 "시작 오류", decision 37). Nothing on the device works without it, so the
 * screen first says nothing was erased, then what to do in order: try again, restart the phone, look for an update. It
 * names the part by kind only, never the core's message, and never says to remove the app: that would erase the
 * calendar a restart or an update might have opened, and a recovery code brings back only the last backup.
 */
@Composable
fun StartFailureScreen(
    reason: StartFailure,
    /** "다시 시도" is opening the core again: the button says "여는 중…" and takes no second tap. */
    retrying: Boolean,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val neutrals = LocalNeutralColors.current
    val steps = stringArrayResource(R.array.start_failure_steps)
    Column(
        modifier
            .fillMaxSize()
            .background(neutrals.background)
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(horizontal = Space.l)
            .padding(bottom = Space.m),
    ) {
        // Centred while it fits; with a large font it scrolls above the button rather than pushing it off screen.
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
            Column(
                verticalArrangement = Arrangement.Center,
                modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).heightIn(min = maxHeight),
            ) {
                Text(
                    stringResource(R.string.start_failure_title),
                    style = GeunalType.title2,
                    color = neutrals.ink,
                    modifier = Modifier.semantics { heading() },
                )
                Spacer(Modifier.height(Space.xs))
                Text(stringResource(R.string.start_failure_body), style = GeunalType.secondary, color = neutrals.secondaryText)
                Spacer(Modifier.height(Space.l))
                Column(
                    verticalArrangement = Arrangement.spacedBy(Space.s),
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(Radius.card))
                            .background(neutrals.groupedBackground)
                            .padding(Space.m)
                            // TalkBack reads the steps as a list of three ("목록, 3개 항목").
                            .semantics { collectionInfo = CollectionInfo(rowCount = steps.size, columnCount = 1) }
                            .testTag("start.steps"),
                ) {
                    steps.forEachIndexed { index, step ->
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(Space.xs),
                            modifier =
                                Modifier.semantics(mergeDescendants = true) {
                                    collectionItemInfo = CollectionItemInfo(rowIndex = index, rowSpan = 1, columnIndex = 0, columnSpan = 1)
                                },
                        ) {
                            Text(
                                "${index + 1}.",
                                style = GeunalType.body.copy(fontFeatureSettings = "tnum"),
                                color = neutrals.secondaryText,
                            )
                            Text(step, style = GeunalType.body, color = neutrals.ink, modifier = Modifier.weight(1f))
                        }
                    }
                }
                Spacer(Modifier.height(Space.s))
                Text(
                    stringResource(
                        when (reason) {
                            StartFailure.STORE -> R.string.start_failure_store
                            StartFailure.SECRETS -> R.string.start_failure_secrets
                            StartFailure.OTHER -> R.string.start_failure_other
                        },
                    ),
                    style = GeunalType.caption,
                    color = neutrals.tertiaryText,
                    modifier = Modifier.testTag("start.failure"),
                )
            }
        }
        GeunalButton(
            text = stringResource(if (retrying) R.string.start_failure_retrying else R.string.start_failure_retry),
            onClick = onRetry,
            enabled = !retrying,
            modifier = Modifier.testTag("start.retry"),
        )
    }
}
