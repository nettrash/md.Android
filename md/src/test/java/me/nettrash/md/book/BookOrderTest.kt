/*
 * BookOrderTest.kt
 * md (Android)
 *
 * JVM unit tests for the pure, DocumentFile-free parts of the book model
 * (`book/Book.kt`): which file names count as articles, how the display
 * title is derived, the numbered-first natural book ordering that drives
 * the navigator's listing — matching the iOS/macOS apps' Finder-style
 * `localizedStandardCompare` order — and the management planning behind
 * Rename… / Move Up / Move Down (prefix splitting, rename targets, and
 * the renumbering rename plan) and the whole-book compilation behind
 * Share / Export as PDF (compileBook); the SAF I/O that applies them
 * lives in BookState and is exercised by hand.
 */

package me.nettrash.md.book

import me.nettrash.md.markdown.MarkdownBlock
import me.nettrash.md.markdown.MarkdownParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BookOrderTest {

    // isArticleName — the three article extensions, any case, nothing else

    @Test fun markdownAndTextFilesAreArticles() {
        assertTrue(isArticleName("intro.md"))
        assertTrue(isArticleName("intro.markdown"))
        assertTrue(isArticleName("notes.txt"))
        assertTrue(isArticleName("SHOUTY.MD"))
    }

    @Test fun otherFilesAreNot() {
        assertFalse(isArticleName("cover.png"))
        assertFalse(isArticleName("draft.md.bak"))
        assertFalse(isArticleName("no-extension"))
        assertFalse(isArticleName(null))
    }

    // articleTitle — the file name without its extension

    @Test fun titleDropsTheExtensionOnly() {
        assertEquals("02-the-storm", articleTitle("02-the-storm.md"))
        assertEquals("chapter.one", articleTitle("chapter.one.md"))
    }

    // leadingNumber — the numeric prefix writers use to order drafts

    @Test fun leadingDigitsParse() {
        assertEquals(1L, leadingNumber("01-intro"))
        assertEquals(2L, leadingNumber("2. setup"))
        assertEquals(10L, leadingNumber("10"))
    }

    @Test fun unnumberedNamesDoNot() {
        assertNull(leadingNumber("appendix"))
        assertNull(leadingNumber("-3 degrees"))       // sign is not a digit
        assertNull(leadingNumber(""))
        // A digit run too long for a Long counts as unnumbered, not a crash.
        assertNull(leadingNumber("99999999999999999999-overflow"))
        // Only ASCII '0'..'9' count — Swift's numbering on iOS/macOS ignores
        // other Unicode digits (here: Arabic-Indic three).
        assertNull(leadingNumber("٣-arabic"))
    }

    // naturalCompare — Finder-style order, the localizedStandardCompare stand-in

    @Test fun embeddedNumbersCompareNumerically() {
        assertTrue(naturalCompare("part2", "part10") < 0)
        assertTrue(naturalCompare("part10", "part2") > 0)
        assertTrue(naturalCompare("v1.9", "v1.10") < 0)
    }

    @Test fun naturalCompareIsCaseInsensitiveWithDeterministicTies() {
        assertTrue(naturalCompare("Apple", "banana") < 0)
        assertEquals(0, naturalCompare("draft", "draft"))
        // Same value, different padding / case: ordered, never "equal".
        assertTrue(naturalCompare("part01", "part1") != 0)
        assertTrue(naturalCompare("Draft", "draft") != 0)
    }

    // bookNameOrder — numbered first (numerically), then natural order

    @Test fun numbersSortNumericallyNotLexicographically() {
        val sorted = listOf("10-deploy", "01-intro", "2. setup").sortedWith(bookNameOrder)
        assertEquals(listOf("01-intro", "2. setup", "10-deploy"), sorted)
    }

    @Test fun numberedComeBeforeUnnumbered() {
        val sorted = listOf("appendix", "01-intro", "Zebra", "2-body").sortedWith(bookNameOrder)
        assertEquals(listOf("01-intro", "2-body", "appendix", "Zebra"), sorted)
    }

    @Test fun unnumberedSortNaturally() {
        val sorted = listOf("banana", "Apple", "cherry").sortedWith(bookNameOrder)
        assertEquals(listOf("Apple", "banana", "cherry"), sorted)
        // The Finder rule reaches embedded numbers too: part2 before part10.
        val parts = listOf("part10", "part2", "epilogue").sortedWith(bookNameOrder)
        assertEquals(listOf("epilogue", "part2", "part10"), parts)
    }

    @Test fun equalNumbersFallBackToNaturalOrder() {
        val sorted = listOf("1-b", "01-a").sortedWith(bookNameOrder)
        assertEquals(listOf("01-a", "1-b"), sorted)
    }

    // orderPrefix — the leading digits plus their separator, or nothing

    @Test fun numberedNamesHaveAPrefix() {
        assertEquals("01-", orderPrefix("01-intro"))
        assertEquals("2. ", orderPrefix("2. setup"))
        assertEquals("3_", orderPrefix("3_draft"))
    }

    @Test fun nonOrderingDigitsAreNotAPrefix() {
        assertEquals("", orderPrefix("appendix"))
        assertEquals("", orderPrefix("3rd party"))   // digits run into letters
        assertEquals("", orderPrefix("2026"))        // the digits ARE the name
        assertEquals("", orderPrefix("01-"))         // nothing would remain
        assertEquals("", orderPrefix(""))
    }

    // editableBookName — what the Rename… dialog pre-fills

    @Test fun editableNameDropsPrefixAndExtension() {
        assertEquals("The Storm", editableBookName("02-The Storm.md"))
        assertEquals("notes", editableBookName("notes.txt"))
        assertEquals("Getting Started", editableBookName("02-Getting Started"))
        assertEquals("chapter.one", editableBookName("01-chapter.one.md"))
        // A chapter folder is no article: its dot is part of the name.
        assertEquals("v1.2", editableBookName("v1.2"))
    }

    // renamedBookName — Rename… keeps the ordering prefix and extension

    @Test fun renameKeepsPrefixAndExtension() {
        assertEquals("02-New.md", renamedBookName("02-Old.md", "New"))
        assertEquals("ideas.txt", renamedBookName("notes.txt", "ideas"))
        assertEquals("03-Finale", renamedBookName("03-Part", "Finale"))
        assertEquals("Ideas", renamedBookName("Drafts", "Ideas"))
    }

    // renumberedBookName — a zero-padded prefix, display name untouched

    @Test fun renumberingReplacesOrAddsThePrefix() {
        assertEquals("03-intro.md", renumberedBookName("intro.md", 3))
        assertEquals("02-deploy.md", renumberedBookName("10-deploy.md", 2))
        assertEquals("01-setup", renumberedBookName("2. setup", 1))
        // Three digits still work; the padding is a minimum, not a cap.
        assertEquals("100-late.md", renumberedBookName("late.md", 100))
    }

    // isValidBookName — what the name dialog will accept

    @Test fun namesWithSeparatorsAreRejected() {
        assertTrue(isValidBookName("Chapter One"))
        assertFalse(isValidBookName(""))
        assertFalse(isValidBookName("   "))
        assertFalse(isValidBookName("a/b"))
        assertFalse(isValidBookName("a:b"))
    }

    // planMove — swap with a neighbor, then materialize the whole order

    @Test fun moveUpSwapsAndRenumbersOnlyWhatChanged() {
        val plan = planMove(listOf("01-a.md", "02-b.md", "03-c.md"), from = 2, to = 1)
        // "01-a.md" is already right and isn't renamed.
        assertEquals(
            listOf("03-c.md" to "02-c.md", "02-b.md" to "03-b.md"),
            plan,
        )
    }

    @Test fun unprefixedSiblingsGainPrefixes() {
        val plan = planMove(listOf("alpha.md", "beta.md"), from = 1, to = 0)
        assertEquals(
            listOf("beta.md" to "01-beta.md", "alpha.md" to "02-alpha.md"),
            plan,
        )
    }

    @Test fun renumberingNormalizesLooseSeparators() {
        val plan = planMove(listOf("1. a.md", "02-b.md"), from = 0, to = 1)
        assertEquals(
            listOf("02-b.md" to "01-b.md", "1. a.md" to "02-a.md"),
            plan,
        )
    }

    @Test fun impossibleMovesPlanNothing() {
        assertTrue(planMove(listOf("01-a.md", "02-b.md"), from = 1, to = 1).isEmpty())
        assertTrue(planMove(listOf("01-a.md", "02-b.md"), from = 0, to = -1).isEmpty())
        assertTrue(planMove(listOf("01-a.md", "02-b.md"), from = 1, to = 2).isEmpty())
        assertTrue(planMove(emptyList(), from = 0, to = 1).isEmpty())
    }

    @Test fun chapterMovesRenumberFolderNames() {
        // Chapters have no extension; a dotted folder name stays whole.
        val plan = planMove(listOf("01-Basics", "v1.2"), from = 1, to = 0)
        assertEquals(
            listOf("v1.2" to "01-v1.2", "01-Basics" to "02-Basics"),
            plan,
        )
    }

    // compileBook — the whole book as one Markdown document

    @Test fun compiledBookOpensWithTheTitlePage() {
        val compiled = compileBook("My Book", listOf("# Intro\n\nHello."), emptyList())
        assertTrue(compiled.startsWith("# My Book\n\n\\newpage\n\n"))
    }

    @Test fun rootArticlesComeBeforeChaptersAndEveryUnitGetsItsOwnPage() {
        val compiled = compileBook(
            "Book",
            rootArticles = listOf("root article"),
            chapters = listOf("Chapter One" to listOf("first", "second")),
        )
        assertEquals(
            "# Book" +
                "\n\n\\newpage\n\nroot article" +
                "\n\n\\newpage\n\n# Chapter One" +
                "\n\n\\newpage\n\nfirst" +
                "\n\n\\newpage\n\nsecond",
            compiled,
        )
    }

    @Test fun chapterHeadingsSitOnTheirOwnPage() {
        val compiled = compileBook("B", emptyList(), listOf("Setup" to listOf("body")))
        // A page break on both sides of the heading: nothing shares its page.
        assertTrue(compiled.contains("\\newpage\n\n# Setup\n\n\\newpage"))
    }

    @Test fun emptyBookIsJustTheTitlePage() {
        assertEquals("# Empty", compileBook("Empty", emptyList(), emptyList()))
    }

    @Test fun articleContentIsVerbatim() {
        // `---` stays an ordinary thematic break; notes and the author's own
        // page breaks pass through untouched.
        val article = "line one\n\n---\n\n<!-- note: private -->\n\n\\newpage\n\nline two"
        val compiled = compileBook("B", listOf(article), emptyList())
        assertTrue(compiled.contains(article))
    }

    @Test fun compiledBookParsesWithItsPageBreaks() {
        val compiled = compileBook(
            "Book",
            rootArticles = listOf("# A\n\ntext"),
            chapters = listOf("Ch" to listOf("body")),
        )
        // Four units — title, article, heading, body — three separators.
        val breaks = MarkdownParser.parse(compiled)
            .filterIsInstance<MarkdownBlock.PageBreak>()
        assertEquals(3, breaks.size)
    }
}
