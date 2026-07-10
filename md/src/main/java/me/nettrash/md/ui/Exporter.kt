/*
 * Exporter.kt
 * md (Android)
 *
 * The document's output paths: Share (the raw Markdown source), Share /
 * Export as PDF (a single content-tall page by default, or real A4 pages
 * when the "PDF layout" setting says so — see PdfLayout) and Print
 * (paginated for real paper). Mirrors the iOS DocumentExport.
 *
 * Everything renders through an offscreen WebView, the same way the iOS app
 * renders through WKWebView — WebKit honors the full typewriter CSS,
 * including the paper background, in the printout and the PDFs alike.
 */

package me.nettrash.md.ui

import android.annotation.SuppressLint
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.graphics.pdf.PdfDocument
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
import org.json.JSONObject
import org.json.JSONTokener
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.math.ceil

object Exporter {

    /** Keeps print WebViews alive until their print job has been created;
     *  the PrintDocumentAdapter reads from the WebView asynchronously. */
    private val livePrintViews = mutableListOf<WebView>()

    /** Keeps PDF-rendering WebViews alive until their document has been
     *  drawn into the PDF; mirrors [livePrintViews]. */
    private val livePdfViews = mutableListOf<WebView>()

    /** A4 width at 72 dpi, in PostScript points. The exported PDF keeps this
     *  width — the same page width as the iOS / macOS export — and grows
     *  into a single page as tall as the content (see [renderPdf]). */
    private const val PAGE_WIDTH_PT = 595

    /** The largest page dimension the PDF format allows — 200 inches at
     *  72 dpi. A document that renders taller is scaled down uniformly to
     *  fit, so the export stays one uncut page (see [captureLaidOutPdf]). */
    private const val MAX_PAGE_PT = 14_400

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
     *  framework, which paginates it to A4 for real paper. (For a PDF, use
     *  [sharePdf] / [renderPdf] — a single content-tall page with no line
     *  sliced at a page boundary.)
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
     *  thread (null if rendering failed). Which shape depends on the user's
     *  persistent [PdfLayout] setting: one content-tall page per `\newpage`
     *  section (the default — [renderSinglePdf]) or real A4 pages, paginated
     *  line-aware by the print engine ([renderA4Pdf]). Share Rendered PDF
     *  and Export as PDF (documents and compiled books alike) all come
     *  through here, so the setting covers every PDF the app makes. */
    fun renderPdf(context: Context, source: String, title: String, dark: Boolean, done: (ByteArray?) -> Unit) {
        when (PdfLayout.load(context)) {
            PdfLayout.SINGLE -> renderSinglePdf(context, source, title, dark, done)
            PdfLayout.A4 -> renderA4Pdf(context, source, title, dark, done)
        }
    }

    /** Render the document to a **single-page PDF exactly as tall as the
     *  content** — the whole document as it appears in the preview, with no
     *  line sliced at an A4 boundary (unlike [printRendered], which paginates
     *  for real paper) — and hand the bytes to [done] on the main thread
     *  (null if rendering failed).
     *
     *  The WebView is laid out 595 CSS px wide — the same viewport width as
     *  the iOS / macOS export, since with `initial-scale=1` a CSS px is a dp —
     *  then, once md-init.js flags the rich renderers complete, re-measured to
     *  its full content height and drawn into one [PdfDocument] page. Drawing
     *  the whole document (not just the visible tiles) requires
     *  `WebView.enableSlowWholeDocumentDraw()`, set in MainActivity. */
    @SuppressLint("SetJavaScriptEnabled")
    private fun renderSinglePdf(context: Context, source: String, title: String, dark: Boolean, done: (ByteArray?) -> Unit) {
        val html = MarkdownHtml.document(source, title, dark, export = true)
        val widthPx = (PAGE_WIDTH_PT * context.resources.displayMetrics.density).toInt()
        val webView = WebView(context)
        webView.settings.javaScriptEnabled = true
        // Lay out at the final width BEFORE loading, so the CSS (and the
        // diagram engines' text measurement) see the real viewport.
        webView.measure(
            View.MeasureSpec.makeMeasureSpec(widthPx, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(widthPx * 842 / PAGE_WIDTH_PT, View.MeasureSpec.EXACTLY),
        )
        webView.layout(0, 0, webView.measuredWidth, webView.measuredHeight)
        webView.webViewClient = object : MdAssetWebViewClient(context.assets, { html }) {
            override fun onPageFinished(view: WebView, url: String?) {
                waitForRenderComplete(view, 0) { capturePdf(view, widthPx, done) }
            }
        }
        livePdfViews.add(webView)
        webView.loadUrl(MdAssetWebViewClient.INDEX_URL)
    }

    private fun capturePdf(view: WebView, widthPx: Int, done: (ByteArray?) -> Unit) {
        // Read the full document height AND the author's `\newpage` cut
        // positions from the DOM in one round-trip (like the iOS export)
        // rather than re-measuring the view: a detached WebView's own
        // measured content height only refreshes on a compositor commit,
        // which a never-attached view may not have produced yet — the DOM
        // is authoritative. CSS px == dp here (`initial-scale=1`).
        view.evaluateJavascript(
            "JSON.stringify({h: document.documentElement.scrollHeight," +
                " pt: parseFloat(getComputedStyle(document.body).paddingTop) || 0," +
                " pb: parseFloat(getComputedStyle(document.body).paddingBottom) || 0," +
                " cuts: Array.from(document.querySelectorAll('.md-pagebreak'))" +
                ".map(e => e.getBoundingClientRect().top + window.scrollY)})",
        ) { value ->
            val density = view.resources.displayMetrics.density
            var cssHeight = 0f
            val cutsPx = ArrayList<Int>()
            runCatching {
                // evaluateJavascript hands back the *JSON-quoted* string —
                // unquote it first, then parse the payload.
                val unquoted = JSONTokener(value ?: "null").nextValue() as? String ?: return@runCatching
                val json = JSONObject(unquoted)
                cssHeight = json.optDouble("h", 0.0).toFloat()
                val padTop = json.optDouble("pt", 0.0)
                val padBottom = json.optDouble("pb", 0.0)
                val cuts = json.optJSONArray("cuts")
                if (cuts != null) {
                    for (index in 0 until cuts.length()) {
                        // A cut inside the body's top / bottom padding means
                        // the marker is the first / last thing in the
                        // document — snap it to the edge so the segment rule
                        // collapses it instead of emitting a padding-only
                        // sliver page.
                        var cut = cuts.optDouble(index, 0.0)
                        cut = when {
                            cut <= padTop + 1 -> 0.0
                            cut >= cssHeight - padBottom - 1 -> cssHeight.toDouble()
                            else -> cut
                        }
                        cutsPx.add(ceil(cut * density).toInt())
                    }
                }
            }
            val heightPx = ceil(cssHeight * density).toInt()
                .coerceAtLeast(widthPx * 842 / PAGE_WIDTH_PT) // at least one A4 page
            captureLaidOutPdf(view, widthPx, heightPx, cutsPx, done)
        }
    }

    private fun captureLaidOutPdf(
        view: WebView,
        widthPx: Int,
        heightPx: Int,
        cutsPx: List<Int>,
        done: (ByteArray?) -> Unit,
    ) {
        view.measure(
            View.MeasureSpec.makeMeasureSpec(widthPx, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(heightPx, View.MeasureSpec.EXACTLY),
        )
        view.layout(0, 0, view.measuredWidth, view.measuredHeight)
        // Give the WebView a beat to repaint at the grown size before drawing.
        handler.postDelayed({
            val bytes = runCatching {
                // Scale device px back to points so the page is 595 pt wide
                // regardless of screen density; text stays vector either way.
                var scale = PAGE_WIDTH_PT.toFloat() / view.measuredWidth
                var widthPt = PAGE_WIDTH_PT
                val total = view.measuredHeight
                // The author's `\newpage` cuts split the document into
                // sections; each becomes its own content-tall page.
                // Consecutive / edge markers collapse into nothing rather
                // than emitting empty pages.
                val segments = ArrayList<Pair<Int, Int>>() // top to height, px
                var top = 0
                for (cut in cutsPx.map { it.coerceIn(0, total) }.sorted() + total) {
                    if (cut - top >= 2) segments.add(top to (cut - top))
                    top = maxOf(top, cut)
                }
                if (segments.isEmpty()) segments.add(0 to total)
                // A page taller than the PDF format's cap shrinks uniformly —
                // judged per page, so only a document with an oversize
                // section triggers it. Nothing is sliced either way.
                val tallestPt = ceil(segments.maxOf { it.second } * scale).toInt()
                if (tallestPt > MAX_PAGE_PT) {
                    val fit = MAX_PAGE_PT.toFloat() / tallestPt
                    scale *= fit
                    widthPt = ceil(PAGE_WIDTH_PT * fit).toInt().coerceAtLeast(1)
                }
                val pdf = PdfDocument()
                try {
                    segments.forEachIndexed { index, (segTop, segHeight) ->
                        val heightPt = ceil(segHeight * scale).toInt()
                            .coerceIn(1, MAX_PAGE_PT)
                        val page = pdf.startPage(
                            PdfDocument.PageInfo.Builder(widthPt, heightPt, index + 1).create(),
                        )
                        page.canvas.scale(scale, scale)
                        page.canvas.translate(0f, -segTop.toFloat())
                        view.draw(page.canvas)
                        pdf.finishPage(page)
                    }
                    ByteArrayOutputStream().also { pdf.writeTo(it) }.toByteArray()
                } finally {
                    pdf.close()
                }
            }.getOrNull()
            livePdfViews.remove(view)
            view.destroy()
            done(bytes)
        }, 250)
    }

    /** Render the document to a **real A4-paginated PDF** with line-aware
     *  page breaks (`\newpage` honored through the export CSS's
     *  `break-after: page`) and hand the bytes to [done] on the main thread
     *  (null if rendering failed). The same offscreen-WebView setup as
     *  [renderSinglePdf], but captured through the WebView print pipeline —
     *  [captureA4Pdf] drives the print adapter by hand, no print dialog. */
    @SuppressLint("SetJavaScriptEnabled")
    private fun renderA4Pdf(context: Context, source: String, title: String, dark: Boolean, done: (ByteArray?) -> Unit) {
        val html = MarkdownHtml.document(source, title, dark, export = true)
        val widthPx = (PAGE_WIDTH_PT * context.resources.displayMetrics.density).toInt()
        val webView = WebView(context)
        webView.settings.javaScriptEnabled = true
        // Lay out at the export width BEFORE loading — the print engine does
        // its own pagination layout, but the diagram engines' text
        // measurement still wants a real viewport (as in renderSinglePdf).
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

    /** Render the document to a PDF (per the [PdfLayout] setting) and offer
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
