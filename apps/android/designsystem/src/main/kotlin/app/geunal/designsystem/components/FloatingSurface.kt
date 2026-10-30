package app.geunal.designsystem.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp
import app.geunal.designsystem.LocalNeutralColors

/**
 * The surface of what floats over the tab bar: the next-up bar, the toast in its place and the proposal card
 * (docs/design/README.md §5). iOS draws glass there; Android keeps the background color with the boards' hairline and
 * shadow, since Material 3 has no blur under a surface.
 */
@Composable
fun Modifier.floatingSurface(shape: Shape): Modifier {
    val neutrals = LocalNeutralColors.current
    return this
        .shadow(12.dp, shape, clip = false)
        .clip(shape)
        .background(neutrals.background)
        .border(1.dp, Color.Black.copy(alpha = 0.07f), shape)
}
