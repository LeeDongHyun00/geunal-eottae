package app.geunal.feature.account.delete

import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.geunal.core.Provider
import app.geunal.designsystem.GeunalType
import app.geunal.designsystem.LocalNeutralColors
import app.geunal.designsystem.Metrics
import app.geunal.designsystem.Radius
import app.geunal.designsystem.Space
import app.geunal.designsystem.components.ButtonKind
import app.geunal.designsystem.components.GeunalButton
import app.geunal.designsystem.components.GeunalSheet
import app.geunal.designsystem.components.NoticeCard
import app.geunal.designsystem.components.NoticeKind
import app.geunal.feature.account.R
import app.geunal.feature.account.delete.DeleteAccountModel.Problem
import app.geunal.designsystem.R as DesignR

/**
 * 계정 삭제 (m1.md screen 22, boards/DeleteAccount), over the tabs: what the server deletes, what stays on this device,
 * the notes, the red "계정 삭제" and "그냥 둘게요". The sheet asks for "삭제" typed out, then the provider's sign-in for
 * a Kakao or Apple account. Offline the button says it waits for a connection.
 */
@Composable
fun DeleteAccountScreen(
    model: DeleteAccountModel,
    online: Boolean,
    onDeleted: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by model.state.collectAsStateWithLifecycle()
    val neutrals = LocalNeutralColors.current
    // Deleted by the press or found deleted by the check: the same end as a 204.
    LaunchedEffect(state.deleted) { if (state.deleted) onDeleted() }
    Column(
        modifier
            .fillMaxSize()
            .background(neutrals.groupedBackground)
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
                        .testTag("delete.back"),
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
                    .padding(horizontal = Space.m)
                    .padding(bottom = Space.xxl),
        ) {
            Text(
                stringResource(R.string.delete_title),
                style = GeunalType.largeTitle,
                color = neutrals.ink,
                modifier = Modifier.semantics { heading() },
            )
            Text(stringResource(R.string.delete_note), style = GeunalType.secondary, color = neutrals.secondaryText)
            Card(
                R.string.delete_server_title,
                listOf(
                    R.string.delete_server_account to R.string.delete_server_account_detail,
                    R.string.delete_server_backups to R.string.delete_server_backups_detail,
                    R.string.delete_server_groups to R.string.delete_server_groups_detail,
                    R.string.delete_server_media to R.string.delete_server_media_detail,
                ),
            )
            Card(R.string.delete_device_title, listOf(null to R.string.delete_device_body))
            Text(stringResource(R.string.delete_legal), style = GeunalType.caption, color = neutrals.secondaryText)
            Spacer(Modifier.height(Space.xs))
            GeunalButton(
                text = stringResource(if (online) R.string.delete_start else R.string.delete_start_offline),
                onClick = model::openSheet,
                kind = ButtonKind.Danger,
                enabled = online,
                modifier = Modifier.fillMaxWidth().testTag("delete.start"),
            )
            GeunalButton(
                text = stringResource(R.string.delete_keep),
                onClick = onClose,
                kind = ButtonKind.Text,
                modifier = Modifier.fillMaxWidth().testTag("delete.keep"),
            )
        }
    }
    if (state.sheetOpen) ConfirmSheet(model, state, online)
}

/** One card of the screen: a caption title, then items with a bold lead when there are several. */
@Composable
private fun Card(
    @StringRes title: Int,
    items: List<Pair<Int?, Int>>,
) {
    val neutrals = LocalNeutralColors.current
    Column(
        verticalArrangement = Arrangement.spacedBy(Space.s),
        modifier =
            Modifier
                .fillMaxWidth()
                .background(neutrals.background, RoundedCornerShape(Radius.card))
                .padding(Space.m),
    ) {
        Text(
            stringResource(title),
            style = GeunalType.caption.copy(fontWeight = FontWeight.SemiBold),
            color = neutrals.secondaryText,
        )
        items.forEach { (lead, body) ->
            val leadText = lead?.let { stringResource(it) }
            val bodyText = stringResource(body)
            Text(
                buildAnnotatedString {
                    if (items.size > 1) append("• ")
                    if (leadText != null) {
                        withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) { append(leadText) }
                        append(" ")
                    }
                    append(bodyText)
                },
                style = GeunalType.body,
                color = neutrals.ink,
            )
        }
    }
}

/**
 * "하린님의 계정을 삭제할까요?": the warning, for a Kakao or Apple account why it signs in once more, and the field that
 * wants "삭제". A lost answer is checked as soon as there is a connection, now or when it comes back (decision 36).
 */
@Composable
private fun ConfirmSheet(
    model: DeleteAccountModel,
    state: DeleteAccountModel.State,
    online: Boolean,
) {
    val neutrals = LocalNeutralColors.current
    val provider = model.provider
    val answerLost = state.problem == Problem.ANSWER_LOST
    LaunchedEffect(online && answerLost) {
        if (online && answerLost) model.checkLostAnswer()
    }
    GeunalSheet(onDismiss = model::closeSheet) {
        Column(
            verticalArrangement = Arrangement.spacedBy(Space.m),
            modifier =
                Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = Metrics.Sheet.paddingSide)
                    .padding(top = Space.l, bottom = Metrics.Sheet.paddingBottom),
        ) {
            Text(
                if (model.displayName.isEmpty()) {
                    stringResource(R.string.delete_sheet_title_unnamed)
                } else {
                    stringResource(R.string.delete_sheet_title, model.displayName)
                },
                style = GeunalType.title2,
                color = neutrals.ink,
                modifier = Modifier.semantics { heading() },
            )
            NoticeCard(text = stringResource(R.string.delete_sheet_warning), kind = NoticeKind.Problem)
            if (!model.screenLocked) {
                Text(
                    stringResource(R.string.device_auth_no_lock_wipe),
                    style = GeunalType.caption,
                    color = neutrals.secondaryText,
                    modifier = Modifier.testTag("delete.noScreenLock"),
                )
            }
            val reauth = if (state.problem == Problem.REAUTH_REQUIRED) reauthText(provider) else null
            val reason = reasonText(provider)
            if (reauth != null) {
                NoticeCard(text = reauth, kind = NoticeKind.Problem, modifier = Modifier.testTag("delete.reauth"))
            } else if (reason != null) {
                NoticeCard(text = reason, kind = NoticeKind.Offline)
            }
            WordField(state.typed, model::type)
            val problem = state.problem
            if (problem != null && problem != Problem.REAUTH_REQUIRED) {
                NoticeCard(
                    text = stringResource(problemText(problem)),
                    kind = NoticeKind.Problem,
                    modifier = Modifier.testTag("delete.problem"),
                )
            }
            GeunalButton(
                text = stringResource(confirmLabel(state, provider)),
                onClick = { model.confirm(online) },
                kind = ButtonKind.Danger,
                enabled = state.canConfirm,
                modifier = Modifier.fillMaxWidth().testTag("delete.confirm"),
            )
        }
    }
}

@Composable
private fun WordField(
    typed: String,
    onType: (String) -> Unit,
) {
    val neutrals = LocalNeutralColors.current
    val label = stringResource(R.string.delete_word_label, DeleteAccountModel.WORD)
    Column(verticalArrangement = Arrangement.spacedBy(Space.xs)) {
        Text(label, style = GeunalType.caption.copy(fontWeight = FontWeight.SemiBold), color = neutrals.secondaryText)
        BasicTextField(
            value = typed,
            onValueChange = onType,
            singleLine = true,
            textStyle = GeunalType.body.copy(color = neutrals.ink),
            cursorBrush = SolidColor(neutrals.ink),
            keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, imeAction = ImeAction.Done),
            modifier =
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = 52.dp)
                    .background(neutrals.groupedBackground, RoundedCornerShape(Radius.input))
                    .padding(horizontal = Space.m)
                    .semantics { contentDescription = label }
                    .testTag("delete.word"),
            decorationBox = { field ->
                Box(contentAlignment = Alignment.CenterStart, modifier = Modifier.heightIn(min = 52.dp)) {
                    if (typed.isEmpty()) Text(DeleteAccountModel.WORD, style = GeunalType.body, color = neutrals.tertiaryText)
                    field()
                }
            },
        )
    }
}

@Composable
private fun reasonText(provider: Provider?): String? =
    when (provider) {
        Provider.KAKAO -> stringResource(R.string.delete_reason_kakao)
        Provider.APPLE -> stringResource(R.string.delete_reason_apple)
        null -> null
    }

/** m1.md screen 22 "오류": the linked account, not the one the provider happened to sign in. */
@Composable
private fun reauthText(provider: Provider?): String? =
    when (provider) {
        Provider.KAKAO -> stringResource(R.string.delete_reauth_kakao)
        Provider.APPLE -> stringResource(R.string.delete_reauth_apple)
        null -> null
    }

private fun problemText(problem: Problem): Int =
    when (problem) {
        Problem.OFFLINE -> R.string.delete_problem_offline
        Problem.REAUTH_REQUIRED, Problem.FAILED -> R.string.delete_problem_failed
        Problem.RECOVERY_MISSING -> R.string.delete_problem_recovery_missing
        Problem.ANSWER_LOST -> R.string.delete_problem_answer_lost
        Problem.NOT_DELETED -> R.string.delete_problem_not_deleted
    }

internal fun confirmLabel(
    state: DeleteAccountModel.State,
    provider: Provider?,
): Int =
    when {
        state.problem == Problem.ANSWER_LOST ->
            if (state.checking) R.string.delete_checking else R.string.delete_wait_connection
        state.isWorking -> R.string.delete_working
        state.problem == Problem.REAUTH_REQUIRED && state.canConfirm && provider != null ->
            if (provider == Provider.KAKAO) R.string.delete_again_kakao else R.string.delete_again_apple
        !state.canConfirm ->
            when (provider) {
                Provider.KAKAO -> R.string.delete_type_word_kakao
                Provider.APPLE -> R.string.delete_type_word_apple
                null -> R.string.delete_type_word
            }
        else ->
            when (provider) {
                Provider.KAKAO -> R.string.delete_confirm_kakao
                Provider.APPLE -> R.string.delete_confirm_apple
                null -> R.string.delete_confirm
            }
    }
