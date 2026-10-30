package app.geunal.designsystem.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
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

/**
 * The one toast on screen (components.md §6): shown for five seconds, replaced by a newer one, closed at once when its
 * action runs. Instead of asking first, the app acts and offers this (tone.md "확인 대신 되돌리기").
 */
class ToastCenter(
    private val scope: CoroutineScope,
    private val durationMs: Long = Motion.TOAST_DURATION_MS,
) {
    private val _current = MutableStateFlow<Toast?>(null)
    val current: StateFlow<Toast?> = _current.asStateFlow()
    private var timer: Job? = null
    private var next = 0L

    fun show(
        message: String,
        action: ToastAction? = null,
    ) {
        val toast = Toast(message, action, id = next++)
        _current.value = toast
        timer?.cancel()
        timer =
            scope.launch {
                delay(durationMs)
                if (_current.value?.id == toast.id) _current.value = null
            }
    }

    fun performAction() {
        val toast = _current.value ?: return
        dismiss()
        toast.action?.perform?.invoke()
    }

    fun dismiss() {
        timer?.cancel()
        _current.value = null
    }
}

/** Where a screen shows the [ToastCenter]'s toast: ink background, white text, the action on the right. */
@Composable
fun ToastHost(
    center: ToastCenter,
    modifier: Modifier = Modifier,
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
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier =
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = Metrics.Toast.height)
                    .clip(RoundedCornerShape(Metrics.Toast.radius))
                    .background(neutrals.ink)
                    .semantics { liveRegion = LiveRegionMode.Polite }
                    .padding(start = Space.m),
        ) {
            Text(
                visible.message,
                style = GeunalType.buttonSmall,
                color = neutrals.background,
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
                            .padding(horizontal = Space.m),
                ) {
                    Text(action.label, style = GeunalType.buttonSmall, color = neutrals.background)
                }
            } else {
                Box(Modifier.padding(end = 4.dp))
            }
        }
    }
}
