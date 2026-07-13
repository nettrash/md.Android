/*
 * WritingStats.kt
 * md (Android)
 *
 * The author-facing counters in the editor footer. Pure Kotlin so the JVM
 * tests can pin the semantics; the Kotlin sibling of the Apple apps'
 * WritingStats.
 */

package me.nettrash.md.markdown

import java.text.BreakIterator

object WritingStats {

    /** Locale-aware word count (what "words" means to a writer, not a
     *  whitespace split — "it's" is one word, "—" is none): BreakIterator
     *  segments, counting only the segments that contain a letter or a
     *  digit. */
    fun words(text: String): Int {
        if (text.isEmpty()) return 0
        val iterator = BreakIterator.getWordInstance()
        iterator.setText(text)
        var count = 0
        var start = iterator.first()
        var end = iterator.next()
        while (end != BreakIterator.DONE) {
            if ((start until end).any { text[it].isLetterOrDigit() }) count++
            start = end
            end = iterator.next()
        }
        return count
    }
}
