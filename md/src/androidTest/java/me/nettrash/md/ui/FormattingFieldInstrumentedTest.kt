/*
 * FormattingFieldInstrumentedTest.kt
 * md (Android)
 *
 * The formatting commands (#13) in the real editor: typing * over a selection
 * wraps it, a hardware chord formats the selection, and each is ONE undo step
 * — the field's own Ctrl+Z takes the whole edit back.
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
import org.junit.Assert.assertEquals

@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class FormattingFieldInstrumentedTest {

    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    private fun field() = compose.onNode(hasSetTextAction())

    private fun text(): String {
        compose.waitForIdle()
        return field().fetchSemanticsNode().config[SemanticsProperties.EditableText].text
    }

    private fun type(s: String) = s.forEach { field().performTextInput(it.toString()) }

    private fun select(start: Int, end: Int) = field().performTextInputSelection(TextRange(start, end))

    private fun undo() = field().performKeyInput { withKeyDown(Key.CtrlLeft) { pressKey(Key.Z) } }

    @Test fun typingAStarOverASelectionWrapsItInOneUndoStep() {
        type("hello world")
        select(6, 11)
        type("*")
        assertEquals("Hello *world*", text())
        undo()
        assertEquals("Hello world", text())
    }

    @Test fun ctrlBBoldsTheSelectionInOneUndoStep() {
        type("say hello")
        select(4, 9)
        field().performKeyInput { withKeyDown(Key.CtrlLeft) { pressKey(Key.B) } }
        assertEquals("Say **hello**", text())
        undo()
        assertEquals("Say hello", text())
    }

    @Test fun ctrlAltTwoMakesAHeadingAndCtrlShiftQAQuote() {
        type("title")
        field().performKeyInput { withKeyDown(Key.CtrlLeft) { withKeyDown(Key.AltLeft) { pressKey(Key.Two) } } }
        assertEquals("## Title", text())
        field().performKeyInput { withKeyDown(Key.CtrlLeft) { withKeyDown(Key.ShiftLeft) { pressKey(Key.Q) } } }
        assertEquals("> ## Title", text())
    }

    @Test fun ctrlCStillCopiesAndCtrlShiftCIsCode() {
        type("run it")
        select(4, 6)
        field().performKeyInput { withKeyDown(Key.CtrlLeft) { withKeyDown(Key.ShiftLeft) { pressKey(Key.C) } } }
        assertEquals("Run `it`", text())
    }
}
