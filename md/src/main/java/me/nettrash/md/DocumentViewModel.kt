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
 *
 * It also owns the two things a document architecture would otherwise decide
 * for us:
 *
 *   - what an incoming ACTION_VIEW / ACTION_EDIT / ACTION_SEND hand-off may
 *     be done with ([openIncoming]) — a document granted write opens
 *     writable and autosaves like any other, rather than the read-only
 *     assumption md used to make of every file opened from Files or Drive
 *     (see IncomingGrant.kt);
 *   - whether replacing the open document has to ask first ([DiscardGuard]).
 *     A replacement — New, Open…, an example, an incoming document — is
 *     *offered* to the model, which either hands it straight back to be run
 *     or parks it behind the unsaved-changes dialog. The parked request lives
 *     here rather than in the composition so a rotation while the dialog is
 *     up cannot lose the document that was on its way in.
 */

package me.nettrash.md

import android.app.Application
import android.content.Intent
import android.content.pm.PackageManager
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

/**
 * Something that would replace the open document — or, for [Exit], walk away
 * from it. Every one of them is offered to [DocumentViewModel
 * .requestReplacement] first, so a buffer with nowhere to autosave to gets
 * the unsaved-changes dialog instead of being overwritten in silence.
 *
 * A description rather than a lambda on purpose: a parked request outlives
 * the composition that offered it (a rotation while the dialog is up), and a
 * lambda from that composition would carry a disposed
 * `ActivityResultLauncher` with it. `EditorScreen.runReplacement` is the one
 * place that turns a description back into an action.
 */
sealed interface Replacement {

    /** File > New — a fresh untitled document. */
    data object New : Replacement

    /** Open… — the SAF picker (which replaces the document when it returns). */
    data object Open : Replacement

    /** Edit… — the same picker, started at the read-only document already on
     *  screen, which re-opens it with the write access its sender withheld.
     *  A replacement like any other: what comes back is the file as it is on
     *  disk, so a buffer that has been typed into has to be answered for
     *  first. The URI is carried rather than read back at the end, so the
     *  request means the document it was raised for. */
    data class Reopen(val uri: Uri) : Replacement

    /** A bundled example document, by asset file name. */
    data class Example(val fileName: String) : Replacement

    /** Example Book… — the folder picker that copies the bundled book. */
    data object ExampleBook : Replacement

    /** A document handed in by the system: ACTION_VIEW / ACTION_EDIT over a
     *  URI, or ACTION_SEND text. The whole intent is carried because the
     *  grant flags on it are what decide whether the document opens writable
     *  (see [DocumentViewModel.openIncoming]). */
    data class Incoming(val intent: Intent) : Replacement

    /** Leaving with Back, which replaces the document with nothing at all. */
    data object Exit : Replacement
}

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

    /** True for a document that is backed by a file we may not write — one
     *  handed in with a read-only grant. The editor badges it "Read Only" and
     *  offers to re-open it through the picker; it is NOT the same as having
     *  nowhere to save (an untitled document, shared-in text, an imported
     *  pack), which is a document with no file at all. */
    val isReadOnly: Boolean
        get() = uri != null && !canWrite

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

    private var previewRetryToken = -1L
    private var previewRetryPolicy = me.nettrash.md.ui.PreviewRetryPolicy()

    /** The preview's render-process retry policy for the open document. It
     *  lives here, not in the preview pane, because the pane leaves the
     *  composition whenever the writer switches to Edit: a policy kept there
     *  forgot a give-up on every mode switch, and each return to Preview
     *  cost two more renderer deaths for a document that had not changed
     *  (iOS keeps the give-up). A new document starts a new policy. */
    internal fun previewRetry(): me.nettrash.md.ui.PreviewRetryPolicy {
        if (previewRetryToken != documentToken) {
            previewRetryToken = documentToken
            previewRetryPolicy = me.nettrash.md.ui.PreviewRetryPolicy()
        }
        return previewRetryPolicy
    }

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

    /** The editor's buffer changed — relayed from the text field's own
     *  `TextFieldState` by `ui/SmartTypingTransformation.kt`
     *  (`rememberEditorBuffer`), one call per edit: update the buffer, mark it
     *  dirty, and schedule the autosave. A bundled example or shared-in text
     *  does not come through here: those *replace* the document (see
     *  [acceptSharedText]) and bump [documentToken]. */
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

    /**
     * Land whatever the debounced autosave still owes, before the document it
     * belongs to is replaced.
     *
     * Every path below opens by cancelling the pending autosave, which is
     * right — a save in flight must not chase the old document into the new
     * one — but cancelling alone threw away up to a second of typing. The
     * discard guard lets a dirty writable document through *because* its
     * edits are a beat from disk ([needsAutosaveFlush]), so the beat has to
     * be paid before the cancel or that promise is not kept: New is one
     * keystroke, an example is one tap, and a document handed over by another
     * app arrives whenever it arrives.
     *
     * The same main-thread [write] the ON_STOP flush does, over the same
     * one-document-sized string. A document with nowhere to autosave to is
     * not written here — it is the one the dialog asks about.
     */
    private fun flushPendingAutosave() {
        if (!needsAutosaveFlush(isDirty = isDirty, hasUri = uri != null, canWrite = canWrite)) return
        val target = uri ?: return
        if (write(target)) isDirty = false
    }

    /** Start a fresh, never-saved document. */
    fun newDocument() {
        flushPendingAutosave()   // the beat the autosave still owes lands first
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
        flushPendingAutosave()   // the beat the autosave still owes lands first
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
        flushPendingAutosave()   // the beat the autosave still owes lands first
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
        flushPendingAutosave()   // the beat the autosave still owes lands first
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

    // ---- Replacing the open document ------------------------------------

    /** The parked replacement and the phase it is waiting in — see
     *  DiscardGuard.kt. It lives in the model, not the composition, so the
     *  document on its way in survives a rotation while the dialog is up. */
    private val guard = DiscardGuard<Replacement>()

    /** Whether the unsaved-changes dialog should be on screen. The guard
     *  itself is plain Kotlin (so the JVM tests reach it); this is the
     *  observable mirror the editor recomposes from, refreshed after every
     *  transition by [publishGuard]. */
    var isPromptingDiscard by mutableStateOf(false)
        private set

    /** Whether replacing this document has to ask first: unsaved edits, and
     *  nowhere to autosave them (untitled, shared-in, an imported pack, or a
     *  read-only hand-off). A dirty document with a writable file behind it
     *  is already a beat away from disk and never asks. */
    val needsDiscardPrompt: Boolean
        get() = needsDiscardPrompt(isDirty = isDirty, hasUri = uri != null, canWrite = canWrite)

    /** Offer [request]. Returns it when the caller is to run it now, or null
     *  when it has been parked behind the dialog. */
    fun requestReplacement(request: Replacement): Replacement? =
        guard.submit(request, needsDiscardPrompt).also { publishGuard() }

    /** The reader answered the dialog. Returns the parked replacement when it
     *  is to run now (Discard), or null — Cancel drops it and Save keeps it
     *  until [discardSaveFinished]. */
    internal fun resolveDiscard(choice: DiscardChoice): Replacement? =
        guard.choose(choice).also { publishGuard() }

    /** The Save the reader asked for landed, or the Save As picker was
     *  dismissed. Returns the parked replacement to run now, or null.
     *  Harmless when nothing is waiting — an ordinary Save As calls it too. */
    fun discardSaveFinished(saved: Boolean): Replacement? =
        guard.saveFinished(saved).also { publishGuard() }

    private fun publishGuard() {
        isPromptingDiscard = guard.isAsking
    }

    /** Take an intent handed to the Activity: open it now, or park it behind
     *  the unsaved-changes dialog. Intents that carry no document (the
     *  launcher's own ACTION_MAIN, an ACTION_SEND with no text) are ignored
     *  outright — they must never raise a dialog about a document nothing is
     *  about to replace. */
    fun handOver(intent: Intent) {
        if (!carriesDocument(intent)) return
        if (requestReplacement(Replacement.Incoming(intent)) != null) openIncoming(intent)
    }

    /** Open the document [intent] carries. ACTION_VIEW and ACTION_EDIT are
     *  the same hand-off as far as md is concerned — what decides whether the
     *  file may be written is the grant on the intent, never the action (a
     *  file manager sends VIEW with a write grant, and both actions are
     *  claimed by the same filters). ACTION_SEND text opens untitled.
     *
     *  A writable hand-off also asks for a persistable grant, so the same
     *  document is still writable the next time it arrives — wrapped, because
     *  plenty of providers refuse, and a refusal only costs us the grant we
     *  already have for this launch. */
    fun openIncoming(intent: Intent) {
        when (intent.action) {
            Intent.ACTION_VIEW, Intent.ACTION_EDIT -> {
                val target = intent.data ?: return
                val writable = IncomingGrant.isWritable(
                    intentFlags = intent.flags,
                    holdsWriteGrant = holdsWriteGrant(target),
                )
                if (writable) takePersistableGrant(target, intent.flags)
                load(target, writable = writable)
            }
            Intent.ACTION_SEND -> intent.getStringExtra(Intent.EXTRA_TEXT)?.let { acceptSharedText(it) }
        }
    }

    /** Whether [intent] actually hands us a document to open. */
    private fun carriesDocument(intent: Intent): Boolean = when (intent.action) {
        Intent.ACTION_VIEW, Intent.ACTION_EDIT -> intent.data != null
        Intent.ACTION_SEND -> intent.getStringExtra(Intent.EXTRA_TEXT) != null
        else -> false
    }

    /** Whether this process already holds a write grant for [target] — a
     *  grant taken persistably on an earlier launch is not re-stated in the
     *  flags of every later intent. */
    private fun holdsWriteGrant(target: Uri): Boolean = runCatching {
        getApplication<Application>().checkCallingOrSelfUriPermission(
            target,
            Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
        ) == PackageManager.PERMISSION_GRANTED
    }.getOrDefault(false)

    private fun takePersistableGrant(target: Uri, flags: Int) {
        val modes = IncomingGrant.persistableModes(flags)
        if (modes == 0) return
        runCatching { resolver.takePersistableUriPermission(target, modes) }
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
