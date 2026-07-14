/*
 * Epub.kt
 * md (Android)
 *
 * The pure side of Export as EPUB: everything that turns an already-read,
 * already-rendered book into an EPUB 3 archive, with no Android types —
 * JVM-testable end to end (EpubTest round-trips the archive). The pieces:
 *
 *   - the XHTML fixer (`toXhtml`) that makes MarkdownHtml's HTML5 output
 *     well-formed XML (self-closed voids, no scripts, numeric entities),
 *   - the rich-element finder/replacer that swaps math / Mermaid /
 *     PlantUML containers for <img> tags (the PNGs are rendered by
 *     ui/EpubExporter in an offscreen WebView — the impure half),
 *   - the container.xml / content.opf / nav.xhtml / style.css generators,
 *   - `buildEpub`, which zips it all with the `mimetype` entry first and
 *     STORED, as the EPUB OCF spec requires.
 */

package me.nettrash.md.book

import java.io.ByteArrayOutputStream
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** One XHTML content document, in reading order. [fileName] is relative
 *  to OEBPS/ ("unit-001.xhtml"); [body] is already well-formed XHTML. */
internal data class EpubUnit(
    val fileName: String,
    val title: String,
    val body: String,
)

/** One image resource under OEBPS/ ("images/rich-001.png"). */
internal class EpubImage(
    val fileName: String,
    val bytes: ByteArray,
)

/** A chapter for the nav nesting: its heading page and its articles. */
internal data class EpubChapter(
    val heading: EpubUnit,
    val articles: List<EpubUnit>,
)

/** The assembled book. Reading order — [units] — is identical to
 *  [compileBook]: title page, root articles, then each chapter's heading
 *  followed by its articles. */
internal class EpubBook(
    val title: String,
    val titlePage: EpubUnit,
    val rootArticles: List<EpubUnit>,
    val chapters: List<EpubChapter>,
    val images: List<EpubImage>,
) {
    val units: List<EpubUnit> =
        listOf(titlePage) + rootArticles + chapters.flatMap { listOf(it.heading) + it.articles }
}

/** The five XML-predefined entities are all XHTML may use; everything the
 *  generators interpolate goes through here. */
internal fun escapeXml(s: String): String = s
    .replace("&", "&amp;")
    .replace("<", "&lt;")
    .replace(">", "&gt;")
    .replace("\"", "&quot;")

/** The body markup of a `MarkdownHtml.document()` page — everything
 *  between the `<body …>` tag and the md-init script. The renderer's only
 *  public surface is the full document (and it must stay untouched); the
 *  wrapper is simply discarded here. */
internal fun documentBody(html: String): String {
    val bodyTag = html.indexOf("<body")
    if (bodyTag < 0) return html
    val start = html.indexOf('>', bodyTag) + 1
    val script = html.lastIndexOf("<script type=\"module\" src=\"rich/md-init.js\"></script>")
    val end = when {
        script >= 0 -> script
        else -> html.lastIndexOf("</body>").takeIf { it >= 0 } ?: html.length
    }
    return html.substring(start, end).trim()
}

private val SCRIPT_ELEMENT = Regex("<script[^>]*>[\\s\\S]*?</script>")
private val UNCLOSED_IMG = Regex("<img([^>]*[^/>])>")

/** Make the renderer's HTML5 fragment well-formed XHTML: strip script
 *  elements (md-init and the engine loaders have no place in an EPUB),
 *  turn the renderer's named entities into numeric references (XML only
 *  predefines five), and self-close the void elements it emits
 *  (`<br>`, `<hr>`, `<img …>`). Everything else MarkdownHtml produces is
 *  already XML-clean: attributes are always quoted and content is escaped. */
internal fun toXhtml(html: String): String = html
    .replace(SCRIPT_ELEMENT, "")
    .replace("&bull;", "&#8226;")
    .replace("&nbsp;", "&#160;")
    .replace("<br>", "<br/>")
    .replace("<hr>", "<hr/>")
    .replace(UNCLOSED_IMG, "<img$1/>")

/** A rich-content element found in generated markup: the [range] of the
 *  whole element and its [kind] — "formula" (math) or "diagram"
 *  (Mermaid / PlantUML), used for the replacement image's alt text. */
internal data class RichElement(val range: IntRange, val kind: String)

/** The renderer's rich containers: opening tag to (closing tag, kind).
 *  Their content is always escaped text (see MarkdownHtml.mathSpan and
 *  the code-block branch), so the first closing tag is the right one. */
private val RICH_MARKERS = listOf(
    "<span class=\"md-mathi\">" to ("</span>" to "formula"),
    "<span class=\"md-mathd\">" to ("</span>" to "formula"),
    "<div class=\"md-mathd\">" to ("</div>" to "formula"),
    "<pre class=\"mermaid\">" to ("</pre>" to "diagram"),
    "<div class=\"plantuml\">" to ("</div>" to "diagram"),
)

/** Every rich element in [html], in document order — the same order the
 *  exporter's `querySelectorAll('.md-mathi, .md-mathd, .mermaid,
 *  .plantuml')` reports rects in, so captures and replacements pair up
 *  by index. */
internal fun findRichElements(html: String): List<RichElement> {
    val found = ArrayList<RichElement>()
    var index = 0
    while (true) {
        var start = -1
        var open = ""
        var close = ""
        var kind = ""
        for ((opener, closeAndKind) in RICH_MARKERS) {
            val at = html.indexOf(opener, index)
            if (at >= 0 && (start < 0 || at < start)) {
                start = at
                open = opener
                close = closeAndKind.first
                kind = closeAndKind.second
            }
        }
        if (start < 0) return found
        val closeAt = html.indexOf(close, start + open.length)
        if (closeAt < 0) return found
        val end = closeAt + close.length
        found.add(RichElement(start until end, kind))
        index = end
    }
}

/** Replace the rich elements of [html] (document order) with [imageTags]
 *  (same order — one per element, from the WebView captures). A tag-count
 *  mismatch replaces only the pairs that exist. */
internal fun replaceRichElements(html: String, imageTags: List<String>): String {
    val elements = findRichElements(html)
    if (elements.isEmpty() || imageTags.isEmpty()) return html
    val result = StringBuilder()
    var last = 0
    for ((index, element) in elements.withIndex()) {
        if (index >= imageTags.size) break
        result.append(html, last, element.range.first)
        result.append(imageTags[index])
        last = element.range.last + 1
    }
    result.append(html, last, html.length)
    return result.toString()
}

/** A complete XHTML5 content document around an already-fixed [body]. */
internal fun xhtmlDocument(title: String, body: String): String = buildString {
    append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
    append("<!DOCTYPE html>\n")
    append("<html xmlns=\"http://www.w3.org/1999/xhtml\">\n")
    append("<head>\n")
    append("<title>").append(escapeXml(title)).append("</title>\n")
    append("<link rel=\"stylesheet\" type=\"text/css\" href=\"style.css\"/>\n")
    append("</head>\n")
    append("<body>\n")
    append(body).append('\n')
    append("</body>\n")
    append("</html>\n")
}

/** META-INF/container.xml: the one fixed pointer at the OPF. */
internal fun containerXml(): String = buildString {
    append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
    append("<container version=\"1.0\" xmlns=\"urn:oasis:names:tc:opendocument:xmlns:container\">\n")
    append("<rootfiles>\n")
    append("<rootfile full-path=\"OEBPS/content.opf\" media-type=\"application/oebps-package+xml\"/>\n")
    append("</rootfiles>\n")
    append("</container>\n")
}

private fun unitId(unit: EpubUnit): String = unit.fileName.removeSuffix(".xhtml")

/** OEBPS/content.opf: metadata, the manifest of every resource, and the
 *  spine in reading order. */
internal fun contentOpf(book: EpubBook, identifier: String, modified: String): String = buildString {
    append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
    append("<package xmlns=\"http://www.idpf.org/2007/opf\" version=\"3.0\" unique-identifier=\"book-id\">\n")
    append("<metadata xmlns:dc=\"http://purl.org/dc/elements/1.1/\">\n")
    append("<dc:identifier id=\"book-id\">").append(escapeXml(identifier)).append("</dc:identifier>\n")
    append("<dc:title>").append(escapeXml(book.title)).append("</dc:title>\n")
    append("<dc:language>en</dc:language>\n")
    append("<meta property=\"dcterms:modified\">").append(escapeXml(modified)).append("</meta>\n")
    append("</metadata>\n")
    append("<manifest>\n")
    append("<item id=\"nav\" href=\"nav.xhtml\" media-type=\"application/xhtml+xml\" properties=\"nav\"/>\n")
    append("<item id=\"style\" href=\"style.css\" media-type=\"text/css\"/>\n")
    for (unit in book.units) {
        append("<item id=\"").append(unitId(unit)).append("\" href=\"").append(unit.fileName)
            .append("\" media-type=\"application/xhtml+xml\"/>\n")
    }
    for ((index, image) in book.images.withIndex()) {
        append("<item id=\"img-").append(index).append("\" href=\"").append(image.fileName)
            .append("\" media-type=\"image/png\"/>\n")
    }
    append("</manifest>\n")
    append("<spine>\n")
    for (unit in book.units) {
        append("<itemref idref=\"").append(unitId(unit)).append("\"/>\n")
    }
    append("</spine>\n")
    append("</package>\n")
}

/** OEBPS/nav.xhtml: the EPUB 3 nav document — root articles first, then
 *  each chapter with its articles as a nested list, all display names.
 *  A book with nothing but its title page still gets one entry (the spec
 *  requires a non-empty toc list). */
internal fun navXhtml(book: EpubBook): String = buildString {
    append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
    append("<!DOCTYPE html>\n")
    append("<html xmlns=\"http://www.w3.org/1999/xhtml\" xmlns:epub=\"http://www.idpf.org/2007/ops\">\n")
    append("<head>\n")
    append("<title>Contents</title>\n")
    append("<link rel=\"stylesheet\" type=\"text/css\" href=\"style.css\"/>\n")
    append("</head>\n")
    append("<body>\n")
    append("<nav epub:type=\"toc\">\n")
    append("<h1>Contents</h1>\n")
    append("<ol>\n")
    fun entry(unit: EpubUnit) {
        append("<li><a href=\"").append(unit.fileName).append("\">")
            .append(escapeXml(unit.title)).append("</a></li>\n")
    }
    if (book.rootArticles.isEmpty() && book.chapters.isEmpty()) {
        entry(book.titlePage)
    }
    for (article in book.rootArticles) entry(article)
    for (chapter in book.chapters) {
        append("<li><a href=\"").append(chapter.heading.fileName).append("\">")
            .append(escapeXml(chapter.heading.title)).append("</a>\n")
        if (chapter.articles.isNotEmpty()) {
            append("<ol>\n")
            for (article in chapter.articles) entry(article)
            append("</ol>\n")
        }
        append("</li>\n")
    }
    append("</ol>\n")
    append("</nav>\n")
    append("</body>\n")
    append("</html>\n")
}

/** OEBPS/style.css: the export look, minus everything an EPUB must not
 *  carry — no scripts, no page-break chrome, no forced colors (readers
 *  bring their own theme). The .md-* rules style the renderer's list and
 *  rich-image markup. */
internal val EPUB_CSS: String = """
    body { font-family: Georgia, 'Times New Roman', serif; line-height: 1.6; }
    h1, h2, h3, h4, h5, h6 { line-height: 1.3; }
    code, pre { font-family: Menlo, Consolas, monospace; font-size: 0.9em; }
    pre { padding: 0.8em; background: rgba(128, 128, 128, 0.12); white-space: pre-wrap; }
    blockquote { margin: 1em 0; padding: 0 1em; border-left: 3px solid rgba(128, 128, 128, 0.5); }
    table { border-collapse: collapse; margin: 1em 0; }
    th, td { border: 1px solid rgba(128, 128, 128, 0.5); padding: 0.3em 0.6em; }
    img { max-width: 100%; }
    .md-item { margin: 0.2em 0; }
    .md-item.done { opacity: 0.65; }
    .md-marker { margin-right: 0.5em; }
    .md-formula { vertical-align: middle; }
    .md-pagebreak { display: none; }
""".trimIndent() + "\n"

/** Zip the whole book into EPUB bytes. Per the OCF spec the `mimetype`
 *  entry comes FIRST and is STORED (uncompressed, size and CRC set by
 *  hand — ZipOutputStream requires both for a STORED entry) so readers
 *  can sniff the type from the raw bytes; everything else is DEFLATED.
 *  [identifier] and [modified] default to a fresh urn:uuid and the
 *  current UTC time, and are injectable so tests are deterministic. */
internal fun buildEpub(
    book: EpubBook,
    identifier: String = "urn:uuid:${UUID.randomUUID()}",
    modified: String = Instant.now().truncatedTo(ChronoUnit.SECONDS).toString(),
): ByteArray {
    val out = ByteArrayOutputStream()
    ZipOutputStream(out).use { zip ->
        val mimetype = "application/epub+zip".toByteArray(Charsets.US_ASCII)
        zip.putNextEntry(
            ZipEntry("mimetype").apply {
                method = ZipEntry.STORED
                size = mimetype.size.toLong()
                crc = CRC32().apply { update(mimetype) }.value
            },
        )
        zip.write(mimetype)
        zip.closeEntry()

        fun deflated(name: String, content: ByteArray) {
            zip.putNextEntry(ZipEntry(name))   // default method: DEFLATED
            zip.write(content)
            zip.closeEntry()
        }
        deflated("META-INF/container.xml", containerXml().toByteArray(Charsets.UTF_8))
        deflated("OEBPS/content.opf", contentOpf(book, identifier, modified).toByteArray(Charsets.UTF_8))
        deflated("OEBPS/nav.xhtml", navXhtml(book).toByteArray(Charsets.UTF_8))
        deflated("OEBPS/style.css", EPUB_CSS.toByteArray(Charsets.UTF_8))
        for (unit in book.units) {
            deflated("OEBPS/${unit.fileName}", xhtmlDocument(unit.title, unit.body).toByteArray(Charsets.UTF_8))
        }
        for (image in book.images) {
            deflated("OEBPS/${image.fileName}", image.bytes)
        }
    }
    return out.toByteArray()
}
