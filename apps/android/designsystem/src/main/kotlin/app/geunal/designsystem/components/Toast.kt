package app.geunal.designsystem.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.geunal.designsystem.GeunalType
import app.geunal.designsystem.LocalNeutralColors
import app.geunal.designsystem.Metrics
import app.geunal.designsystem.Motion
import app.geunal.designsystem.Space
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** One toast: the result in a few words, and at most one thing to do about it ("되돌리기"). */
data class Toast(
    val message: String,
    val action: ToastAction? = null,
    /** Tells two equal messages apart, so showing the same text again restarts the five seconds. */
    val id: Long = 0,
)

data class ToastAction(
    val label: String,
    val perform: () -> Unit,
)

/** A question that comes with a toast: "매주 금요일 반복으로 바꿀까요?" · 괜찮아요 · 반복으로 바꾸기 (m1.md screen 6). */
data class Proposal(
    val message: String,
    val declineLabel: String,
    val acceptLabel: String,
)

/** The card on screen with its question. */
data class Question(
    val proposal: Proposal,
    val id: Long,
)

/**
 * The one toast on screen (components.md §6): shown for five seconds, replaced by a newer one, closed at once when its
 * action runs. Instead of asking first, the app acts and offers this (tone.md "확인 대신 되돌리기").
 *
 * A question that comes with it is a card of its own that outlives the toast (m1.md decision 28): the toast and its undo
 * last five seconds because the core takes an undo back only that long after the add, while the question is asked once,
 * so its card stays [proposalDurationMs], and with a screen reader running until it is answered.
 */
class ToastCenter(
    private val scope: CoroutineScope,
    private val durationMs: Long = Motion.TOAST_DURATION_MS,
    private val proposalDurationMs: Long = Motion.PROPOSAL_DURATION_MS,
    private val screenReaderRunning: () -> Boolean = { false },
) {
    private val _current = MutableStateFlow<Toast?>(null)
    val current: StateFlow<Toast?> = _current.asStateFlow()
    private val _question = MutableStateFlow<Question?>(null)
    val question: StateFlow<Question?> = _question.asStateFlow()
    private var timer: Job? = null
    private var questionTimer: Job? = null
    private var acceptance: (() -> Unit)? = null
    private var next = 0L

    /** Shows [message], replacing any toast on screen and its question. */
    fun show(
        message: String,
        action: ToastAction? = null,
    ) {
        dismissQuestion()
        val toast = Toast(message, action, id = next++)
        _current.value = toast
        timer?.cancel()
        timer =
            scope.launch {
                delay(durationMs)
                if (_current.value?.id == toast.id) _current.value = null
            }
    }

    /** Shows [message] and the card with [proposal] above it; [onAccept] runs when the person takes the proposal. */
    fun show(
        message: String,
        action: ToastAction?,
        proposal: Proposal,
        onAccept: () -> Unit,
    ) {
        show(message, action)
        val question = Question(proposal, id = next++)
        _question.value = question
        acceptance = onAccept
        if (screenReaderRunning()) return
        questionTimer =
            scope.launch {
                delay(proposalDurationMs)
                if (_question.value?.id == question.id) dismissQuestion()
            }
    }

    /** The toast's action, the undo. The question goes with it: what it asked about is gone. */
    fun performAction() {
        val toast = _current.value ?: return
        dismiss()
        dismissQuestion()
        toast.action?.perform?.invoke()
    }

    /** "반복으로 바꾸기": the card goes; what [onAccept] shows replaces the toast. */
    fun acceptProposal() {
        val accept = acceptance
        dismissQuestion()
        accept?.invoke()
    }

    /** "괜찮아요": the card goes, and the toast stays for the rest of its time. */
    fun declineProposal() {
        dismissQuestion()
    }

    /** Closes the toast. A question stays for its own time. */
    fun dismiss() {
        timer?.cancel()
        _current.value = null
    }

    private fun dismissQuestion() {
        questionTimer?.cancel()
        questionTimer = null
        acceptance = null
        _question.value = null
    }
}

/** How a toast looks where it shows. */
enum class ToastStyle {
    /** Over a plain screen: the ink pill, 48 high with radius 14 and white text. */
    Ink,

    /** In the next-up bar's place: the bar's 56 capsule with ink text (components.md "토스트"). */
    Slot,
}

/** Where a screen shows the [ToastCenter]'s toast, with its action on the right. */
@Composable
fun ToastHost(
    center: ToastCenter,
    modifier: Modifier = Modifier,
    style: ToastStyle = ToastStyle.Ink,
) {
    val toast by center.current.collectAsState()
    val neutrals = LocalNeutralColors.current
    // The last toast shown, so the exit animation still has something to draw after the center clears it.
    var shown by remember { mutableStateOf<Toast?>(null) }
    SideEffect { if (toast != null) shown = toast }
    AnimatedVisibility(
        visible = toast != null,
        enter = fadeIn(tween(Motion.TOAST_IN_MS)) + slideInVertically(tween(Motion.TOAST_IN_MS)) { it / 2 },
        exit = fadeOut(tween(Motion.TOAST_OUT_MS)) + slideOutVertically(tween(Motion.TOAST_OUT_MS)) { it / 2 },
        modifier = modifier,
    ) {
        val visible = toast ?: shown ?: return@AnimatedVisibility
        val slot = style == ToastStyle.Slot
        val text = if (slot) neutrals.ink else neutrals.background
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier =
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = if (slot) Metrics.NextUpBar.height else Metrics.Toast.height)
                    .then(
                        if (slot) {
                            Modifier.floatingSurface(CircleShape)
                        } else {
                            Modifier.clip(RoundedCornerShape(Metrics.Toast.radius)).background(neutrals.ink)
                        },
                    ).semantics { liveRegion = LiveRegionMode.Polite }
                    .padding(start = if (slot) Space.l else Space.m)
                    .testTag("toast"),
        ) {
            Text(
                visible.message,
                style = GeunalType.buttonSmall,
                color = text,
                maxLines = 2,
                modifier = Modifier.weight(1f).padding(vertical = Space.s),
            )
            val action = visible.action
            if (action != null) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier =
                        Modifier
                            .heightIn(min = Metrics.minHitTarget)
                            .clickable(role = Role.Button) { center.performAction() }
                            .padding(horizontal = Space.m)
                            .testTag("toast.action"),
                ) {
                    Text(action.label, style = GeunalType.buttonSmall, color = text)
                }
            } else {
                Box(Modifier.padding(end = 4.dp))
            }
        }
    }
}

/**
 * The question a toast may carry, as a card 8 above the toast (components.md "제안 카드"): the next-up bar's surface with
 * radius 24, the question in 15 · 600, then 괜찮아요 and the action as two 44 pills on the right.
 */
@Composable
fun ProposalHost(
    center: ToastCenter,
    modifier: Modifier = Modifier,
) {
    val question by center.question.collectAsState()
    var shown by remember { mutableStateOf<Question?>(null) }
    SideEffect { if (question != null) shown = question }
    AnimatedVisibility(
        visible = question != null,
        enter = fadeIn(tween(Motion.TOAST_IN_MS)) + slideInVertically(tween(Motion.TOAST_IN_MS)) { it / 2 },
        exit = fadeOut(tween(Motion.TOAST_OUT_MS)) + slideOutVertically(tween(Motion.TOAST_OUT_MS)) { it / 2 },
        modifier = modifier,
    ) {
        val visible = question ?: shown ?: return@AnimatedVisibility
        ProposalCard(visible.proposal, onDecline = center::declineProposal, onAccept = center::acceptProposal)
    }
}

@Composable
private fun ProposalCard(
    proposal: Proposal,
    onDecline: () -> Unit,
    onAccept: () -> Unit,
) {
    val neutrals = LocalNeutralColors.current
    Column(
        verticalArrangement = Arrangement.spacedBy(Space.xs),
        modifier =
            Modifier
                .fillMaxWidth()
                .floatingSurface(RoundedCornerShape(PROPOSAL_RADIUS))
                .semantics { liveRegion = LiveRegionMode.Polite }
                .padding(start = 18.dp, top = 14.dp, end = Space.s, bottom = Space.s)
                .testTag("proposal"),
    ) {
        Text(proposal.message, style = GeunalType.buttonSmall, color = neutrals.ink)
        Row(horizontalArrangement = Arrangement.spacedBy(Space.xs, Alignment.End), modifier = Modifier.fillMaxWidth()) {
            GeunalButton(
                text = proposal.declineLabel,
                onClick = onDecline,
                kind = ButtonKind.SmallPill,
                height = Metrics.Button.secondary,
                modifier = Modifier.testTag("proposal.decline"),
            )
            GeunalButton(
                text = proposal.acceptLabel,
                onClick = onAccept,
                height = Metrics.Button.secondary,
                fullWidth = false,
                modifier = Modifier.testTag("proposal.accept"),
            )
        }
    }
}

/** Not a token: components.md gives the proposal card its own radius. */
private val PROPOSAL_RADIUS = 24.dp
