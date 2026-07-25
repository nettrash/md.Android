/*
 * EpubTest.kt
 * md (Android)
 *
 * JVM tests for the pure EPUB builders (`book/Epub.kt`). Because the
 * archive is plain java.util.zip, it round-trips on the JVM: a small
 * imageless book is built into bytes and reopened, and the OCF contract
 * is asserted on what actually came back — mimetype first / STORED /
 * byte-exact, container pointing at the OPF, the spine in reading order,
 * the nav nesting chapters — plus the XHTML fixer's output parsing as
 * well-formed XML. The WebView image pipeline (ui/EpubExporter) is
 * device-only and exercised by hand.
 */

package me.nettrash.md.book

import me.nettrash.md.markdown.MarkdownHtml
import me.nettrash.md.markdown.MarkdownParser
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Document
import org.xml.sax.InputSource
import java.io.ByteArrayInputStream
import java.io.StringReader
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import javax.xml.parsers.DocumentBuilderFactory

class EpubTest {

    /** A small imageless book: title page, one root article, one chapter
     *  ("Getting Started") with two articles — five units in all. */
    private fun sampleBook(): EpubBook {
        fun unit(index: Int, title: String, markdown: String): EpubUnit {
            val body = toXhtml(documentBody(MarkdownHtml.document(markdown, title, dark = false)))
            return EpubUnit("unit-" + index.toString().padStart(3, '0') + ".xhtml", title, body)
        }
        val titlePage = EpubUnit("unit-001.xhtml", "My Book", "<h1>My Book</h1>")
        val intro = unit(2, "Intro", "# Intro\n\nHello *there*.\n\n- one\n- two\n\n---")
        val heading = EpubUnit("unit-003.xhtml", "Getting Started", "<h1>Getting Started</h1>")
        val first = unit(4, "First", "# First\n\n| a | b |\n| --- | --- |\n| 1 | 2 |")
        val second = unit(5, "Second", "# Second\n\n> quoted\n\n`code`")
        return EpubBook(
            title = "My Book",
            titlePage = titlePage,
            rootArticles = listOf(intro),
            chapters = listOf(EpubChapter(heading, listOf(first, second))),
            images = emptyList(),
        )
    }

    /** Every entry of an archive, in file order. */
    private fun entries(epub: ByteArray): List<Pair<ZipEntry, ByteArray>> {
        val found = ArrayList<Pair<ZipEntry, ByteArray>>()
        ZipInputStream(ByteArrayInputStream(epub)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                found.add(entry to zip.readBytes())
            }
        }
        return found
    }

    private fun parseXml(xml: String): Document =
        DocumentBuilderFactory.newInstance().newDocumentBuilder()
            .parse(InputSource(StringReader(xml)))

    @Test fun mimetypeIsFirstStoredAndExact() {
        val all = entries(buildEpub(sampleBook()))
        val (entry, bytes) = all.first()
        assertEquals("mimetype", entry.name)
        assertEquals(ZipEntry.STORED, entry.method)
        assertEquals("application/epub+zip", bytes.toString(Charsets.US_ASCII))
        // And nothing else is STORED — the rest of the archive deflates.
        assertTrue(all.drop(1).all { it.first.method == ZipEntry.DEFLATED })
    }

    @Test fun containerPointsAtTheOpf() {
        val all = entries(buildEpub(sampleBook())).associate { it.first.name to it.second }
        val container = all.getValue("META-INF/container.xml").toString(Charsets.UTF_8)
        parseXml(container)   // well-formed
        assertTrue(container.contains("full-path=\"OEBPS/content.opf\""))
        assertTrue(container.contains("media-type=\"application/oebps-package+xml\""))
    }

    @Test fun opfSpineListsTheUnitsInReadingOrder() {
        val all = entries(buildEpub(sampleBook(), identifier = "urn:uuid:test", modified = "2026-07-10T00:00:00Z"))
            .associate { it.first.name to it.second }
        val opf = all.getValue("OEBPS/content.opf").toString(Charsets.UTF_8)
        val document = parseXml(opf)
        val idrefs = document.getElementsByTagName("itemref").let { refs ->
            (0 until refs.length).map { refs.item(it).attributes.getNamedItem("idref").nodeValue }
        }
        assertEquals(listOf("unit-001", "unit-002", "unit-003", "unit-004", "unit-005"), idrefs)
        // Metadata and the nav/style manifest entries ride along.
        assertTrue(opf.contains("<dc:title>My Book</dc:title>"))
        assertTrue(opf.contains("<dc:language>en</dc:language>"))
        assertTrue(opf.contains("urn:uuid:test"))
        assertTrue(opf.contains("<meta property=\"dcterms:modified\">2026-07-10T00:00:00Z</meta>"))
        assertTrue(opf.contains("href=\"nav.xhtml\" media-type=\"application/xhtml+xml\" properties=\"nav\""))
        assertTrue(opf.contains("href=\"style.css\""))
    }

    @Test fun navNestsChapterArticlesUnderTheirChapter() {
        val all = entries(buildEpub(sampleBook())).associate { it.first.name to it.second }
        val nav = all.getValue("OEBPS/nav.xhtml").toString(Charsets.UTF_8)
        parseXml(nav)   // well-formed
        assertTrue(nav.contains("<nav epub:type=\"toc\">"))
        // Root articles first, then the chapter.
        assertTrue(nav.indexOf("Intro") < nav.indexOf("Getting Started"))
        // The chapter's li nests its own ol: the nested list opens before
        // anything closes the chapter's <li> — a flat sibling list would
        // close it first — and carries both articles, in order.
        val afterChapter = nav.substring(nav.indexOf("unit-003.xhtml"))
        val nestedOl = afterChapter.indexOf("<ol>")
        assertTrue(nestedOl >= 0)
        assertTrue(nestedOl < afterChapter.indexOf("</li>"))
        val nested = afterChapter.substring(nestedOl, afterChapter.indexOf("</ol>"))
        assertTrue(nested.contains("unit-004.xhtml"))
        assertTrue(nested.contains("unit-005.xhtml"))
        assertTrue(nested.indexOf("First") < nested.indexOf("Second"))
    }

    @Test fun everyContentDocumentIsWellFormedXhtml() {
        val all = entries(buildEpub(sampleBook())).associate { it.first.name to it.second }
        val units = all.keys.filter { it.startsWith("OEBPS/unit-") }.sorted()
        assertEquals(5, units.size)
        for (name in units) {
            parseXml(all.getValue(name).toString(Charsets.UTF_8))
        }
    }

    @Test fun xhtmlFixerSelfClosesVoidsAndStripsScripts() {
        val fixed = toXhtml(
            "<p>a<br>\nb</p>\n<hr>\n<img src=\"x.png\" alt=\"pic\">\n" +
                "<script type=\"module\" src=\"rich/md-init.js\"></script>\n" +
                "<div class=\"md-list\"><div class=\"md-item\">" +
                "<span class=\"md-marker\">&bull;</span><span>item</span></div></div>",
        )
        assertTrue(fixed.contains("<br/>"))
        assertTrue(fixed.contains("<hr/>"))
        assertTrue(fixed.contains("<img src=\"x.png\" alt=\"pic\"/>"))
        assertFalse(fixed.contains("<script"))
        assertFalse(fixed.contains("&bull;"))
        parseXml(xhtmlDocument("fixed", fixed))   // well-formed
    }

    @Test fun realRenderedMarkdownSurvivesTheFixer() {
        // The fixture leans on everything the renderer emits that XML would
        // choke on raw: soft breaks, a thematic break, an image, list
        // markers (&bull;), task markers, and a table.
        val source = "# T\n\nline one\nline two\n\n---\n\n" +
            "![badge](https://nettrash.me/favicon.ico)\n\n- plain\n- [x] done\n\n" +
            "| a | b |\n| --- | --- |\n| 1 | 2 |"
        val body = toXhtml(documentBody(MarkdownHtml.document(source, "T", dark = false)))
        parseXml(xhtmlDocument("T", body))
    }

    @Test fun richElementsAreFoundAndReplacedInDocumentOrder() {
        val source = "Inline \$E=mc^2\$ math.\n\n```mermaid\ngraph TD;\nA-->B;\n```"
        val body = toXhtml(documentBody(MarkdownHtml.document(source, "rich", dark = false)))
        val found = findRichElements(body)
        assertEquals(listOf("formula", "diagram"), found.map { it.kind })
        val replaced = replaceRichElements(
            body,
            listOf(
                "<img src=\"images/rich-001.png\" alt=\"formula\"/>",
                "<img src=\"images/rich-002.png\" alt=\"diagram\"/>",
            ),
        )
        assertFalse(replaced.contains("md-mathi"))
        assertFalse(replaced.contains("class=\"mermaid\""))
        assertTrue(replaced.indexOf("rich-001") < replaced.indexOf("rich-002"))
        parseXml(xhtmlDocument("rich", replaced))
    }

    @Test fun graphvizContainersAreFoundWhateverTheirEngine() {
        // A ```dot block in a book article must be captured as a PNG like any
        // other diagram, or the EPUB carries its DOT source as text. The
        // container names its layout program in the tag
        // (`<div class="graphviz" data-engine="sfdp">`), so the marker scan
        // has to match every engine's container, not just the default one.
        val source = "```dot\ndigraph { a -> b }\n```\n\n```sfdp\ngraph { a -- b }\n```"
        val body = toXhtml(documentBody(MarkdownHtml.document(source, "dot", dark = false)))
        assertTrue(body.contains("data-engine=\"sfdp\""))   // fixture uses a non-default engine

        val found = findRichElements(body)
        assertEquals(listOf("diagram", "diagram"), found.map { it.kind })
        // Each range spans the whole element, so replacing it leaves no
        // orphaned open tag behind.
        for (element in found) {
            val markup = body.substring(element.range.first, element.range.last + 1)
            assertTrue(markup.startsWith("<div class=\"graphviz\""))
            assertTrue(markup.endsWith("</div>"))
        }

        val replaced = replaceRichElements(
            body,
            listOf(
                "<img src=\"images/rich-001.png\" alt=\"diagram\"/>",
                "<img src=\"images/rich-002.png\" alt=\"diagram\"/>",
            ),
        )
        assertFalse(replaced.contains("class=\"graphviz\""))
        assertFalse(replaced.contains("data-engine"))
        assertFalse(replaced.contains("digraph"))
        assertTrue(replaced.indexOf("rich-001") < replaced.indexOf("rich-002"))
        parseXml(xhtmlDocument("dot", replaced))
    }

    @Test fun epubIdentifierIsStableForTheSameBook() {
        // A fresh random UUID per export made every export a *different*
        // publication: re-exporting after fixing a typo stacked up beside the
        // old file in a reader's library instead of replacing it, and a store
        // that expects a stable identifier across releases could not take it.
        val first = stableIdentifier("My Book")
        assertEquals("the same book must export the same identifier", first, stableIdentifier("My Book"))

        // A different book is a different publication.
        assertFalse(first == stableIdentifier("Other Book"))

        // A well-formed RFC 4122 version 5 URN: `urn:uuid:` then 8-4-4-4-12
        // hex, with the version nibble 5 and the variant bits `10`.
        assertTrue(first.startsWith("urn:uuid:"))
        val uuid = first.removePrefix("urn:uuid:")
        val groups = uuid.split("-")
        assertEquals(listOf(8, 4, 4, 4, 12), groups.map { it.length })
        assertTrue(uuid.all { it == '-' || it in "0123456789abcdef" })
        assertEquals('5', groups[2].first())          // version 5 — name-based, not random
        assertTrue(groups[3].first() in "89ab")       // RFC 4122 variant

        // Version 5 is a published algorithm, so the answer is not merely
        // self-consistent — these are exactly what `uuid.uuid5(NAMESPACE_URL,
        // name)` gives, and what the Apple apps emit. The Cyrillic title is
        // the one that proves the name is hashed as UTF-8: any other encoding
        // hashes different bytes and lands somewhere else entirely.
        assertEquals("urn:uuid:9905e6b1-7ffd-5c7f-9cef-1d878db12e9d", stableIdentifier("My Book"))
        assertEquals("urn:uuid:07addc7e-aaf2-52ce-8dff-3367db92aca3", stableIdentifier("Other Book"))
        assertEquals("urn:uuid:e3c20fe7-dec7-5bde-b04d-a56022deed36", stableIdentifier("Тетрадь"))
        assertEquals("urn:uuid:1b4db7eb-4057-5ddf-91e0-36dec72071f5", stableIdentifier(""))
    }

    @Test fun exportedBookCarriesItsStableIdentifier() {
        // And it is the value that actually reaches the package document —
        // twice over, byte for byte, since that is the whole point.
        val opf = { book: EpubBook ->
            entries(buildEpub(book, modified = "2026-07-24T00:00:00Z"))
                .associate { it.first.name to it.second }
                .getValue("OEBPS/content.opf").toString(Charsets.UTF_8)
        }
        val identifier = stableIdentifier("My Book")
        assertTrue(opf(sampleBook()).contains("<dc:identifier id=\"book-id\">$identifier</dc:identifier>"))
        assertEquals(opf(sampleBook()), opf(sampleBook()))
    }

    @Test fun emptyBookStillNavigates() {
        val title = EpubUnit("unit-001.xhtml", "Empty", "<h1>Empty</h1>")
        val book = EpubBook("Empty", title, emptyList(), emptyList(), emptyList())
        val all = entries(buildEpub(book)).associate { it.first.name to it.second }
        val nav = all.getValue("OEBPS/nav.xhtml").toString(Charsets.UTF_8)
        parseXml(nav)
        // The toc list may not be empty — the title page stands in.
        assertTrue(nav.contains("<li><a href=\"unit-001.xhtml\">Empty</a></li>"))
    }

    // ---- Single-document EPUB (Feature 1) -----------------------------
    //
    // The one-unit EPUB the document share menu's "Export as EPUB…" makes,
    // assembled exactly as ui/EpubExporter.buildDocument does — minus the
    // WebView, which a plain (no math / no diagram) document never touches, so
    // the whole thing round-trips on the JVM like the book above. The subtle
    // part is what a document must NOT be: no title-page unit, and a nav that
    // is the document's own headings rather than a book tree.

    /** The single-document EPUB for a plain [source]: one content unit whose
     *  body is what documentBody+toXhtml give, spined and packed as
     *  `content.xhtml`, with the nav built from the document's outline.
     *  [modified] is pinned so two exports compare byte-for-byte. */
    private fun documentEpub(source: String, fileName: String, modified: String = "2026-07-24T00:00:00Z"): ByteArray {
        val title = documentTitle(MarkdownParser.frontMatter(source), fileName)
        val body = toXhtml(documentBody(MarkdownHtml.document(source, title, dark = false)))
        val unit = EpubUnit("content.xhtml", title, body)
        val book = EpubBook(title, unit, emptyList(), emptyList(), emptyList())
        return buildEpub(book, modified = modified, nav = documentNavXhtml(title, MarkdownParser.outline(source)))
    }

    @Test fun documentTitlePrefersFrontMatterThenFileName() {
        // A non-empty front-matter title wins over the file name.
        assertEquals(
            "From Matter",
            documentTitle(MarkdownParser.frontMatter("---\ntitle: From Matter\nauthor: x\n---\n\n# Body"), "file.md"),
        )
        // The key matches case-insensitively (generators write Title: too).
        assertEquals(
            "Cased",
            documentTitle(MarkdownParser.frontMatter("---\nTitle: Cased\n---\n\nBody"), "file.md"),
        )
        // An empty title value is not a title — fall through to the file name.
        assertEquals(
            "file.md",
            documentTitle(MarkdownParser.frontMatter("---\ntitle:\nauthor: x\n---\n\nBody"), "file.md"),
        )
        // No front matter at all → the file name (a lone document has no folder
        // name to fall back on, unlike a book).
        assertEquals("Untitled", documentTitle(MarkdownParser.frontMatter("# Just a doc"), "Untitled"))
    }

    @Test fun documentEpubIsAValidStoredZipWithMimetypeFirst() {
        val all = entries(documentEpub("# Hello\n\nBody.", "Doc.md"))
        val (entry, bytes) = all.first()
        assertEquals("mimetype", entry.name)
        assertEquals(ZipEntry.STORED, entry.method)
        assertEquals("application/epub+zip", bytes.toString(Charsets.US_ASCII))
        assertTrue(all.drop(1).all { it.first.method == ZipEntry.DEFLATED })
    }

    @Test fun documentEpubHasOneContentUnitAndNoTitlePage() {
        val all = entries(documentEpub("# One\n\n# Two", "Doc.md")).associate { it.first.name to it.second }
        val opf = all.getValue("OEBPS/content.opf").toString(Charsets.UTF_8)
        val document = parseXml(opf)
        // Exactly one spine item — the content file — with no title-page unit
        // ahead of it (the book path's `unit-001` title page must not appear).
        val idrefs = document.getElementsByTagName("itemref").let { refs ->
            (0 until refs.length).map { refs.item(it).attributes.getNamedItem("idref").nodeValue }
        }
        assertEquals(listOf("content"), idrefs)
        assertTrue(opf.contains("href=\"content.xhtml\""))
        assertFalse(opf.contains("unit-001"))
        // The content document is really in the archive, and the nav points
        // into it rather than at a phantom title page.
        assertTrue(all.containsKey("OEBPS/content.xhtml"))
        val nav = all.getValue("OEBPS/nav.xhtml").toString(Charsets.UTF_8)
        parseXml(nav)
        assertTrue(nav.contains("content.xhtml#"))
        assertFalse(nav.contains("unit-001"))
    }

    @Test fun documentEpubNavListsHeadingsWithSlugsMatchingTheContentAnchors() {
        // Two identically-titled headings force the dedup path (intro,
        // intro-1). The outline and the heading ids both walk MarkdownParser
        // .slug over the headings in document order, so they must stay in
        // lockstep — otherwise a nav tap lands on nothing.
        val source = "# Intro\n\ntext\n\n## Details\n\n# Intro\n\nmore"
        val all = entries(documentEpub(source, "Doc.md")).associate { it.first.name to it.second }
        val nav = all.getValue("OEBPS/nav.xhtml").toString(Charsets.UTF_8)
        val content = all.getValue("OEBPS/content.xhtml").toString(Charsets.UTF_8)

        val outline = MarkdownParser.outline(source)
        assertEquals(listOf("intro", "details", "intro-1"), outline.map { it.slug })
        for (entry in outline) {
            // Every outline slug appears as a nav link into the content file …
            assertTrue("nav must link ${entry.slug}", nav.contains("<a href=\"content.xhtml#${entry.slug}\">"))
            // … and as the matching id MarkdownHtml gave the heading.
            assertTrue("content must anchor ${entry.slug}", content.contains("id=\"${entry.slug}\""))
        }
        // A flat list — the document outline, never a book tree — so the nav
        // carries exactly one <ol> (a nested chapter list would add more).
        assertEquals("nav is a flat list", 1, nav.split("<ol>").size - 1)
        parseXml(nav)
        parseXml(content)
    }

    @Test fun documentEpubIdentifierIsStableAcrossTwoExports() {
        // Two exports of the same document (same title, here from front matter)
        // must reach the same package — byte for byte, and so the same id: a
        // random UUID per export would stack copies up in a reader's library.
        val a = documentEpub("---\ntitle: Whitepaper\n---\n\n# Body", "ignored.md")
        val b = documentEpub("---\ntitle: Whitepaper\n---\n\n# Body", "ignored.md")
        assertArrayEquals(a, b)
        val opf = entries(a).associate { it.first.name to it.second }
            .getValue("OEBPS/content.opf").toString(Charsets.UTF_8)
        // The id is exactly what stableIdentifier gives the resolved title.
        assertTrue(opf.contains("<dc:identifier id=\"book-id\">${stableIdentifier("Whitepaper")}</dc:identifier>"))
        assertTrue(opf.contains("<dc:title>Whitepaper</dc:title>"))
    }

    @Test fun documentEpubWithNoHeadingsStillNavigates() {
        // A heading-less document still needs a non-empty toc list — it falls
        // back to a single link at the content file, the same stand-in the
        // book nav makes for a title-page-only book.
        val all = entries(documentEpub("Just a paragraph, no headings.", "Notes.md"))
            .associate { it.first.name to it.second }
        val nav = all.getValue("OEBPS/nav.xhtml").toString(Charsets.UTF_8)
        parseXml(nav)
        assertTrue(nav.contains("<li><a href=\"content.xhtml\">Notes.md</a></li>"))
    }
}
