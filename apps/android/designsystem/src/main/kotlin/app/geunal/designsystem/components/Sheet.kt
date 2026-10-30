package app.geunal.designsystem.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.ModalBottomSheetProperties
import androidx.compose.material3.SheetValue
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import app.geunal.designsystem.LocalNeutralColors
import app.geunal.designsystem.Metrics

/** The sheet scrim of docs/design/tokens.json (`sheet.scrim`). */
val SheetScrim = Color(0x520A0A0E)

private val GrabberColor = Color(0xFFD1D1D6)

/**
 * A bottom sheet in the design's chrome: corner 20 at the top, the 36×5 grabber, the scrim at 32% (components.md §6).
 * A sheet that must be answered (m1.md screen 24) passes `dismissible = false`: it neither drags down nor closes on the
 * scrim or the back gesture.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GeunalSheet(
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    dismissible: Boolean = true,
    content: @Composable ColumnScope.() -> Unit,
) {
    val neutrals = LocalNeutralColors.current
    val state =
        rememberModalBottomSheetState(
            skipPartiallyExpanded = true,
            confirmValueChange = { dismissible || it != SheetValue.Hidden },
        )
    ModalBottomSheet(
        onDismissRequest = { if (dismissible) onDismiss() },
        sheetState = state,
        modifier = modifier,
        shape = RoundedCornerShape(topStart = Metrics.Sheet.topRadius, topEnd = Metrics.Sheet.topRadius),
        containerColor = neutrals.background,
        scrimColor = SheetScrim,
        sheetGesturesEnabled = dismissible,
        dragHandle = {
            Box(
                Modifier
                    .padding(top = Metrics.Sheet.paddingTop)
                    .size(Metrics.Sheet.grabberWidth, Metrics.Sheet.grabberHeight)
                    .background(GrabberColor, CircleShape),
            )
        },
        properties =
            ModalBottomSheetProperties(
                shouldDismissOnBackPress = dismissible,
                shouldDismissOnClickOutside = dismissible,
            ),
        content = content,
    )
}
