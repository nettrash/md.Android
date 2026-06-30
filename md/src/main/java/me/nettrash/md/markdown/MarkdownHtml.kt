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

    /** A full HTML document for `source`, themed light or dark. */
    fun document(source: String, title: String, dark: Boolean): String {
        val body = MarkdownParser.parse(source).joinToString("\n") { renderBlock(it) }
        return """
            <!DOCTYPE html>
            <html lang="en">
            <head>
            <meta charset="utf-8">
            <meta name="viewport" content="width=device-width, initial-scale=1">
            <title>${escape(title)}</title>
            <style>${css(dark)}</style>
            </head>
            <body>
            $body
            </body>
            </html>
        """.trimIndent()
    }

    // MARK: - Blocks

    private fun renderBlock(block: MarkdownBlock): String = when (block) {
        is MarkdownBlock.Heading -> "<h${block.level}>${inline(block.text)}</h${block.level}>"
        is MarkdownBlock.Paragraph ->
            "<p>${inline(block.text).replace("\n", "<br>\n")}</p>"
        is MarkdownBlock.ListBlock -> renderList(block.items, block.ordered)
        is MarkdownBlock.CodeBlock -> "<pre><code>${escape(block.code)}</code></pre>"
        is MarkdownBlock.Quote ->
            "<blockquote>\n${block.blocks.joinToString("\n") { renderBlock(it) }}\n</blockquote>"
        is MarkdownBlock.Table -> renderTable(block.header, block.alignments, block.rows)
        MarkdownBlock.ThematicBreak -> "<hr>"
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

    /** Convert a block's inline Markdown to HTML. Code spans are lifted out
     *  first (literal), the remainder is HTML-escaped, span syntax is
     *  converted, then the code spans are restored. */
    private fun inline(text: String): String {
        val codeSpans = ArrayList<String>()
        var working = text

        // 1. Extract `code spans`, replacing each with a private-use token.
        working = Regex("`([^`]+)`").replace(working) { m ->
            val index = codeSpans.size
            codeSpans.add("<code>${escape(m.groupValues[1])}</code>")
            token(index)
        }

        // 2. Escape the literal text (tokens are private-use chars, untouched).
        working = escape(working)

        // 3. Span syntax → tags. Links first; bold before italic so `**` wins.
        working = replace("\\[([^\\]]+)\\]\\(([^)\\s]+)\\)", "<a href=\"$2\">$1</a>", working)
        working = replace("\\*\\*([^*]+)\\*\\*", "<strong>$1</strong>", working)
        working = replace("__([^_]+)__", "<strong>$1</strong>", working)
        working = replace("~~([^~]+)~~", "<del>$1</del>", working)
        working = replace("\\*([^*]+)\\*", "<em>$1</em>", working)
        // Underscore italic only at word boundaries, so snake_case survives.
        working = replace("(?<![\\w])_([^_]+)_(?![\\w])", "<em>$1</em>", working)

        // 4. Restore code spans.
        for ((index, html) in codeSpans.withIndex()) {
            working = working.replace(token(index), html)
        }
        return working
    }

    private fun token(index: Int): String = "$index"

    private fun escape(s: String): String = s
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")

    private fun replace(pattern: String, template: String, s: String): String =
        runCatching { s.replace(Regex(pattern), template) }.getOrDefault(s)

    // MARK: - CSS

    private fun css(dark: Boolean): String {
        val paper = if (dark) "#241E18" else "#F4EFE2"
        val ink = if (dark) "#E7DBC2" else "#2B2620"
        val secondary = if (dark) "#2F2820" else "#EAE2CF"
        val accent = if (dark) "#C99A55" else "#9C6B2E"
        val muted = if (dark) "#B3A98E" else "#6B635A"
        val border = if (dark) "rgba(231,219,194,0.16)" else "rgba(43,38,32,0.16)"
        val scheme = if (dark) "dark" else "light"
        return """
            /* Force backgrounds to render in print / PDF so the chosen theme
               (including the dark paper) survives, rather than being dropped. */
            * { -webkit-print-color-adjust: exact; print-color-adjust: exact; box-sizing: border-box; }
            :root { color-scheme: $scheme; }
            html, body { background: $paper; }
            body {
                color: $ink;
                font-family: Georgia, "Times New Roman", serif;
                font-size: 13pt;
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
            pre code { background: none; padding: 0; font-size: 0.92em; }
            blockquote { margin: 0 0 0.9em; padding-left: 14px; border-left: 4px solid $accent; color: $muted; }
            hr { border: none; border-top: 1px solid $border; margin: 1.4em 0; }
            table { border-collapse: collapse; margin: 0 0 0.9em; }
            th, td { border: 1px solid $border; padding: 6px 12px; }
            th { background: $secondary; }
            .md-list { margin: 0 0 0.9em; }
            .md-item { display: flex; gap: 0.5em; margin: 0.22em 0; }
            .md-marker { color: $muted; min-width: 1.5em; text-align: right; }
            .md-item.done { color: $muted; text-decoration: line-through; }
        """.trimIndent()
    }
}
