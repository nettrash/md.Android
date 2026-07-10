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

    @Test fun emptyBookStillNavigates() {
        val title = EpubUnit("unit-001.xhtml", "Empty", "<h1>Empty</h1>")
        val book = EpubBook("Empty", title, emptyList(), emptyList(), emptyList())
        val all = entries(buildEpub(book)).associate { it.first.name to it.second }
        val nav = all.getValue("OEBPS/nav.xhtml").toString(Charsets.UTF_8)
        parseXml(nav)
        // The toc list may not be empty — the title page stands in.
        assertTrue(nav.contains("<li><a href=\"unit-001.xhtml\">Empty</a></li>"))
    }
}
