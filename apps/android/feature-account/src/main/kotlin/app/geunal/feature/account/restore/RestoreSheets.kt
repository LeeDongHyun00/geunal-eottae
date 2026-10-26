package app.geunal.feature.account.restore

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import app.geunal.core.LocalData
import app.geunal.core.LocalDataChoice
import app.geunal.designsystem.GeunalType
import app.geunal.designsystem.LocalNeutralColors
import app.geunal.designsystem.Metrics
import app.geunal.designsystem.Space
import app.geunal.designsystem.components.ButtonKind
import app.geunal.designsystem.components.GeunalButton
import app.geunal.designsystem.components.GeunalSheet
import app.geunal.designsystem.components.NoticeCard
import app.geunal.designsystem.components.NoticeKind
import app.geunal.feature.account.R
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

/**
 * "복구 코드가 없어요" (m1.md screen 21). The code is the only key, so the sheet says what starting over loses; for an
 * account with backups the red button first turns the sheet into the wipe confirmation instead of deleting anything.
 */
@Composable
internal fun NoCodeSheet(
    model: RestoreModel,
    noCode: RestoreModel.NoCode,
    isWorking: Boolean,
) {
    GeunalSheet(onDismiss = model::closeNoCode, dismissible = !isWorking) {
        SheetColumn {
            when (noCode) {
                RestoreModel.NoCode.Explaining -> Explaining(model, isWorking)
                is RestoreModel.NoCode.ConfirmingWipe -> ConfirmingWipe(model, noCode, isWorking)
            }
        }
    }
}

@Composable
private fun Explaining(
    model: RestoreModel,
    isWorking: Boolean,
) {
    val (title, paragraphs, startOver) =
        when (model.mode) {
            RestoreMode.DEVICE_CHANGE ->
                Triple(
                    R.string.no_code_title_device_change,
                    listOf(R.string.no_code_body_backup, R.string.no_code_body_device_change),
                    R.string.no_code_start_fresh,
                )
            RestoreMode.OFFER ->
                Triple(
                    R.string.no_code_title_offer,
                    listOf(R.string.no_code_body_offer, R.string.no_code_body_device_change),
                    R.string.no_code_start_fresh,
                )
            RestoreMode.GUEST_RETURN ->
                Triple(
                    R.string.no_code_title_guest,
                    listOf(R.string.no_code_body_guest, R.string.no_code_body_guest_new),
                    R.string.no_code_new_guest,
                )
        }
    SheetTitle(stringResource(title))
    paragraphs.forEach { SheetParagraph(stringResource(it)) }
    Spacer(Modifier.height(Space.s))
    if (model.mode == RestoreMode.OFFER) {
        GeunalButton(
            text = stringResource(R.string.no_code_later),
            onClick = model::later,
            kind = ButtonKind.Secondary,
            enabled = !isWorking,
            modifier = Modifier.testTag("restore.later"),
        )
    }
    GeunalButton(
        text = stringResource(if (isWorking) R.string.busy else startOver),
        onClick = model::startOver,
        kind = ButtonKind.Danger,
        enabled = !isWorking,
        modifier = Modifier.testTag("restore.startOver"),
    )
    GeunalButton(
        text = stringResource(R.string.no_code_close),
        onClick = model::closeNoCode,
        kind = ButtonKind.Text,
        enabled = !isWorking,
        modifier = Modifier.fillMaxWidth(),
    )
}

/** The second step: how many backups go and when the last was made, then the red filled button (decision 24). */
@Composable
private fun ConfirmingWipe(
    model: RestoreModel,
    confirming: RestoreModel.NoCode.ConfirmingWipe,
    isWorking: Boolean,
) {
    val neutrals = LocalNeutralColors.current
    val count = confirming.backups
    val lastAt = confirming.lastAt
    SheetTitle(stringResource(R.string.wipe_title))
    SheetParagraph(
        if (count != null && count > 0 && lastAt != null) {
            stringResource(R.string.wipe_body_counted, count, lastAtText(lastAt))
        } else {
            stringResource(R.string.wipe_body)
        },
    )
    NoticeCard(text = stringResource(R.string.wipe_warning), kind = NoticeKind.Problem)
    if (confirming.screenLockMissing) {
        Text(
            stringResource(R.string.device_auth_no_lock_wipe),
            style = GeunalType.secondary,
            color = neutrals.secondaryText,
            modifier = Modifier.testTag("restore.noScreenLock"),
        )
    }
    Spacer(Modifier.height(Space.s))
    GeunalButton(
        text =
            when {
                isWorking -> stringResource(R.string.busy)
                count != null && count > 0 -> stringResource(R.string.wipe_confirm_counted, count)
                else -> stringResource(R.string.wipe_confirm)
            },
        onClick = model::confirmWipe,
        kind = ButtonKind.DangerFilled,
        enabled = !isWorking,
        modifier = Modifier.testTag("restore.confirmWipe"),
    )
    GeunalButton(
        text = stringResource(R.string.wipe_keep),
        onClick = model::keepBackups,
        kind = ButtonKind.Text,
        enabled = !isWorking,
        modifier = Modifier.fillMaxWidth().testTag("restore.keepBackups"),
    )
}

/**
 * "이 기기에 다른 계정의 일정이 남아 있어요" (m1.md screen 24). The two accounts' events are never merged, and the sheet
 * closes only with an answer.
 */
@Composable
internal fun KeepLocalSheet(
    local: LocalData,
    isWorking: Boolean,
    onChoose: (LocalDataChoice) -> Unit,
) {
    val neutrals = LocalNeutralColors.current
    GeunalSheet(onDismiss = {}, dismissible = false) {
        SheetColumn {
            SheetTitle(stringResource(R.string.keep_local_title))
            SheetParagraph(stringResource(R.string.keep_local_body, local.events.toInt(), local.people.toInt()))
            Text(
                stringResource(R.string.keep_local_note),
                style = GeunalType.secondary,
                color = neutrals.ink,
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(vertical = Space.xxs),
            )
            Spacer(Modifier.height(Space.s))
            GeunalButton(
                text = stringResource(if (isWorking) R.string.busy else R.string.keep_local_keep),
                onClick = { onChoose(LocalDataChoice.KEEP) },
                enabled = !isWorking,
                modifier = Modifier.testTag("keepLocal.keep"),
            )
            GeunalButton(
                text = stringResource(R.string.keep_local_discard),
                onClick = { onChoose(LocalDataChoice.DISCARD) },
                kind = ButtonKind.Danger,
                enabled = !isWorking,
                modifier = Modifier.testTag("keepLocal.discard"),
            )
        }
    }
}

@Composable
private fun SheetColumn(content: @Composable () -> Unit) {
    Column(
        verticalArrangement = Arrangement.spacedBy(Space.m),
        modifier =
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Metrics.Sheet.paddingSide)
                .padding(top = Space.l, bottom = Metrics.Sheet.paddingBottom),
    ) { content() }
}

@Composable
private fun SheetTitle(text: String) {
    Text(
        text,
        style = GeunalType.title2,
        color = LocalNeutralColors.current.ink,
        modifier = Modifier.semantics { heading() },
    )
}

@Composable
private fun SheetParagraph(text: String) {
    Text(text, style = GeunalType.secondary, color = LocalNeutralColors.current.ink)
}

/** "10월 20일 18:20", with the year when it is not this one (tone.md "날짜와 시간"), in Korean time like every date. */
@Composable
private fun lastAtText(at: Instant): String {
    val zone = ZoneId.of("Asia/Seoul")
    val time = ZonedDateTime.ofInstant(at, zone)
    val clock = time.format(DateTimeFormatter.ofPattern("HH:mm"))
    return if (time.year == ZonedDateTime.now(zone).year) {
        stringResource(R.string.wipe_last_at, time.monthValue, time.dayOfMonth, clock)
    } else {
        stringResource(R.string.wipe_last_at_other_year, time.year, time.monthValue, time.dayOfMonth, clock)
    }
}
