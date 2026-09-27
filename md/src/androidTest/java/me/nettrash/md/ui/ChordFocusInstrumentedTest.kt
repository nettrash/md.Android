/*
 * ChordFocusInstrumentedTest.kt
 * md (Android)
 *
 * The chords, pressed where a writer actually presses them: with the caret in
 * the editor's REAL text field, so the focused field has its say first.
 *
 * `ShortcutsTest` pins the table (ui/Shortcuts.kt) on the JVM and says which
 * command a chord names; it cannot say whether the press ever reaches it.
 * That is decided by Compose, and for two of md's twelve rows the answer used
 * to be no: Compose Foundation's Android key mapping reads Alt+Up as HOME and
 * Alt+Down as END *without consulting Ctrl* (`KeyMapping.android.kt`, and its
 * platform map is tried before the common one, whose `isCtrlPressed -> null`
 * branch would have let the press through). The focused field therefore
 * claimed Ctrl+Alt+Up / Ctrl+Alt+Down, moved the caret to offset 0 or to the
 * end of the document, and reported the press consumed — so the Activity's
 * leftovers handler, which only ever sees what the view hierarchy did not
 * want, was never asked, and Previous / Next Article did nothing at all.
 *
 * The Compose test framework dispatches straight into the AndroidComposeView
 * (`AndroidInputDispatcher`), which is exactly the path these two rows lose,
 * so this suite is the one that can see the difference.
 *
 * Every chord below runs on a brand-new untitled document with no book
 * adopted, so Previous / Next Article are deliberate no-ops: what is asserted
 * is that the press was md's — the caret does not move — and never the
 * field's.
 *
 * Run with (emulator only; see the workspace rules):
 *   adb -s emulator-5554 shell am instrument -w \
 *     -e class me.nettrash.md.ui.ChordFocusInstrumentedTest \
 *     me.nettrash.md.test/androidx.test.runner.AndroidJUnitRunner
 */

package me.nettrash.md.ui

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextInputSelection
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.withKeyDown
import androidx.compose.ui.text.TextRange
import androidx.test.ext.junit.runners.AndroidJUnit4
import me.nettrash.md.MainActivity
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class ChordFocusInstrumentedTest {

    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    private fun field() = compose.onNode(hasSetTextAction())

    /** Keystrokes, one scalar per commit — as a hardware keyboard sends them. */
    private fun type(s: String) {
        var i = 0
        while (i < s.length) {
            val n = Character.charCount(s.codePointAt(i))
            field().performTextInput(s.substring(i, i + n))
            i += n
        }
    }

    private fun caret(at: Int) = field().performTextInputSelection(TextRange(at))

    private fun selection(): TextRange {
        compose.waitForIdle()
        return field().fetchSemanticsNode().config[SemanticsProperties.TextSelectionRange]
    }

    private fun text(): String {
        compose.waitForIdle()
        return field().fetchSemanticsNode().config[SemanticsProperties.EditableText].text
    }

    private fun ctrlAlt(key: Key) = field().performKeyInput {
        withKeyDown(Key.CtrlLeft) { withKeyDown(Key.AltLeft) { pressKey(key) } }
    }

    private fun ctrl(key: Key) = field().performKeyInput {
        withKeyDown(Key.CtrlLeft) { pressKey(key) }
    }

    /** "alpha beta", capitalized by smart typing — ten characters either way. */
    private fun sentence(): String {
        type("alpha beta")
        assertEquals(10, text().length)
        return text()
    }

    // ---- The two rows the focused field used to claim ---------------------

    @Test fun nextArticleIsMdsChordAndNotTheFieldsEndKey() {
        sentence()
        caret(3)
        ctrlAlt(Key.DirectionDown)
        // With no book adopted, Next Article has nowhere to go and does
        // nothing — which is the point: the caret must not have been dragged
        // to the end of the document by the field's END command instead.
        assertEquals(TextRange(3), selection())
    }

    @Test fun previousArticleIsMdsChordAndNotTheFieldsHomeKey() {
        sentence()
        caret(3)
        ctrlAlt(Key.DirectionUp)
        assertEquals(TextRange(3), selection())
    }

    @Test fun neitherChordEditsTheDocument() {
        val before = sentence()
        caret(3)
        ctrlAlt(Key.DirectionDown)
        ctrlAlt(Key.DirectionUp)
        assertEquals(before, text())
    }

    // ---- The control: the keys the field owns are still the field's -------

    @Test fun theFieldKeepsSelectAll() {
        sentence()
        caret(3)
        ctrl(Key.A)
        assertEquals(TextRange(0, 10), selection())
    }

    @Test fun theFieldKeepsAltArrowOnItsOwn() {
        // Alt+Up / Alt+Down without Ctrl are the field's Home and End, and md
        // claims no row for them: taking the chord in the preview pass must
        // not take these with it.
        sentence()
        caret(3)
        field().performKeyInput { withKeyDown(Key.AltLeft) { pressKey(Key.DirectionDown) } }
        assertEquals(TextRange(10), selection())
        caret(3)
        field().performKeyInput { withKeyDown(Key.AltLeft) { pressKey(Key.DirectionUp) } }
        assertEquals(TextRange(0), selection())
    }

    @Test fun theFieldKeepsPlainArrowKeys() {
        sentence()
        caret(3)
        field().performKeyInput { pressKey(Key.DirectionRight) }
        assertEquals(TextRange(4), selection())
        field().performKeyInput { pressKey(Key.DirectionLeft) }
        assertEquals(TextRange(3), selection())
    }

    /** Ctrl+F from the editor opens the find bar and types nothing — on every
     *  supported Android, 12 included (added 2026-09-27 with minSdk 31). */
    @Test fun ctrlFFromTheEditorOpensFindAndTypesNothing() {
        val before = sentence()
        ctrl(Key.F)
        compose.waitForIdle()
        assertEquals(
            "the find bar's Next match button",
            1,
            compose.onAllNodesWithContentDescription("Next match").fetchSemanticsNodes().size,
        )
        // The bar brings two fields of its own; the document is still one of
        // the three, unchanged — no "f" was typed into it.
        val texts = compose.onAllNodes(hasSetTextAction()).fetchSemanticsNodes()
            .map { it.config[SemanticsProperties.EditableText].text }
        assertEquals("Ctrl+F must not reach the text: $texts", true, before in texts)
    }
}
