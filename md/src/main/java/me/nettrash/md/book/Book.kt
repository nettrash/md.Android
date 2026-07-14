/*
 * Book.kt
 * md (Android)
 *
 * The "book" behind the writer-mode navigator: a folder the user either
 * picks once with Open Book… (the picked folder is the book) or creates from
 * scratch with New Book… (a named subfolder of a picked parent — see
 * [BookState.createBook]). Its shape is a convention, not a format — chapters
 * are the subfolders, articles are the Markdown / plain-text files
 * (`*.md`, `*.markdown`, `*.txt`), everything else in the folder is ignored.
 * Ordering follows the writer's habit of numbering drafts: names with a
 * leading integer ("01-intro", "2. setup") sort first, numerically; the rest
 * follow in Finder-style natural order — matching the iOS/macOS apps, which
 * sort with `localizedStandardCompare`.
 *
 * [BookState] holds the persistent state — the SAF tree URI plus, for a
 * created book, the subfolder name, kept in SharedPreferences alongside a
 * persistable read/write grant on the tree — and does the tree walking
 * through DocumentFile (androidx). The listing is
 * re-read every time the navigator opens: books are small, and re-listing a
 * few dozen documents beats cache invalidation.
 *
 * v1 is deliberately open-and-create only — no rename, no delete; a file
 * manager does those better. One level of chapters, like the iOS app.
 */

package me.nettrash.md.book

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.edit
import androidx.documentfile.provider.DocumentFile

/** One openable article: display [name] (file name without its extension),
 *  the raw [fileName] (management — renames and renumbering — needs the
 *  real name, and `DocumentFile.getName()` is a ContentResolver query, so
 *  it's captured once at listing time), and the [DocumentFile] backing it. */
data class BookArticle(
    val name: String,
    val fileName: String,
    val file: DocumentFile,
)

/** One chapter: a direct subfolder of the book, with its articles already
 *  filtered and ordered. */
data class BookChapter(
    val name: String,
    val directory: DocumentFile,
    val articles: List<BookArticle>,
)

/** The listed book: the [root] folder, its top-level [articles], and its
 *  [chapters] — each list already in book order. [rootName] is captured at
 *  listing time because `DocumentFile.getName()` is a ContentResolver query —
 *  the UI must render a cached string, not re-query per recomposition. */
data class BookTree(
    val root: DocumentFile,
    val rootName: String,
    val articles: List<BookArticle>,
    val chapters: List<BookChapter>,
)

/** An article read for a whole-book export: display [title] (ordering
 *  prefix and extension stripped) and its Markdown [source]. */
data class ArticleContent(val title: String, val source: String)

/** A chapter read for a whole-book export. */
data class ChapterContent(val title: String, val articles: List<ArticleContent>)

/** The whole book, read for export — the structured counterpart of the
 *  stitched [compileBook] document; the EPUB export needs the pieces. */
data class BookContent(
    val title: String,
    val articles: List<ArticleContent>,
    val chapters: List<ChapterContent>,
)

/**
 * Owns which book (SAF folder tree) is open and walks it. Constructed with
 * the application context — it outlives any single screen and must not pin
 * an Activity. `treeUri` is Compose state so the menu's "Show Book" /
 * "Close Book" items appear the moment a book is adopted.
 */
class BookState(private val context: Context) {

    /** The persisted tree URI of the current book, or null when the user has
     *  never picked one. */
    var treeUri: Uri? by mutableStateOf(restore())
        private set

    private val prefs
        get() = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private fun restore(): Uri? =
        prefs.getString(KEY_TREE_URI, null)?.let(Uri::parse)

    /** Adopt a folder picked with OpenDocumentTree: take a persistable
     *  read/write grant (so the book survives app restarts) and remember the
     *  URI. Replaces any previously opened book — including one created with
     *  [createBook], so the stale subfolder name is cleared too: an absent
     *  `book_dir` means "the tree root is the book". */
    fun adopt(uri: Uri) {
        runCatching {
            context.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
        }
        prefs.edit {
            putString(KEY_TREE_URI, uri.toString())
            remove(KEY_BOOK_DIR)
        }
        treeUri = uri
    }

    /** Create a brand-new book: a [name] subfolder of [parentTree]. SAF can
     *  only grant whole trees, so New Book… has the user pick the *parent*
     *  folder and the book is created inside it. The persistable grant is
     *  taken on that parent — a tree grant covers every descendant document,
     *  so the subfolder needs (and could get) no grant of its own; the book
     *  is persisted as parent URI + subfolder name instead. Replaces any
     *  previously opened book. Does provider I/O — call off the main thread.
     *  Provider-backed end to end, so there's no pure logic here worth a JVM
     *  test; the flow is exercised by hand. */
    fun createBook(parentTree: Uri, name: String): Boolean {
        runCatching {
            context.contentResolver.takePersistableUriPermission(
                parentTree,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
        }
        val parent = DocumentFile.fromTreeUri(context, parentTree) ?: return false
        val created = parent.createDirectory(name) ?: return false
        prefs.edit {
            putString(KEY_TREE_URI, parentTree.toString())
            // The provider may have decorated the name ("Name (1)") — store
            // what it actually created, not what was asked for.
            putString(KEY_BOOK_DIR, created.name ?: name)
        }
        treeUri = parentTree
        return true
    }

    /** Create the bundled example book: copy the `assets/examples/Example
     *  Book` tree into a [name]d subfolder of [parentTree] and open it as the
     *  active book. The same parent-tree pattern as [createBook] — the
     *  persistable grant is taken on the picked parent, the book persisted as
     *  parent URI + subfolder name — but the folder name is fixed ("Example
     *  Book", deduped with " 2", " 3"… by hand: nicer than the provider's
     *  "Example Book (1)" decoration). A copy that fails partway is deleted
     *  rather than adopted — better no book than a broken one. Does asset and
     *  provider I/O — call off the main thread. */
    fun createExampleBook(parentTree: Uri): Boolean {
        runCatching {
            context.contentResolver.takePersistableUriPermission(
                parentTree,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
        }
        val parent = DocumentFile.fromTreeUri(context, parentTree) ?: return false
        var name = EXAMPLE_BOOK_NAME
        var suffix = 2
        while (parent.findFile(name) != null) name = "$EXAMPLE_BOOK_NAME ${suffix++}"
        val created = parent.createDirectory(name) ?: return false
        val copied = runCatching { copyExampleAssets(EXAMPLE_BOOK_ASSETS, created) }
            .getOrDefault(false)
        if (!copied) {
            runCatching { created.delete() }   // best-effort; don't adopt a half-copied book
            return false
        }
        prefs.edit {
            putString(KEY_TREE_URI, parentTree.toString())
            // The provider may still have decorated the name — store what it
            // actually created, not what was asked for (as in createBook).
            putString(KEY_BOOK_DIR, created.name ?: name)
        }
        treeUri = parentTree
        return true
    }

    /** Copy the asset folder [assetPath] into [into]: subfolders become
     *  chapters, files become articles. AssetManager has no isDirectory —
     *  an entry that lists children is a folder, one that lists none is a
     *  file (no bundled example folder is empty, so the test can't misfire).
     *  Returns false on the first thing that couldn't be created; the caller
     *  cleans up. */
    private fun copyExampleAssets(assetPath: String, into: DocumentFile): Boolean {
        val assets = context.assets
        for (entry in assets.list(assetPath).orEmpty()) {
            val entryPath = "$assetPath/$entry"
            if (assets.list(entryPath).orEmpty().isNotEmpty()) {
                val chapter = into.createDirectory(entry) ?: return false
                if (!copyExampleAssets(entryPath, chapter)) return false
            } else {
                val file = into.createFile("text/markdown", entry) ?: return false
                // "wt" truncates, matching DocumentViewModel.write; some
                // providers only support plain "w" — fall back rather than
                // fail (the file was just created empty anyway).
                val stream = runCatching { context.contentResolver.openOutputStream(file.uri, "wt") }
                    .getOrNull() ?: context.contentResolver.openOutputStream(file.uri) ?: return false
                stream.use { out -> assets.open(entryPath).use { it.copyTo(out) } }
            }
        }
        return true
    }

    /** List the book afresh. Null when no book is set, or when the book can
     *  no longer be read (folder deleted or renamed, permission revoked).
     *  Does SAF queries — call it off the main thread. */
    fun loadTree(): BookTree? {
        val uri = treeUri ?: return null
        val tree = DocumentFile.fromTreeUri(context, uri) ?: return null
        // Which folder is the book: a New Book… lives in a named subfolder of
        // the granted tree (`book_dir`); with the pref absent or empty the
        // tree root itself is the book — the backward-compatible default, so
        // books stored by Open Book… before the pref existed keep working.
        // The subfolder needs no grant of its own: the tree grant covers all
        // descendants. If the subfolder vanished (renamed / deleted outside
        // the app), returning null surfaces it through the sheet's existing
        // "couldn't read the book" path — listing the parent tree instead
        // would silently show the wrong folder.
        val dir = prefs.getString(KEY_BOOK_DIR, null)
        val root = if (dir.isNullOrEmpty()) {
            tree
        } else {
            tree.findFile(dir)?.takeIf { it.isDirectory } ?: return null
        }
        if (!root.isDirectory || !root.canRead()) return null
        val children = root.listFiles()
        val chapters = children
            .filter { it.isDirectory }
            .map { dir -> BookChapter(dir.name ?: "", dir, articlesIn(dir)) }
            .sortedWith(compareBy(bookNameOrder) { it.name })
        return BookTree(root, root.name ?: "Book", articlesIn(root), chapters)
    }

    /** Forget the current book: drop the persisted URI and hand back the
     *  persistable grant (best-effort — the folder or provider may already
     *  be gone). The folder itself is untouched. */
    fun close() {
        treeUri?.let { uri ->
            runCatching {
                context.contentResolver.releasePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                )
            }
        }
        prefs.edit {
            remove(KEY_TREE_URI)
            remove(KEY_BOOK_DIR)
        }
        treeUri = null
    }

    /** Create a chapter (subfolder) under the book [root]. */
    fun createChapter(root: DocumentFile, name: String): Boolean =
        root.createDirectory(name) != null

    /** Create an article "[name].md" inside [parent] (the book root or a
     *  chapter) and seed it with a title heading, so opening it lands in a
     *  real document rather than an empty buffer. Returns false only when
     *  the file itself couldn't be created — a failed seed still leaves a
     *  perfectly usable empty article behind. */
    fun createArticle(parent: DocumentFile, name: String): Boolean {
        val file = parent.createFile("text/markdown", "$name.md") ?: return false
        runCatching {
            // "wt" truncates, matching DocumentViewModel.write.
            context.contentResolver.openOutputStream(file.uri, "wt")?.use {
                it.write("# $name\n".toByteArray(Charsets.UTF_8))
            }
        }
        return true
    }

    /** Delete [item] — an article, or a chapter folder with everything in
     *  it. `DocumentFile.delete()` happens to recurse on the common
     *  providers, but the contract doesn't promise it — the children go
     *  first, bottom-up, and the first refusal stops the walk (the caller
     *  toasts and re-lists). Does provider I/O — call off the main thread. */
    fun deleteItem(item: DocumentFile): Boolean = runCatching {
        if (item.isDirectory) {
            for (child in item.listFiles()) {
                if (!deleteItem(child)) return false
            }
        }
        item.delete()
    }.getOrDefault(false)

    /** Rename [item] (whose full on-disk name is [oldName]) so its display
     *  name becomes [newDisplay] — the ordering prefix and article extension
     *  stay put (see [renamedBookName]). False when the provider refuses:
     *  some SAF providers won't rename directories at all, and renameTo can
     *  throw as well as return false — caught here, toasted by the caller.
     *  Does provider I/O — call off the main thread. */
    fun renameItem(item: DocumentFile, oldName: String, newDisplay: String): Boolean {
        val newName = renamedBookName(oldName, newDisplay)
        if (newName == oldName) return true
        return runCatching { item.renameTo(newName) }.getOrDefault(false)
    }

    /** Materialize a Move Up / Move Down: run [planMove] over [siblings]
     *  (name to file, in displayed order) and apply each rename. SAF has no
     *  transaction, so a refusal mid-plan just reports false — the caller
     *  toasts and re-lists, showing whatever actually landed. Does provider
     *  I/O — call off the main thread. */
    fun applyMove(siblings: List<Pair<String, DocumentFile>>, from: Int, to: Int): Boolean {
        val byName = siblings.toMap()
        var allRenamed = true
        for ((oldName, newName) in planMove(siblings.map { it.first }, from, to)) {
            val renamed = runCatching { byName.getValue(oldName).renameTo(newName) }
                .getOrDefault(false)
            if (!renamed) allRenamed = false
        }
        return allRenamed
    }

    /** Read every article of [tree] and compile the whole book into one
     *  Markdown document — title page, root articles, then each chapter as
     *  a heading page followed by its articles (the pure assembly is
     *  [compileBook]). Headings carry the display names, ordering prefixes
     *  stripped. Null when any article can't be read — a book PDF with a
     *  silently missing article would be worse than a toast. Does provider
     *  I/O — call off the main thread. */
    fun compileForExport(tree: BookTree): String? {
        val rootArticles = tree.articles.map { readArticle(it.file) ?: return null }
        val chapters = tree.chapters.map { chapter ->
            editableBookName(chapter.name) to chapter.articles.map { readArticle(it.file) ?: return null }
        }
        return compileBook(editableBookName(tree.rootName), rootArticles, chapters)
    }

    /** Read every article of [tree] with its display title, keeping the
     *  book's structure — what Export as EPUB consumes (compare
     *  [compileForExport], which stitches one document for the PDF). Null
     *  when any article can't be read. Does provider I/O — call off the
     *  main thread. */
    fun readBook(tree: BookTree): BookContent? {
        val articles = tree.articles.map {
            ArticleContent(editableBookName(it.fileName), readArticle(it.file) ?: return null)
        }
        val chapters = tree.chapters.map { chapter ->
            ChapterContent(
                editableBookName(chapter.name),
                chapter.articles.map {
                    ArticleContent(editableBookName(it.fileName), readArticle(it.file) ?: return null)
                },
            )
        }
        return BookContent(editableBookName(tree.rootName), articles, chapters)
    }

    /** The article's text, or null when it can't be read. Book articles are
     *  UTF-8 by convention — the app only ever writes UTF-8. */
    private fun readArticle(file: DocumentFile): String? = runCatching {
        context.contentResolver.openInputStream(file.uri)?.use {
            it.readBytes().toString(Charsets.UTF_8)
        }
    }.getOrNull()

    /** The article files directly inside [directory], in book order. */
    private fun articlesIn(directory: DocumentFile): List<BookArticle> =
        directory.listFiles()
            .filter { it.isFile && isArticleName(it.name) }
            .map { file ->
                val fileName = file.name ?: ""
                BookArticle(articleTitle(fileName), fileName, file)
            }
            .sortedWith(compareBy(bookNameOrder) { it.name })

    companion object {
        private const val PREFS_NAME = "book"
        private const val KEY_TREE_URI = "tree_uri"
        /** Display name of the bundled example book — also the name of its
         *  folder under assets. */
        private const val EXAMPLE_BOOK_NAME = "Example Book"
        /** Where the example book ships inside the APK's assets. */
        private const val EXAMPLE_BOOK_ASSETS = "examples/$EXAMPLE_BOOK_NAME"
        /** Name of the book subfolder inside the granted tree, set only by
         *  [createBook]. Absent/empty = the tree root is the book. */
        private const val KEY_BOOK_DIR = "book_dir"
    }
}

/** The file extensions that count as articles. */
private val ARTICLE_EXTENSIONS = setOf("md", "markdown", "txt")

/** Whether a file with this name is an article (by extension, any case). */
internal fun isArticleName(name: String?): Boolean {
    name ?: return false
    return name.substringAfterLast('.', "").lowercase() in ARTICLE_EXTENSIONS
}

/** The display title of an article file: its name without the extension
 *  ("02-the-storm.md" → "02-the-storm"). */
internal fun articleTitle(name: String): String = name.substringBeforeLast('.')

// ---- Management: rename / renumber planning (pure — the I/O lives in
// BookState.renameItem / applyMove) --------------------------------------

/** The characters that may separate an ordering prefix's digits from the
 *  name proper: "01-intro", "2. setup", "3_draft", "4 finale". */
private const val PREFIX_SEPARATORS = "-. _"

/** The ordering prefix of a [base] name (extension already stripped): the
 *  leading ASCII digits plus the separator run after them ("01-intro" →
 *  "01-", "2. setup" → "2. "). Empty when the name isn't numbered, when
 *  the digits run straight into letters ("3rd party" is a name, not an
 *  ordering), or when nothing would remain after stripping ("2026" — the
 *  digits ARE the name). */
internal fun orderPrefix(base: String): String {
    val digits = base.takeWhile(::isAsciiDigit)
    if (digits.isEmpty()) return ""
    var end = digits.length
    while (end < base.length && base[end] in PREFIX_SEPARATORS) end++
    if (end == digits.length || end == base.length) return ""
    return base.substring(0, end)
}

/** [name] split for management: ordering prefix (may be ""), the editable
 *  display stem, and the extension with its dot ("" unless the name is an
 *  article's — a chapter folder called "v1.2" keeps its dot in the stem). */
internal fun splitBookName(name: String): Triple<String, String, String> {
    val extension = if (isArticleName(name)) name.substring(name.lastIndexOf('.')) else ""
    val base = name.dropLast(extension.length)
    val prefix = orderPrefix(base)
    return Triple(prefix, base.substring(prefix.length), extension)
}

/** What the Rename… dialog pre-fills: [name] without its ordering prefix
 *  and article extension ("02-The Storm.md" → "The Storm"). */
internal fun editableBookName(name: String): String = splitBookName(name).second

/** The on-disk name after a Rename…: [newDisplay] slotted between the
 *  ordering prefix and extension of [oldName] ("02-Old.md" + "New" →
 *  "02-New.md", "notes.txt" + "ideas" → "ideas.txt"). */
internal fun renamedBookName(oldName: String, newDisplay: String): String {
    val (prefix, _, extension) = splitBookName(oldName)
    return prefix + newDisplay + extension
}

/** [name] renumbered to 1-based [position] in its sibling group: the
 *  ordering prefix replaced — or, for an unprefixed name, added — as a
 *  zero-padded two-digit "NN-", display stem and extension untouched
 *  ("intro.md" at 3 → "03-intro.md"). Built without String.format, whose
 *  digits follow the default locale. */
internal fun renumberedBookName(name: String, position: Int): String {
    val (_, stem, extension) = splitBookName(name)
    return position.toString().padStart(2, '0') + "-" + stem + extension
}

/** Whether [name] can be a book item's display name: non-blank and free of
 *  the path / URI-authority separators SAF providers reject or mangle. */
internal fun isValidBookName(name: String): Boolean =
    name.isNotBlank() && name.none { it == '/' || it == ':' }

/** Plan a Move Up / Move Down: the item at [from] among [names] (one
 *  sibling group — a chapter's articles, the root articles, or the
 *  chapters — in displayed order) moves to [to], and the whole group is
 *  renumbered "01-", "02-", … so the new order is material on disk (and
 *  survives any file manager). Returns the (oldName, newName) rename
 *  pairs, skipping names that are already right; empty when the move is
 *  impossible. Pure — the I/O lives in [BookState.applyMove]. */
internal fun planMove(names: List<String>, from: Int, to: Int): List<Pair<String, String>> {
    if (from == to || from !in names.indices || to !in names.indices) return emptyList()
    val reordered = names.toMutableList().apply { add(to, removeAt(from)) }
    return reordered.mapIndexedNotNull { index, name ->
        val renumbered = renumberedBookName(name, index + 1)
        if (renumbered == name) null else name to renumbered
    }
}

/** Compile a whole book into one Markdown document, pure and testable —
 *  the I/O (reading the articles) lives in [BookState.compileForExport].
 *  The shape: a title page (`# bookName`), then the root [rootArticles]
 *  in book order, then each of [chapters] (display name to its articles'
 *  sources) as a `# heading` page followed by its articles — with a
 *  `\newpage` between every unit, so the title page, every chapter
 *  heading and every article starts on a fresh page. Article sources are
 *  included verbatim (`---` stays an ordinary thematic break). */
internal fun compileBook(
    bookName: String,
    rootArticles: List<String>,
    chapters: List<Pair<String, List<String>>>,
): String {
    val units = mutableListOf("# $bookName")
    units.addAll(rootArticles)
    for ((chapterName, articles) in chapters) {
        units.add("# $chapterName")
        units.addAll(articles)
    }
    return units.joinToString("\n\n\\newpage\n\n")
}

/** Whether [ch] is an ASCII digit. Deliberately narrower than
 *  `Char.isDigit()` (any Unicode Nd digit): the numbering convention — and
 *  Swift's digit handling in `localizedStandardCompare` on the sibling
 *  apps — recognises only '0'..'9'. */
private fun isAsciiDigit(ch: Char): Boolean = ch in '0'..'9'

/** The leading integer prefix of a name ("01-intro" → 1, "2. setup" → 2),
 *  or null when it doesn't start with an ASCII digit. A digit run too long
 *  for a Long is treated as unnumbered rather than crashing the sort. */
internal fun leadingNumber(name: String): Long? {
    val digits = name.takeWhile(::isAsciiDigit)
    if (digits.isEmpty()) return null
    return digits.toLongOrNull()
}

/**
 * Finder-style natural comparison — the Android stand-in for the
 * `localizedStandardCompare` the iOS/macOS apps sort with. The names are
 * walked as alternating runs of digits and non-digits: digit runs compare
 * numerically ("part2" before "part10"), everything else char by char,
 * case-insensitively. Ties (differing only in case or zero-padding) fall
 * back to a plain lexicographic compare so the order is deterministic.
 */
internal fun naturalCompare(a: String, b: String): Int {
    var i = 0
    var j = 0
    while (i < a.length && j < b.length) {
        if (isAsciiDigit(a[i]) && isAsciiDigit(b[j])) {
            // Bound both digit runs, skip their leading zeros, and compare
            // by significant length first, then digit by digit — never
            // parsed into a number, so run length can't overflow anything.
            var ea = i; while (ea < a.length && isAsciiDigit(a[ea])) ea++
            var eb = j; while (eb < b.length && isAsciiDigit(b[eb])) eb++
            var sa = i; while (sa < ea - 1 && a[sa] == '0') sa++
            var sb = j; while (sb < eb - 1 && b[sb] == '0') sb++
            val byLength = (ea - sa).compareTo(eb - sb)
            if (byLength != 0) return byLength
            while (sa < ea) {
                val byDigit = a[sa].compareTo(b[sb])
                if (byDigit != 0) return byDigit
                sa++; sb++
            }
            i = ea; j = eb
        } else {
            val byChar = a[i].lowercaseChar().compareTo(b[j].lowercaseChar())
            if (byChar != 0) return byChar
            i++; j++
        }
    }
    val byRemainder = (a.length - i).compareTo(b.length - j)
    return if (byRemainder != 0) byRemainder else a.compareTo(b)
}

/**
 * Book ordering for chapter and article names: numbered names first, by
 * their number ("2. setup" before "10-deploy" — numeric, not lexicographic),
 * then the unnumbered rest in natural order ("part2" before "part10").
 * [naturalCompare] also breaks numeric-prefix ties.
 */
internal val bookNameOrder: Comparator<String> = Comparator { a, b ->
    val na = leadingNumber(a)
    val nb = leadingNumber(b)
    when {
        na != null && nb != null -> {
            val byNumber = na.compareTo(nb)
            if (byNumber != 0) byNumber else naturalCompare(a, b)
        }
        na != null -> -1
        nb != null -> 1
        else -> naturalCompare(a, b)
    }
}
