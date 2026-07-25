/*
 * MarkdownHtml.kt
 * md (Android)
 *
 * Serializes the parsed block model to a self-contained, themed HTML
 * document — the Print / Save-as-PDF path. A faithful Kotlin port of the
 * iOS / macOS `MarkdownHTML.swift`: it reuses the same `MarkdownParser`
 * the on-screen preview uses, then emits HTML with embedded typewriter
 * CSS (serif prose, monospace code) and the paper-and-ink palette in a
 * light or dark variant. The HTML is loaded into an offscreen WebView and
 * handed to Android's PrintManager.
 */

package me.nettrash.md.markdown

import java.util.Locale

object MarkdownHtml {

    /** Fenced-block info strings that select the bundled Graphviz engine,
     *  mapped to the Graphviz layout program that lays the graph out. ```dot
     *  is the everyday one; the rest name a layout directly, which is how
     *  Graphviz itself is invoked (`neato -Tsvg`, `circo -Tsvg`, …). Every
     *  value must be one of `Viz.engines` — an unknown name makes the render
     *  throw and the block falls back to its source text. */
    val graphvizEngines: Map<String, String> = mapOf(
        "dot" to "dot", "graphviz" to "dot", "gv" to "dot",
        "neato" to "neato", "circo" to "circo", "fdp" to "fdp", "sfdp" to "sfdp",
        "twopi" to "twopi", "osage" to "osage", "patchwork" to "patchwork",
    )

    /**
     * A full HTML document for `source`, themed light or dark.
     *
     * Rich renderers (KaTeX math, Mermaid, Graphviz, PlantUML) load entirely
     * from bundled assets under `rich/` — no network. Each heavy engine is
     * pulled in only when the document uses it (PlantUML alone is 7 MB): the
     * KaTeX / Mermaid / Viz scripts are included conditionally here, and
     * `md-init.js` dynamically imports the PlantUML engine only when a
     * `.plantuml` block exists.
     * `md-init.js` itself is tiny and always runs; when it finishes it flags
     * `data-md-render-complete` (which the print / PDF path waits on).
     *
     * The WebView must load this from an origin that serves `rich/` (see
     * RichWebView) so `md-init.js`'s ES-module import resolves — offline.
     *
     * [export] styles the document for paper / PDF instead of the live
     * preview: a smaller, print-typical body size (everything else is
     * em-based and scales with it), code blocks wrap long lines — paper
     * can't scroll, so an overflowing line would be clipped at the block's
     * edge — and the page is plain white in the light palette regardless
     * of [dark]: the tinted paper and cream-on-carbon ink are screen
     * themes, not something to fix into a printout.
     */
    fun document(source: String, title: String, dark: Boolean, export: Boolean = false): String {
        // Deliberate shadow, mirroring the Apple siblings'
        // `let dark = dark && !export`.
        @Suppress("NAME_SHADOWING")
        val dark = dark && !export
        // A raw diagram document — an opened `.puml` or `.gv`: bare diagram
        // source with no fence (see [isRawPlantUML] / [isRawGraphviz]). Render
        // the whole file as one diagram rather than parsing it as Markdown,
        // which would only show the `@startuml…` / `digraph…` text. Everything
        // else — a `.md` / `.txt` file — is parsed as Markdown exactly as before.
        val body: String
        val needsMath: Boolean
        val needsMermaid: Boolean
        val needsPlantuml: Boolean
        val needsGraphviz: Boolean
        val needsHighlight: Boolean

        if (isRawPlantUML(source)) {
            // md-init.js turns the `.plantuml` container into an SVG offline; on
            // failure it restores the source, so an invalid diagram still shows
            // its text. No Markdown here, so no math / Mermaid / highlighting.
            body = "<div class=\"plantuml\">${escape(source)}</div>"
            needsMath = false
            needsMermaid = false
            needsPlantuml = true
            needsGraphviz = false
            needsHighlight = false
        } else if (isRawGraphviz(source)) {
            // An opened `.gv`: bare DOT source, rendered as one diagram the
            // same way a raw `.puml` is (see [isRawGraphviz]).
            body = "<div class=\"graphviz\" data-engine=\"dot\">${escape(source)}</div>"
            needsMath = false
            needsMermaid = false
            needsPlantuml = false
            needsGraphviz = true
            needsHighlight = false
        } else {
            val blocks = MarkdownParser.parse(source)
            // Top-level headings carry a GitHub-style anchor id, so `[…](#slug)`
            // links navigate and the table of contents can scroll the preview.
            // The slugs come from the same `MarkdownParser.slug` the TOC uses,
            // so the two always agree.
            val slugs = HashMap<String, Int>()
            val rendered = blocks.joinToString("\n") { block ->
                if (block is MarkdownBlock.Heading) {
                    val id = MarkdownParser.slug(block.text, slugs)
                    "<h${block.level} id=\"$id\">${inline(block.text)}</h${block.level}>"
                } else {
                    renderBlock(block)
                }
            }

            // Footnotes are a whole-document affair: the references are
            // numbered by where the reader meets them, and the notes are
            // gathered at the foot of the page rather than left where they
            // were written. Both need the finished body, so they happen here.
            body = withFootnotes(rendered, blocks.filterIsInstance<MarkdownBlock.FootnoteDefinition>())

            // Every engine is needed iff the render actually emitted its
            // container. Keying off the produced markup — rather than scanning
            // the block list for fence languages — is what makes this correct
            // for a diagram nested inside a block quote: `renderBlock` recurses
            // into quoted blocks, so a scan of the top-level blocks alone would
            // emit the container and then never include the engine, leaving the
            // diagram stuck as its own source text. It is also what has always
            // kept KaTeX out of prose with stray dollar signs in it: `inline()`
            // emits a math span only for a real formula, never for "$5".
            //
            // A code block that merely *quotes* one of these strings can't
            // trigger a false positive: its content is HTML-escaped, so the
            // quotes and angle brackets no longer match.
            needsMermaid = body.contains("<pre class=\"mermaid\">")
            needsPlantuml = body.contains("<div class=\"plantuml\">")
            needsGraphviz = body.contains("<div class=\"graphviz\"")
            needsMath = body.contains("md-mathi") || body.contains("md-mathd")
            // A highlightable code block is one `renderBlock` tagged with a real
            // code language (`class="language-…"`, below). Keyed off the emitted
            // markup for the same reason as the engines above — a block nested in
            // a quote is reached by the recursion, a scan of the top-level blocks
            // would miss it — and it excludes the diagram / math / data fences,
            // which never get that class, and a bare fence, which has no language.
            needsHighlight = body.contains("<code class=\"language-")
        }

        val head = StringBuilder()
        if (needsMath) {
            // mhchem (KaTeX's chemistry extension: `\ce{…}`, `\pu{…}`) rides the
            // same `needsMath` gate — `\ce{}` only ever appears inside the math
            // delimiters that produce `.md-mathi` / `.md-mathd`, so it needs no
            // signal of its own. It must load right after KaTeX and share its
            // `defer`: deferred classic scripts run in document order, so KaTeX
            // defines the global `katex`, then mhchem registers its macros onto
            // it, before md-init.js (a deferred module, last) calls
            // katex.render(). Both are the same KaTeX 0.17.0 build (MIT) —
            // mhchem silently no-ops against a mismatched KaTeX, so the two
            // files must always be replaced together.
            head.append(
                """
                <link rel="stylesheet" href="rich/katex.min.css">
                <script defer src="rich/katex.min.js"></script>
                <script defer src="rich/mhchem.min.js"></script>
                """.trimIndent()
            )
        }
        if (needsMermaid) head.append("\n<script src=\"rich/mermaid.min.js\"></script>")
        // Viz.js is Graphviz. PlantUML needs it for its own Graphviz-backed
        // layouts (class, activity, …), and a ```dot block is that same engine
        // addressed directly — so the two share one script include.
        if (needsPlantuml || needsGraphviz) head.append("\n<script src=\"rich/viz-global.js\"></script>")
        // highlight.js (BSD-3-Clause, v11.11.1, ~40-language "common" build):
        // syntax-highlights the ```lang code blocks tagged `class="language-…"`.
        // Included only when the document has such a block — off unless used,
        // like every engine here. A standalone engine (it depends on nothing and
        // nothing depends on it), so a plain blocking include is enough: it runs
        // during head parse, defining the `hljs` global before the deferred
        // md-init.js module calls hljs.highlightElement(). Highlighting is done
        // in that live DOM, so it reaches preview / print / PDF / HTML export —
        // but NOT EPUB, which snapshots the source HTML before any script runs.
        if (needsHighlight) head.append("\n<script src=\"rich/highlight.min.js\"></script>")

        return """
            <!DOCTYPE html>
            <html lang="en">
            <head>
            <meta charset="utf-8">
            <meta name="viewport" content="width=device-width, initial-scale=1">
            <title>${escape(title)}</title>
            <style>${css(dark, export)}</style>
            $head
            </head>
            <body data-md-dark="${if (dark) "1" else "0"}">
            $body
            <script type="module" src="rich/md-init.js"></script>
            </body>
            </html>
        """.trimIndent()
    }

    /** True when [source] is a raw PlantUML document rather than Markdown — its
     *  first non-blank, non-comment line opens a PlantUML diagram (`@startuml`,
     *  `@startmindmap`, `@startgantt`, `@startjson`, …). That is exactly what an
     *  opened `.puml` file is: bare diagram source with no ```plantuml fence.
     *  Such a document is rendered as a single diagram (see [document]) instead
     *  of being parsed as Markdown, which would only show the source text.
     *  PlantUML line comments (`'…`) and blank lines before the opener are
     *  skipped, so a commented header doesn't hide it. */
    fun isRawPlantUML(source: String): Boolean {
        for (raw in lines(source)) {
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("'")) continue
            return line.startsWith("@start")
        }
        return false
    }

    /** Split [source] into lines, whatever it uses to end them.
     *
     *  Not `split("\n")`: that leaves the CR of a CRLF file on the end of every
     *  line. Kotlin's `trim()` happens to strip it, so the Apple siblings' bug —
     *  where a Swift `Character` is a grapheme cluster and CRLF is *one* of
     *  them, so splitting on "\n" matched nothing and handed back the whole file
     *  as a single line — never bit here. Splitting on the newline set makes
     *  that independent of the trim, and takes care of a lone CR (classic Mac)
     *  as well, which `split("\n")` would not have split at all. The "\r\n"
     *  delimiter is listed first so a CRLF is consumed whole rather than
     *  yielding a spurious empty line.
     *
     *  NEL / LINE SEPARATOR / PARAGRAPH SEPARATOR are listed too because the
     *  Apple side splits on Foundation's newline *set*, which includes them.
     *  Without them a file using those separators would be one line here and
     *  several there — and Java's `Character.isWhitespace` excludes NEL, so
     *  `trim()` would not have rescued it either. */
    private fun lines(source: String): List<String> =
        source.split("\r\n", "\n", "\r", "\u0085", "\u2028", "\u2029")

    /** True when [source] is a raw Graphviz DOT document rather than Markdown —
     *  its first non-blank, non-comment line opens a graph (`digraph {`,
     *  `graph G {`, `strict digraph {`). That is exactly what an opened `.gv`
     *  file is: bare DOT source with no ```dot fence, so it renders as a single
     *  diagram (see [document]) instead of being parsed as Markdown, which
     *  would only show the source text. DOT's `//` and `/* … */` comments and
     *  blank lines before the opener are skipped, and its keywords are
     *  case-insensitive.
     *
     *  Unlike PlantUML's `@start…`, `graph` is an ordinary English word, so the
     *  opener is matched against DOT's actual grammar — `[strict] (graph |
     *  digraph) [ID] '{'` — and not merely by prefix. A document that begins
     *  "graph theory is a branch of…" has three words where DOT allows at most
     *  one name and then a brace, so it stays Markdown. (A stray `{` somewhere
     *  in the file is no help either: a KaTeX formula or a JSON sample supplies
     *  one in plenty of perfectly ordinary documents.)
     *
     *  DOT's third comment form, a `#` line, is deliberately *not* skipped:
     *  every Markdown heading starts with `#`, and skipping those would let the
     *  check see straight past the title of an ordinary document to whatever
     *  prose follows. The cost is only that a `.gv` file opening with a `#`
     *  line renders as Markdown — it is a C-preprocessor artifact, vanishingly
     *  rare in hand-written DOT — and the file's text is still shown either way. */
    fun isRawGraphviz(source: String): Boolean {
        if (!source.contains("{")) return false
        for (raw in lines(source)) {
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("//") || line.startsWith("/*")) continue
            var head = line.lowercase(Locale.ROOT)
            if (head.startsWith("strict ")) head = head.removePrefix("strict ").trim()
            // `digraph` is tested first: it also has `graph` inside it, and a
            // prefix match on the shorter word would misread the longer one.
            for (keyword in listOf("digraph", "graph")) {
                if (!head.startsWith(keyword)) continue
                return isGraphHeader(head.substring(keyword.length))
            }
            return false
        }
        return false
    }

    /** Whether what follows a `graph` / `digraph` keyword is the rest of a DOT
     *  graph header: an optional name, then the opening brace. The name may be
     *  a bare identifier or a quoted string; the brace may be on a later line,
     *  in which case nothing at all follows here. */
    private fun isGraphHeader(tail: String): Boolean {
        var rest = tail.dropWhile { it == ' ' || it == '\t' }
        // `digraph` / `digraph {` — no name.
        if (rest.isEmpty() || rest.startsWith("{")) return true
        // A name must have been separated from the keyword by space or brace;
        // otherwise this is just a longer word ("digraphs", "graphviz").
        val separator = tail.firstOrNull()
        if (separator != ' ' && separator != '\t') return false
        if (rest.startsWith("\"")) {
            val close = rest.indexOf('"', startIndex = 1)
            if (close < 0) return false
            rest = rest.substring(close + 1)
        } else {
            val name = rest.takeWhile { it.isGraphNameChar() }
            if (name.isEmpty()) return false
            rest = rest.substring(name.length)
        }
        rest = rest.dropWhile { it == ' ' || it == '\t' }
        return rest.isEmpty() || rest.startsWith("{")
    }

    /** Whether this character can appear in a bare (unquoted) DOT graph name.
     *
     *  Deliberately wider than `isLetter() || isDigit() || '_'`, to agree with
     *  the Apple siblings character for character. Two gaps would otherwise
     *  open up, and both make the same file a diagram on one platform and
     *  plain text on the other:
     *
     *  - **Combining marks.** A Swift `Character` is a grapheme cluster, so in
     *    `digraph café {` written in NFD (`e` + U+0301) the accented letter is
     *    one letter to Swift. A Kotlin `Char` is a UTF-16 unit, so the mark is
     *    a separate character of category Mn and would end the name at `caf`,
     *    leaving `é {` — no brace where one is required. Marks only ever
     *    follow a base character, so accepting them is safe.
     *  - **Non-decimal numerals.** Swift's `isNumber` spans Nd, Nl and No;
     *    Kotlin's `isDigit()` is Nd alone. `digraph Ⅹ {` (U+2169, Nl) parses
     *    on Apple and would not here.
     *
     *  DOT's own grammar is narrower than either — alphabetics, underscores
     *  and digits, not starting with a digit — so both sides are a superset,
     *  and a name this accepts but Graphviz rejects simply renders as the
     *  engine's own error, which is the behaviour any malformed graph gets. */
    private fun Char.isGraphNameChar(): Boolean =
        isLetterOrDigit() || this == '_' || category in NAME_CATEGORIES

    private val NAME_CATEGORIES = setOf(
        CharCategory.NON_SPACING_MARK,        // Mn — the NFD combining accent
        CharCategory.COMBINING_SPACING_MARK,  // Mc
        CharCategory.ENCLOSING_MARK,          // Me
        CharCategory.LETTER_NUMBER,           // Nl — Ⅹ
        CharCategory.OTHER_NUMBER,            // No — ½
    )

    // MARK: - Blocks

    private fun renderBlock(block: MarkdownBlock): String = when (block) {
        is MarkdownBlock.Heading -> "<h${block.level}>${inline(block.text)}</h${block.level}>"
        is MarkdownBlock.Paragraph ->
            // Soft breaks are inserted inside `inline` (before protected math /
            // code spans are restored) so a multi-line display-math span keeps
            // its own internal newlines instead of getting <br>s injected.
            "<p>${inline(block.text, softBreaks = true)}</p>"
        is MarkdownBlock.ListBlock -> renderList(block.items, block.ordered)
        // A fenced block's info string selects a rich renderer; md-init.js turns
        // these containers into diagrams / formulas in the WebView.
        is MarkdownBlock.CodeBlock -> when (val lang = block.language.orEmpty().lowercase(Locale.ROOT)) {
            "mermaid" -> "<pre class=\"mermaid\">${escape(block.code)}</pre>"
            "plantuml", "puml", "plant-uml" -> "<div class=\"plantuml\">${escape(block.code)}</div>"
            // ```dot (and the layout-named aliases) — Viz.js renders the DOT
            // source with the engine named by the info string.
            in graphvizEngines ->
                "<div class=\"graphviz\" data-engine=\"${graphvizEngines[lang]}\">${escape(block.code)}</div>"
            // Data pasted straight out of a spreadsheet, drawn as a table while
            // the source stays the data — so it can be re-pasted and re-sorted
            // without hand-editing a grid of pipes.
            "csv", "tsv" -> renderDelimited(block.code, if (lang == "tsv") '\t' else ',')
            "math", "latex", "tex" -> "<div class=\"md-mathd\">${escape(block.code)}</div>"
            // A fenced block with a real code language (```swift, ```js, …) is
            // tagged for highlight.js, which highlightElement()s it in the
            // WebView (see md-init.js). The diagram / math / data languages are
            // handled above, so by here `lang` is either such a code language or
            // empty; a bare fence with no info string stays a plain block — there
            // is no language to key off, and letting hljs guess one would colour
            // prose and shell transcripts nobody marked as code.
            else -> if (lang.isEmpty()) {
                "<pre><code>${escape(block.code)}</code></pre>"
            } else {
                "<pre><code class=\"language-${escape(lang)}\">${escape(block.code)}</code></pre>"
            }
        }
        is MarkdownBlock.Quote ->
            "<blockquote>\n${block.blocks.joinToString("\n") { renderBlock(it) }}\n</blockquote>"
        is MarkdownBlock.Table -> renderTable(block.header, block.alignments, block.rows)
        MarkdownBlock.ThematicBreak -> "<hr>"
        // In the preview a subtle dashed rule; in export / print it becomes
        // a real page boundary (see the CSS).
        MarkdownBlock.PageBreak -> "<div class=\"md-pagebreak\"></div>"
        // Private author notes never reach the rendered document — they
        // live in the editor and the notes panel only.
        is MarkdownBlock.Note -> ""
        // Metadata about the document, not part of it. It is parsed so the
        // fields are available (and so the opening `---` stops being read as
        // a horizontal rule), but nothing is drawn — which is what every
        // tool that understands front matter does.
        is MarkdownBlock.FrontMatter -> ""
        // Nothing is drawn where the definition was written; [document]
        // collects them all and prints them at the foot of the page.
        is MarkdownBlock.FootnoteDefinition -> ""
    }

    private fun renderList(items: List<ListItem>, ordered: Boolean): String {
        val rows = StringBuilder()
        for (item in items) {
            val indent = String.format(Locale.ROOT, "%.2f", item.level * 1.6)
            val marker = when {
                item.task != null -> if (item.task) "&#9745;" else "&#9744;"  // ☑ / ☐
                ordered && item.ordinal != null -> "${item.ordinal}."
                else -> "&bull;"
            }
            val done = if (item.task == true) " done" else ""
            rows.append(
                "<div class=\"md-item$done\" style=\"padding-left:${indent}em\">" +
                    "<span class=\"md-marker\">$marker</span>" +
                    "<span>${inline(item.text)}</span></div>"
            )
        }
        return "<div class=\"md-list\">$rows</div>"
    }

    /** Render a ```csv / ```tsv block as a table: first row the header, the
     *  rest the body. A block that parses to nothing stays a code block, so
     *  nothing the author wrote disappears. */
    private fun renderDelimited(code: String, separator: Char): String {
        val table = delimitedTable(code, separator)
            ?: return "<pre><code>${escape(code)}</code></pre>"
        return renderTable(table.header, table.alignments, table.rows)
    }

    /** The table a ```csv / ```tsv block describes — header row, inferred
     *  alignments, body rows. */
    data class DelimitedTable(
        val header: List<String>,
        val alignments: List<ColumnAlignment>,
        val rows: List<List<String>>,
    )

    /** The table a ```csv / ```tsv block describes, or null when the block
     *  parses to nothing.
     *
     *  Split out from [renderDelimited] because the LaTeX export owes the
     *  same spreadsheet the same table: the number rule below is subtle
     *  enough (see [isDecimalNumber]) that a second copy of it would drift,
     *  and a column that is right-aligned in the PDF and left-aligned in
     *  the `.tex` is exactly the kind of difference nobody would look for. */
    fun delimitedTable(code: String, separator: Char): DelimitedTable? {
        val rows = parseDelimited(code, separator)
        val header = rows.firstOrNull()
        if (header == null || header.isEmpty()) return null
        val body = rows.drop(1)
        // A column whose every filled cell is a number is right-aligned, the
        // way a spreadsheet would show it — decimal points then line up, which
        // is most of what makes a table of figures readable.
        val columns = maxOf(header.size, body.maxOfOrNull { it.size } ?: 0)
        val alignments = (0 until columns).map { column ->
            val cells = body.mapNotNull { row ->
                // Only ASCII padding is stripped, and deliberately: Foundation's
                // `.whitespaces` also contains U+200B, which a Zs-based trim
                // does not, so the two platforms would align the same
                // spreadsheet differently over an invisible character.
                row.getOrNull(column)?.trim(' ', '\t')?.takeIf { it.isNotEmpty() }
            }
            if (cells.isNotEmpty() && cells.all { isDecimalNumber(it) }) ColumnAlignment.TRAILING
            else ColumnAlignment.LEADING
        }
        return DelimitedTable(header, alignments, body)
    }

    /** Foundation's `CharacterSet.whitespaces` — the `Zs` category plus tab —
     *  which is what the Apple siblings trim a cell with. Kotlin's own `trim()`
     *  is wider: it eats the line breaks a quoted field is allowed to contain,
     *  so a cell holding "\n5" would count as a number here and not there.
     *  (MarkdownParser trims the same way, for the same reason.) */
    private fun String.trimSpaces(): String =
        trim { it == '\t' || Character.getType(it) == Character.SPACE_SEPARATOR.toInt() }

    /**
     * Whether [cell] is a plain decimal number — the only thing worth
     * right-aligning in a column of figures.
     *
     * Deliberately **not** `toDoubleOrNull()`. Java's parser and Swift's
     * `Double(_:)` disagree in both directions: Swift takes hex (`0x10`) and
     * `inf` / `nan` in any casing, Java takes a trailing `f`/`d` type suffix
     * and only the exact spellings `Infinity` / `NaN`. Either way a column
     * would right-align on one platform and left-align on the other over a
     * difference in two standard libraries that has nothing to do with what
     * the author wrote. Spelling the grammar out — optional sign, digits with
     * an optional decimal point, optional exponent — is the same rule
     * everywhere, and it is the honest one: a hex literal is not a figure
     * whose decimal point can line up with anything.
     *
     * Hand-written rather than a `Regex` on purpose: `\d` and `\s` mean
     * different things on the desktop JVM and on Android's ICU engine, which
     * this project has already been bitten by.
     */
    fun isDecimalNumber(cell: String): Boolean {
        var i = 0
        fun takeDigits(): Int {
            val from = i
            while (i < cell.length && cell[i] in '0'..'9') i++
            return i - from
        }

        if (i < cell.length && (cell[i] == '+' || cell[i] == '-')) i++
        var digits = takeDigits()
        if (i < cell.length && cell[i] == '.') {
            i++
            digits += takeDigits()
        }
        if (digits == 0) return false

        if (i < cell.length && (cell[i] == 'e' || cell[i] == 'E')) {
            i++
            if (i < cell.length && (cell[i] == '+' || cell[i] == '-')) i++
            if (takeDigits() == 0) return false
        }
        return i == cell.length
    }

    /** Split delimiter-separated text into rows of fields, per RFC 4180: a
     *  field may be quoted, a quoted field may contain the separator and line
     *  breaks, and a doubled quote inside one is a literal quote. A quote that
     *  is not at the start of a field is just a character (`5" pipe`).
     *
     *  Line endings are normalised first rather than matched. A Kotlin `Char`
     *  is a UTF-16 unit, so — unlike Swift, where CRLF is a single grapheme
     *  cluster and a comparison against "\n" silently never fires — matching
     *  would work here; but the CR would then be left on the end of the last
     *  field of every row, and a lone CR (classic Mac) would not break a row
     *  at all. Normalising takes care of both, and keeps the three apps
     *  parsing a spreadsheet export identically. */
    fun parseDelimited(text: String, separator: Char): List<List<String>> {
        val normalized = text.replace("\r\n", "\n").replace("\r", "\n")
        val rows = ArrayList<List<String>>()
        var row = ArrayList<String>()
        val field = StringBuilder()
        var quoted = false
        var i = 0

        while (i < normalized.length) {
            val character = normalized[i]
            if (quoted) {
                if (character == '"') {
                    // A doubled quote is one literal quote; a single one ends
                    // the quoted run.
                    if (i + 1 < normalized.length && normalized[i + 1] == '"') {
                        field.append('"')
                        i += 2
                        continue
                    }
                    quoted = false
                } else {
                    field.append(character)
                }
                i++
                continue
            }
            when {
                character == '"' && field.isEmpty() -> quoted = true
                character == separator -> {
                    row.add(field.toString())
                    field.setLength(0)
                }
                character == '\n' -> {
                    row.add(field.toString())
                    field.setLength(0)
                    rows.add(row)
                    row = ArrayList()
                }
                else -> field.append(character)
            }
            i++
        }
        // A file that does not end in a newline still has a last row.
        if (field.isNotEmpty() || row.isNotEmpty()) {
            row.add(field.toString())
            rows.add(row)
        }
        return rows
    }

    private fun renderTable(
        header: List<String>,
        alignments: List<ColumnAlignment>,
        rows: List<List<String>>,
    ): String {
        fun align(i: Int): String = when (alignments.getOrNull(i)) {
            ColumnAlignment.CENTER -> "center"
            ColumnAlignment.TRAILING -> "right"
            else -> "left"
        }
        val html = StringBuilder("<table><thead><tr>")
        header.forEachIndexed { i, cell ->
            html.append("<th style=\"text-align:${align(i)}\">${inline(cell)}</th>")
        }
        html.append("</tr></thead><tbody>")
        for (row in rows) {
            html.append("<tr>")
            row.forEachIndexed { i, cell ->
                html.append("<td style=\"text-align:${align(i)}\">${inline(cell)}</td>")
            }
            html.append("</tr>")
        }
        html.append("</tbody></table>")
        return html.toString()
    }

    // MARK: - Footnotes

    /** The placeholder [inline] leaves behind for `[^id]`, and the pattern
     *  [withFootnotes] reads it back with. */
    private const val FOOTNOTE_MARKER = """<sup class="md-fnref" data-fn="([A-Za-z0-9_-]+)"></sup>"""

    /** One reference in the finished body: where it sits, which note it cites,
     *  and how many times that note has been cited up to and including it. */
    private data class FootnoteReference(val range: IntRange, val id: String, val occurrence: Int)

    /** Turn the placeholder references [inline] left in [body] into numbered
     *  links, and append the footnotes themselves.
     *
     *  Numbering is by order of first reference, which is the order a reader
     *  meets them — not the order the definitions happen to be written in.
     *  Two cases are deliberate rather than incidental:
     *
     *  - A reference with **no definition** is not a footnote at all, so it
     *    goes back to being the text the author typed. Linking it to nothing
     *    would be worse than leaving it alone.
     *  - A definition that is **never referenced** is still printed, after the
     *    referenced ones. Dropping it would silently discard something the
     *    author wrote; it simply gets no back-link, having nowhere to go back
     *    to. */
    private fun withFootnotes(
        body: String,
        definitions: List<MarkdownBlock.FootnoteDefinition>,
    ): String {
        val regex = runCatching { Regex(FOOTNOTE_MARKER) }.getOrNull() ?: return body
        val matches = regex.findAll(body).toList()
        if (matches.isEmpty() && definitions.isEmpty()) return body

        // First definition wins if an id is defined twice, matching how a
        // duplicate link reference behaves.
        val defined = HashMap<String, String>()
        for (definition in definitions) {
            if (!defined.containsKey(definition.id)) defined[definition.id] = definition.text
        }

        // Walk the references forward once: assign each id its number, and
        // each individual reference its occurrence, so repeated references to
        // one footnote each get a distinct anchor to come back to.
        val references = ArrayList<FootnoteReference>()
        val occurrences = HashMap<String, Int>()
        val number = HashMap<String, Int>()
        val ordered = ArrayList<String>()
        for (match in matches) {
            val id = match.groupValues[1]
            val occurrence = (occurrences[id] ?: 0) + 1
            occurrences[id] = occurrence
            references.add(FootnoteReference(match.range, id, occurrence))
            if (defined.containsKey(id) && number[id] == null) {
                number[id] = ordered.size + 1
                ordered.add(id)
            }
        }
        for (definition in definitions) {
            if (number[definition.id] != null) continue
            number[definition.id] = ordered.size + 1
            ordered.add(definition.id)
        }

        // Substitute back-to-front so the earlier ranges stay valid.
        val result = StringBuilder(body)
        for (reference in references.asReversed()) {
            val n = number[reference.id]
            val replacement = if (n != null && defined.containsKey(reference.id)) {
                val anchor =
                    if (reference.occurrence == 1) "fnref-$n" else "fnref-$n-${reference.occurrence}"
                "<sup class=\"md-fnref\" id=\"$anchor\"><a href=\"#fn-$n\">$n</a></sup>"
            } else {
                escape("[^${reference.id}]")
            }
            result.replace(reference.range.first, reference.range.last + 1, replacement)
        }

        if (ordered.isEmpty()) return result.toString()
        val items = StringBuilder()
        for (id in ordered) {
            val n = number[id] ?: continue
            // A footnote's own text is inline Markdown. Should it contain a
            // further reference, that placeholder has missed the numbering
            // pass above, so it is cleaned back to literal text below rather
            // than left as markup the reader would see.
            val text = inline(defined[id] ?: "")
            val back =
                if (occurrences[id] != null) " <a class=\"md-fnback\" href=\"#fnref-$n\">&#8617;</a>" else ""
            items.append("<li id=\"fn-$n\">$text$back</li>")
        }
        result.append("\n<section class=\"md-footnotes\"><hr><ol>$items</ol></section>")
        return replace(FOOTNOTE_MARKER, escape("[^") + "$1" + escape("]"), result.toString())
    }

    // MARK: - Inline

    /** Convert a block's inline Markdown to HTML. Code spans and math spans are
     *  lifted out first — their content is literal and must not be re-interpreted
     *  by the span-syntax pass — then the remainder is HTML-escaped, span syntax
     *  is converted, optional soft breaks are inserted, and finally the protected
     *  spans are restored. Math is emitted as explicit `.md-mathi` / `.md-mathd`
     *  spans (rendered by md-init.js with KaTeX), so this pass — not a browser
     *  delimiter scan — decides what is a formula. Content is escaped, but KaTeX
     *  reads the decoded textContent so `<`, `>`, `&` in a formula are fine. */
    private fun inline(text: String, softBreaks: Boolean = false): String {
        val protectedSpans = ArrayList<String>()
        var working = text

        // 1. Protect, in order: code spans, then display math ($$…$$, \[…\]),
        //    then inline math ($…$, \(…\)). Code wins over math, so `$x$` inside
        //    backticks stays literal code. The inline `$…$` form carries a
        //    currency guard so "$5 and $10" is left as prose.
        working = protect("`([^`]+)`", working, protectedSpans) { "<code>${escape(it)}</code>" }
        working = protect("""\${'$'}\${'$'}([\s\S]+?)\${'$'}\${'$'}""", working, protectedSpans) { mathSpan(it, display = true) }
        working = protect("""\\\[([\s\S]+?)\\\]""", working, protectedSpans) { mathSpan(it, display = true) }
        // The word-boundary guard is spelled out as `[\p{L}\p{N}_]` rather
        // than `\w`, because what `\w` means is not portable and the two ends
        // of that are both wrong here:
        //
        //   - the desktop JVM these unit tests run on reads `\w` as ASCII, so
        //     a Cyrillic letter counts as a non-word character and prose the
        //     Apple apps leave alone becomes a formula, or emphasis;
        //   - Android's own regex engine is ICU-backed and reads it as Unicode
        //     already — but it REJECTS the `(?U)` flag that fixes the JVM,
        //     throwing PatternSyntaxException. `protect` and `replace` swallow
        //     that, so the pattern silently stops matching at all: on a real
        //     device `(?U)` cost us single-dollar math and `_italics_` outright,
        //     while every unit test went on passing on the JVM.
        //
        // Unicode categories are understood by both engines and need no flag,
        // so the same pattern now means the same thing in the tests, on the
        // device, and on Apple's ICU.
        working = protect(
            """(?<![\p{L}\p{N}_${'$'}])\${'$'}([^${'$'}\n]+?)\${'$'}(?![\p{L}\p{N}_${'$'}])""",
            working, protectedSpans,
        ) { mathSpan(it, display = false) }
        working = protect("""\\\(([^\n]+?)\\\)""", working, protectedSpans) { mathSpan(it, display = false) }
        //    A footnote reference needs no protection: `[^id]` has no `](`,
        //    so no link or image pattern can match it, and one written inside
        //    backticks is already a code token by now. (Protecting it would
        //    not even work: the token lands inside an image's alt text and
        //    restoring it injects the markup there anyway — see below.)

        // 2. Escape the literal text (protection tokens are private-use, untouched).
        working = escape(working)

        // 3. Span syntax → tags. Images before links (image syntax is link
        //    syntax with a leading `!`, so the link pass would eat it), links
        //    before emphasis, bold before italic so `**` wins. The text is
        //    already escaped, so an optional source title reads `&quot;…&quot;`
        //    here and attribute values can't break out of their quotes.
        working = replace(
            "!\\[([^\\]]*)\\]\\(([^)\\s]+)\\s+&quot;(.*?)&quot;\\)",
            "<img src=\"$2\" alt=\"$1\" title=\"$3\">", working,
        )
        working = replace("!\\[([^\\]]*)\\]\\(([^)\\s]+)\\)", "<img src=\"$2\" alt=\"$1\">", working)
        working = replace(
            "\\[([^\\]]+)\\]\\(([^)\\s]+)\\s+&quot;(.*?)&quot;\\)",
            "<a href=\"$2\" title=\"$3\">$1</a>", working,
        )
        working = replace("\\[([^\\]]+)\\]\\(([^)\\s]+)\\)", "<a href=\"$2\">$1</a>", working)
        // Footnote references come after images and links, and must: the
        // markup a reference becomes is full of quotes and angle brackets, so
        // converting it first would let an image carry it into an `alt`
        // attribute — straight through the quoting this pass relies on — and
        // let a link wrap it in an `<a>` inside another `<a>`. Running last
        // means a reference written inside a link's own label simply stops
        // that label from being a link, which is the harmless failure.
        //
        // The number and the target are not known yet — they depend on the
        // order of first reference across the whole document — so this leaves
        // a placeholder for [withFootnotes] to resolve.
        working = replace(
            "\\[\\^([A-Za-z0-9_-]+)\\]",
            "<sup class=\"md-fnref\" data-fn=\"$1\"></sup>", working,
        )
        working = replace("\\*\\*([^*]+)\\*\\*", "<strong>$1</strong>", working)
        working = replace("__([^_]+)__", "<strong>$1</strong>", working)
        working = replace("~~([^~]+)~~", "<del>$1</del>", working)
        working = replace("\\*([^*]+)\\*", "<em>$1</em>", working)
        // Underscore italic only at word boundaries, so snake_case survives.
        // Spelled-out Unicode categories, for the same portability reason as
        // the currency guard above: `ф_em_ф` must stay prose everywhere.
        working = replace("(?<![\\p{L}\\p{N}_])_([^_]+)_(?![\\p{L}\\p{N}_])", "<em>$1</em>", working)

        // 4. Soft line breaks (paragraphs only), before restoring protected spans
        //    so a multi-line display-math span keeps its own internal newlines.
        if (softBreaks) working = working.replace("\n", "<br>\n")

        // 5. Restore protected spans.
        for ((index, html) in protectedSpans.withIndex()) {
            working = working.replace(token(index), html)
        }
        return working
    }

    /** Replace every match of `pattern` (group 1) with a unique private-use
     *  token, appending `transform(group1)` to `store`. */
    private fun protect(
        pattern: String,
        text: String,
        store: ArrayList<String>,
        transform: (String) -> String,
    ): String = runCatching {
        Regex(pattern).replace(text) { m ->
            val index = store.size
            store.add(transform(m.groupValues[1]))
            token(index)
        }
    }.getOrDefault(text)

    private fun token(index: Int): String = "$index"

    /** A KaTeX target element for [latex]: `.md-mathi` inline, `.md-mathd`
     *  display. The LaTeX is HTML-escaped, but KaTeX reads the decoded
     *  textContent so escaped `<`, `>`, `&` in the formula are fine. */
    private fun mathSpan(latex: String, display: Boolean): String =
        "<span class=\"md-math${if (display) "d" else "i"}\">${escape(latex)}</span>"

    // `"` is escaped too so a link URL (which lands in a double-quoted href
    // attribute) can't break out and inject attributes into the WebView.
    private fun escape(s: String): String = s
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")

    private fun replace(pattern: String, template: String, s: String): String =
        runCatching { s.replace(Regex(pattern), template) }.getOrDefault(s)

    // MARK: - CSS

    private fun css(dark: Boolean, export: Boolean): String {
        // On paper the page keeps its own single color: the paper tint is a
        // screen theme, and a content-height background would end mid-page
        // next to the white A4 margins.
        val paper = when {
            export -> "#FFFFFF"
            dark -> "#241E18"
            else -> "#F4EFE2"
        }
        val ink = if (dark) "#E7DBC2" else "#2B2620"
        val secondary = if (dark) "#2F2820" else "#EAE2CF"
        val accent = if (dark) "#C99A55" else "#9C6B2E"
        val muted = if (dark) "#B3A98E" else "#6B635A"
        val border = if (dark) "rgba(231,219,194,0.16)" else "rgba(43,38,32,0.16)"
        // Syntax-highlighting string tone: a quieter shade of the ink, warmer
        // than the grey `muted` used for comments so the two read apart. Kept
        // deliberately close to the ink — the code should look typewritten, not
        // colour-coded.
        val codeString = if (dark) "#B79A67" else "#6A5433"
        val scheme = if (dark) "dark" else "light"
        return """
            /* Force backgrounds to render in print / PDF so the content chrome
               (code blocks, table headers) survives, rather than being dropped. */
            * { -webkit-print-color-adjust: exact; print-color-adjust: exact; box-sizing: border-box; }
            :root { color-scheme: $scheme; }
            html, body { background: $paper; }
            body {
                color: $ink;
                font-family: Georgia, "Times New Roman", serif;
                font-size: ${if (export) 11 else 13}pt;
                line-height: 1.55;
                margin: 0;
                padding: 48px 56px;
                -webkit-text-size-adjust: 100%;
            }
            h1, h2, h3, h4, h5, h6 { font-weight: bold; line-height: 1.25; margin: 1.2em 0 0.5em; }
            h1 { font-size: 2em; }
            h2 { font-size: 1.6em; }
            h3 { font-size: 1.3em; }
            h4 { font-size: 1.1em; }
            h5 { font-size: 1em; }
            h6 { font-size: 0.9em; color: $muted; }
            p { margin: 0 0 0.9em; }
            a { color: $accent; }
            code, pre { font-family: "Courier New", monospace; }
            code { background: $secondary; padding: 0.1em 0.3em; border-radius: 4px; font-size: 0.92em; }
            pre { background: $secondary; padding: 12px 14px; border-radius: 8px; overflow-x: auto; }
            /* In export, code wraps: paper can't scroll a too-wide block, so a
               long line would otherwise be clipped at the block's edge. */
            ${if (export) "pre { white-space: pre-wrap; overflow-wrap: anywhere; }" else ""}
            pre code { background: none; padding: 0; font-size: 0.92em; }
            /* Syntax highlighting (highlight.js). A small hand-written theme in
               the app's own paper palette — a stock highlight.js theme
               (github / monokai / …) would fight the warm-paper, typewriter
               look with its bright, cool colours. Three quiet tones over the
               plain ink: keywords in the accent, comments muted and italic,
               strings a softer shade of the ink — and everything else (numbers,
               titles, types, attributes) left as ordinary ink. The code face
               stays Courier New (set on `code, pre` above); highlight.js only
               wraps tokens in spans and sets no colours of its own, so these
               rules are the whole theme. Applied by JS in the live DOM, so it
               reaches preview / print / PDF / HTML export — but NOT EPUB, whose
               code blocks are snapshotted from the source HTML before any
               script runs and so stay plain (by design). */
            .hljs-comment, .hljs-quote { color: $muted; font-style: italic; }
            .hljs-keyword, .hljs-selector-tag, .hljs-literal, .hljs-built_in,
            .hljs-name, .hljs-section, .hljs-doctag { color: $accent; }
            .hljs-string, .hljs-regexp { color: $codeString; }
            blockquote { margin: 0 0 0.9em; padding-left: 14px; border-left: 4px solid $accent; color: $muted; }
            hr { border: none; border-top: 1px solid $border; margin: 1.4em 0; }
            /* The author's `\newpage`: a dashed rule on screen; in export / print
               it collapses to an invisible marker where a new page starts (the
               PDF capture splits pages at it, and paginated printing breaks). */
            ${if (export) ".md-pagebreak { height: 0; margin: 0; break-after: page; }"
              else ".md-pagebreak { border-top: 2px dashed $border; margin: 1.6em 0; }"}
            table { border-collapse: collapse; margin: 0 0 0.9em; }
            th, td { border: 1px solid $border; padding: 6px 12px; }
            th { background: $secondary; }
            .md-list { margin: 0 0 0.9em; }
            .md-item { display: flex; gap: 0.5em; margin: 0.22em 0; }
            .md-marker { color: $muted; min-width: 1.5em; text-align: right; }
            .md-item.done { color: $muted; text-decoration: line-through; }
            /* Rich blocks: diagrams and formulas render as SVG/markup, not code —
               drop the code-block chrome, centre them, and let them scroll if wide. */
            .mermaid, .plantuml, .graphviz, .md-mathd {
                background: none; padding: 6px 0; margin: 0 0 0.9em;
                overflow-x: auto; text-align: center;
            }
            .mermaid svg, .plantuml svg, .graphviz svg { max-width: 100%; height: auto; }
            /* Graphviz draws in plain black on a transparent ground (md-init.js
               asks for `bgcolor=transparent`). Recolor it to the page's ink here,
               in CSS, rather than passing colors to the engine: these are
               presentation attributes, which any CSS rule outranks, and leaving
               the engine's own attributes alone keeps the layout metrics — and so
               the label positions it computed — exactly as Graphviz intended.
               An author's own `fontcolor` / `color` survives: Graphviz writes
               those out as attributes too, so each rule is scoped to the value
               the engine emits when nothing was asked for — text with no fill
               of its own, and explicit black. */
            .graphviz svg text:not([fill]) { fill: $ink; }
            .graphviz svg text[fill="black"] { fill: $ink; }
            .graphviz svg [stroke="black"] { stroke: $ink; }
            .graphviz svg [fill="black"]:not(text) { fill: $ink; }
            /* Footnotes. The references are superscript numerals in the running
               text; the notes themselves sit under a rule at the foot of the
               document, a size down, with a back-link to where they were cited. */
            .md-fnref a { text-decoration: none; color: $accent; }
            .md-footnotes { margin-top: 2em; font-size: 0.9em; color: $muted; }
            .md-footnotes hr { margin: 0 0 0.8em; }
            .md-footnotes ol { margin: 0; padding-left: 1.6em; }
            .md-footnotes li { margin: 0.35em 0; }
            .md-fnback { text-decoration: none; color: $accent; }
            /* Images render at their natural size, only capped to the page width;
               height follows so the aspect ratio never distorts. */
            img { max-width: 100%; height: auto; }
            .md-mathd .katex-display { margin: 0; }
            .katex-display { overflow-x: auto; overflow-y: hidden; padding: 2px 0; }
        """.trimIndent()
    }
}
