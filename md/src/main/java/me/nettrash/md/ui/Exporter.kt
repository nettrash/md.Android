/*
 * Exporter.kt
 * md (Android)
 *
 * The document's output paths: Share (the raw Markdown source) and Print /
 * Save as PDF (the rendered document). Mirrors the iOS DocumentExport.
 *
 * Printing renders through an offscreen WebView, the same way the iOS app
 * renders through WKWebView — WebKit honors the full typewriter CSS,
 * including the paper background, in both the printout and Android's
 * "Save as PDF" target (which the system print dialog offers for free).
 */

package me.nettrash.md.ui

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.print.PrintAttributes
import android.print.PrintManager
import android.webkit.WebView
import me.nettrash.md.markdown.MarkdownHtml

object Exporter {

    /** Keeps print WebViews alive until their print job has been created;
     *  the PrintDocumentAdapter reads from the WebView asynchronously. */
    private val livePrintViews = mutableListOf<WebView>()

    /** Share the raw Markdown source through the system share sheet. */
    fun shareSource(context: Context, text: String, title: String) {
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/markdown"
            putExtra(Intent.EXTRA_TITLE, "$title.md")
            putExtra(Intent.EXTRA_SUBJECT, title)
            putExtra(Intent.EXTRA_TEXT, text)
        }
        context.startActivity(Intent.createChooser(send, "Share Markdown").apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        })
    }

    /** Render the document to themed HTML and hand it to Android's print
     *  framework. The system dialog includes a "Save as PDF" target, so
     *  this single action covers both printing and exporting a PDF.
     *
     *  Served through [MdAssetWebViewClient] so the rich renderers (math /
     *  Mermaid / PlantUML, all offline) resolve, and the print job is created
     *  only once md-init.js flags `data-md-render-complete` — otherwise the
     *  captured page would show the raw source, not the rendered diagrams. */
    @SuppressLint("SetJavaScriptEnabled")
    fun printRendered(context: Context, source: String, title: String, dark: Boolean) {
        val html = MarkdownHtml.document(source, title, dark)
        val webView = WebView(context)
        webView.settings.javaScriptEnabled = true
        webView.webViewClient = object : MdAssetWebViewClient(context.assets, { html }) {
            override fun onPageFinished(view: WebView, url: String?) {
                waitForRenderComplete(view, 0) { startPrint(context, view, title) }
            }
        }
        livePrintViews.add(webView)
        webView.loadUrl(MdAssetWebViewClient.INDEX_URL)
    }

    /** Poll until md-init.js flags the document fully rendered (or give up after
     *  a generous cap — Graphviz-backed PlantUML diagrams are slow). Plain docs
     *  and math/Mermaid settle almost immediately. */
    private fun waitForRenderComplete(view: WebView, attempt: Int, done: () -> Unit) {
        // PlantUML renders sequentially, up to ~20s per Graphviz diagram, so a
        // document with several slow diagrams needs a generous cap.
        val maxAttempts = 480 // ~120s at 0.25s each
        view.evaluateJavascript("document.documentElement.getAttribute('data-md-render-complete')") { value ->
            if (value == "\"1\"" || attempt >= maxAttempts) {
                done()
            } else {
                view.postDelayed({ waitForRenderComplete(view, attempt + 1, done) }, 250)
            }
        }
    }

    private fun startPrint(context: Context, view: WebView, title: String) {
        val printManager = context.getSystemService(Context.PRINT_SERVICE) as PrintManager
        val jobName = "md – $title"
        val adapter = view.createPrintDocumentAdapter(jobName)
        printManager.print(
            jobName,
            adapter,
            PrintAttributes.Builder().setMediaSize(PrintAttributes.MediaSize.ISO_A4).build(),
        )
        // The adapter now owns the rendering; drop our ref shortly.
        view.post { livePrintViews.remove(view) }
    }
}
