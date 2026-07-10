/*
 * BookSheet.kt
 * md (Android)
 *
 * The book navigator: a modal bottom sheet listing the open book — its
 * top-level articles first, then each chapter as a header with that
 * chapter's articles indented beneath it. Tapping an article opens it in
 * the editor; "New Article…" rows (one at the root, one per chapter) and
 * the trailing "New Chapter…" row grow the book in place through a small
 * name dialog. Every chapter and article row also carries an overflow
 * menu with Rename… / Move Up / Move Down / Delete… — renames keep the
 * ordering prefix and extension, moves renumber the whole sibling group
 * so the order is material on disk (see the planning helpers in
 * `book/Book.kt`). The book's own header row offers Share as PDF /
 * Export as PDF… — the whole book compiled into one document (title page,
 * chapters, articles, each on a fresh page; BookState.compileForExport)
 * and rendered through the same layout-aware PDF pipeline as a document —
 * and Export as EPUB…, a real EPUB 3 with the same reading order and
 * rich content rendered to images (book/Epub.kt + EpubExporter).
 * The listing is re-read from the SAF tree every time the sheet opens and
 * after every change.
 */

package me.nettrash.md.ui

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.nettrash.md.book.BookArticle
import me.nettrash.md.book.BookState
import me.nettrash.md.book.BookTree
import me.nettrash.md.book.editableBookName
import me.nettrash.md.book.isValidBookName
import me.nettrash.md.book.renamedBookName

/** What the name dialog is creating: a chapter at the book root, or an
 *  article inside [Article.parent] (the root or a chapter folder). */
private sealed interface Creation {
    data object Chapter : Creation
    data class Article(val parent: DocumentFile) : Creation
}

/** A pending management action from a row's overflow menu, awaiting its
 *  dialog. [Delete] confirms first (chapters warn about their contents);
 *  [Rename] pre-fills the editable display name and carries the sibling
 *  file names for the collision check. Moves need no dialog. */
private sealed interface Management {
    data class Delete(val label: String, val isChapter: Boolean, val item: DocumentFile) : Management
    data class Rename(val fileName: String, val item: DocumentFile, val siblings: List<String>) : Management
}

/** One row's management actions, behind its overflow icon. A null move is
 *  impossible (the first row can't go up, the last can't go down) and
 *  renders disabled. */
private class RowActions(
    val onRename: () -> Unit,
    val onMoveUp: (() -> Unit)?,
    val onMoveDown: (() -> Unit)?,
    val onDelete: () -> Unit,
)

/**
 * The book navigator sheet. [onOpenArticle] receives the tapped article
 * (the caller loads it and dismisses); [onDismiss] closes the sheet.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BookSheet(
    book: BookState,
    onOpenArticle: (BookArticle) -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val dark = isSystemInDarkTheme()

    // The listing, re-read lazily: `generation` bumps after every creation,
    // and the sheet itself is only composed while open, so each opening
    // starts from a fresh LaunchedEffect. `loaded` separates "still listing"
    // from "listed and found nothing / unreadable".
    var tree by remember { mutableStateOf<BookTree?>(null) }
    var loaded by remember { mutableStateOf(false) }
    var generation by remember { mutableIntStateOf(0) }
    LaunchedEffect(generation) {
        tree = withContext(Dispatchers.IO) { book.loadTree() }
        loaded = true
    }

    var creation by remember { mutableStateOf<Creation?>(null) }
    var management by remember { mutableStateOf<Management?>(null) }

    fun create(request: Creation, name: String) {
        // Dismiss the dialog synchronously, before the IO work runs — a
        // Create button left live during the write invites a double-tap,
        // and SAF quietly auto-renames the second copy to "Name (1)"
        // instead of failing.
        val root = tree?.root
        creation = null
        scope.launch {
            val created = withContext(Dispatchers.IO) {
                when (request) {
                    is Creation.Chapter ->
                        root?.let { book.createChapter(it, name) } ?: false
                    is Creation.Article ->
                        book.createArticle(request.parent, name)
                }
            }
            if (!created) {
                Toast.makeText(context, "Couldn't create \"$name\".", Toast.LENGTH_LONG).show()
            }
            generation++    // re-list either way; the tree may have moved under us
        }
    }

    // The management actions. All three mirror create(): dialog (if any)
    // dismissed synchronously, the provider I/O on Dispatchers.IO, failures
    // as a toast, and a re-list either way — the tree is the truth.

    fun delete(request: Management.Delete) {
        scope.launch {
            val deleted = withContext(Dispatchers.IO) { book.deleteItem(request.item) }
            if (!deleted) {
                Toast.makeText(context, "Couldn't delete \"${request.label}\".", Toast.LENGTH_LONG).show()
            }
            generation++
        }
    }

    fun rename(request: Management.Rename, newDisplay: String) {
        // The collision check is pure — the sibling names were cached at
        // listing time — so it runs before any I/O is dispatched.
        val newName = renamedBookName(request.fileName, newDisplay)
        if (newName != request.fileName && request.siblings.contains(newName)) {
            Toast.makeText(context, "\"$newName\" already exists.", Toast.LENGTH_LONG).show()
            return
        }
        scope.launch {
            val renamed = withContext(Dispatchers.IO) {
                book.renameItem(request.item, request.fileName, newDisplay)
            }
            if (!renamed) {
                // Provider refusal — some SAF providers won't rename
                // directories at all.
                Toast.makeText(context, "Couldn't rename \"${request.fileName}\".", Toast.LENGTH_LONG).show()
            }
            generation++
        }
    }

    fun move(siblings: List<Pair<String, DocumentFile>>, from: Int, to: Int) {
        scope.launch {
            val moved = withContext(Dispatchers.IO) { book.applyMove(siblings, from, to) }
            if (!moved) {
                Toast.makeText(context, "Couldn't reorder.", Toast.LENGTH_LONG).show()
            }
            generation++
        }
    }

    /** The overflow actions for the row at [index] among [siblings] (its
     *  own displayed sibling group — root articles, a chapter's articles,
     *  or the chapters). [label] names the item in dialogs and toasts;
     *  [fileName] is its raw on-disk name. */
    fun rowActions(
        label: String,
        fileName: String,
        item: DocumentFile,
        isChapter: Boolean,
        siblings: List<Pair<String, DocumentFile>>,
        index: Int,
    ) = RowActions(
        onRename = {
            management = Management.Rename(fileName, item, siblings.map { it.first })
        },
        onMoveUp = if (index > 0) {
            { move(siblings, index, index - 1) }
        } else null,
        onMoveDown = if (index < siblings.size - 1) {
            { move(siblings, index, index + 1) }
        } else null,
        onDelete = { management = Management.Delete(label, isChapter, item) },
    )

    // Share the whole book as one PDF: compile it on IO (title page +
    // chapters + articles, each on its own page — see compileBook), then
    // hand the combined Markdown to the same share pipeline a document
    // uses; Exporter honors the PDF layout setting and toasts render
    // failures itself.
    fun shareBookPdf() {
        val current = tree ?: return
        scope.launch {
            val compiled = withContext(Dispatchers.IO) { book.compileForExport(current) }
            if (compiled == null) {
                Toast.makeText(context, "Couldn't read the whole book.", Toast.LENGTH_LONG).show()
                return@launch
            }
            Exporter.sharePdf(context, compiled, editableBookName(current.rootName), dark)
        }
    }

    // Export the compiled book as a PDF: the destination is picked first
    // (like the editor's Export as PDF…), then the book is read on IO,
    // rendered through the layout-aware pipeline, and written to the
    // picked URI.
    val exportBookLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/pdf")
    ) { uri ->
        val current = tree
        if (uri == null || current == null) return@rememberLauncherForActivityResult
        scope.launch {
            val compiled = withContext(Dispatchers.IO) { book.compileForExport(current) }
            if (compiled == null) {
                Toast.makeText(context, "Couldn't read the whole book.", Toast.LENGTH_LONG).show()
                return@launch
            }
            Exporter.renderPdf(context, compiled, editableBookName(current.rootName), dark) { bytes ->
                val written = bytes != null && runCatching {
                    // "wt" truncates; some providers only support plain "w"
                    // — fall back rather than fail (see the editor's export
                    // path; CreateDocument made the file empty anyway).
                    val stream = runCatching { context.contentResolver.openOutputStream(uri, "wt") }
                        .getOrNull() ?: context.contentResolver.openOutputStream(uri)
                    stream!!.use { it.write(bytes) }
                }.isSuccess
                if (!written) {
                    Toast.makeText(context, "Couldn't export the PDF.", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    // Export the book as an EPUB 3: destination first, then the book is
    // read on IO (structured — see BookState.readBook), assembled and
    // rendered by EpubExporter (rich content becomes images), and the
    // archive bytes written to the picked URI.
    val exportEpubLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/epub+zip")
    ) { uri ->
        val current = tree
        if (uri == null || current == null) return@rememberLauncherForActivityResult
        scope.launch {
            val content = withContext(Dispatchers.IO) { book.readBook(current) }
            if (content == null) {
                Toast.makeText(context, "Couldn't read the whole book.", Toast.LENGTH_LONG).show()
                return@launch
            }
            EpubExporter.export(context, content) { bytes ->
                val written = bytes != null && runCatching {
                    // The same "wt"-then-"w" fallback as the PDF exports.
                    val stream = runCatching { context.contentResolver.openOutputStream(uri, "wt") }
                        .getOrNull() ?: context.contentResolver.openOutputStream(uri)
                    stream!!.use { it.write(bytes) }
                }.isSuccess
                if (!written) {
                    Toast.makeText(context, "Couldn't export the EPUB.", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        val current = tree
        when {
            !loaded -> SheetMessage("Reading the book…")
            current == null -> SheetMessage(
                "Couldn't read the book folder. It may have been moved or deleted — pick it again with Open Book…"
            )
            // navigationBarsPadding keeps the last row tappable above the
            // gesture bar (a no-op when the sheet has already consumed it).
            else -> LazyColumn(Modifier.navigationBarsPadding()) {
                // The book itself, then its loose articles, then a root-level
                // "New Article…", then the chapters — mirroring the on-disk
                // shape top to bottom. The header renders the name cached in
                // loadTree(): DocumentFile.getName() is a ContentResolver
                // query and must not run per recomposition.
                item {
                    BookHeader(
                        current.rootName,
                        onSharePdf = { shareBookPdf() },
                        onExportPdf = {
                            exportBookLauncher.launch(editableBookName(current.rootName) + ".pdf")
                        },
                        onExportEpub = {
                            exportEpubLauncher.launch(editableBookName(current.rootName) + ".epub")
                        },
                    )
                }
                // Each row's sibling group (for Move Up / Move Down and the
                // rename collision check), in the same displayed order.
                val rootSiblings = current.articles.map { it.fileName to it.file }
                val chapterSiblings = current.chapters.map { it.name to it.directory }
                itemsIndexed(current.articles, key = { _, it -> it.file.uri.toString() }) { index, article ->
                    ArticleRow(
                        article,
                        indent = 0.dp,
                        actions = rowActions(
                            article.name, article.fileName, article.file,
                            isChapter = false, siblings = rootSiblings, index = index,
                        ),
                    ) { onOpenArticle(article) }
                }
                item {
                    CreateRow("New Article…", indent = 0.dp) {
                        creation = Creation.Article(current.root)
                    }
                }
                itemsIndexed(current.chapters, key = { _, it -> it.directory.uri.toString() }) { index, chapter ->
                    val articleSiblings = chapter.articles.map { it.fileName to it.file }
                    ChapterSection(
                        chapter.name,
                        chapter.articles,
                        actions = rowActions(
                            chapter.name, chapter.name, chapter.directory,
                            isChapter = true, siblings = chapterSiblings, index = index,
                        ),
                        articleActions = { i, article ->
                            rowActions(
                                article.name, article.fileName, article.file,
                                isChapter = false, siblings = articleSiblings, index = i,
                            )
                        },
                        onOpenArticle = onOpenArticle,
                        onNewArticle = { creation = Creation.Article(chapter.directory) },
                    )
                }
                item { CreateRow("New Chapter…", indent = 0.dp) { creation = Creation.Chapter } }
            }
        }
    }

    creation?.let { request ->
        NameDialog(
            title = when (request) {
                is Creation.Chapter -> "New Chapter"
                is Creation.Article -> "New Article"
            },
            onCancel = { creation = null },
            onCreate = { name -> create(request, name) },
        )
    }

    // The management dialogs. Like create(), both dismiss synchronously in
    // the confirm handler, before the I/O runs.
    when (val request = management) {
        is Management.Delete -> AlertDialog(
            onDismissRequest = { management = null },
            title = { Text("Delete \"${request.label}\"?") },
            text = {
                Text(
                    if (request.isChapter) "The chapter and every article in it will be deleted."
                    else "The article will be deleted."
                )
            },
            confirmButton = {
                TextButton(onClick = { management = null; delete(request) }) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = { management = null }) { Text("Cancel") }
            },
        )
        is Management.Rename -> NameDialog(
            title = "Rename",
            initialName = editableBookName(request.fileName),
            confirmLabel = "Rename",
            onCancel = { management = null },
            onCreate = { name ->
                management = null
                rename(request, name)
            },
        )
        null -> {}
    }
}

/** One chapter: its header followed by its articles and a chapter-scoped
 *  "New Article…", all indented one level. [actions] manage the chapter
 *  itself; [articleActions] build each article row's from its index in
 *  this chapter's displayed order. */
@Composable
private fun ChapterSection(
    name: String,
    articles: List<BookArticle>,
    actions: RowActions,
    articleActions: (Int, BookArticle) -> RowActions,
    onOpenArticle: (BookArticle) -> Unit,
    onNewArticle: () -> Unit,
) {
    Column(Modifier.fillMaxWidth()) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                text = name,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .weight(1f)
                    .padding(start = 24.dp, end = 4.dp, top = 16.dp, bottom = 4.dp),
            )
            RowOverflow(actions, modifier = Modifier.padding(end = 12.dp, top = 12.dp))
        }
        articles.forEachIndexed { index, article ->
            ArticleRow(article, indent = 16.dp, actions = articleActions(index, article)) {
                onOpenArticle(article)
            }
        }
        CreateRow("New Article…", indent = 16.dp, onClick = onNewArticle)
    }
}

/** The book's name row, with the whole-book actions behind a trailing
 *  overflow: Share as PDF / Export as PDF… over the compiled book, and
 *  Export as EPUB… over the structured one. */
@Composable
private fun BookHeader(
    name: String,
    onSharePdf: () -> Unit,
    onExportPdf: () -> Unit,
    onExportEpub: () -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            text = name,
            style = MaterialTheme.typography.titleMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .weight(1f)
                .padding(start = 24.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
        )
        Box(Modifier.padding(end = 12.dp)) {
            IconButton(onClick = { open = true }) {
                Icon(
                    Icons.Filled.MoreVert,
                    contentDescription = "Book actions",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                DropdownMenuItem(text = { Text("Share as PDF") }, onClick = {
                    open = false; onSharePdf()
                })
                DropdownMenuItem(text = { Text("Export as PDF…") }, onClick = {
                    open = false; onExportPdf()
                })
                DropdownMenuItem(text = { Text("Export as EPUB…") }, onClick = {
                    open = false; onExportEpub()
                })
            }
        }
    }
}

@Composable
private fun ArticleRow(article: BookArticle, indent: Dp, actions: RowActions, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
    ) {
        Text(
            text = article.name,
            style = MaterialTheme.typography.bodyLarge,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .weight(1f)
                .padding(start = 24.dp + indent, end = 4.dp, top = 12.dp, bottom = 12.dp),
        )
        RowOverflow(actions, modifier = Modifier.padding(end = 12.dp))
    }
}

/** The per-row overflow: a small trailing icon opening the Rename… /
 *  Move Up / Move Down / Delete… menu. Impossible moves (null in
 *  [RowActions]) render disabled rather than vanish, so the menu doesn't
 *  jump around between rows. */
@Composable
private fun RowOverflow(actions: RowActions, modifier: Modifier = Modifier) {
    var open by remember { mutableStateOf(false) }
    Box(modifier) {
        IconButton(onClick = { open = true }) {
            Icon(
                Icons.Filled.MoreVert,
                contentDescription = "Manage",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(text = { Text("Rename…") }, onClick = {
                open = false; actions.onRename()
            })
            DropdownMenuItem(
                text = { Text("Move Up") },
                enabled = actions.onMoveUp != null,
                onClick = { open = false; actions.onMoveUp?.invoke() },
            )
            DropdownMenuItem(
                text = { Text("Move Down") },
                enabled = actions.onMoveDown != null,
                onClick = { open = false; actions.onMoveDown?.invoke() },
            )
            HorizontalDivider()
            DropdownMenuItem(text = { Text("Delete…") }, onClick = {
                open = false; actions.onDelete()
            })
        }
    }
}

@Composable
private fun CreateRow(label: String, indent: Dp, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(start = 24.dp + indent, end = 24.dp, top = 12.dp, bottom = 12.dp),
    ) {
        Icon(
            Icons.Filled.Add,
            contentDescription = null,   // the label says it all
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(18.dp),
        )
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(start = 8.dp),
        )
    }
}

@Composable
private fun SheetMessage(message: String) {
    Text(
        text = message,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 32.dp),
    )
}

/** Ask for a chapter / article / book name (an article's is used both as
 *  the file name, with ".md" appended, and as the seeded `# title`) — or,
 *  with [confirmLabel] = "Rename", the new display name of an existing
 *  item. Blank names and names with the separators SAF rejects ('/', ':')
 *  keep the confirm button disabled (see isValidBookName). Internal
 *  because EditorScreen's New Book… flow shares it, seeding [initialName]
 *  with a suggested book name. */
@Composable
internal fun NameDialog(
    title: String,
    initialName: String = "",
    confirmLabel: String = "Create",
    onCancel: () -> Unit,
    onCreate: (String) -> Unit,
) {
    var name by remember { mutableStateOf(initialName) }
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                singleLine = true,
                label = { Text("Name") },
            )
        },
        confirmButton = {
            TextButton(enabled = isValidBookName(name), onClick = { onCreate(name.trim()) }) {
                Text(confirmLabel)
            }
        },
        dismissButton = {
            TextButton(onClick = onCancel) { Text("Cancel") }
        },
    )
}
