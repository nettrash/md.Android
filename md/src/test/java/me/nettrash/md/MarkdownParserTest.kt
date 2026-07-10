/*
 * MarkdownParserTest.kt
 * md (Android)
 *
 * JVM unit tests for the block-level Markdown parser and the HTML export —
 * the pieces with non-trivial logic. Ported from the iOS / macOS app's
 * `mdTests.swift` so the Kotlin parser stays byte-faithful to the Swift
 * one: headings, paragraphs, lists, fences, quotes, tables, rules and the
 * edge cases that separate them, plus the HTML serialization.
 */

package me.nettrash.md

import me.nettrash.md.markdown.ColumnAlignment
import me.nettrash.md.markdown.MarkdownBlock
import me.nettrash.md.markdown.MarkdownHtml
import me.nettrash.md.markdown.MarkdownParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MarkdownParserTest {

    private fun parse(s: String) = MarkdownParser.parse(s)

    // headings

    @Test fun headingLevels() {
        for (level in 1..6) {
            val hashes = "#".repeat(level)
            val b = parse("$hashes Title").first()
            assertTrue(b is MarkdownBlock.Heading)
            b as MarkdownBlock.Heading
            assertEquals(level, b.level)
            assertEquals("Title", b.text)
        }
    }

    @Test fun headingRequiresSpace() {
        assertTrue(parse("#Title").first() is MarkdownBlock.Paragraph)
    }

    @Test fun headingSevenHashesIsParagraph() {
        assertTrue(parse("####### too deep").first() is MarkdownBlock.Paragraph)
    }

    @Test fun headingClosingHashesStripped() {
        val b = parse("## Title ##").first() as MarkdownBlock.Heading
        assertEquals("Title", b.text)
    }

    @Test fun headingPreservesTrailingHashInWord() {
        assertEquals("C#", (parse("# C#").first() as MarkdownBlock.Heading).text)
        assertEquals("F# notes", (parse("# F# notes").first() as MarkdownBlock.Heading).text)
    }

    // paragraphs

    @Test fun paragraphPreservesSoftBreaks() {
        val b = parse("line one\nline two").first() as MarkdownBlock.Paragraph
        assertEquals("line one\nline two", b.text)
    }

    @Test fun blankLineSeparatesParagraphs() {
        val kinds = parse("first\n\nsecond")
        assertEquals(2, kinds.size)
        assertTrue(kinds[0] is MarkdownBlock.Paragraph)
        assertTrue(kinds[1] is MarkdownBlock.Paragraph)
    }

    // lists

    @Test fun unorderedList() {
        val b = parse("- a\n- b\n* c").first() as MarkdownBlock.ListBlock
        assertFalse(b.ordered)
        assertEquals(listOf("a", "b", "c"), b.items.map { it.text })
    }

    @Test fun orderedList() {
        val b = parse("1. one\n2. two\n3) three").first() as MarkdownBlock.ListBlock
        assertTrue(b.ordered)
        assertEquals(listOf(1, 2, 3), b.items.map { it.ordinal })
    }

    @Test fun nestedListLevels() {
        val b = parse("- top\n  - nested\n    - deeper").first() as MarkdownBlock.ListBlock
        assertEquals(listOf(0, 1, 2), b.items.map { it.level })
    }

    @Test fun taskList() {
        val b = parse("- [ ] todo\n- [x] done\n- [X] also").first() as MarkdownBlock.ListBlock
        assertEquals(listOf(false, true, true), b.items.map { it.task })
        assertEquals(listOf("todo", "done", "also"), b.items.map { it.text })
    }

    @Test fun listItemContinuationIsAbsorbed() {
        val blocks = parse("- First item\n  with continuation\n- Second item")
        assertEquals(1, blocks.size)
        val list = blocks.first() as MarkdownBlock.ListBlock
        assertEquals(2, list.items.size)
        assertEquals("First item with continuation", list.items[0].text)
        assertEquals("Second item", list.items[1].text)
    }

    @Test fun tabIndentedNestedListRecognised() {
        val b = parse("- top\n\t- nested").first() as MarkdownBlock.ListBlock
        assertEquals(listOf("top", "nested"), b.items.map { it.text })
        assertTrue(b.items[1].level > b.items[0].level)
    }

    // code fences

    @Test fun fencedCodeWithLanguage() {
        val b = parse("```swift\nlet x = 1\n```").first() as MarkdownBlock.CodeBlock
        assertEquals("swift", b.language)
        assertEquals("let x = 1", b.code)
    }

    @Test fun tildeFence() {
        val b = parse("~~~\nplain\n~~~").first() as MarkdownBlock.CodeBlock
        assertEquals("plain", b.code)
    }

    @Test fun fenceContentIsNotInterpreted() {
        val b = parse("```\n# not a heading\n```").first() as MarkdownBlock.CodeBlock
        assertEquals("# not a heading", b.code)
    }

    @Test fun unclosedFenceConsumesToEnd() {
        val b = parse("```\na\nb").first() as MarkdownBlock.CodeBlock
        assertEquals("a\nb", b.code)
    }

    @Test fun indentedFenceStripsIndent() {
        val b = parse("  ```\n  indented\n  ```").first() as MarkdownBlock.CodeBlock
        assertEquals("indented", b.code)
    }

    // block quotes

    @Test fun blockQuote() {
        val q = parse("> quoted\n> text").first() as MarkdownBlock.Quote
        val p = q.blocks.first() as MarkdownBlock.Paragraph
        assertEquals("quoted\ntext", p.text)
    }

    @Test fun nestedBlockQuote() {
        val q = parse("> > deep").first() as MarkdownBlock.Quote
        assertTrue(q.blocks.first() is MarkdownBlock.Quote)
    }

    @Test fun deeplyNestedQuoteDoesNotOverflow() {
        val input = ">".repeat(5000) + " deep"
        val blocks = MarkdownParser.parse(input)   // must return, not crash
        assertTrue(blocks.isNotEmpty())
        assertTrue(blocks.first() is MarkdownBlock.Quote)
    }

    // thematic breaks

    @Test fun thematicBreaks() {
        for (rule in listOf("---", "***", "___", "- - -", "****")) {
            assertTrue(parse(rule).first() is MarkdownBlock.ThematicBreak)
        }
    }

    @Test fun dashesUnderTextAreNotRuleWhenTooShort() {
        assertTrue(parse("--").first() is MarkdownBlock.Paragraph)
    }

    @Test fun standaloneRuleStillParsesAfterSetextChange() {
        assertTrue(parse("---").first() is MarkdownBlock.ThematicBreak)
    }

    // setext headings

    @Test fun setextHeadings() {
        val h1 = parse("My Title\n===").first() as MarkdownBlock.Heading
        assertEquals(1, h1.level)
        assertEquals("My Title", h1.text)

        val h2blocks = parse("My Title\n---")
        val h2 = h2blocks.first() as MarkdownBlock.Heading
        assertEquals(2, h2.level)
        assertEquals("My Title", h2.text)
        assertEquals(1, h2blocks.size)   // no spurious thematic break
    }

    // tables

    @Test fun tableParsing() {
        val md = """
            | Name | Age |
            | :--- | ---: |
            | Ann  | 30 |
            | Bob  | 25 |
        """.trimIndent()
        val t = parse(md).first() as MarkdownBlock.Table
        assertEquals(listOf("Name", "Age"), t.header)
        assertEquals(listOf(ColumnAlignment.LEADING, ColumnAlignment.TRAILING), t.alignments)
        assertEquals(listOf(listOf("Ann", "30"), listOf("Bob", "25")), t.rows)
    }

    @Test fun tableCenterAlignment() {
        val t = parse("| A | B |\n|:-:|:-:|\n| 1 | 2 |").first() as MarkdownBlock.Table
        assertEquals(listOf(ColumnAlignment.CENTER, ColumnAlignment.CENTER), t.alignments)
    }

    @Test fun tableEscapedPipe() {
        val t = parse("| Col |\n| --- |\n| a \\| b |").first() as MarkdownBlock.Table
        assertEquals(listOf(listOf("a | b")), t.rows)
    }

    @Test fun notATableWithoutDelimiterRow() {
        assertTrue(parse("a | b | c\nx | y | z").first() is MarkdownBlock.Paragraph)
    }

    // mixed document

    @Test fun mixedDocumentBlockSequence() {
        val md = """
            # Title

            Intro paragraph.

            - one
            - two

            > a quote

            ```
            code
            ```

            ---
        """.trimIndent()
        val kinds = parse(md)
        assertEquals(6, kinds.size)
        assertTrue(kinds[0] is MarkdownBlock.Heading)
        assertTrue(kinds[1] is MarkdownBlock.Paragraph)
        assertTrue(kinds[2] is MarkdownBlock.ListBlock)
        assertTrue(kinds[3] is MarkdownBlock.Quote)
        assertTrue(kinds[4] is MarkdownBlock.CodeBlock)
        assertTrue(kinds[5] is MarkdownBlock.ThematicBreak)
    }

    // HTML serialization (print / PDF)

    @Test fun htmlWrapsDocument() {
        val html = MarkdownHtml.document("# Title", "Doc", dark = false)
        assertTrue(html.contains("<!DOCTYPE html>"))
        assertTrue(html.contains("<title>Doc</title>"))
        assertTrue(html.contains("<h1 id=\"title\">Title</h1>"))
    }

    @Test fun htmlEscapesSpecialCharacters() {
        val html = MarkdownHtml.document("a < b & c > d", "t", dark = false)
        assertTrue(html.contains("a &lt; b &amp; c &gt; d"))
    }

    @Test fun htmlInlineEmphasis() {
        val html = MarkdownHtml.document("**bold** and *italic* and ~~gone~~", "t", dark = false)
        assertTrue(html.contains("<strong>bold</strong>"))
        assertTrue(html.contains("<em>italic</em>"))
        assertTrue(html.contains("<del>gone</del>"))
    }

    @Test fun htmlCodeSpanIsEscapedAndNotReinterpreted() {
        val html = MarkdownHtml.document("`a < *b* > c`", "t", dark = false)
        assertTrue(html.contains("<code>a &lt; *b* &gt; c</code>"))
        assertFalse(html.contains("<em>b</em>"))
    }

    @Test fun htmlLink() {
        val html = MarkdownHtml.document("[site](https://nettrash.me)", "t", dark = false)
        assertTrue(html.contains("<a href=\"https://nettrash.me\">site</a>"))
    }

    @Test fun htmlLinkWithTitle() {
        val html = MarkdownHtml.document("[site](https://nettrash.me \"Hover title\")", "t", dark = false)
        assertTrue(html.contains("<a href=\"https://nettrash.me\" title=\"Hover title\">site</a>"))
    }

    @Test fun htmlImage() {
        val html = MarkdownHtml.document("![Alt text](https://nettrash.me/favicon.ico)", "t", dark = false)
        assertTrue(html.contains("<img src=\"https://nettrash.me/favicon.ico\" alt=\"Alt text\">"))
    }

    @Test fun htmlImageWithTitle() {
        val html = MarkdownHtml.document("![Alt](https://nettrash.me/favicon.ico \"The favicon\")", "t", dark = false)
        assertTrue(html.contains("<img src=\"https://nettrash.me/favicon.ico\" alt=\"Alt\" title=\"The favicon\">"))
    }

    @Test fun htmlLinkedImage() {
        // The image pass must run before the link pass, so `[![…](…)](…)`
        // nests the <img> inside the <a> instead of the link eating the label.
        val html = MarkdownHtml.document(
            "[![badge](https://nettrash.me/favicon.ico)](https://nettrash.me)", "t", dark = false,
        )
        assertTrue(
            html.contains(
                "<a href=\"https://nettrash.me\"><img src=\"https://nettrash.me/favicon.ico\" alt=\"badge\"></a>",
            ),
        )
    }

    @Test fun htmlUnderscoreInWordIsNotItalic() {
        val html = MarkdownHtml.document("call some_long_name now", "t", dark = false)
        assertFalse(html.contains("<em>"))
    }

    @Test fun htmlTableAlignmentsAndCells() {
        val html = MarkdownHtml.document("| A | B |\n|:-:|--:|\n| 1 | 2 |", "t", dark = false)
        assertTrue(html.contains("text-align:center"))
        assertTrue(html.contains("text-align:right"))
        assertTrue(html.contains("<td"))
    }

    @Test fun htmlThemeVariantsDiffer() {
        val light = MarkdownHtml.document("hi", "t", dark = false)
        val dark = MarkdownHtml.document("hi", "t", dark = true)
        assertTrue(light != dark)
        assertTrue(dark.contains("color-scheme: dark"))
        assertTrue(dark.contains("print-color-adjust: exact"))
    }

    // Rich blocks — math / Mermaid / PlantUML (v1.1)

    @Test fun htmlMermaidBlockEmitsContainer() {
        val html = MarkdownHtml.document("```mermaid\ngraph TD\nA-->B\n```", "t", dark = false)
        assertTrue(html.contains("<pre class=\"mermaid\">"))
        assertTrue(html.contains("graph TD"))
        assertFalse(html.contains("<pre><code>graph TD"))
        assertTrue(html.contains("mermaid.min.js"))
        assertFalse(html.contains("katex.min.js"))
        assertFalse(html.contains("viz-global.js"))
    }

    @Test fun htmlPlantumlBlockEmitsContainer() {
        val html = MarkdownHtml.document("```plantuml\n@startuml\nA->B\n@enduml\n```", "t", dark = false)
        assertTrue(html.contains("<div class=\"plantuml\">"))
        assertTrue(html.contains("@startuml"))
        assertTrue(html.contains("viz-global.js"))
    }

    @Test fun htmlMathFenceEmitsDisplayMath() {
        val html = MarkdownHtml.document("```math\n\\int_0^1 x\\,dx\n```", "t", dark = false)
        assertTrue(html.contains("class=\"md-mathd\""))
        assertTrue(html.contains("\\int_0^1"))
        assertTrue(html.contains("katex.min.js"))
    }

    @Test fun htmlInlineMathIsNotMangledByEmphasis() {
        // A `*` inside inline math must stay literal, not become <em>.
        val html = MarkdownHtml.document("total \$a*b*c\$ units", "t", dark = false)
        assertTrue(html.contains("class=\"md-mathi\""))
        assertTrue(html.contains("a*b*c"))
        assertFalse(html.contains("<em>"))
        assertTrue(html.contains("katex.min.js"))
    }

    @Test fun htmlDisplayMathSpanPreserved() {
        val html = MarkdownHtml.document("\$\$x^2 + y^2\$\$", "t", dark = false)
        assertTrue(html.contains("class=\"md-mathd\""))
        assertTrue(html.contains("x^2 + y^2"))
    }

    @Test fun htmlCurrencyDollarsAreNotMath() {
        val html = MarkdownHtml.document("it costs \$5 and \$10 today", "t", dark = false)
        assertTrue(html.contains("\$5 and \$10"))
        assertFalse(html.contains("class=\"md-mathi\""))
        assertFalse(html.contains("katex.min.js"))
    }

    @Test fun htmlPlainDocumentStaysLight() {
        val html = MarkdownHtml.document("# Just text\n\nA paragraph.", "t", dark = false)
        assertFalse(html.contains("katex.min.js"))
        assertFalse(html.contains("mermaid.min.js"))
        assertFalse(html.contains("viz-global.js"))
        assertTrue(html.contains("rich/md-init.js"))
    }

    @Test fun htmlCodeSpanDollarIsNotMath() {
        val html = MarkdownHtml.document("use `\$x\$` here", "t", dark = false)
        assertTrue(html.contains("<code>\$x\$</code>"))
    }

    // MARK: Page breaks, notes & outline

    @Test fun pageBreakParses() {
        val blocks = MarkdownParser.parse("before\n\n\\newpage\n\nafter")
        assertEquals(3, blocks.size)
        assertTrue(blocks[1] is MarkdownBlock.PageBreak)
    }

    @Test fun pageBreakVariantInterruptsParagraph() {
        // `\pagebreak` works too, and a marker interrupts a paragraph run.
        val blocks = MarkdownParser.parse("line one\n\\pagebreak\nline two")
        assertEquals(3, blocks.size)
        assertTrue(blocks[1] is MarkdownBlock.PageBreak)
    }

    @Test fun noteCommentBecomesNoteBlock() {
        val blocks = MarkdownParser.parse("<!-- note: check the intro -->")
        assertEquals(1, blocks.size)
        assertEquals("check the intro", (blocks[0] as MarkdownBlock.Note).text)
    }

    @Test fun plainCommentIsDropped() {
        // A non-note HTML comment vanishes entirely — no block, no output.
        val blocks = MarkdownParser.parse("a\n\n<!-- just a comment -->\n\nb")
        assertEquals(2, blocks.size)
    }

    @Test fun multilineNote() {
        val blocks = MarkdownParser.parse("<!-- note: first\nsecond -->")
        val note = blocks.first() as MarkdownBlock.Note
        assertTrue(note.text.contains("first"))
        assertTrue(note.text.contains("second"))
    }

    @Test fun outlineLevelsSlugsAndLines() {
        val source = "# One\n\ntext\n\n## Two\n\n```\n# not a heading\n```\n\nSetext\n---"
        val outline = MarkdownParser.outline(source)
        assertEquals(3, outline.size)
        assertEquals(1, outline[0].level)
        assertEquals("one", outline[0].slug)
        assertEquals(0, outline[0].line)
        assertEquals("two", outline[1].slug)
        assertEquals(2, outline[2].level)            // setext `---` underline
        assertEquals("Setext", outline[2].text)
        assertEquals(10, outline[2].line)
    }

    @Test fun duplicateHeadingSlugsAreDeduped() {
        val outline = MarkdownParser.outline("# Same\n\n# Same")
        assertEquals(listOf("same", "same-1"), outline.map { it.slug })
    }

    @Test fun outlineSkipsUnderlineAfterMultiLineParagraph() {
        // `---` after a 2+-line paragraph is a rule, not a setext heading —
        // parse() and outline() must agree, or the Contents menu would list
        // a phantom entry and desync every later anchor slug.
        assertTrue(MarkdownParser.outline("line1\nline2\n---").isEmpty())
        assertEquals(1, MarkdownParser.outline("only\n---").size)
    }

    @Test fun slugDropsPunctuationLikeGitHub() {
        assertEquals("c--f", MarkdownParser.slug("C# & F#!", HashMap()))
    }

    @Test fun notesHelperFindsLine() {
        val notes = MarkdownParser.notes("start\n\n<!-- note: fix me -->\n\nend")
        assertEquals(1, notes.size)
        assertEquals("fix me", notes[0].text)
        assertEquals(2, notes[0].line)
    }

    @Test fun htmlHeadingsCarryAnchorIds() {
        val html = MarkdownHtml.document("# My Title\n\n# My Title", "t", dark = false)
        assertTrue(html.contains("<h1 id=\"my-title\">"))
        assertTrue(html.contains("<h1 id=\"my-title-1\">"))
    }

    @Test fun htmlPageBreakMarkerAndExportCss() {
        val preview = MarkdownHtml.document("a\n\n\\newpage\n\nb", "t", dark = false)
        assertTrue(preview.contains("md-pagebreak"))
        assertFalse(preview.contains("break-after: page"))
        val export = MarkdownHtml.document("a\n\n\\newpage\n\nb", "t", dark = false, export = true)
        assertTrue(export.contains("break-after: page"))
    }

    @Test fun htmlOmitsAuthorNotes() {
        val html = MarkdownHtml.document("visible\n\n<!-- note: secret draft thought -->", "t", dark = false)
        assertTrue(html.contains("visible"))
        assertFalse(html.contains("secret draft thought"))
    }
}
