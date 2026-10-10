/*
 * PlantUmlLargeDiagramInstrumentedTest.kt
 * md (Android)
 *
 * The 4096 px gate patch (#9) measured in the WebView the app actually uses.
 * A 48-participant sequence diagram lays out past 7000 px wide: unpatched,
 * the engine replaced it with "Diagram too large for browser rendering"; it
 * now renders as an SVG of its own size. A three-participant control renders
 * as it always did.
 */

package me.nettrash.md.ui

import android.webkit.WebView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import me.nettrash.md.markdown.MarkdownHtml
import org.json.JSONArray
import org.json.JSONTokener
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class PlantUmlLargeDiagramInstrumentedTest {

    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()

    /** A detached WebView nothing refers to can be collected mid-load and
     *  never finish — EpubExporter keeps its own in `liveViews` for exactly
     *  this reason. */
    private var live: WebView? = null

    private fun sequence(participants: Int): String = buildString {
        append("```plantuml\n@startuml\n")
        for (i in 1..participants) append("participant \"Participant $i\" as P$i\n")
        for (i in 1 until participants) append("P$i -> P${i + 1} : step $i\n")
        append("@enduml\n```\n")
    }

    /** Render [markdown] in an offscreen WebView and return the PlantUML
     *  block's [svg width, svg height, block text]. */
    private fun render(markdown: String): Triple<Double, Double, String> {
        val html = MarkdownHtml.document(markdown, "t", dark = false)
        val latch = CountDownLatch(1)
        var result = ""
        instrumentation.runOnMainSync {
            val context = instrumentation.targetContext
            val view = WebView(context).also { live = it }
            view.settings.javaScriptEnabled = true
            view.layout(0, 0, 1080, 2000)
            view.webViewClient = object : MdAssetWebViewClient(context.assets, { html }) {
                override fun onPageFinished(view: WebView, url: String?) {
                    Exporter.waitForRenderComplete(view, 0) {
                        view.evaluateJavascript(
                            "(() => { const b = document.querySelector('div.plantuml'); const s = b && b.querySelector('svg');" +
                                " const box = s && s.viewBox && s.viewBox.baseVal;" +
                                " return JSON.stringify([box ? box.width : 0, box ? box.height : 0, b ? b.textContent.slice(0, 300) : '']); })()",
                        ) { value ->
                            result = JSONTokener(value).nextValue() as String
                            view.destroy()
                            live = null
                            latch.countDown()
                        }
                    }
                }
            }
            view.loadUrl(MdAssetWebViewClient.INDEX_URL)
        }
        assertTrue("rendered within the export ceiling", latch.await(150, TimeUnit.SECONDS))
        val array = JSONArray(result)
        return Triple(array.getDouble(0), array.getDouble(1), array.getString(2))
    }

    @Test fun aFortyEightParticipantSequenceRenders() {
        val (width, height, text) = render(sequence(48))
        assertFalse(text, text.contains("too large"))
        assertTrue("width $width", width > 4096)
        assertTrue("height $height", height > 0)
    }

    @Test fun aSmallDiagramIsUnchanged() {
        val (width, _, text) = render(sequence(3))
        assertFalse(text.contains("too large"))
        assertTrue("width $width", width in 100.0..1000.0)
        assertEquals(false, text.contains("Exception"))
    }

    @Test fun anEpubOfALargeDiagramCarriesItsSnapshot() {
        // The one path that rasterises: the snapshot is bounded by
        // CaptureBudget, and the export still lands with the image in it.
        val latch = CountDownLatch(1)
        var bytes: ByteArray? = null
        instrumentation.runOnMainSync {
            EpubExporter.exportDocument(instrumentation.targetContext, "# Big\n\n" + sequence(120), "Big.md", null) {
                bytes = it
                latch.countDown()
            }
        }
        assertTrue(latch.await(200, TimeUnit.SECONDS))
        val epub = checkNotNull(bytes) { "the export failed" }
        val images = java.util.zip.ZipInputStream(epub.inputStream()).use { zip ->
            generateSequence { zip.nextEntry }.filter { it.name.endsWith(".png") }.map { it.name to zip.readBytes().size }.toList()
        }
        assertEquals(1, images.size)
        assertTrue("${images.single().second} bytes", images.single().second > 1_000)
    }
}
