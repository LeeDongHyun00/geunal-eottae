package app.geunal.feature.account.restore

import android.text.format.Formatter
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.geunal.designsystem.GeunalType
import app.geunal.designsystem.LocalDarkTheme
import app.geunal.designsystem.LocalNeutralColors
import app.geunal.designsystem.Metrics
import app.geunal.designsystem.Radius
import app.geunal.designsystem.Space
import app.geunal.designsystem.components.ButtonKind
import app.geunal.designsystem.components.GeunalButton
import app.geunal.designsystem.components.NoticeCard
import app.geunal.designsystem.components.NoticeKind
import app.geunal.designsystem.problemBackground
import app.geunal.feature.account.R
import kotlinx.coroutines.launch
import app.geunal.designsystem.R as DesignR

/**
 * Screen 21 in its three modes, with its sheets and the progress and result steps. Where the flow goes after a start
 * without the code, "나중에" or an expired ticket is the caller's, from [RestoreModel.Phase].
 */
@Composable
fun RestoreScreen(
    model: RestoreModel,
    onBack: () -> Unit,
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by model.state.collectAsStateWithLifecycle()
    val neutrals = LocalNeutralColors.current
    SecureWindow()
    // Leaving mid-restore would strand a half-written store; once it is done, back means the same as "캘린더 열기".
    BackHandler(enabled = state.phase is RestoreModel.Phase.Restoring || state.phase is RestoreModel.Phase.Finished) {
        if (state.phase is RestoreModel.Phase.Finished) onDone()
    }
    Column(
        modifier =
            modifier
                .fillMaxSize()
                .background(neutrals.background)
                .statusBarsPadding()
                .navigationBarsPadding()
                .imePadding()
                .padding(horizontal = Metrics.Button.sideInset),
    ) {
        when (val phase = state.phase) {
            is RestoreModel.Phase.Restoring -> Restoring(phase)
            is RestoreModel.Phase.Finished -> Finished(model.mode, phase, onDone = onDone)
            else -> Entering(model, state, onBack)
        }
    }
    state.noCode?.let { NoCodeSheet(model, it, state.isWorking) }
    (state.phase as? RestoreModel.Phase.AskingLocalData)?.let { KeepLocalSheet(it.local, state.isWorking, model::choose) }
}

@Composable
private fun ColumnScope.Entering(
    model: RestoreModel,
    state: RestoreModel.State,
    onBack: () -> Unit,
) {
    val neutrals = LocalNeutralColors.current
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    TextButton(onClick = onBack, modifier = Modifier.padding(top = Space.xs).heightIn(min = Metrics.minHitTarget).testTag("restore.back")) {
        Icon(painterResource(DesignR.drawable.ds_ic_chevron_left), contentDescription = null, tint = neutrals.ink)
        Text(stringResource(R.string.restore_back), style = GeunalType.body, color = neutrals.ink)
    }
    Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
        Spacer(Modifier.height(Space.s))
        Text(stringResource(title(model.mode)), style = GeunalType.largeTitle, color = neutrals.ink)
        Spacer(Modifier.height(Space.s))
        Text(stringResource(body(model.mode)), style = GeunalType.secondary, color = neutrals.secondaryText)
        Spacer(Modifier.height(Space.xl))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                stringResource(R.string.restore_progress, state.acceptedCount),
                style = GeunalType.bodyStrong,
                color = neutrals.secondaryText,
                modifier = Modifier.weight(1f),
            )
            GeunalButton(
                text = stringResource(R.string.restore_paste),
                onClick = {
                    scope.launch {
                        val text =
                            clipboard
                                .getClipEntry()
                                ?.clipData
                                ?.takeIf { it.itemCount > 0 }
                                ?.getItemAt(0)
                                ?.text
                        if (text != null) model.paste(text.toString())
                    }
                },
                kind = ButtonKind.SmallPill,
                modifier = Modifier.testTag("restore.paste"),
            )
        }
        Spacer(Modifier.height(Space.s))
        WordGrid(model, state)
        Spacer(Modifier.height(Space.m))
        ProblemCard(model.mode, state.problem)
        NoticeCard(text = stringResource(R.string.restore_reassurance))
        Spacer(Modifier.height(Space.m))
    }
    val problem = state.problem
    val (label, enabled) =
        when {
            problem is RestoreModel.Problem.UnknownWord ->
                stringResource(R.string.restore_fix_word, problem.index + 1) to false
            !state.isComplete -> stringResource(R.string.restore_more_words, RestoreModel.WORD_COUNT - state.acceptedCount) to false
            state.isWorking -> stringResource(R.string.busy) to false
            problem != null -> stringResource(R.string.restore_retry) to true
            model.mode == RestoreMode.GUEST_RETURN -> stringResource(R.string.restore_action_guest) to true
            else -> stringResource(R.string.restore_action_device_change) to true
        }
    GeunalButton(text = label, onClick = model::start, enabled = enabled, modifier = Modifier.testTag("restore.start"))
    GeunalButton(
        text = stringResource(R.string.restore_no_code),
        onClick = model::openNoCode,
        kind = ButtonKind.Text,
        modifier = Modifier.fillMaxWidth().padding(vertical = Space.xs).testTag("restore.noCode"),
    )
}

@Composable
private fun WordGrid(
    model: RestoreModel,
    state: RestoreModel.State,
) {
    val requesters = remember { List(RestoreModel.WORD_COUNT) { FocusRequester() } }
    LaunchedEffect(state.focused) { state.focused?.let { requesters[it].requestFocus() } }
    NoKeyboardLearning {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            for (row in 0 until RestoreModel.WORD_COUNT / 3) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    for (column in 0 until 3) {
                        val index = row * 3 + column
                        WordCell(
                            index = index,
                            state = state,
                            requester = requesters[index],
                            model = model,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun WordCell(
    index: Int,
    state: RestoreModel.State,
    requester: FocusRequester,
    model: RestoreModel,
    modifier: Modifier = Modifier,
) {
    val neutrals = LocalNeutralColors.current
    val dark = LocalDarkTheme.current
    // The field draws its own copy, updated in the same frame as the keyboard's edit: a value that came back later through
    // the model's flow would make the IME drop the Hangul syllable it is composing, or undo the newest keystroke. It takes
    // the model's text only when the model rewrote the cell itself (a taken hint, a paste).
    val rewrite = state.rewrites[index]
    var text by remember { mutableStateOf(state.cells[index]) }
    LaunchedEffect(rewrite) { text = model.state.value.cells[index] }
    // Only a focus the cell really had is left; the first, unfocused callback on attaching is not a departure.
    var hadFocus by remember { mutableStateOf(false) }
    val focused = state.focused == index
    val unknown = state.isUnknown(index)
    val accepted = state.accepted[index] != null
    val shape = RoundedCornerShape(Radius.input)
    val background =
        when {
            unknown -> problemBackground(dark)
            accepted && !focused -> neutrals.groupedBackground
            else -> neutrals.background
        }
    val border =
        when {
            focused -> Modifier.border(2.dp, neutrals.ink, shape)
            unknown -> Modifier.border(1.dp, neutrals.sunday, shape)
            accepted -> Modifier
            else -> Modifier.border(1.dp, neutrals.separator, shape)
        }
    val hint =
        state.completions[index]
            ?.takeIf { !accepted && it.startsWith(text.trim()) && text.isNotBlank() }
            ?.substring(text.trim().length)
    val label = stringResource(R.string.restore_cell, index + 1)
    BasicTextField(
        value = text,
        onValueChange = {
            text = it
            model.changed(index, it)
        },
        singleLine = true,
        textStyle = GeunalType.bodyStrong.copy(color = if (unknown) neutrals.sunday else neutrals.ink),
        cursorBrush = SolidColor(neutrals.ink),
        keyboardOptions =
            KeyboardOptions(
                capitalization = KeyboardCapitalization.None,
                autoCorrectEnabled = false,
                keyboardType = KeyboardType.Text,
                imeAction = ImeAction.Next,
            ),
        keyboardActions = KeyboardActions(onNext = { model.submitted(index) }),
        visualTransformation = HintTransformation(hint, neutrals.tertiaryText),
        modifier =
            modifier
                .heightIn(min = 48.dp)
                .clip(shape)
                .background(background)
                .then(border)
                .focusRequester(requester)
                .onFocusChanged {
                    if (it.isFocused) {
                        hadFocus = true
                        model.focused(index)
                    } else if (hadFocus) {
                        hadFocus = false
                        model.left(index)
                    }
                }.semantics { contentDescription = label }
                .testTag("restore.cell.$index"),
        decorationBox = { inner ->
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = Space.s)) {
                Text("${index + 1}", style = GeunalType.caption, color = neutrals.tertiaryText)
                Spacer(Modifier.width(Space.xs))
                Box(Modifier.weight(1f)) { inner() }
            }
        },
    )
}

/** Shows the rest of the one word a prefix leads to, in tertiary text after the cursor, without typing it. */
private class HintTransformation(
    private val hint: String?,
    private val color: Color,
) : VisualTransformation {
    override fun filter(text: AnnotatedString): TransformedText {
        if (hint.isNullOrEmpty()) return TransformedText(text, OffsetMapping.Identity)
        val shown =
            buildAnnotatedString {
                append(text)
                withStyle(SpanStyle(color = color)) { append(hint) }
            }
        val length = text.length
        return TransformedText(
            shown,
            object : OffsetMapping {
                override fun originalToTransformed(offset: Int): Int = offset

                override fun transformedToOriginal(offset: Int): Int = minOf(offset, length)
            },
        )
    }

    override fun equals(other: Any?): Boolean = other is HintTransformation && other.hint == hint && other.color == color

    override fun hashCode(): Int = hint.hashCode() * 31 + color.hashCode()
}

@Composable
private fun ProblemCard(
    mode: RestoreMode,
    problem: RestoreModel.Problem?,
) {
    val text =
        when (problem) {
            is RestoreModel.Problem.UnknownWord -> stringResource(R.string.restore_error_word, problem.index + 1)
            RestoreModel.Problem.WrongCode ->
                stringResource(
                    if (mode == RestoreMode.GUEST_RETURN) R.string.restore_error_guest_not_found else R.string.restore_error_locked,
                )
            RestoreModel.Problem.Offline -> stringResource(R.string.restore_error_offline)
            RestoreModel.Problem.Failed -> stringResource(R.string.restore_error_failed)
            null -> return
        }
    NoticeCard(
        text = text,
        kind = if (problem == RestoreModel.Problem.Offline) NoticeKind.Offline else NoticeKind.Problem,
        modifier = Modifier.padding(bottom = Space.s).testTag("restore.problem"),
    )
}

@Composable
private fun Restoring(phase: RestoreModel.Phase.Restoring) {
    val neutrals = LocalNeutralColors.current
    val context = LocalContext.current
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center) {
        Text(stringResource(R.string.restore_restoring_title), style = GeunalType.largeTitle, color = neutrals.ink)
        Spacer(Modifier.height(Space.s))
        Text(stringResource(R.string.restore_restoring_body), style = GeunalType.secondary, color = neutrals.secondaryText)
        Spacer(Modifier.height(Space.xl))
        if (phase.total > 0) {
            LinearProgressIndicator(
                progress = { (phase.done.toFloat() / phase.total).coerceIn(0f, 1f) },
                color = neutrals.ink,
                trackColor = neutrals.fill,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(Space.xs))
            Text(
                stringResource(
                    R.string.restore_restoring_size,
                    Formatter.formatShortFileSize(context, phase.done),
                    Formatter.formatShortFileSize(context, phase.total),
                ),
                style = GeunalType.caption,
                color = neutrals.secondaryText,
            )
        } else {
            LinearProgressIndicator(color = neutrals.ink, trackColor = neutrals.fill, modifier = Modifier.fillMaxWidth())
        }
    }
}

@Composable
private fun ColumnScope.Finished(
    mode: RestoreMode,
    phase: RestoreModel.Phase.Finished,
    onDone: () -> Unit,
) {
    val neutrals = LocalNeutralColors.current
    val summary = phase.summary
    Column(Modifier.weight(1f), verticalArrangement = Arrangement.Center) {
        Text(stringResource(R.string.restore_done_title), style = GeunalType.largeTitle, color = neutrals.ink)
        Spacer(Modifier.height(Space.s))
        val restoredBackup = summary.backupAt != null
        val body =
            when {
                restoredBackup ->
                    stringResource(
                        R.string.restore_done_body,
                        summary.events.toInt(),
                        summary.people.toInt(),
                        summary.calendars.toInt(),
                    )
                mode == RestoreMode.GUEST_RETURN -> stringResource(R.string.restore_done_guest_no_backup)
                summary.replacedDevice -> stringResource(R.string.restore_done_no_backup)
                else -> stringResource(R.string.restore_done_no_backup_offer)
            }
        Text(body, style = GeunalType.body, color = neutrals.ink, modifier = Modifier.testTag("restore.done"))
        if (restoredBackup && summary.replacedDevice) {
            Spacer(Modifier.height(Space.xs))
            Text(stringResource(R.string.restore_done_replaced), style = GeunalType.secondary, color = neutrals.secondaryText)
        }
        if (!restoredBackup && mode != RestoreMode.GUEST_RETURN) {
            Spacer(Modifier.height(Space.m))
            NoticeCard(text = stringResource(R.string.restore_done_no_backup_hint))
        }
    }
    GeunalButton(
        text = stringResource(R.string.restore_open_calendar),
        onClick = onDone,
        modifier = Modifier.padding(bottom = Space.m).testTag("restore.open"),
    )
}

private fun title(mode: RestoreMode): Int =
    when (mode) {
        RestoreMode.DEVICE_CHANGE -> R.string.restore_title_device_change
        RestoreMode.OFFER -> R.string.restore_title_offer
        RestoreMode.GUEST_RETURN -> R.string.restore_title_guest
    }

private fun body(mode: RestoreMode): Int =
    when (mode) {
        RestoreMode.DEVICE_CHANGE -> R.string.restore_body_device_change
        RestoreMode.OFFER -> R.string.restore_body_offer
        RestoreMode.GUEST_RETURN -> R.string.restore_body_guest
    }
