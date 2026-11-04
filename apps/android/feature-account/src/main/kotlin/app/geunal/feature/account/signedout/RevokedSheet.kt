package app.geunal.feature.account.signedout

import androidx.annotation.ArrayRes
import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import app.geunal.core.Revocation
import app.geunal.core.RevokedVia
import app.geunal.designsystem.GeunalType
import app.geunal.designsystem.LocalNeutralColors
import app.geunal.designsystem.Metrics
import app.geunal.designsystem.Radius
import app.geunal.designsystem.Space
import app.geunal.designsystem.components.ButtonKind
import app.geunal.designsystem.components.GeunalButton
import app.geunal.designsystem.components.GeunalSheet
import app.geunal.feature.account.R
import app.geunal.feature.account.backup.BackupText
import java.time.Instant

/**
 * 기기 로그아웃됨 (m1.md screen 23, boards/Revoked): another device signed in to this account, so this one is signed out.
 * The core has already wiped the session, the device key, the code's entropy and the group state, and kept the calendar.
 * The note differs by how the other device came in (m1.md decision 15). It closes only with one of its two answers.
 */
@Composable
fun RevokedSheet(
    revocation: Revocation,
    onSignIn: () -> Unit,
    onCalendarOnly: () -> Unit,
) {
    val neutrals = LocalNeutralColors.current
    val resources = LocalResources.current
    val time = BackupText.time(revocation.at, Instant.now(), resources)
    val platform =
        stringResource(if (revocation.byPlatform == "android") R.string.revoked_platform_android else R.string.revoked_platform_ios)
    val provider = revocation.via == RevokedVia.PROVIDER
    GeunalSheet(onDismiss = {}, dismissible = false) {
        Column(
            verticalArrangement = Arrangement.spacedBy(Space.m),
            modifier =
                Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = Metrics.Sheet.paddingSide)
                    .padding(top = Space.l, bottom = Metrics.Sheet.paddingBottom)
                    .testTag("revoked.sheet"),
        ) {
            Text(
                stringResource(R.string.revoked_title),
                style = GeunalType.title2,
                color = neutrals.ink,
                modifier = Modifier.semantics { heading() },
            )
            Text(
                stringResource(if (provider) R.string.revoked_body_provider else R.string.revoked_body_recovery, time, platform),
                style = GeunalType.body,
                color = neutrals.ink,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(Space.s), modifier = Modifier.height(IntrinsicSize.Min)) {
                ListCard(R.string.revoked_kept, R.array.revoked_kept_items, Modifier.weight(1f))
                ListCard(R.string.revoked_wiped, R.array.revoked_wiped_items, Modifier.weight(1f))
            }
            Text(
                stringResource(if (provider) R.string.revoked_note_provider else R.string.revoked_note_recovery),
                style = GeunalType.caption,
                color = neutrals.secondaryText,
            )
            Spacer(Modifier.height(Space.xs))
            GeunalButton(
                text = stringResource(R.string.revoked_sign_in),
                onClick = onSignIn,
                modifier = Modifier.testTag("revoked.signIn"),
            )
            GeunalButton(
                text = stringResource(R.string.revoked_calendar_only),
                onClick = onCalendarOnly,
                kind = ButtonKind.Text,
                modifier = Modifier.fillMaxWidth().testTag("revoked.calendarOnly"),
            )
        }
    }
}

@Composable
private fun ListCard(
    @StringRes title: Int,
    @ArrayRes items: Int,
    modifier: Modifier,
) {
    val neutrals = LocalNeutralColors.current
    Column(
        verticalArrangement = Arrangement.spacedBy(Space.xs),
        modifier =
            modifier
                .fillMaxHeight()
                .background(neutrals.groupedBackground, RoundedCornerShape(Radius.card))
                .padding(Space.m),
    ) {
        Text(
            stringResource(title),
            style = GeunalType.caption.copy(fontWeight = FontWeight.SemiBold),
            color = neutrals.secondaryText,
        )
        stringArrayResource(items).forEach { Text(it, style = GeunalType.body, color = neutrals.ink) }
    }
}
