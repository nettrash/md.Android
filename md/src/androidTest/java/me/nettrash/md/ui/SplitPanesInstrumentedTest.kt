/*
 * SplitPanesInstrumentedTest.kt
 * md (Android)
 *
 * Split's side-by-side layout gives each pane half the window. The divider
 * between them used to be a `HorizontalDivider` with
 * `fillMaxSize().widthIn(max = 1.dp)`: a Row measures unweighted children
 * first, so that divider took the Row's whole width and both panes were
 * measured at 0 px — Split on a tablet, a foldable or a phone in landscape
 * showed an empty window under the app bar. No unit test could see it; the
 * layout has to be measured.
 *
 * Run with (emulator only; see the workspace rules):
 *   adb -s emulator-5554 shell am instrument -w \
 *     -e class me.nettrash.md.ui.SplitPanesInstrumentedTest \
 *     me.nettrash.md.test/androidx.test.runner.AndroidJUnitRunner
 */

package me.nettrash.md.ui

import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import me.nettrash.md.MainActivity
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SplitPanesInstrumentedTest {

    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    /** Replaces MainActivity's screen with just the panes. The rule's own
     *  `setContent` refuses an activity that already has content, and
     *  MainActivity sets its screen in onCreate; ComponentActivity.setContent
     *  reuses that ComposeView instead. */
    private fun host(sideBySide: Boolean) {
        compose.runOnUiThread {
            compose.activity.setContent {
                // Small enough to fit the window in either orientation.
                Box(Modifier.size(360.dp, 240.dp)) {
                    SplitPanes(
                        sideBySide = sideBySide,
                        first = { Box(it.testTag("first")) },
                        second = { Box(it.testTag("second")) },
                    )
                }
            }
        }
        compose.waitForIdle()
    }

    @Test fun sideBySideGivesEachPaneHalfTheWidth() {
        host(sideBySide = true)
        for (tag in listOf("first", "second")) {
            val bounds = compose.onNodeWithTag(tag).getUnclippedBoundsInRoot()
            val width = bounds.right - bounds.left
            val height = bounds.bottom - bounds.top
            assertTrue("$tag pane is $width wide", width > 175.dp && width < 180.dp)
            assertTrue("$tag pane is $height tall", height > 239.dp && height < 241.dp)
        }
    }

    @Test fun stackedGivesEachPaneHalfTheHeight() {
        host(sideBySide = false)
        for (tag in listOf("first", "second")) {
            val bounds = compose.onNodeWithTag(tag).getUnclippedBoundsInRoot()
            val width = bounds.right - bounds.left
            val height = bounds.bottom - bounds.top
            assertTrue("$tag pane is $width wide", width > 359.dp && width < 361.dp)
            assertTrue("$tag pane is $height tall", height > 115.dp && height < 120.dp)
        }
    }
}
