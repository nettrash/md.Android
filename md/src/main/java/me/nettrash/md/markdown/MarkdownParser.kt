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
 *
 * On matching delimiters, this port is the one that was already right, and
 * the comments below say so where it was checked. Kotlin's `String` is a
 * sequence of UTF-16 units: `startsWith`, `indexOf`, `contains`, `substring`
 * and `first()` compare units, so a combining mark, a variation selector or a
 * ZWJ after a delimiter is a unit of its own and the delimiter is still
 * found. Swift's `String` is a sequence of *grapheme clusters*, where the
 * mark fuses onto the delimiter and the guard silently stops firing — which
 * is why the Apple sibling now routes every one of these through
 * `ScalarText`, and why a differential run over the two ports found 465 of
 * 3,555 marked documents parsing differently before that change and none
 * after. No half of a surrogate pair equals any ASCII delimiter, so a `Char`
 * walk and a code-point walk take the same decisions throughout.
 *
 * What did need changing here was the other half of the same audit: two
 * whitespace sets that were *not* the ones Foundation trims with (see
 * `trimSpaces` / `trimSpacesAndNewlines`), and the ordered-list digits.
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
    data class FrontMatter(val fields: List<MetadataField>) : MarkdownBlock
    data class FootnoteDefinition(val id: String, val text: String) : MarkdownBlock
}

/** One `key: value` line of a document's front matter. Order is the order
 *  they were written in, and duplicate keys are kept rather than collapsed —
 *  this is a record of what the author wrote, not a dictionary. */
data class MetadataField(
    val key: String,
    val value: String,
)

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

// Exactly Foundation's `CharacterSet.whitespaces`, which is what the Swift
// apps trim with: Unicode's `Zs` space-separator category, CHARACTER
// TABULATION, and U+200B. Not simply `' '` and `'\t'` — that is narrower, and
// the gap is not academic: a fence line padded with a non-breaking space was
// front matter on Apple and an ordinary paragraph here, so the document's
// metadata was silently lost on one platform of three. Nor is it Kotlin's
// `isWhitespace()`, which is wider — it includes the line separators this must
// never eat, since the caller has already split on them.
//
// U+200B ZERO WIDTH SPACE is in Foundation's set and is *not* `Zs` to this
// JVM: Apple's table is frozen at a Unicode version that still classified it
// as a space separator, and Java follows the current one, which calls it a
// format character. Enumerating both sets over the whole of Unicode is what
// found it, and it is the only character they disagree about. Without the
// clause a line holding one invisible ZWSP was a blank line — a block
// separator — on Apple and a paragraph here. (`MarkdownHTML.swift` met the
// same fact from the other side and narrowed *its* trim to " \t".)
//
// Scalars do not arise: every character in the set is BMP, so a `Char` walk
// and a code-point walk take the same decisions, and no half of a surrogate
// pair is equal to any of them.
private fun String.trimSpaces(): String = trim(::isSpaceCharacter)

// Foundation's `CharacterSet.whitespacesAndNewlines`: the set above plus
// U+000A…U+000D, U+0085, and the `Zl` / `Zp` separators. Kotlin's own
// `trim()` is neither a subset nor a superset of it — it keeps U+00A0 and
// U+0085 and eats U+001C…U+001F — and the difference was live: a
// `<!-- note: … -->` whose marker was preceded by U+0085 was a note on
// Apple and no note at all here, and one preceded by U+001C the other way
// about, so the notes panel listed a different set of notes on the two
// platforms.
private fun String.trimSpacesAndNewlines(): String =
    trim {
        isSpaceCharacter(it) || it in '\u000A'..'\u000D' || it == '\u0085' ||
            Character.getType(it) == Character.LINE_SEPARATOR.toInt() ||
            Character.getType(it) == Character.PARAGRAPH_SEPARATOR.toInt()
    }

private fun isSpaceCharacter(c: Char): Boolean =
    c == '\t' || c == '\u200B' ||
        Character.getType(c) == Character.SPACE_SEPARATOR.toInt()

object MarkdownParser {

    /** Parse Markdown source into a flat list of blocks. `quoteDepth` is
     *  internal: block quotes recurse, and the cap bounds that recursion so
     *  a pathological run of `>` can't overflow the stack. */
    fun parse(source: String, quoteDepth: Int = 0): List<MarkdownBlock> {
        // Checked: exact. `replace` and `split` here match UTF-16 units, so a
        // mark on the character after a newline cannot hide the newline. The
        // Apple sibling reaches the same line list in one scalar pass.
        val lines = source
            .replace("\r\n", "\n")
            .replace("\r", "\n")
            .split("\n")
        val blocks = ArrayList<MarkdownBlock>()
        var i = 0

        // Front matter: a metadata block fenced off at the very top of the
        // file, the convention every static-site generator and note-taker
        // uses (Jekyll, Hugo, Obsidian, Quarto). Without this the opening
        // `---` reads as a thematic break and the metadata as stray prose,
        // which is how such a file used to look here. It is only front
        // matter at the very start of the document and never inside a quote.
        if (quoteDepth == 0) {
            val matter = parseFrontMatter(lines)
            if (matter != null) {
                blocks.add(MarkdownBlock.FrontMatter(matter.fields))
                i = matter.next
            }
        }

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

            // Footnote definition: `[^id]: the note`, the GitHub / Pandoc
            // convention. Collected here so it never renders where it was
            // written — the renderer gathers the definitions and prints them
            // together at the foot of the document. Soft-wrapped continuation
            // lines are absorbed the way a list item's are.
            val definition = parseFootnoteDefinition(line)
            if (definition != null) {
                var text = definition.second
                i++
                while (i < lines.size) {
                    val l = lines[i]
                    if (l.trimSpaces().isEmpty()) break
                    if (parseFootnoteDefinition(l) != null || FenceMarker.from(l) != null ||
                        isThematicBreak(l) || parseHeading(l) != null || isQuote(l) ||
                        isPageBreak(l) || isCommentStart(l) || listMarker(l) != null
                    ) break
                    text += " " + l.trimSpaces()
                    i++
                }
                blocks.add(MarkdownBlock.FootnoteDefinition(definition.first, text))
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

    // MARK: - Front matter

    /** The fields of a front-matter block and the line [parse] resumes on. */
    private data class FrontMatterScan(val fields: List<MetadataField>, val next: Int)

    /** Front matter, if the document opens with it. The opening fence must be
     *  the very first line: `---` for YAML (closed by `---` or `...`) or `+++`
     *  for TOML (closed by `+++`).
     *
     *  Three things must all hold, because the opening fence of YAML front
     *  matter is spelled exactly like a thematic break and getting this wrong
     *  *hides the reader's prose*: the fence must be closed; the line straight
     *  after it must not be blank (no generator writes front matter that way,
     *  but a document opening with a rule and a blank line is commonplace);
     *  and the block must hold at least one recognisable field. A document
     *  that opens with a horizontal rule, says something, and rules off again
     *  therefore keeps every word of it — as does `---` followed by a setext
     *  heading's text, which stays a break and a heading the way it reads
     *  everywhere else.
     *
     *  Values are read with a deliberately flat `key: value` (or `key = value`)
     *  scan rather than a YAML/TOML parser: the app has no room for one, and
     *  nothing here needs nesting. Anything the scan doesn't recognise — a
     *  list, a nested mapping, a comment — is skipped, and the block is still
     *  consumed, which is the part that matters for how the document looks.
     *  Quotes around a value are stripped, since every generator writes some. */
    private fun parseFrontMatter(lines: List<String>): FrontMatterScan? {
        val opener = lines.firstOrNull()?.trimSpaces() ?: return null
        val closers: Set<String>
        val separator: Char
        when (opener) {
            "---" -> { closers = setOf("---", "..."); separator = ':' }
            "+++" -> { closers = setOf("+++"); separator = '=' }
            else -> return null
        }

        // Nothing is committed to until all three guards pass. A blank line
        // straight after the opener means this is a rule with prose under it.
        if (lines.size <= 1 || lines[1].trimSpaces().isEmpty()) return null

        // Find the closing fence before committing to anything.
        var close = -1
        for (index in 1 until lines.size) {
            if (lines[index].trimSpaces() in closers) { close = index; break }
        }
        if (close < 0) return null

        val fields = ArrayList<MetadataField>()
        for (line in lines.subList(1, close)) {
            val trimmed = line.trimSpaces()
            // Skip comments, list items, and anything with no separator —
            // including a nested mapping's indented children, whose parent
            // key was already recorded with an empty value.
            // Checked: exact. `startsWith` and `indexOf` are UTF-16 searches,
            // so `#` or `:` followed by a combining mark is still found here
            // — the Apple sibling had to be taught to see them.
            if (trimmed.isEmpty() || trimmed.startsWith("#") || trimmed.startsWith("-")) continue
            val split = trimmed.indexOf(separator)
            if (split < 0) continue
            val key = trimmed.substring(0, split).trimSpaces()
            if (key.isEmpty()) continue
            var value = trimmed.substring(split + 1).trimSpaces()
            for (quote in listOf("\"", "'")) {
                if (value.length >= 2 && value.startsWith(quote) && value.endsWith(quote)) {
                    value = value.substring(1, value.length - 1)
                    break
                }
            }
            fields.add(MetadataField(key, value))
        }
        // No field at all means this was never metadata — most likely a
        // thematic break with prose beneath it, or a setext heading. Report
        // no front matter and let the ordinary block parser have the lines.
        if (fields.isEmpty()) return null
        return FrontMatterScan(fields, close + 1)
    }

    /** The front matter of [source], or an empty list if it has none — the
     *  document's own metadata, for anything that needs to know the author or
     *  the title rather than just render the text. */
    fun frontMatter(source: String): List<MetadataField> {
        for (block in parse(source)) {
            if (block is MarkdownBlock.FrontMatter) return block.fields
        }
        return emptyList()
    }

    // MARK: - Footnotes

    /** A footnote definition line — `[^id]: the note` — as `(id, text)`, or
     *  null when the line is not one.
     *
     *  Identifiers are **ASCII** letters, digits, `-` and `_`. That is
     *  narrower than Pandoc allows, and deliberately so twice over: the
     *  identifier travels through the HTML renderer's escaping pass and into
     *  an `id` attribute, and a character set with nothing to escape cannot
     *  come out the other side spelled differently from the definition it has
     *  to match — and it is the same set the renderer's reference pattern
     *  accepts, so a definition can never be written that no reference is able
     *  to name. (Allowing any Unicode letter here would do exactly that: the
     *  note would be parsed, found unreferenced, and printed on its own at the
     *  foot of the page, which is a puzzling thing to hand an author.) */
    fun parseFootnoteDefinition(line: String): Pair<String, String>? {
        val trimmed = line.trimSpaces()
        if (!trimmed.startsWith("[^")) return null
        val afterMarker = trimmed.substring(2)
        val close = afterMarker.indexOf(']')
        if (close < 0) return null
        val id = afterMarker.substring(0, close)
        if (id.isEmpty() || !isFootnoteIdentifier(id)) return null
        if (afterMarker.getOrNull(close + 1) != ':') return null
        return id to afterMarker.substring(close + 2).trimSpaces()
    }

    /** Whether every character of [id] may stand in a footnote identifier:
     *  an ASCII letter, an ASCII digit, `-` or `_`.
     *
     *  ASCII-only, and a plain `Char` walk is therefore right: no character
     *  outside ASCII is accepted, so surrogate pairs and combining marks
     *  cannot arise. The set is exactly the one the renderer's reference
     *  pattern `[A-Za-z0-9_-]` accepts (see MarkdownHtml), which is the whole
     *  point — anything wider would let an author write a definition that no
     *  reference could ever name, and it would be printed, uncited, at the
     *  foot of the page. (Contrast `isGraphNameChar` there, which is
     *  deliberately Unicode-wide: nothing on the other side of it narrows to
     *  ASCII.) */
    private fun isFootnoteIdentifier(id: String): Boolean = id.all {
        it.code < 128 && (it.isLetter() || it.isDigit() || it == '-' || it == '_')
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

    /** `<!-- note: … -->` → the note's text; any other comment → null.
     *
     *  `indexOf` / `startsWith` / `substring` are UTF-16 exact and need no
     *  scalar form — that is the whole reason this port never had the defect
     *  `MarkdownParser.swift` was carrying. The trims are the part that had
     *  to change: `trim()` is Kotlin's own whitespace set, not Foundation's.
     *  See `trimSpacesAndNewlines`. */
    private fun noteText(comment: String): String? {
        val open = comment.indexOf("<!--")
        if (open < 0) return null
        val closeIndex = comment.indexOf("-->")
        val close = if (closeIndex >= 0) closeIndex else comment.length
        if (open + 4 > close) return null
        val body = comment.substring(open + 4, close).trimSpacesAndNewlines()
        if (!body.lowercase().startsWith("note:")) return null
        return body.substring(5).trimSpacesAndNewlines()
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
        // Skip the front matter, exactly as [parse] does. Its closing `---`
        // would otherwise underline the last metadata line into a phantom
        // setext heading — one the rendered document does not contain, whose
        // slug would then push every real heading's anchor out of step with
        // the ids [MarkdownHtml] assigns, so the TOC would scroll to nothing.
        var i = parseFrontMatter(lines)?.next ?: 0
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
            // A footnote definition is not plain text either: [parse] claims
            // the line, so an underline beneath it is a rule and not a setext
            // heading. Counting it as plain would list a heading the rendered
            // document has not got, and its slug would drag every later
            // anchor out of step — the same failure front matter had.
            val isPlain = trimmed.isNotEmpty() && !isThematicBreak(line) && !isQuote(line) &&
                listMarker(line) == null && !isPageBreak(line) &&
                parseFootnoteDefinition(line) == null
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
        // Skip the front matter, as [parse] and [outline] do — a comment
        // inside the metadata block is not part of the document, so listing
        // it would send the notes panel to a line the preview never renders.
        var i = parseFrontMatter(lines)?.next ?: 0
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
     *  anchors must be byte-identical across platforms.
     *
     *  Checked: this is the shape the Apple sibling has now been given, a
     *  scalar walk keeping the same three families, and the categories agree
     *  for everything an author is going to put in a heading. They cannot be
     *  made to agree everywhere: enumerating both keep-sets over the whole of
     *  Unicode leaves ~9,800 code points that are letters to Swift's ICU and
     *  not to this JVM's tables (recent additions, mostly), which is a
     *  Unicode-version difference no code here can close. */
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
        } else if (isAsciiDigit(first)) {
            // ASCII digits only, as CommonMark says. `Char.isDigit()` also
            // admits every other decimal script, and `toIntOrNull` then reads
            // ٣ as 3 while Swift's `Int(_:)` reads it as nothing and falls
            // back to 1 — the same list, numbered differently on the two
            // platforms. With this set the ordinal always parses.
            val digits = body.takeWhile(::isAsciiDigit)
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

    /** An ASCII digit — the whole of CommonMark's ordered-list marker. */
    private fun isAsciiDigit(c: Char): Boolean = c in '0'..'9'

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
 *  CommonMark's "at least as long, same char" close rule.
 *
 *  Every comparison here is against a single ASCII `Char`, which is exact:
 *  no half of a surrogate pair equals one, and a combining mark is a `Char`
 *  of its own rather than something fused to the backtick before it — which
 *  is precisely what the Apple port got wrong. The trims, though, have to be
 *  Foundation's set and not Kotlin's: see `trimSpaces`. A closing fence
 *  padded with a non-breaking space closed the block on Apple and did not
 *  here, so the rest of the document was swallowed into the code block on
 *  one platform of three. */
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
        return trimmedIndent.drop(run.length).trimSpaces().isEmpty()
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
            val info = body.substring(run.length).trimSpaces()
            // An info string on a backtick fence may not contain a backtick.
            if (first == '`' && info.contains('`')) return null
            val lang = info.split(" ").firstOrNull()
            return FenceMarker(first, run.length, indent, if (lang.isNullOrEmpty()) null else lang)
        }
    }
}
