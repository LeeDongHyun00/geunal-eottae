package app.geunal.designsystem

import androidx.compose.material3.Typography
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp

/**
 * The type scale of docs/design/README.md §3 on Material's slots. Sizes are sp so the user's text size setting
 * applies; digits are tabular so times and tables do not jitter. Pretendard lands with the M1 screens, so until then
 * the system font draws the scale.
 */
val GeunalTypography =
    Typography(
        headlineLarge = style(32.sp, FontWeight.SemiBold, (-0.025).em),
        headlineMedium = style(26.sp, FontWeight.SemiBold, (-0.025).em),
        titleLarge = style(20.sp, FontWeight.SemiBold, (-0.015).em),
        titleMedium = style(17.sp, FontWeight.SemiBold, (-0.01).em),
        bodyLarge = style(17.sp, FontWeight.Normal, (-0.01).em, lineHeight = 25.5.sp),
        bodyMedium = style(15.sp, FontWeight.Normal, (-0.01).em),
        bodySmall = style(13.sp, FontWeight.Normal, (-0.01).em),
        labelSmall = style(11.sp, FontWeight.SemiBold, (-0.01).em),
    )

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
    platformStyle = PlatformTextStyle(includeFontPadding = false),
    lineHeightStyle = LineHeightStyle(alignment = LineHeightStyle.Alignment.Center, trim = LineHeightStyle.Trim.None),
)
