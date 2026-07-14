/*
 * MarkdownParser.kt
 * md (Android)
 *
 * A small, dependency-free, block-level Markdown parser — a faithful
 * Kotlin port of the iOS / macOS app's `MarkdownParser.swift`. It splits
 * the source into a flat list of block elements (headings, paragraphs,
 * lists, code fences, block quotes, tables, rules) which the Compose
 * renderer draws. *Inline* formatting inside a block (bold, italic, code
 * spans, links, strikethrough) is handled separately in MarkdownInline.
 *
 * This is a pragmatic subset of CommonMark + the common GitHub extensions
 * (fenced code, task lists, tables, strikethrough) — a faithful, readable
 * preview of everyday Markdown, not full spec conformance. Parsing is
 * single-pass and line-oriented.
 */

package me.nettrash.md.markdown

/** One rendered block. */
sealed interface MarkdownBlock {
    data class Heading(val level: Int, val text: String) : MarkdownBlock
    data class Paragraph(val text: String) : MarkdownBlock
    data class ListBlock(val ordered: Boolean, val items: List<ListItem>) : MarkdownBlock
    data class CodeBlock(val language: String?, val code: String) : MarkdownBlock
    data class Quote(val blocks: List<MarkdownBlock>) : MarkdownBlock
    data class Table(
        val header: List<String>,
        val alignments: List<ColumnAlignment>,
        val rows: List<List<String>>,
    ) : MarkdownBlock
    data object ThematicBreak : MarkdownBlock
    data object PageBreak : MarkdownBlock
    data class Note(val text: String) : MarkdownBlock
}

/** A single list row. `level` is the indentation depth (0 = top level);
 *  `task` is non-null for GitHub task-list items (`- [ ]` / `- [x]`). */
data class ListItem(
    val text: String,
    val level: Int,
    val ordinal: Int?,
    val task: Boolean?,
)

/** One table-of-contents entry. `line` is the 0-based source line of the
 *  heading, so the editor can jump to it; `slug` matches the `id` the HTML
 *  renderer gives the same heading, so the preview can scroll to it. */
data class OutlineEntry(
    val level: Int,
    val text: String,
    val slug: String,
    val line: Int,
)

/** One private author note (`<!-- note: … -->`). `line` is the 0-based
 *  source line the note starts on, so the notes panel can jump to it. */
data class NoteEntry(
    val text: String,
    val line: Int,
)

enum class ColumnAlignment { LEADING, CENTER, TRAILING }

object MarkdownParser {

    // Swift's `.whitespaces` character set is space + tab; match it exactly.
    private fun String.trimSpaces(): String = trim { it == ' ' || it == '\t' }

    /** Parse Markdown source into a flat list of blocks. `quoteDepth` is
     *  internal: block quotes recurse, and the cap bounds that recursion so
     *  a pathological run of `>` can't overflow the stack. */
    fun parse(source: String, quoteDepth: Int = 0): List<MarkdownBlock> {
        val lines = source
            .replace("\r\n", "\n")
            .replace("\r", "\n")
            .split("\n")
        val blocks = ArrayList<MarkdownBlock>()
        var i = 0

        while (i < lines.size) {
            val line = lines[i]

            // Blank line — paragraph / block separator.
            if (line.trimSpaces().isEmpty()) { i++; continue }

            // Fenced code block: ``` or ~~~ with an optional info string.
            val fence = FenceMarker.from(line)
            if (fence != null) {
                val code = ArrayList<String>()
                i++
                while (i < lines.size) {
                    if (fence.closes(lines[i])) { i++; break }
                    code.add(fence.stripIndent(lines[i]))
                    i++
                }
                blocks.add(MarkdownBlock.CodeBlock(fence.language, code.joinToString("\n")))
                continue
            }

            // Thematic break: a line of 3+ -, * or _ (spaces allowed).
            if (isThematicBreak(line)) {
                blocks.add(MarkdownBlock.ThematicBreak)
                i++
                continue
            }

            // Page break: a line of exactly `\newpage` (or `\pagebreak`),
            // the Pandoc convention — where the author says a page ends.
            // Shown as a subtle divider in the preview; starts a new page
            // in print and in the shared / exported PDF.
            if (isPageBreak(line)) {
                blocks.add(MarkdownBlock.PageBreak)
                i++
                continue
            }

            // HTML comment block: `<!-- … -->`, possibly spanning lines.
            // A `<!-- note: … -->` comment is the author's private note —
            // kept as a block so the notes panel can list it. Any other
            // comment is simply dropped. Neither appears in the preview,
            // the PDF, or print.
            if (isCommentStart(line)) {
                val raw = ArrayList<String>()
                while (i < lines.size) {
                    raw.add(lines[i])
                    val closed = lines[i].contains("-->")
                    i++
                    if (closed) break
                }
                noteText(raw.joinToString("\n"))?.let { blocks.add(MarkdownBlock.Note(it)) }
                continue
            }

            // ATX heading.
            val heading = parseHeading(line)
            if (heading != null) {
                blocks.add(MarkdownBlock.Heading(heading.first, heading.second))
                i++
                continue
            }

            // GFM table: header row + delimiter row lookahead.
            if (i + 1 < lines.size) {
                val table = parseTable(line, lines[i + 1])
                if (table != null) {
                    val rows = ArrayList<List<String>>()
                    i += 2
                    while (i < lines.size && lines[i].contains("|") && lines[i].trimSpaces().isNotEmpty()) {
                        rows.add(splitTableRow(lines[i], table.header.size))
                        i++
                    }
                    blocks.add(MarkdownBlock.Table(table.header, table.alignments, rows))
                    continue
                }
            }

            // Block quote: collect `>`-prefixed lines, strip one marker
            // level, parse the inner content recursively (depth-capped).
            if (isQuote(line)) {
                val inner = ArrayList<String>()
                while (i < lines.size && isQuote(lines[i])) {
                    inner.add(stripQuoteMarker(lines[i]))
                    i++
                }
                val innerText = inner.joinToString("\n")
                val innerBlocks =
                    if (quoteDepth < 32) parse(innerText, quoteDepth + 1)
                    else listOf(MarkdownBlock.Paragraph(innerText))
                blocks.add(MarkdownBlock.Quote(innerBlocks))
                continue
            }

            // List: collect the run of consecutive list-item lines, each
            // absorbing its lazy / indented continuation lines.
            if (listMarker(line) != null) {
                val items = ArrayList<ListItem>()
                var ordered = false
                while (i < lines.size) {
                    val marker = listMarker(lines[i]) ?: break
                    ordered = ordered || marker.ordinal != null
                    var text = marker.text
                    i++
                    while (i < lines.size) {
                        val l = lines[i]
                        if (l.trimSpaces().isEmpty()) break
                        if (listMarker(l) != null || FenceMarker.from(l) != null ||
                            isThematicBreak(l) || parseHeading(l) != null || isQuote(l) ||
                            isPageBreak(l) || isCommentStart(l)
                        ) break
                        if (i + 1 < lines.size && parseTable(l, lines[i + 1]) != null) break
                        text += " " + l.trimSpaces()
                        i++
                    }
                    items.add(ListItem(text, marker.level, marker.ordinal, marker.task))
                }
                blocks.add(MarkdownBlock.ListBlock(ordered, items))
                continue
            }

            // Otherwise: a paragraph — gather lines until a blank line or
            // the start of another block, preserving soft line breaks.
            val paragraph = ArrayList<String>()
            var emittedHeading = false
            while (i < lines.size) {
                val l = lines[i]
                if (l.trimSpaces().isEmpty()) break
                // Setext heading: a single buffered line underlined by `===`
                // (h1) or `---` (h2). Checked before the rule / list branches.
                if (paragraph.size == 1) {
                    val level = setextUnderline(l)
                    if (level != null) {
                        blocks.add(MarkdownBlock.Heading(level, paragraph[0].trimSpaces()))
                        i++
                        emittedHeading = true
                        break
                    }
                }
                if (FenceMarker.from(l) != null || isThematicBreak(l) || parseHeading(l) != null ||
                    isQuote(l) || listMarker(l) != null ||
                    isPageBreak(l) || isCommentStart(l)
                ) break
                paragraph.add(l)
                i++
            }
            if (!emittedHeading && paragraph.isNotEmpty()) {
                blocks.add(MarkdownBlock.Paragraph(paragraph.joinToString("\n")))
            }
        }

        return blocks
    }

    // MARK: - Headings

    private fun parseHeading(line: String): Pair<Int, String>? {
        val trimmed = line.dropWhile { it == ' ' }
        if (trimmed.firstOrNull() != '#') return null
        var level = 0
        var rest = trimmed
        while (rest.firstOrNull() == '#' && level < 7) {
            level++
            rest = rest.substring(1)
        }
        if (level !in 1..6) return null
        // A valid ATX heading needs a space (or end of line) after the #s.
        if (rest.isNotEmpty() && rest.firstOrNull() != ' ') return null
        return level to stripClosingHashes(rest.trimSpaces())
    }

    /** Remove a *closing* ATX `#` run (`## Title ##` → `Title`) but only
     *  when preceded by whitespace, so `C#` / `F#` survive. */
    private fun stripClosingHashes(text: String): String {
        var end = text.length
        while (end > 0 && text[end - 1] == '#') end--
        if (end == text.length) return text   // no trailing # run
        if (end == 0) return ""                // all #s → empty heading
        val before = text[end - 1]
        if (before != ' ' && before != '\t') return text  // e.g. "C#"
        return text.substring(0, end).trimSpaces()
    }

    /** A setext underline: a non-empty line of only `=` (1) or only `-` (2). */
    private fun setextUnderline(line: String): Int? {
        val t = line.trimSpaces()
        if (t.isEmpty()) return null
        if (t.all { it == '=' }) return 1
        if (t.all { it == '-' }) return 2
        return null
    }

    // MARK: - Page breaks & comments

    /** A page break: a line whose only content is `\newpage` or
     *  `\pagebreak` (the Pandoc / LaTeX conventions). */
    private fun isPageBreak(line: String): Boolean {
        val t = line.trimSpaces()
        return t == "\\newpage" || t == "\\pagebreak"
    }

    /** A line that opens an HTML comment block. */
    private fun isCommentStart(line: String): Boolean =
        line.dropWhile { it == ' ' }.startsWith("<!--")

    /** `<!-- note: … -->` → the note's text; any other comment → null. */
    private fun noteText(comment: String): String? {
        val open = comment.indexOf("<!--")
        if (open < 0) return null
        val closeIndex = comment.indexOf("-->")
        val close = if (closeIndex >= 0) closeIndex else comment.length
        if (open + 4 > close) return null
        val body = comment.substring(open + 4, close).trim()
        if (!body.lowercase().startsWith("note:")) return null
        return body.substring(5).trim()
    }

    // MARK: - Outline, notes & anchors

    /** The document's table of contents: every ATX / setext heading outside
     *  a code fence, with the source line and the same anchor slug the HTML
     *  renderer assigns. Line-oriented like [parse], so it stays cheap
     *  enough to recompute whenever the TOC is shown. */
    fun outline(source: String): List<OutlineEntry> {
        val lines = normalizedLines(source)
        val entries = ArrayList<OutlineEntry>()
        val used = HashMap<String, Int>()
        var fence: FenceMarker? = null
        var previousPlain: Pair<String, Int>? = null
        // How many plain lines ran up to `previousPlain`. [parse] only treats
        // an underline as setext when the buffered paragraph has exactly ONE
        // line; the outline must apply the same rule, or it would list
        // headings the rendered document doesn't have (and their phantom
        // slugs would shift every later anchor).
        var plainRun = 0
        var i = 0
        while (i < lines.size) {
            val line = lines[i]
            val openFence = fence
            if (openFence != null) {
                if (openFence.closes(line)) fence = null
                previousPlain = null
                plainRun = 0
                i++
                continue
            }
            val newFence = FenceMarker.from(line)
            if (newFence != null) {
                fence = newFence
                previousPlain = null
                plainRun = 0
                i++
                continue
            }
            if (isCommentStart(line)) {
                while (i < lines.size && !lines[i].contains("-->")) i++
                previousPlain = null
                plainRun = 0
                i++
                continue
            }
            val heading = parseHeading(line)
            if (heading != null) {
                entries.add(OutlineEntry(heading.first, heading.second,
                    slug(heading.second, used), i))
                previousPlain = null
                plainRun = 0
                i++
                continue
            }
            // Setext heading: exactly one plain buffered line underlined by
            // === / --- (a longer run is a paragraph; [parse] then reads the
            // underline as a rule / plain text, and so must we).
            val previous = previousPlain
            val level = setextUnderline(line)
            if (previous != null && plainRun == 1 && level != null) {
                entries.add(OutlineEntry(level, previous.first,
                    slug(previous.first, used), previous.second))
                previousPlain = null
                plainRun = 0
                i++
                continue
            }
            val trimmed = line.trimSpaces()
            val isPlain = trimmed.isNotEmpty() && !isThematicBreak(line) && !isQuote(line) &&
                listMarker(line) == null && !isPageBreak(line)
            plainRun = if (isPlain) plainRun + 1 else 0
            previousPlain = if (isPlain) trimmed to i else null
            i++
        }
        return entries
    }

    /** Every private author note in the document, with its source line. */
    fun notes(source: String): List<NoteEntry> {
        val lines = normalizedLines(source)
        val entries = ArrayList<NoteEntry>()
        var fence: FenceMarker? = null
        var i = 0
        while (i < lines.size) {
            val line = lines[i]
            val openFence = fence
            if (openFence != null) {
                if (openFence.closes(line)) fence = null
                i++
                continue
            }
            val newFence = FenceMarker.from(line)
            if (newFence != null) {
                fence = newFence
                i++
                continue
            }
            if (isCommentStart(line)) {
                val start = i
                val raw = ArrayList<String>()
                while (i < lines.size) {
                    raw.add(lines[i])
                    val closed = lines[i].contains("-->")
                    i++
                    if (closed) break
                }
                noteText(raw.joinToString("\n"))?.let { entries.add(NoteEntry(it, start)) }
                continue
            }
            i++
        }
        return entries
    }

    /** GitHub-style anchor slug for a heading, unique within one document
     *  via the caller-maintained `used` counts ("title", "title-1", …).
     *  Keeps letters, numbers, `_` and `-`; spaces become hyphens; all other
     *  punctuation (including inline-markup characters) is dropped — the
     *  same rule GitHub applies, so links written for GitHub keep working.
     *
     *  Iterates CODE POINTS with the full Unicode categories the Swift
     *  original sees per grapheme — letters, every number category (Nd, Nl,
     *  No: digits, Roman numerals, fractions) and combining marks (so an
     *  NFD "café" keeps its accent) — because a per-`Char` walk would drop
     *  surrogate-pair letters and non-decimal numbers, and the two parsers'
     *  anchors must be byte-identical across platforms. */
    fun slug(text: String, used: MutableMap<String, Int>): String {
        val base = StringBuilder()
        var i = 0
        val lower = text.lowercase()
        while (i < lower.length) {
            val cp = lower.codePointAt(i)
            val type = Character.getType(cp)
            val isNumber = type == Character.DECIMAL_DIGIT_NUMBER.toInt() ||
                type == Character.LETTER_NUMBER.toInt() ||
                type == Character.OTHER_NUMBER.toInt()
            val isMark = type == Character.NON_SPACING_MARK.toInt() ||
                type == Character.COMBINING_SPACING_MARK.toInt() ||
                type == Character.ENCLOSING_MARK.toInt()
            when {
                Character.isLetter(cp) || isNumber || isMark ||
                    cp == '_'.code || cp == '-'.code -> base.appendCodePoint(cp)
                cp == ' '.code -> base.append('-')
            }
            i += Character.charCount(cp)
        }
        var slug = base.toString()
        if (slug.isEmpty()) slug = "section"
        val seen = used[slug] ?: 0
        used[slug] = seen + 1
        return if (seen == 0) slug else "$slug-$seen"
    }

    /** Source split into terminator-free lines, with line endings normalised
     *  the same way [parse] does — so [outline] / [notes] line numbers match. */
    private fun normalizedLines(source: String): List<String> =
        source.replace("\r\n", "\n").replace("\r", "\n").split("\n")

    // MARK: - Thematic break

    private fun isThematicBreak(line: String): Boolean {
        val stripped = line.filter { it != ' ' && it != '\t' }
        if (stripped.length < 3) return false
        return stripped.all { it == '-' } || stripped.all { it == '*' } || stripped.all { it == '_' }
    }

    // MARK: - Block quote

    private fun isQuote(line: String): Boolean = line.dropWhile { it == ' ' }.firstOrNull() == '>'

    private fun stripQuoteMarker(line: String): String {
        var s = line.dropWhile { it == ' ' }
        if (s.firstOrNull() == '>') s = s.substring(1)
        if (s.firstOrNull() == ' ') s = s.substring(1)
        return s
    }

    // MARK: - Lists

    private data class Marker(
        val level: Int,
        val ordinal: Int?,
        val text: String,
        val task: Boolean?,
    )

    private fun listMarker(line: String): Marker? {
        // Leading whitespace → nesting depth (2 columns ≈ one level); a tab
        // advances to the next 4-column stop.
        var indent = 0
        var i = 0
        while (i < line.length) {
            val c = line[i]
            when (c) {
                ' ' -> indent += 1
                '\t' -> indent += 4 - (indent % 4)
                else -> break
            }
            i++
        }
        val body = line.substring(i)
        val first = body.firstOrNull() ?: return null

        var ordinal: Int? = null
        val rest: String

        if (first == '-' || first == '*' || first == '+') {
            rest = body.substring(1)
        } else if (first.isDigit()) {
            val digits = body.takeWhile { it.isDigit() }
            if (digits.length > 9) return null
            val afterDigits = body.substring(digits.length)
            val delim = afterDigits.firstOrNull() ?: return null
            if (delim != '.' && delim != ')') return null
            ordinal = digits.toIntOrNull() ?: 1
            rest = afterDigits.substring(1)
        } else {
            return null
        }

        // The marker must be followed by at least one space (or be empty).
        if (rest.isNotEmpty() && rest.firstOrNull() != ' ') return null
        var text = rest.dropWhile { it == ' ' }

        // GitHub task-list checkbox.
        var task: Boolean? = null
        if (text.startsWith("[ ] ") || text == "[ ]") {
            task = false
            text = text.drop(3).dropWhile { it == ' ' }
        } else if (text.lowercase().startsWith("[x] ") || text.lowercase() == "[x]") {
            task = true
            text = text.drop(3).dropWhile { it == ' ' }
        }

        return Marker(indent / 2, ordinal, text, task)
    }

    // MARK: - Tables (GFM)

    private data class TableHead(val header: List<String>, val alignments: List<ColumnAlignment>)

    private fun parseTable(header: String, delimiter: String): TableHead? {
        if (!header.contains("|")) return null
        if (!delimiter.trimSpaces().contains("-")) return null
        val delimCells = splitTableRow(delimiter, null)
        if (delimCells.isEmpty()) return null
        val alignments = ArrayList<ColumnAlignment>()
        for (cell in delimCells) {
            val c = cell.trimSpaces()
            if (c.isEmpty() || !c.all { it == '-' || it == ':' } || !c.contains('-')) return null
            val left = c.startsWith(":")
            val right = c.endsWith(":")
            alignments.add(
                when {
                    left && right -> ColumnAlignment.CENTER
                    right -> ColumnAlignment.TRAILING
                    else -> ColumnAlignment.LEADING
                }
            )
        }
        val headerCells = splitTableRow(header, null)
        if (headerCells.size != alignments.size) return null
        return TableHead(headerCells, alignments)
    }

    /** Split one table row into cells. Leading / trailing pipes are
     *  optional; escaped pipes (`\|`) stay inside a cell. */
    private fun splitTableRow(row: String, columns: Int?): List<String> {
        var trimmed = row.trimSpaces()
        if (trimmed.startsWith("|")) trimmed = trimmed.substring(1)
        if (trimmed.endsWith("|")) trimmed = trimmed.substring(0, trimmed.length - 1)

        val cells = ArrayList<String>()
        val current = StringBuilder()
        var escaped = false
        for (ch in trimmed) {
            when {
                escaped -> {
                    if (ch != '|') current.append('\\')
                    current.append(ch)
                    escaped = false
                }
                ch == '\\' -> escaped = true
                ch == '|' -> {
                    cells.add(current.toString().trimSpaces())
                    current.clear()
                }
                else -> current.append(ch)
            }
        }
        if (escaped) current.append('\\')
        cells.add(current.toString().trimSpaces())

        if (columns != null) {
            while (cells.size < columns) cells.add("")
            if (cells.size > columns) {
                val sub = ArrayList(cells.subList(0, columns))
                cells.clear()
                cells.addAll(sub)
            }
        }
        return cells
    }
}

/** Parses and matches a fenced-code delimiter (``` or ~~~), following
 *  CommonMark's "at least as long, same char" close rule. */
private class FenceMarker private constructor(
    private val char: Char,
    private val count: Int,
    private val indent: Int,
    val language: String?,
) {
    /** A closing fence: same char, at least as long, no trailing content. */
    fun closes(line: String): Boolean {
        val trimmedIndent = line.dropWhile { it == ' ' }
        val run = trimmedIndent.takeWhile { it == char }
        if (run.length < count) return false
        return trimmedIndent.drop(run.length).trim { it == ' ' || it == '\t' }.isEmpty()
    }

    /** Remove up to the opening fence's indentation from a body line. */
    fun stripIndent(line: String): String {
        var removed = 0
        var s = line
        while (removed < indent && s.firstOrNull() == ' ') {
            s = s.substring(1)
            removed++
        }
        return s
    }

    companion object {
        fun from(line: String): FenceMarker? {
            val indent = line.takeWhile { it == ' ' }.length
            if (indent > 3) return null                 // 4+ spaces = code, not a fence
            val body = line.substring(indent)
            val first = body.firstOrNull() ?: return null
            if (first != '`' && first != '~') return null
            val run = body.takeWhile { it == first }
            if (run.length < 3) return null
            val info = body.substring(run.length).trim { it == ' ' || it == '\t' }
            // An info string on a backtick fence may not contain a backtick.
            if (first == '`' && info.contains('`')) return null
            val lang = info.split(" ").firstOrNull()
            return FenceMarker(first, run.length, indent, if (lang.isNullOrEmpty()) null else lang)
        }
    }
}
