/*
 * NoticeBar.kt
 * md (Android)
 *
 * The bar under the app bar that says what happened to the file (#10): it
 * changed on disk under unsaved edits (Reload / Keep Mine), or a save failed
 * and the text was rescued (Save a copy…). It stays until it is answered or
 * the document is replaced — never a toast that is gone before it is read.
 */

package me.nettrash.md.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import me.nettrash.md.DocumentNotice

@Composable
fun NoticeBar(
    notice: DocumentNotice,
    onReload: () -> Unit,
    onKeepMine: () -> Unit,
    onSaveCopy: () -> Unit,
    onDismiss: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.secondaryContainer)
            .semantics { liveRegion = LiveRegionMode.Polite },
    ) {
        Text(
            when (notice) {
                DocumentNotice.ChangedOnDisk -> "This file changed on disk since md opened it."
                is DocumentNotice.SaveFailed -> when {
                    notice.besideOriginal != null -> "Couldn't save — your text is kept, also as “${notice.besideOriginal}”."
                    notice.keptPrivately -> "Couldn't save — your text is kept."
                    else -> "Couldn't save, and no copy could be kept. Save a copy before you leave this document."
                }
            },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSecondaryContainer,
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 10.dp),
        )
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp),
            horizontalArrangement = Arrangement.End,
        ) {
            when (notice) {
                DocumentNotice.ChangedOnDisk -> {
                    TextButton(onClick = onReload) { Text("Reload") }
                    TextButton(onClick = onKeepMine) { Text("Keep Mine") }
                }
                is DocumentNotice.SaveFailed -> {
                    TextButton(onClick = onDismiss) { Text("Dismiss") }
                    TextButton(onClick = onSaveCopy) { Text("Save a copy…") }
                }
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    }
}
