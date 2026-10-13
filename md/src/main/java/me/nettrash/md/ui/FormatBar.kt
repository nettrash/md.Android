/*
 * FormatBar.kt
 * md (Android)
 *
 * The phone's formatting bar (#13): a row of buttons above the keyboard, in
 * the family's order — Bold, Italic, Code, Link, Heading (H1 → H2 → H3 →
 * none), Bullet List, Task List, Quote, Outdent, Indent. It scrolls sideways
 * when a narrow phone cannot show them all. A tap edits the selection the
 * editor still holds: the buttons never take focus, so the keyboard stays up
 * and the caret stays where it was.
 */

package me.nettrash.md.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.FormatIndentDecrease
import androidx.compose.material.icons.automirrored.filled.FormatIndentIncrease
import androidx.compose.material.icons.automirrored.filled.FormatListBulleted
import androidx.compose.material.icons.filled.Checklist
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.FormatBold
import androidx.compose.material.icons.filled.FormatItalic
import androidx.compose.material.icons.filled.FormatQuote
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Title
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.graphics.vector.ImageVector
import me.nettrash.md.markdown.FormatOp

/** The bar's buttons: what each does, and what a screen reader calls it. */
internal val FORMAT_BAR: List<Triple<FormatOp, ImageVector, String>> = listOf(
    Triple(FormatOp.BOLD, Icons.Filled.FormatBold, "Bold"),
    Triple(FormatOp.ITALIC, Icons.Filled.FormatItalic, "Italic"),
    Triple(FormatOp.CODE, Icons.Filled.Code, "Code"),
    Triple(FormatOp.LINK, Icons.Filled.Link, "Link"),
    Triple(FormatOp.HEADING_CYCLE, Icons.Filled.Title, "Heading"),
    Triple(FormatOp.BULLET_LIST, Icons.AutoMirrored.Filled.FormatListBulleted, "Bullet List"),
    Triple(FormatOp.TASK_LIST, Icons.Filled.Checklist, "Task List"),
    Triple(FormatOp.QUOTE, Icons.Filled.FormatQuote, "Quote"),
    Triple(FormatOp.OUTDENT, Icons.AutoMirrored.Filled.FormatIndentDecrease, "Outdent"),
    Triple(FormatOp.INDENT, Icons.AutoMirrored.Filled.FormatIndentIncrease, "Indent"),
)

@Composable
internal fun FormatBar(onFormat: (FormatOp) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .horizontalScroll(rememberScrollState()),
    ) {
        for ((op, icon, label) in FORMAT_BAR) {
            IconButton(
                onClick = { onFormat(op) },
                // Never take focus from the editor: the keyboard would close.
                modifier = Modifier.focusProperties { canFocus = false },
            ) {
                Icon(icon, contentDescription = label)
            }
        }
    }
}
