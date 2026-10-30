package app.geunal.designsystem

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color

/**
 * Material 3 on the neutral tokens: white (or black) background, ink text, and ink as the accent, because inside my
 * calendar the primary button is ink and inside a group the group's color takes over (docs/design/README.md §1).
 * Group accents are applied per screen from [GroupColors], not through the theme.
 */
@Composable
fun GeunalTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val neutrals = if (darkTheme) DarkNeutrals else LightNeutrals
    val scheme =
        if (darkTheme) {
            darkColorScheme(
                primary = neutrals.ink,
                onPrimary = neutrals.background,
                background = neutrals.background,
                onBackground = neutrals.ink,
                surface = neutrals.background,
                onSurface = neutrals.ink,
                surfaceVariant = neutrals.groupedBackground,
                onSurfaceVariant = neutrals.secondaryText,
                outline = neutrals.separator,
                outlineVariant = neutrals.separator,
                error = neutrals.sunday,
                onError = Color.White,
            )
        } else {
            lightColorScheme(
                primary = neutrals.ink,
                onPrimary = neutrals.background,
                background = neutrals.background,
                onBackground = neutrals.ink,
                surface = neutrals.background,
                onSurface = neutrals.ink,
                surfaceVariant = neutrals.groupedBackground,
                onSurfaceVariant = neutrals.secondaryText,
                outline = neutrals.separator,
                outlineVariant = neutrals.separator,
                error = neutrals.sunday,
                onError = Color.White,
            )
        }
    CompositionLocalProvider(LocalNeutralColors provides neutrals, LocalDarkTheme provides darkTheme) {
        MaterialTheme(colorScheme = scheme, typography = GeunalTypography, content = content)
    }
}
