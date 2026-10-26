package app.geunal.designsystem

import androidx.compose.material3.Typography
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp

/**
 * The eight steps of docs/design/README.md §3. Sizes are sp so the user's text size setting applies; digits are tabular
 * so times and tables do not jitter; Korean breaks between words. Pretendard is not bundled yet, so the system font
 * draws the scale.
 */
object GeunalType {
    val largeTitle = style(32.sp, FontWeight.SemiBold, (-0.025).em)
    val title1 = style(26.sp, FontWeight.SemiBold, (-0.025).em)
    val title2 = style(20.sp, FontWeight.SemiBold, (-0.015).em)
    val bodyStrong = style(17.sp, FontWeight.SemiBold, (-0.01).em)
    val body = style(17.sp, FontWeight.Normal, (-0.01).em, lineHeight = 25.5.sp)
    val secondary = style(15.sp, FontWeight.Normal, (-0.01).em, lineHeight = 22.sp)
    val caption = style(13.sp, FontWeight.Normal, (-0.01).em)
    val micro = style(11.sp, FontWeight.SemiBold, (-0.01).em)

    /** Button and chip labels: 17 · 600, 15 · 600 and 14 · 600 (components.md §2). */
    val button = bodyStrong
    val buttonSmall = style(15.sp, FontWeight.SemiBold, (-0.01).em)
    val pill = style(14.sp, FontWeight.SemiBold, (-0.01).em)

    private fun style(
        size: TextUnit,
        weight: FontWeight,
        tracking: TextUnit,
        lineHeight: TextUnit = TextUnit.Unspecified,
    ) = TextStyle(
        fontFamily = FontFamily.Default,
        fontSize = size,
        fontWeight = weight,
        letterSpacing = tracking,
        lineHeight = lineHeight,
        fontFeatureSettings = "tnum",
        // Word-level breaking for Korean (design README §3); API 33 and later honour it, older releases break anywhere.
        lineBreak = LineBreak.Paragraph.copy(wordBreak = LineBreak.WordBreak.Phrase),
        platformStyle = PlatformTextStyle(includeFontPadding = false),
        lineHeightStyle = LineHeightStyle(alignment = LineHeightStyle.Alignment.Center, trim = LineHeightStyle.Trim.None),
    )
}

/** The same steps on Material's slots, for the Material components that read the theme's typography. */
val GeunalTypography =
    Typography(
        headlineLarge = GeunalType.largeTitle,
        headlineMedium = GeunalType.title1,
        titleLarge = GeunalType.title2,
        titleMedium = GeunalType.bodyStrong,
        bodyLarge = GeunalType.body,
        bodyMedium = GeunalType.secondary,
        bodySmall = GeunalType.caption,
        labelLarge = GeunalType.button,
        labelMedium = GeunalType.pill,
        labelSmall = GeunalType.micro,
    )
