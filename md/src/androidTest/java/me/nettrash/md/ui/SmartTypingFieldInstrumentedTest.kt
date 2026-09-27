/*
 * SmartTypingFieldInstrumentedTest.kt
 * md (Android)
 *
 * The eight consequences of SPEC §3.4 (the override), driven through the
 * editor's REAL text field: MainActivity as it launches — a new, empty
 * document, in Edit — and its BasicTextField(state = …) with the
 * smart-typing transformation attached, fed by the Compose test framework's
 * own input (commitText per keystroke, hardware Backspace, Ctrl+Z, Ctrl+X,
 * selection). The JVM suite (TypingTest) pins the same eight on the adapter
 * alone; this one proves the Compose shell feeds it every edit, and that
 * Compose's Undo — which runs no transformation — is learnt from the text.
 *
 * Run with (emulator only; see the workspace rules):
 *   adb -s emulator-5554 shell am instrument -w \
 *     -e class me.nettrash.md.ui.SmartTypingFieldInstrumentedTest \
 *     me.nettrash.md.test/androidx.test.runner.AndroidJUnitRunner
 */

package me.nettrash.md.ui

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createAndroidComposeRule
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
class SmartTypingFieldInstrumentedTest {

    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    private fun field() = compose.onNode(hasSetTextAction())

    /** The field's text, as its semantics report it. */
    private fun text(): String {
        compose.waitForIdle()
        return field().fetchSemanticsNode().config[SemanticsProperties.EditableText].text
    }

    /** Keystrokes: one scalar per commit, as a hardware key or a non-composing keyboard sends them. */
    private fun type(s: String) {
        var i = 0
        while (i < s.length) {
            val n = Character.charCount(s.codePointAt(i))
            field().performTextInput(s.substring(i, i + n))
            i += n
        }
    }

    private fun backspace(times: Int = 1) {
        repeat(times) { field().performKeyInput { pressKey(Key.Backspace) } }
    }

    private fun caret(at: Int) = field().performTextInputSelection(TextRange(at))

    private fun select(start: Int, end: Int) = field().performTextInputSelection(TextRange(start, end))

    private fun undo() = field().performKeyInput { withKeyDown(Key.CtrlLeft) { pressKey(Key.Z) } }

    private fun cut() = field().performKeyInput { withKeyDown(Key.CtrlLeft) { pressKey(Key.X) } }

    private fun paste() = field().performKeyInput { withKeyDown(Key.CtrlLeft) { pressKey(Key.V) } }

    private fun enter() = field().performKeyInput { pressKey(Key.Enter) }

    @Test fun c1_deleteTheCapitalAndTypeTheWordAgain() {
        type("md")
        assertEquals("Md", text())
        backspace(2)
        assertEquals("", text())
        type("md")
        assertEquals("md", text())
    }

    @Test fun c2_iOS() {
        type("iOS")
        assertEquals("IOS", text())
        backspace(3)
        assertEquals("", text())
        type("iOS")
        assertEquals("iOS", text())
    }

    @Test fun c3_fiveKeystrokesAfterTheCapitalAndFiveBackspaces() {
        type("md is")
        assertEquals("Md is", text())
        backspace(5)
        assertEquals("", text())
        type("md is")
        assertEquals("md is", text())
    }

    @Test fun c4_selectTheWholeWordAndRetype() {
        type("md")
        assertEquals("Md", text())
        select(0, 2)
        type("md")
        assertEquals("md", text())
    }

    @Test fun c5_typeBeforeTheCapital() {
        type("m")
        assertEquals("M", text())
        caret(0)
        type("a")
        assertEquals("AM", text())
    }

    @Test fun c6_undoRemovesTheLetterAndTheRetypeStaysLowercase() {
        // Android's recorded difference (§3.5): the capital is atomic with
        // the keystroke, so Undo removes the letter; Apple and Windows
        // restore the lowercase one. Either way the letter typed again at
        // that offset stays as typed.
        type("m")
        assertEquals("M", text())
        undo()
        assertEquals("", text())
        type("m")
        assertEquals("m", text())
        type("d")
        assertEquals("md", text())
    }

    @Test fun c7_offsetTrackingSurvivesACutBeforeTheCapital() {
        type("Xxx. m")
        assertEquals("Xxx. M", text())
        select(0, 5)
        cut()
        assertEquals("M", text())
        caret(1)
        backspace()
        assertEquals("", text())
        type("m")
        assertEquals("m", text())
    }

    @Test fun c8_deletingAfterTheCapitalArmsNothing() {
        type("md")
        assertEquals("Md", text())
        backspace()
        assertEquals("M", text())
        type("d")
        assertEquals("Md", text())
    }

    @Test fun aContinuedListItemIsOneUndoStepOfItsOwn() {
        // §3.5: every enter edit is one undo step. Compose's undo manager
        // merges contiguous insertions typed within five seconds into one
        // step and exempts only a bare "\n", so the continuation "\n- " is
        // recorded as a replacement — which never merges — and Undo takes
        // back the "b", then the continuation, then the item: never the item
        // together with what was typed around it.
        type("- a")
        assertEquals("- A", text())
        enter()
        assertEquals("- A\n- ", text())
        type("b")
        assertEquals("- A\n- B", text())
        undo()
        assertEquals("- A\n- ", text())
        undo()
        assertEquals("- A", text())
    }

    @Test fun aPlainNewlineIsOneUndoStepOfItsOwn() {
        // The control: Return outside a list is the platform's own "\n",
        // which Compose already keeps apart.
        type("a")
        assertEquals("A", text())
        enter()
        assertEquals("A\n", text())
        type("b")
        assertEquals("A\nB", text())
        undo()
        assertEquals("A\n", text())
        undo()
        assertEquals("A", text())
    }

    @Test fun aPasteBackAfterACutClearsTheOverride() {
        // Cut "Md", paste it back, put the caret before it and type "a": the
        // paste at the armed offset is not a word insertion, so it clears
        // the override, and the "a" is judged — (5), not a stale arm.
        type("md")
        assertEquals("Md", text())
        select(0, 2)
        cut()
        assertEquals("", text())
        paste()
        assertEquals("Md", text())
        caret(0)
        type("a")
        assertEquals("AMd", text())
    }

    @Test fun aWholeWordCommittedAtOnceIsCapitalizedAndRetypedAsTyped() {
        // Glide typing and a suggestion commit the word in one go.
        field().performTextInput("md ")
        assertEquals("Md ", text())
        backspace(3)
        field().performTextInput("md ")
        assertEquals("md ", text())
        field().performTextInput("is")
        assertEquals("md is", text())
    }
}
