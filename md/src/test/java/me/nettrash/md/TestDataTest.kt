/*
 * TestDataTest.kt
 * md (Android)
 *
 * Fixture-driven tests over the shared testdata corpus (mirrored in the
 * iOS / macOS repos as mdTests/TestData). Every fixture must parse and
 * render, and each per-feature file must carry the construct its name
 * promises, so a fixture edit that loses a feature fails here rather
 * than silently weakening the corpus.
 */

package me.nettrash.md

import me.nettrash.md.markdown.MarkdownBlock
import me.nettrash.md.markdown.MarkdownHtml
import me.nettrash.md.markdown.MarkdownParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TestDataTest {

    private val fixtures = listOf(
        "blockquotes", "code", "edge-cases", "headings", "images",
        "inline", "lists", "math", "mermaid", "notes", "outline",
        "page-breaks", "plantuml", "tables", "test", "thematic-breaks",
    )

    private fun load(name: String): String =
        checkNotNull(javaClass.classLoader?.getResourceAsStream("testdata/$name.md")) {
            "missing fixture $name.md"
        }.bufferedReader().use { it.readText() }

    @Test fun corpusIsComplete() {
        val dir = checkNotNull(javaClass.classLoader?.getResource("testdata")) {
            "missing testdata resource directory"
        }
        val names = java.io.File(dir.toURI())
            .listFiles { file -> file.extension == "md" }
            ?.map { it.nameWithoutExtension }
            ?.sorted()
        assertEquals(fixtures, names)
    }

    @Test fun everyFixtureParsesAndRenders() {
        for (name in fixtures) {
            val source = load(name)
            assertTrue("$name.md parsed to no blocks", MarkdownParser.parse(source).isNotEmpty())
            val html = MarkdownHtml.document(source, title = name, dark = false)
            assertTrue("$name.md rendered no body", html.contains("<body"))
        }
    }

    @Test fun headingsFixtureCoversAllSixLevels() {
        val levels = MarkdownParser.parse(load("headings"))
            .filterIsInstance<MarkdownBlock.Heading>()
            .map { it.level }
            .toSet()
        assertTrue(levels.containsAll((1..6).toList()))
    }

    @Test fun tablesFixtureParsesTables() {
        val tables = MarkdownParser.parse(load("tables"))
            .filterIsInstance<MarkdownBlock.Table>()
        // Three real tables; the delimiter-less pair of lines is not one.
        assertEquals(3, tables.size)
    }

    @Test fun listsFixtureCarriesOrderedAndUnordered() {
        val lists = MarkdownParser.parse(load("lists"))
            .filterIsInstance<MarkdownBlock.ListBlock>()
        assertTrue(lists.any { it.ordered })
        assertTrue(lists.any { !it.ordered })
    }

    @Test fun pageBreaksFixtureCarriesBothSpellings() {
        val breaks = MarkdownParser.parse(load("page-breaks"))
            .filterIsInstance<MarkdownBlock.PageBreak>()
        assertEquals(2, breaks.size)
    }

    @Test fun notesFixtureKeepsPrivateNotesOutOfTheHtml() {
        val source = load("notes")
        // Two `note:` comments; the plain comment is not a note.
        assertEquals(2, MarkdownParser.notes(source).size)
        val html = MarkdownHtml.document(source, title = "notes", dark = false)
        assertTrue(html.contains("Visible prose before"))
        assertFalse(html.contains("private author note"))
        assertFalse(html.contains("plain comment"))
    }

    @Test fun outlineFixtureDedupesAndSlugs() {
        val outline = MarkdownParser.outline(load("outline"))
        val slugs = outline.map { it.slug }
        assertTrue(slugs.contains("section"))
        assertTrue(slugs.contains("section-1"))
        assertTrue(slugs.contains("c--f"))
        assertFalse(outline.any { it.text.contains("not a heading") })
        assertEquals("Setext also counts", outline.last().text)
    }

    @Test fun imagesFixtureEmitsImgTags() {
        val html = MarkdownHtml.document(load("images"), title = "images", dark = false)
        assertTrue(
            html.contains(
                "<img src=\"https://nettrash.me/favicon.ico\" alt=\"nettrash.me favicon\" title=\"The favicon\">",
            ),
        )
        assertTrue(
            html.contains(
                "<a href=\"https://nettrash.me\"><img src=\"https://nettrash.me/favicon.ico\" alt=\"badge\"></a>",
            ),
        )
    }

    @Test fun richFixturesEmitTheirContainers() {
        val math = MarkdownHtml.document(load("math"), title = "math", dark = false)
        assertTrue(math.contains("class=\"md-mathi\""))
        assertTrue(math.contains("class=\"md-mathd\""))
        assertTrue(math.contains("katex.min.js"))

        val mermaid = MarkdownHtml.document(load("mermaid"), title = "mermaid", dark = false)
        assertTrue(mermaid.contains("<pre class=\"mermaid\">"))

        val plantuml = MarkdownHtml.document(load("plantuml"), title = "plantuml", dark = false)
        assertTrue(plantuml.contains("<div class=\"plantuml\">"))
    }
}
