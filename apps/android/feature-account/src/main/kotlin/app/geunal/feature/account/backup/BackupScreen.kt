package app.geunal.feature.account.backup

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.geunal.designsystem.GeunalType
import app.geunal.designsystem.LocalNeutralColors
import app.geunal.designsystem.Metrics
import app.geunal.designsystem.Radius
import app.geunal.designsystem.Space
import app.geunal.designsystem.components.GeunalButton
import app.geunal.designsystem.components.GeunalSwitch
import app.geunal.designsystem.components.NoticeCard
import app.geunal.designsystem.components.NoticeKind
import app.geunal.feature.account.R
import java.time.Instant
import app.geunal.designsystem.R as DesignR

/**
 * 암호화 백업 (m1.md screen 20, boards/Backup), over the tabs: what a backup is, the card with the last backup and
 * "지금 백업", the automatic backup switch, the backups the server keeps, and what goes in and what stays out. A code
 * never written down adds a line leading to 복구 코드 ([onRecoveryCode]); a code put off at screen 21 keeps backups off.
 */
@Composable
fun BackupScreen(
    model: BackupModel,
    onRecoveryCode: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by model.state.collectAsStateWithLifecycle()
    val neutrals = LocalNeutralColors.current
    LaunchedEffect(model) { model.start() }
    Column(
        modifier
            .fillMaxSize()
            .background(neutrals.background)
            .statusBarsPadding()
            .navigationBarsPadding(),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().height(56.dp).padding(horizontal = Space.xxs),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier =
                    Modifier
                        .heightIn(min = Metrics.minHitTarget)
                        .clip(RoundedCornerShape(Radius.cell))
                        .clickable(role = Role.Button, onClick = onClose)
                        .padding(end = Space.xs)
                        .testTag("backup.back"),
            ) {
                Icon(painterResource(DesignR.drawable.ds_ic_chevron_left), contentDescription = null, tint = neutrals.ink)
                Text(stringResource(R.string.recovery_back), style = GeunalType.body, color = neutrals.ink)
            }
        }
        Column(
            verticalArrangement = Arrangement.spacedBy(Space.m),
            modifier =
                Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = Space.l)
                    .padding(bottom = Space.l),
        ) {
            Text(
                stringResource(R.string.backup_title),
                style = GeunalType.largeTitle,
                color = neutrals.ink,
                modifier = Modifier.semantics { heading() },
            )
            Text(stringResource(R.string.backup_note), style = GeunalType.secondary, color = neutrals.secondaryText)
            if (state.codeNotSaved) {
                NoticeCard(
                    stringResource(R.string.backup_code_not_saved),
                    kind = NoticeKind.Offline,
                    modifier = Modifier.clickable(role = Role.Button, onClick = onRecoveryCode).testTag("backup.codeNotSaved"),
                )
            }
            if (state.waitsForCode) {
                NoticeCard(
                    stringResource(R.string.backup_waits_for_code),
                    kind = NoticeKind.Offline,
                    modifier = Modifier.testTag("backup.waitsForCode"),
                )
            }
            StatusCard(state, onBackUp = model::launchBackUpNow)
            if (state.failed) {
                Text(
                    stringResource(R.string.backup_offline),
                    style = GeunalType.caption,
                    color = neutrals.secondaryText,
                    modifier = Modifier.padding(horizontal = Space.xxs).testTag("backup.offline"),
                )
            }
            AutoRow(
                on = state.status?.auto == true && !state.waitsForCode,
                enabled = state.hasLoaded && !state.waitsForCode,
                onChange = model::setAuto,
            )
            if (state.backups.isNotEmpty()) Recent(state)
            Contents()
        }
    }
}

/** The last backup and "지금 백업" (boards/Backup's status card). */
@Composable
private fun StatusCard(
    state: BackupModel.State,
    onBackUp: () -> Unit,
) {
    val neutrals = LocalNeutralColors.current
    val resources = LocalResources.current
    val last = state.status?.last
    Column(
        verticalArrangement = Arrangement.spacedBy(Space.xs),
        modifier =
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(Radius.card))
                .background(neutrals.groupedBackground)
                .padding(Space.m)
                .testTag("backup.card"),
    ) {
        Text(
            stringResource(if (state.isBackingUp) R.string.backup_working_title else R.string.backup_last),
            style = GeunalType.caption,
            color = neutrals.secondaryText,
        )
        Text(
            when {
                state.isBackingUp -> stringResource(R.string.backup_working)
                state.justMade -> stringResource(R.string.backup_just_now)
                last != null -> BackupText.time(last.createdAt, Instant.now(), resources)
                else -> stringResource(R.string.backup_never)
            },
            style = GeunalType.body.copy(fontSize = 20.sp, fontWeight = FontWeight.SemiBold),
            color = neutrals.ink,
        )
        if (last != null && !state.isBackingUp) {
            Text(
                listOfNotNull(BackupText.counts(last, resources), BackupText.size(last.size, resources)).joinToString(" · "),
                style = GeunalType.caption,
                color = neutrals.secondaryText,
            )
        }
        state.progress?.let { progress ->
            LinearProgressIndicator(
                progress = { progress },
                color = neutrals.ink,
                trackColor = neutrals.fill,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        GeunalButton(
            text =
                stringResource(
                    when {
                        state.waitsForCode -> R.string.backup_needs_code
                        state.isBackingUp -> R.string.backup_sending
                        else -> R.string.backup_now
                    },
                ),
            onClick = onBackUp,
            enabled = state.canBackUp,
            modifier = Modifier.padding(top = Space.xxs).testTag("backup.now"),
        )
    }
}

@Composable
private fun AutoRow(
    on: Boolean,
    enabled: Boolean,
    onChange: (Boolean) -> Unit,
) {
    val neutrals = LocalNeutralColors.current
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier =
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(Radius.card))
                .background(neutrals.groupedBackground)
                .heightIn(min = Metrics.Row.twoLine)
                .toggleable(value = on, enabled = enabled, role = Role.Switch, onValueChange = onChange)
                .alpha(if (enabled) 1f else DIMMED)
                .padding(horizontal = Space.m, vertical = Space.xs)
                .testTag("backup.auto"),
    ) {
        Column(Modifier.weight(1f)) {
            Text(stringResource(R.string.backup_auto), style = GeunalType.body, color = neutrals.ink)
            Text(stringResource(R.string.backup_auto_note), style = GeunalType.caption, color = neutrals.secondaryText)
        }
        GeunalSwitch(checked = on, onCheckedChange = null, enabled = enabled)
    }
}

/** "최근 백업 · 3개까지 보관": the time, how it was made and what was in it, and its size. */
@Composable
private fun Recent(state: BackupModel.State) {
    val neutrals = LocalNeutralColors.current
    val resources = LocalResources.current
    val now = Instant.now()
    Text(
        stringResource(R.string.backup_recent),
        style = GeunalType.caption.copy(fontWeight = FontWeight.SemiBold),
        color = neutrals.secondaryText,
        modifier = Modifier.padding(start = Space.m, top = Space.xs).semantics { heading() },
    )
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Radius.card))
            .background(neutrals.groupedBackground),
    ) {
        state.backups.forEachIndexed { index, backup ->
            if (index > 0) HorizontalDivider(thickness = 1.dp, color = neutrals.separator, modifier = Modifier.padding(start = Space.m))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .heightIn(min = Metrics.Row.twoLine)
                        .padding(horizontal = Space.m, vertical = Space.xs)
                        .testTag("backup.item.${backup.id}"),
            ) {
                Column(Modifier.weight(1f)) {
                    Text(BackupText.time(backup.createdAt, now, resources), style = GeunalType.body, color = neutrals.ink)
                    val detail = BackupText.detail(backup, resources)
                    if (detail.isNotEmpty()) Text(detail, style = GeunalType.caption, color = neutrals.secondaryText)
                }
                Text(BackupText.size(backup.size, resources), style = GeunalType.caption, color = neutrals.secondaryText)
            }
        }
    }
}

/** The two cards: what a backup holds and what it leaves out. */
@Composable
private fun Contents() {
    val neutrals = LocalNeutralColors.current
    Row(horizontalArrangement = Arrangement.spacedBy(Space.xs)) {
        listOf(
            Triple(R.string.backup_in, R.array.backup_in_items, null),
            Triple(R.string.backup_out, R.array.backup_out_items, R.string.backup_out_note),
        ).forEach { (title, items, note) ->
            Column(
                verticalArrangement = Arrangement.spacedBy(Space.xxs),
                modifier =
                    Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(Radius.card))
                        .background(neutrals.groupedBackground)
                        .padding(Space.m),
            ) {
                Text(stringResource(title), style = GeunalType.bodyStrong, color = neutrals.ink)
                stringArrayResource(items).forEach { item ->
                    Text(item, style = GeunalType.caption, color = neutrals.secondaryText)
                }
                if (note != null) Text(stringResource(note), style = GeunalType.caption, color = neutrals.tertiaryText)
            }
        }
    }
}

/** How a switch that cannot be used now looks (m1.md "흐림 0.4"). */
private const val DIMMED = 0.4f
