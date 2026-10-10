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

    private companion object {
        /** Under `filesDir`: where a failed save's text is kept. */
        const val RESCUE_DIRECTORY = "rescued"
    }

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

    /** How the open file is written back: the encoding and line endings it
     *  was read with (see TextCodec.kt). A new document, shared-in text and an
     *  imported pack write UTF-8 with LF. */
    private var dressing = TextDressing.DEFAULT

    private var autosave: Job? = null

    /** The read in flight, if any: a second open cancels the first, so the
     *  document that lands is the one asked for last. */
    private var loading: Job? = null

    // ---- The file on disk ----------------------------------------------

    /** Something the editor has to say about the file, shown as a bar under
     *  the app bar until it is answered or the document is replaced. */
    var notice: DocumentNotice? by mutableStateOf(null)
        private set

    /** The file as md last read or wrote it (see DiskStamp.kt). */
    private var diskStamp: DiskStamp? = null

    /** The app-private copy and the sibling beside the original that the
     *  current run of failed saves writes to — one of each per run, rewritten
     *  on every later failure rather than a new file per autosave. */
    private var rescueFile: java.io.File? = null
    private var rescueSibling: Uri? = null

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
     *  was in flight, so later edits stay dirty for the next autosave.
     *
     *  Nothing is written while the file is known to have changed on disk:
     *  the writer has to choose Reload or Keep Mine first. */
    private fun scheduleAutosave() {
        autosave?.cancel()
        autosave = viewModelScope.launch {
            delay(1_000)
            if (!isDirty || !canWrite || notice == DocumentNotice.ChangedOnDisk) return@launch
            val target = uri ?: return@launch
            val snapshot = text
            val dressed = dressing
            val expected = diskStamp
            val result = withContext(Dispatchers.IO) {
                writeTo(target, snapshot, dressed, expected, force = false).let { written ->
                    if (written.outcome == WriteOutcome.FAILED) written.copy(rescued = rescue(snapshot, dressed)) else written
                }
            }
            // The document may have been replaced while the write was out.
            if (uri != target) return@launch
            land(result, dressed)
            if (result.outcome == WriteOutcome.WRITTEN && text == snapshot) isDirty = false
        }
    }

    /**
     * Land whatever the debounced autosave still owes, before the document it
     * belongs to is replaced. Returns false when it could not — the file
     * changed on disk under the edits, or the write failed — and the caller
     * must then NOT replace the document: the edits are kept (a failed write
     * is rescued) and the bar says why, and the replacement can be asked for
     * again once the writer has answered it.
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
     * The same main-thread write the ON_STOP flush does, over the same
     * one-document-sized string. A document with nowhere to autosave to is
     * not written here — it is the one the dialog asks about.
     */
    private fun flushPendingAutosave(): Boolean {
        if (!needsAutosaveFlush(isDirty = isDirty, hasUri = uri != null, canWrite = canWrite)) return true
        val target = uri ?: return true
        if (notice == DocumentNotice.ChangedOnDisk) return false
        val dressed = dressing
        var result = writeTo(target, text, dressed, diskStamp, force = false)
        if (result.outcome == WriteOutcome.FAILED) result = result.copy(rescued = rescue(text, dressed))
        land(result, dressed)
        if (result.outcome != WriteOutcome.WRITTEN) {
            autosave?.cancel()
            return false
        }
        isDirty = false
        return true
    }

    /** Everything about the previous file is forgotten when a document is
     *  replaced: its bar, its stamp, its rescue copies. */
    private fun forgetFile(stamp: DiskStamp? = null) {
        notice = null
        diskStamp = stamp
        rescueFile = null
        rescueSibling = null
    }

    /** Start a fresh, never-saved document. */
    fun newDocument() {
        if (!flushPendingAutosave()) return   // the beat the autosave still owes lands first
        autosave?.cancel()   // a stale save must not chase the old document
        loading?.cancel()
        forgetFile()
        text = ""
        dressing = TextDressing.DEFAULT
        uri = null
        displayName = "Untitled"
        canWrite = false
        isDirty = false
        isBookArticle = false
        documentToken++
    }

    /** Open shared text (ACTION_SEND) as a new untitled document. */
    fun acceptSharedText(shared: String) {
        if (!flushPendingAutosave()) return   // the beat the autosave still owes lands first
        autosave?.cancel()   // a stale save must not chase the old document
        loading?.cancel()
        forgetFile()
        text = LineEndings.normalize(shared)
        dressing = TextDressing.DEFAULT
        uri = null
        displayName = "Untitled"
        canWrite = false
        isDirty = text.isNotEmpty()
        isBookArticle = false
        documentToken++
    }

    /** What a read of a document's URI found. */
    private sealed interface Read {
        class Text(val text: String, val dressing: TextDressing, val name: String, val stamp: DiskStamp?) : Read
        class Pack(val text: String, val name: String) : Read
    }

    /** Load a document from a SAF URI. `writable` reflects whether we hold
     *  a read-write grant (our own Open / Create flows) or read-only
     *  (a file handed in via ACTION_VIEW).
     *
     *  The provider I/O — the read, the DISPLAY_NAME query, the disk stamp —
     *  runs on Dispatchers.IO: a large file on a slow or cloud provider must
     *  not freeze the app (#10). State lands back on Main, [documentToken]
     *  last; a failed read leaves the current document untouched.
     *
     *  A `.textpack` (a zipped TextBundle) is imported for editing rather than
     *  decoded as text: its `text.md` becomes the buffer, but the pack is NOT
     *  adopted as a writable backing file (see [importTextPack]). Detection is
     *  by name or the zip magic, so a `.md`/`.txt` — which never carries the
     *  magic — takes the unchanged plain-text path. A file that looks like a
     *  pack but can't be read as one is left showing the current document
     *  rather than being decoded (its Latin-1 last resort would render the zip
     *  as mojibake and the autosave would then bake that in). */
    fun load(target: Uri, writable: Boolean) = open(target, writable, fromBook = false)

    /** [load], for a step through the writer-mode book: [fromBook] exempts
     *  the open from per-file view-mode memory (see [isBookArticle]). */
    fun loadAsync(target: Uri, writable: Boolean, fromBook: Boolean = false) = open(target, writable, fromBook)

    private fun open(target: Uri, writable: Boolean, fromBook: Boolean) {
        if (!flushPendingAutosave()) return   // the beat the autosave still owes lands first
        autosave?.cancel()   // a stale save must not chase the old document
        loading?.cancel()
        loading = viewModelScope.launch {
            val read = withContext(Dispatchers.IO) { read(target) } ?: return@launch
            when (read) {
                is Read.Pack -> importTextPack(read.text, read.name)
                is Read.Text -> {
                    forgetFile(read.stamp)
                    text = read.text
                    dressing = read.dressing
                    uri = target
                    displayName = read.name
                    canWrite = writable
                    isDirty = false
                    isBookArticle = fromBook
                    documentToken++
                }
            }
        }
    }

    /** Read [target] off the main thread: a pack's text, or the file's text
     *  with its dressing, name and stamp; null when it cannot be read. */
    private fun read(target: Uri): Read? {
        val bytes = runCatching {
            resolver.openInputStream(target)?.use { it.readBytes() }
        }.getOrNull() ?: return null
        val name = queryName(target) ?: target.lastPathSegment ?: "Untitled"
        if (TextBundle.looksLikePack(name, bytes)) {
            return TextBundle.textFromPack(bytes)?.let { Read.Pack(it, name) }
        }
        val (decoded, dressed) = TextCodec.undress(bytes)
        return Read.Text(decoded, dressed, name, stampOf(target))
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
        forgetFile()
        text = LineEndings.normalize(imported)
        dressing = TextDressing.DEFAULT
        uri = null
        displayName = packBaseName(name)
        canWrite = false
        isDirty = text.isNotEmpty()
        isBookArticle = false
        documentToken++
    }

    /** A pack's file name without its `.textpack` extension, for the title bar
     *  and the Save As suggestion; blank names fall back to "Untitled". */
    private fun packBaseName(name: String): String {
        val base = if (name.endsWith(".textpack", ignoreCase = true)) name.dropLast(9) else name
        return base.ifBlank { "Untitled" }
    }

    /** Adopt a URI from Create Document and write the current text into it.
     *
     *  [isBookArticle] is cleared: whatever the text came from, what is open
     *  now is a standalone file the writer just created outside the book, so
     *  it stops being exempt from per-file view-mode memory. Leaving the flag
     *  set would give the new file no remembered mode AND silence every later
     *  mode change on it (the screen checks the flag before storing) until
     *  some other document was opened. [documentToken] is still deliberately
     *  NOT bumped — the writer stays in the document, and the same pane.
     *
     *  This is also where "Save a copy…" on the rescue bar lands: the writer
     *  carries on in the file that could be written. */
    fun saveAs(target: Uri): Boolean {
        val dressed = dressing
        val result = writeTo(target, text, dressed, expected = null, force = true)
        if (result.outcome != WriteOutcome.WRITTEN) return false
        forgetFile(result.stamp)
        rememberEncoding(dressed, result.encoding!!)
        uri = target
        displayName = queryName(target) ?: target.lastPathSegment ?: "Untitled"
        canWrite = true
        isDirty = false
        isBookArticle = false
        return true
    }

    /** Save to the current writable URI. Returns false if there's nowhere
     *  to write (the caller should fall back to Save As) — and also when the
     *  file changed on disk or the write failed, in which case [notice] says
     *  so and [hasWritableFile] tells the caller not to offer Save As. */
    fun save(): Boolean {
        val target = uri ?: return false
        if (!canWrite) return false
        if (notice == DocumentNotice.ChangedOnDisk) return false
        val dressed = dressing
        var result = writeTo(target, text, dressed, diskStamp, force = false)
        if (result.outcome == WriteOutcome.FAILED) result = result.copy(rescued = rescue(text, dressed))
        land(result, dressed)
        if (result.outcome != WriteOutcome.WRITTEN) return false
        isDirty = false
        return true
    }

    /** True when the document has a file md may write — a failed [save] on it
     *  is a save that failed, not one that needs Save As. */
    val hasWritableFile: Boolean
        get() = uri != null && canWrite

    /** "Keep Mine" on the changed-on-disk bar: write the buffer over what is
     *  on disk now, the stamp check skipped — that is the point. */
    fun keepMine() {
        val target = uri ?: return
        val dressed = dressing
        var result = writeTo(target, text, dressed, expected = null, force = true)
        if (result.outcome == WriteOutcome.FAILED) result = result.copy(rescued = rescue(text, dressed))
        if (result.outcome == WriteOutcome.WRITTEN) notice = null
        land(result, dressed)
        if (result.outcome == WriteOutcome.WRITTEN) isDirty = false
    }

    /** "Reload" on the changed-on-disk bar: the file as it is now replaces the
     *  buffer, the edits given up — the writer asked for exactly that. */
    fun reloadFromDisk() {
        val target = uri ?: return
        isDirty = false   // nothing to flush: the edits are being let go
        autosave?.cancel()
        open(target, canWrite, isBookArticle)
    }

    /** The rescue bar was dismissed. The copies stay where they are. */
    fun dismissNotice() {
        if (notice is DocumentNotice.SaveFailed) notice = null
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
        // A shared file md does not open is refused before the guard: the
        // writer must not answer the unsaved-changes dialog for a photo.
        if (refusesSharedFile(intent)) {
            android.widget.Toast.makeText(
                getApplication(),
                "md opens Markdown, text, PlantUML, Graphviz and TextPack files.",
                android.widget.Toast.LENGTH_LONG,
            ).show()
            return
        }
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
            Intent.ACTION_SEND -> {
                val stream = sharedStream(intent)
                if (stream == null) {
                    intent.getStringExtra(Intent.EXTRA_TEXT)?.let { acceptSharedText(it) }
                    return
                }
                // A shared file is the same hand-off as Open with md: the
                // grant decides whether it opens writable (a share usually
                // grants read only), and load() imports a pack or reads text.
                if (refusesSharedFile(intent)) return
                val writable = IncomingGrant.isWritable(
                    intentFlags = intent.flags,
                    holdsWriteGrant = holdsWriteGrant(stream),
                )
                if (writable) takePersistableGrant(stream, intent.flags)
                load(stream, writable = writable)
            }
        }
    }

    /** True for a share whose file md does not open (see
     *  [DocumentTypes.acceptsSharedFile]); false for anything else. */
    private fun refusesSharedFile(intent: Intent): Boolean {
        if (intent.action != Intent.ACTION_SEND) return false
        val stream = sharedStream(intent) ?: return false
        val type = intent.type ?: runCatching { resolver.getType(stream) }.getOrNull()
        return !DocumentTypes.acceptsSharedFile(type, queryName(stream) ?: stream.lastPathSegment)
    }

    /** The file an ACTION_SEND carries — EXTRA_STREAM, or the first item of
     *  its clip, which is where the grant travels — or null for a text share. */
    private fun sharedStream(intent: Intent): Uri? =
        androidx.core.content.IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
            ?: intent.clipData?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.uri

    /** Whether [intent] actually hands us a document to open. */
    private fun carriesDocument(intent: Intent): Boolean = when (intent.action) {
        Intent.ACTION_VIEW, Intent.ACTION_EDIT -> intent.data != null
        Intent.ACTION_SEND -> sharedStream(intent) != null || intent.getStringExtra(Intent.EXTRA_TEXT) != null
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

    private enum class WriteOutcome { WRITTEN, CONFLICT, FAILED }

    /** What a write did: the encoding it used and the stamp it left when it
     *  landed; where the text was rescued to when it failed. */
    private data class WriteResult(
        val outcome: WriteOutcome,
        val encoding: TextEncoding? = null,
        val stamp: DiskStamp? = null,
        val rescued: String? = null,
    )

    /** Write [snapshot] to [target] in [dressed] — unless the file moved from
     *  [expected] since md last saw it ([force] skips that check). Safe to call
     *  on any thread: it touches no Compose state. */
    private fun writeTo(target: Uri, snapshot: String, dressed: TextDressing, expected: DiskStamp?, force: Boolean): WriteResult {
        if (!force && DiskStamp.changed(expected, stampOf(target))) return WriteResult(WriteOutcome.CONFLICT)
        return runCatching {
            val encoded = TextCodec.dress(snapshot, dressed)
            // "wt" = write + truncate, so a shorter edit doesn't leave a tail.
            resolver.openOutputStream(target, "wt")?.use { it.write(encoded.data) }
                ?: return WriteResult(WriteOutcome.FAILED)
            WriteResult(WriteOutcome.WRITTEN, encoded.encoding, stampOf(target))
        }.getOrElse { WriteResult(WriteOutcome.FAILED) }
    }

    /** Apply a write's result to the model, on Main. */
    private fun land(result: WriteResult, dressed: TextDressing) {
        when (result.outcome) {
            WriteOutcome.WRITTEN -> {
                rememberEncoding(dressed, result.encoding!!)
                diskStamp = result.stamp
                // A save that lands ends a run of failures; the copies stay.
                if (notice is DocumentNotice.SaveFailed) notice = null
                rescueFile = null
                rescueSibling = null
            }
            WriteOutcome.CONFLICT -> notice = DocumentNotice.ChangedOnDisk
            WriteOutcome.FAILED -> notice = DocumentNotice.SaveFailed(besideOriginal = result.rescued)
        }
    }

    /** A save that had to upgrade the file to UTF-8 is remembered, so the next
     *  one does not retry the encoding that cannot hold the text. Only when
     *  the dressing is still the one the save used — a document opened in the
     *  meantime has its own. */
    private fun rememberEncoding(used: TextDressing, encoding: TextEncoding) {
        if (dressing === used && encoding != used.encoding) dressing = used.copy(encoding = encoding)
    }

    /** The file's stamp now, or null when the provider will not say. */
    private fun stampOf(target: Uri): DiskStamp? = runCatching {
        if (target.scheme == "file") {
            val file = java.io.File(checkNotNull(target.path))
            if (!file.exists()) null else DiskStamp.of(file.lastModified(), file.length())
        } else {
            resolver.query(target, null, null, null, null)?.use { c ->
                if (!c.moveToFirst()) return@use null
                val m = c.getColumnIndex(android.provider.DocumentsContract.Document.COLUMN_LAST_MODIFIED)
                val s = c.getColumnIndex(OpenableColumns.SIZE)
                DiskStamp.of(
                    if (m >= 0 && !c.isNull(m)) c.getLong(m) else null,
                    if (s >= 0 && !c.isNull(s)) c.getLong(s) else null,
                )
            }
        }
    }.getOrNull()

    /**
     * Keep [snapshot] somewhere after a save failed (decided 2026-10-10):
     * always in md's own storage, at once — `files/rescued/` — and, when the
     * document sits inside a folder md holds a grant on (a book article),
     * also beside the original as "name (rescued).ext". Returns that sibling's
     * name, or null when there is none. One copy of each per run of failures,
     * rewritten as later saves fail too. Safe on any thread.
     */
    private fun rescue(snapshot: String, dressed: TextDressing): String? {
        val bytes = TextCodec.dress(snapshot, dressed).data
        val dir = java.io.File(getApplication<Application>().filesDir, RESCUE_DIRECTORY)
        runCatching {
            dir.mkdirs()
            val file = rescueFile ?: java.io.File(dir, Rescue.fileName(displayName, System.currentTimeMillis())).also { rescueFile = it }
            file.writeBytes(bytes)
            Rescue.prune(dir)
        }
        val sibling = rescueSibling ?: createRescueSibling()?.also { rescueSibling = it } ?: return null
        val written = runCatching {
            resolver.openOutputStream(sibling, "wt")?.use { it.write(bytes) } != null
        }.getOrDefault(false)
        return if (written) queryName(sibling) ?: Rescue.siblingName(displayName) else null
    }

    /** "name (rescued).ext" in the folder of a document md reached through a
     *  tree grant, or null for any other document. */
    private fun createRescueSibling(): Uri? = runCatching {
        val target = uri ?: return null
        val treeId = android.provider.DocumentsContract.getTreeDocumentId(target)
        val tree = android.provider.DocumentsContract.buildTreeDocumentUri(target.authority, treeId)
        val path = android.provider.DocumentsContract.findDocumentPath(resolver, target)?.path ?: return null
        val parentId = path.getOrNull(path.size - 2) ?: return null
        val parent = android.provider.DocumentsContract.buildDocumentUriUsingTree(tree, parentId)
        Rescue.siblingName(displayName).let { name ->
            android.provider.DocumentsContract.createDocument(resolver, parent, Rescue.siblingMimeType(name), name)
        }
    }.getOrNull()

    private fun queryName(target: Uri): String? = runCatching {
        resolver.query(target, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (idx >= 0) c.getString(idx) else null
            } else null
        }
    }.getOrNull()
}
