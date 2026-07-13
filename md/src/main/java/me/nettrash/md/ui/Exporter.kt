/*
 * Exporter.kt
 * md (Android)
 *
 * The document's output paths: Share (the raw Markdown source), Share /
 * Export as PDF and Print — all real A4 pages, paginated line-aware by the
 * print engine, with the author's `\newpage` markers cutting pages. Mirrors
 * the iOS DocumentExport. The pages are plain white with the light ink
 * regardless of the app's theme: paper tint and dark mode are screen
 * themes (see MarkdownHtml.css).
 *
 * Everything renders through an offscreen WebView, the same way the iOS app
 * renders through WKWebView.
 */

package me.nettrash.md.ui

import android.annotation.SuppressLint
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.print.PdfPrinter
import android.print.PrintAttributes
import android.print.PrintManager
import android.view.View
import android.webkit.WebView
import android.widget.Toast
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.nettrash.md.markdown.MarkdownHtml
import java.io.File

object Exporter {

    /** Keeps print WebViews alive until their print job has been created;
     *  the PrintDocumentAdapter reads from the WebView asynchronously. */
    private val livePrintViews = mutableListOf<WebView>()

    /** Keeps PDF-rendering WebViews alive until their document has been
     *  drawn into the PDF; mirrors [livePrintViews]. */
    private val livePdfViews = mutableListOf<WebView>()

    /** A4 width at 72 dpi, in PostScript points — the viewport width the
     *  offscreen WebViews lay content out against, matching the iOS /
     *  macOS export. */
    private const val PAGE_WIDTH_PT = 595

    /** Scheduling for the offscreen WebViews. A detached View's own
     *  post/postDelayed just parks runnables until the view attaches to a
     *  window — which these offscreen views never do — so schedule on the
     *  main looper directly. */
    private val handler = Handler(Looper.getMainLooper())

    /** Scope for the A4 export's file plumbing: the temp file and its
     *  read-back run on Dispatchers.IO, everything WebView on Main. */
    private val exportScope = MainScope()

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
     *  framework, which paginates it to A4 for real paper — the same pages
     *  [sharePdf] / [renderPdf] produce as a file.
     *
     *  Served through [MdAssetWebViewClient] so the rich renderers (math /
     *  Mermaid / PlantUML, all offline) resolve, and the print job is created
     *  only once md-init.js flags `data-md-render-complete` — otherwise the
     *  captured page would show the raw source, not the rendered diagrams. */
    @SuppressLint("SetJavaScriptEnabled")
    fun printRendered(context: Context, source: String, title: String, dark: Boolean) {
        val html = MarkdownHtml.document(source, title, dark, export = true)
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
     *  and math/Mermaid settle almost immediately. Internal because
     *  EpubExporter's image captures wait on the same flag. */
    internal fun waitForRenderComplete(view: WebView, attempt: Int, done: () -> Unit) {
        // PlantUML renders sequentially, up to ~20s per Graphviz diagram, so a
        // document with several slow diagrams needs a generous cap.
        val maxAttempts = 480 // ~120s at 0.25s each
        view.evaluateJavascript("document.documentElement.getAttribute('data-md-render-complete')") { value ->
            if (value == "\"1\"" || attempt >= maxAttempts) {
                done()
            } else {
                handler.postDelayed({ waitForRenderComplete(view, attempt + 1, done) }, 250)
            }
        }
    }

    /** Render the document to PDF bytes and hand them to [done] on the main
     *  thread (null if rendering failed). Every PDF the app makes — Share
     *  Rendered PDF and Export as PDF, documents and compiled books alike —
     *  comes through here: real A4 pages, paginated line-aware by the
     *  print engine ([renderA4Pdf]), exactly what printing produces. */
    fun renderPdf(context: Context, source: String, title: String, dark: Boolean, done: (ByteArray?) -> Unit) {
        renderA4Pdf(context, source, title, dark, done)
    }

    /** Render the document to a **real A4-paginated PDF** with line-aware
     *  page breaks (`\newpage` honored through the export CSS's
     *  `break-after: page`) and hand the bytes to [done] on the main thread
     *  (null if rendering failed). An offscreen WebView laid out at the
     *  export width, captured through the WebView print pipeline —
     *  [captureA4Pdf] drives the print adapter by hand, no print dialog. */
    @SuppressLint("SetJavaScriptEnabled")
    private fun renderA4Pdf(context: Context, source: String, title: String, dark: Boolean, done: (ByteArray?) -> Unit) {
        val html = MarkdownHtml.document(source, title, dark, export = true)
        val widthPx = (PAGE_WIDTH_PT * context.resources.displayMetrics.density).toInt()
        val webView = WebView(context)
        webView.settings.javaScriptEnabled = true
        // Lay out at the export width BEFORE loading — the print engine does
        // its own pagination layout, but the diagram engines' text
        // measurement still wants a real viewport.
        webView.measure(
            View.MeasureSpec.makeMeasureSpec(widthPx, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(widthPx * 842 / PAGE_WIDTH_PT, View.MeasureSpec.EXACTLY),
        )
        webView.layout(0, 0, webView.measuredWidth, webView.measuredHeight)
        webView.webViewClient = object : MdAssetWebViewClient(context.assets, { html }) {
            override fun onPageFinished(view: WebView, url: String?) {
                waitForRenderComplete(view, 0) { captureA4Pdf(context, view, title, done) }
            }
        }
        livePdfViews.add(webView)
        webView.loadUrl(MdAssetWebViewClient.INDEX_URL)
    }

    /** Print the fully rendered [view] to A4 PDF bytes: the WebView's own
     *  print adapter, driven manually through onLayout/onWrite (see
     *  PdfPrinter for why that class lives in `package android.print`) into
     *  a temp file under `cacheDir/exports`. The temp-file setup and
     *  read-back run on Dispatchers.IO; the adapter is created and driven
     *  from Main (WebView rule), and the print framework's callbacks are
     *  re-posted to Main before the view is touched. */
    private fun captureA4Pdf(context: Context, view: WebView, title: String, done: (ByteArray?) -> Unit) {
        val adapter = view.createPrintDocumentAdapter("md – $title")
        val attributes = PrintAttributes.Builder()
            .setMediaSize(PrintAttributes.MediaSize.ISO_A4)
            .setResolution(PrintAttributes.Resolution("pdf", "PDF", 300, 300))
            // Half an inch, in mils. Real page margins on every page —
            // NO_MARGINS would leave middle pages touching the paper edge,
            // since the body's CSS padding only wraps the whole document.
            .setMinMargins(PrintAttributes.Margins(500, 500, 500, 500))
            .build()
        exportScope.launch {
            val target = withContext(Dispatchers.IO) {
                runCatching {
                    val dir = File(context.cacheDir, "exports").apply { mkdirs() }
                    val file = File.createTempFile("a4-", ".pdf", dir)
                    file to ParcelFileDescriptor.open(
                        file,
                        ParcelFileDescriptor.MODE_READ_WRITE or ParcelFileDescriptor.MODE_TRUNCATE,
                    )
                }.getOrNull()
            }
            if (target == null) {
                livePdfViews.remove(view)
                view.destroy()
                done(null)
                return@launch
            }
            val (file, descriptor) = target
            PdfPrinter.writeToPdf(adapter, attributes, descriptor) { written ->
                // The print framework may call back off the main thread;
                // exportScope is Main-dispatched, so this hops back before
                // the WebView is touched.
                exportScope.launch {
                    val bytes = withContext(Dispatchers.IO) {
                        runCatching { descriptor.close() }
                        val result = if (written) runCatching { file.readBytes() }.getOrNull() else null
                        file.delete()
                        result
                    }
                    livePdfViews.remove(view)
                    view.destroy()
                    done(bytes)
                }
            }
        }
    }

    /** Render the document to a PDF and offer
     *  it through the system share sheet. The file is staged under
     *  `cacheDir/exports` and exposed to the chosen app via FileProvider
     *  with a one-off read grant. Failures surface as a toast — silently
     *  doing nothing after a long render would read as a dead menu item. */
    fun sharePdf(context: Context, source: String, title: String, dark: Boolean) {
        renderPdf(context, source, title, dark) { bytes ->
            val shared = bytes != null && runCatching {
                val dir = File(context.cacheDir, "exports").apply { mkdirs() }
                val file = File(dir, "${sanitized(title)}.pdf").apply { writeBytes(bytes) }
                val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
                val send = Intent(Intent.ACTION_SEND).apply {
                    type = "application/pdf"
                    putExtra(Intent.EXTRA_STREAM, uri)
                    putExtra(Intent.EXTRA_SUBJECT, title)
                    // The chooser propagates the grant from the ClipData too,
                    // covering targets that only look there.
                    clipData = ClipData.newRawUri(null, uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                context.startActivity(Intent.createChooser(send, "Share PDF").apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                })
            }.isSuccess
            if (!shared) {
                Toast.makeText(context, "Couldn't create the PDF.", Toast.LENGTH_LONG).show()
            }
        }
    }

    /** Make a string safe to use as a file name (mirrors the iOS helper). */
    private fun sanitized(name: String): String {
        val cleaned = name.split('/', '\\', ':', '?', '%', '*', '|', '"', '<', '>')
            .joinToString("-").trim()
        return cleaned.ifEmpty { "Document" }
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
        handler.post { livePrintViews.remove(view) }
    }
}
