/*
 * FindBar.kt
 * md (Android)
 *
 * Find and Replace over the editor pane: the bar under the app bar, and the
 * four things its buttons do to the buffer.
 *
 * The matching rule is `TextSearch.kt` and nothing else — ordinal,
 * case-insensitive, wrapping, no regular expressions — the same one rule md
 * uses on Windows for both the search and the replace. Everything decidable
 * lives there, unit-tested on the JVM; this file is the surface and the four
 * edits:
 *
 *   - **Next** / **Previous** select the hit at or after (before) the
 *     selection and scroll it on screen;
 *   - **Replace** replaces the hit the selection is standing on and moves to
 *     the next one — and is a plain Find Next when it is standing on nothing,
 *     which is what makes "Replace, Replace, Replace…" walk the document;
 *   - **Replace All** puts every hit in as ONE edit, so one Undo takes the
 *     lot back (`TextSearch.ReplaceAllPlan.apply` is that single edit).
 *
 * Every edit goes through `EditorBuffer.applyExternalEdit`, so the undo
 * history, the smart-typing override (§3.4) and the dirty flag all see a
 * replace the way they see a paste. Find inside the *preview* is deliberately
 * out of scope: the preview is a WebView showing rendered HTML, and a hit in
 * it has no offset in the Markdown source to take the writer to.
 */

package me.nettrash.md.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp

/**
 * The find bar. [query] and [replacement] are held by the screen (and so
 * survive a rotation); [focusToken] is bumped by the screen every time Find
 * is asked for, so Ctrl+F with the bar already up puts the caret back in the
 * query box instead of doing nothing.
 */
@Composable
internal fun FindBar(
    editor: EditorBuffer,
    query: String,
    onQueryChange: (String) -> Unit,
    replacement: String,
    onReplacementChange: (String) -> Unit,
    focusToken: Int,
    onClose: () -> Unit,
) {
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(focusToken) {
        // A request before the node is attached throws rather than waiting;
        // the bar has just been composed, so it is not worth an assertion.
        runCatching { focusRequester.requestFocus() }
    }

    val text = editor.state.text.toString()
    val hasMatch = remember(text, query) {
        query.isNotEmpty() && TextSearch.next(text, query, 0) != null
    }

    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp)
            .padding(bottom = 8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = query,
                onValueChange = onQueryChange,
                singleLine = true,
                label = { Text("Find") },
                isError = query.isNotEmpty() && !hasMatch,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { findNext(editor, query) }),
                modifier = Modifier
                    .weight(1f)
                    .focusRequester(focusRequester),
            )
            IconButton(onClick = { findPrevious(editor, query) }, enabled = hasMatch) {
                Icon(Icons.Filled.KeyboardArrowUp, contentDescription = "Previous match")
            }
            IconButton(onClick = { findNext(editor, query) }, enabled = hasMatch) {
                Icon(Icons.Filled.KeyboardArrowDown, contentDescription = "Next match")
            }
            IconButton(onClick = onClose) {
                Icon(Icons.Filled.Close, contentDescription = "Close find bar")
            }
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            modifier = Modifier.padding(top = 4.dp),
        ) {
            OutlinedTextField(
                value = replacement,
                onValueChange = onReplacementChange,
                singleLine = true,
                label = { Text("Replace") },
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = { replaceOne(editor, query, replacement) }, enabled = hasMatch) {
                Text("Replace")
            }
            TextButton(onClick = { replaceAll(editor, query, replacement) }, enabled = hasMatch) {
                Text("All")
            }
        }
        if (query.isNotEmpty() && !hasMatch) {
            Text(
                "No matches",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 4.dp, top = 2.dp),
            )
        }
    }
}

/** Select the hit at or after the selection, wrapping to the top. */
private fun findNext(editor: EditorBuffer, query: String) {
    val text = editor.state.text.toString()
    val match = TextSearch.next(text, query, editor.state.selection.max) ?: return
    editor.select(match.index, match.index + match.length)
}

/** Select the hit before the selection, wrapping to the bottom. */
private fun findPrevious(editor: EditorBuffer, query: String) {
    val text = editor.state.text.toString()
    val match = TextSearch.previous(text, query, editor.state.selection.min) ?: return
    editor.select(match.index, match.index + match.length)
}

/**
 * One press of Replace: replace the hit the selection is standing on (if it
 * is standing on one), then select the next one — read out of the text the
 * edit leaves behind, from past the replacement, so a replacement that
 * contains the query is not found again by the step that made it.
 */
private fun replaceOne(editor: EditorBuffer, query: String, replacement: String) {
    val selection = editor.state.selection
    val step = TextSearch.replace(
        editor.state.text.toString(), query, replacement, selection.min, selection.length,
    )
    step.apply?.let { edit ->
        editor.applyExternalEdit(
            edit.start, edit.start + edit.length, edit.text,
            caret = edit.start + edit.text.length,
        )
    }
    val after = editor.state.text.toString()
    val match = TextSearch.next(after, query, step.searchFrom) ?: return
    editor.select(match.index, match.index + match.length)
}

/**
 * Every hit replaced in one pass and put in as ONE edit, so the field's undo
 * takes the lot back in one step. The caret is left after the last
 * replacement — where the writer would be if they had walked the document
 * with Replace — and scrolled on screen.
 */
private fun replaceAll(editor: EditorBuffer, query: String, replacement: String) {
    val plan = TextSearch.replaceAll(editor.state.text.toString(), query, replacement)
    if (plan.count == 0) return
    val edit = plan.apply
    editor.applyExternalEdit(
        edit.start, edit.start + edit.length, edit.text,
        caret = edit.start + edit.text.length,
    )
}
