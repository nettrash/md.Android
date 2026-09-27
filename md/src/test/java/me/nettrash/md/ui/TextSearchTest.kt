/*
 * TextSearchTest.kt
 * md (Android)
 *
 * JVM unit tests for the find bar's engine (`ui/TextSearch.kt`): the search
 * wraps, ignores case, and hands back a range the editor can select as is;
 * Replace edits only the hit the selection is standing on and moves along;
 * Replace All is one pass and ONE edit.
 *
 * The cases are md.win's `TextSearchTests.cs` ported with the port — same
 * text, same offsets, same answers — so the two ports cannot drift apart
 * quietly. The Android-only case at the end is the one the find bar's
 * Replace All leans on: the plan's single edit and its whole-text answer
 * agree, which is what lets the text field record the lot as one undo step.
 */

package me.nettrash.md.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TextSearchTest {

    private val text = "alpha beta Alpha gamma alpha"
    //                  0          11          23

    /** The text the plan's single edit leaves behind. */
    private fun applied(source: String, edit: TextSearch.Edit): String =
        source.substring(0, edit.start) + edit.text + source.substring(edit.start + edit.length)

    /** A plan's two answers agree by construction: applying its one edit
     *  produces its whole new text. */
    private fun assertPlanIsOneEdit(source: String, plan: TextSearch.ReplaceAllPlan) {
        assertEquals(plan.text, applied(source, plan.apply))
    }

    // ---- Next / Previous -------------------------------------------------

    @Test fun nextFindsTheFirstHitAtOrAfterTheCaret() {
        assertEquals(TextSearch.Match(0, 5), TextSearch.next(text, "alpha", 0))
        assertEquals(TextSearch.Match(11, 5), TextSearch.next(text, "alpha", 1))
        assertEquals(TextSearch.Match(23, 5), TextSearch.next(text, "alpha", 12))
    }

    @Test fun nextWrapsToTheTopWhenNothingFollows() {
        assertEquals(TextSearch.Match(23, 5), TextSearch.next(text, "alpha", 23))   // "at or after"
        assertEquals(TextSearch.Match(0, 5), TextSearch.next(text, "alpha", 24))
        assertEquals(TextSearch.Match(0, 5), TextSearch.next(text, "alpha", text.length))
        assertEquals(TextSearch.Match(0, 5), TextSearch.next(text, "alpha", 9999))
    }

    @Test fun previousFindsTheLastHitStartingBeforeTheCaret() {
        assertEquals(TextSearch.Match(11, 5), TextSearch.previous(text, "alpha", 22))
        assertEquals(TextSearch.Match(0, 5), TextSearch.previous(text, "alpha", 11))
        assertEquals(TextSearch.Match(23, 5), TextSearch.previous(text, "alpha", text.length))
    }

    @Test fun previousWrapsToTheBottomWhenNothingPrecedes() {
        assertEquals(TextSearch.Match(23, 5), TextSearch.previous(text, "alpha", 0))
        assertEquals(TextSearch.Match(23, 5), TextSearch.previous(text, "alpha", -5))
    }

    @Test fun matchingIsOrdinalAndCaseInsensitive() {
        assertEquals(TextSearch.Match(11, 5), TextSearch.next(text, "ALPHA", 1))
        // Ordinal, so no locale folds these together and the length always
        // equals the query's.
        assertNull(TextSearch.next("straße", "strasse", 0))
        assertEquals(6, TextSearch.next("Straße x", "straße", 0)!!.length)
    }

    @Test fun anEmptyOrOversizedQueryFindsNothing() {
        assertNull(TextSearch.next(text, "", 0))
        assertNull(TextSearch.previous(text, "", 0))
        assertNull(TextSearch.next("ab", "abc", 0))
        assertNull(TextSearch.previous("ab", "abc", 0))
        assertNull(TextSearch.next(text, "zeta", 0))
        assertNull(TextSearch.previous(text, "zeta", 0))
    }

    @Test fun anEmptyTextFindsNothingEitherWay() {
        assertNull(TextSearch.next("", "alpha", 0))
        assertNull(TextSearch.previous("", "alpha", 0))
    }

    @Test fun aSingleHitIsFoundFromAnywhereInBothDirections() {
        val one = "xxxNEEDLExxx"
        assertEquals(TextSearch.Match(3, 6), TextSearch.next(one, "needle", 0))
        assertEquals(TextSearch.Match(3, 6), TextSearch.next(one, "needle", 5))   // wraps onto itself
        assertEquals(TextSearch.Match(3, 6), TextSearch.previous(one, "needle", 0))
        assertEquals(TextSearch.Match(3, 6), TextSearch.previous(one, "needle", one.length))
    }

    @Test fun overlappingHitsAreEachReachable() {
        val aaa = "aaaa"
        assertEquals(TextSearch.Match(0, 2), TextSearch.next(aaa, "aa", 0))
        assertEquals(TextSearch.Match(1, 2), TextSearch.next(aaa, "aa", 1))
        assertEquals(TextSearch.Match(2, 2), TextSearch.next(aaa, "aa", 2))
        assertEquals(TextSearch.Match(2, 2), TextSearch.previous(aaa, "aa", 3))
    }

    @Test fun walkingWithNextComesBackToWhereItStarted() {
        // Three hits, and Next from the end of each one lands on the following
        // hit until it wraps: the find bar's whole behaviour in one loop.
        val visited = mutableListOf<Int>()
        var from = 0
        repeat(4) {
            val match = TextSearch.next(text, "alpha", from)!!
            visited += match.index
            from = match.index + match.length
        }
        assertEquals(listOf(0, 11, 23, 0), visited)
    }

    // ---- Replace ---------------------------------------------------------

    @Test fun theSelectionIsTheMatchOnlyWhenItSpansOneHitExactly() {
        assertTrue(TextSearch.selectionIsMatch(text, "alpha", 0, 5))
        assertTrue(TextSearch.selectionIsMatch(text, "alpha", 11, 5))    // "Alpha" — case-insensitive
        assertTrue(TextSearch.selectionIsMatch(text, "ALPHA", 23, 5))
        assertTrue(TextSearch.selectionIsMatch(text, "alpha", 23, 5))    // the hit at the very end

        assertFalse(TextSearch.selectionIsMatch(text, "alpha", 0, 0))    // a caret is not a match
        assertFalse(TextSearch.selectionIsMatch(text, "alpha", 0, 4))    // short of the query
        assertFalse(TextSearch.selectionIsMatch(text, "alpha", 0, 6))    // past it
        assertFalse(TextSearch.selectionIsMatch(text, "alpha", 1, 5))    // the nearest hit is elsewhere
        assertFalse(TextSearch.selectionIsMatch(text, "", 0, 0))         // an empty query matches nothing
        assertFalse(TextSearch.selectionIsMatch(text, "alpha", -1, 5))   // off either end, rather than a throw
        assertFalse(TextSearch.selectionIsMatch(text, "alpha", 24, 5))
        assertFalse(TextSearch.selectionIsMatch("straße", "strasse", 0, 7))   // ordinal, like the search
    }

    @Test fun replaceEditsTheSelectionWhenItIsTheHitAndSearchesOnFromAfterTheReplacement() {
        val step = TextSearch.replace(text, "alpha", "omega", 11, 5)
        assertEquals(TextSearch.Edit(11, 5, "omega"), step.apply)
        assertEquals(16, step.searchFrom)

        // What the find bar then does: apply, re-read, Next from searchFrom.
        val after = applied(text, step.apply!!)
        assertEquals("alpha beta omega gamma alpha", after)
        assertEquals(TextSearch.Match(23, 5), TextSearch.next(after, "alpha", step.searchFrom))
    }

    @Test fun replaceIsAPlainFindNextWhenTheSelectionIsNotTheHit() {
        // A caret inside the word, a selection of the wrong length, and one
        // over other text.
        listOf(2 to 0, 0 to 4, 6 to 4).forEach { (start, length) ->
            val step = TextSearch.replace(text, "alpha", "omega", start, length)
            assertNull(step.apply)
            assertEquals(start + length, step.searchFrom)
        }
        assertNull(TextSearch.replace(text, "", "omega", 0, 0).apply)
        assertNull(TextSearch.replace(text, "zeta", "omega", 0, 4).apply)
    }

    @Test fun replaceClampsASelectionTheFieldCouldNotHaveHad() {
        val step = TextSearch.replace(text, "alpha", "omega", 9999, 9999)
        assertNull(step.apply)
        assertEquals(text.length, step.searchFrom)

        val negative = TextSearch.replace(text, "alpha", "omega", -4, -4)
        assertNull(negative.apply)
        assertEquals(0, negative.searchFrom)
    }

    @Test fun replaceAtTheVeryStartAndTheVeryEndAreBothTheHitTheyStandOn() {
        val first = TextSearch.replace(text, "alpha", "x", 0, 5)
        assertEquals(TextSearch.Edit(0, 5, "x"), first.apply)
        assertEquals(1, first.searchFrom)
        assertEquals("x beta Alpha gamma alpha", applied(text, first.apply!!))

        val last = TextSearch.replace(text, "alpha", "x", 23, 5)
        assertEquals(TextSearch.Edit(23, 5, "x"), last.apply)
        assertEquals(24, last.searchFrom)
        assertEquals("alpha beta Alpha gamma x", applied(text, last.apply!!))
    }

    /** The loop this rule exists to prevent: "a" → "aa" is a replacement that
     *  contains the query, and searchFrom is past what went in, so the very
     *  next Next cannot land inside it. */
    @Test fun aReplacementThatContainsTheQueryIsNotSearchedAgain() {
        val step = TextSearch.replace("aaa", "a", "aa", 0, 1)
        assertEquals(TextSearch.Edit(0, 1, "aa"), step.apply)
        assertEquals(2, step.searchFrom)
        assertEquals("aaaa", applied("aaa", step.apply!!))
        assertEquals(TextSearch.Match(2, 1), TextSearch.next("aaaa", "a", step.searchFrom))
    }

    @Test fun aReplacementEqualToTheQueryStillCountsAndStillMoves() {
        val step = TextSearch.replace(text, "alpha", "alpha", 11, 5)
        assertEquals(TextSearch.Edit(11, 5, "alpha"), step.apply)
        assertEquals(16, step.searchFrom)
        // "Alpha" became "alpha": the text DID change, which is the point of a
        // case-insensitive find.
        assertEquals("alpha beta alpha gamma alpha", applied(text, step.apply!!))
    }

    @Test fun anEmptyReplacementIsADeletion() {
        val step = TextSearch.replace(text, "alpha ", "", 0, 6)
        assertEquals(TextSearch.Edit(0, 6, ""), step.apply)
        assertEquals(0, step.searchFrom)
        assertEquals("beta Alpha gamma alpha", applied(text, step.apply!!))
    }

    // ---- Replace All -----------------------------------------------------

    @Test fun replaceAllReplacesEveryHitCaseInsensitivelyAndCountsThem() {
        val plan = TextSearch.replaceAll(text, "alpha", "omega")
        assertEquals(3, plan.count)
        assertEquals("omega beta omega gamma omega", plan.text)
        assertPlanIsOneEdit(text, plan)
    }

    /** The edit is the span from the first hit to the last, not the whole
     *  document: that is what lets the field put it in with one
     *  `TextFieldState.edit` (one undo step) without rewriting text nobody
     *  asked it to touch. */
    @Test fun theReplaceAllPlanIsTheOneSpanFromTheFirstHitToTheLast() {
        val source = "head alpha middle alpha tail"
        val plan = TextSearch.replaceAll(source, "alpha", "X")
        assertEquals(2, plan.count)
        assertEquals("head X middle X tail", plan.text)
        assertEquals(TextSearch.Edit(5, 18, "X middle X"), plan.apply)
        assertPlanIsOneEdit(source, plan)
    }

    @Test fun replaceAllWithNothingToDoLeavesTheTextAndPlansAnEmptyEdit() {
        listOf("", "zeta", "$text and more").forEach { query ->
            val plan = TextSearch.replaceAll(text, query, "X")
            assertEquals(0, plan.count)
            assertEquals(text, plan.text)
            assertEquals(TextSearch.Edit(0, 0, ""), plan.apply)
            assertPlanIsOneEdit(text, plan)
        }
    }

    @Test fun replaceAllTakesHitsAtTheVeryStartAndTheVeryEnd() {
        val plan = TextSearch.replaceAll("xx middle xx", "xx", "Y")
        assertEquals(2, plan.count)
        assertEquals("Y middle Y", plan.text)
        assertEquals(TextSearch.Edit(0, 12, "Y middle Y"), plan.apply)
        assertPlanIsOneEdit("xx middle xx", plan)
    }

    /** Hits are taken left to right and never overlap: "aa" over "aaaa" is
     *  two, over "aaaaa" two with an "a" left over. */
    @Test fun overlappingCandidatesAreTakenLeftToRightWithoutOverlapping() {
        val four = TextSearch.replaceAll("aaaa", "aa", "b")
        assertEquals(2, four.count)
        assertEquals("bb", four.text)
        assertPlanIsOneEdit("aaaa", four)

        val five = TextSearch.replaceAll("aaaaa", "aa", "b")
        assertEquals(2, five.count)
        assertEquals("bba", five.text)
        assertEquals(TextSearch.Edit(0, 4, "bb"), five.apply)
        assertPlanIsOneEdit("aaaaa", five)
    }

    /** One pass, left to right, never rescanning what it inserted — or this
     *  test never returns. */
    @Test fun aReplacementContainingTheQueryReplacesEachHitExactlyOnce() {
        val plan = TextSearch.replaceAll("aaa", "a", "aa")
        assertEquals(3, plan.count)
        assertEquals("aaaaaa", plan.text)
        assertPlanIsOneEdit("aaa", plan)

        val longer = TextSearch.replaceAll("one two", "o", "foo")
        assertEquals(2, longer.count)
        assertEquals("foone twfoo", longer.text)
        assertPlanIsOneEdit("one two", longer)
    }

    @Test fun aReplacementEqualToTheQueryOnlyChangesTheCasingThatDiffered() {
        val plan = TextSearch.replaceAll(text, "alpha", "alpha")
        assertEquals(3, plan.count)
        assertEquals("alpha beta alpha gamma alpha", plan.text)
        assertPlanIsOneEdit(text, plan)

        // Nothing differed: the text comes back character for character, and
        // the count still says what happened.
        val same = TextSearch.replaceAll("alpha alpha", "alpha", "alpha")
        assertEquals(2, same.count)
        assertEquals("alpha alpha", same.text)
        assertPlanIsOneEdit("alpha alpha", same)
    }

    /** A Markdown buffer holds line ends, and a file may hold CRLF; the
     *  engine is ordinal over UTF-16 units and never treats a line end as
     *  anything else — including a query that spans one. */
    @Test fun lineEndsAreOrdinaryCharactersOnBothSidesOfTheReplace() {
        val crlf = "one\r\ntwo\r\nthree"
        val plan = TextSearch.replaceAll(crlf, "\r\n", "\n")
        assertEquals(2, plan.count)
        assertEquals("one\ntwo\nthree", plan.text)
        assertPlanIsOneEdit(crlf, plan)

        val joined = TextSearch.replaceAll(crlf, "two\r\n", "TWO\r")
        assertEquals(1, joined.count)
        assertEquals("one\r\nTWO\rthree", joined.text)
        assertPlanIsOneEdit(crlf, joined)

        val step = TextSearch.replace("a\rb\rc", "\r", "\r\r", 1, 1)
        assertEquals(TextSearch.Edit(1, 1, "\r\r"), step.apply)
        assertEquals(3, step.searchFrom)
        assertEquals("a\r\rb\rc", applied("a\rb\rc", step.apply!!))
    }

    /** Offsets are UTF-16 units — the text field's own — so an emoji on
     *  either side of the hit moves it by two and the edit must still land on
     *  the hit and never inside a pair. */
    @Test fun surrogatePairsAreCountedAsTwoUnitsEach() {
        val source = "😀needle😀needle😀"
        assertEquals(2, TextSearch.next(source, "needle", 0)!!.index)
        assertEquals(10, TextSearch.next(source, "needle", 3)!!.index)
        val plan = TextSearch.replaceAll(source, "needle", "pin")
        assertEquals(2, plan.count)
        assertEquals("😀pin😀pin😀", plan.text)
        assertPlanIsOneEdit(source, plan)
    }
}
