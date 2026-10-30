package app.geunal.designsystem

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/** The neutral roles of docs/design/README.md §2.1, one set per mode. */
@Immutable
data class NeutralColors(
    val background: Color,
    val groupedBackground: Color,
    val emptyCell: Color,
    val fill: Color,
    val separator: Color,
    val ink: Color,
    val secondaryText: Color,
    val tertiaryText: Color,
    val sunday: Color,
    val saturday: Color,
)

val LightNeutrals =
    NeutralColors(
        background = Color(0xFFFFFFFF),
        groupedBackground = Color(0xFFF4F4F6),
        emptyCell = Color(0xFFF2F2F5),
        fill = Color(0xFFEBEBEF),
        separator = Color(0xFFE1E1E6),
        ink = Color(0xFF1B1B1F),
        secondaryText = Color(0xFF5C5C66),
        tertiaryText = Color(0xFF696973),
        sunday = Color(0xFFC92F26),
        saturday = Color(0xFF2958C8),
    )

val DarkNeutrals =
    NeutralColors(
        background = Color(0xFF000000),
        groupedBackground = Color(0xFF1C1C1F),
        emptyCell = Color(0xFF161618),
        fill = Color(0xFF2A2A2E),
        separator = Color(0xFF333338),
        ink = Color(0xFFF4F4F6),
        secondaryText = Color(0xFFB4B4BC),
        tertiaryText = Color(0xFF93939D),
        sunday = Color(0xFFFF6B61),
        saturday = Color(0xFF7AA2FF),
    )

/**
 * The background behind a problem: a cell whose word is not in the list and the red notice card (docs/design/m1.md
 * screen 21). The light value is the one the design gives; the dark one is the dark `sunday` at the same weight over
 * the black background, which the design leaves open.
 */
fun problemBackground(dark: Boolean): Color = if (dark) Color(0xFF2A1311) else Color(0xFFFBECEB)

/** Brand colors (docs/design/README.md §2.3): the tab bar and the app icon only. */
object BrandColors {
    val grape = Color(0xFF7D63CB)
    val tabTextLight = Color(0xFF7152C4)
    val tabTextDark = Color(0xFFA898EB)
    val androidIndicator = Color(0xFFD9D4F8)
    val androidBar = Color(0xFFF4F4F6)
}

/**
 * One of the twelve group colors with its five tints (docs/design/README.md §2.2). Index 0 is the lightest tint, index
 * 4 the group's representative color, which is the same in both modes.
 */
@Immutable
data class GroupColor(
    val name: String,
    val light: List<Color>,
    val dark: List<Color>,
) {
    init {
        require(light.size == TINTS && dark.size == TINTS) { "$name needs $TINTS tints per mode" }
    }

    companion object {
        const val TINTS = 5
    }
}

val GroupColors: List<GroupColor> =
    listOf(
        GroupColor(
            name = "tomato",
            light = tints(0xF9CDC8, 0xF5AAA1, 0xED887D, 0xDE685D, 0xC54E45),
            dark = tints(0x3E211E, 0x5C2D28, 0x7C3933, 0x9E453E, 0xC54E45),
        ),
        GroupColor(
            name = "tangerine",
            light = tints(0xF5D1BC, 0xEFB08A, 0xE5905A, 0xD57128, 0xBA5A00),
            dark = tints(0x3C2314, 0x593116, 0x783E16, 0x984C13, 0xBA5A00),
        ),
        GroupColor(
            name = "mustard",
            light = tints(0xEAD7B4, 0xDCBA7B, 0xCD9D3D, 0xB58400, 0x976D00),
            dark = tints(0x35280D, 0x4E3704, 0x664900, 0x7E5B00, 0x976D00),
        ),
        GroupColor(
            name = "olive",
            light = tints(0xD3DFBB, 0xB5C887, 0x98B152, 0x7D9911, 0x667E00),
            dark = tints(0x262E13, 0x354112, 0x45550C, 0x556900, 0x667E00),
        ),
        GroupColor(
            name = "forest",
            light = tints(0xC3E3C8, 0x94CF9F, 0x64B979, 0x32A256, 0x00873D),
            dark = tints(0x19301E, 0x1B4526, 0x1C5A2F, 0x197037, 0x00873D),
        ),
        GroupColor(
            name = "mint",
            light = tints(0xB6E5DB, 0x77D1C2, 0x19BDAA, 0x00A090, 0x008476),
            dark = tints(0x09312C, 0x00453D, 0x005A50, 0x006F63, 0x008476),
        ),
        GroupColor(
            name = "sky",
            light = tints(0xB8E0F3, 0x7DCAEC, 0x36B3E2, 0x0099C6, 0x007EA4),
            dark = tints(0x0D2E3C, 0x004257, 0x005570, 0x00698A, 0x007EA4),
        ),
        GroupColor(
            name = "sea",
            light = tints(0xC7DAFB, 0xA0C0FA, 0x7BA6F5, 0x5A8BE9, 0x4172D1),
            dark = tints(0x1E2A40, 0x273B61, 0x314D83, 0x3B60A6, 0x4172D1),
        ),
        GroupColor(
            name = "grape",
            light = tints(0xD9D4F8, 0xC1B6F6, 0xAA98F0, 0x937CE3, 0x7D63CB),
            dark = tints(0x2B263F, 0x3D355E, 0x51457F, 0x6655A2, 0x7D63CB),
        ),
        GroupColor(
            name = "plum",
            light = tints(0xECCEEA, 0xE1ACDE, 0xD38CD1, 0xC16DBF, 0xAA54A9),
            dark = tints(0x362235, 0x502F4F, 0x6C3C6B, 0x894A88, 0xAA54A9),
        ),
        GroupColor(
            name = "blossom",
            light = tints(0xF6CCD9, 0xF1A9C0, 0xE786A9, 0xD86691, 0xC04C7B),
            dark = tints(0x3C202A, 0x5A2C3D, 0x793851, 0x9A4465, 0xC04C7B),
        ),
        GroupColor(
            name = "cocoa",
            light = tints(0xE5D5CD, 0xD5B9AA, 0xC29D89, 0xAE836C, 0x966B54),
            dark = tints(0x322822, 0x49382E, 0x61493B, 0x7A5A48, 0x966B54),
        ),
    )

private fun tints(vararg rgb: Long): List<Color> = rgb.map { Color(0xFF000000 or it) }

/**
 * The tint for `free` people free out of `total` (docs/design/README.md §2.2): 0 is the empty cell (null), everyone is
 * the fifth tint, and the rest spread over the first four.
 */
fun freeCountTint(
    free: Int,
    total: Int,
): Int? {
    require(total >= 1 && free in 0..total) { "free=$free total=$total" }
    return when {
        free == 0 -> null
        free == total -> GroupColor.TINTS - 1
        else -> minOf(GroupColor.TINTS - 2, (free - 1) * 4 / (total - 1))
    }
}

/**
 * Events mirrored from the device's calendars all take this one color, whatever their source (m1.md screen 15): cocoa's
 * second tint, the same in both modes.
 */
val DeviceCalendarColor = Color(0xFFD5B9AA)

val LocalNeutralColors = staticCompositionLocalOf { LightNeutrals }

/** Whether the theme is drawing dark; components that pick between two token sets read it. */
val LocalDarkTheme = staticCompositionLocalOf { false }
