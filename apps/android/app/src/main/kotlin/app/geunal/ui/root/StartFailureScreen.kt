package app.geunal.ui.root

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import app.geunal.R
import app.geunal.StartFailure
import app.geunal.designsystem.GeunalType
import app.geunal.designsystem.LocalNeutralColors
import app.geunal.designsystem.Space
import app.geunal.designsystem.components.ButtonKind
import app.geunal.designsystem.components.GeunalButton

/**
 * The core could not open its store. Nothing on the device works without it, so the screen says so plainly, names the
 * part by kind (never the core's message) and lets the person try again.
 */
@Composable
fun StartFailureScreen(
    reason: StartFailure,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val neutrals = LocalNeutralColors.current
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
        modifier =
            modifier
                .fillMaxSize()
                .background(neutrals.background)
                .padding(Space.xl),
    ) {
        Text(stringResource(R.string.start_failure_title), style = GeunalType.title2, color = neutrals.ink, textAlign = TextAlign.Center)
        Spacer(Modifier.height(Space.s))
        Text(
            stringResource(R.string.start_failure_body),
            style = GeunalType.secondary,
            color = neutrals.secondaryText,
            textAlign = TextAlign.Center,
        )
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
        Spacer(Modifier.height(Space.xl))
        GeunalButton(text = stringResource(R.string.start_failure_retry), onClick = onRetry, kind = ButtonKind.Secondary)
    }
}
