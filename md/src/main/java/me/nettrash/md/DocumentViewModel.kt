/*
 * DocumentViewModel.kt
 * md (Android)
 *
 * Holds the open document: its text, the Storage Access Framework URI it
 * was opened from / saved to (if any), the display name, and whether the
 * buffer has unsaved edits. Android has no `DocumentGroup`, so this is the
 * model the iOS `MarkdownDocument` + the document architecture would
 * otherwise provide — reading and writing go through the ContentResolver
 * against a user-picked SAF URI.
 */

package me.nettrash.md

import android.app.Application
import android.net.Uri
import android.provider.OpenableColumns
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel

class DocumentViewModel(app: Application) : AndroidViewModel(app) {

    /** The raw Markdown source — the single source of truth the editor
     *  binds to and the previewer renders. */
    var text by mutableStateOf("")
        private set

    /** The SAF URI the document is backed by, or null for a brand-new,
     *  never-saved document. */
    var uri: Uri? by mutableStateOf(null)
        private set

    /** Display name shown in the title bar (file name, or "Untitled"). */
    var displayName by mutableStateOf("Untitled")
        private set

    /** True once the buffer differs from what's on disk. */
    var isDirty by mutableStateOf(false)
        private set

    /** False when the document was opened read-only (e.g. handed to us via
     *  ACTION_VIEW without write access); Save then becomes Save As. */
    var canWrite by mutableStateOf(false)
        private set

    private val resolver get() = getApplication<Application>().contentResolver

    /** Editor keystroke — update the buffer and mark it dirty. */
    fun onTextChange(new: String) {
        if (new != text) {
            text = new
            isDirty = true
        }
    }

    /** Start a fresh, never-saved document. */
    fun newDocument() {
        text = ""
        uri = null
        displayName = "Untitled"
        canWrite = false
        isDirty = false
    }

    /** Open shared text (ACTION_SEND) as a new untitled document. */
    fun acceptSharedText(shared: String) {
        text = shared
        uri = null
        displayName = "Untitled"
        canWrite = false
        isDirty = shared.isNotEmpty()
    }

    /** Load a document from a SAF URI. `writable` reflects whether we hold
     *  a read-write grant (our own Open / Create flows) or read-only
     *  (a file handed in via ACTION_VIEW). */
    fun load(target: Uri, writable: Boolean) {
        val decoded = runCatching {
            resolver.openInputStream(target)?.use { it.readBytes() }
        }.getOrNull() ?: return
        text = decodeText(decoded)
        uri = target
        displayName = queryName(target) ?: target.lastPathSegment ?: "Untitled"
        canWrite = writable
        isDirty = false
    }

    /** Adopt a URI from Create Document and write the current text into it. */
    fun saveAs(target: Uri): Boolean {
        if (!write(target)) return false
        uri = target
        displayName = queryName(target) ?: target.lastPathSegment ?: "Untitled"
        canWrite = true
        isDirty = false
        return true
    }

    /** Save to the current writable URI. Returns false if there's nowhere
     *  to write (the caller should fall back to Save As). */
    fun save(): Boolean {
        val target = uri ?: return false
        if (!canWrite) return false
        if (!write(target)) return false
        isDirty = false
        return true
    }

    private fun write(target: Uri): Boolean = runCatching {
        // "wt" = write + truncate, so a shorter edit doesn't leave a tail.
        resolver.openOutputStream(target, "wt")?.use { it.write(text.toByteArray(Charsets.UTF_8)) }
        true
    }.getOrDefault(false)

    /** Decode bytes as text. UTF-8 first (the Markdown convention), then a
     *  couple of common fallbacks, mirroring the iOS strict-decode order. */
    private fun decodeText(data: ByteArray): String {
        for (charset in listOf(Charsets.UTF_8, Charsets.UTF_16, Charsets.ISO_8859_1)) {
            runCatching {
                val decoder = charset.newDecoder()
                return decoder.decode(java.nio.ByteBuffer.wrap(data)).toString()
            }
        }
        return String(data, Charsets.UTF_8)
    }

    private fun queryName(target: Uri): String? = runCatching {
        resolver.query(target, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (idx >= 0) c.getString(idx) else null
            } else null
        }
    }.getOrNull()
}
