/*
 * FindNudgeInstrumentedTest.kt
 * md (Android)
 *
 * The find bar's navigation nudge, over its whole life: searching from
 * Preview brings the editor on screen "for as long as you are searching"
 * (`findTapMode`, and the CHANGELOG's own words), so closing the bar has to
 * put the reader back where they were.
 *
 * `ViewModeTest` pins `findTapMode` itself — a pure function that says which
 * mode the nudge is — but the screen is what raises the nudge and the screen
 * is what has to drop it, and closing the bar used to clear only `findOpen`.
 * The reader was left in Edit on a file whose remembered mode was Preview,
 * with the mode switch showing a pane they never picked. Nothing was written
 * to the per-file memory, so the only thing stranded was the reader.
 *
 * Driven through the real screen because that is where the defect lived: a
 * pure function cannot be asked what the X button does. The document is the
 * new, empty, untitled one MainActivity launches with, so the editor pane is
 * recognisable by its placeholder and Preview shows no text field at all.
 *
 * Run with (emulator only; see the workspace rules):
 *   adb -s emulator-5554 shell am instrument -w \
 *     -e class me.nettrash.md.ui.FindNudgeInstrumentedTest \
 *     me.nettrash.md.test/androidx.test.runner.AndroidJUnitRunner
 */

package me.nettrash.md.ui

import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import me.nettrash.md.MainActivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class FindNudgeInstrumentedTest {

    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    /** Whether the editor pane is on screen: its placeholder is drawn only
     *  there, and only for the empty document this test opens with. */
    private fun editorOnScreen(): Boolean {
        compose.waitForIdle()
        return compose
            .onAllNodesWithText("# Start writing…", useUnmergedTree = true)
            .fetchSemanticsNodes()
            .isNotEmpty()
    }

    /** Every editable box on screen: the editor's field, plus the find bar's
     *  Find and Replace boxes while the bar is up. */
    private fun editableBoxes(): Int {
        compose.waitForIdle()
        return compose.onAllNodes(hasSetTextAction()).fetchSemanticsNodes().size
    }

    private fun showPreview() = compose.onNodeWithContentDescription("Preview").performClick()

    /** The top bar's Find button — since the ⋮ menu was regrouped
     *  (2026-09-27) the one touch route to the find bar. */
    private fun openFind() = compose.onNodeWithContentDescription("Find").performClick()

    private fun closeFind() = compose.onNodeWithContentDescription("Close find bar").performClick()

    @Test fun closingTheFindBarGivesThePreviewBack() {
        // An empty document opens in Edit; the reader moves to Preview.
        assertTrue(editorOnScreen())
        showPreview()
        assertFalse(editorOnScreen())
        assertEquals(0, editableBoxes())

        // Find nudges the editor on screen — a match is shown by selecting it
        // there, so it has to be visible.
        openFind()
        assertTrue(editorOnScreen())
        assertEquals(3, editableBoxes())   // the editor, Find, Replace

        // ...and closing the bar is the end of the search, so the nudge goes
        // with it and the reader is back in Preview.
        closeFind()
        assertFalse(editorOnScreen())
        assertEquals(0, editableBoxes())
    }

    @Test fun closingTheFindBarLeavesAReaderInEditAloneAndSearchableAgain() {
        // The other half: the bar opened from Edit raises no nudge, so
        // closing it must not move anybody either — and Find still works the
        // second time.
        assertTrue(editorOnScreen())
        openFind()
        assertTrue(editorOnScreen())
        closeFind()
        assertTrue(editorOnScreen())
        assertEquals(1, editableBoxes())

        showPreview()
        openFind()
        assertTrue(editorOnScreen())
        closeFind()
        assertFalse(editorOnScreen())
    }
}
