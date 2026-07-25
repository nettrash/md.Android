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
import me.nettrash.md.markdown.MetadataField
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
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

    // front matter

    @Test fun yamlFrontMatterIsParsedAndHidden() {
        val md = """
            ---
            title: My Book
            author: Ivan Alekseev
            date: 2026-07-24
            ---

            # Chapter One

            Text.
        """.trimIndent()
        val kinds = parse(md)
        val first = kinds.firstOrNull()
        assertTrue("expected front matter first", first is MarkdownBlock.FrontMatter)
        first as MarkdownBlock.FrontMatter
        assertEquals(
            listOf(
                MetadataField("title", "My Book"),
                MetadataField("author", "Ivan Alekseev"),
                MetadataField("date", "2026-07-24"),
            ),
            first.fields,
        )
        // The opening `---` must not survive as a thematic break, and the
        // metadata must not survive as prose — that is how such a file used
        // to look, and it is the whole point of the feature.
        assertFalse(kinds.any { it is MarkdownBlock.ThematicBreak })
        val html = MarkdownHtml.document(md, "t", dark = false)
        assertFalse(html.contains("My Book"))
        assertFalse(html.contains("<hr>"))
        assertTrue(html.contains("Chapter One"))
    }

    @Test fun tomlFrontMatterUsesEquals() {
        val kinds = parse("+++\ntitle = \"Quoted\"\ndraft = false\n+++\n\nBody.")
        val first = kinds.firstOrNull()
        assertTrue("expected front matter", first is MarkdownBlock.FrontMatter)
        first as MarkdownBlock.FrontMatter
        // Quotes around a value are stripped — every generator writes some.
        assertEquals(
            listOf(
                MetadataField("title", "Quoted"),
                MetadataField("draft", "false"),
            ),
            first.fields,
        )
    }

    @Test fun frontMatterOnlyAtTheVeryTopAndOnlyWhenClosed() {
        // An unclosed fence is not front matter: a document that simply opens
        // with a horizontal rule must keep its rule rather than have the rest
        // of the file swallowed as metadata.
        val unclosed = parse("---\n\nJust a rule above.")
        assertTrue(
            "an unclosed opener stays a thematic break",
            unclosed.firstOrNull() is MarkdownBlock.ThematicBreak,
        )
        // The dangerous case, because a YAML opener is spelled exactly like a
        // thematic break: a document that opens with a rule, says something,
        // and rules off again. Every word of it must survive — an earlier
        // version of this swallowed the prose between the two rules.
        val divider = parse("---\n\nIntro the reader must see.\n\n---\n\nMore.")
        assertTrue(
            "a rule followed by a blank line is a rule",
            divider.firstOrNull() is MarkdownBlock.ThematicBreak,
        )
        assertTrue(
            "the prose under the rule must survive",
            divider.any { it is MarkdownBlock.Paragraph && it.text == "Intro the reader must see." },
        )

        // Same guard without the blank line: no `key: value` anywhere means it
        // was never metadata, so this stays a break plus a setext heading —
        // which is how it reads in every other Markdown tool.
        val setext = parse("---\nChapter One\n---\n\nText.")
        assertFalse(setext.any { it is MarkdownBlock.FrontMatter })
        assertTrue(
            "the setext heading must survive",
            setext.any { it is MarkdownBlock.Heading && it.level == 2 && it.text == "Chapter One" },
        )
        // The blank-line guard on its own. Only this input separates the two
        // guards: here the block *does* hold a readable field, so the blank
        // line after the opener is the sole reason it is not metadata — the
        // cases above are caught by the "at least one field" guard as well, so
        // without this the blank-line guard could be deleted and the suite
        // would stay green. It reads as a rule, a setext heading and a
        // paragraph, as on Apple.
        val blankThenField = parse("---\n\ntitle: A\n---\n\nbody")
        assertFalse(
            "a readable field below a blank line is still not metadata",
            blankThenField.any { it is MarkdownBlock.FrontMatter },
        )
        assertTrue(
            "a blank line after the opener rules out front matter",
            blankThenField.firstOrNull() is MarkdownBlock.ThematicBreak,
        )
        assertTrue(
            blankThenField.any { it is MarkdownBlock.Heading && it.level == 2 && it.text == "title: A" },
        )

        // A fence further down is an ordinary thematic break too.
        val later = parse("# Title\n\n---\n\ntitle: not metadata\n\n---")
        assertFalse(later.any { it is MarkdownBlock.FrontMatter })
        // And it is never front matter inside a block quote.
        val quote = parse("> ---\n> title: x\n> ---").firstOrNull()
        assertTrue("expected a quote", quote is MarkdownBlock.Quote)
        quote as MarkdownBlock.Quote
        assertFalse(quote.blocks.any { it is MarkdownBlock.FrontMatter })
    }

    @Test fun frontMatterSkipsWhatTheFlatScanCannotRead() {
        // Lists, nested mappings and comments are skipped, but the block is
        // still consumed whole — which is what decides how the page looks.
        val md = """
            ---
            title: Deep
            # a comment
            tags:
              - one
              - two
            ---
            Body.
        """.trimIndent()
        val kinds = parse(md)
        val first = kinds.firstOrNull()
        assertTrue("expected front matter", first is MarkdownBlock.FrontMatter)
        first as MarkdownBlock.FrontMatter
        assertEquals(MetadataField("title", "Deep"), first.fields.firstOrNull())
        assertTrue(first.fields.contains(MetadataField("tags", "")))
        assertFalse(first.fields.any { it.key.startsWith("-") })
        // Exactly one block follows, and it is the body paragraph.
        assertEquals(2, kinds.size)
        val last = kinds.last()
        assertTrue("expected the body paragraph", last is MarkdownBlock.Paragraph)
        assertEquals("Body.", (last as MarkdownBlock.Paragraph).text)
    }

    @Test fun frontMatterDoesNotLeakIntoTheOutlineOrNotes() {
        // The closing `---` underlines the last metadata line, so a scanner
        // that walks the raw source reads it as a setext heading the rendered
        // document does not contain — and its slug then pushes every real
        // heading's anchor out of step with the ids MarkdownHtml assigns, so
        // the table of contents scrolls to nothing.
        val md = "---\ntitle: My Post\n---\n\n# Hello\n"
        val outline = MarkdownParser.outline(md)
        assertEquals(listOf("Hello"), outline.map { it.text })
        assertEquals("hello", outline.firstOrNull()?.slug)
        val html = MarkdownHtml.document(md, "t", dark = false)
        assertTrue("the TOC slug must match the rendered id", html.contains("id=\"hello\""))

        // The same for a heading whose slug the phantom would have stolen.
        val clash = "---\nHello: x\n---\n\n# Hello: x\n"
        assertEquals("hello-x", MarkdownParser.outline(clash).firstOrNull()?.slug)
        assertTrue(MarkdownHtml.document(clash, "t", dark = false).contains("id=\"hello-x\""))

        // A comment inside the metadata is not one of the author's notes.
        assertTrue(MarkdownParser.notes("---\ntitle: X\n<!-- note: hidden -->\n---\n\nBody.").isEmpty())
        // …while a note in the document proper is still found.
        assertEquals(1, MarkdownParser.notes("---\ntitle: X\n---\n\n<!-- note: real -->").size)
    }

    @Test fun frontMatterAccessor() {
        val fields = MarkdownParser.frontMatter("---\nauthor: Ann\n---\n\nHi.")
        assertEquals(listOf(MetadataField("author", "Ann")), fields)
        assertTrue(MarkdownParser.frontMatter("# Plain\n\nNo metadata.").isEmpty())
    }

    // footnotes

    @Test fun footnoteDefinitionIsParsedAndNotDrawnInPlace() {
        val kinds = parse("Text[^a].\n\n[^a]: The note.")
        val last = kinds.lastOrNull()
        assertTrue("expected a footnote definition", last is MarkdownBlock.FootnoteDefinition)
        last as MarkdownBlock.FootnoteDefinition
        assertEquals("a", last.id)
        assertEquals("The note.", last.text)
        // A definition must not also render as a paragraph where it was written.
        val html = MarkdownHtml.document("Text[^a].\n\n[^a]: The note.", "t", dark = false)
        assertFalse(html.contains("<p>[^a]: The note.</p>"))
        assertTrue(html.contains("<li id=\"fn-1\">The note."))
    }

    @Test fun footnotesAreNumberedByFirstReferenceNotDefinitionOrder() {
        // `b` is cited first, so it is footnote 1 even though `a` is defined
        // first — the number a reader sees follows the reading order.
        val html = MarkdownHtml.document(
            "See[^b] then[^a].\n\n[^a]: Alpha.\n[^b]: Bravo.", "t", dark = false,
        )
        assertTrue(html.contains("<li id=\"fn-1\">Bravo."))
        assertTrue(html.contains("<li id=\"fn-2\">Alpha."))
        val first = html.indexOf("#fn-1")
        val second = html.indexOf("#fn-2")
        assertTrue("expected both references", first >= 0 && second >= 0)
        assertTrue(first < second)
    }

    @Test fun repeatedFootnoteReferenceGetsItsOwnAnchor() {
        val html = MarkdownHtml.document("One[^a] two[^a].\n\n[^a]: Note.", "t", dark = false)
        // Both cite footnote 1…
        assertEquals(2, html.split("href=\"#fn-1\"").size - 1)
        // …but each reference is its own anchor, so the ids stay unique.
        assertTrue(html.contains("id=\"fnref-1\""))
        assertTrue(html.contains("id=\"fnref-1-2\""))
        // The note's back-link goes to the first citation.
        assertTrue(html.contains("href=\"#fnref-1\""))
    }

    @Test fun footnoteReferenceWithoutDefinitionStaysLiteralText() {
        // Linking to nothing would be worse than leaving the author's text be.
        val html = MarkdownHtml.document("A claim[^nope].", "t", dark = false)
        assertTrue(html.contains("[^nope]"))
        // Assert on the emitted markup, not the class name: the stylesheet
        // names every class unconditionally, so a bare `contains` would pass
        // no matter what the body actually holds.
        assertFalse(html.contains("<sup class=\"md-fnref\""))
        assertFalse(html.contains("<section class=\"md-footnotes\">"))
    }

    @Test fun unreferencedFootnoteIsStillPrinted() {
        // Dropping it would silently discard something the author wrote. It
        // gets no back-link, having nowhere to go back to.
        val html = MarkdownHtml.document("Body.\n\n[^lone]: Never cited.", "t", dark = false)
        assertTrue(html.contains("<li id=\"fn-1\">Never cited."))
        assertFalse(html.contains("<a class=\"md-fnback\""))
    }

    @Test fun footnoteTextIsInlineMarkdownAndIsEscaped() {
        val html = MarkdownHtml.document(
            "X[^a].\n\n[^a]: *Emphasis* and <b>literal</b> & co.", "t", dark = false,
        )
        assertTrue(html.contains("<em>Emphasis</em>"))
        assertTrue(html.contains("&lt;b&gt;literal&lt;/b&gt;"))
        assertTrue(html.contains("&amp; co."))
    }

    @Test fun footnoteInsideCodeIsNotAReference() {
        // A code span's content is literal — it must not sprout a footnote.
        val html = MarkdownHtml.document("Use `arr[^1]` here.\n\n[^1]: Note.", "t", dark = false)
        assertTrue(html.contains("<code>arr[^1]</code>"))
        assertFalse(html.contains("<code>arr<sup"))
    }

    @Test fun footnoteDefinitionAbsorbsWrappedLines() {
        val kinds = parse("X[^a].\n\n[^a]: first line\n  second line\n\nAfter.")
        val definition = kinds.drop(1).firstOrNull()
        assertTrue("expected a footnote definition", definition is MarkdownBlock.FootnoteDefinition)
        assertEquals("first line second line", (definition as MarkdownBlock.FootnoteDefinition).text)
        // The paragraph after the blank line is its own block, not swallowed.
        val after = kinds.lastOrNull()
        assertTrue("expected the trailing paragraph", after is MarkdownBlock.Paragraph)
        assertEquals("After.", (after as MarkdownBlock.Paragraph).text)
    }

    @Test fun footnoteReferenceCannotBreakOutIntoMarkup() {
        // The reference becomes markup full of quotes and angle brackets, so
        // it is converted *after* images and links. Converting it first let an
        // image carry that markup into an `alt` attribute, straight through
        // the quoting the inline pass relies on, and let a link wrap it in an
        // `<a>` inside another `<a>`. Writing a reference inside a link's own
        // label now simply stops that label being a link — the harmless
        // failure, and a nonsensical thing to write in the first place.
        val image = MarkdownHtml.document("![alt [^a] here](i.png)\n\n[^a]: N.", "t", dark = false)
        assertFalse("markup must never reach an attribute", image.contains("alt=\"alt <sup"))

        val link = MarkdownHtml.document("[see [^a] here](u)\n\n[^a]: N.", "t", dark = false)
        assertFalse("a label holding a reference is not a link", link.contains("<a href=\"u\">"))
        assertFalse("no anchor may nest inside another", link.contains("</a></a>"))

        // An ordinary link and image are untouched by the footnote pass.
        val plain = MarkdownHtml.document("[text](u) and ![a](i.png)", "t", dark = false)
        assertTrue(plain.contains("<a href=\"u\">text</a>"))
        assertTrue(plain.contains("<img src=\"i.png\" alt=\"a\">"))
    }

    @Test fun footnoteDefinitionDoesNotLeakIntoTheOutline() {
        // [parse] claims the definition line, so an underline beneath it is a
        // rule — not a setext heading. The outline must agree, or it lists a
        // heading that isn't there and every later anchor drifts.
        val md = "# Real\n\n[^a]: The note.\n---\n\nText[^a]."
        assertEquals(listOf("Real"), MarkdownParser.outline(md).map { it.text })
    }

    @Test fun footnoteReferenceInsideAFootnoteBecomesLiteralText() {
        // A reference inside a footnote's own text is rendered *after* the
        // numbering walk has scanned the body, so its placeholder never gets a
        // number. Cleaned back to what the author typed it reads as `[^b]`;
        // left alone it would be an empty `<sup>` and the words would simply
        // disappear from the page. This nesting is the only input that reaches
        // the final cleanup pass at all.
        val html = MarkdownHtml.document(
            "X[^a].\n\n[^a]: See [^b] as well.\n[^b]: Bee.", "t", dark = false,
        )
        assertTrue(html.contains("<li id=\"fn-1\">See [^b] as well."))
        // The uncited `b` is still printed, and no placeholder survives.
        assertTrue(html.contains("<li id=\"fn-2\">Bee."))
        assertFalse(html.contains("data-fn="))
    }

    @Test fun unicodeSpacingAndWordBoundariesAgreeAcrossPlatforms() {
        // trimSpaces() trims exactly Foundation's CharacterSet.whitespaces —
        // Unicode's Zs category plus tab — the set the Swift apps trim. These
        // are the inputs that used to diverge: padded with a non-breaking
        // space, a fence was metadata on Apple and an ordinary paragraph here,
        // so a document's front matter went missing on one platform of three.
        val nbsp = "\u00A0"
        assertEquals(
            listOf(MetadataField("title", "A")),
            MarkdownParser.frontMatter("---\ntitle: A\n---$nbsp\n\nbody"),
        )
        assertNotNull(MarkdownParser.parseFootnoteDefinition("$nbsp[^a]: note"))
        assertEquals("note", MarkdownParser.parseFootnoteDefinition("[^a]: note$nbsp")?.second)

        // The word-boundary guards are spelled `[\p{L}\p{N}_]`, not `\w`:
        // the desktop JVM reads `\w` as ASCII (so a Cyrillic letter would
        // count as a non-word character and prose would become a formula),
        // while Android's ICU-backed engine reads it as Unicode already but
        // REJECTS the `(?U)` flag that fixes the JVM — and `protect`/`replace`
        // swallow the resulting PatternSyntaxException, so the pattern just
        // stops matching. That cost single-dollar math and `_italics_` on
        // device while this suite stayed green.
        val guarded = MarkdownHtml.document("\u0444_em_\u0444 and \u0444${'$'}x${'$'}\u0444", "t", dark = false)
        assertFalse("an underscore between letters is not emphasis", guarded.contains("<em>"))
        assertFalse(
            "a dollar between letters is not a formula",
            guarded.contains("<span class=\"md-mathi\">"),
        )

        // …and the other half, which is what a negative assertion alone can
        // never tell you: the patterns must still MATCH. A guard that has
        // silently stopped working also passes every assertion above.
        val working = MarkdownHtml.document("an _em_ word and ${'$'}x${'$'} alone", "t", dark = false)
        assertTrue("an underscore around a word is still emphasis", working.contains("<em>em</em>"))
        assertTrue(
            "a dollar around a symbol is still a formula",
            working.contains("<span class=\"md-mathi\">"),
        )
    }

    @Test fun footnoteIdentifierCharacterSet() {
        assertNotNull(MarkdownParser.parseFootnoteDefinition("[^a-1_B]: ok"))
        // Non-ASCII is rejected, and must be: the renderer's reference pattern
        // is ASCII-only, so a Unicode identifier would be a definition that no
        // reference could ever name — parsed, found unreferenced, and printed
        // on its own at the foot of the page.
        assertNull(MarkdownParser.parseFootnoteDefinition("[^café]: no"))
        assertNull(MarkdownParser.parseFootnoteDefinition("[^сн]: no"))
        // A space or punctuation in the identifier is not a definition, so the
        // line stays ordinary text rather than becoming a note that no
        // reference could ever match.
        assertNull(MarkdownParser.parseFootnoteDefinition("[^two words]: no"))
        assertNull(MarkdownParser.parseFootnoteDefinition("[^a.b]: no"))
        assertNull(MarkdownParser.parseFootnoteDefinition("[^]: no"))
        assertNull(MarkdownParser.parseFootnoteDefinition("[a]: not a footnote"))
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

    @Test fun htmlGraphvizBlockEmitsContainer() {
        val html = MarkdownHtml.document("```dot\ndigraph { a -> b }\n```", "t", dark = false)
        assertTrue(html.contains("<div class=\"graphviz\" data-engine=\"dot\">"))
        assertTrue(html.contains("digraph { a -&gt; b }"))
        // A dot fence must NOT become an ordinary code block.
        assertFalse(html.contains("<pre><code>digraph"))
        // Graphviz *is* Viz.js — the engine md already bundles for PlantUML —
        // so a dot block adds no payload beyond the include PlantUML needs.
        assertTrue(html.contains("viz-global.js"))
        assertFalse(html.contains("mermaid.min.js"))
        assertFalse(html.contains("katex.min.js"))
    }

    @Test fun htmlGraphvizAliasesAndLayoutEngines() {
        // ```graphviz and ```gv are spellings of the default `dot` layout…
        for (alias in listOf("graphviz", "gv")) {
            val html = MarkdownHtml.document("```$alias\ngraph { a -- b }\n```", "t", dark = false)
            assertTrue("$alias selects the dot layout", html.contains("data-engine=\"dot\""))
        }
        // …while the layout programs name themselves, the way Graphviz is
        // invoked on a command line (`neato -Tsvg`). Every one of these must be
        // a name Viz.js accepts, or the render throws.
        for (engine in listOf("neato", "circo", "fdp", "sfdp", "twopi", "osage", "patchwork")) {
            val html = MarkdownHtml.document("```$engine\ngraph { a -- b }\n```", "t", dark = false)
            assertTrue("$engine selects its own layout", html.contains("data-engine=\"$engine\""))
        }
        // An unrelated language is not a diagram — it stays an ordinary code
        // block (now tagged for highlight.js, not a Graphviz container).
        val kotlin = MarkdownHtml.document("```kotlin\nval x = 1\n```", "t", dark = false)
        assertTrue(kotlin.contains("<pre><code class=\"language-kotlin\">val x = 1"))
        assertFalse(kotlin.contains("class=\"graphviz\""))
        assertFalse(kotlin.contains("viz-global.js"))
    }

    @Test fun htmlGraphvizEscapesAngleBrackets() {
        // DOT's HTML-like labels are full of `<`/`>`. They must be escaped into
        // the container (md-init.js reads the decoded textContent back out), or
        // the label markup would be parsed as page markup.
        val html = MarkdownHtml.document(
            "```dot\ndigraph { n [label=<<b>hi</b>>] }\n```", "t", dark = false,
        )
        assertTrue(html.contains("&lt;&lt;b&gt;hi&lt;/b&gt;&gt;"))
        assertFalse(html.contains("<b>hi</b>"))
    }

    @Test fun htmlGraphvizInkRecolorIsScopedToTheEnginesOwnBlack() {
        // The ink recolor must not overwrite an author's `fontcolor` / `color`:
        // Graphviz writes those out as the same presentation attributes, so each
        // rule is scoped to what the engine emits when nothing was asked for —
        // text with no fill of its own, and explicit black. An unscoped
        // `.graphviz svg text { fill: … }` would silently discard the author's.
        val html = MarkdownHtml.document("```dot\ndigraph { a }\n```", "t", dark = true)
        assertTrue(html.contains(".graphviz svg text:not([fill]) { fill: #E7DBC2; }"))
        assertTrue(html.contains(".graphviz svg text[fill=\"black\"] { fill: #E7DBC2; }"))
        assertTrue(html.contains(".graphviz svg [stroke=\"black\"] { stroke: #E7DBC2; }"))
        assertTrue(html.contains(".graphviz svg [fill=\"black\"]:not(text) { fill: #E7DBC2; }"))
        // The old blanket rule is gone.
        assertFalse(html.contains(".graphviz svg text { fill:"))
        assertFalse(html.contains(".graphviz svg [fill=\"black\"] { fill:"))
    }

    // CSV / TSV blocks

    @Test fun csvFenceRendersAsATable() {
        val html = MarkdownHtml.document("```csv\nName,Role\nAnn,Editor\n```", "t", dark = false)
        assertTrue(html.contains("<th style=\"text-align:left\">Name</th>"))
        assertTrue(html.contains("<td style=\"text-align:left\">Ann</td>"))
        assertFalse(html.contains("<pre><code>Name,Role"))
    }

    @Test fun delimitedParsingFollowsRFC4180() {
        val rows = MarkdownHtml.parseDelimited(
            "Name,Note\n\"Alekseev, Ivan\",\"She said \"\"hi\"\"\"\nAnn,\n", ',',
        )
        assertEquals(
            listOf(
                listOf("Name", "Note"),
                listOf("Alekseev, Ivan", "She said \"hi\""),  // quoted separator, doubled quote
                listOf("Ann", ""),                            // an empty trailing field
            ),
            rows,
        )

        // A quoted field may hold a line break…
        assertEquals(
            listOf(listOf("a", "one\ntwo")),
            MarkdownHtml.parseDelimited("a,\"one\ntwo\"\n", ','),
        )
        // …and a quote that is not at the start of a field is just a character.
        assertEquals(
            listOf(listOf("5\" pipe", "x")),
            MarkdownHtml.parseDelimited("5\" pipe,x", ','),
        )
        // A last row with no trailing newline is still a row.
        assertEquals(listOf(listOf("a", "b")), MarkdownHtml.parseDelimited("a,b", ','))
        assertEquals(emptyList<List<String>>(), MarkdownHtml.parseDelimited("", ','))
    }

    @Test fun delimitedParsingHandlesWindowsLineEndings() {
        // A spreadsheet export is exactly where CRLF comes from. Matching "\n"
        // alone would leave the CR on the end of every row's last field, and a
        // lone CR (classic Mac) would not break a row at all — so the endings
        // are normalised before the scan, not matched during it.
        assertEquals(
            listOf(listOf("a", "b"), listOf("c", "d")),
            MarkdownHtml.parseDelimited("a,b\r\nc,d\r\n", ','),
        )
        assertEquals(
            listOf(listOf("a", "b"), listOf("c", "d")),
            MarkdownHtml.parseDelimited("a,b\rc,d", ','),
        )
    }

    @Test fun csvNumericColumnsAreRightAligned() {
        // Decimal points lining up is most of what makes figures readable.
        val html = MarkdownHtml.document(
            "```csv\nCity,People\nOslo,709037\nBergen,289330\n```", "t", dark = false,
        )
        assertTrue(html.contains("<th style=\"text-align:left\">City</th>"))
        assertTrue(html.contains("<th style=\"text-align:right\">People</th>"))
        assertTrue(html.contains("<td style=\"text-align:right\">709037</td>"))
        // A column with any non-numeric value stays left-aligned.
        val mixed = MarkdownHtml.document("```csv\nA\n1\nn/a\n```", "t", dark = false)
        assertTrue(mixed.contains("<th style=\"text-align:left\">A</th>"))
        // Including the JVM's own extras, which the Apple siblings' `Double(_:)`
        // rejects. A trailing `f`/`d` type suffix parses there, so an ordinary
        // column of "2D" / "3D" would be right-aligned here and left-aligned
        // on iOS…
        val dimensions = MarkdownHtml.document("```csv\nMode\n2D\n3D\n```", "t", dark = false)
        assertTrue(dimensions.contains("<th style=\"text-align:left\">Mode</th>"))
        // …and so does anything padded with characters up to U+0020, which is
        // how a cell holding the line break a quoted field may contain would
        // otherwise sneak in as a number.
        val wrapped = MarkdownHtml.document("```csv\nN\n\"\n6\"\n```", "t", dark = false)
        assertTrue(wrapped.contains("<th style=\"text-align:left\">N</th>"))

        // A cell is trimmed of spaces and tabs before any of that (Foundation's
        // `.whitespaces`, which is what the Swift trims with): " 5 " is a number,
        // and a cell of nothing but spaces is empty — skipped, rather than
        // counted against the column.
        val padded = MarkdownHtml.document("```csv\nN\n 5 \n \n7\n```", "t", dark = false)
        assertTrue(padded.contains("<th style=\"text-align:right\">N</th>"))
        // A column with no filled cell at all has nothing to line up, so it
        // stays left-aligned rather than vacuously right.
        val blank = MarkdownHtml.document("```csv\nA,B\n1,\n2,\n```", "t", dark = false)
        assertTrue(blank.contains("<th style=\"text-align:right\">A</th>"))
        assertTrue(blank.contains("<th style=\"text-align:left\">B</th>"))
    }

    @Test fun decimalNumberGrammarIsExplicitNotToDoubleOrNull() {
        // What counts as a number decides column alignment, and it must mean
        // the same thing on every platform. Neither language's Double parser
        // does: Swift takes hex and any casing of inf/nan, Java takes a
        // trailing f/d suffix and only the exact Infinity/NaN — so the same
        // spreadsheet aligned differently on the two.
        for (number in listOf("0", "-1", "+2", "3.5", ".5", "5.", "1e9", "-2.5E-3", "007")) {
            assertTrue("$number is a figure", MarkdownHtml.isDecimalNumber(number))
        }
        for (other in listOf(
            "0x10", "inf", "Inf", "INF", "nan", "NaN", "Infinity", "2D", "1f",
            "1,000", "1 000", "", "-", ".", "e5", "1e", "1e+", "12abc", "1.2.3",
        )) {
            assertFalse("$other is not a figure", MarkdownHtml.isDecimalNumber(other))
        }

        // A hex column stays left-aligned — the same on both platforms.
        val hex = MarkdownHtml.document("```csv\nItem,Value\na,0x10\n```", "t", dark = false)
        assertTrue(hex.contains("<th style=\"text-align:left\">Value</th>"))

        // And an invisible U+200B is not padding: Foundation strips it and a
        // Zs-based trim does not, so the scan trims ASCII only and both agree.
        val zwsp = MarkdownHtml.document(
            "```csv\nItem,Value\na,\u200B1\u200B\n```", "t", dark = false,
        )
        assertTrue(zwsp.contains("<th style=\"text-align:left\">Value</th>"))
    }

    @Test fun tsvFenceUsesTabs() {
        val html = MarkdownHtml.document("```tsv\nCity\tPeople\nOslo\t709037\n```", "t", dark = false)
        assertTrue(html.contains("<th style=\"text-align:left\">City</th>"))
        assertTrue(html.contains("<td style=\"text-align:right\">709037</td>"))
        // A comma in a TSV cell is data, not a separator.
        val commas = MarkdownHtml.parseDelimited("a,b\tc\n", '\t')
        assertEquals(listOf(listOf("a,b", "c")), commas)
    }

    @Test fun emptyCsvFenceStaysACodeBlock() {
        // Nothing the author wrote may disappear because it failed to parse.
        val html = MarkdownHtml.document("```csv\n\n```", "t", dark = false)
        assertFalse(html.contains("<table>"))
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
        assertFalse(html.contains("highlight.min.js"))
        assertTrue(html.contains("rich/md-init.js"))
    }

    @Test fun htmlCodeSpanDollarIsNotMath() {
        val html = MarkdownHtml.document("use `\$x\$` here", "t", dark = false)
        assertTrue(html.contains("<code>\$x\$</code>"))
    }

    // mhchem — chemistry notation ($\ce{…}$), a KaTeX extension on the math gate

    @Test fun htmlMhchemLoadsWithAndAfterKatex() {
        // mhchem is a KaTeX extension gated by the same `needsMath` signal, so
        // it must appear exactly when — and only when — KaTeX does, and it must
        // come *after* KaTeX in the document (KaTeX defines the global `katex`;
        // mhchem registers `\ce{}` onto it). The actual `\ce{}` render is proven
        // in a real WebView; here we only assert the include is present and
        // ordered. `\ce{}` lives inside math delimiters, so a plain formula is
        // enough to pull it in.
        val math = MarkdownHtml.document("Reaction \$\\ce{H2O}\$ here", "t", dark = false)
        assertTrue(math.contains("katex.min.js"))
        assertTrue(math.contains("mhchem.min.js"))
        val katexAt = math.indexOf("katex.min.js")
        val mhchemAt = math.indexOf("mhchem.min.js")
        assertTrue(
            "mhchem must load after KaTeX so the global `katex` exists",
            katexAt in 0 until mhchemAt,
        )
        // mhchem shares KaTeX's `defer`, or it would run before the deferred
        // KaTeX and find no global to extend.
        assertTrue(math.contains("<script defer src=\"rich/mhchem.min.js\"></script>"))
    }

    @Test fun htmlMhchemAbsentWithoutMath() {
        // No math → no KaTeX → no mhchem either, for every non-math document.
        val plain = MarkdownHtml.document("# Just text\n\nA paragraph.", "t", dark = false)
        assertFalse(plain.contains("mhchem.min.js"))
        val mermaid = MarkdownHtml.document("```mermaid\ngraph TD\nA-->B\n```", "t", dark = false)
        assertFalse(mermaid.contains("mhchem.min.js"))
        // A currency dollar sign is prose, not a formula — no engines at all.
        val currency = MarkdownHtml.document("it costs \$5 and \$10 today", "t", dark = false)
        assertFalse(currency.contains("mhchem.min.js"))
    }

    // Syntax highlighting — highlight.js on ```lang code blocks

    @Test fun htmlCodeBlockWithLanguageIsTaggedAndPullsHighlightJs() {
        // A fenced block with a real code language is tagged `language-<lang>`
        // (which md-init.js hands to hljs.highlightElement in the WebView), and
        // that is the signal that pulls the highlight.js engine in. The content
        // is HTML-escaped into the block; hljs reads the decoded textContent.
        val html = MarkdownHtml.document("```swift\nlet x = 1 < 2\n```", "t", dark = false)
        assertTrue(html.contains("<pre><code class=\"language-swift\">"))
        assertTrue(html.contains("let x = 1 &lt; 2"))
        assertTrue(html.contains("rich/highlight.min.js"))
        // It is its own engine, not a diagram or a formula.
        assertFalse(html.contains("class=\"md-math"))
        assertFalse(html.contains("mermaid.min.js"))
        assertFalse(html.contains("katex.min.js"))
    }

    @Test fun htmlBareCodeFenceIsNotHighlighted() {
        // A fence with no language has nothing to key highlighting off, so it
        // stays a plain code block and the engine is never loaded — letting hljs
        // guess a language would colour shell transcripts and prose nobody
        // marked as code.
        val html = MarkdownHtml.document("```\nplain text\n```", "t", dark = false)
        assertTrue(html.contains("<pre><code>plain text</code></pre>"))
        assertFalse(html.contains("class=\"language-"))
        assertFalse(html.contains("highlight.min.js"))
    }

    @Test fun htmlDiagramMathAndDataFencesAreNotHighlighted() {
        // The languages with their own handling must never be tagged for
        // highlight.js: they are diagrams, formulas or tables, not code, and a
        // `language-…` class on them would load an engine with nothing to do.
        for (fence in listOf(
            "```mermaid\ngraph TD\nA-->B\n```",
            "```dot\ndigraph { a -> b }\n```",
            "```plantuml\n@startuml\nA->B\n@enduml\n```",
            "```math\n\\int_0^1 x\\,dx\n```",
            "```csv\nName,Role\nAnn,Editor\n```",
        )) {
            val html = MarkdownHtml.document(fence, "t", dark = false)
            assertFalse("`$fence` must not be tagged for highlighting", html.contains("class=\"language-"))
            assertFalse("`$fence` must not load highlight.js", html.contains("highlight.min.js"))
        }
    }

    @Test fun htmlHighlightThemeIsTheAppsOwnPalette() {
        // The theme is hand-written in the paper palette, not a bundled stock
        // highlight.js theme — keywords in the accent, comments muted + italic,
        // strings a shade of the ink — and it tracks light / dark like the rest
        // of the CSS. Like the .mermaid / .graphviz rules, this small static
        // theme rides in every document's <style>; it is the engine <script>,
        // not the CSS, that is gated on the document actually highlighting.
        val dark = MarkdownHtml.document("```swift\nlet x = 1\n```", "t", dark = true)
        assertTrue(dark.contains(".hljs-comment, .hljs-quote { color: #B3A98E; font-style: italic; }"))
        assertTrue(dark.contains(".hljs-string, .hljs-regexp { color: #B79A67; }"))
        // The keyword group ends on the accent — asserted against its own rule,
        // not a bare `color: #C99A55`, which `a { … }` would satisfy anyway.
        assertTrue(dark.contains(".hljs-name, .hljs-section, .hljs-doctag { color: #C99A55; }"))
        val light = MarkdownHtml.document("```swift\nlet x = 1\n```", "t", dark = false)
        assertTrue(light.contains(".hljs-comment, .hljs-quote { color: #6B635A; font-style: italic; }"))
        assertTrue(light.contains(".hljs-string, .hljs-regexp { color: #6A5433; }"))
        assertTrue(light.contains(".hljs-name, .hljs-section, .hljs-doctag { color: #9C6B2E; }"))
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

    @Test fun exportPageIsPlainWhiteAndAlwaysLight() {
        // Print / PDF pages keep their own single color: no paper tint
        // (which would end mid-page next to the white A4 margins), and the
        // light palette even from a dark device — dark cream-on-carbon is
        // a screen theme, unreadable as cream-on-white.
        val export = MarkdownHtml.document("hello", "t", dark = true, export = true)
        assertTrue("plain white page", export.contains("background: #FFFFFF"))
        assertTrue("always light", export.contains("color-scheme: light"))
        assertFalse("no dark paper in an export", export.contains("#241E18"))
        assertTrue("Mermaid sees light mode too", export.contains("data-md-dark=\"0\""))
        // The on-screen preview still honors the device's appearance.
        val preview = MarkdownHtml.document("hello", "t", dark = true)
        assertTrue(preview.contains("background: #241E18"))
        assertTrue(preview.contains("data-md-dark=\"1\""))
    }

    // Parser — scalar-exact block delimiters (regression — fifth review)
    //
    // The Apple sibling matched block delimiters against extended grapheme
    // clusters, so a combining mark, a variation selector or a ZWJ written
    // after a delimiter fused onto it and the block was silently not
    // recognised. This port compares UTF-16 units and was already right, so
    // every case below is a *pin*: it states the behaviour Swift has now
    // been brought to, and it fails here if this port ever drifts. A
    // differential run of the two ports over 3,555 marked documents put the
    // divergence at 465 records before that change and 0 after.
    //
    // The three that did change here are marked: two whitespace sets that
    // were not the ones Foundation trims with, and the ordered-list digits.

    private val marks = listOf("\u0301", "\uFE0F", "\u200D")

    @Test fun listMarkerSurvivesAMarkOnItsSpace() {
        for (mark in marks) {
            val b = parse("- $mark[draft]").first()
            assertTrue("a marked marker space must still start a list", b is MarkdownBlock.ListBlock)
            b as MarkdownBlock.ListBlock
            assertFalse(b.ordered)
            assertEquals(listOf("$mark[draft]"), b.items.map { it.text })
        }
    }

    @Test fun fenceSurvivesAMarkOnItsOpeningRun() {
        for (mark in marks) {
            val b = parse("```${mark}js\ncode()\n```").first()
            assertTrue(b is MarkdownBlock.CodeBlock)
            b as MarkdownBlock.CodeBlock
            assertEquals("code()", b.code)
            assertEquals("${mark}js", b.language)
        }
    }

    @Test fun tableRowSurvivesAMarkOnItsOnlyPipe() {
        for (mark in marks) {
            val blocks = parse("a | b\n--- | ---\n1 |$mark 2")
            assertEquals("the row must not also become a paragraph", 1, blocks.size)
            val b = blocks.first()
            assertTrue(b is MarkdownBlock.Table)
            b as MarkdownBlock.Table
            assertEquals(listOf(listOf("1", "$mark 2")), b.rows)
        }
    }

    @Test fun footnoteDefinitionSurvivesAMarkOnItsColon() {
        for (mark in marks) {
            val b = parse("[^a]:$mark the note").first()
            assertTrue(b is MarkdownBlock.FootnoteDefinition)
            b as MarkdownBlock.FootnoteDefinition
            assertEquals("a", b.id)
            assertEquals("$mark the note", b.text)
        }
    }

    @Test fun quoteMarkerSurvivesAMarkAfterIt() {
        for (mark in marks) {
            val b = parse(">$mark quoted").first()
            assertTrue(b is MarkdownBlock.Quote)
            b as MarkdownBlock.Quote
            assertEquals("$mark quoted", (b.blocks.first() as MarkdownBlock.Paragraph).text)

            val stripped = parse("> ${mark}text").first() as MarkdownBlock.Quote
            assertEquals("${mark}text", (stripped.blocks.first() as MarkdownBlock.Paragraph).text)
        }
    }

    @Test fun headingSurvivesAMarkOnItsMarkerSpace() {
        for (mark in marks) {
            val b = parse("# ${mark}Heading").first()
            assertTrue(b is MarkdownBlock.Heading)
            b as MarkdownBlock.Heading
            assertEquals(1, b.level)
            assertEquals("${mark}Heading", b.text)
            assertEquals(listOf("${mark}Heading"),
                MarkdownParser.outline("# ${mark}Heading").map { it.text })
        }
    }

    @Test fun commentEndSurvivesAMarkAfterIt() {
        for (mark in marks) {
            val blocks = parse("<!-- note: private -->$mark\n\nafter")
            assertEquals("the comment must end where it ends", 2, blocks.size)
            assertEquals("private", (blocks[0] as MarkdownBlock.Note).text)
            assertEquals("after", (blocks[1] as MarkdownBlock.Paragraph).text)
            assertEquals(listOf("After"),
                MarkdownParser.outline("<!-- x -->$mark\n\n# After").map { it.text })
            assertEquals(listOf("n"),
                MarkdownParser.notes("<!-- note: n -->$mark").map { it.text })
            assertEquals(1, parse("<!--$mark hidden -->\n\nafter").size)
        }
    }

    @Test fun frontMatterFieldSurvivesAMarkOnItsSeparator() {
        for (mark in marks) {
            val b = parse("---\ntitle: T\nauthor:$mark A\n---\n\nbody").first()
            assertTrue(b is MarkdownBlock.FrontMatter)
            b as MarkdownBlock.FrontMatter
            assertEquals(listOf("title", "author"), b.fields.map { it.key })
            assertEquals(listOf("T", "$mark A"), b.fields.map { it.value })
        }
        val quoted = parse("---\ntitle: \"\u0301Q\"\n---\n\nbody").first() as MarkdownBlock.FrontMatter
        assertEquals(listOf("\u0301Q"), quoted.fields.map { it.value })
    }

    @Test fun taskBoxIsScalarExact() {
        for (mark in marks) {
            val b = parse("- [ ] ${mark}task").first() as MarkdownBlock.ListBlock
            assertEquals(listOf(false), b.items.map { it.task })
            assertEquals(listOf("${mark}task"), b.items.map { it.text })
        }
    }

    @Test fun orderedListMarkerIsAsciiDigitsOnly() {
        // CHANGED HERE. `Char.isDigit()` admits every decimal script and
        // `toIntOrNull` reads ٣ as 3, while Swift's `Int(_:)` reads it as
        // nothing and fell back to 1 — the same list, numbered differently
        // on the two platforms. CommonMark says ASCII digits.
        assertTrue("½ is not an ordered-list marker",
            parse("½. half").first() is MarkdownBlock.Paragraph)
        assertTrue("٣ is not an ordered-list marker",
            parse("\u0663. three").first() is MarkdownBlock.Paragraph)
        for (mark in marks) {
            val b = parse("1. one\n2$mark. two").first() as MarkdownBlock.ListBlock
            assertEquals(listOf("one 2$mark. two"), b.items.map { it.text })
        }
        val plain = parse("1. one\n2. two").first() as MarkdownBlock.ListBlock
        assertTrue(plain.ordered)
        assertEquals(listOf(1, 2), plain.items.map { it.ordinal })
    }

    @Test fun slugKeepsWhatTheApplePortKeeps() {
        val used = HashMap<String, Int>()
        assertEquals("a--\u0301b-c", MarkdownParser.slug("a -\u0301b c", used))
        used.clear()
        assertEquals("heading", MarkdownParser.slug("Heading\u200D", used))
        used.clear()
        assertEquals("cafe\u0301", MarkdownParser.slug("Cafe\u0301", used))
        used.clear()
        assertEquals("getting-started", MarkdownParser.slug("Getting Started", used))
        assertEquals("getting-started-1", MarkdownParser.slug("Getting Started", used))
    }

    @Test fun blankLineSetIsFoundationsOwn() {
        // CHANGED HERE. U+200B is a space separator to Apple's (frozen)
        // Unicode tables and a format character to this JVM's current ones,
        // and it is the only character the two sets disagree about. A line
        // holding one was a blank line on Apple and a paragraph here.
        assertEquals("a zero-width space alone is a blank line",
            2, parse("para\n\u200B\nnext").size)
        assertEquals(2, parse("para\n \t\u00A0\nnext").size)
    }

    @Test fun noteAndFenceTrimsAreFoundationsOwn() {
        // CHANGED HERE, both of them. `trim()` is Kotlin's whitespace set,
        // not Foundation's: it keeps U+0085 and eats U+001C…U+001F, so a
        // note marker preceded by U+0085 was a note on Apple and nothing
        // here. And a fence info string trimmed with only ' ' and '\t'
        // kept a non-breaking space Apple dropped — worse on the closing
        // fence, which then did not close and swallowed the rest of the
        // document into the code block.
        assertEquals(listOf("n"), MarkdownParser.notes("<!--\u0085 note: n -->").map { it.text })
        assertEquals(listOf("n"), MarkdownParser.notes("<!-- note: n \u0085-->").map { it.text })
        val fence = parse("```\u00A0js\ncode()\n```").first() as MarkdownBlock.CodeBlock
        assertEquals("js", fence.language)
        assertEquals("code()", fence.code)
        val closed = parse("```\ncode()\n```\u00A0\n\nafter")
        assertEquals("the fence closes, so the text after it is its own block", 2, closed.size)
    }

    @Test fun lineEndingsAreScalarExact() {
        val b = parse("one\r\ntwo\rthree\nfour").first() as MarkdownBlock.Paragraph
        assertEquals("one\ntwo\nthree\nfour", b.text)
        assertEquals(2, parse("a\r\n\r\nb").size)
        assertEquals(listOf(0, 2), MarkdownParser.outline("# One\r\n\r\n# Two").map { it.line })
    }
}
