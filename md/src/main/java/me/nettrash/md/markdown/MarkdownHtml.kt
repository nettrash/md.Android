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

    /**
     * A full HTML document for `source`, themed light or dark.
     *
     * Rich renderers (KaTeX math, Mermaid, PlantUML) load entirely from bundled
     * assets under `rich/` — no network. Each heavy engine is pulled in only
     * when the document uses it (PlantUML alone is 7 MB): the KaTeX / Mermaid /
     * Viz scripts are included conditionally here, and `md-init.js` dynamically
     * imports the PlantUML engine only when a `.plantuml` block exists.
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
        // A raw PlantUML document — an opened `.puml`: bare diagram source with
        // no ```plantuml fence (see [isRawPlantUML]). Render the whole file as
        // one diagram rather than parsing it as Markdown, which would only show
        // the `@startuml…` text. Everything else — a `.md` / `.txt` file — is
        // parsed as Markdown exactly as before.
        val body: String
        val needsMath: Boolean
        val needsMermaid: Boolean
        val needsPlantuml: Boolean

        if (isRawPlantUML(source)) {
            // md-init.js turns the `.plantuml` container into an SVG offline; on
            // failure it restores the source, so an invalid diagram still shows
            // its text. No Markdown here, so no math / Mermaid.
            body = "<div class=\"plantuml\">${escape(source)}</div>"
            needsMath = false
            needsMermaid = false
            needsPlantuml = true
        } else {
            val blocks = MarkdownParser.parse(source)
            // Top-level headings carry a GitHub-style anchor id, so `[…](#slug)`
            // links navigate and the table of contents can scroll the preview.
            // The slugs come from the same `MarkdownParser.slug` the TOC uses,
            // so the two always agree.
            val slugs = HashMap<String, Int>()
            body = blocks.joinToString("\n") { block ->
                if (block is MarkdownBlock.Heading) {
                    val id = MarkdownParser.slug(block.text, slugs)
                    "<h${block.level} id=\"$id\">${inline(block.text)}</h${block.level}>"
                } else {
                    renderBlock(block)
                }
            }

            val langs = blocks.filterIsInstance<MarkdownBlock.CodeBlock>()
                .mapNotNull { it.language?.lowercase(Locale.ROOT) }.toSet()
            needsMermaid = "mermaid" in langs
            needsPlantuml = langs.any { it == "plantuml" || it == "puml" || it == "plant-uml" }
            // Math is needed iff inline() actually emitted a math span — which it
            // only does for real formulas, never for currency like "$5". Keying
            // off the produced markup (not a raw "$" heuristic) means prose with
            // stray dollar signs never even loads KaTeX.
            needsMath = body.contains("md-mathi") || body.contains("md-mathd")
        }

        val head = StringBuilder()
        if (needsMath) {
            head.append(
                """
                <link rel="stylesheet" href="rich/katex.min.css">
                <script defer src="rich/katex.min.js"></script>
                """.trimIndent()
            )
        }
        if (needsMermaid) head.append("\n<script src=\"rich/mermaid.min.js\"></script>")
        if (needsPlantuml) head.append("\n<script src=\"rich/viz-global.js\"></script>")

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
        for (raw in source.split("\n")) {
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("'")) continue
            return line.startsWith("@start")
        }
        return false
    }

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
        is MarkdownBlock.CodeBlock -> when (block.language?.lowercase(Locale.ROOT)) {
            "mermaid" -> "<pre class=\"mermaid\">${escape(block.code)}</pre>"
            "plantuml", "puml", "plant-uml" -> "<div class=\"plantuml\">${escape(block.code)}</div>"
            "math", "latex", "tex" -> "<div class=\"md-mathd\">${escape(block.code)}</div>"
            else -> "<pre><code>${escape(block.code)}</code></pre>"
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
        working = protect(
            """(?<![\w${'$'}])\${'$'}([^${'$'}\n]+?)\${'$'}(?![\w${'$'}])""",
            working, protectedSpans,
        ) { mathSpan(it, display = false) }
        working = protect("""\\\(([^\n]+?)\\\)""", working, protectedSpans) { mathSpan(it, display = false) }

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
        working = replace("\\*\\*([^*]+)\\*\\*", "<strong>$1</strong>", working)
        working = replace("__([^_]+)__", "<strong>$1</strong>", working)
        working = replace("~~([^~]+)~~", "<del>$1</del>", working)
        working = replace("\\*([^*]+)\\*", "<em>$1</em>", working)
        // Underscore italic only at word boundaries, so snake_case survives.
        working = replace("(?<![\\w])_([^_]+)_(?![\\w])", "<em>$1</em>", working)

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
            .mermaid, .plantuml, .md-mathd {
                background: none; padding: 6px 0; margin: 0 0 0.9em;
                overflow-x: auto; text-align: center;
            }
            .mermaid svg, .plantuml svg { max-width: 100%; height: auto; }
            /* Images render at their natural size, only capped to the page width;
               height follows so the aspect ratio never distorts. */
            img { max-width: 100%; height: auto; }
            .md-mathd .katex-display { margin: 0; }
            .katex-display { overflow-x: auto; overflow-y: hidden; padding: 2px 0; }
        """.trimIndent()
    }
}
