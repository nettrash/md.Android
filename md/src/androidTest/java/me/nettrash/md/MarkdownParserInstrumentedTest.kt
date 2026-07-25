/*
 * MarkdownParserInstrumentedTest.kt
 * md (Android)
 *
 * The block parser is pure string work and [MarkdownParserTest] covers it
 * densely — but it covers it on the desktop JVM, and the parser now asks
 * questions the JVM does not answer for the device:
 *
 *  1. **`Character.getType`.** Which characters are `Zs` space separators
 *     decides what a blank line is, what a fence's info string is trimmed
 *     to, and whether a closing fence closes. The desktop JVM and ART carry
 *     their own Unicode tables, and Apple's `CharacterSet.whitespaces` is a
 *     third, frozen one — the whole point of `trimSpaces` is that all three
 *     agree, so it has to be checked where the app actually runs.
 *
 *  2. **`Char.isDigit()` / `String.toIntOrNull`.** An ordered-list marker is
 *     ASCII digits only, precisely so the ordinal cannot depend on a table:
 *     `toIntOrNull` reads ٣ as 3 through `Character.digit`, and Swift's
 *     `Int(_:)` reads it as nothing. Pinned here on the device too.
 *
 * The grapheme-cluster cases the Apple port had to be fixed for are *not*
 * repeated here: Kotlin compares UTF-16 units, which no platform table can
 * change. They live in [MarkdownParserTest].
 *
 * Run with:
 *   ./gradlew :md:connectedDebugAndroidTest
 */

package me.nettrash.md

import androidx.test.ext.junit.runners.AndroidJUnit4
import me.nettrash.md.markdown.MarkdownBlock
import me.nettrash.md.markdown.MarkdownParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MarkdownParserInstrumentedTest {

    @Test fun blankLineSetMatchesFoundationsOnTheDevice() {
        // Every character in Foundation's `CharacterSet.whitespaces`, one
        // per line: each must be a blank line here too, on the device's own
        // Unicode tables. U+200B is the interesting one — a space separator
        // to Apple and a format character to Java, so `trimSpaces` names it
        // outright.
        val spaces = listOf(
            '\u0009', '\u0020', '\u00A0', '\u1680',
            '\u2000', '\u2001', '\u2002', '\u2003',
            '\u2004', '\u2005', '\u2006', '\u2007',
            '\u2008', '\u2009', '\u200A', '\u200B',
            '\u202F', '\u205F', '\u3000',
        )
        for (space in spaces) {
            assertEquals(
                "U+%04X alone must be a blank line".format(space.code),
                2, MarkdownParser.parse("para\n$space\nnext").size,
            )
        }
        // And an invisible character in neither set is not one: a WORD
        // JOINER line is a paragraph, here and on Apple.
        assertEquals(1, MarkdownParser.parse("para\n\u2060\nnext").size)
    }

    @Test fun fenceTrimsWithFoundationsSetOnTheDevice() {
        // A non-breaking space around a fence's info string is trimmed, as
        // Foundation trims it; the language is the word the author typed.
        val fence = MarkdownParser.parse("```\u00A0js\ncode()\n```").first()
        assertTrue(fence is MarkdownBlock.CodeBlock)
        fence as MarkdownBlock.CodeBlock
        assertEquals("js", fence.language)
        assertEquals("code()", fence.code)
        // And a closing fence padded with one still closes, so the rest of
        // the document is not swallowed into the code block.
        assertEquals(2, MarkdownParser.parse("```\ncode()\n```\u00A0\n\nafter").size)
    }

    @Test fun noteTrimsWithFoundationsSetOnTheDevice() {
        // U+0085 is whitespace to Foundation and not to Kotlin's `trim()`:
        // the note is found on both platforms only because the parser trims
        // with Foundation's set.
        assertEquals(listOf("n"), MarkdownParser.notes("<!--\u0085 note: n -->").map { it.text })
        assertEquals(listOf("n"), MarkdownParser.notes("<!-- note: n \u0085-->").map { it.text })
        // U+001C is whitespace to Kotlin's `trim()` and to neither Foundation
        // nor this parser, so the marker is not at the front and there is no
        // note — the same nothing the Apple ports report.
        assertEquals(
            emptyList<String>(),
            MarkdownParser.notes("<!--\u001C note: n -->").map { it.text },
        )
    }

    @Test fun orderedListMarkerIsAsciiDigitsOnTheDevice() {
        // \u0663 is ARABIC-INDIC DIGIT THREE: `Char.isDigit()` accepts it and
        // `toIntOrNull` reads it as 3, while Swift's `Int(_:)` reads nothing
        // and fell back to 1. ASCII digits only, so no table decides an
        // ordinal.
        assertTrue(MarkdownParser.parse("\u0663. three").first() is MarkdownBlock.Paragraph)
        assertTrue(MarkdownParser.parse("\u00BD. half").first() is MarkdownBlock.Paragraph)
        val list = MarkdownParser.parse("1. one\n2. two").first()
        assertTrue(list is MarkdownBlock.ListBlock)
        list as MarkdownBlock.ListBlock
        assertEquals(listOf(1, 2), list.items.map { it.ordinal })
    }
}
