package app.geunal.feature.account.security

import android.os.SystemClock
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.geunal.designsystem.GeunalType
import app.geunal.designsystem.LocalNeutralColors
import app.geunal.designsystem.Metrics
import app.geunal.designsystem.Radius
import app.geunal.designsystem.Space
import app.geunal.designsystem.components.ButtonKind
import app.geunal.designsystem.components.GeunalButton
import app.geunal.designsystem.components.NoticeCard
import app.geunal.designsystem.components.NoticeKind
import app.geunal.designsystem.components.ToastCenter
import app.geunal.designsystem.components.ToastHost
import app.geunal.feature.account.R
import app.geunal.feature.account.backup.BackupText
import app.geunal.feature.account.restore.SecureWindow
import kotlinx.coroutines.delay
import java.text.Normalizer
import java.time.Instant
import app.geunal.designsystem.R as DesignR

/**
 * 복구 코드 (m1.md screen 13, boards/Recovery), opened over the tabs once the device owner check passed: what the code
 * is for, the twelve words in three columns, the warning, the backup line, "복사" and "종이에 적었어요". The window is
 * kept out of screenshots and the app switcher (FLAG_SECURE), and a copied code leaves the clipboard after a minute
 * or when the screen goes away. [deviceSecure] false adds the line suggesting a screen lock.
 */
@Composable
fun RecoveryCodeScreen(
    model: RecoveryCodeModel,
    deviceSecure: Boolean,
    toasts: ToastCenter,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by model.state.collectAsStateWithLifecycle()
    val neutrals = LocalNeutralColors.current
    val resources = LocalResources.current
    val context = LocalContext.current
    val clipboard = remember(context) { SensitiveClipboard(context) }
    var copiedAt by remember { mutableStateOf<Long?>(null) }
    SecureWindow()
    LaunchedEffect(model) { model.start() }
    LaunchedEffect(copiedAt) {
        if (copiedAt == null) return@LaunchedEffect
        delay(SensitiveClipboard.CLEAR_AFTER_MS)
        clipboard.clear()
    }
    // Leaving the screen takes the code off at once, rather than waiting out the minute.
    DisposableEffect(clipboard) { onDispose { clipboard.clear() } }
    // Gone to another app (to paste the code there, say), the timer clears the code from the background; should it
    // not have run, the code comes off as soon as the app is back in front.
    LifecycleResumeEffect(clipboard, copiedAt) {
        val at = copiedAt
        if (at != null && SystemClock.elapsedRealtime() - at >= SensitiveClipboard.CLEAR_AFTER_MS) {
            clipboard.clear()
            copiedAt = null
        }
        onPauseOrDispose { }
    }
    Box(modifier.fillMaxSize().background(neutrals.background)) {
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
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
                            .testTag("recovery.back"),
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
                    stringResource(R.string.recovery_title),
                    style = GeunalType.largeTitle,
                    color = neutrals.ink,
                    modifier = Modifier.semantics { heading() },
                )
                Text(stringResource(R.string.recovery_note), style = GeunalType.secondary, color = neutrals.secondaryText)
                if (state.hasLoaded && state.failed) {
                    NoticeCard(stringResource(R.string.recovery_failed), kind = NoticeKind.Problem)
                }
                if (state.words.isNotEmpty()) WordGrid(state.words)
                Column(
                    verticalArrangement = Arrangement.spacedBy(Space.xxs),
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(Radius.card))
                            .background(neutrals.groupedBackground)
                            .padding(Space.m),
                ) {
                    Row(horizontalArrangement = Arrangement.spacedBy(Space.xs), verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            painterResource(DesignR.drawable.ds_ic_warning),
                            contentDescription = null,
                            tint = neutrals.sunday,
                        )
                        Text(
                            stringResource(R.string.recovery_warning_title),
                            style = GeunalType.bodyStrong,
                            color = neutrals.ink,
                        )
                    }
                    Text(stringResource(R.string.recovery_warning_body), style = GeunalType.caption, color = neutrals.secondaryText)
                    if (!deviceSecure) {
                        Text(
                            stringResource(R.string.recovery_no_lock),
                            style = GeunalType.caption,
                            color = neutrals.secondaryText,
                            modifier = Modifier.testTag("recovery.noLock"),
                        )
                    }
                }
                Column(verticalArrangement = Arrangement.spacedBy(Space.xxs)) {
                    Text(
                        state.lastBackupAt?.let {
                            stringResource(R.string.recovery_backup_line, BackupText.time(it, Instant.now(), resources))
                        } ?: stringResource(R.string.recovery_backup_none),
                        style = GeunalType.caption,
                        color = neutrals.secondaryText,
                    )
                    Text(stringResource(R.string.recovery_backup_media), style = GeunalType.caption, color = neutrals.secondaryText)
                }
            }
            Row(
                horizontalArrangement = Arrangement.spacedBy(Space.xs),
                modifier = Modifier.fillMaxWidth().padding(horizontal = Space.l).padding(bottom = Space.m),
            ) {
                GeunalButton(
                    text = stringResource(R.string.recovery_copy),
                    onClick = {
                        clipboard.copy(state.words.joinToString(" ") { it.nfc() })
                        copiedAt = SystemClock.elapsedRealtime()
                        toasts.show(resources.getString(R.string.recovery_copied))
                    },
                    kind = ButtonKind.Secondary,
                    enabled = state.words.isNotEmpty(),
                    modifier = Modifier.weight(1f).testTag("recovery.copy"),
                )
                GeunalButton(
                    text = stringResource(R.string.recovery_written),
                    onClick = { model.launchMarkSaved(onClose) },
                    enabled = state.words.isNotEmpty() && !state.isSaving,
                    modifier = Modifier.weight(1f).testTag("recovery.written"),
                )
            }
        }
        ToastHost(
            center = toasts,
            modifier =
                Modifier
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding()
                    .padding(horizontal = Space.m)
                    .padding(bottom = 88.dp),
        )
    }
}

/** The twelve words, three to a row: the number, then the word (boards/Recovery, 16 · 600). */
@Composable
private fun WordGrid(words: List<String>) {
    val neutrals = LocalNeutralColors.current
    Column(
        verticalArrangement = Arrangement.spacedBy(Space.xs),
        modifier =
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(Radius.card))
                .background(neutrals.groupedBackground)
                .padding(Space.m)
                .testTag("recovery.words"),
    ) {
        words.chunked(COLUMNS).forEachIndexed { row, line ->
            Row(horizontalArrangement = Arrangement.spacedBy(Space.xs)) {
                line.forEachIndexed { column, word ->
                    val number = row * COLUMNS + column + 1
                    val spoken = stringResource(R.string.recovery_word, number, word.nfc())
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(Space.xxs),
                        verticalAlignment = Alignment.CenterVertically,
                        // One stop per word for TalkBack: "1번 가방".
                        modifier = Modifier.weight(1f).clearAndSetSemantics { contentDescription = spoken },
                    ) {
                        Text("$number", style = GeunalType.caption, color = neutrals.tertiaryText)
                        Text(
                            word.nfc(),
                            style = GeunalType.body.copy(fontSize = 16.sp, fontWeight = FontWeight.SemiBold),
                            color = neutrals.ink,
                        )
                    }
                }
            }
        }
    }
}

/** The BIP-39 Korean list is decomposed; it shows as composed syllables (m1.md screen 13 "NFC로 표시"). */
private fun String.nfc(): String = Normalizer.normalize(this, Normalizer.Form.NFC)

private const val COLUMNS = 3
