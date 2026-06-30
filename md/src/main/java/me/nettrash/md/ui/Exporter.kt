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

import android.content.Context
import android.content.Intent
import android.print.PrintAttributes
import android.print.PrintManager
import android.webkit.WebView
import android.webkit.WebViewClient
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
     *  this single action covers both printing and exporting a PDF. */
    fun printRendered(context: Context, source: String, title: String, dark: Boolean) {
        val html = MarkdownHtml.document(source, title, dark)
        val webView = WebView(context)
        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView, url: String?) {
                val printManager = context.getSystemService(Context.PRINT_SERVICE) as PrintManager
                val jobName = "md – $title"
                val adapter = view.createPrintDocumentAdapter(jobName)
                printManager.print(
                    jobName,
                    adapter,
                    PrintAttributes.Builder()
                        .setMediaSize(PrintAttributes.MediaSize.ISO_A4)
                        .build(),
                )
                // The adapter now owns the rendering; drop our ref shortly.
                view.post { livePrintViews.remove(view) }
            }
        }
        livePrintViews.add(webView)
        webView.loadDataWithBaseURL(null, html, "text/html", "UTF-8", null)
    }
}
