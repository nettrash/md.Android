/*
 * MenuRows.kt
 * md (Android)
 *
 * The rows the overflow (⋮) menu is built from.
 *
 * The menu used to be one flat list of some twenty-three rows — seven of them
 * Export rows — and it opened three more menus from the same button that
 * replaced it with no way back. It is now a short main page of groups, each
 * of which the menu *drills into*: the same popup swaps its rows for the
 * group's, headed by a Back row, the way Chrome's and Files' menus do on
 * Android. Material 3 has no cascading submenu for a phone-sized popup, and a
 * second popup anchored to the same button is what made the old layout feel
 * like it was losing its place.
 */

package me.nettrash.md.ui

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp

/** The pages of the overflow menu: the main one, and the groups it drills into. */
internal enum class MenuPage { MAIN, EXAMPLES, SHARE, EXPORT, SVG, PAGE_SIZE, BOOK, TYPING }

/** An action row. [icon] is optional so a row can sit under the one above it
 *  (Save As… under Save) without a second, near-identical glyph. */
@Composable
internal fun MenuRow(
    text: String,
    icon: ImageVector? = null,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    DropdownMenuItem(
        text = { Text(text) },
        leadingIcon = icon?.let { { Icon(it, contentDescription = null) } } ?: { Spacer(Modifier.width(24.dp)) },
        enabled = enabled,
        onClick = onClick,
    )
}

/** A row that opens a group: a chevron, and — for a setting — its current
 *  value beside it ("PDF Page Size   A4 ›"), so the choice is readable
 *  without going in. */
@Composable
internal fun SubmenuRow(
    text: String,
    icon: ImageVector? = null,
    value: String? = null,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    DropdownMenuItem(
        text = { Text(text) },
        leadingIcon = icon?.let { { Icon(it, contentDescription = null) } } ?: { Spacer(Modifier.width(24.dp)) },
        trailingIcon = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (value != null) {
                    Text(
                        value,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.width(4.dp))
                }
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null)
            }
        },
        enabled = enabled,
        onClick = onClick,
    )
}

/** The head of a group's page: its name, and the way back to the page above. */
@Composable
internal fun BackRow(title: String, onBack: () -> Unit) {
    DropdownMenuItem(
        text = { Text(title, style = MaterialTheme.typography.titleSmall) },
        leadingIcon = { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") },
        onClick = onBack,
    )
    HorizontalDivider()
}
