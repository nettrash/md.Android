/*
 * Theme.kt
 * md (Android)
 *
 * Wraps the typewriter palette in a Material 3 color scheme. We do NOT
 * use dynamic (wallpaper) color here — the whole point of md is the fixed
 * warm paper-and-ink look, light or dark, matching the iOS / macOS app.
 *
 * The renderer and editor read these roles:
 *   background / surface        → paper
 *   onBackground / onSurface    → ink
 *   surfaceVariant              → secondary paper (code / table chrome)
 *   onSurfaceVariant            → muted ink (secondary text, markers)
 *   primary                     → warm-amber accent (links, caret, ticks)
 */

package me.nettrash.md.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable

private val LightColors = lightColorScheme(
    primary = AccentLight,
    onPrimary = PaperLight,
    background = PaperLight,
    onBackground = InkLight,
    surface = PaperLight,
    onSurface = InkLight,
    surfaceVariant = PaperSecondaryLight,
    onSurfaceVariant = MutedLight,
    outlineVariant = MutedLight,
)

private val DarkColors = darkColorScheme(
    primary = AccentDark,
    onPrimary = PaperDark,
    background = PaperDark,
    onBackground = InkDark,
    surface = PaperDark,
    onSurface = InkDark,
    surfaceVariant = PaperSecondaryDark,
    onSurfaceVariant = MutedDark,
    outlineVariant = MutedDark,
)

@Composable
fun MdTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        typography = MdTypography,
        content = content,
    )
}
