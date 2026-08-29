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
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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

    /** Bumped once, as the LAST statement, every time the open document is
     *  *replaced* — New, Open, a book article, shared-in text, an imported
     *  pack. It is the editor's "a different document is now on screen"
     *  signal, and the per-file view-mode rule (see `ui/ViewMode.kt`) is
     *  driven off it.
     *
     *  It is a token rather than the URI because the identity of a document
     *  is not enough: New and shared-in text both have no URI at all, and two
     *  successive examples are two different documents behind the same
     *  (absent) one. Bumping LAST is what lets the observer read [text],
     *  [uri] and [displayName] and be sure it is seeing the new document —
     *  in particular [loadAsync] fills [text] only when its provider read
     *  lands, long after composition.
     *
     *  Saving is deliberately NOT a bump: Save As changes the backing URI of
     *  the document the writer is already in, and re-deciding its mode there
     *  would flip them out of the pane they were typing in. */
    var documentToken by mutableLongStateOf(0L)
        private set

    /** True when the open document came from the writer-mode book navigator.
     *
     *  Book articles are exempt from per-file view-mode memory on all three
     *  ports: a book is stepped through chapter by chapter in one editor, and
     *  re-deciding the mode on each step would drop a writer into Preview on
     *  the chapter they were about to write. The flag is set with the rest of
     *  the document state, before [documentToken] is bumped. */
    var isBookArticle by mutableStateOf(false)
        private set

    private val resolver get() = getApplication<Application>().contentResolver

    private var autosave: Job? = null

    /** Editor keystroke — update the buffer, mark it dirty, and schedule
     *  the autosave. */
    fun onTextChange(new: String) {
        if (new != text) {
            text = new
            isDirty = true
            scheduleAutosave()
        }
    }

    /** Autosave: write the buffer once typing pauses for a moment, so the
     *  file on disk is never more than a beat behind the editor — matching
     *  the iOS / macOS system autosave. Debounced rather than per-keystroke
     *  so fast typing doesn't hammer the documents provider; only fires
     *  when there's a writable target (a brand-new document has nowhere to
     *  write until the user picks a location with Save).
     *
     *  The write itself runs on Dispatchers.IO — a slow or cloud documents
     *  provider must not freeze the editor at every typing pause — against
     *  a snapshot of the buffer taken on Main. Back on Main the buffer is
     *  marked clean only if the write landed *and* nothing changed while it
     *  was in flight, so later edits stay dirty for the next autosave. */
    private fun scheduleAutosave() {
        autosave?.cancel()
        autosave = viewModelScope.launch {
            delay(1_000)
            if (!isDirty || !canWrite) return@launch
            val target = uri ?: return@launch
            val snapshot = text
            val written = withContext(Dispatchers.IO) {
                runCatching {
                    // "wt" truncates, like `write` — no stale tail bytes.
                    resolver.openOutputStream(target, "wt")?.use {
                        it.write(snapshot.toByteArray(Charsets.UTF_8))
                    } != null
                }.getOrDefault(false)
            }
            if (written && text == snapshot) isDirty = false
        }
    }

    /** Start a fresh, never-saved document. */
    fun newDocument() {
        autosave?.cancel()   // a stale save must not chase the old document
        text = ""
        uri = null
        displayName = "Untitled"
        canWrite = false
        isDirty = false
        isBookArticle = false
        documentToken++
    }

    /** Open shared text (ACTION_SEND) as a new untitled document. */
    fun acceptSharedText(shared: String) {
        autosave?.cancel()   // a stale save must not chase the old document
        text = shared
        uri = null
        displayName = "Untitled"
        canWrite = false
        isDirty = shared.isNotEmpty()
        isBookArticle = false
        documentToken++
    }

    /** Load a document from a SAF URI. `writable` reflects whether we hold
     *  a read-write grant (our own Open / Create flows) or read-only
     *  (a file handed in via ACTION_VIEW).
     *
     *  A `.textpack` (a zipped TextBundle) is imported for editing rather than
     *  decoded as text: its `text.md` becomes the buffer, but the pack is NOT
     *  adopted as a writable backing file (see [importTextPack]). Detection is
     *  by name or the zip magic, so a `.md`/`.txt` — which never carries the
     *  magic — takes the unchanged plain-text path. A file that looks like a
     *  pack but can't be read as one is left showing the current document
     *  rather than being decoded (its Latin-1 last resort would render the zip
     *  as mojibake and the autosave would then bake that in). */
    fun load(target: Uri, writable: Boolean) {
        autosave?.cancel()   // a stale save must not chase the old document
        val bytes = runCatching {
            resolver.openInputStream(target)?.use { it.readBytes() }
        }.getOrNull() ?: return
        val name = queryName(target) ?: target.lastPathSegment ?: "Untitled"
        if (TextBundle.looksLikePack(name, bytes)) {
            val imported = TextBundle.textFromPack(bytes) ?: return
            importTextPack(imported, name)
            return
        }
        text = decodeText(bytes)
        uri = target
        displayName = name
        canWrite = writable
        isDirty = false
        isBookArticle = false
        documentToken++
    }

    /** Adopt a `.textpack`'s `text.md` as the editable buffer — read-only.
     *
     *  A pack can carry an `assets/` folder this single-`String` document has no
     *  place for, so it is deliberately NOT adopted as a writable backing file:
     *  saving straight back would drop those assets (the house rule forbids
     *  anything the author kept vanishing). The text opens for editing; because
     *  there is no writable URI, Save falls through to Save As (a fresh `.md`) —
     *  exactly the read-only import the iOS sibling gets from a readable-but-not-
     *  writable UTI. The imported text is unsaved content, so it starts dirty,
     *  the same as shared-in text. */
    private fun importTextPack(imported: String, name: String) {
        text = imported
        uri = null
        displayName = packBaseName(name)
        canWrite = false
        isDirty = imported.isNotEmpty()
        isBookArticle = false
        // [load] returns straight after calling this, so its own bump is never
        // reached: the pack path has to bump for itself.
        documentToken++
    }

    /** A pack's file name without its `.textpack` extension, for the title bar
     *  and the Save As suggestion; blank names fall back to "Untitled". */
    private fun packBaseName(name: String): String {
        val base = if (name.endsWith(".textpack", ignoreCase = true)) name.dropLast(9) else name
        return base.ifBlank { "Untitled" }
    }

    /** [load], but with the provider I/O (the read and the DISPLAY_NAME
     *  query) on Dispatchers.IO — book articles can live on slow or cloud
     *  documents providers, and opening one must not stall the UI. State
     *  lands back on Main; a failed read leaves the current document
     *  untouched, like [load].
     *
     *  [fromBook] marks the open as a step through the writer-mode book, which
     *  exempts it from per-file view-mode memory (see [isBookArticle]). */
    fun loadAsync(target: Uri, writable: Boolean, fromBook: Boolean = false) {
        autosave?.cancel()   // a stale save must not chase the old document
        viewModelScope.launch {
            val loaded = withContext(Dispatchers.IO) {
                val bytes = runCatching {
                    resolver.openInputStream(target)?.use { it.readBytes() }
                }.getOrNull() ?: return@withContext null
                decodeText(bytes) to (queryName(target) ?: target.lastPathSegment ?: "Untitled")
            } ?: return@launch
            text = loaded.first
            uri = target
            displayName = loaded.second
            canWrite = writable
            isDirty = false
            isBookArticle = fromBook
            documentToken++
        }
    }

    /** Adopt a URI from Create Document and write the current text into it.
     *
     *  [isBookArticle] is cleared: whatever the text came from, what is open
     *  now is a standalone file the writer just created outside the book, so
     *  it stops being exempt from per-file view-mode memory. Leaving the flag
     *  set would give the new file no remembered mode AND silence every later
     *  mode change on it (the screen checks the flag before storing) until
     *  some other document was opened. [documentToken] is still deliberately
     *  NOT bumped — the writer stays in the document, and the same pane. */
    fun saveAs(target: Uri): Boolean {
        if (!write(target)) return false
        uri = target
        displayName = queryName(target) ?: target.lastPathSegment ?: "Untitled"
        canWrite = true
        isDirty = false
        isBookArticle = false
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

    /** Decode bytes as text — see [TextCodec] for the (BOM-aware) trial
     *  order, shared in spirit with the iOS/macOS siblings and pure so the
     *  JVM tests can pin it. */
    private fun decodeText(data: ByteArray): String = TextCodec.decode(data)

    private fun queryName(target: Uri): String? = runCatching {
        resolver.query(target, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (idx >= 0) c.getString(idx) else null
            } else null
        }
    }.getOrNull()
}
