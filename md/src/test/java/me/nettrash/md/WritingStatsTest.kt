/*
 * WritingStatsTest.kt
 * md (Android)
 *
 * Pins the footer's word-count semantics — locale-aware words, not a
 * whitespace split — mirroring the iOS/macOS WritingStats tests.
 */

package me.nettrash.md

import me.nettrash.md.markdown.WritingStats
import org.junit.Assert.assertEquals
import org.junit.Test

class WritingStatsTest {

    @Test
    fun emptyAndWhitespaceCountZero() {
        assertEquals(0, WritingStats.words(""))
        assertEquals(0, WritingStats.words("   \n\n"))
    }

    @Test
    fun punctuationIsNotAWord() {
        assertEquals(2, WritingStats.words("Hello, world!"))
        // An apostrophe joins a word; a dash alone is none.
        assertEquals(2, WritingStats.words("it's — done"))
    }

    @Test
    fun newlinesSeparateWords() {
        assertEquals(3, WritingStats.words("One\ntwo\n\nthree"))
    }
}
