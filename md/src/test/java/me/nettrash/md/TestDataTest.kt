/*
 * TestDataTest.kt
 * md (Android)
 *
 * Fixture-driven tests over the shared testdata corpus (mirrored in the
 * iOS / macOS repos as mdTests/TestData, and in md.vscode as
 * test/fixtures/testdata). Every fixture must parse and render, and each
 * per-feature file must carry the construct its name promises, so a
 * fixture edit that loses a feature fails here rather than silently
 * weakening the corpus.
 *
 * Fifteen of the sixteen fixtures are byte-identical in all four repos, and
 * a difference in one of them is drift to be fixed. test.md is the
 * deliberate exception: being the kitchen-sink document, it names the
 * platform it runs on and the command that builds it, so those few lines
 * differ per repo on purpose. Do not unify them.
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

    @Test fun rawPlantUmlDocumentRendersAsDiagram() {
        // An opened `.puml` is bare diagram source with no ```plantuml fence.
        // It must render as one PlantUML diagram — a `.plantuml` container that
        // md-init.js turns into an SVG — not as Markdown text, which would show
        // the `@startuml…` source as paragraphs and never draw anything.
        val puml = "@startuml\nAlice -> Bob: hi\nBob --> Alice: hi\n@enduml\n"
        val html = MarkdownHtml.document(puml, title = "d", dark = false)
        assertTrue(html.contains("<div class=\"plantuml\">"))
        assertTrue(html.contains("rich/viz-global.js"))
        assertFalse(html.contains("<p>@startuml"))

        // Detection skips PlantUML line comments and blank lines before the
        // opener, and recognizes any @start… diagram, not only @startuml.
        assertTrue(MarkdownHtml.isRawPlantUML("' header comment\n\n@startmindmap\n* root\n@endmindmap"))
        assertTrue(MarkdownHtml.isRawPlantUML("   \n@startuml\n@enduml"))

        // A normal Markdown document is untouched: not a whole-document diagram,
        // and `@startuml` mentioned mid-prose is not a false positive.
        assertFalse(MarkdownHtml.isRawPlantUML("# Title\n\nSome prose about @startuml in passing."))
        val md = MarkdownHtml.document("# Title\n\nHello.", title = "d", dark = false)
        assertFalse(md.contains("<div class=\"plantuml\">"))
        assertTrue(md.contains("<h1"))
    }

    @Test fun rawGraphvizDocumentRendersAsDiagram() {
        // An opened `.gv` is bare DOT source with no ```dot fence — the
        // Graphviz counterpart of a raw `.puml`. It must render as one diagram,
        // not as Markdown text.
        val dot = "digraph G {\n  a -> b;\n}\n"
        val html = MarkdownHtml.document(dot, title = "d", dark = false)
        assertTrue(html.contains("<div class=\"graphviz\" data-engine=\"dot\">"))
        assertTrue(html.contains("rich/viz-global.js"))
        assertFalse(html.contains("<p>digraph"))

        // Every shape of opener, and DOT's case-insensitive keywords.
        assertTrue(MarkdownHtml.isRawGraphviz("digraph { a -> b }"))
        assertTrue(MarkdownHtml.isRawGraphviz("graph {}"))
        assertTrue(MarkdownHtml.isRawGraphviz("strict digraph G {\n}"))
        assertTrue(MarkdownHtml.isRawGraphviz("DiGraph Foo {\n}"))
        assertTrue(MarkdownHtml.isRawGraphviz("digraph{a}"))
        // The brace may open on a later line.
        assertTrue(MarkdownHtml.isRawGraphviz("digraph\n{\n  a\n}"))
        // Comments and blank lines before the opener are skipped.
        assertTrue(MarkdownHtml.isRawGraphviz("// generated\n\n/* by hand */\ndigraph { a }"))
        // …but a `#` line is not, precisely so a Markdown heading can't be
        // mistaken for a DOT comment and let the check read past the title.
        assertFalse(MarkdownHtml.isRawGraphviz("# Notes\n\ngraph { the mental model }"))

        // A quoted graph name, which DOT allows.
        assertTrue(MarkdownHtml.isRawGraphviz("digraph \"my graph\" {\n}"))

        // Unicode graph names must behave identically to the Apple siblings,
        // where a Character is a grapheme cluster and a Char here is a UTF-16
        // unit. An NFD-decomposed accent (e + combining acute) is one letter
        // there and two characters here, so without the mark categories the
        // name would end early and the required brace would go missing.
        assertTrue(MarkdownHtml.isRawGraphviz("digraph caf\u0065\u0301 {\n}"))
        assertTrue(MarkdownHtml.isRawGraphviz("digraph caf\u00e9 {\n}"))
        // Swift's isNumber spans Nl/No, Kotlin's isDigit() is Nd alone.
        assertTrue(MarkdownHtml.isRawGraphviz("digraph \u2169 {\n}"))
        // NEL is a line break to Foundation's newline set, so it must be one
        // here too — otherwise this file is one line and the opener is hidden.
        assertTrue(MarkdownHtml.isRawGraphviz("// generated\u0085digraph G {\u0085}"))

        // Not DOT: prose that merely opens with the word, a word that only
        // starts with a keyword, and anything with no brace at all. DOT's
        // header allows one optional name and then a brace — prose has more
        // words than that, which is what keeps an essay opening "graph theory
        // is…" from being swallowed just because a `{` appears further down.
        assertFalse(MarkdownHtml.isRawGraphviz("graph theory is a branch of maths.\n\nSee \$\\frac{a}{b}\$."))
        assertFalse(MarkdownHtml.isRawGraphviz("digraph models are useful { in theory }"))
        assertFalse(MarkdownHtml.isRawGraphviz("graphviz is a fine tool { see }"))
        assertFalse(MarkdownHtml.isRawGraphviz("digraphs are a topic { here }"))
        assertFalse(MarkdownHtml.isRawGraphviz("digraph without a brace"))
        assertFalse(MarkdownHtml.isRawGraphviz("# Title\n\nSome prose about digraph { } in passing."))
        assertFalse(MarkdownHtml.isRawGraphviz(""))

        // Windows line endings. On the Apple siblings a `Character` is a
        // grapheme cluster and CRLF is *one* of them, so a naive split on "\n"
        // handed back the whole file as a single line and any leading blank
        // line or comment hid the opener. Kotlin's split has no such quirk, but
        // both raw-diagram checks are pinned here anyway so the platforms can
        // never drift — this covers the `.puml` path too, which had the same
        // flaw on Apple.
        assertTrue(MarkdownHtml.isRawGraphviz("\r\ndigraph G {\r\n  a -> b;\r\n}\r\n"))
        assertTrue(MarkdownHtml.isRawGraphviz("// generated\r\ndigraph G {\r\n}\r\n"))
        assertTrue(MarkdownHtml.isRawPlantUML("\r\n@startuml\r\nA -> B\r\n@enduml\r\n"))
        assertTrue(MarkdownHtml.isRawPlantUML("' note\r\n\r\n@startmindmap\r\n* r\r\n@endmindmap"))
        // A lone CR (classic Mac) is a line ending too — `split("\n")` alone
        // would not have split it at all.
        assertTrue(MarkdownHtml.isRawGraphviz("// generated\rdigraph G {\r}\r"))
        assertTrue(MarkdownHtml.isRawPlantUML("' note\r@startuml\rA -> B\r@enduml\r"))

        // A diagram nested in a block quote still pulls its engine in: the
        // renderer recurses into quoted blocks, so keying the script includes
        // off the emitted markup (not a scan of the top-level blocks) is what
        // keeps the container and the engine from getting separated.
        val quoted = MarkdownHtml.document("> ```dot\n> digraph { a }\n> ```", title = "d", dark = false)
        assertTrue(quoted.contains("class=\"graphviz\""))
        assertTrue(
            "A quoted diagram must still load the engine that draws it",
            quoted.contains("rich/viz-global.js"),
        )

        // The same for the other two engines, which had the identical flaw.
        val quotedMermaid = MarkdownHtml.document("> ```mermaid\n> graph TD\n> A-->B\n> ```", title = "d", dark = false)
        assertTrue(quotedMermaid.contains("<pre class=\"mermaid\">"))
        assertTrue(quotedMermaid.contains("rich/mermaid.min.js"))
        val quotedPuml = MarkdownHtml.document("> ```plantuml\n> @startuml\n> A->B\n> @enduml\n> ```", title = "d", dark = false)
        assertTrue(quotedPuml.contains("<div class=\"plantuml\">"))
        assertTrue(quotedPuml.contains("rich/viz-global.js"))

        // A Markdown document that merely *contains* a dot fence is still
        // Markdown — the whole-file diagram path must not swallow it.
        val mixed = MarkdownHtml.document("# Title\n\n```dot\ndigraph { a }\n```\n", title = "d", dark = false)
        assertTrue(mixed.contains("<h1"))
        assertTrue(mixed.contains("class=\"graphviz\""))
    }
}
