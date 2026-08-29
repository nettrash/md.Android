/*
 * LaTeXExport.kt
 * md (Android)
 *
 * Serializes the parsed block model to LaTeX source — the `.tex` export.
 * A faithful Kotlin port of the iOS / macOS `LaTeXExport.swift`, down to
 * the escaping table and the order of the inline passes.
 *
 * Every other output md produces turns a formula into a picture (PDF,
 * EPUB, print) or into KaTeX markup (HTML). This one hands the
 * mathematics back as the `$…$` the author wrote, ready to drop into a
 * paper and keep editing. That is the whole point of the format, and it
 * is why a math span — alone among everything here — is copied through
 * untouched while every other scrap of author text is escaped.
 *
 * Pure string work: no Android types, no WebView, nothing asynchronous,
 * so the whole of it is covered by the JVM unit tests. The file writing
 * and the SAF picker live in `ui/Exporter`.
 *
 * Where LaTeX cannot do what the preview does, it says so instead of
 * dropping: a diagram fence keeps its source in a `verbatim` block under
 * a comment naming the language, and a footnote definition nothing
 * references is still printed at the end.
 */

package me.nettrash.md.markdown

import me.nettrash.md.book.ArticleContent
import me.nettrash.md.book.BookContent
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.util.Locale

object LaTeXExport {

    // MARK: - Entry points

    /** One document as a standalone `article` .tex file.
     *
     *  The result has no title block unless the document's front matter
     *  gives one: an invented `\title` would be a line the author never
     *  wrote, and a file name is not part of a document. */
    fun document(source: String): String {
        val blocks = MarkdownParser.parse(withoutSentinels(source))
        val writer = Writer(headingOffset = 0)
        val body = writer.renderUnit(blocks)
        return assemble("article", writer, titleBlock(fields(blocks)), body)
    }

    /** A whole book as a `book` .tex file: each chapter a `\chapter`, each
     *  article a `\section`, in the reading order the PDF compile and the
     *  EPUB already use — root articles first, then the chapters, each
     *  chapter's articles in order (compare `compileBook` and
     *  `EpubExporter.prepare`).
     *
     *  An article's own headings drop one level (`#` becomes
     *  `\subsection`) so they nest *under* the `\section` the article
     *  itself is, instead of becoming its siblings. */
    fun book(content: BookContent): String {
        val writer = Writer(headingOffset = 1)
        val parts = ArrayList<String>()

        fun append(article: ArticleContent) {
            parts.add("\\section{${escape(withoutSentinels(article.title))}}")
            val body = writer.renderUnit(MarkdownParser.parse(withoutSentinels(article.source)))
            if (body.isNotEmpty()) parts.add(body)
        }

        content.articles.forEach(::append)
        for (chapter in content.chapters) {
            // `book` resets the footnote counter at every chapter, so the
            // numbers `\footnotemark` cites have to restart with it.
            writer.startChapter()
            parts.add("\\chapter{${escape(withoutSentinels(chapter.title))}}")
            chapter.articles.forEach(::append)
        }

        // A book has a title page in every other export, so it keeps one
        // here. `\date{}` rather than no date at all: `\maketitle` would
        // otherwise stamp today, which nobody wrote.
        val title = listOf("\\title{${escape(withoutSentinels(content.title))}}", "\\date{}")
        return assemble("book", writer, title, parts.joinToString("\n\n"))
    }

    // MARK: - Preamble

    /** Wrap a rendered body in its document class, packages and title block.
     *
     *  Only the packages the document actually uses are emitted — a plain
     *  document should compile with a preamble short enough to read at a
     *  glance — and each flag was set by the renderer as it emitted the
     *  command that needs it, rather than by scanning the finished text:
     *  a code block quoting `\href` is not a link. */
    private fun assemble(
        documentClass: String,
        writer: Writer,
        titleBlock: List<String>,
        body: String,
    ): String {
        val lines = arrayListOf("\\documentclass{$documentClass}", "\\usepackage[utf8]{inputenc}")

        // T2A carries the Cyrillic glyphs, and it has to be the *last*
        // encoding listed: `fontenc` makes the last one the document
        // default, and T2A holds the Latin alphabet as well, so this order
        // leaves English untouched while T1 alone would leave Russian to
        // fail character by character. Only a document that actually has
        // Cyrillic in it pays for the switch.
        if (containsCyrillic(body) || titleBlock.any(::containsCyrillic)) {
            lines.add("\\usepackage[T1,T2A]{fontenc}")
        }
        // amsmath is where the mathematics actually lives: `aligned`,
        // `\text`, `\substack`, and the sane spacing around a display. A
        // document with a formula in it loads amsmath whatever the formula
        // says, because guessing which constructs the author reached for
        // means a compile that stops at the first one that was not guessed
        // ("LaTeX Error: Environment aligned undefined"), and it stops the
        // whole file rather than the one formula.
        if (writer.needsAmsmath) lines.add("\\usepackage{amsmath}")
        if (writer.needsGraphicx) lines.add("\\usepackage{graphicx}")
        // `normalem` is not optional: plain `ulem` redefines `\emph` to
        // underline, so loading it for one struck-through word would
        // quietly underline every italic in the document.
        if (writer.needsUlem) lines.add("\\usepackage[normalem]{ulem}")
        // Every table is a `longtable`; see [Writer.renderTable] for why
        // nothing here is a float.
        if (writer.needsLongtable) lines.add("\\usepackage{longtable}")
        // hyperref last — it redefines enough of LaTeX's internals that it
        // is the one package with a documented loading order.
        if (writer.needsHyperref) lines.add("\\usepackage{hyperref}")

        lines.addAll(titleBlock)
        lines.add("\\begin{document}")
        if (titleBlock.isNotEmpty()) lines.add("\\maketitle")
        lines.add("")
        lines.add(body)
        lines.add("")
        lines.add("\\end{document}")
        return lines.joinToString("\n") + "\n"
    }

    /** The document's front-matter fields, first occurrence winning — the
     *  same rule the rest of the app applies to a repeated key. Keys are
     *  lowercased against `Locale.ROOT`, never the device's: in a Turkish
     *  locale `TITLE` folds to `tıtle` and the title block would vanish on
     *  that phone alone. */
    private fun fields(blocks: List<MarkdownBlock>): Map<String, String> {
        val fields = HashMap<String, String>()
        for (block in blocks.filterIsInstance<MarkdownBlock.FrontMatter>()) {
            for (field in block.fields) {
                val key = field.key.lowercase(Locale.ROOT)
                if (!fields.containsKey(key)) fields[key] = field.value
            }
        }
        return fields
    }

    /** `\title` / `\author` / `\date` from front matter. Every other field
     *  is metadata *about* the document — a slug, a set of tags, a layout
     *  name — and has nowhere to go in a typeset one, so it is dropped the
     *  way the HTML and PDF renderers drop it. */
    private fun titleBlock(fields: Map<String, String>): List<String> {
        val lines = ArrayList<String>()
        fields["title"]?.let { lines.add("\\title{${escape(it)}}") }
        fields["author"]?.let { lines.add("\\author{${escape(it)}}") }
        val date = fields["date"]
        if (date != null) {
            lines.add("\\date{${escape(date)}}")
        } else if (lines.isNotEmpty()) {
            // `\maketitle` prints today's date when none is given, which is
            // a date the author never wrote into the document.
            lines.add("\\date{}")
        }
        return lines
    }

    // MARK: - Sentinels

    /** The author's text with this writer's own token sentinels taken out
     *  of it, applied to every string that enters the writer — a
     *  document's source, and a book's title, chapter names and article
     *  names and sources.
     *
     *  U+E000 and U+E001 are what [Writer.inline] wraps a span index in,
     *  and a document holding them of its own has its *own* characters
     *  read back as a token index. `text **b <E000>0<E001> x** more` came
     *  out as `text \textbf{b \textbf{ x} more`: the command duplicated,
     *  the author's word dropped, the braces unbalanced and the file
     *  refused with "File ended while scanning use of \textbf". They are
     *  also the one class of character it costs nothing to drop —
     *  private-use scalars no font assigns and inputenc has no definition
     *  for, so a document that kept them would stop at "Unicode character
     *  U+E000 not set up for use with LaTeX" instead. They cannot be
     *  typed, only pasted or generated.
     *
     *  Nothing else is stripped anywhere in this file, and this is the
     *  only place it happens: the working string of an inline pass is
     *  *made* of these scalars, so a strip applied any later would delete
     *  the writer's own tokens and with them every span in the document.
     *
     *  Both are inside the BMP and clear of the surrogate range, so
     *  walking UTF-16 units matches the Swift editions' scalar walk. */
    fun withoutSentinels(text: String): String {
        if (text.none(::isSentinel)) return text
        val out = StringBuilder(text.length)
        for (character in text) if (!isSentinel(character)) out.append(character)
        return out.toString()
    }

    private fun isSentinel(character: Char): Boolean =
        character == '\uE000' || character == '\uE001'

    // MARK: - Escaping

    /** Escape LaTeX's ten special characters in a run of author text.
     *
     *  Done in a single character-by-character pass rather than a chain of
     *  `replace` calls: three of the escapes (`\`, `~`, `^`) *contain*
     *  characters that are themselves special, so any sequential ordering
     *  re-escapes its own output.
     *
     *  `<`, `>` and `|` are deliberately left alone. They are not TeX
     *  specials — they only render as the wrong glyph under the ancient
     *  OT1 encoding — and the three ports have to agree character for
     *  character on this table.
     *
     *  Walking UTF-16 units rather than code points is safe here and only
     *  here: every character this switches on is ASCII, and a surrogate
     *  half can never equal one, so an emoji or a rare CJK glyph is copied
     *  through as the pair it arrived as. It is also what keeps this
     *  identical to Swift's, which walks Unicode scalars for the same
     *  reason and not grapheme clusters: `#` followed by a combining mark
     *  is one grapheme, and a walk over graphemes would leave the `#` raw
     *  on one platform and escape it on the other. */
    fun escape(text: String): String {
        val out = StringBuilder(text.length)
        for (character in text) {
            when (character) {
                '\\' -> out.append("\\textbackslash{}")
                '~' -> out.append("\\textasciitilde{}")
                '^' -> out.append("\\textasciicircum{}")
                '#', '$', '%', '&', '_', '{', '}' -> out.append('\\').append(character)
                else -> out.append(character)
            }
        }
        return out.toString()
    }

    /** Escape a URL or an image path for `\href` / `\includegraphics`.
     *
     *  Far less than [escape] does, and deliberately: hyperref reads its
     *  URL argument with its own catcodes, so `_`, `~` and `&` arrive
     *  intact and escaping them would put backslashes into the link
     *  itself. Only the characters that break the *argument* are escaped —
     *  a comment character would swallow the rest of the line, a parameter
     *  character is illegal there, and an unbalanced brace ends the
     *  argument early.
     *
     *  A `%` or a `#` never reaches this by way of `\includegraphics` —
     *  graphicx reads its argument as a file name, where neither the raw
     *  nor the escaped form of either works at all, so `image` refuses the
     *  file outright rather than emitting something that cannot compile. */
    fun escapeURL(url: String): String {
        val out = StringBuilder(url.length)
        for (character in url) {
            when (character) {
                '\\' -> out.append("\\textbackslash{}")
                '%', '#', '{', '}' -> out.append('\\').append(character)
                else -> out.append(character)
            }
        }
        return out.toString()
    }

    /** Percent-decode an image path, or hand back exactly what came in.
     *
     *  `![](my%20dir/a.png)` is what an editor writes when the author
     *  drags in a file whose folder has a space in its name, and
     *  `my%20dir` is a directory on no disk anywhere: the author meant
     *  `my dir`, and that is what the `.tex` should point at.
     *
     *  Anything that is not valid percent-encoding is not a mistake to be
     *  corrected — it is a file name that happens to contain a `%` — so it
     *  is returned untouched for `image` to refuse. Written by hand rather
     *  than with `URLDecoder`, which is an HTML form decoder and turns
     *  every `+` in a file name into a space. */
    fun percentDecoded(path: String): String {
        if (!path.contains('%')) return path
        val bytes = ByteArrayOutputStream(path.length)
        var plain = 0                       // start of the run of ordinary characters
        var index = 0

        fun flush(end: Int) {
            if (end > plain) bytes.write(path.substring(plain, end).toByteArray(Charsets.UTF_8))
        }

        while (index < path.length) {
            if (path[index] != '%') {
                index += 1
                continue
            }
            if (index + 2 >= path.length) return path
            val high = hexDigit(path[index + 1]) ?: return path
            val low = hexDigit(path[index + 2]) ?: return path
            flush(index)
            bytes.write(high shl 4 or low)
            index += 3
            plain = index
        }
        flush(path.length)
        return decodedUtf8(bytes.toByteArray()) ?: path
    }

    private fun hexDigit(character: Char): Int? = when (character) {
        in '0'..'9' -> character - '0'
        in 'a'..'f' -> character - 'a' + 10
        in 'A'..'F' -> character - 'A' + 10
        else -> null
    }

    /** UTF-8 that reports rather than repairs: a run of bytes that is not
     *  UTF-8 has to be null here, so the path can be handed back as the
     *  author wrote it instead of coming out full of U+FFFD. */
    private fun decodedUtf8(bytes: ByteArray): String? = try {
        Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes))
            .toString()
    } catch (_: CharacterCodingException) {
        null
    }

    /** True when [text] holds a Cyrillic letter.
     *
     *  This is the trigger for the T2A font encoding, and the reason it
     *  matters is the failure mode: with no Cyrillic encoding loaded,
     *  pdfTeX does not stop — the document compiles and the Russian simply
     *  is not in it. Written as a scan of scalar ranges rather than a
     *  character class because `\w` and friends mean different things on
     *  the desktop JVM and on Android's ICU engine, and this project has
     *  been bitten by that twice.
     *
     *  Comparing UTF-16 units is exact here: all four blocks are inside the
     *  BMP and well clear of the surrogate range, so no half of a
     *  supplementary character can be mistaken for a Cyrillic letter. */
    fun containsCyrillic(text: String): Boolean {
        for (character in text) {
            when (character.code) {
                in 0x0400..0x04FF,   // Cyrillic
                in 0x0500..0x052F,   // Cyrillic Supplement
                in 0x2DE0..0x2DFF,   // Cyrillic Extended-A
                in 0xA640..0xA69F,   // Cyrillic Extended-B
                -> return true
            }
        }
        return false
    }

    /** LaTeX reads a `[` immediately after `\item` or `\\` as the opening
     *  of an optional argument — a label for the item, a vertical skip for
     *  the row — so anything that *begins* with a bracket would have its
     *  first words eaten. That is not a corner case here: a task list's
     *  checkbox is literally `[x]`, and "[draft]" is an ordinary thing to
     *  write in the first cell of a table. An empty group in front costs
     *  nothing and stops the scan.
     *
     *  Every caller applies it to *finished* LaTeX, never to a string that
     *  still holds tokens: a line beginning with an undefined footnote
     *  reference begins with a token beforehand and with a `[` after.
     *
     *  `startsWith` is exact here, and that is worth saying because the
     *  Swift editions cannot use theirs. Kotlin walks UTF-16 units, so `[`
     *  followed by a combining mark still *starts with* `[`; Swift's
     *  `hasPrefix` matches extended grapheme clusters, where the two
     *  together are one `Character` that is not `"["` and the guard
     *  silently does not fire. Every `contains` / `startsWith` / `split` /
     *  `replace` in this file is the same story, which is why the Swift
     *  copies route all of them through scalar-exact helpers of their own.
     *  Do not "simplify" those back; do not add anything here that the
     *  helpers there could not express. */
    private fun bracketGuard(body: String): String =
        if (body.startsWith("[")) "{}$body" else body

    /** A table header cell, set bold.
     *
     *  `\textbf` is not `{\bfseries …}`: it reads its argument, and the
     *  reading is done by a delimited macro (`\check@nocorr@`) that a
     *  top-level `&` ends the table row out from underneath. So
     *  `\textbf{$\begin{aligned}a &= b\end{aligned}$}` is "Argument of
     *  \check@nocorr@ has an extra }" and `\textbf{\href{…?a=1&b=2}{x}}`
     *  is the same failure in `\href@split`, while either of them in a
     *  *body* cell — where nothing reads an argument — compiles. Both stop
     *  the whole file.
     *
     *  An extra group is the whole fix: TeX only reads `&` as an alignment
     *  tab at the outermost brace level of a cell, and a delimited
     *  argument scan steps over a group whole. It costs nothing
     *  typographically, so it is written only where there is an alignment
     *  tab to guard and every other header cell in the corpus is
     *  unchanged. */
    private fun headerCell(cell: String): String =
        if (holdsAlignmentTab(cell)) "\\textbf{{$cell}}" else "\\textbf{$cell}"

    /** True when finished LaTeX holds an `&` that is an alignment tab.
     *
     *  An author's own ampersand is `\&` by the time this runs, so a
     *  backslash swallows the character after it — which also steps over
     *  the `\\` of a multi-line formula without reading its second
     *  backslash as an escape. */
    private fun holdsAlignmentTab(latex: String): Boolean {
        var index = 0
        while (index < latex.length) {
            when (latex[index]) {
                '\\' -> index += 2
                '&' -> return true
                else -> index += 1
            }
        }
        return false
    }

    /** A formula, given an `aligned` of its own when its `\\` and `&` have
     *  nowhere else to live.
     *
     *  `&` is an alignment tab wherever it is read, so `\[a &= b\]` is
     *  "Misplaced alignment tab character &" in ordinary body text, and
     *  inside a table cell a top-level `\\` ends the *row* from inside math
     *  mode ("Extra }, or forgotten $"). Either stops the whole file. Both
     *  are what `aligned` is for, and both mean the author wrote a
     *  multi-line formula without saying so — so one is supplied, and every
     *  symbol they typed is set exactly where they meant it.
     *
     *  A formula that opens an environment of its own (`aligned`, `cases`,
     *  `array`, `matrix`) already owns its separators and is left as
     *  written; wrapping that would change what the author's own
     *  environment aligns on. */
    fun aligned(math: String): String {
        if (math.contains("\\begin{")) return math
        if (!math.contains("\\\\") && !math.contains('&')) return math
        return "\\begin{aligned}$math\\end{aligned}"
    }

    /** True when [line] sets something on the page — anything at all that
     *  is not white space, a comment or a float.
     *
     *  `\\` ends a *line*, and LaTeX refuses it with "There's no line here
     *  to end" when the paragraph has not begun. A captioned image alone on
     *  its line is a `figure`, which is a float and begins nothing; a
     *  skipped one is a comment, which is not even read. Either of them
     *  with a soft break after it takes the whole document down, so the
     *  soft-break join asks this before it writes a `\\`.
     *
     *  Scanned by hand rather than by regular expression: this runs on
     *  finished LaTeX, where a `%` that was escaped is `\%` and must not be
     *  read as the start of a comment. */
    fun setsSomething(line: String): Boolean {
        var index = 0
        while (index < line.length) {
            val character = line[index]
            when {
                character == ' ' || character == '\t' ||
                    character == '\n' || character == '\r' -> index += 1

                // A comment runs to the end of its physical line.
                character == '%' -> {
                    index += 1
                    while (index < line.length && line[index] != '\n') index += 1
                }

                line.startsWith(FIGURE_BEGIN, index) -> {
                    val end = line.indexOf(FIGURE_END, index + FIGURE_BEGIN.length)
                    if (end < 0) return false
                    index = end + FIGURE_END.length
                }

                else -> return true
            }
        }
        return false
    }

    /** Why `\includegraphics` cannot be handed this path, or null when it
     *  can.
     *
     *  graphicx reads its argument as a *file name*: it goes to the file
     *  system, not to the typesetter. So a control sequence in it is not a
     *  character that can be opened — and `{`, `}` and `\` are exactly what
     *  [escapeURL] turns into control sequences, which is right for `\href`
     *  and wrong here — while a `%` comments the rest of the line away
     *  before the argument is even read, and a `#` is an illegal parameter
     *  character. There is no spelling of any of them that works.
     *
     *  `"` is refused for a reason of its own: graphicx quotes a file name
     *  that has spaces in it with a pair of them, so one the author wrote
     *  breaks graphicx's own parser — `\includegraphics{a"b.png}` is "Use
     *  of ??? doesn't match its definition" and the document stops there.
     *  A sweep of all 95 printable ASCII characters through this function
     *  found it the only one that was neither refused here nor compilable.
     *
     *  A URL is the same problem from the other end: TeX fetches nothing,
     *  so `\includegraphics{https://…}` is "File not found" and the
     *  document stops there, and a `data:` URI is a picture with no file
     *  name at all. */
    fun unreadableImage(file: String): String? {
        for (character in file) {
            if (character in "%#{}\\\"") return "is not a file name LaTeX can read"
        }
        if (uriScheme(file) != null) return "is a URL, and LaTeX has nothing to fetch it with"
        return null
    }

    /** The URI scheme of [path] when it is a URL rather than a file name,
     *  null otherwise.
     *
     *  A scheme followed by `//` (`https://…`, `file://…`) or the schemeless
     *  `data:` form. Deliberately not "any letters then a colon": `C:` is a
     *  drive and `notes:draft.png` is a file somebody can really have, and
     *  neither should be refused for the shape of its name. */
    private fun uriScheme(path: String): String? {
        var index = 0
        while (index < path.length) {
            val character = path[index]
            if (character == ':') break
            val letter = character in 'a'..'z' || character in 'A'..'Z'
            val rest = index > 0 &&
                (character in '0'..'9' || character == '+' || character == '-' || character == '.')
            if (!letter && !rest) return null
            index += 1
        }
        if (index == 0 || index >= path.length) return null
        val scheme = path.substring(0, index)
        val authority = path.startsWith("//", index + 1)
        if (!authority && scheme.lowercase(Locale.ROOT) != "data") return null
        return scheme
    }

    // MARK: - Context

    /** Where a run of inline text is being written, which decides what
     *  LaTeX is legal in it.
     *
     *  [RESTRICTED] is a table cell, a footnote's own text or an image
     *  caption — anywhere a float or a display environment is an error
     *  rather than a layout choice ("Not in outer par mode", "Bad math
     *  environment delimiter"), and one that stops the whole file rather
     *  than the one paragraph. It is only ever added, never lifted: a
     *  footnote raised from a table cell is inside the float too. */
    private enum class Context { BODY, RESTRICTED }

    // MARK: - Writer

    /** Renders blocks to LaTeX while accumulating what the preamble owes
     *  them: which packages the commands emitted so far need, and where
     *  the document's footnotes are up to.
     *
     *  A class rather than a pile of parameters because the footnote
     *  numbering is a running count that has to survive nested calls — a
     *  reference inside a table cell inside a block quote still takes the
     *  next number the reader will meet.
     *
     *  [headingOffset] is how far to push the author's headings down, so
     *  the structure a book imposes (`\chapter`, `\section`) sits above
     *  them. */
    private class Writer(private val headingOffset: Int) {

        var needsAmsmath = false
        var needsGraphicx = false
        var needsHyperref = false
        var needsLongtable = false
        var needsUlem = false

        /** The current unit's footnote definitions, and the order they
         *  were written in — needed to print the ones nothing cites. */
        private var definitions = HashMap<String, String>()
        private var written = ArrayList<String>()
        private var cited = HashSet<String>()

        /** id → the number LaTeX will give that footnote. Ours and LaTeX's
         *  counters agree because every `\footnote` and every bare
         *  `\footnotemark` this file emits is emitted in the order it is
         *  numbered, and both step the same counter. */
        private var number = HashMap<String, Int>()
        private var counter = 0

        /** Inside a footnote's own text (LaTeX cannot nest footnotes) and
         *  inside a moving argument (a section title, a caption) — both
         *  change what a footnote reference is allowed to become. */
        private var inFootnote = false
        private var movingArgument = false

        /** Inside a table's header row — the one box in a `longtable` whose
         *  footnote insertions LaTeX throws away. */
        private var inTableHead = false

        /** The `\footnotetext` lines the header row being written owes,
         *  emitted after that table's `\end{longtable}`. */
        private var headerNotes = ArrayList<String>()

        /** What the run of inline text now being written may contain. */
        private var context = Context.BODY

        /** Start a new chapter: `book` resets the footnote counter at every
         *  `\chapter`, so the numbers `\footnotemark` cites restart too. */
        fun startChapter() {
            counter = 0
            number = HashMap()
        }

        // MARK: Units

        /** One whole document or book article: its blocks, then any
         *  footnote definition the text never referenced.
         *
         *  Every one of the four stores resets, [number] included. Footnote
         *  ids belong to the file they were written in — each article of a
         *  book numbers its own notes from `[^1]`, which is the normal thing
         *  and not a clash — so carrying the previous article's numbers over
         *  makes the second article's `[^1]` a `\footnotemark[1]` citing the
         *  *first* article's note, and the words the author wrote for it are
         *  never printed at all. */
        fun renderUnit(blocks: List<MarkdownBlock>): String {
            definitions = HashMap()
            written = ArrayList()
            cited = HashSet()
            number = HashMap()
            for (definition in blocks.filterIsInstance<MarkdownBlock.FootnoteDefinition>()) {
                if (definitions.containsKey(definition.id)) continue
                definitions[definition.id] = definition.text
                written.add(definition.id)
            }

            var out = renderBlocks(blocks)
            // A definition nothing points at is still something the author
            // wrote, so it is printed rather than dropped — with no
            // reference of its own, since it has nowhere to point back to.
            val orphans = written.filter { it !in cited }
            if (orphans.isEmpty()) return out
            val trailer =
                arrayListOf("% Footnotes defined but never referenced — kept so nothing is lost.")
            for (id in orphans) {
                counter += 1
                trailer.add("\\footnote{${footnoteText(definitions[id] ?: "")}}")
            }
            if (out.isNotEmpty()) out += "\n\n"
            return out + trailer.joinToString("\n")
        }

        /** A run of blocks, blank-line separated. Blocks that render to
         *  nothing (private notes, front matter, footnote definitions)
         *  drop out rather than leaving holes in the spacing. */
        fun renderBlocks(blocks: List<MarkdownBlock>): String =
            blocks.map(::renderBlock).filter { it.isNotEmpty() }.joinToString("\n\n")

        // MARK: Blocks

        private fun sectionCommand(level: Int): String =
            SECTIONS[(level - 1 + headingOffset).coerceIn(0, SECTIONS.size - 1)]

        fun renderBlock(block: MarkdownBlock): String = when (block) {
            is MarkdownBlock.Heading ->
                "\\${sectionCommand(block.level)}{${headingInline(block.text)}}"

            // Soft line breaks are breaks in md's preview, its HTML and
            // its PDF; they stay breaks here rather than reflowing the
            // way raw LaTeX would treat the same newlines.
            is MarkdownBlock.Paragraph -> inline(block.text, softBreaks = true)

            is MarkdownBlock.ListBlock -> renderList(block.items, block.ordered)

            is MarkdownBlock.CodeBlock -> renderCode(block.language, block.code)

            is MarkdownBlock.Quote -> "\\begin{quote}\n${renderBlocks(block.blocks)}\n\\end{quote}"

            is MarkdownBlock.Table -> renderTable(block.header, block.alignments, block.rows)

            MarkdownBlock.ThematicBreak -> "\\par\\noindent\\hrulefill\\par"

            MarkdownBlock.PageBreak -> "\\newpage"

            // Private author notes never reach a rendered document — they
            // live in the editor and the notes panel only.
            is MarkdownBlock.Note -> ""

            // Hoisted into the preamble's title block; nothing is drawn
            // where it was written, as everywhere else.
            is MarkdownBlock.FrontMatter -> ""

            // Inlined at the point of first reference (see
            // [footnoteReference]), or printed by [renderUnit] if nothing
            // references it.
            is MarkdownBlock.FootnoteDefinition -> ""
        }

        /** A section title: a moving argument, so a footnote inside it
         *  needs `\protect` before LaTeX writes the title to the `.toc`. */
        private fun headingInline(text: String): String {
            val saved = movingArgument
            movingArgument = true
            try {
                return inline(text)
            } finally {
                movingArgument = saved
            }
        }

        // MARK: Lists

        /** `itemize` / `enumerate`, nested by the item's level.
         *
         *  The model is flat — every item carries its own indentation
         *  depth — so the levels are mapped onto a stack of open
         *  environments rather than trusted directly. Two things make that
         *  necessary: LaTeX errors on a `\begin{itemize}` that is not
         *  preceded by an `\item` (which is what a list whose first item is
         *  already indented would produce), and it refuses to nest lists
         *  more than four deep. Opening at most one level per item, and
         *  stopping at four, keeps every item in the output either way. */
        private fun renderList(items: List<ListItem>, ordered: Boolean): String {
            val environment = if (ordered) "enumerate" else "itemize"
            val lines = ArrayList<String>()
            val open = ArrayList<Int>()

            for (item in items) {
                while (open.isNotEmpty() && item.level < open.last()) {
                    lines.add("\\end{$environment}")
                    open.removeAt(open.size - 1)
                }
                if (open.isEmpty() || (item.level > open.last() && open.size < 4)) {
                    lines.add("\\begin{$environment}")
                    open.add(item.level)
                }
                var body = inline(item.text)
                val done = item.task
                if (done != null) {
                    // A literal checkbox rather than a package: `amssymb`'s
                    // boxes would be a dependency for one glyph, and the
                    // author reads `[x]` in the source anyway.
                    body = "${if (done) "[x]" else "[\\,]"} $body"
                }
                lines.add("\\item ${bracketGuard(body)}")
            }
            repeat(open.size) { lines.add("\\end{$environment}") }
            return lines.joinToString("\n")
        }

        // MARK: Code, diagrams and data

        /** A fenced block. The info string decides what it is, using the
         *  same names the HTML renderer answers to, so the two agree about
         *  every fence in the document. */
        private fun renderCode(language: String?, code: String): String {
            val name = language.orEmpty()
            return when (val lang = name.lowercase(Locale.ROOT)) {
                "mermaid", "plantuml", "puml", "plant-uml" -> diagram(name, code)
                in MarkdownHtml.graphvizEngines -> diagram(name, code)
                // The one rich fence this app *can* draw without an engine —
                // and it still travels as its source. `\includegraphics` reads
                // no SVG without a conversion step a `.tex` file cannot carry,
                // so the honest thing is the same treatment a Mermaid diagram
                // gets: every number the author wrote, under a comment naming
                // the language.
                "plot" -> diagram(name, code)
                // Already a table everywhere else in the app; the parse and
                // the alignment rule are shared with the HTML renderer so
                // the same spreadsheet lands the same way in both.
                "csv", "tsv" ->
                    MarkdownHtml.delimitedTable(code, if (lang == "tsv") '\t' else ',')
                        ?.let { renderTable(it.header, it.alignments, it.rows) }
                        ?: verbatim(code)
                // The author's mathematics, untouched — the point of the
                // whole export.
                "math", "latex", "tex" -> display("\n$code\n")
                else -> verbatim(code)
            }
        }

        /** A diagram fence. LaTeX has no Mermaid, PlantUML or Graphviz, and
         *  md's renderers are JavaScript engines that cannot travel in a
         *  `.tex` file — so the source is kept verbatim under a comment
         *  naming the language, and the author decides what to do with it.
         *  Dropping it would lose a whole figure without saying so. */
        private fun diagram(language: String, code: String): String =
            "% $language diagram source — LaTeX has no renderer for it, so it is kept as written.\n" +
                verbatim(code)

        /** Wrap code in `verbatim`. The content is not escaped: verbatim is
         *  verbatim, and escaping it would put backslashes on the page.
         *
         *  One hazard is real. LaTeX's `verbatim` scans for the *characters*
         *  `\end{verbatim}` — the environment's terminator is defined with
         *  `\`, `{` and `}` as ordinary characters precisely so it can be
         *  found inside verbatim text — so a code block that quotes them
         *  closes the environment early and spills the rest of the block
         *  into the document as LaTeX to execute. Nothing can be inserted to
         *  escape it without changing the code, so the block is split around
         *  each occurrence and the terminator itself set with `\verb`,
         *  which keeps every character the author typed. */
        private fun verbatim(code: String): String {
            val pieces = code.split(VERBATIM_END)
            if (pieces.size == 1) return verbatimBlock(pieces[0])
            val out = ArrayList<String>()
            for ((index, piece) in pieces.withIndex()) {
                if (index > 0) out.add("\\noindent\\verb|$VERBATIM_END|\\par")
                if (piece.isNotEmpty()) out.add(verbatimBlock(piece))
            }
            return out.joinToString("\n")
        }

        private fun verbatimBlock(code: String): String =
            "\\begin{verbatim}\n$code\n$VERBATIM_END"

        // MARK: Tables

        /** A `longtable`, its column spec taken from the alignments the
         *  author wrote in the delimiter row.
         *
         *  A `longtable` and not a `tabular` inside a `table` float, and
         *  the difference is whether the author's rows are in the PDF. A
         *  float cannot break across a page, so a table taller than one
         *  does not overflow onto the next — it is **truncated**. The
         *  compile is exit 0, the PDF simply stops at the row that filled
         *  the page, and the only trace is one "Float too large for page"
         *  line in a log nobody reads. A sixty-row table loses its last
         *  five rows and says nothing. `longtable` breaks across pages, so
         *  every row the author wrote is printed.
         *
         *  Not being a float pays a second time. LaTeX typesets no footnote
         *  text from inside a float: with a plain `\footnote` in a cell the
         *  mark prints, the counter steps, and the note's words are not on
         *  the page. Inside a `longtable` an ordinary `\footnote` works, so
         *  every *body* cell keeps the note it cites where the author put
         *  the reference. The header row is the one exception, and the last
         *  paragraph here is why.
         *
         *  The header repeats at the top of each page (`\endhead`) — a
         *  reader who turns to the second page of a table needs to know
         *  what its columns are.
         *
         *  The width is the widest row in the table, not the header's: a
         *  row with a cell too many would otherwise overrun the alignment
         *  and take the rest of the document down with it, and truncating
         *  it would throw the cell away.
         *
         *  The head is the one place inside a `longtable` where a plain
         *  `\footnote` still loses its words, and `\endhead` is why: the
         *  header row is typeset **once** into a box that is reinserted at
         *  every page break, and LaTeX discards a footnote insertion made
         *  inside a box. The compile is exit 0 and the note's text is on no
         *  page. So a note first cited from the head is split — see
         *  [footnoteReference] — and the `\footnotetext` it owes is written
         *  after `\end{longtable}`, where it is read exactly once. */
        private fun renderTable(
            header: List<String>,
            alignments: List<ColumnAlignment>,
            rows: List<List<String>>,
        ): String {
            needsLongtable = true
            val columns = maxOf(header.size, rows.maxOfOrNull { it.size } ?: 0)
            if (columns == 0) return ""

            val spec = (0 until columns).joinToString("") { column ->
                when (alignments.getOrNull(column)) {
                    ColumnAlignment.CENTER -> "c"
                    ColumnAlignment.TRAILING -> "r"
                    else -> "l"
                }
            }

            fun row(cells: List<String>, heading: Boolean): String {
                val rendered = (0 until columns).map { column ->
                    val cell = cells.getOrNull(column)
                        ?.let { inline(it, context = Context.RESTRICTED) }.orEmpty()
                    if (heading && cell.isNotEmpty()) headerCell(cell) else cell
                }
                return bracketGuard(rendered.joinToString(" & ")) + " \\\\"
            }

            // The header row is rendered first — it is what the reader
            // meets first, so its notes take the first numbers — and while
            // `inTableHead` is set, so a note cited from it is split rather
            // than dropped into the saved head box.
            val savedHead = inTableHead
            val savedNotes = headerNotes
            inTableHead = true
            headerNotes = ArrayList()
            val head = row(header, heading = true)
            val notes = headerNotes
            inTableHead = savedHead
            headerNotes = savedNotes

            val lines = arrayListOf(
                "\\begin{longtable}{$spec}", "\\hline",
                head, "\\hline", "\\endhead",
            )
            rows.mapTo(lines) { row(it, heading = false) }
            lines.addAll(listOf("\\hline", "\\end{longtable}"))
            lines.addAll(notes)
            return lines.joinToString("\n")
        }

        // MARK: Footnotes

        /** A reference to [id], resolved the moment the reader meets it.
         *
         *  The first reference to a defined note carries the note's text —
         *  that is what a LaTeX footnote *is*, and the reason this export
         *  has no collected list at the foot of the document the way the
         *  HTML one does. A later reference to the same note can only cite
         *  the number, which works because every mark here is emitted in
         *  the order it was numbered, so LaTeX's counter and this one never
         *  disagree.
         *
         *  A reference with no definition is not a footnote at all, so it
         *  goes back to being the text the author typed. */
        private fun footnoteReference(id: String): String {
            val protection = if (movingArgument) "\\protect" else ""
            // LaTeX cannot nest footnotes, so a reference inside a note's
            // own text stays literal — the same degradation the HTML
            // renderer applies to the same case.
            val text = definitions[id]
            if (inFootnote || text == null) return escape("[^$id]")
            cited.add(id)
            val existing = number[id]
            if (existing != null) return "$protection\\footnotemark[$existing]"
            counter += 1
            number[id] = counter
            // The header row is the exception, and only the header row: it
            // is typeset once into the box `\endhead` reinserts at every
            // page break, and LaTeX drops a footnote insertion made inside
            // a box — the mark prints, the counter steps, and the note's
            // words are on no page at all. The standard idiom is the mark
            // here and the text after the table, and the counter is stepped
            // by hand because `\footnotemark[n]` does not step it: a body
            // cell further down this same table takes the next number, and
            // would otherwise print this one's.
            if (inTableHead) {
                val mark = counter
                headerNotes.add("\\footnotetext[$mark]{${footnoteText(text)}}")
                return "\\stepcounter{footnote}$protection\\footnotemark[$mark]"
            }
            // A plain `\footnote` everywhere else, a table *body* cell
            // included: the tables here are `longtable`s and a `longtable`
            // is not a float, so the note's own words are typeset where a
            // `table` would have dropped them.
            return "$protection\\footnote{${footnoteText(text)}}"
        }

        /** A footnote's own text is inline Markdown, rendered with nesting
         *  disabled for the duration — and restricted, because a footnote
         *  is no more able to hold a float than a table cell is. */
        private fun footnoteText(text: String): String {
            val saved = inFootnote
            inFootnote = true
            try {
                return inline(text, context = Context.RESTRICTED)
            } finally {
                inFootnote = saved
            }
        }

        // MARK: Inline

        /** Convert a block's inline Markdown to LaTeX.
         *
         *  The ordering is the whole of the correctness here, and it is not
         *  the HTML renderer's. There, escaping can run first because none
         *  of `&`, `<`, `>` is Markdown syntax; here `_` and `~` are both
         *  LaTeX specials *and* emphasis delimiters, so escaping first
         *  would destroy the very markup that still has to be found.
         *
         *  So the commands go in first — but as *tokens*, never as text.
         *  Each conversion replaces its delimiters with a private-use token
         *  standing for the LaTeX it becomes and leaves the author's words
         *  between them, which does two things at once: the escaping pass
         *  at the end sees author text and nothing else (no backslash this
         *  file emitted is ever escaped), and markup nested inside a link
         *  label or a bold run is still plain text when the later passes
         *  look for it.
         *
         *  The patterns themselves are [MarkdownHtml]'s, deliberately: if
         *  the two disagreed about what a formula is, the preview and the
         *  `.tex` would disagree about the same document. That includes the
         *  spelled-out `[\p{L}\p{N}_]` word guards — `\w` is ASCII on the
         *  desktop JVM the unit tests run on and Unicode on the device's
         *  ICU engine, and the `(?U)` flag that fixes the one is rejected
         *  outright by the other.
         *
         *  [context] is *added* to whatever is already in force, never
         *  substituted for it: a footnote raised from a table cell is still
         *  inside the float. */
        fun inline(
            text: String,
            softBreaks: Boolean = false,
            context: Context = Context.BODY,
        ): String {
            // A pass owns its tokens. `image` starts a second, complete
            // pass over the alt text in the middle of this one, and the two
            // stores must never meet: a token belonging to this pass that
            // ended up inside that one's output would be restored by
            // nobody, and the author's formula would print as U+E000.
            val savedSpans = spans
            val savedContext = this.context
            spans = Spans()
            if (context == Context.RESTRICTED) this.context = Context.RESTRICTED

            try {
                var working = text

                // 1. Literal spans first — their content must not be read
                //    as markup, and math's must not be escaped either. Code
                //    wins over math, so `$x$` in backticks stays code; the
                //    inline `$…$` form keeps its currency guard so "$5 and
                //    $10" is prose. `\texttt` rather than `\verb`: this
                //    text has to survive inside a section title, a caption
                //    and a table cell, where `\verb` is not allowed.
                working = substitute("`([^`]+)`", working) { "\\texttt{${escape(it[1])}}" }
                working = substitute(DISPLAY_MATH, working) { display(it[1]) }
                working = substitute(BRACKET_MATH, working) { display(it[1]) }
                working = substitute(INLINE_MATH, working) { inlineMath(it[1]) }
                working = substitute(PAREN_MATH, working) { inlineMath(it[1]) }

                // 2. Images before links (image syntax is link syntax with
                //    a `!` in front, so the link pass would eat it), links
                //    before emphasis. A source title is matched only so it
                //    is consumed — it is a browser tooltip, and a typeset
                //    page has nowhere to put one.
                working = substitute(TITLED_IMAGE, working) { image(alt = it[1], path = it[2]) }
                working = substitute(IMAGE, working) { image(alt = it[1], path = it[2]) }
                working = wrap(TITLED_LINK, working, close = "}") { href(it[2]) }
                working = wrap(LINK, working, close = "}") { href(it[2]) }

                // 3. Footnote references after images and links, and for
                //    the same reason the HTML renderer runs them last:
                //    converting one first would let an image carry a whole
                //    `\footnote` into its caption. Running here, a
                //    reference written inside a link label simply stops
                //    that label being a link.
                working = substitute(FOOTNOTE_REFERENCE, working) { footnoteReference(it[1]) }

                // 4. Emphasis: bold before italic so `**` wins, underscore
                //    italic only at word boundaries so snake_case survives.
                working = wrap("""\*\*([^*]+)\*\*""", working, close = "}") { "\\textbf{" }
                working = wrap("""__([^_]+)__""", working, close = "}") { "\\textbf{" }
                working = wrap("""~~([^~]+)~~""", working, close = "}") {
                    needsUlem = true
                    "\\sout{"
                }
                working = wrap("""\*([^*]+)\*""", working, close = "}") { "\\emph{" }
                working = wrap(UNDERSCORE_ITALIC, working, close = "}") { "\\emph{" }

                // 5. Everything still in the string is the author's own
                //    text. Tokens are private-use scalars and digits, none
                //    of them special, so they pass through untouched.
                working = escape(working)

                // 6. Restore the spans, and with them the soft breaks.
                //
                //    The lines are split while the spans are still tokens —
                //    a multi-line formula is one token here, and would
                //    otherwise collect a `\\` at each of its own newlines —
                //    but the bracket guard runs on the *restored* line, the
                //    way the item and row guards do. A line that begins
                //    with an undefined footnote reference begins with a
                //    token now and with a `[` a moment later, and `\\[` is
                //    a vertical skip.
                //    A soft break is only written between two lines that
                //    both set something. `\\` ends a line, and LaTeX
                //    refuses it with "There's no line here to end" when the
                //    paragraph has not begun — which is exactly a captioned
                //    image alone on its line (a float) or a skipped one (a
                //    comment). Those join with an ordinary newline, which is
                //    what LaTeX reads as "the paragraph goes on" anyway.
                if (!softBreaks) return restored(working)
                val lines = working.split("\n").map(::restored)
                val out = StringBuilder(lines[0])
                var opened = setsSomething(lines[0])
                for (line in lines.drop(1)) {
                    if (opened && setsSomething(line)) {
                        out.append("\\\\\n").append(bracketGuard(line))
                    } else {
                        // Never two newlines in a row: a blank line is a
                        // paragraph break, and one straight after a `\\` is
                        // the very error this is avoiding.
                        if (!out.endsWith("\n")) out.append('\n')
                        out.append(line)
                        opened = opened || setsSomething(line)
                    }
                }
                return out.toString()
            } finally {
                spans = savedSpans
                this.context = savedContext
            }
        }

        /** An inline formula. Nothing about it changes with the context —
         *  `$…$` is legal in a table cell, a caption and a footnote alike —
         *  but its own `\\` and `&` still have to be given somewhere to
         *  live, and the document still owes amsmath for whatever is in
         *  it. */
        private fun inlineMath(math: String): String {
            needsAmsmath = true
            return "\$${aligned(math)}\$"
        }

        /** A display formula — or an inline one, where LaTeX will not open
         *  a display environment at all. `\[…\]` in a table cell is "Bad
         *  math environment delimiter" and the file stops there; set inline
         *  the formula is smaller than the author asked for, but every
         *  symbol of it is still on the page. */
        private fun display(math: String): String {
            needsAmsmath = true
            val body = aligned(math)
            return if (context == Context.RESTRICTED) "\$$body\$" else "\\[$body\\]"
        }

        private fun href(url: String): String {
            needsHyperref = true
            return "\\href{${escapeURL(url)}}{"
        }

        /** An image.
         *
         *  In body text, alt text makes it a captioned `figure`, which is
         *  what alt text is for on a printed page; without alt text, just
         *  the graphic, since an empty `\caption` would print a bare
         *  "Figure 1".
         *
         *  The uncaptioned form carries a `\noindent`, and it earns its
         *  place: a graphic exactly `\linewidth` wide starting a paragraph
         *  is pushed over the margin by the paragraph indent, and LaTeX
         *  reports an overfull box on every single image in the document.
         *  Inside the `figure` the same graphic is already unindented.
         *
         *  In a restricted place there is no float to be had — a `figure`
         *  in a table cell is "Not in outer par mode" and in a footnote it
         *  is "Float(s) lost", and either takes the whole file down — so
         *  the graphic is written bare and the alt text goes beside it in
         *  italic. It is a description of the picture, not a sentence of
         *  the author's prose, and dropping it because there is no
         *  `\caption` to put it in would lose words somebody wrote. */
        private fun image(alt: String, path: String): String {
            val file = percentDecoded(path)
            val reason = unreadableImage(file)
            if (reason != null) return skipped(file, reason, alt)
            needsGraphicx = true
            val include = "\\includegraphics[width=\\linewidth]{${escapeURL(file)}}"
            if (context == Context.RESTRICTED) {
                return if (alt.isEmpty()) include else "$include \\emph{${altText(alt)}}"
            }
            if (alt.isEmpty()) return "\\noindent$include"
            val saved = movingArgument
            movingArgument = true
            val caption = try {
                altText(alt)
            } finally {
                movingArgument = saved
            }
            return "\\begin{figure}[ht]\n\\centering\n$include\n\\caption{$caption}\n\\end{figure}"
        }

        /** An image LaTeX cannot include, set as the words the author wrote
         *  about it.
         *
         *  Nothing here vanishes without saying so: the alt text is the
         *  author's own description and is printed in place of the picture,
         *  and the file is named in a comment so the reason is in the file
         *  they are handed.
         *
         *  The comment comes **last** and ends with a newline, and that is
         *  the whole of its safety. `%` runs to the end of its physical
         *  line, so a comment in front of the text would swallow the text,
         *  and one without a newline after it would swallow the rest of a
         *  table row or the rest of a sentence. */
        private fun skipped(file: String, reason: String, alt: String): String {
            val note = "% md: image skipped — $file $reason.\n"
            if (alt.isEmpty()) return note
            return "\\emph{${altText(alt)}}\n$note"
        }

        /** An image's alt text as LaTeX.
         *
         *  A fresh, complete pass over the Markdown the author wrote. [alt]
         *  arrives here carrying the tokens of the pass that found this
         *  image, and re-tokenising a half-tokenised string leaves those
         *  tokens behind for the outer restore never to see — which is a
         *  formula, a code span or a bold run silently replaced by U+E000.
         *
         *  Restricted, wherever it is going: a caption cannot hold a float
         *  or a display any more than a table cell can. */
        private fun altText(alt: String): String =
            inline(rawSource(alt), context = Context.RESTRICTED)

        // MARK: Token plumbing

        /** The spans one inline pass has lifted out of the text: for each
         *  token, the LaTeX it stands for and the Markdown it was made
         *  from.
         *
         *  The source half is there for exactly one caller — an image's alt
         *  text, which is captured from a string this pass has already
         *  tokenised and has to be rendered from what the author typed. */
        private class Spans {
            val latex = ArrayList<String>()
            val source = ArrayList<String>()
            val count: Int get() = latex.size

            fun append(latex: String, source: String) {
                this.latex.add(latex)
                this.source.add(source)
            }
        }

        private var spans = Spans()

        /** Put the LaTeX back where the tokens are.
         *
         *  Newest span first: a span's replacement can only ever hold a
         *  token from a pass older than itself, so resolving downwards
         *  finishes in one sweep whatever nests inside what. */
        private fun restored(text: String): String = resolve(text) { spans.latex[it] }

        /** The author's own Markdown for a stretch of the working string,
         *  every token in it put back to the source it was made from. */
        private fun rawSource(text: String): String = resolve(text) { spans.source[it] }

        private fun resolve(text: String, replacement: (Int) -> String): String {
            if (!text.contains('\uE000')) return text
            var out = text
            for (index in spans.count - 1 downTo 0) {
                out = out.replace(token(index), replacement(index))
            }
            return out
        }

        /** Replace every match of [pattern] with a token standing for
         *  `transform(groups)`, a finished LaTeX fragment the escaping pass
         *  must never see. `groups[0]` is the whole match. */
        private fun substitute(
            pattern: String,
            text: String,
            transform: (List<String>) -> String,
        ): String = splice(pattern, text) { match ->
            // The transform runs before the span is filed because it may
            // start an inline pass of its own — an image caption does —
            // which borrows the store and hands it back.
            val latex = transform(match.groups)
            spans.append(latex, match.groups[0])
            token(spans.count - 1) to null
        }

        /** Replace every match with an opening token, the text of capture
         *  group 1, and a closing token — so the command's braces are
         *  hidden from the escaping pass while the author's words between
         *  them stay ordinary text that the later passes can still match. */
        private fun wrap(
            pattern: String,
            text: String,
            close: String,
            open: (List<String>) -> String,
        ): String = splice(pattern, text) { match ->
            val latex = open(match.groups)
            val first = spans.count
            spans.append(latex, match.before)
            spans.append(close, match.after)
            token(first) to token(first + 1)
        }

        /** One regex match, as the passes need it: the captured groups, and
         *  the author's own text on either side of group 1 — the
         *  delimiters, which is what [wrap] replaces and what it has to be
         *  able to give back. */
        private class Match(val groups: List<String>, val before: String, val after: String)

        /** The shared rewrite: match forward, build each replacement in
         *  reading order, then splice from the end of the string back.
         *
         *  The transforms run **forward** and the splicing runs backward:
         *  footnote numbering depends on the order the reader meets the
         *  references, while rewriting from the end is what keeps the
         *  earlier ranges valid. */
        private fun splice(
            pattern: String,
            text: String,
            build: (Match) -> Pair<String, String?>,
        ): String {
            // A pattern that won't compile leaves the text alone rather than
            // throwing, the way [MarkdownHtml.protect] does — and it is
            // compiled before anything is matched, so a failure can never
            // leave half a document's tokens in the store.
            val regex = runCatching { Regex(pattern) }.getOrNull() ?: return text
            val matches = regex.findAll(text).toList()
            if (matches.isEmpty()) return text

            val replacements = matches.map { match ->
                val groups = match.groupValues
                val inner = match.groups.takeIf { it.size > 1 }?.get(1)?.range
                val before =
                    if (inner == null) "" else text.substring(match.range.first, inner.first)
                val after =
                    if (inner == null) "" else text.substring(inner.last + 1, match.range.last + 1)
                val (open, close) = build(Match(groups, before, after))
                if (close == null) open else open + groups[1] + close
            }

            val result = StringBuilder(text)
            for (index in matches.indices.reversed()) {
                val range = matches[index].range
                result.replace(range.first, range.last + 1, replacements[index])
            }
            return result.toString()
        }
    }

    // MARK: - Constants

    /** The five sectioning commands `article` and `book` share. Six
     *  heading levels map onto them, so the deepest two both land on
     *  `\subparagraph` — LaTeX has nothing below it. */
    private val SECTIONS =
        listOf("section", "subsection", "subsubsection", "paragraph", "subparagraph")

    private const val VERBATIM_END = "\\end{verbatim}"
    private const val FIGURE_BEGIN = "\\begin{figure}"
    private const val FIGURE_END = "\\end{figure}"

    private fun token(index: Int): String = "\uE000$index\uE001"

    // The inline patterns, spelled the way MarkdownHtml spells them.
    private const val DISPLAY_MATH = """\${'$'}\${'$'}([\s\S]+?)\${'$'}\${'$'}"""
    private const val BRACKET_MATH = """\\\[([\s\S]+?)\\\]"""
    private const val INLINE_MATH =
        """(?<![\p{L}\p{N}_${'$'}])\${'$'}([^${'$'}\n]+?)\${'$'}(?![\p{L}\p{N}_${'$'}])"""
    private const val PAREN_MATH = """\\\(([^\n]+?)\\\)"""
    private const val TITLED_IMAGE = """!\[([^\]]*)\]\(([^)\s]+)\s+"(.*?)"\)"""
    private const val IMAGE = """!\[([^\]]*)\]\(([^)\s]+)\)"""
    private const val TITLED_LINK = """\[([^\]]+)\]\(([^)\s]+)\s+"(.*?)"\)"""
    private const val LINK = """\[([^\]]+)\]\(([^)\s]+)\)"""
    private const val FOOTNOTE_REFERENCE = """\[\^([A-Za-z0-9_-]+)\]"""
    private const val UNDERSCORE_ITALIC = """(?<![\p{L}\p{N}_])_([^_]+)_(?![\p{L}\p{N}_])"""
}
