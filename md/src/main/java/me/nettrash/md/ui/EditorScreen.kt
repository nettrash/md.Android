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
 * iPhone. The chosen mode is remembered across configuration changes. Mirrors
 * the iOS `DocumentView.swift`.
 *
 * Documents are opened, created and saved through the Storage Access
 * Framework (Android's document architecture). Save writes back to the
 * current URI; when there's nowhere writable yet it falls through to a
 * Create Document ("Save As") picker. The buffer is also flushed when the
 * app is backgrounded, approximating the iOS autosave.
 *
 * The app bar also carries the document's structure and the writer tools:
 * a table-of-contents action that scrolls the preview to a heading, a
 * Notes… panel listing the private `<!-- note: … -->` comments, an
 * Examples menu that opens a bundled sample document as a new untitled
 * buffer (with an Example Book… action that copies the bundled sample
 * book into a picked folder), and the book navigator (New Book… /
 * Open Book… / Show Book / Close Book) over a user-picked folder tree —
 * see `BookSheet.kt` and `book/Book.kt`.
 */

package me.nettrash.md.ui

import android.content.Intent
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
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
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Splitscreen
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.nettrash.md.DocumentViewModel
import me.nettrash.md.book.BookState
import me.nettrash.md.markdown.MarkdownParser
import me.nettrash.md.markdown.WritingStats

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditorScreen(viewModel: DocumentViewModel) {
    val context = LocalContext.current
    val dark = isSystemInDarkTheme()
    // Which modes to offer depends on the current window width; Split is only
    // shown when there's room for it (see `ViewMode.kt`). `mode` holds the raw
    // preference and survives configuration changes; `effectiveMode` coerces
    // it to fit the current width without discarding it, so Split returns when
    // the window widens again.
    val isWide = isWideLayout(LocalConfiguration.current.screenWidthDp)
    var mode by rememberSaveable { mutableStateOf(Mode.SPLIT) }
    val currentMode = effectiveMode(mode, isWide)
    var menuOpen by remember { mutableStateOf(false) }

    // Document structure, recomputed only when the text changes: the outline
    // feeds the table-of-contents action, the notes feed the Notes… panel.
    // Both parsers are line-oriented and cheap (see MarkdownParser).
    val outline = remember(viewModel.text) { MarkdownParser.outline(viewModel.text) }
    val notes = remember(viewModel.text) { MarkdownParser.notes(viewModel.text) }
    var contentsOpen by remember { mutableStateOf(false) }
    var notesOpen by remember { mutableStateOf(false) }
    var examplesOpen by remember { mutableStateOf(false) }
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

    val openLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let {
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    it,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                )
            }
            viewModel.load(it, writable = true)
        }
    }
    val createLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/markdown")
    ) { uri ->
        uri?.let {
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    it,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                )
            }
            viewModel.saveAs(it)
        }
    }
    // Export as PDF: the user picks the destination first, then the document
    // renders offscreen and the single-page PDF is written to that URI.
    val exportPdfLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/pdf")
    ) { uri ->
        uri?.let { target ->
            Exporter.renderPdf(context, viewModel.text, viewModel.displayName, dark) { bytes ->
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

    fun saveOrSaveAs() {
        if (!viewModel.save()) createLauncher.launch(suggestedFileName(viewModel.displayName))
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

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                    titleContentColor = MaterialTheme.colorScheme.onBackground,
                ),
                title = {
                    Text(
                        text = viewModel.displayName + if (viewModel.isDirty) " •" else "",
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                actions = {
                    // Table of contents: enabled once the document has any
                    // headings. Tapping an entry makes sure the preview is on
                    // screen (Edit flips to Preview; Split and Preview already
                    // show it) and scrolls it to that heading's anchor.
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
                                        if (currentMode == Mode.EDIT) mode = Mode.PREVIEW
                                        previewNavigation = PreviewNavigation(
                                            id = (previewNavigation?.id ?: 0L) + 1,
                                            slug = entry.slug,
                                        )
                                    },
                                )
                            }
                        }
                    }
                    ModeSwitch(availableModes(isWide), currentMode) { mode = it }
                    Box {
                        IconButton(onClick = { menuOpen = true }) {
                            Icon(Icons.Filled.MoreVert, contentDescription = "More")
                        }
                        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                            DropdownMenuItem(text = { Text("New") }, onClick = {
                                menuOpen = false; viewModel.newDocument()
                            })
                            DropdownMenuItem(text = { Text("Open…") }, onClick = {
                                menuOpen = false
                                openLauncher.launch(arrayOf("text/markdown", "text/plain", "application/octet-stream"))
                            })
                            // The bundled examples, in their own dropdown
                            // (anchored to this same button — see below).
                            DropdownMenuItem(text = { Text("Examples") }, onClick = {
                                menuOpen = false; examplesOpen = true
                            })
                            DropdownMenuItem(text = { Text("Save") }, onClick = {
                                menuOpen = false; saveOrSaveAs()
                            })
                            DropdownMenuItem(text = { Text("Save As…") }, onClick = {
                                menuOpen = false
                                createLauncher.launch(suggestedFileName(viewModel.displayName))
                            })
                            HorizontalDivider()
                            DropdownMenuItem(text = { Text("Share Source…") }, onClick = {
                                menuOpen = false
                                Exporter.shareSource(context, viewModel.text, viewModel.displayName)
                            })
                            DropdownMenuItem(text = { Text("Share Rendered PDF…") }, onClick = {
                                menuOpen = false
                                Exporter.sharePdf(context, viewModel.text, viewModel.displayName, dark)
                            })
                            DropdownMenuItem(text = { Text("Export as PDF…") }, onClick = {
                                menuOpen = false
                                exportPdfLauncher.launch(suggestedPdfName(viewModel.displayName))
                            })
                            HorizontalDivider()
                            // The private author notes (`<!-- note: … -->`),
                            // greyed out until the document has one.
                            DropdownMenuItem(
                                text = { Text("Notes…") },
                                enabled = notes.isNotEmpty(),
                                onClick = { menuOpen = false; notesOpen = true },
                            )
                            HorizontalDivider()
                            // The writer-mode book: create one from scratch
                            // or pick an existing folder tree, then browse it
                            // from Show Book (see BookSheet.kt). The labels
                            // match the iOS/macOS menu.
                            DropdownMenuItem(text = { Text("New Book…") }, onClick = {
                                menuOpen = false; newBookDialogOpen = true
                            })
                            DropdownMenuItem(text = { Text("Open Book…") }, onClick = {
                                menuOpen = false
                                openBookLauncher.launch(null)
                            })
                            if (bookState.treeUri != null) {
                                DropdownMenuItem(text = { Text("Show Book") }, onClick = {
                                    menuOpen = false; bookSheetOpen = true
                                })
                                DropdownMenuItem(text = { Text("Close Book") }, onClick = {
                                    menuOpen = false; bookState.close()
                                })
                            }
                            HorizontalDivider()
                            DropdownMenuItem(text = { Text("Print…") }, onClick = {
                                menuOpen = false
                                Exporter.printRendered(context, viewModel.text, viewModel.displayName, dark)
                            })
                        }
                        // The Examples list, anchored to the same More
                        // button (the main menu closes as this one opens).
                        // Picking one seeds a fresh untitled document with
                        // that sample — see openExample above.
                        DropdownMenu(expanded = examplesOpen, onDismissRequest = { examplesOpen = false }) {
                            examples.forEach { fileName ->
                                DropdownMenuItem(
                                    text = { Text(exampleTitle(fileName)) },
                                    onClick = {
                                        examplesOpen = false
                                        openExample(fileName)
                                    },
                                )
                            }
                            HorizontalDivider()
                            // The sample book lives with its fellow samples:
                            // copies the bundled book into a picked folder
                            // and opens it (see exampleBookLauncher above).
                            DropdownMenuItem(text = { Text("Example Book…") }, onClick = {
                                examplesOpen = false
                                exampleBookLauncher.launch(null)
                            })
                        }
                    }
                },
            )
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
            viewModel, currentMode, previewNavigation,
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
    // through loadAsync — the provider read must not stall the UI.
    if (bookSheetOpen) {
        BookSheet(
            book = bookState,
            onOpenArticle = { article ->
                viewModel.loadAsync(article.file.uri, writable = true)
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
    mode: Mode,
    navigation: PreviewNavigation?,
    modifier: Modifier,
) {
    when (mode) {
        Mode.EDIT -> EditorPane(viewModel, null, modifier.fillMaxSize())
        Mode.PREVIEW -> PreviewPane(viewModel, navigation, null, modifier.fillMaxSize())
        Mode.SPLIT -> BoxWithConstraints(modifier.fillMaxSize()) {
            // The pane link that makes the two halves scroll as one — a
            // plain remembered object; the panes register themselves on it.
            val scrollSync = remember { ScrollSync() }
            if (maxWidth >= 640.dp) {
                Row(Modifier.fillMaxSize()) {
                    EditorPane(viewModel, scrollSync, Modifier.weight(1f).fillMaxSize())
                    HorizontalDivider(
                        modifier = Modifier.fillMaxSize().widthIn(max = 1.dp),
                        color = MaterialTheme.colorScheme.outlineVariant,
                    )
                    PreviewPane(viewModel, navigation, scrollSync, Modifier.weight(1f).fillMaxSize())
                }
            } else {
                Column(Modifier.fillMaxSize()) {
                    EditorPane(viewModel, scrollSync, Modifier.weight(1f).fillMaxWidth())
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    PreviewPane(viewModel, navigation, scrollSync, Modifier.weight(1f).fillMaxWidth())
                }
            }
        }
    }
}

@Composable
private fun EditorPane(viewModel: DocumentViewModel, scrollSync: ScrollSync?, modifier: Modifier) {
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
        Column(Modifier.fillMaxSize().verticalScroll(scrollState)) {
            BasicTextField(
                value = viewModel.text,
                onValueChange = viewModel::onTextChange,
                modifier = Modifier
                    .fillMaxWidth()
                    .defaultMinSize(minHeight = viewportHeight)
                    .padding(16.dp),
                textStyle = TextStyle(
                    fontFamily = FontFamily.Serif,
                    fontSize = 16.sp,
                    lineHeight = 24.sp,
                    color = MaterialTheme.colorScheme.onBackground,
                ),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                keyboardOptions = KeyboardOptions(
                    autoCorrectEnabled = false,
                    capitalization = KeyboardCapitalization.Sentences,
                ),
                decorationBox = { inner ->
                    if (viewModel.text.isEmpty()) {
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
    )
}

private fun suggestedFileName(displayName: String): String {
    val base = displayName
        .removeSuffix(".md")
        .removeSuffix(".markdown")
        .ifBlank { "Untitled" }
    return "$base.md"
}

private fun suggestedPdfName(displayName: String): String =
    suggestedFileName(displayName).removeSuffix(".md") + ".pdf"

/** The ordering prefix the bundled example files carry ("01-", "02-", …). */
private val examplePrefix = Regex("""^\d+-""")

/** Menu label for a bundled example: the file name without ".md" and
 *  without its ordering prefix ("01-Welcome.md" → "Welcome"). */
private fun exampleTitle(fileName: String): String =
    fileName.removeSuffix(".md").replace(examplePrefix, "")
