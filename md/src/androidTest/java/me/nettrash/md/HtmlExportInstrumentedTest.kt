/*
 * HtmlExportInstrumentedTest.kt
 * md (Android)
 *
 * Export as HTML, on a real WebView. Two things can only be checked on a
 * device, and both of them are the whole point of the feature:
 *
 *  1. **The capture channel.** The finished page comes back out of the DOM
 *     as one string through `WebView.evaluateJavascript`, JSON-encoded. A
 *     rich document is a few hundred KB of it and a big one a few MB, so
 *     "does that channel actually carry a whole document" is a question
 *     about Chromium's IPC, not about md — [evaluateJavascriptCarriesAWholeDocument]
 *     measures where it stops, and [exportOfABigRichDocumentIsComplete]
 *     puts a genuinely large document through the real export path.
 *
 *  2. **Self-containment.** The exported file is loaded back from `file://`
 *     in a WebView with NO asset scheme handler registered at all — so
 *     anything still reaching for `rich/` could not possibly resolve — and
 *     the loaded DOM is audited: standards mode, diagrams present as inline
 *     SVG, formulas present as KaTeX markup in a KaTeX face, no scripts, no
 *     stylesheet links, and not one occurrence of the string `rich/`.
 *
 * Run with:
 *   ./gradlew :md:connectedDebugAndroidTest
 */

package me.nettrash.md

import android.annotation.SuppressLint
import android.content.Context
import android.util.Log
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import me.nettrash.md.ui.Exporter
import org.json.JSONObject
import org.json.JSONTokener
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

private const val TAG = "mdHtmlExport"

@RunWith(AndroidJUnit4::class)
class HtmlExportInstrumentedTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context: Context get() = instrumentation.targetContext

    /** Run [block] on the main thread; WebView work may happen nowhere else. */
    private fun onMain(block: () -> Unit) = instrumentation.runOnMainSync(block)

    // ---- 1. the capture channel ----------------------------------------

    /**
     * How big a string `evaluateJavascript` will actually hand back. The
     * export capture is one such string — `documentElement.outerHTML` — so
     * a silent ceiling here would mean silently truncated (or empty)
     * exports on long documents.
     *
     * Measured on a Pixel 8 Pro (Android 17, WebView 150.0.7871.124): every
     * size below comes back byte-exact, up to and including **32 MB** — a
     * couple of orders of magnitude past the biggest page the app can
     * produce (the bundled example set five times over captures at 860 KB,
     * see [exportOfABigRichDocumentIsComplete]). That is why the export
     * reads the page straight off `evaluateJavascript` and not through the
     * `window.MdRenderBridge` JavaScript interface `rich/md-init.js`
     * already pokes: the interface would buy nothing and would put an
     * `@JavascriptInterface` method inside every export WebView.
     */
    @SuppressLint("SetJavaScriptEnabled")
    @Test fun evaluateJavascriptCarriesAWholeDocument() {
        val sizes = listOf(64 * 1024, 1 shl 20, 4 shl 20, 8 shl 20, 16 shl 20, 32 shl 20)
        lateinit var webView: WebView
        onMain {
            webView = WebView(context)
            webView.settings.javaScriptEnabled = true
        }
        val loaded = CountDownLatch(1)
        onMain {
            webView.webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView, url: String?) = loaded.countDown()
            }
            webView.loadUrl("about:blank")
        }
        assertTrue("about:blank never finished", loaded.await(30, TimeUnit.SECONDS))

        for (size in sizes) {
            var result: String? = null
            val done = CountDownLatch(1)
            onMain {
                // A distinct first and last character, so truncation at
                // either end is visible rather than merely plausible.
                webView.evaluateJavascript("'A' + 'x'.repeat(${size - 2}) + 'Z'") { value ->
                    result = value
                    done.countDown()
                }
            }
            assertTrue("no callback for $size bytes", done.await(120, TimeUnit.SECONDS))
            val decoded = runCatching { JSONTokener(result ?: "null").nextValue() as? String }.getOrNull()
            Log.i(TAG, "evaluateJavascript $size B -> ${decoded?.length ?: -1} chars")
            assertEquals("$size bytes came back short", size, decoded?.length ?: -1)
            assertTrue("$size bytes lost its ends", decoded!!.first() == 'A' && decoded.last() == 'Z')
        }
        onMain { webView.destroy() }
    }

    // ---- 2. a real, big, rich document ---------------------------------

    /**
     * The whole bundled example set repeated five times — 40 documents, ~30
     * diagrams (Mermaid, Graphviz and PlantUML) and a page of math — put
     * through the real `Exporter.renderHtml`, then loaded back with no
     * scheme handler and audited. The single biggest document the app can
     * realistically be handed, and the one that would break first if the
     * capture channel had a ceiling.
     */
    @Test fun exportOfABigRichDocumentIsComplete() {
        val source = buildString {
            repeat(5) {
                for (name in context.assets.list("examples").orEmpty().filter { it.endsWith(".md") }.sorted()) {
                    append(context.assets.open("examples/$name").bufferedReader().use { it.readText() })
                    append("\n\n")
                }
            }
        }
        Log.i(TAG, "big document: ${source.length} chars of Markdown")
        val bytes = export(source, "Big")
        Log.i(TAG, "big document exported: ${bytes.size} bytes")

        val audit = auditSelfContained(bytes, "big")
        // Diagrams became inline SVG and formulas became KaTeX markup — the
        // engines ran before the capture, so none of them has to run again.
        assertTrue("no inline SVG survived the capture", audit.getInt("svgs") >= 25)
        assertTrue("no KaTeX markup survived the capture", audit.getInt("katex") > 0)
    }

    /**
     * The two shapes the export has to get right, side by side: a rich
     * document carries its fonts, a plain one does not.
     */
    @Test fun aPlainDocumentDoesNotCarryTheFontPayload() {
        val rich = export(
            """
            # Rich

            Inline \(e^{i\pi} + 1 = 0\) and display:

            ${'$'}${'$'}\int_0^1 x^2\,dx = \tfrac{1}{3}${'$'}${'$'}

            ```dot
            digraph { a -> b; b -> c; }
            ```

            ```mermaid
            flowchart LR
              A --> B
            ```

            | one | two |
            | --- | --- |
            | 1   | 2   |

            A footnote.[^1]

            [^1]: The note.
            """.trimIndent(),
            "Rich",
        )
        val plain = export("# Plain\n\nJust prose, no math at all.\n", "Plain")

        val richAudit = auditSelfContained(rich, "rich")
        val plainAudit = auditSelfContained(plain, "plain")

        assertEquals(2, richAudit.getInt("svgs"))
        // One inline formula and one display formula, both already expanded
        // into KaTeX markup by the time the page was captured.
        assertEquals(2, richAudit.getInt("katex"))
        // A formula is positioned by KaTeX's stylesheet, not just glyphed —
        // it has to have come across with the file.
        assertTrue(
            "math is not in a KaTeX face: ${richAudit.getString("mathFont")}",
            richAudit.getString("mathFont").contains("KaTeX"),
        )

        assertEquals(0, plainAudit.getInt("svgs"))
        assertEquals(0, plainAudit.getInt("katex"))
        // The font payload rides along only when it is needed.
        assertTrue("a plain export should be tiny, was ${plain.size} B", plain.size < 32 * 1024)
        assertTrue("a rich export should carry the fonts, was ${rich.size} B", rich.size > 200 * 1024)
        Log.i(TAG, "rich ${rich.size} B / plain ${plain.size} B")
    }

    // ---- helpers -------------------------------------------------------

    /** The real export path, run to completion. */
    private fun export(source: String, title: String): ByteArray {
        var bytes: ByteArray? = null
        val done = CountDownLatch(1)
        onMain {
            Exporter.renderHtml(context, source, title, dark = false) { result ->
                bytes = result
                done.countDown()
            }
        }
        assertTrue("$title never finished rendering", done.await(5, TimeUnit.MINUTES))
        return checkNotNull(bytes) { "$title exported nothing" }
    }

    /**
     * Write [bytes] out, load them back from `file://` in a WebView with no
     * scheme handler registered at all, and audit the DOM that comes up.
     * Everything checkable off the DOM is asserted here; the caller adds
     * what is specific to its document.
     */
    @SuppressLint("SetJavaScriptEnabled")
    private fun auditSelfContained(bytes: ByteArray, label: String): JSONObject {
        // Not one reference to the engines may survive — the exported file
        // has no folder beside it and no scheme handler behind it.
        val text = bytes.toString(Charsets.UTF_8)
        assertEquals("$label: the file still reaches for rich/", 0, text.split("rich/").size - 1)
        assertTrue("$label: no doctype", text.startsWith("<!DOCTYPE html>\n"))
        assertTrue("$label: truncated capture", text.trimEnd().endsWith("</html>"))

        val file = File(context.cacheDir, "export-audit-$label.html").apply { writeBytes(bytes) }
        lateinit var webView: WebView
        val loaded = CountDownLatch(1)
        onMain {
            webView = WebView(context)
            webView.settings.javaScriptEnabled = true
            webView.settings.allowFileAccess = true   // read the export back
            webView.webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView, url: String?) = loaded.countDown()
            }
            webView.loadUrl("file://${file.absolutePath}")
        }
        assertTrue("$label: the export never loaded", loaded.await(60, TimeUnit.SECONDS))

        var raw: String? = null
        val audited = CountDownLatch(1)
        onMain {
            webView.evaluateJavascript(
                """
                (function () {
                  var math = document.querySelector('.katex .mord');
                  return JSON.stringify({
                    doctype: document.doctype ? document.doctype.name : '',
                    compatMode: document.compatMode,
                    scripts: document.querySelectorAll('script').length,
                    links: document.querySelectorAll('link').length,
                    svgs: document.querySelectorAll('svg').length,
                    katex: document.querySelectorAll('.katex').length,
                    mathi: document.querySelectorAll('.md-mathi').length,
                    mathd: document.querySelectorAll('.md-mathd').length,
                    mathFont: math ? getComputedStyle(math).fontFamily : '',
                    complete: document.documentElement.getAttribute('data-md-render-complete') || ''
                  });
                })()
                """.trimIndent(),
            ) { value ->
                raw = runCatching { JSONTokener(value ?: "null").nextValue() as? String }.getOrNull()
                audited.countDown()
            }
        }
        assertTrue("$label: the audit never ran", audited.await(60, TimeUnit.SECONDS))
        onMain { webView.destroy() }

        val audit = JSONObject(checkNotNull(raw) { "$label: the audit returned nothing" })
        Log.i(TAG, "$label (${bytes.size} B): $audit")

        assertEquals("$label: no doctype in the loaded DOM", "html", audit.getString("doctype"))
        assertEquals("$label: quirks mode", "CSS1Compat", audit.getString("compatMode"))
        assertEquals("$label: a script survived", 0, audit.getInt("scripts"))
        assertEquals("$label: a stylesheet link survived", 0, audit.getInt("links"))
        assertEquals("$label: a stale render-complete flag", "", audit.getString("complete"))
        return audit
    }
}
