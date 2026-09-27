/*
 * PreviewRenderBridgeInstrumentedTest.kt
 * md (Android)
 *
 * The wiring, end to end: the bundled `rich/md-init.js`, running in the real
 * preview WebView, has to reach `PreviewState.renderCompleted()` through the
 * `MdRenderBridge` JavaScript interface. The script has always poked
 * `window.MdRenderBridge.onRenderComplete()` — until 1.5 nothing in the
 * preview registered that name, so the call was a no-op and the retry
 * policy was fed from `onPageFinished` instead, which fires before the
 * diagram engines run. No unit test can see whether a JavaScript interface
 * is registered; the page has to say so.
 *
 * The same case as the iOS port's
 * `testTheBundledInitScriptReportsItsRenderToThePreview`.
 *
 * Run with (emulator only; see the workspace rules):
 *   adb -s emulator-5554 shell am instrument -w \
 *     -e class me.nettrash.md.ui.PreviewRenderBridgeInstrumentedTest \
 *     me.nettrash.md.debug.test/androidx.test.runner.AndroidJUnitRunner
 */

package me.nettrash.md.ui

import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import me.nettrash.md.MainActivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PreviewRenderBridgeInstrumentedTest {

    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun theBundledInitScriptReportsItsRenderToThePreview() {
        val state = PreviewState()
        // A death before the page is up — the count that only a completed
        // render may clear.
        assertEquals(PreviewRetryPolicy.Action.RELOAD, state.processTerminated())
        assertEquals(1, state.retry.failures)
        assertEquals(0, state.renderCompletions)

        // Replaces MainActivity's screen with just the preview (see
        // SplitPanesInstrumentedTest for why setContent is called this way).
        compose.runOnUiThread {
            compose.activity.setContent {
                Box(Modifier.size(360.dp, 240.dp)) {
                    // A plain heading pulls in no engine at all, so md-init.js
                    // settles as soon as the page loads; the wait is slack,
                    // not a duration.
                    RichPreview(text = "# Plain", title = "Plain", modifier = Modifier.fillMaxSize(), state = state)
                }
            }
        }
        compose.waitUntil(timeoutMillis = 30_000) { state.renderCompletions > 0 }

        // And it is the render, not the navigation, that ends a run of
        // failures — the page itself said so.
        assertEquals(0, state.retry.failures)
        assertFalse(state.retry.hasGivenUp)
    }
}
