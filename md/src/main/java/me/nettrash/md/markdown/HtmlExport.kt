/*
 * HtmlExport.kt
 * md (Android)
 *
 * The pure side of Export as HTML — one self-contained file that opens
 * anywhere with no engines, no folder of assets and no network. (The
 * impure half, which drives the offscreen WebView and writes the bytes,
 * is `ui/Exporter.renderHtml`; the split mirrors book/Epub.kt vs
 * ui/EpubExporter.kt.)
 *
 * What has to happen, and why:
 *
 *  1. The page is captured from the LIVE DOM, after md-init.js has flagged
 *     `data-md-render-complete` — by then Mermaid, Graphviz and PlantUML
 *     have already become inline <svg> and KaTeX has already expanded its
 *     formulas into markup. Nothing is left to run, so [CAPTURE_SCRIPT]
 *     removes every <script> and every stylesheet <link> into `rich/`: the
 *     exported file must not reach for an engine that will not be there.
 *     The removals happen in the DOM (`el.remove()`), not by string
 *     surgery on the markup.
 *  2. `documentElement.outerHTML` does not include the doctype, and
 *     without one every browser renders the file in quirks mode — so
 *     [DOCTYPE] is prepended by hand.
 *  3. KaTeX's stylesheet is inlined, but only into a document that
 *     actually has math (see [needsKatex] / [embeddedKatexCss]).
 *  4. The OFL notice for the embedded faces travels with them
 *     ([KATEX_NOTICE]).
 *  5. The export CSS renders `\newpage` as `break-after: page`, which
 *     means nothing on a screen — a reader scrolling the exported file
 *     would find the author's page breaks silently gone. [visiblePageBreaks]
 *     swaps that one rule back for the dashed rule the preview shows; the
 *     PDF and print paths are untouched and still get a real page break.
 */

package me.nettrash.md.markdown

import java.util.Base64

object HtmlExport {

    /**
     * Strip the finished page down to something that stands on its own and
     * hand back its markup. Runs against the rendered DOM, so what it
     * captures is diagrams-as-SVG and formulas-as-markup, not the source
     * that produced them.
     */
    const val CAPTURE_SCRIPT: String = """
        (function () {
          document.querySelectorAll('script, link[rel="stylesheet"]').forEach(function (el) {
            el.remove();
          });
          // A stale completion flag would be misleading in a file that has
          // nothing left to complete.
          document.documentElement.removeAttribute('data-md-render-complete');
          return document.documentElement.outerHTML;
        })()
    """

    /** `outerHTML` omits it; without one the file renders in quirks mode. */
    const val DOCTYPE: String = "<!DOCTYPE html>\n"

    /**
     * The notice that has to travel with an exported page carrying KaTeX's
     * stylesheet and fonts. The code is MIT; the faces are **not** — they are
     * SIL Open Font License 1.1 with reserved names, and the OFL requires its
     * notice to accompany the fonts wherever they go. Exporting is the first
     * thing md does that hands those files to somebody else, so this is the
     * first place the obligation actually bites.
     */
    val KATEX_NOTICE: String = """
        <!--
          Mathematics rendered with KaTeX (https://katex.org) — MIT License,
          Copyright (c) 2013-2020 Khan Academy and other contributors.
          The embedded KaTeX_* fonts are licensed under the SIL Open Font
          License 1.1 (https://scripts.sil.org/OFL); "KaTeX" is a Reserved Font
          Name. The fonts are embedded unmodified.
        -->
    """.trimIndent()

    /**
     * Mermaid writes its own theme CSS into every diagram it draws, so an
     * exported page carrying a Mermaid diagram is carrying several kilobytes
     * of Mermaid's source text — not just generated geometry, the way
     * Graphviz and PlantUML output is. MIT asks for its notice to go with
     * that, so it does.
     */
    val MERMAID_NOTICE: String = """
        <!--
          Diagrams rendered with Mermaid (https://mermaid.js.org) — MIT License,
          Copyright (c) 2014-2022 Knut Sveidqvist. The diagram SVG carries
          Mermaid's own theme stylesheet.
        -->
    """.trimIndent()

    /** Drop Mermaid's notice into a captured page that holds one of its
     *  diagrams. */
    fun withMermaidNotice(page: String): String =
        if (page.contains("class=\"mermaid\"")) {
            page.replaceFirst("</head>", "$MERMAID_NOTICE\n</head>")
        } else {
            page
        }

    /** The bundled stylesheet, as MarkdownHtml links it and as the asset
     *  manager addresses it. */
    const val KATEX_CSS_ASSET: String = "rich/katex.min.css"

    /**
     * True when [document] pulled KaTeX in — the same signal
     * `MarkdownHtml.document` uses to decide whether to link the stylesheet
     * at all, so only a document with real math carries the font payload.
     */
    fun needsKatex(document: String): Boolean = document.contains(KATEX_CSS_ASSET)

    /**
     * Put the author's `\newpage` markers back on screen. In export CSS a
     * page break collapses to an invisible marker (`break-after: page`),
     * which paper honours and a scrolling reader never sees; an exported
     * HTML file is read on screen, so it gets the preview's dashed rule
     * instead. The literal is the export branch of `MarkdownHtml.css`, and
     * the replacement its screen branch with the light border colour —
     * `document(export = true)` always renders in the light palette.
     */
    fun visiblePageBreaks(document: String): String = document.replace(
        ".md-pagebreak { height: 0; margin: 0; break-after: page; }",
        ".md-pagebreak { border-top: 2px dashed rgba(43,38,32,0.16); margin: 1.6em 0; }",
    )

    /** Drop the stylesheet (and its licence notice) into a captured page. */
    fun withEmbeddedKatex(page: String, css: String): String =
        page.replaceFirst("</head>", "<style>$css</style>\n$KATEX_NOTICE\n</head>")

    /** Every `src:` in katex.min.css that names a woff2 face, with the rest
     *  of the value (the woff / ttf alternates) up to the rule's end. */
    private val woff2Source =
        Regex("""src:url\(fonts/([A-Za-z0-9_-]+)\.woff2\) format\("woff2"\)[^;}]*""")

    /**
     * KaTeX's stylesheet with its web fonts embedded, ready to be dropped
     * into an exported page — or null if the assets are missing it.
     *
     * A formula is not glyphs alone: `katex.min.css` positions every piece of
     * it, so an export that dropped the stylesheet would show the right
     * characters in the wrong places. It cannot be linked either, since the
     * file has to stand on its own — so it is inlined, and each `@font-face`
     * keeps only its **woff2** source, rewritten as a `data:` URI. woff2 is
     * the one format every browser that matters reads; carrying the `woff`
     * and `ttf` alternates as well would quadruple the payload for nothing,
     * and leaving them as relative paths would leave dead links in the file.
     * Twenty faces, about 300 KB before encoding.
     *
     * [readAsset] reads one bundled asset by its path (`rich/…`) — the
     * `AssetManager` in the app, a plain file read in the tests, which is
     * what keeps this function testable off-device.
     */
    fun embeddedKatexCss(readAsset: (String) -> ByteArray?): String? {
        val css = readAsset(KATEX_CSS_ASSET)?.toString(Charsets.UTF_8) ?: return null
        return woff2Source.replace(css) { match ->
            val face = match.groupValues[1]
            val bytes = readAsset("rich/fonts/$face.woff2")
                // Leave the rule alone rather than emit a broken src.
                ?: return@replace match.value
            "src:url(data:font/woff2;base64," +
                Base64.getEncoder().encodeToString(bytes) + ") format(\"woff2\")"
        }
    }
}
