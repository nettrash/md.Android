/*
 * TypingTest.kt
 * md (Android)
 *
 * JVM tests for the pure half of the smart-typing adapter (ui/Typing.kt):
 * the word-insertion reduction of SPEC §3.3, the change-set classifier that
 * turns a text field's change list into the one edit SmartTyping is asked
 * about, the tracked-capital state machine of §3.4 with its two Android
 * readings (IME composition re-sends the whole word over the same region),
 * and — on a text field on paper that applies `Typing.plan` exactly as the
 * transformation does — the eight consequences every port pins. The Compose
 * shell (SmartTypingTransformation) is exercised on the emulator, by
 * SmartTypingFieldInstrumentedTest.
 */

package me.nettrash.md.ui

import kotlin.math.max
import kotlin.math.min
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TypingTest {

    // MARK: - §0.3 WS19

    @Test fun ws19IsExactlyTheNineteenScalars() {
        val expected = listOf(0x09, 0x20, 0xa0, 0x1680) + (0x2000..0x200a) +
            listOf(0x200b, 0x202f, 0x205f, 0x3000)
        assertEquals(19, expected.size)
        for (u in expected) assertTrue("U+%04X".format(u), Typing.isWS19(u))
        // The usual impostors: line terminators, VT, FF, NEL, LS, PS, BOM.
        for (u in listOf(0x0a, 0x0d, 0x0b, 0x0c, 0x85, 0x2028, 0x2029, 0xfeff, 0x200c, 0x180e)) {
            assertFalse("U+%04X".format(u), Typing.isWS19(u))
        }
    }

    // MARK: - §3.3 word insertion

    @Test fun aComposedOrCommittedWordIsAWordInsertion() {
        assertTrue(Typing.isWordInsertion("h"))
        assertTrue(Typing.isWordInsertion("he"))
        assertTrue(Typing.isWordInsertion("hello"))
        assertTrue(Typing.isWordInsertion("hello "))   // Gboard's commit carries one trailing space
        assertTrue(Typing.isWordInsertion("école"))
        assertTrue(Typing.isWordInsertion("ǆemal"))
        assertTrue(Typing.isWordInsertion("ß"))          // Ll; capitalize itself answers null (§0.7)
        assertTrue(Typing.isWordInsertion("\uD801\uDC28x")) // U+10428 Deseret small long I: a non-BMP Ll
        assertTrue(Typing.isWordInsertion("don't"))
        assertTrue(Typing.isWordInsertion("re-type"))
        assertTrue(Typing.isWordInsertion("a."))
    }

    @Test fun whitespaceAndLineBreaksDisqualify() {
        assertFalse(Typing.isWordInsertion(""))
        assertFalse(Typing.isWordInsertion(" "))
        assertFalse(Typing.isWordInsertion(" hello"))
        assertFalse(Typing.isWordInsertion("hello  "))       // two trailing spaces
        assertFalse(Typing.isWordInsertion("hello world"))   // a dictated phrase
        assertFalse(Typing.isWordInsertion("hello world "))
        assertFalse(Typing.isWordInsertion("hello\n"))
        assertFalse(Typing.isWordInsertion("hel\rlo"))
        assertFalse(Typing.isWordInsertion("\n"))
        assertFalse(Typing.isWordInsertion("hello\t"))
        assertFalse(Typing.isWordInsertion("hello\u00a0"))   // a trailing NBSP is WS19, not SP
        assertFalse(Typing.isWordInsertion("hello\u3000"))
        assertFalse(Typing.isWordInsertion("he\u200bllo"))   // ZWSP inside
    }

    @Test fun theFirstScalarMustBeLowercase() {
        assertFalse(Typing.isWordInsertion("Hello"))
        assertFalse(Typing.isWordInsertion("1st"))
        assertFalse(Typing.isWordInsertion("_x"))
        assertFalse(Typing.isWordInsertion("ǅ"))            // Lt, titlecase
        assertFalse(Typing.isWordInsertion("ª"))        // ª is Lo since Unicode 6.1
        assertFalse(Typing.isWordInsertion("\uD801"))        // a lone high surrogate is Cs
        assertFalse(Typing.isWordInsertion("\uDC28"))        // a lone low surrogate
        assertFalse(Typing.isWordInsertion("\uD801\uDC00"))  // U+10400, the CAPITAL long I
        assertFalse(Typing.isWordInsertion("\u0301a"))       // a combining mark first
    }

    @Test fun urlsPathsAndHandlesAreInsertedUnchanged() {
        assertFalse(Typing.isWordInsertion("https://a.b"))
        assertFalse(Typing.isWordInsertion("http://"))
        assertFalse(Typing.isWordInsertion("www.nettrash.me"))
        assertFalse(Typing.isWordInsertion("nettrash@nettrash.me"))
        assertFalse(Typing.isWordInsertion("@nettrash"))     // fails on the first scalar and the @ alike
        assertFalse(Typing.isWordInsertion("a/b"))
        assertFalse(Typing.isWordInsertion("~/Documents/x"))
        assertFalse(Typing.isWordInsertion("src/"))
        // Not a URL, just a word that happens to contain "www" without the dot.
        assertTrue(Typing.isWordInsertion("wwwhat"))
    }

    // MARK: - the classifier

    private fun one(start: Int, end: Int, inserted: String) = listOf(TypingChange(start, end, inserted))

    @Test fun returnAtACollapsedCaretIsEnter() {
        assertEquals(TypingEdit.Enter(5, 5), Typing.classify(one(5, 5, "\n"), 5, 5))
        // The pre-edit selection may be reported in either order — collapsed
        // it is one offset whichever way round.
        assertEquals(TypingEdit.Enter(0, 0), Typing.classify(one(0, 0, "\n"), 0, 0))
    }

    @Test fun returnOverASelectionOrElsewhereIsNotEnter() {
        // The platform's own newline replaces the selection (§3.3, Android).
        assertNull(Typing.classify(one(3, 5, "\n"), 3, 5))
        assertNull(Typing.classify(one(3, 5, "\n"), 5, 3))
        // A newline inserted somewhere other than the caret (an accessibility
        // service, a programmatic edit) is not the writer's Return.
        assertNull(Typing.classify(one(5, 5, "\n"), 2, 2))
        assertNull(Typing.classify(one(5, 5, "\n"), 5, 7))
        // Two newlines, or a CR LF, are pastes.
        assertNull(Typing.classify(one(5, 5, "\n\n"), 5, 5))
        assertNull(Typing.classify(one(5, 5, "\r\n"), 5, 5))
    }

    @Test fun aWordInsertionIsWordWhateverTheSelectionWas() {
        assertEquals(TypingEdit.Word(7, 7, "w"), Typing.classify(one(7, 7, "w"), 7, 7))
        // Gboard re-sending "wo" over its composing region "W".
        assertEquals(TypingEdit.Word(7, 8, "wo"), Typing.classify(one(7, 8, "wo"), 8, 8))
        // A glide-typed word committed with its space over a selection.
        assertEquals(TypingEdit.Word(2, 6, "hello "), Typing.classify(one(2, 6, "hello "), 6, 2))
    }

    @Test fun deletionsPhrasesAndMultiChangeSetsAreNothing() {
        assertNull(Typing.classify(one(4, 5, ""), 5, 5))             // backspace
        assertNull(Typing.classify(one(0, 0, "hello world"), 0, 0))  // a phrase
        assertNull(Typing.classify(one(0, 0, "Hello"), 0, 0))        // a capital of its own
        assertNull(Typing.classify(emptyList(), 0, 0))
        assertNull(
            Typing.classify(listOf(TypingChange(0, 1, ""), TypingChange(4, 4, "x")), 4, 4),
        )
    }

    @Test fun wordExposesItsFirstScalarInUnits() {
        val bmp = TypingEdit.Word(0, 0, "hello ")
        assertEquals("h", bmp.first)
        assertEquals('h'.code, bmp.firstScalar)
        val astral = TypingEdit.Word(3, 3, "\uD801\uDC28x")
        assertEquals("\uD801\uDC28", astral.first)
        assertEquals(0x10428, astral.firstScalar)
        assertEquals(2, astral.first.length)
    }

    // MARK: - §3.4 the tracked capital

    private val m = 'm'.code

    @Test fun startsWithNothingTrackedAndCapitalizeDecides() {
        val o = TypingOverride()
        assertEquals(-1, o.capital)
        assertEquals(-1, o.armedAt)
        assertFalse(o.isArmed)
        assertFalse(o.insertsAsTyped("", 0, 0, "m", 0, 0, m))
        assertFalse(o.isArmed)
    }

    @Test fun anEditBeforeTheCapitalShiftsItAndOneAfterLeavesIt() {
        val o = TypingOverride()
        o.produced(5, 'M'.code)
        o.edited("Xxx. M", 0, 0, "Hi ", 0, 0)          // an insertion before: +3
        assertEquals(8, o.capital)
        o.edited("Hi Xxx. M", 0, 3, "", 3, 3)          // a deletion before: −3
        assertEquals(5, o.capital)
        o.edited("Xxx. M", 1, 3, "abcd", 1, 3)         // a replacement before: +2
        assertEquals(7, o.capital)
        o.edited("Xabcd. M", 8, 8, "d", 8, 8)          // after: nothing
        o.edited("Xabcd. Md", 7, 7, "", 8, 8)          // an empty edit at p: nothing
        assertEquals(7, o.capital)
        assertEquals('M'.code, o.capitalScalar)
        assertFalse(o.isArmed)
    }

    @Test fun anInsertionAtTheCapitalShiftsIt() {
        // (5): after "Md", the caret before the M and "a" typed: the capital
        // was not deleted; it moves to p+1.
        val o = TypingOverride()
        o.produced(0, 'M'.code)
        o.edited("Md", 0, 0, "a", 0, 0)
        assertEquals(1, o.capital)
        assertFalse(o.isArmed)
    }

    @Test fun removingTheCapitalArmsAtTheEditsStart() {
        // A backspace over it.
        val o = TypingOverride()
        o.produced(5, 'M'.code)
        o.edited("Xxx. M", 5, 6, "", 6, 6)
        assertEquals(-1, o.capital)
        assertEquals(5, o.armedAt)
        // A selection that covers it, replaced by something else.
        val p = TypingOverride()
        p.produced(5, 'M'.code)
        p.edited("Xxx. Md", 3, 7, "!", 3, 7)
        assertEquals(-1, p.capital)
        assertEquals(3, p.armedAt)
        // Deleted together with what is before it: armed at the deletion's start.
        val q = TypingOverride()
        q.produced(5, 'M'.code)
        q.edited("Xxx. M", 0, 6, "", 0, 6)
        assertEquals(0, q.armedAt)
    }

    @Test fun puttingTheSameCapitalBackKeepsIt() {
        // Select "M", type "M" (Shift); or select "Md", paste "Md".
        val o = TypingOverride()
        o.produced(0, 'M'.code)
        o.edited("Md", 0, 1, "M", 0, 1)
        assertEquals(0, o.capital)
        assertFalse(o.isArmed)
        o.edited("Md", 0, 2, "Md", 0, 2)
        assertEquals(0, o.capital)
        // Put back at another offset is not the same capital.
        o.edited("Md", 0, 2, "xM", 0, 2)
        assertEquals(-1, o.capital)
        assertEquals(0, o.armedAt)
    }

    @Test fun producingANewCapitalReplacesTheTrackedOneAndArmsNothing() {
        val o = TypingOverride()
        o.produced(0, 'M'.code)
        o.produced(6, 'I'.code)
        assertEquals(6, o.capital)
        assertEquals('I'.code, o.capitalScalar)
        assertFalse(o.isArmed)
        // The old one is forgotten: deleting it now arms nothing.
        o.edited("Md is Iy", 0, 1, "", 1, 1)
        assertEquals(5, o.capital)
        assertFalse(o.isArmed)
        // A capital produced while armed: nothing stays armed.
        o.edited("d is Iy", 5, 6, "", 6, 6)
        assertTrue(o.isArmed)
        o.produced(5, 'W'.code)
        assertFalse(o.isArmed)
    }

    @Test fun whileArmedAWordAtQIsAsTypedAndStaysArmedElsewhereClears() {
        val o = TypingOverride()
        o.produced(7, 'W'.code)
        o.edited("hello. W", 7, 8, "", 8, 8)               // backspace
        assertEquals(7, o.armedAt)
        assertTrue(o.insertsAsTyped("hello. ", 7, 7, "w", 7, 7, 'w'.code))
        assertTrue("stays armed for the re-sends", o.isArmed)
        // Gboard re-sends "wo", then commits "wo " — all over the region at 7.
        assertTrue(o.insertsAsTyped("hello. w", 7, 8, "wo", 8, 8, 'w'.code))
        assertTrue(o.insertsAsTyped("hello. wo", 7, 9, "wo ", 9, 9, 'w'.code))
        assertEquals(7, o.armedAt)
        // The next word starts elsewhere: cleared, capitalize decides.
        assertFalse(o.insertsAsTyped("hello. wo ", 10, 10, "i", 10, 10, 'i'.code))
        assertFalse(o.isArmed)
    }

    @Test fun deletionsNeverClearTheOverride() {
        val o = TypingOverride()
        o.produced(5, 'M'.code)
        o.edited("Xxx. M", 5, 6, "", 6, 6)
        assertEquals(5, o.armedAt)
        o.edited("Xxx. ", 4, 5, "", 5, 5)       // a deletion ending at q shifts it
        assertEquals(4, o.armedAt)
        o.edited("Xxx.", 0, 2, "", 0, 2)        // a deletion before q shifts it
        assertEquals(2, o.armedAt)
        o.edited("x.", 1, 2, "", 1, 2)          // a deletion covering q moves q to its start
        assertEquals(1, o.armedAt)
        o.edited("x", 1, 1, "", 1, 1)           // an empty edit at q
        assertEquals(1, o.armedAt)
        // Any insertion elsewhere — a newline, a paste — clears.
        o.edited("x", 0, 0, "\n", 0, 0)
        assertFalse(o.isArmed)
    }

    @Test fun aNonWordInsertionAtQClearsTheOverride() {
        // Only a word insertion at q — the IME re-sending its composing
        // region — keeps the override armed; a digit, a paste, a newline at q
        // is not that and clears it, as any insertion elsewhere does. Left
        // armed, a stale q would make the letter later inserted there — a
        // paste back after a cut, then a letter typed before it — lowercase.
        val o = TypingOverride()
        o.produced(0, 'M'.code)
        o.edited("M", 0, 1, "", 1, 1)
        assertEquals(0, o.armedAt)
        o.edited("", 0, 0, "1", 0, 0)           // a digit at q
        assertFalse(o.isArmed)
        // The letter after it lands at 1: capitalize decides, as it would anyway.
        assertFalse(o.insertsAsTyped("1", 1, 1, "m", 1, 1, m))
        assertFalse(o.isArmed)
        val p = TypingOverride()
        p.produced(0, 'M'.code)
        p.edited("M", 0, 1, "", 1, 1)
        p.edited("", 0, 0, "Md", 0, 0)          // a paste at q
        assertFalse(p.isArmed)
        val q = TypingOverride()
        q.produced(0, 'M'.code)
        q.edited("M", 0, 1, "", 1, 1)
        q.edited("", 0, 0, "\n", 0, 0)          // Return at q
        assertFalse(q.isArmed)
        // A word insertion at q is the one that keeps it.
        val r = TypingOverride()
        r.produced(0, 'M'.code)
        r.edited("M", 0, 1, "", 1, 1)
        assertTrue(r.insertsAsTyped("", 0, 0, "m", 0, 0, m))
        assertTrue(r.isArmed)
        assertTrue(r.insertsAsTyped("m", 0, 1, "md", 1, 1, m))
        assertEquals(0, r.armedAt)
    }

    @Test fun oneLowercaseLetterOverItsOwnCapitalIsAsTypedAndArms() {
        // A capital the writer typed with Shift — nothing tracked: select the
        // "M", type "m". As typed, and armed, so the composition re-sends stay
        // lowercase too.
        val o = TypingOverride()
        assertFalse(o.isArmed)
        assertTrue(o.insertsAsTyped("Md is", 0, 1, "m", 0, 1, m))
        assertEquals(0, o.armedAt)
        assertTrue(o.insertsAsTyped("md is", 0, 1, "md", 1, 1, m))
        // Backwards selection, and the tracked capital itself.
        val p = TypingOverride()
        p.produced(0, 'M'.code)
        assertTrue(p.insertsAsTyped("Md is", 0, 1, "m", 1, 0, m))
        assertEquals(-1, p.capital)
        assertEquals(0, p.armedAt)
        // Non-BMP: U+10400 selected, U+10428 typed over it.
        val q = TypingOverride()
        assertTrue(q.insertsAsTyped("𐐀x", 0, 2, "𐐨", 2, 0, 0x10428))
        assertTrue(q.isArmed)
    }

    @Test fun theIndependentRuleNeedsExactlyThatCapitalAndOneLetter() {
        val o = TypingOverride()
        // A different letter over an untracked capital: capitalize decides.
        assertFalse(o.insertsAsTyped("Md is", 0, 1, "x", 0, 1, 'x'.code))
        assertFalse(o.isArmed)
        // A whole word over it (glide, a suggestion): capitalize decides.
        assertFalse(o.insertsAsTyped("Md is", 0, 1, "md", 0, 1, m))
        // The selection must be exactly the one scalar.
        assertFalse(o.insertsAsTyped("Md is", 0, 2, "m", 0, 2, m))
        // The same lowercase letter over itself is not a retype of a capital.
        assertFalse(o.insertsAsTyped("md is", 0, 1, "m", 0, 1, m))
        // A collapsed selection before the capital is an insertion, not a retype.
        assertFalse(o.insertsAsTyped("Md is", 0, 0, "m", 0, 0, m))
        assertFalse(o.isArmed)
    }

    @Test fun anExternalReplacementClearsBoth() {
        val o = TypingOverride()
        o.produced(3, 'A'.code)
        o.clear()
        assertEquals(-1, o.capital)
        assertFalse(o.isArmed)
        o.produced(3, 'A'.code)
        o.edited("xx A", 3, 4, "", 4, 4)
        assertTrue(o.isArmed)
        o.clear()
        assertFalse(o.isArmed)
        assertFalse(o.insertsAsTyped("xx ", 3, 3, "a", 3, 3, 'a'.code))
    }

    @Test fun anUnseenRemovalIsLearntFromTheTextAndArmsAtP() {
        // Undo runs no hook: the next edit finds the capital gone.
        val o = TypingOverride()
        o.produced(0, 'M'.code)
        o.reconcile("")
        assertEquals(-1, o.capital)
        assertEquals(0, o.armedAt)
        // Still there: nothing changes.
        val p = TypingOverride()
        p.produced(5, 'M'.code)
        p.reconcile("Xxx. Md")
        assertEquals(5, p.capital)
        assertFalse(p.isArmed)
        // Gone from p (something else sits there): armed at p.
        p.reconcile("Xxx. md")
        assertEquals(5, p.armedAt)
        // Non-BMP: half a pair is not the capital.
        val q = TypingOverride()
        q.produced(0, 0x10400)
        q.reconcile("𐐀")
        assertEquals(0, q.capital)
        q.reconcile("\uD801")
        assertEquals(-1, q.capital)
        assertEquals(0, q.armedAt)
    }

    @Test fun theImeEchoOverTheFreshCapitalKeepsTheCapitalizePath() {
        // "m" became "M" at 0; Gboard now re-sends "md" over the "M" with the
        // caret after it. That is the echo, not a retype: capitalize decides
        // (and, being idempotent, keeps the "M"); p stays; nothing armed.
        val o = TypingOverride()
        o.produced(0, 'M'.code)
        assertFalse(o.insertsAsTyped("M", 0, 1, "md", 1, 1, m))
        assertEquals(0, o.capital)
        assertFalse(o.isArmed)
        // … and again on the commit "md " over "Md", and on a backspace
        // inside the composition, "m" over "Md": still the echo.
        assertFalse(o.insertsAsTyped("Md", 0, 2, "md ", 2, 2, m))
        assertFalse(o.insertsAsTyped("Md", 0, 2, "m", 2, 2, m))
        assertEquals(0, o.capital)
        // Emptying the region removes the capital.
        o.edited("M", 0, 1, "", 1, 1)
        assertEquals(-1, o.capital)
        assertEquals(0, o.armedAt)
    }

    @Test fun theEchoNeedsACollapsedSelectionAndThatLetter() {
        // The same "m" over the "M", but with the "M" selected: the writer's retype.
        val o = TypingOverride()
        o.produced(0, 'M'.code)
        assertTrue(o.insertsAsTyped("M", 0, 1, "m", 0, 1, m))
        assertEquals(0, o.armedAt)
        // A different word over the region ("doc " picked from the suggestion
        // strip while "md" was composing): not the echo — the capital is
        // removed, the override armed, and the word stays as it came, the
        // same as typing over a selection that starts at the capital.
        val p = TypingOverride()
        p.produced(0, 'M'.code)
        assertTrue(p.insertsAsTyped("Md", 0, 2, "doc ", 2, 2, 'd'.code))
        assertEquals(-1, p.capital)
        assertEquals(0, p.armedAt)
    }

    @Test fun theEchoReadsTheScalarNotTheUnit() {
        // The capital is U+10400 (two units); the echo "U+10428 x" over it is the IME's.
        val o = TypingOverride()
        o.produced(0, 0x10400)
        assertFalse(o.insertsAsTyped("𐐀", 0, 2, "𐐨x", 2, 2, 0x10428))
        assertEquals(0, o.capital)
        // With the capital deleted, a letter at 0 is the writer's.
        o.edited("𐐀", 0, 2, "", 2, 2)
        assertTrue(o.insertsAsTyped("", 0, 0, "𐐨", 0, 0, 0x10428))
    }

    @Test fun aChangeIsReducedToItsCoreBeforeTheCapitalIsTracked() {
        // An AOSP-derived keyboard re-composes a word the caret is placed at
        // the start of, so a letter typed there arrives as the whole word
        // over the whole region — "aMd" over "Md". Read raw, that covers p
        // and removes the capital; read as its core, after the common prefix
        // and suffix are stripped, it is an insertion of "a" at 0, and p
        // shifts. Only the tracked capital reads the core; the armed offset
        // reads the raw change (the Android exception is about the region's
        // start q).
        val o = TypingOverride()
        o.produced(0, 'M'.code)
        o.edited("Md", 0, 2, "aMd", 0, 0)          // the core: "a" inserted at 0 → p + 1
        assertEquals(1, o.capital)
        assertFalse(o.isArmed)
        o.edited("aMd", 0, 3, "aMdx", 3, 3)        // the core: "x" inserted at 3 → nothing
        assertEquals(1, o.capital)
        o.edited("aMdx", 0, 4, "Mdx", 4, 4)        // the core: [0, 1) deleted → p − 1
        assertEquals(0, o.capital)
        o.edited("Mdx", 0, 3, "dx", 3, 3)          // the core: [0, 1) deleted — the capital
        assertEquals(-1, o.capital)
        assertEquals(0, o.armedAt)
        // After a sentence: the same, at 5.
        val p = TypingOverride()
        p.produced(5, 'M'.code)
        p.edited("Xxx. Md", 5, 7, "aMd", 5, 5)
        assertEquals(6, p.capital)
        // A non-BMP capital: the common prefix stops short of splitting the
        // pair — U+10428 re-sent over U+10400 shares its high surrogate, and
        // is still the echo, not a re-send of half a scalar at 1.
        val q = TypingOverride()
        q.produced(0, 0x10400)
        q.edited("𐐀x", 0, 3, "𐐨x", 3, 3)
        assertEquals(0, q.capital)
        q.edited("𐐀x", 0, 3, "𐐁x", 3, 3)  // U+10401, another capital: removed
        assertEquals(-1, q.capital)
        assertEquals(0, q.armedAt)
        // … and the common suffix likewise: U+10800 over U+10400 shares its
        // low surrogate.
        val r = TypingOverride()
        r.produced(0, 0x10400)
        r.edited("𐐀", 0, 2, "𐠀", 2, 2)
        assertEquals(-1, r.capital)
        assertEquals(0, r.armedAt)
    }

    @Test fun aChangeSetIsWalkedLastToFirst() {
        // Two changes at once, both before p — the second is applied while the
        // first is still pending, so p ends where the text puts it.
        val o = TypingOverride()
        o.produced(6, 'M'.code)
        o.edited("Xxx.  M", listOf(TypingChange(0, 1, ""), TypingChange(4, 4, "x")), 6, 6)
        assertEquals(6, o.capital)
        // One of them removes it: armed where that one started, shifted by the one before.
        val p = TypingOverride()
        p.produced(6, 'M'.code)
        p.edited("Xxx.  M", listOf(TypingChange(0, 1, ""), TypingChange(6, 7, "")), 6, 6)
        assertEquals(-1, p.capital)
        assertEquals(5, p.armedAt)
    }

    // MARK: - the adapter on paper

    /**
     * A text field on paper: the platform's edit, then [Typing.plan] — the
     * very code the transformation runs — applied on top of it. Keystrokes
     * replace the selection one scalar at a time; [compose] is Gboard
     * re-sending its composing region; [undo] takes the last edit back with
     * no hook run, as Compose does — and, as §3.5 records for Android, that
     * removes the letter, capital and all.
     */
    private class Field(
        private val continueLists: Boolean = true,
        private val capitalizeSentences: Boolean = true,
    ) {
        var text = ""
        var selectionStart = 0
        var selectionEnd = 0
        val override = TypingOverride()
        private val history = ArrayList<Triple<String, Int, Int>>()

        fun edit(start: Int, end: Int, inserted: String): Field {
            history += Triple(text, selectionStart, selectionEnd)
            val before = text
            val plan = Typing.plan(
                before, selectionStart, selectionEnd, listOf(TypingChange(start, end, inserted)),
                continueLists, capitalizeSentences, override,
            )
            var after = before.substring(0, start) + inserted + before.substring(end)
            var caret = start + inserted.length
            when (plan) {
                is TypingPlan.Enter -> {
                    val e = plan.edit
                    after = before.substring(0, e.location) + e.replacement + before.substring(e.location + e.length)
                    caret = e.caret
                }
                is TypingPlan.Capital -> after = after.substring(0, plan.at) + plan.upper + after.substring(plan.at + plan.length)
                null -> Unit
            }
            text = after
            selectionStart = caret
            selectionEnd = caret
            return this
        }

        /** Keystrokes: each scalar replaces the selection. */
        fun type(s: String): Field {
            var i = 0
            while (i < s.length) {
                val n = Character.charCount(s.codePointAt(i))
                edit(min(selectionStart, selectionEnd), max(selectionStart, selectionEnd), s.substring(i, i + n))
                i += n
            }
            return this
        }

        /** Gboard re-sending its composing region `[from, caret)` as [word], the caret collapsed after it. */
        fun compose(from: Int, word: String): Field = edit(from, selectionEnd, word)

        fun backspace(times: Int = 1): Field {
            repeat(times) {
                val s0 = min(selectionStart, selectionEnd)
                val s1 = max(selectionStart, selectionEnd)
                if (s0 != s1) edit(s0, s1, "") else if (s0 > 0) edit(s0 - 1, s0, "")
            }
            return this
        }

        fun enter(): Field = edit(min(selectionStart, selectionEnd), max(selectionStart, selectionEnd), "\n")

        fun select(anchor: Int, focus: Int): Field {
            selectionStart = anchor
            selectionEnd = focus
            return this
        }

        fun caret(at: Int): Field = select(at, at)

        fun undo(): Field {
            val (t, a, b) = history.removeAt(history.size - 1)
            text = t
            selectionStart = a
            selectionEnd = b
            return this
        }

        fun replaceDocument(t: String): Field {
            text = t
            selectionStart = 0
            selectionEnd = 0
            override.clear()
            history.clear()
            return this
        }
    }

    // MARK: - §3.4 the eight consequences

    @Test fun c1_deleteTheCapitalAndTypeTheWordAgain() {
        val f = Field()
        assertEquals("Md", f.type("md").text)
        assertEquals("", f.backspace(2).text)
        assertEquals("md", f.type("md").text)
    }

    @Test fun c2_iOS() {
        val f = Field()
        assertEquals("IOS", f.type("iOS").text)
        assertEquals("", f.backspace(3).text)
        assertEquals("iOS", f.type("iOS").text)
    }

    @Test fun c3_fiveKeystrokesAfterTheCapitalAndFiveBackspaces() {
        val f = Field()
        assertEquals("Md is", f.type("md is").text)
        assertEquals("", f.backspace(5).text)
        assertEquals("md is", f.type("md is").text)
    }

    @Test fun c4_selectTheWholeWordAndRetype() {
        val f = Field()
        assertEquals("Md", f.type("md").text)
        assertEquals("md", f.select(0, 2).type("md").text)
        // Backwards selection, the same.
        val g = Field()
        g.type("md")
        assertEquals("md", g.select(2, 0).type("md").text)
    }

    @Test fun c5_typeBeforeTheCapital() {
        val f = Field()
        assertEquals("M", f.type("m").text)
        assertEquals("AM", f.caret(0).type("a").text)
        // The capital was not deleted: nothing armed; the new capital is the tracked one.
        assertFalse(f.override.isArmed)
        assertEquals(0, f.override.capital)
        assertEquals('A'.code, f.override.capitalScalar)
    }

    @Test fun c6_undoRemovesTheLetterOnAndroidAndTheRetypeStaysLowercase() {
        // Apple and Windows restore the lowercase "m"; Android's Undo takes
        // the whole keystroke back, capital and all — its recorded
        // difference (§3.5). The letter typed again then stays as typed.
        val f = Field()
        assertEquals("M", f.type("m").text)
        assertEquals("", f.undo().text)
        assertEquals("unseen by the machine until the next edit", 0, f.override.capital)
        assertEquals("m", f.type("m").text)
        assertEquals("md", f.type("d").text)
    }

    @Test fun c7_offsetTrackingSurvivesACutBeforeTheCapital() {
        val f = Field()
        assertEquals("Xxx. M", f.type("Xxx. m").text)
        assertEquals(5, f.override.capital)
        assertEquals("M", f.select(0, 5).backspace().text)   // the cut, a deletion to the machine
        assertEquals(0, f.override.capital)
        assertEquals("", f.caret(1).backspace().text)        // the caret moved after the M: a move, not an edit
        assertEquals(0, f.override.armedAt)
        assertEquals("m", f.type("m").text)
    }

    @Test fun c8_deletingAfterTheCapitalArmsNothing() {
        val f = Field()
        assertEquals("Md", f.type("md").text)
        assertEquals("M", f.backspace().text)
        assertEquals("Md", f.type("d").text)
        assertFalse(f.override.isArmed)
        assertEquals(0, f.override.capital)
    }

    // MARK: - the adapter under IME composition and the rest

    @Test fun composedWordEchoesKeepTheCapital() {
        val f = Field()
        assertEquals("M", f.type("m").text)                  // composing "m"
        assertEquals("Md", f.compose(0, "md").text)           // the echo: re-applied, p stays
        assertEquals(0, f.override.capital)
        assertFalse(f.override.isArmed)
        assertEquals("M", f.compose(0, "m").text)             // backspace inside the composition: (8) under Gboard
        assertEquals("Md", f.compose(0, "md").text)
        assertEquals("Md ", f.compose(0, "md ").text)         // the commit
        assertEquals("Md i", f.type("i").text)                // the next word is no sentence start
    }

    @Test fun composedRetypeStaysLowercaseThroughTheResends() {
        val f = Field()
        f.type("m").compose(0, "md").compose(0, "md ")
        assertEquals("Md ", f.text)
        assertEquals("", f.compose(0, "").text)               // the composition deleted: (1) under Gboard
        assertEquals(0, f.override.armedAt)
        assertEquals("m", f.type("m").text)
        assertEquals("md", f.compose(0, "md").text)
        assertEquals("md ", f.compose(0, "md ").text)
        assertTrue("armed through every re-send", f.override.isArmed)
        assertEquals("md i", f.type("i").text)
        assertFalse("an insertion elsewhere clears", f.override.isArmed)
    }

    @Test fun composedSelectAndRetype() {
        // (4) with the IME: the selection replaced by the composing "m", then
        // the re-send "md" over it.
        val f = Field()
        f.type("m").compose(0, "md")
        assertEquals("Md", f.text)
        assertEquals("m", f.select(0, 2).type("m").text)
        assertEquals("md", f.compose(0, "md").text)
    }

    @Test fun c5_underAKeyboardThatReComposesTheWordTheCaretIsPlacedAt() {
        // (5) again, under an AOSP-derived keyboard (Gboard does not do this;
        // LatinIME's RichInputConnection does): the caret placed at the start
        // of "Md" re-composes the word, so the "a" typed there arrives as
        // "aMd" over [0, 2) — the capital still in the text, one unit on.
        // The core of that change is an insertion of "a" at 0: p shifts,
        // the "a" is judged, and becomes the tracked capital.
        val f = Field()
        assertEquals("Md", f.type("md").text)
        assertEquals("AMd", f.caret(0).edit(0, 2, "aMd").text)
        assertFalse(f.override.isArmed)
        assertEquals(0, f.override.capital)
        assertEquals('A'.code, f.override.capitalScalar)
        // After a sentence.
        val g = Field()
        assertEquals("Xxx. Md", g.type("Xxx. md").text)
        assertEquals("Xxx. AMd", g.caret(5).edit(5, 7, "aMd").text)
        assertEquals(5, g.override.capital)
        // A backspace inside the re-composed word — "d" over "Md", the caret
        // after the M: the core is a deletion of the capital; the "d" stays,
        // the override is armed at 0, and the "m" typed there again — sent
        // as "md" over the composing region — stays lowercase.
        val h = Field()
        h.type("md")
        assertEquals("d", h.caret(1).edit(0, 2, "d").text)
        assertEquals(-1, h.override.capital)
        assertEquals(0, h.override.armedAt)
        assertEquals("md", h.caret(0).edit(0, 1, "md").text)
        assertEquals(0, h.override.armedAt)
    }

    @Test fun aPasteBackAtQClearsSoALetterTypedBeforeItIsJudged() {
        // Cut "Md", paste it back, place the caret before it and type "a":
        // the paste at q is not a word insertion, so the override is
        // cleared, and the "a" is judged — (5), not a stale arm.
        val f = Field()
        assertEquals("Md", f.type("md").text)
        assertEquals("", f.select(0, 2).backspace().text)     // the cut
        assertEquals(0, f.override.armedAt)
        assertEquals("Md", f.edit(0, 0, "Md").text)            // the paste
        assertFalse(f.override.isArmed)
        assertEquals("AMd", f.caret(0).type("a").text)
    }

    @Test fun returnBeforeTheCapitalShiftsItAndAListContinuesAfterIt() {
        val f = Field()
        f.type("md")
        assertEquals("\nMd", f.caret(0).enter().text)
        assertEquals(1, f.override.capital)
        val g = Field()
        assertEquals("- A", g.type("- a").text)
        assertEquals(2, g.override.capital)
        assertEquals("- A\n- ", g.enter().text)             // enter's own replacement, tracked as what it is
        assertEquals(2, g.override.capital)
        assertFalse(g.override.isArmed)
        // Return at q — an insertion at q that is not a word — clears it; the
        // letter typed after it lands at 1 and is judged either way.
        val h = Field()
        h.type("m").backspace()
        assertEquals(0, h.override.armedAt)
        h.enter()
        assertFalse(h.override.isArmed)
        assertEquals("\nM", h.type("m").text)
    }

    @Test fun withCapitalizeSentencesOffNothingIsJudgedButTheTrackingGoesOn() {
        val f = Field(capitalizeSentences = false)
        assertEquals("md", f.type("md").text)
        assertEquals(-1, f.override.capital)
        assertFalse(f.override.isArmed)
        // A capital produced earlier (the setting was on) is still followed.
        f.override.produced(0, 'M'.code)
        f.replaceDocumentKeepingMachine("Md")
        f.caret(0).type("x")
        assertEquals("xMd", f.text)
        assertEquals(1, f.override.capital)
    }

    private fun Field.replaceDocumentKeepingMachine(t: String) {
        text = t
        selectionStart = 0
        selectionEnd = 0
    }

    @Test fun withContinueListsOffReturnIsAPlainNewline() {
        val f = Field(continueLists = false)
        assertEquals("- A", f.type("- a").text)
        assertEquals("- A\n", f.enter().text)
        assertEquals(2, f.override.capital)
    }

    @Test fun anExternalReplacementForgetsEverything() {
        val f = Field()
        f.type("m").backspace()
        assertTrue(f.override.isArmed)
        f.replaceDocument("")
        assertFalse(f.override.isArmed)
        assertEquals("M", f.type("m").text)
    }

    @Test fun aPasteOverTheCapitalRemovesItAPasteBeforeItShiftsIt() {
        val f = Field()
        f.type("md is")
        f.select(0, 2).edit(0, 2, "hello world")             // a phrase: not a word insertion
        assertEquals("hello world is", f.text)
        assertEquals(-1, f.override.capital)
        assertEquals(0, f.override.armedAt)
        val g = Field()
        g.type("md is")
        g.caret(0).edit(0, 0, "hello world ")
        assertEquals("hello world Md is", g.text)
        assertEquals(12, g.override.capital)
        assertFalse(g.override.isArmed)
    }
}
