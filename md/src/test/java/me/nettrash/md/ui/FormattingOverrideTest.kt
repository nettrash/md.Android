/*
 * FormattingOverrideTest.kt
 * md (Android)
 *
 * A formatting command over a capital md has just made (Bold on a fresh
 * `Word`, or `*` typed over it) is not the writer deleting that capital: it
 * must not arm the delete-and-retype override (§3.4) where the word began.
 * The same bug md and md.macOS had, found by Copilot there.
 */

package me.nettrash.md.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FormattingOverrideTest {

    private fun freshCapital(at: Int = 0) = TypingOverride().apply { produced(at, 'W'.code) }

    /** The bug, as a plain edit reads the wrap: the capital is gone, armed at 0. */
    @Test fun aPlainEditReadsAWrappedCapitalAsItsRemoval() {
        val o = freshCapital()
        o.edited("Word", 0, 4, "**Word**", 0, 4)
        assertTrue(o.isArmed)
        assertEquals(0, o.armedAt)
    }

    @Test fun boldItalicAndTheTypedWrapArmNothing() {
        for (wrapped in listOf("**Word**", "*Word*", "`Word`", "~~Word~~", "\$Word\$")) {
            val o = freshCapital()
            o.formatted("Word", 0, 4, wrapped, 0, 4)
            assertFalse(wrapped, o.isArmed)
        }
    }

    @Test fun aCapitalTheCommandOnlyMovesIsStillTracked() {
        val o = freshCapital(at = 4)
        o.formatted("One Word", 0, 3, "**One**", 0, 3)
        assertEquals(8, o.capital)
        assertFalse(o.isArmed)
        // A heading inserts its marker before the capital: it moves too.
        val h = freshCapital()
        h.formatted("Word", 0, 4, "# Word", 0, 4)
        assertEquals(2, h.capital)
    }
}
