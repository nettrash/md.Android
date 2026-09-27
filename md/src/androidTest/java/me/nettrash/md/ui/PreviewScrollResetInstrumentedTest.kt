/*
 * PreviewScrollResetInstrumentedTest.kt
 * md (Android)
 *
 * A document opened while the preview is on screen starts at the top. The
 * preview pane — and its WebView — outlives a document change, and the page
 * was reloaded with the OLD page's scroll position restored (explicitly, and
 * natively by `reload()`), so a file or example opened while the reader was
 * deep in another came up scrolled into its middle or its end. Found by
 * driving the real app: Diagrams read to the end, then Plots opened on its
 * last paragraph.
 *
 * Driven through the real screen, because the defect is the pane surviving
 * the change; the scroll offset is read out of the page itself.
 *
 * Run with (emulator only; see the workspace rules):
 *   adb -s emulator-5554 shell am instrument -w \
 *     -e class me.nettrash.md.ui.PreviewScrollResetInstrumentedTest \
 *     me.nettrash.md.test/androidx.test.runner.AndroidJUnitRunner
 */

package me.nettrash.md.ui

import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import me.nettrash.md.MainActivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class PreviewScrollResetInstrumentedTest {

    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    private fun findWebView(view: View): WebView? {
        if (view is WebView) return view
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) findWebView(view.getChildAt(i))?.let { return it }
        }
        return null
    }

    /** Runs [script] in the preview's page and returns its result. */
    private fun js(script: String): String? {
        val latch = CountDownLatch(1)
        var result: String? = null
        compose.runOnUiThread {
            val webView = findWebView(compose.activity.window.decorView)
            if (webView == null) latch.countDown()
            else webView.evaluateJavascript(script) { result = it; latch.countDown() }
        }
        latch.await(5, TimeUnit.SECONDS)
        return result
    }

    /** Waits until the page shows [text] and has stopped growing (the
     *  diagram engines lay out after the page loads). */
    private fun awaitPage(text: String) {
        val deadline = System.currentTimeMillis() + 20_000
        var lastHeight = -1
        var stable = 0
        while (System.currentTimeMillis() < deadline) {
            val shows = js("document.body && document.body.innerText.indexOf(${quote(text)}) >= 0") == "true"
            val height = js("document.documentElement.scrollHeight")?.toIntOrNull() ?: -1
            if (shows && height == lastHeight) stable++ else stable = 0
            if (stable >= 3) return
            lastHeight = height
            Thread.sleep(300)
        }
        throw AssertionError("the preview never settled on \"$text\"")
    }

    private fun quote(s: String) = "'" + s.replace("'", "\\'") + "'"

    private fun scrollY(): Int = js("window.scrollY")?.toFloatOrNull()?.toInt() ?: -1

    private fun openExample(title: String) {
        compose.onNodeWithContentDescription("More").performClick()
        compose.onNodeWithText("Examples").performClick()
        compose.onNodeWithText(title).performClick()
        compose.waitForIdle()
        // An example is an unsaved copy, so replacing one asks first.
        val guard = compose.onAllNodes(hasText("Discard"))
            .fetchSemanticsNodes()
        if (guard.isNotEmpty()) compose.onNodeWithText("Discard").performClick()
        compose.waitForIdle()
    }

    private fun showPreview() {
        compose.onNodeWithContentDescription("Preview").performClick()
        compose.waitForIdle()
    }

    @Test fun aDocumentOpenedInThePreviewStartsAtTheTop() {
        openExample("Diagrams")
        showPreview()
        awaitPage("Diagram files")
        js("window.scrollTo(0, document.documentElement.scrollHeight)")
        Thread.sleep(500)
        assertTrue("the first document scrolled down", scrollY() > 1000)

        // The preview stays on screen while the next document replaces this one.
        openExample("Plots")
        awaitPage("Because a plot is just a formula")
        Thread.sleep(1000)
        assertEquals("the new document's scroll offset", 0, scrollY())
    }
}
