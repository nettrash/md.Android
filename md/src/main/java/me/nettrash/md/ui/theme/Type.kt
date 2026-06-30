/*
 * Type.kt
 * md (Android)
 *
 * The typewriter type. The iOS / macOS app uses American Typewriter for
 * prose and Courier New for code; Android ships neither, so we map prose
 * to the system slab/serif face and code to the system monospace face —
 * the closest "typed page" equivalents without bundling a font file.
 */

package me.nettrash.md.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/** Prose face — serif, the closest stock Android face to a typewriter slab. */
val ProseFamily = FontFamily.Serif

/** Code face — monospace, where character alignment matters. */
val CodeFamily = FontFamily.Monospace

/** Body text style used across the editor and the rendered preview. */
val ProseBody = TextStyle(
    fontFamily = ProseFamily,
    fontWeight = FontWeight.Normal,
    fontSize = 17.sp,
    lineHeight = 25.sp,
)

val MdTypography = Typography(
    bodyLarge = ProseBody,
    bodyMedium = ProseBody,
)
