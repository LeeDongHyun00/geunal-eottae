package app.geunal.designsystem

import androidx.compose.ui.unit.dp

/**
 * The sizes of docs/design/tokens.json (`space`, `radius`, `component`, `nav`, `motion`) as Compose values, named the way
 * iOS names them so both apps read the same. DesignTokensTest keeps them equal to the JSON.
 */
object Space {
    val xxs = 4.dp
    val xs = 8.dp
    val s = 12.dp
    val m = 16.dp
    val l = 20.dp
    val xl = 24.dp
    val xxl = 32.dp
    val xxxl = 48.dp
}

object Radius {
    val strip = 4.dp
    val cell = 8.dp
    val input = 12.dp
    val card = 20.dp
    val sheet = 20.dp
}

object Metrics {
    /** Anything a finger can press is at least this big, whatever it draws (components.md §1). */
    val minHitTarget = 44.dp

    object Button {
        val primary = 52.dp
        val secondary = 44.dp
        val text = 44.dp
        val smallPill = 36.dp
        val icon = 44.dp

        /** A full-width bottom button keeps this much from the screen edges. */
        val sideInset = 20.dp
    }

    object Chip {
        val suggestion = 44.dp
        val select = 36.dp
        val badge = 24.dp
        val dot = 8.dp
    }

    object Segment {
        val height = 40.dp
        val padding = 2.dp
    }

    object Checkbox {
        val size = 24.dp
        val radius = 6.dp
    }

    object Row {
        val setting = 52.dp
        val twoLine = 60.dp
        val event = 48.dp
        val card = 84.dp
    }

    object Sheet {
        val topRadius = 20.dp
        val paddingTop = 8.dp
        val paddingSide = 20.dp
        val paddingBottom = 34.dp
        const val MAX_HEIGHT_RATIO = 0.9f
        val grabberWidth = 36.dp
        val grabberHeight = 5.dp
    }

    object Toast {
        val height = 48.dp
        val radius = 14.dp
        val gapAboveTabBar = 8.dp
    }

    object TabBar {
        val height = 64.dp
        val sideInset = 16.dp
        val bottomInset = 20.dp
        val radius = 32.dp
        val icon = 26.dp
    }

    object NextUpBar {
        val height = 56.dp
        val gapAboveTabBar = 8.dp
        val radius = 28.dp
        val thumb = 40.dp
        val thumbRadius = 10.dp
    }
}

object Motion {
    const val PRESS_SCALE = 0.97f
    const val PRESS_MS = 120
    const val TOAST_IN_MS = 200
    const val TOAST_OUT_MS = 200
    const val TOAST_DURATION_MS = 5_000L
    const val NEXT_UP_MS = 160
    const val REDUCED_MOTION_FADE_MS = 120
}
