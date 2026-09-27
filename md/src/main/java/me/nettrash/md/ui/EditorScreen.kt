/*
 * EditorScreen.kt
 * md (Android)
 *
 * The content of the editor window: a raw-Markdown editor and a live
 * rendered preview, with a mode switch in the app bar. The available modes
 * adapt to the window width (see `ViewMode.kt`): a wide window — tablet,
 * unfolded foldable, desktop, large phone in landscape — offers Edit, Split
 * and Preview, with Split showing the two panes side by side and re-rendering
 * as you type (side by side when there's room, stacked when the Split window
 * itself is narrow); a phone-width window offers only Edit and Preview, like
 * iPhone. The chosen mode is remembered across configuration changes, and
 * per file: a document comes back in the mode it was left in, while one the
 * app has not seen before opens in Edit when it is empty, in Split where
 * Split exists, and in Preview on a narrow window where it does not. Book
 * articles are exempt, so stepping through chapters never changes the pane,
 * and jumping to a heading from the Contents menu only *nudges* the preview
 * on screen for as long as you are reading there — it never rewrites what the
 * file is remembered as. Mirrors the iOS `DocumentView.swift`.
 *
 * Documents are opened, created and saved through the Storage Access
 * Framework (Android's document architecture). Save writes back to the
 * current URI; when there's nowhere writable yet it falls through to a
 * Create Document ("Save As") picker. The buffer is also flushed when the
 * app is backgrounded, approximating the iOS autosave.
 *
 * Every action that would replace the open document — New, Open…, Edit…, an
 * example, Example Book…, and an intent handed in by the system — goes
 * through [confirmDiscard] rather than straight to the ViewModel. A buffer
 * with nowhere to autosave (untitled, shared in, an imported pack, a
 * read-only hand-off) gets the unsaved-changes dialog first, and the
 * replacement waits, parked in the ViewModel, until Save / Discard / Cancel
 * answers it (see DiscardGuard.kt). Back is held by the same dialog, so
 * predictive back cannot commit the exit animation before it appears; a
 * dirty WRITABLE document is a beat from disk and still leaves in silence.
 * A document handed in read-only is badged as such in the title, with an
 * Edit… action that re-opens it through the picker.
 *
 * The app bar also carries the document's structure and the writer tools:
 * a table-of-contents action that scrolls the preview to a heading, a
 * Notes… panel listing the private `<!-- note: … -->` comments, an
 * Examples menu that opens a bundled sample document as a new untitled
 * buffer (with an Example Book… action that copies the bundled sample
 * book into a picked folder), and the book navigator (New Book… /
 * Open Book… / Show Book / Close Book) over a user-picked folder tree —
 * see `BookSheet.kt` and `book/Book.kt`.
 *
 * Find and Replace is the bar under the app bar — Find… in the overflow menu
 * or Ctrl+F — over the editor pane: ordinal, case-insensitive, wrapping, no
 * regular expressions, the one rule `TextSearch.kt` states and md uses on
 * every port. Opening it from Preview nudges the editor on screen the way a
 * Contents tap nudges the preview (see `findTapMode`); the bar, its four
 * edits and the reveal that scrolls a match into the pane's scroll container
 * are `FindBar.kt`. Closing the bar gives that nudge straight back, so the
 * reader lands in the pane they were reading in.
 *
 * A hardware keyboard reaches the same commands through the chords md
 * answers to on every port — `Shortcuts.kt`, which is md.win's CommandTable
 * copied row for row. One handler answers them all (`handleKey`), installed
 * both in the composition's preview pass and on the Activity, because a
 * chord has to work wherever focus is: the preview's WebView leaves the
 * composition with no focus at all and only the Activity sees those presses,
 * while the editor's text field claims Ctrl+Alt+Up / Ctrl+Alt+Down as its
 * own Home / End unless the preview pass takes them first. It claims nothing
 * the text field owns: Ctrl+A, Ctrl+Z, Ctrl+Y, Ctrl+X, Ctrl+C and Ctrl+V are
 * not rows in that table and reach the field untouched.
 *
 * The editor types smart: Return continues a list, a quote or a table and
 * ends an empty item, and the first letter of a line or a sentence is
 * capitalized, Markdown-aware — md owns capitalization, so the keyboard's own
 * is off. Both are switches in the overflow menu ("Continue Lists and
 * Tables", "Capitalize Sentences"). The rules are `markdown/SmartTyping.kt`;
 * the field is `BasicTextField(state = …)` with the `InputTransformation` of
 * `SmartTypingTransformation.kt`, whose pure half is `Typing.kt`.
 */

package me.nettrash.md.ui

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContract
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.TextFieldDecorator
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.LibraryBooks
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.EditNote
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Print
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.SaveAlt
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Splitscreen
import androidx.compose.material.icons.filled.TextFields
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.VerticalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.net.toUri
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.nettrash.md.DiscardChoice
import me.nettrash.md.DocumentTypes
import me.nettrash.md.DocumentViewModel
import me.nettrash.md.MainActivity
import me.nettrash.md.Replacement
import me.nettrash.md.book.BookState
import me.nettrash.md.book.bookReadingOrder
import me.nettrash.md.book.bookStep
import me.nettrash.md.markdown.DiagramSvg
import me.nettrash.md.markdown.MarkdownParser
import me.nettrash.md.markdown.WritingStats

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditorScreen(viewModel: DocumentViewModel) {
    val context = LocalContext.current
    // Back on a document with unsaved changes is answered by the dialog, and
    // Discard there has to do what Back would have done — leave.
    val activity = LocalActivity.current
    val dark = isSystemInDarkTheme()
    // Which modes to offer depends on the current window width; Split is only
    // shown when there's room for it (see `ViewMode.kt`). `mode` holds the raw
    // preference and survives configuration changes; `effectiveMode` coerces
    // it to fit the current width without discarding it, so Split returns when
    // the window widens again.
    val isWide = isWideLayout(LocalConfiguration.current.screenWidthDp)
    var mode by rememberSaveable { mutableStateOf(Mode.SPLIT) }
    // A *navigation nudge*, and the whole of the distinction this screen has
    // to keep: picking a mode is a deliberate layout choice and is persisted,
    // while merely going somewhere — today only a Contents tap, which has to
    // put the preview on screen before it can scroll it to a heading — is
    // not. The nudge overrides what is DISPLAYED and nothing else: it writes
    // nothing to the per-file memory, and it is dropped the moment the reader
    // picks a mode from the switch or opens another document. That is exactly
    // how these apps behaved before per-file memory existed, when the mode
    // was session-only: a jump moved you, and it was never a preference.
    //
    // `rememberSaveable`, like `mode`: a rotation is neither a mode pick nor
    // a document change, and the reader is still parked at the heading they
    // jumped to, so dropping the nudge there would throw them back into the
    // other pane for no reason they could see.
    var navigationMode by rememberSaveable { mutableStateOf<Mode?>(null) }
    // What is on screen: the nudge if there is one, otherwise the file's
    // preference — then coerced to fit the window. The persisted preference
    // is `mode` alone and is read from there, never from here.
    val currentMode = effectiveMode(navigationMode ?: mode, isWide)
    var menuOpen by remember { mutableStateOf(false) }
    // Which page of the overflow menu is showing (see MenuRows.kt).
    var menuPage by remember { mutableStateOf(MenuPage.MAIN) }
    // Find and Replace (see FindBar.kt). The query and the replacement are
    // held here, and `rememberSaveable`, so a rotation mid-search keeps both
    // boxes as they were; `findFocus` is bumped every time Find is asked for,
    // so Ctrl+F with the bar already up puts the caret back in the query box.
    var findOpen by rememberSaveable { mutableStateOf(false) }
    var findQuery by rememberSaveable { mutableStateOf("") }
    var findReplacement by rememberSaveable { mutableStateOf("") }
    var findFocus by remember { mutableIntStateOf(0) }

    // Per-file view-mode memory (see `ViewMode.kt`): a file comes back in the
    // mode it was left in, and one the app has never seen opens in Edit when
    // it is empty, Split where Split exists, and Preview on a narrow window
    // where it does not. The store gets the application context because it
    // outlives any one composition and must not pin the Activity.
    val viewModeStore = remember { ViewModeStore(context.applicationContext) }
    // The identity the current document's mode is remembered under, or null
    // for a document that has none (untitled, shared-in text, an imported
    // pack). Recomputed only when the backing URI changes.
    val viewModeIdentity = remember(viewModel.uri) { viewModeStore.identityFor(viewModel.uri) }

    // Apply the open rule once per document — keyed on the ViewModel's
    // document token, which is bumped as the LAST statement of every path
    // that replaces the open document, so `text` and `uri` are already the
    // new document's by the time this runs. `text` is deliberately read HERE
    // and not at composition time: `loadAsync` fills it only when its
    // provider read lands, long after the composition that started it.
    //
    // `appliedToken` survives configuration changes, so a rotation cannot
    // re-run the rule and throw away a mode the reader chose on an untitled
    // buffer.
    var appliedToken by rememberSaveable { mutableLongStateOf(-1L) }
    LaunchedEffect(viewModel.documentToken) {
        val token = viewModel.documentToken
        if (token == appliedToken) return@LaunchedEffect
        appliedToken = token
        // A different document is on screen, so wherever the reader had
        // navigated to in the last one is gone and its nudge goes with it.
        // Before the book check, because a chapter step is a document change
        // too.
        navigationMode = null
        // A book article is exempt on all three ports: stepping to the next
        // chapter must neither read the memory nor write it, or a writer
        // would be dropped into Preview on the chapter they came to write.
        if (!remembersViewMode(viewModel.isBookArticle)) return@LaunchedEffect
        val opened = openViewMode(
            remembered = viewModeStore.remembered(viewModeIdentity),
            isEmptyDocument = viewModel.text.isEmpty(),
            hasFileIdentity = viewModeIdentity != null,
            isWide = isWide,
        )
        // The RAW mode, never `effectiveMode`'s output: a remembered Split
        // shown as Edit on a phone must still be Split when the window widens
        // again, and must still be Split the next time the file is opened.
        mode = opened
        viewModeStore.remember(viewModeIdentity, opened)
    }

    // Adopting a mode from the switch is the deliberate layout choice, and
    // the only thing the memory records — the raw choice, against the current
    // document. It also clears any navigation nudge: the reader has just said
    // what they want on screen, so the transient override has nothing left to
    // say, and the switch they just used goes back to showing their own
    // preference. A book article and a document with no identity store
    // nothing.
    fun chooseMode(chosen: Mode) {
        mode = chosen
        navigationMode = null
        if (remembersViewMode(viewModel.isBookArticle)) {
            viewModeStore.remember(viewModeIdentity, chosen)
        }
    }

    // Document structure, recomputed only when the text changes: the outline
    // feeds the table-of-contents action, the notes feed the Notes… panel.
    // Both parsers are line-oriented and cheap (see MarkdownParser).
    val outline = remember(viewModel.text) { MarkdownParser.outline(viewModel.text) }
    val notes = remember(viewModel.text) { MarkdownParser.notes(viewModel.text) }
    var contentsOpen by remember { mutableStateOf(false) }
    var notesOpen by remember { mutableStateOf(false) }
    // The diagrams the document offers for standalone-SVG export (Mermaid /
    // PlantUML / Graphviz — math is HTML+CSS, never SVG, so it is never
    // offered). Recomputed only when the text changes, like the outline; feeds
    // the overflow menu's Export ▸ Diagram as SVG page, disabled when there are none.
    val svgDiagrams = remember(viewModel.text) { DiagramSvg.diagrams(viewModel.text) }
    // The diagram a just-launched SVG create-document picker is for, carried
    // across the picker round-trip so its callback knows which ordinal to
    // capture out of the offscreen DOM.
    var pendingSvgDiagram by remember { mutableStateOf<DiagramSvg.Diagram?>(null) }
    // The PDF/print trim size, remembered app-wide (see PageSizeState). One
    // instance, shared with the book navigator below, so the document menu and
    // the book compile agree on a single choice; the overflow menu's Export
    // page shows it and changes it.
    val pageSizeState = remember { PageSizeState(context.applicationContext) }
    // The two smart-typing switches (see Typing.kt), remembered app-wide and
    // read live by the editor's transformation at every keystroke; and the
    // editor buffer itself — the text field's state, kept in step with the
    // ViewModel both ways (see rememberEditorBuffer) — one per screen, so
    // switching between Edit and Split keeps the caret where it was.
    val typingSettings = remember { TypingSettings(context.applicationContext) }
    val editor = rememberEditorBuffer(viewModel, typingSettings)
    // The bundled example documents (assets/examples/*.md); the "Example
    // Book" folder ships alongside them but belongs to Example Book… below.
    // Listed once — the APK's asset table can't change while we're running.
    val examples = remember {
        context.assets.list("examples").orEmpty().filter { it.endsWith(".md") }.sorted()
    }
    // The latest scroll-to-heading request for the preview. The counter id
    // makes every tap a distinct request (see PreviewNavigation).
    var previewNavigation by remember { mutableStateOf<PreviewNavigation?>(null) }

    // The writer-mode book (a user-picked folder tree — see book/Book.kt).
    // The holder restores its persisted tree URI from SharedPreferences, so
    // recreating it with the screen is free; it gets the application context
    // because it outlives any one composition and must not pin the Activity.
    val bookState = remember { BookState(context.applicationContext) }
    var bookSheetOpen by remember { mutableStateOf(false) }
    // New Book… runs in two steps: the name dialog first, then a tree picker
    // for where to keep it. `newBookDialogOpen` shows the dialog;
    // `pendingBookName` carries the chosen name across the picker round-trip.
    var newBookDialogOpen by remember { mutableStateOf(false) }
    var pendingBookName by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    // A forward reference, in the same spirit as EditorPane's
    // `expectedRemote` below: the Save As callback has to run whatever
    // replacement was parked behind the unsaved-changes dialog, but a
    // replacement is run by `runReplacement`, which is declared among the
    // launchers it needs. The holder is filled (and re-filled, so the
    // freshest composition owns the closure) just below them, and is only
    // ever read from a callback, long after composition. A plain holder:
    // it must never recompose anything.
    val runParked = remember { arrayOfNulls<(Replacement) -> Unit>(1) }

    // Adopt a document the picker returned: take the persistable read-write
    // grant (so the same file is still writable next launch) and open it.
    // Shared by Open… and by the read-only badge's Edit… action, which is the
    // same picker started at the document already on screen.
    fun adoptOpened(target: Uri) {
        runCatching {
            context.contentResolver.takePersistableUriPermission(
                target,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
        }
        viewModel.load(target, writable = true)
    }

    val openLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let { adoptOpened(it) }
    }
    // Edit…, offered only for a document handed in read-only: the same
    // ACTION_OPEN_DOCUMENT picker, asked to start AT that document so the
    // reader only has to confirm it. Not every provider honours
    // EXTRA_INITIAL_URI — the ones that don't just open where they always do,
    // which is why this is a nicety and not the mechanism.
    val reopenLauncher = rememberLauncherForActivityResult(
        ReopenDocument()
    ) { uri ->
        uri?.let { adoptOpened(it) }
    }
    val createLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/markdown")
    ) { uri ->
        if (uri == null) {
            // The picker was dismissed. Nothing was written, so a replacement
            // parked behind a Save in the unsaved-changes dialog is dropped
            // rather than run — the edits the reader asked to keep are still
            // unsaved, and replacing the document now would lose them. An
            // ordinary Save As lands here too; the guard is Idle and this
            // does nothing.
            viewModel.discardSaveFinished(false)
        } else {
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                )
            }
            // Save As is NOT a document change — the writer stays in the same
            // document and the same pane, and the ViewModel deliberately does
            // not bump its token — so the open rule must not re-run here. The
            // memory does move: the mode they are in right now becomes the
            // mode this newly created file will open in.
            //
            // Not gated on `isBookArticle`: saving a book article as a new
            // file makes it an ordinary standalone document, and `saveAs`
            // clears the flag for exactly that reason. Skipping the write
            // here would leave the new file with no memory at all AND — the
            // flag being what `remembersViewMode` answers from — leave every
            // later mode change on it unrecorded until the next document was
            // opened.
            // The RAW `mode` is what moves across — never `currentMode`, and
            // never `navigationMode`: Save As must immortalise the file's
            // actual preference, not the pane a Contents tap happens to be
            // showing while the reader looks something up. The two are
            // deliberately kept apart rather than folded into one accessor
            // for exactly this reason.
            val saved = viewModel.saveAs(uri)
            if (saved) {
                viewModeStore.remember(viewModeStore.identityFor(uri), mode)
            }
            // The Save the dialog asked for has landed (or failed): release
            // the replacement that was waiting on it.
            viewModel.discardSaveFinished(saved)?.let { request -> runParked[0]?.invoke(request) }
        }
    }
    // Export as PDF: the user picks the destination first, then the document
    // renders offscreen and the single-page PDF is written to that URI.
    val exportPdfLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/pdf")
    ) { uri ->
        uri?.let { target ->
            Exporter.renderPdf(context, viewModel.text, viewModel.displayName, dark, pageSizeState.selected) { bytes ->
                val written = bytes != null && runCatching {
                    // "wt" truncates — plain "w" keeps stale bytes when
                    // overwriting a longer, already-existing file — but some
                    // documents providers only support "w"; fall back rather
                    // than fail (CreateDocument made the file empty anyway).
                    val stream = runCatching { context.contentResolver.openOutputStream(target, "wt") }
                        .getOrNull() ?: context.contentResolver.openOutputStream(target)
                    stream!!.use { it.write(bytes) }
                }.isSuccess
                if (!written) {
                    // Don't leave the freshly created, empty .pdf silently in
                    // place — say the export failed.
                    Toast.makeText(context, "Couldn't export the PDF.", Toast.LENGTH_LONG).show()
                }
            }
        }
    }
    // Export as HTML: same shape as Export as PDF — destination first, then
    // the document renders offscreen and ONE self-contained .html file (no
    // engines, no folder of assets, no network) is written to that URI.
    val exportHtmlLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/html")
    ) { uri ->
        uri?.let { target ->
            Exporter.renderHtml(context, viewModel.text, viewModel.displayName, dark) { bytes ->
                val written = bytes != null && runCatching {
                    val stream = runCatching { context.contentResolver.openOutputStream(target, "wt") }
                        .getOrNull() ?: context.contentResolver.openOutputStream(target)
                    stream!!.use { it.write(bytes) }
                }.isSuccess
                if (!written) {
                    Toast.makeText(context, "Couldn't export the HTML.", Toast.LENGTH_LONG).show()
                }
            }
        }
    }
    // Export as LaTeX: the same destination-first shape, but nothing to
    // render — a .tex file is pure string work, so it is written the moment
    // the picker comes back (Exporter.exportLaTeX toasts its own failures).
    val exportTexLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/x-tex")
    ) { uri ->
        uri?.let { Exporter.exportLaTeX(context, it, viewModel.text) }
    }
    // Export as EPUB: same destination-first shape as Export as PDF / HTML —
    // the picker first, then the document renders offscreen (rich blocks to
    // images) and a one-unit EPUB 3 is written to the picked URI. The single
    // document is not a book, so it carries no title page and its nav is the
    // document's own heading outline (see EpubExporter.exportDocument).
    val exportEpubLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/epub+zip")
    ) { uri ->
        uri?.let { target ->
            EpubExporter.exportDocument(context, viewModel.text, viewModel.displayName) { bytes ->
                val written = bytes != null && runCatching {
                    val stream = runCatching { context.contentResolver.openOutputStream(target, "wt") }
                        .getOrNull() ?: context.contentResolver.openOutputStream(target)
                    stream!!.use { it.write(bytes) }
                }.isSuccess
                if (!written) {
                    Toast.makeText(context, "Couldn't export the EPUB.", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    // Export Diagram as SVG: the user picks one diagram from the submenu, the
    // destination picker opens, then the document renders offscreen and that
    // diagram's rendered <svg> is read out of the DOM and written as a
    // standalone vector file. `pendingSvgDiagram` carries the chosen diagram
    // across the picker round-trip (see the submenu below).
    val exportSvgLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("image/svg+xml")
    ) { uri ->
        val diagram = pendingSvgDiagram
        pendingSvgDiagram = null
        if (uri != null && diagram != null) {
            Exporter.renderDiagramSvg(context, viewModel.text, viewModel.displayName, diagram.ordinal) { bytes ->
                val written = bytes != null && runCatching {
                    val stream = runCatching { context.contentResolver.openOutputStream(uri, "wt") }
                        .getOrNull() ?: context.contentResolver.openOutputStream(uri)
                    stream!!.use { it.write(bytes) }
                }.isSuccess
                if (!written) {
                    // Don't leave the freshly created, empty .svg silently in
                    // place — a diagram that failed to render has no vector.
                    Toast.makeText(context, "Couldn't export the SVG.", Toast.LENGTH_LONG).show()
                }
            }
        }
    }
    // Export as TextPack: destination first, then the current document — with
    // any findable local images copied into assets/ and their refs rewritten —
    // is written as a .textpack (a zipped TextBundle). `viewModel.uri` is passed
    // so a file-backed document's local images can be found beside it.
    val exportTextPackLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream")
    ) { uri ->
        uri?.let { target ->
            Exporter.exportTextPack(context, target, viewModel.text, viewModel.uri, viewModel.displayName)
        }
    }

    // Open Book…: the user picks the book's root folder; the grant is made
    // persistable and the tree URI remembered inside BookState.adopt.
    val openBookLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        uri?.let { bookState.adopt(it) }
    }
    // New Book…, step two: this tree picker chooses the PARENT folder — SAF
    // can only grant existing trees, so the book itself (a subfolder named in
    // the dialog of step one) is created inside the picked tree by
    // BookState.createBook, which also takes the persistable grant on the
    // parent (it covers the subfolder — tree grants are recursive). On
    // success the sheet opens on the fresh, empty book.
    val newBookLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        val name = pendingBookName
        pendingBookName = null
        if (uri == null || name == null) return@rememberLauncherForActivityResult
        scope.launch {
            val created = withContext(Dispatchers.IO) { bookState.createBook(uri, name) }
            if (created) {
                bookSheetOpen = true
            } else {
                Toast.makeText(context, "Couldn't create \"$name\".", Toast.LENGTH_LONG).show()
            }
        }
    }

    // Example Book…: the same parent-tree picker as New Book…, but the
    // content is copied out of the bundled assets by
    // BookState.createExampleBook (which dedupes the folder name and takes
    // the persistable grant, exactly like createBook). On success the sheet
    // opens on the fresh copy.
    val exampleBookLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val created = withContext(Dispatchers.IO) { bookState.createExampleBook(uri) }
            if (created) {
                bookSheetOpen = true
            } else {
                Toast.makeText(context, "Couldn't create the example book.", Toast.LENGTH_LONG).show()
            }
        }
    }

    /** Save to the file behind the document, or fall through to Save As.
     *  Returns true when the document is already saved when this returns —
     *  false means a Create Document picker is now up and the answer arrives
     *  in its callback instead. */
    fun saveOrSaveAs(): Boolean {
        if (viewModel.save()) return true
        createLauncher.launch(suggestedFileName(viewModel.displayName))
        return false
    }

    // Open a bundled example as a fresh untitled document — the same route
    // shared text takes in via ACTION_SEND (see DocumentViewModel
    // .acceptSharedText): no backing file, the user saves it wherever they
    // like. Replaces the buffer like New / Open… do. The asset read stays
    // off the main thread, tiny as it is.
    fun openExample(fileName: String) {
        scope.launch {
            val source = withContext(Dispatchers.IO) {
                runCatching {
                    context.assets.open("examples/$fileName").bufferedReader().use { it.readText() }
                }.getOrNull()
            }
            if (source != null) {
                viewModel.acceptSharedText(source)
            } else {
                Toast.makeText(context, "Couldn't open the example.", Toast.LENGTH_LONG).show()
            }
        }
    }

    // Do a replacement, once it is allowed to happen. Every variant is one of
    // the things that put another document on screen; `Exit` is Back, which
    // puts none there at all. The Open…, Edit… and Example Book… variants
    // only raise their picker — nothing is replaced until it returns — which
    // is deliberate: the buffer stays on screen, and a dismissed picker
    // leaves the writer exactly where they were.
    //
    // NOT routed through here, and unchanged by this work: stepping to an
    // article from the book navigator, which replaces the buffer from a
    // writable file inside a book the writer is already working in.
    fun runReplacement(request: Replacement) {
        when (request) {
            Replacement.New -> viewModel.newDocument()
            Replacement.Open -> openLauncher.launch(DocumentTypes.OPENABLE_MIME_TYPES)
            is Replacement.Reopen -> reopenLauncher.launch(request.uri)
            is Replacement.Example -> openExample(request.fileName)
            Replacement.ExampleBook -> exampleBookLauncher.launch(null)
            is Replacement.Incoming -> viewModel.openIncoming(request.intent)
            Replacement.Exit -> activity?.finish()
        }
    }
    // Fill the forward reference the Save As callback reads (see `runParked`).
    runParked[0] = ::runReplacement

    // The one gate every replacement goes through: run it now when the
    // document on screen has nothing to lose, or let the ViewModel park it
    // behind the unsaved-changes dialog below.
    fun confirmDiscard(request: Replacement) {
        viewModel.requestReplacement(request)?.let { runReplacement(it) }
    }

    // Find…, from the overflow menu and from Ctrl+F. A match is shown by
    // selecting it in the editor, so the editor has to be on screen: asking
    // for Find from Preview nudges it there, exactly as a Contents tap nudges
    // the preview the other way (see `findTapMode`). A nudge and not a mode
    // pick — the file's remembered mode is left exactly as it was, and comes
    // back the moment the reader uses the switch or opens another document.
    // The nudge is dropped again where the search ends — the find bar's
    // `onClose` below — so a reader who searched from Preview is put back
    // there rather than stranded in the editor.
    fun openFind() {
        findTapMode(currentMode)?.let { navigationMode = it }
        findOpen = true
        findFocus++
    }

    // Previous / Next Article (Ctrl+Alt+Up / Ctrl+Alt+Down): step one place
    // along the open book's reading order and open what is there — the same
    // order the navigator lists and the book export writes (see
    // `bookReadingOrder`). Everything about it is a no-op unless it applies:
    // no book, an untitled document, a document that is not in the book, the
    // first article stepping back or the last stepping on. A chord with
    // nowhere to go does nothing, which is what a chord is expected to do;
    // there is no alert to dismiss and nothing moves.
    //
    // The book is listed on the keystroke rather than kept in state — two
    // shallow provider reads, and the alternative is a stale order after a
    // rename or a reorder in the navigator — so it runs off the main thread.
    // The open is the navigator's own: writable, and flagged as coming from
    // the book so the per-file view-mode memory stays out of it.
    fun stepArticle(offset: Int) {
        val current = viewModel.uri ?: return
        if (bookState.treeUri == null) return
        scope.launch {
            val target = withContext(Dispatchers.IO) {
                val tree = bookState.loadTree() ?: return@withContext null
                bookStep(
                    bookReadingOrder(tree).map { it.file.uri.toString() },
                    current.toString(),
                    offset,
                )
            }
            target?.let { viewModel.loadAsync(it.toUri(), writable = true, fromBook = true) }
        }
    }

    // What a chord runs. The table is `Shortcuts.kt` (md.win's CommandTable,
    // row for row); this is the other half — the same commands the menu rows
    // above run, reached from a hardware keyboard.
    fun runShortcut(action: ShortcutAction) {
        when (action) {
            ShortcutAction.NEW -> confirmDiscard(Replacement.New)
            ShortcutAction.OPEN -> confirmDiscard(Replacement.Open)
            ShortcutAction.SAVE -> saveOrSaveAs()
            ShortcutAction.SAVE_AS -> createLauncher.launch(suggestedFileName(viewModel.displayName))
            ShortcutAction.PRINT ->
                Exporter.printRendered(context, viewModel.text, viewModel.displayName, dark)
            ShortcutAction.VIEW_EDIT -> chooseMode(Mode.EDIT)
            ShortcutAction.VIEW_SPLIT -> chooseMode(Mode.SPLIT)
            ShortcutAction.VIEW_PREVIEW -> chooseMode(Mode.PREVIEW)
            // Show Book with no book adopted has nothing to show; md.win
            // greys the row out for the same reason.
            ShortcutAction.SHOW_BOOK -> if (bookState.treeUri != null) bookSheetOpen = true
            ShortcutAction.FIND -> openFind()
            ShortcutAction.PREVIOUS_ARTICLE -> stepArticle(-1)
            ShortcutAction.NEXT_ARTICLE -> stepArticle(1)
        }
    }

    // The one key handler, installed in two places because a chord has to
    // answer wherever focus is and neither place sees every press:
    //
    //  - on the Scaffold's root modifier (`onPreviewKeyEvent`, below), which
    //    Compose runs on the way DOWN to the focused node — ahead of the
    //    editor's text field, which claims Ctrl+Alt+Up / Ctrl+Alt+Down for
    //    its own Home / End if it is asked first;
    //  - on the Activity (see MainActivity.chordHandler), which is the only
    //    one that answers while the preview pane — a WebView in an
    //    AndroidView — is on screen, because the composition then holds no
    //    focus at all and no modifier in this tree runs.
    //
    // Either way it claims a press only when the chord table answers, which
    // is what leaves the text field's own editing keys (Ctrl+A / Z / Y / X /
    // C / V, and a bare Alt+Arrow) alone: they are not rows in that table,
    // so `handleKey` returns false and the press goes on to the field. A
    // press with Meta held is left alone too — the launcher's and the
    // system's territory, not md's. A claimed chord is claimed whole, key-up
    // included, so the field is never handed half a keystroke; a held-down
    // chord runs once, not once per repeat. A press claimed in the preview
    // pass never reaches the Activity, so a chord runs once however it came.
    fun handleKey(event: KeyEvent): Boolean {
        if (event.isMetaPressed) return false
        val action = Shortcuts.resolve(
            key = shortcutKey(event.key),
            ctrl = event.isCtrlPressed,
            shift = event.isShiftPressed,
            alt = event.isAltPressed,
            isWide = isWide,
        ) ?: return false
        if (event.type == KeyEventType.KeyDown && event.nativeKeyEvent.repeatCount == 0) {
            runShortcut(action)
        }
        return true
    }
    // Re-filled on every composition, so the closure a press runs is the
    // freshest one — the same holder discipline `runParked` above keeps, and
    // for the same reason: it is only ever read from a callback.
    (activity as? MainActivity)?.chordHandler = ::handleKey
    DisposableEffect(activity) {
        onDispose { (activity as? MainActivity)?.chordHandler = null }
    }

    // Approximate the iOS autosave: flush a writable, dirty buffer when the
    // app goes to the background.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP && viewModel.isDirty && viewModel.canWrite) {
                viewModel.save()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // Back on a buffer with nowhere to autosave asks before it leaves. It is
    // a BackHandler rather than an Activity override because predictive back
    // (the default at targetSdk 36) starts committing the exit animation as
    // the gesture completes: an enabled handler is what tells the system this
    // Back is being taken over, before there is anything to undo. Enabled
    // only while there is something to lose — a dirty WRITABLE document
    // leaves in silence and is flushed by the ON_STOP observer above, exactly
    // as it always was.
    BackHandler(enabled = viewModel.needsDiscardPrompt) { confirmDiscard(Replacement.Exit) }

    Scaffold(
        // The chords, taken ahead of whatever has focus. Compose dispatches
        // the preview pass from the focus root DOWN to the focused node, so
        // a handler here runs before the text field's own key handling — the
        // same order `EditorBuffer.onPreviewKeyEvent` relies on to take
        // Shift+Return off the field. It is needed for exactly two of md's
        // twelve rows: Compose Foundation's Android key mapping reads
        // Alt+Up / Alt+Down as Home / End *without looking at Ctrl*, so the
        // focused field claimed Ctrl+Alt+Up / Ctrl+Alt+Down, moved the caret
        // and reported the press consumed — and the Activity's leftovers
        // handler, which sees only what the view hierarchy did not want, was
        // never asked. Nothing else changes hands: `handleKey` answers false
        // for every press the chord table does not claim, so Ctrl+A / Z / Y /
        // X / C / V, and a bare Alt+Arrow, still reach the field untouched.
        // `MainActivity.onKeyDown` stays as the other half — a press that
        // arrives while the composition holds no focus at all, which is every
        // press made while the preview's WebView is on screen.
        modifier = Modifier.onPreviewKeyEvent { handleKey(it) },
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            // The app bar, with the find bar under it while it is open —
            // part of the top bar so the Scaffold pads the panes below it.
            Column {
                TopAppBar(
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.background,
                        titleContentColor = MaterialTheme.colorScheme.onBackground,
                    ),
                    title = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = viewModel.displayName + if (viewModel.isDirty) " •" else "",
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f, fill = false),
                            )
                            // A document handed in without write access says so,
                            // rather than looking like every other document and
                            // quietly never saving. The way out is Edit… in the
                            // menu, which re-opens the same file through the
                            // picker (see reopenLauncher).
                            if (viewModel.isReadOnly) {
                                Text(
                                    "Read Only",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    modifier = Modifier
                                        .padding(start = 8.dp)
                                        .background(
                                            MaterialTheme.colorScheme.surfaceVariant,
                                            RoundedCornerShape(4.dp),
                                        )
                                        .padding(horizontal = 6.dp, vertical = 2.dp),
                                )
                            }
                        }
                    },
                    actions = {
                        // Table of contents: enabled once the document has any
                        // headings. Tapping an entry makes sure the preview is on
                        // screen (Edit flips to Preview; Split and Preview already
                        // show it) and scrolls it to that heading's anchor.
                        //
                        // The flip is decided from `currentMode` — what is on
                        // screen — because whether the preview is visible is the
                        // only question being asked (see `contentsTapMode`). It
                        // is adopted as a transient `navigationMode` and NOT
                        // through `chooseMode`: reading a heading is navigation,
                        // not a layout choice, so it must leave the file's
                        // remembered mode exactly as it was. On a narrow window
                        // that is what lets a file remembered as Split — rendered
                        // as Edit there — show the preview for the jump and still
                        // be Split the next time it is opened.
                        Box {
                            IconButton(onClick = { contentsOpen = true }, enabled = outline.isNotEmpty()) {
                                Icon(Icons.AutoMirrored.Filled.List, contentDescription = "Contents")
                            }
                            DropdownMenu(expanded = contentsOpen, onDismissRequest = { contentsOpen = false }) {
                                outline.forEach { entry ->
                                    DropdownMenuItem(
                                        text = {
                                            // Two spaces of indent per level beyond 1 —
                                            // enough to read the nesting at a glance.
                                            Text(
                                                "  ".repeat((entry.level - 1).coerceAtLeast(0)) + entry.text,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                            )
                                        },
                                        onClick = {
                                            contentsOpen = false
                                            contentsTapMode(currentMode)?.let { navigationMode = it }
                                            previewNavigation = PreviewNavigation(
                                                id = (previewNavigation?.id ?: 0L) + 1,
                                                slug = entry.slug,
                                            )
                                        },
                                    )
                                }
                            }
                        }
                        // Find and Replace, one tap away — the only way in on a
                        // touch screen, with Ctrl+F on a keyboard (see `openFind`),
                        // which also brings the editor on screen if Preview is
                        // showing. It is a bar button for the reason the iPhone's
                        // Find is a toolbar button: at the foot of the old flat
                        // menu, below seven Export rows, it went unfound; the ⋮
                        // menu no longer repeats it.
                        IconButton(onClick = { openFind() }) {
                            Icon(Icons.Filled.Search, contentDescription = "Find")
                        }
                        ModeSwitch(availableModes(isWide), currentMode) { chooseMode(it) }
                        Box {
                            IconButton(onClick = { menuPage = MenuPage.MAIN; menuOpen = true }) {
                                Icon(Icons.Filled.MoreVert, contentDescription = "More")
                            }
                            // One popup, several pages (see MenuRows.kt): a short
                            // main page of groups, each drilled into in place and
                            // headed by a Back row. Find is not here: it is the
                            // magnifier beside this button, and Ctrl+F.
                            // A minimum width, so the popup keeps its size as it
                            // drills from page to page instead of jumping.
                            DropdownMenu(
                                expanded = menuOpen,
                                onDismissRequest = { menuOpen = false },
                                modifier = Modifier.widthIn(min = 264.dp),
                            ) {
                                when (menuPage) {
                                    MenuPage.MAIN -> {
                                        // Only for a document handed in read-only: re-open
                                        // the same file through the picker, which grants
                                        // the write access the sender withheld — through
                                        // the guard like every other replacement, since
                                        // what the picker hands back is the file as it is
                                        // on disk.
                                        if (viewModel.isReadOnly) {
                                            MenuRow("Edit…", Icons.Filled.Edit) {
                                                menuOpen = false
                                                viewModel.uri?.let { confirmDiscard(Replacement.Reopen(it)) }
                                            }
                                            HorizontalDivider()
                                        }
                                        MenuRow("New", Icons.Filled.Add) {
                                            menuOpen = false; confirmDiscard(Replacement.New)
                                        }
                                        // The MIME list lives with the extension sets it
                                        // exists to cover (see DocumentTypes).
                                        MenuRow("Open…", Icons.Filled.FolderOpen) {
                                            menuOpen = false; confirmDiscard(Replacement.Open)
                                        }
                                        SubmenuRow("Examples", Icons.AutoMirrored.Filled.MenuBook) {
                                            menuPage = MenuPage.EXAMPLES
                                        }
                                        MenuRow("Save", Icons.Filled.Save) {
                                            menuOpen = false; saveOrSaveAs()
                                        }
                                        MenuRow("Save As…") {
                                            menuOpen = false
                                            createLauncher.launch(suggestedFileName(viewModel.displayName))
                                        }
                                        HorizontalDivider()
                                        SubmenuRow("Share", Icons.Filled.Share) { menuPage = MenuPage.SHARE }
                                        SubmenuRow("Export", Icons.Filled.SaveAlt) { menuPage = MenuPage.EXPORT }
                                        MenuRow("Print…", Icons.Filled.Print) {
                                            menuOpen = false
                                            Exporter.printRendered(context, viewModel.text, viewModel.displayName, dark)
                                        }
                                        HorizontalDivider()
                                        SubmenuRow("Book", Icons.AutoMirrored.Filled.LibraryBooks) { menuPage = MenuPage.BOOK }
                                        // The private author notes (`<!-- note: … -->`),
                                        // greyed out until the document has one.
                                        MenuRow("Notes…", Icons.Filled.EditNote, enabled = notes.isNotEmpty()) {
                                            menuOpen = false; notesOpen = true
                                        }
                                        SubmenuRow("Typing", Icons.Filled.TextFields) { menuPage = MenuPage.TYPING }
                                    }
                                    // The bundled examples: picking one seeds a fresh
                                    // untitled document with that sample (openExample).
                                    MenuPage.EXAMPLES -> {
                                        BackRow("Examples") { menuPage = MenuPage.MAIN }
                                        examples.forEach { fileName ->
                                            MenuRow(exampleTitle(fileName)) {
                                                menuOpen = false
                                                confirmDiscard(Replacement.Example(fileName))
                                            }
                                        }
                                        HorizontalDivider()
                                        // The sample book lives with its fellow samples:
                                        // copies the bundled book into a picked folder
                                        // and opens it (see exampleBookLauncher above).
                                        MenuRow("Example Book…") {
                                            menuOpen = false
                                            confirmDiscard(Replacement.ExampleBook)
                                        }
                                    }
                                    MenuPage.SHARE -> {
                                        BackRow("Share") { menuPage = MenuPage.MAIN }
                                        MenuRow("Source…") {
                                            menuOpen = false
                                            Exporter.shareSource(context, viewModel.text, viewModel.displayName)
                                        }
                                        MenuRow("Rendered PDF…") {
                                            menuOpen = false
                                            Exporter.sharePdf(context, viewModel.text, viewModel.displayName, dark, pageSizeState.selected)
                                        }
                                    }
                                    MenuPage.EXPORT -> {
                                        BackRow("Export") { menuPage = MenuPage.MAIN }
                                        MenuRow("PDF…") {
                                            menuOpen = false
                                            exportPdfLauncher.launch(suggestedPdfName(viewModel.displayName))
                                        }
                                        MenuRow("HTML…") {
                                            menuOpen = false
                                            exportHtmlLauncher.launch(suggestedHtmlName(viewModel.displayName))
                                        }
                                        MenuRow("EPUB…") {
                                            menuOpen = false
                                            exportEpubLauncher.launch(suggestedEpubName(viewModel.displayName))
                                        }
                                        MenuRow("LaTeX…") {
                                            menuOpen = false
                                            exportTexLauncher.launch(suggestedTexName(viewModel.displayName))
                                        }
                                        MenuRow("TextPack…") {
                                            menuOpen = false
                                            exportTextPackLauncher.launch(suggestedTextPackName(viewModel.displayName))
                                        }
                                        // One vector diagram → a standalone .svg; greyed
                                        // out until the document has a Mermaid / PlantUML /
                                        // Graphviz diagram — math is HTML+CSS, not SVG.
                                        SubmenuRow("Diagram as SVG", enabled = svgDiagrams.isNotEmpty()) {
                                            menuPage = MenuPage.SVG
                                        }
                                        HorizontalDivider()
                                        // The trim size both PDF actions (Export and
                                        // Share) use, shown beside the row.
                                        SubmenuRow("PDF Page Size", value = pageSizeState.selected.label) {
                                            menuPage = MenuPage.PAGE_SIZE
                                        }
                                    }
                                    // Each row is one diagram, labelled by engine and a
                                    // snippet of its source (DiagramSvg.menuTitle);
                                    // the capture happens in exportSvgLauncher.
                                    MenuPage.SVG -> {
                                        BackRow("Diagram as SVG") { menuPage = MenuPage.EXPORT }
                                        svgDiagrams.forEach { diagram ->
                                            DropdownMenuItem(
                                                text = {
                                                    Text(diagram.menuTitle, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                                },
                                                onClick = {
                                                    menuOpen = false
                                                    pendingSvgDiagram = diagram
                                                    exportSvgLauncher.launch(
                                                        suggestedSvgName(viewModel.displayName, diagram.ordinal),
                                                    )
                                                },
                                            )
                                        }
                                    }
                                    // Remembered app-wide (see PageSizeState). A choice
                                    // is a setting, not an action: it goes back to the
                                    // Export page, which now shows it beside the row.
                                    MenuPage.PAGE_SIZE -> {
                                        BackRow("PDF Page Size") { menuPage = MenuPage.EXPORT }
                                        PageSize.ALL.forEach { size ->
                                            DropdownMenuItem(
                                                text = { Text(size.label) },
                                                trailingIcon = {
                                                    if (size.id == pageSizeState.selected.id) {
                                                        Icon(Icons.Filled.Check, contentDescription = "Selected")
                                                    }
                                                },
                                                onClick = {
                                                    pageSizeState.choose(size)
                                                    menuPage = MenuPage.EXPORT
                                                },
                                            )
                                        }
                                    }
                                    // The writer-mode book: create one or pick an
                                    // existing folder tree, then browse it from Show
                                    // Book (see BookSheet.kt). Labels match iOS/macOS.
                                    MenuPage.BOOK -> {
                                        BackRow("Book") { menuPage = MenuPage.MAIN }
                                        MenuRow("New Book…") {
                                            menuOpen = false; newBookDialogOpen = true
                                        }
                                        MenuRow("Open Book…") {
                                            menuOpen = false; openBookLauncher.launch(null)
                                        }
                                        if (bookState.treeUri != null) {
                                            HorizontalDivider()
                                            MenuRow("Show Book") {
                                                menuOpen = false; bookSheetOpen = true
                                            }
                                            MenuRow("Close Book") {
                                                menuOpen = false; bookState.close()
                                            }
                                        }
                                    }
                                    // Smart typing (see Typing.kt): two switches, both
                                    // on by default, the same wording on every port. A
                                    // tap flips the checkbox in place and leaves the
                                    // menu open — a setting, not an action. The
                                    // checkbox is display only (the row is the target),
                                    // so the row carries the state for accessibility.
                                    MenuPage.TYPING -> {
                                        BackRow("Typing") { menuPage = MenuPage.MAIN }
                                        DropdownMenuItem(
                                            text = { Text("Continue Lists and Tables") },
                                            modifier = Modifier.semantics {
                                                toggleableState = ToggleableState(typingSettings.continueLists)
                                            },
                                            trailingIcon = {
                                                Checkbox(checked = typingSettings.continueLists, onCheckedChange = null)
                                            },
                                            onClick = { typingSettings.chooseContinueLists(!typingSettings.continueLists) },
                                        )
                                        DropdownMenuItem(
                                            text = { Text("Capitalize Sentences") },
                                            modifier = Modifier.semantics {
                                                toggleableState = ToggleableState(typingSettings.capitalizeSentences)
                                            },
                                            trailingIcon = {
                                                Checkbox(checked = typingSettings.capitalizeSentences, onCheckedChange = null)
                                            },
                                            onClick = { typingSettings.chooseCapitalizeSentences(!typingSettings.capitalizeSentences) },
                                        )
                                    }
                                }
                            }
                        }
                    },
                )
                if (findOpen) {
                    FindBar(
                        editor = editor,
                        query = findQuery,
                        onQueryChange = { findQuery = it },
                        replacement = findReplacement,
                        onReplacementChange = { findReplacement = it },
                        focusToken = findFocus,
                        // Closing the bar is the explicit end of the search,
                        // so the nudge `openFind` raised is given back with
                        // it: a reader who searched from Preview goes back to
                        // Preview rather than being left in an editor they
                        // never asked for, with the switch showing a pane
                        // they never picked. (A Contents tap deliberately
                        // keeps its nudge — the reader is still reading
                        // there.) Nothing was ever written to the per-file
                        // memory, so there is nothing to restore but this.
                        onClose = { findOpen = false; navigationMode = null },
                    )
                }
            }
        },
        bottomBar = {
            // The author's counters: live words and characters, tucked
            // under the panes. Recomputed only when the text changes —
            // the same house pattern as the outline above. The strip pads
            // itself above the navigation bar (the Scaffold doesn't inset
            // a custom bottomBar) and above the keyboard while writing —
            // the inset-padding modifiers coordinate, so the two never sum.
            val stats = remember(viewModel.text) {
                WritingStats.words(viewModel.text) to viewModel.text.length
            }
            Column(Modifier.navigationBarsPadding().imePadding()) {
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Row(
                    Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .padding(horizontal = 12.dp, vertical = 5.dp),
                    horizontalArrangement = Arrangement.End,
                ) {
                    Text(
                        "${stats.first} words · ${stats.second} characters",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
    ) { padding ->
        // `consumeWindowInsets` marks the Scaffold padding as consumed, so
        // no nested inset modifier (an imePadding down the tree) can pad
        // the same keyboard height a second time.
        Content(
            viewModel, editor, currentMode, previewNavigation,
            Modifier.padding(padding).consumeWindowInsets(padding),
        )
    }

    // Notes panel: purely informational. The preview never renders notes
    // (they're private by design), and jumping the editor to a note's
    // *line* would need the text layout's line geometry (the pane's
    // ScrollState scrolls by pixel, not by line) — intentionally out of
    // scope on Android; the 1-based line numbers are the hand-rail instead.
    if (notesOpen) {
        AlertDialog(
            onDismissRequest = { notesOpen = false },
            title = { Text("Notes") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    notes.forEach { note ->
                        Text(
                            "Line ${note.line + 1} — ${note.text}",
                            modifier = Modifier.padding(vertical = 4.dp),
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { notesOpen = false }) { Text("Done") }
            },
        )
    }

    // The unsaved-changes dialog. It is up whenever a replacement is parked
    // (see DiscardGuard.kt): the buffer has edits and nowhere to autosave
    // them, so New, Open…, an example, an incoming document — or Back — has
    // to be answered for first. Three answers, and each of them finishes the
    // job: Save runs the ordinary Save / Save As path and lets the picker's
    // callback release the replacement, Discard runs it now, Cancel drops it
    // and leaves the writer exactly where they were (as does dismissing the
    // dialog).
    if (viewModel.isPromptingDiscard) {
        AlertDialog(
            onDismissRequest = { viewModel.resolveDiscard(DiscardChoice.CANCEL) },
            title = { Text("Unsaved Changes") },
            text = {
                Text(
                    "“${viewModel.displayName}” has changes that aren't saved anywhere yet. " +
                        "Save it before going on?",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.resolveDiscard(DiscardChoice.SAVE)
                    // A document that asked this question has nowhere to save
                    // to, so this is all but always the Save As picker and the
                    // answer arrives in its callback; a save that somehow
                    // landed at once releases the replacement right here.
                    if (saveOrSaveAs()) {
                        viewModel.discardSaveFinished(true)?.let { runReplacement(it) }
                    }
                }) { Text("Save") }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = { viewModel.resolveDiscard(DiscardChoice.CANCEL) }) {
                        Text("Cancel")
                    }
                    TextButton(onClick = {
                        viewModel.resolveDiscard(DiscardChoice.DISCARD)?.let { runReplacement(it) }
                    }) { Text("Discard") }
                }
            },
        )
    }

    // New Book…, step one: the shared name dialog (see BookSheet.NameDialog).
    // Dismissed synchronously in the confirm handler — like the sheet's
    // create paths — before the picker launches, so it can't be re-confirmed.
    if (newBookDialogOpen) {
        NameDialog(
            title = "New Book",
            initialName = "My Book",
            onCancel = { newBookDialogOpen = false },
            onCreate = { name ->
                newBookDialogOpen = false
                pendingBookName = name
                newBookLauncher.launch(null)
            },
        )
    }

    // The book navigator. Composed only while open, so its listing is
    // re-read from the tree on every opening (see BookSheet). Articles open
    // through loadAsync — the provider read must not stall the UI — and are
    // flagged as coming from the book, which exempts them from per-file
    // view-mode memory: a chapter step keeps the writer in the pane they are
    // working in (see `ViewMode.kt`).
    if (bookSheetOpen) {
        BookSheet(
            book = bookState,
            pageSizeState = pageSizeState,
            onOpenArticle = { article ->
                viewModel.loadAsync(article.file.uri, writable = true, fromBook = true)
                bookSheetOpen = false
            },
            onDismiss = { bookSheetOpen = false },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ModeSwitch(modes: List<Mode>, selected: Mode, onChange: (Mode) -> Unit) {
    SingleChoiceSegmentedButtonRow {
        modes.forEachIndexed { index, m ->
            SegmentedButton(
                selected = selected == m,
                onClick = { onChange(m) },
                shape = SegmentedButtonDefaults.itemShape(index, modes.size),
                icon = {},
            ) {
                Icon(
                    imageVector = when (m) {
                        Mode.EDIT -> Icons.Filled.Edit
                        Mode.SPLIT -> Icons.Filled.Splitscreen
                        Mode.PREVIEW -> Icons.Filled.Visibility
                    },
                    contentDescription = m.name.lowercase().replaceFirstChar { it.uppercase() },
                )
            }
        }
    }
}

@Composable
private fun Content(
    viewModel: DocumentViewModel,
    editor: EditorBuffer,
    mode: Mode,
    navigation: PreviewNavigation?,
    modifier: Modifier,
) {
    when (mode) {
        Mode.EDIT -> EditorPane(editor, null, modifier.fillMaxSize())
        Mode.PREVIEW -> PreviewPane(viewModel, navigation, null, modifier.fillMaxSize())
        Mode.SPLIT -> BoxWithConstraints(modifier.fillMaxSize()) {
            // The pane link that makes the two halves scroll as one — a
            // plain remembered object; the panes register themselves on it.
            val scrollSync = remember { ScrollSync() }
            SplitPanes(
                sideBySide = maxWidth >= 640.dp,
                first = { paneModifier -> EditorPane(editor, scrollSync, paneModifier) },
                second = { paneModifier -> PreviewPane(viewModel, navigation, scrollSync, paneModifier) },
            )
        }
    }
}

/**
 * Split's two panes, side by side or stacked, each given half the room.
 *
 * The side-by-side divider is a [VerticalDivider] (`fillMaxHeight` × 1 dp).
 * It used to be a `HorizontalDivider` with `fillMaxSize().widthIn(max = 1.dp)`:
 * a Row measures its unweighted children first, `fillMaxSize` pinned that
 * divider to the Row's WHOLE width before `widthIn` could narrow it, and both
 * weighted panes were left 0 px wide — Split on any window 640 dp or wider
 * (a tablet, a foldable, a phone in landscape) showed a line under the app
 * bar and nothing else. `SplitPanesInstrumentedTest` pins the pane widths.
 */
@Composable
internal fun SplitPanes(
    sideBySide: Boolean,
    first: @Composable (Modifier) -> Unit,
    second: @Composable (Modifier) -> Unit,
) {
    if (sideBySide) {
        Row(Modifier.fillMaxSize()) {
            first(Modifier.weight(1f).fillMaxSize())
            VerticalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            second(Modifier.weight(1f).fillMaxSize())
        }
    } else {
        Column(Modifier.fillMaxSize()) {
            first(Modifier.weight(1f).fillMaxWidth())
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            second(Modifier.weight(1f).fillMaxWidth())
        }
    }
}

@Composable
private fun EditorPane(editor: EditorBuffer, scrollSync: ScrollSync?, modifier: Modifier) {
    // The text field sits in a scroll container the pane owns (rather than
    // relying on BasicTextField's internal scroller) so Split's scroll sync
    // can read and drive a real ScrollState. The field keeps at least the
    // viewport's height, so tapping anywhere below a short text still
    // focuses it.
    val scrollState = rememberScrollState()
    val scope = rememberCoroutineScope()
    // The scroll target a preview-relayed scroll just applied; the
    // collector below consumes exactly that value instead of relaying it
    // back — the feedback-loop guard. A target, not a boolean bracket:
    // snapshotFlow delivers on a later dispatch than scrollTo, so any
    // flag would already be reset by the time the value arrives. -1 =
    // nothing pending. A plain holder: must never recompose anything.
    val expectedRemote = remember { intArrayOf(-1) }
    // The field's latest text layout, for bringing a find match on screen:
    // the field hands us a getter, and the getter always answers with the
    // current layout. A plain holder, like the one above — it must never
    // recompose anything.
    val layout = remember { arrayOfNulls<() -> TextLayoutResult?>(1) }
    if (scrollSync != null) {
        // (Re-)wire on every composition, so the freshest state owns the
        // closures.
        scrollSync.scrollEditor = { fraction ->
            scope.launch {
                // Already coerced into 0..maxValue, so ScrollState writes
                // exactly this value and the collector recognizes it.
                val target = (fraction.coerceIn(0f, 1f) * scrollState.maxValue).toInt()
                expectedRemote[0] = target
                scrollState.scrollTo(target)
            }
        }
        LaunchedEffect(scrollState, scrollSync) {
            snapshotFlow { scrollState.value }.collect { value ->
                val expected = expectedRemote[0]
                expectedRemote[0] = -1 // consume; a mismatch clears staleness
                val max = scrollState.maxValue
                if (value != expected && max > 0) {
                    scrollSync.editorDidScroll(value.toFloat() / max)
                }
            }
        }
    }
    // No imePadding here: the always-present stats bottomBar lifts the
    // Scaffold's content padding above the keyboard already.
    BoxWithConstraints(modifier.background(MaterialTheme.colorScheme.background)) {
        val viewportHeight = maxHeight
        val density = LocalDensity.current
        val viewportPx = with(density) { viewportHeight.roundToPx() }
        val insetPx = with(density) { EDITOR_PADDING.roundToPx() }
        // Bring the selection on screen whenever the find bar moves it (see
        // EditorBuffer.requestReveal) — this is the pane's own scroll
        // container, the one the Split sync reads and drives, so a revealed
        // match takes the preview with it.
        //
        // The frame wait is the whole subtlety: a Replace changes the text,
        // and the layout that answers geometry for it only exists after the
        // field has laid the new text out. Asking on the next frame asks the
        // layout the reader is looking at.
        LaunchedEffect(editor.revealToken) {
            if (editor.revealToken == 0) return@LaunchedEffect
            withFrameNanos { }
            val result = layout[0]?.invoke() ?: return@LaunchedEffect
            val caret = editor.state.selection.min.coerceIn(0, result.layoutInput.text.length)
            val box = result.getCursorRect(caret)
            val top = box.top.toInt() + insetPx
            val bottom = box.bottom.toInt() + insetPx
            val current = scrollState.value
            val target = when {
                top < current -> top
                bottom > current + viewportPx -> bottom - viewportPx
                else -> current
            }
            if (target != current) {
                scrollState.animateScrollTo(target.coerceIn(0, scrollState.maxValue))
            }
        }
        Column(Modifier.fillMaxSize().verticalScroll(scrollState)) {
            // The state-backed field: every user edit runs through the
            // smart-typing transformation (see SmartTypingTransformation.kt),
            // which sees Return as `commitText("\n")` and composed words as
            // replacements over one region — neither is a KeyEvent. A
            // hardware Shift+Return is the one thing caught as a key, in the
            // preview pass, and inserts a plain newline (§3.6).
            //
            // `KeyboardCapitalization.None`: md owns capitalization now (§3.2),
            // so code fences stop being capitalized by the keyboard and list
            // items, quotes and headings start the same way on every port.
            BasicTextField(
                state = editor.state,
                modifier = Modifier
                    .fillMaxWidth()
                    .defaultMinSize(minHeight = viewportHeight)
                    .padding(EDITOR_PADDING)
                    .onPreviewKeyEvent(editor::onPreviewKeyEvent),
                inputTransformation = editor.transformation,
                // Kept for the reveal above; the field calls this on every
                // layout and the getter it hands over stays valid.
                onTextLayout = { getResult -> layout[0] = getResult },
                textStyle = TextStyle(
                    fontFamily = FontFamily.Serif,
                    fontSize = 16.sp,
                    lineHeight = 24.sp,
                    color = MaterialTheme.colorScheme.onBackground,
                ),
                keyboardOptions = KeyboardOptions(
                    autoCorrectEnabled = false,
                    capitalization = KeyboardCapitalization.None,
                ),
                lineLimits = TextFieldLineLimits.MultiLine(),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                decorator = TextFieldDecorator { inner ->
                    if (editor.state.text.isEmpty()) {
                        Text(
                            "# Start writing…",
                            fontFamily = FontFamily.Serif,
                            fontSize = 16.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                        )
                    }
                    inner()
                },
            )
        }
    }
}

@Composable
private fun PreviewPane(
    viewModel: DocumentViewModel,
    navigation: PreviewNavigation?,
    scrollSync: ScrollSync?,
    modifier: Modifier,
) {
    // The rendered preview is a WebView showing the same themed HTML as
    // Print / Save-as-PDF, so LaTeX math, Mermaid and PlantUML render (offline).
    // It scrolls and lays out internally (see the CSS in MarkdownHtml).
    // `navigation` scrolls it to a heading when the table of contents asks;
    // `scrollSync` links it to the editor in Split.
    RichPreview(
        text = viewModel.text,
        title = viewModel.displayName,
        modifier = modifier.fillMaxSize(),
        navigation = navigation,
        scrollSync = scrollSync,
        document = viewModel.documentToken,
        retry = viewModel.previewRetry(),
    )
}

/** The inset between the editor's text and the pane's edges. Named because
 *  the reveal above has to add it: a text layout's geometry is measured from
 *  the text's own origin, which this padding moves. */
private val EDITOR_PADDING = 16.dp

/**
 * The [ShortcutKey] a Compose key is, or null for the many keys md claims no
 * chord on — which is most of them, and every key the text field owns. The
 * numeric row and the numeric keypad are the same key as far as a chord is
 * concerned; nothing else needs an alias.
 */
private fun shortcutKey(key: Key): ShortcutKey? = when (key) {
    Key.N -> ShortcutKey.N
    Key.O -> ShortcutKey.O
    Key.S -> ShortcutKey.S
    Key.P -> ShortcutKey.P
    Key.B -> ShortcutKey.B
    Key.F -> ShortcutKey.F
    Key.One, Key.NumPad1 -> ShortcutKey.NUMBER_1
    Key.Two, Key.NumPad2 -> ShortcutKey.NUMBER_2
    Key.Three, Key.NumPad3 -> ShortcutKey.NUMBER_3
    Key.DirectionUp -> ShortcutKey.UP
    Key.DirectionDown -> ShortcutKey.DOWN
    else -> null
}

/** The `.md` name Save As suggests: the document's name with its own
 *  extension — any of the ones md opens (`.mkd`, `.puml`, `.txt`, …), in any
 *  case — swapped for `.md`. The export suggestions below strip that `.md`
 *  again, so `Notes.mkd` exports as `Notes.pdf`, not `Notes.mkd.pdf`. */
private fun suggestedFileName(displayName: String): String =
    DocumentTypes.markdownFileName(displayName)

private fun suggestedPdfName(displayName: String): String =
    suggestedFileName(displayName).removeSuffix(".md") + ".pdf"

private fun suggestedHtmlName(displayName: String): String =
    suggestedFileName(displayName).removeSuffix(".md") + ".html"

private fun suggestedEpubName(displayName: String): String =
    suggestedFileName(displayName).removeSuffix(".md") + ".epub"

private fun suggestedTexName(displayName: String): String =
    suggestedFileName(displayName).removeSuffix(".md") + ".tex"

/** `Doc.md` → `Doc-<n>.svg`, where n is the diagram's 1-based document-order
 *  position (`ordinal + 1`) — so two diagrams from one document export to
 *  distinct files. */
private fun suggestedSvgName(displayName: String, ordinal: Int): String =
    suggestedFileName(displayName).removeSuffix(".md") + "-" + (ordinal + 1) + ".svg"

private fun suggestedTextPackName(displayName: String): String =
    suggestedFileName(displayName).removeSuffix(".md") + ".textpack"

/** The ordering prefix the bundled example files carry ("01-", "02-", …). */
private val examplePrefix = Regex("""^\d+-""")

/** Menu label for a bundled example: the file name without ".md" and
 *  without its ordering prefix ("01-Welcome.md" → "Welcome"). */
private fun exampleTitle(fileName: String): String =
    fileName.removeSuffix(".md").replace(examplePrefix, "")


/**
 * ACTION_OPEN_DOCUMENT for the types md opens, asked to start at a document
 * the reader already has on screen — the read-only badge's Edit… action.
 *
 * AndroidX's own `OpenDocument` contract takes the MIME list and nothing
 * else, with no way through to EXTRA_INITIAL_URI, so the intent is built
 * here: the same one it builds (ACTION_OPEN_DOCUMENT, CATEGORY_OPENABLE, a
 * wildcard type narrowed by EXTRA_MIME_TYPES — see DocumentTypes), plus the
 * initial URI. A provider is free to ignore that extra and many do; the
 * picker then opens wherever it always does, which is why this is a
 * courtesy and not the mechanism. The URI it returns is a fresh read-write
 * SAF grant like any other pick.
 */
private class ReopenDocument : ActivityResultContract<Uri?, Uri?>() {

    override fun createIntent(context: Context, input: Uri?): Intent =
        Intent(Intent.ACTION_OPEN_DOCUMENT)
            .addCategory(Intent.CATEGORY_OPENABLE)
            .setType("*/*")
            .putExtra(Intent.EXTRA_MIME_TYPES, DocumentTypes.OPENABLE_MIME_TYPES)
            .apply { input?.let { putExtra(DocumentsContract.EXTRA_INITIAL_URI, it) } }

    override fun parseResult(resultCode: Int, intent: Intent?): Uri? =
        intent.takeIf { resultCode == Activity.RESULT_OK }?.data
}
