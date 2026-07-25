/*
 * Exporter.kt
 * md (Android)
 *
 * The document's output paths: Share (the raw Markdown source), Share /
 * Export as PDF, Export as HTML, Export as LaTeX and Print. The PDF/print
 * pages are real A4, paginated line-aware by the print engine, with the
 * author's `\newpage` markers cutting pages. Mirrors the iOS DocumentExport.
 * The pages are plain white with the light ink regardless of the app's theme:
 * paper tint and dark mode are screen themes (see MarkdownHtml.css).
 *
 * Everything but the LaTeX export renders through an offscreen WebView, the
 * same way the iOS app renders through WKWebView; `.tex` is pure string work
 * (markdown/LaTeXExport.kt) and needs no engine at all.
 */

package me.nettrash.md.ui

import android.annotation.SuppressLint
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
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
import me.nettrash.md.TextBundle
import me.nettrash.md.book.BookContent
import me.nettrash.md.markdown.DiagramSvg
import me.nettrash.md.markdown.HtmlExport
import me.nettrash.md.markdown.LaTeXExport
import me.nettrash.md.markdown.MarkdownHtml
import org.json.JSONTokener
import java.io.File
import kotlin.math.roundToInt

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

    /** Render the document to PDF bytes at [pageSize] and hand them to [done]
     *  on the main thread (null if rendering failed). Every PDF the app makes —
     *  Share Rendered PDF and Export as PDF, documents and compiled books
     *  alike — comes through here: real pages of the chosen trim size,
     *  paginated line-aware by the print engine ([renderTrimPdf]), exactly what
     *  printing produces. [pageSize] defaults to A4 (the historical page). */
    fun renderPdf(
        context: Context,
        source: String,
        title: String,
        dark: Boolean,
        pageSize: PageSize = PageSize.A4,
        done: (ByteArray?) -> Unit,
    ) {
        renderTrimPdf(context, source, title, dark, pageSize, done)
    }

    /** Render the document to a **real [pageSize]-paginated PDF** with
     *  line-aware page breaks (`\newpage` honored through the export CSS's
     *  `break-after: page`) and hand the bytes to [done] on the main thread
     *  (null if rendering failed). An offscreen WebView laid out at the
     *  export width, captured through the WebView print pipeline —
     *  [captureTrimPdf] drives the print adapter by hand, no print dialog. */
    @SuppressLint("SetJavaScriptEnabled")
    private fun renderTrimPdf(
        context: Context,
        source: String,
        title: String,
        dark: Boolean,
        pageSize: PageSize,
        done: (ByteArray?) -> Unit,
    ) {
        // Scale the body margin to the trim size (only the PDF/print export
        // path — the screen preview, HTML export and EPUB keep their own). For
        // A4 the rewrite is a no-op, so the default export is unchanged.
        val html = styledForExport(MarkdownHtml.document(source, title, dark, export = true), pageSize)
        val widthPx = (PAGE_WIDTH_PT * context.resources.displayMetrics.density).toInt()
        val webView = WebView(context)
        webView.settings.javaScriptEnabled = true
        // Lay out at the export width BEFORE loading — the print engine does
        // its own pagination layout (reflowing content to the media's printable
        // width, so a narrower trim size lays out narrower), but the diagram
        // engines' text measurement still wants a real viewport. The layout
        // width stays PAGE_WIDTH_PT for EVERY size, matching iOS: holding the
        // viewport constant keeps that text measurement identical whatever the
        // page, and the CSS margin (above) is what tracks the trim size.
        webView.measure(
            View.MeasureSpec.makeMeasureSpec(widthPx, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(widthPx * 842 / PAGE_WIDTH_PT, View.MeasureSpec.EXACTLY),
        )
        webView.layout(0, 0, webView.measuredWidth, webView.measuredHeight)
        webView.webViewClient = object : MdAssetWebViewClient(context.assets, { html }) {
            override fun onPageFinished(view: WebView, url: String?) {
                waitForRenderComplete(view, 0) { captureTrimPdf(context, view, title, pageSize, done) }
            }
        }
        livePdfViews.add(webView)
        webView.loadUrl(MdAssetWebViewClient.INDEX_URL)
    }

    /** Rewrite the export HTML's body margin to [pageSize]'s scaled padding, so
     *  a small trim size doesn't carry A4-sized margins. Only the FIRST
     *  `padding: 48px 56px;` is touched — the body rule in the head's `<style>`,
     *  which always precedes any user content, so a document that quotes that
     *  exact CSS in a code block is left alone. For A4 the replacement equals
     *  the original (its `cssPadding` IS "48px 56px"), so an A4 export is
     *  byte-for-byte what it was before trim sizes existed; a size the HTML
     *  doesn't carry the marker for leaves it untouched (`replaceFirst` no-ops). */
    private fun styledForExport(html: String, pageSize: PageSize): String =
        html.replaceFirst("padding: 48px 56px;", "padding: ${pageSize.cssPadding};")

    /** The print MediaSize for [pageSize]. A4 keeps the framework's own ISO_A4
     *  so the default export is byte-for-byte unchanged — a custom A4 built from
     *  the points would round to 8267 × 11692 mils, one mil off ISO_A4's
     *  8268 × 11693, enough to shift the PDF MediaBox. The trim sizes aren't
     *  standard MediaSizes, so each is built from its points: MediaSize takes
     *  mils (thousandths of an inch), and 1 pt = 1/72 in. */
    private fun mediaSize(pageSize: PageSize): PrintAttributes.MediaSize {
        if (pageSize.id == PageSize.A4.id) return PrintAttributes.MediaSize.ISO_A4
        val widthMils = (pageSize.width / 72.0 * 1000.0).roundToInt()
        val heightMils = (pageSize.height / 72.0 * 1000.0).roundToInt()
        return PrintAttributes.MediaSize(pageSize.id, pageSize.label, widthMils, heightMils)
    }

    /** Print the fully rendered [view] to [pageSize] PDF bytes: the WebView's
     *  own print adapter, driven manually through onLayout/onWrite (see
     *  PdfPrinter for why that class lives in `package android.print`) into
     *  a temp file under `cacheDir/exports`. The temp-file setup and
     *  read-back run on Dispatchers.IO; the adapter is created and driven
     *  from Main (WebView rule), and the print framework's callbacks are
     *  re-posted to Main before the view is touched. */
    private fun captureTrimPdf(context: Context, view: WebView, title: String, pageSize: PageSize, done: (ByteArray?) -> Unit) {
        val adapter = view.createPrintDocumentAdapter("md – $title")
        val attributes = PrintAttributes.Builder()
            .setMediaSize(mediaSize(pageSize))
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

    // ---- Self-contained HTML ------------------------------------------

    /** Keeps HTML-capture WebViews alive until their page has been read
     *  back out of the DOM; mirrors [livePdfViews]. */
    private val liveHtmlViews = mutableListOf<WebView>()

    /**
     * Render the document to **one self-contained HTML file** and hand the
     * bytes to [done] on the main thread (null if rendering failed). The
     * reader can open it anywhere — no engines, no folder of assets, no
     * network.
     *
     * Same offscreen WebView as the PDF path, waiting on the same
     * `data-md-render-complete` flag: by then Mermaid, Graphviz and PlantUML
     * are inline SVG and KaTeX has expanded its formulas, so the captured
     * page has nothing left to run and every script / engine stylesheet can
     * go (see [HtmlExport.CAPTURE_SCRIPT]). The one thing that has to be
     * carried across by hand is KaTeX's own stylesheet, and only for a
     * document that actually has math in it.
     */
    @SuppressLint("SetJavaScriptEnabled")
    fun renderHtml(context: Context, source: String, title: String, dark: Boolean, done: (ByteArray?) -> Unit) {
        // `export = true` gives the page its paper styling; the one rule not
        // wanted here is the page break, which on paper collapses to an
        // invisible `break-after: page` a scrolling reader would never see.
        val html = HtmlExport.visiblePageBreaks(
            MarkdownHtml.document(source, title, dark, export = true),
        )
        val widthPx = (PAGE_WIDTH_PT * context.resources.displayMetrics.density).toInt()
        val webView = WebView(context)
        webView.settings.javaScriptEnabled = true
        // Lay out at the export width BEFORE loading, as in renderTrimPdf —
        // the diagram engines' text measurement wants a real viewport.
        webView.measure(
            View.MeasureSpec.makeMeasureSpec(widthPx, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(widthPx * 842 / PAGE_WIDTH_PT, View.MeasureSpec.EXACTLY),
        )
        webView.layout(0, 0, webView.measuredWidth, webView.measuredHeight)
        webView.webViewClient = object : MdAssetWebViewClient(context.assets, { html }) {
            override fun onPageFinished(view: WebView, url: String?) {
                waitForRenderComplete(view, 0) { captureHtml(context, view, html, done) }
            }
        }
        liveHtmlViews.add(webView)
        webView.loadUrl(MdAssetWebViewClient.INDEX_URL)
    }

    /**
     * Read the finished page out of [view]'s DOM, prepend the doctype and —
     * for a document with math — inline KaTeX's stylesheet with its fonts.
     *
     * The capture comes back through `evaluateJavascript`, whose result is a
     * JSON-encoded string — the one platform risk worth settling rather than
     * assuming, since a page is a single multi-megabyte value. Measured on a
     * Pixel 8 Pro (Android 17, WebView 150): the channel returns a 32 MB
     * string byte-exact, while the whole bundled example set five times over
     * — 65,700 characters of Markdown, 35 diagrams — captures at 859,742
     * bytes. Two orders of magnitude of headroom, so the export reads the
     * page straight off the callback rather than through the
     * `window.MdRenderBridge` interface `rich/md-init.js` already pokes;
     * that would buy nothing and would put an `@JavascriptInterface` method
     * inside every export WebView. HtmlExportInstrumentedTest pins both
     * numbers.
     *
     * The base64 font work is real CPU, so it happens on Dispatchers.Default;
     * the WebView is only ever touched from Main.
     */
    private fun captureHtml(context: Context, view: WebView, html: String, done: (ByteArray?) -> Unit) {
        view.evaluateJavascript(HtmlExport.CAPTURE_SCRIPT) { value ->
            // evaluateJavascript hands back the JSON encoding of the result;
            // `null` (a failed or too-large evaluation) must not be written
            // out as the four characters "null".
            val markup = runCatching { JSONTokener(value ?: "null").nextValue() as? String }
                .getOrNull()
            exportScope.launch {
                val bytes = if (markup.isNullOrEmpty()) null else withContext(Dispatchers.Default) {
                    runCatching {
                        var page = HtmlExport.DOCTYPE + markup
                        if (HtmlExport.needsKatex(html)) {
                            HtmlExport.embeddedKatexCss { path -> readAsset(context, path) }
                                ?.let { css -> page = HtmlExport.withEmbeddedKatex(page, css) }
                        }
                        // Mermaid's own stylesheet travels inside every
                        // diagram it drew, so its notice travels too.
                        page = HtmlExport.withMermaidNotice(page)
                        page.toByteArray(Charsets.UTF_8)
                    }.getOrNull()
                }
                liveHtmlViews.remove(view)
                view.destroy()
                done(bytes)
            }
        }
    }

    /** One bundled asset's bytes, or null when it isn't there. */
    private fun readAsset(context: Context, path: String): ByteArray? =
        runCatching { context.assets.open(path).use { it.readBytes() } }.getOrNull()

    // ---- Diagram → standalone SVG (Feature 1) -------------------------

    /** Keeps SVG-capture WebViews alive until their diagram has been read
     *  back out of the DOM; mirrors [liveHtmlViews]. */
    private val liveSvgViews = mutableListOf<WebView>()

    /**
     * Render the document offscreen (engines and all, waiting for the same
     * `data-md-render-complete` flag the PDF / HTML / EPUB paths do), pull the
     * chosen diagram's rendered `<svg>` out of the finished DOM as a real vector
     * (not a rasterised snapshot), wrap it as a standalone `.svg`, and hand the
     * bytes to [done] on the main thread — null when the diagram produced no
     * vector (its engine hit a syntax error or timed out, so md-init.js left the
     * block as source text with no `<svg>`), so the caller can toast instead of
     * writing a blank file.
     *
     * [ordinal] is the diagram's 0-based document-order position among
     * `pre.mermaid, div.plantuml, div.graphviz` — the diagram half of the
     * selector [EpubExporter] captures, and exactly the index
     * `DiagramSvg.diagrams` assigns, so the menu row and the DOM node pair up.
     * `dark = false`: a `.svg` carries no screen theme (Mermaid bakes its own
     * colours into the SVG; Graphviz/PlantUML draw explicit ink). `export = true`
     * only to keep the same page the other captures use.
     */
    @SuppressLint("SetJavaScriptEnabled")
    fun renderDiagramSvg(
        context: Context,
        source: String,
        title: String,
        ordinal: Int,
        done: (ByteArray?) -> Unit,
    ) {
        val html = MarkdownHtml.document(source, title, dark = false, export = true)
        val widthPx = (PAGE_WIDTH_PT * context.resources.displayMetrics.density).toInt()
        val webView = WebView(context)
        webView.settings.javaScriptEnabled = true
        // Lay out at the export width BEFORE loading, as in renderHtml — the
        // diagram engines' text measurement wants a real viewport.
        webView.measure(
            View.MeasureSpec.makeMeasureSpec(widthPx, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(widthPx * 842 / PAGE_WIDTH_PT, View.MeasureSpec.EXACTLY),
        )
        webView.layout(0, 0, webView.measuredWidth, webView.measuredHeight)
        webView.webViewClient = object : MdAssetWebViewClient(context.assets, { html }) {
            override fun onPageFinished(view: WebView, url: String?) {
                waitForRenderComplete(view, 0) { captureDiagramSvg(view, ordinal, done) }
            }
        }
        liveSvgViews.add(webView)
        webView.loadUrl(MdAssetWebViewClient.INDEX_URL)
    }

    /** Read the chosen diagram's rendered `<svg>` outerHTML out of [view]'s DOM
     *  and turn it into a standalone `.svg`. The query is the diagram half of
     *  the EPUB selector, indexed by [ordinal]; a container with no `<svg>`
     *  (a failed / timed-out engine) comes back null and the export surfaces an
     *  error rather than writing an empty file. The result arrives as a
     *  JSON-encoded string through `evaluateJavascript` (as in [captureHtml]),
     *  so `null` must not be written out as the four characters "null". The
     *  string fix-up is trivial CPU but runs off Main anyway, next to the WebView
     *  teardown discipline the other captures use. */
    private fun captureDiagramSvg(view: WebView, ordinal: Int, done: (ByteArray?) -> Unit) {
        val script =
            "(function(){var n=document.querySelectorAll('pre.mermaid, div.plantuml, div.graphviz');" +
                "var e=n[$ordinal];if(!e)return null;var s=e.querySelector('svg');" +
                "return s?s.outerHTML:null;})()"
        view.evaluateJavascript(script) { value ->
            val svg = runCatching { JSONTokener(value ?: "null").nextValue() as? String }.getOrNull()
            exportScope.launch {
                val bytes = if (svg.isNullOrEmpty()) null else withContext(Dispatchers.Default) {
                    runCatching { DiagramSvg.standaloneDocument(svg).toByteArray(Charsets.UTF_8) }.getOrNull()
                }
                liveSvgViews.remove(view)
                view.destroy()
                done(bytes)
            }
        }
    }

    // ---- TextBundle (Feature 2) ---------------------------------------

    /** Write the current document to [target] as a `.textpack` (a zipped
     *  TextBundle). Local images the Markdown references by relative path are
     *  copied into `assets/` and their refs rewritten (see
     *  `TextBundle.exportRewriting`); refs that can't be found beside the source
     *  are left exactly as written. Failures surface as a toast — a silently
     *  dead menu item would read as a broken app.
     *
     *  No WebView and only a handful of small reads, so — like the LaTeX export
     *  — it runs to completion synchronously on the caller's thread. */
    fun exportTextPack(context: Context, target: Uri, source: String, docUri: Uri?, title: String) {
        val (rewritten, assets) = TextBundle.exportRewriting(source) { relativePath ->
            readLocalAsset(docUri, relativePath)
        }
        val entries = TextBundle.bundleEntries(sanitized(title), rewritten, assets)
        val bytes = TextBundle.pack(entries)
        val written = runCatching {
            val stream = runCatching { context.contentResolver.openOutputStream(target, "wt") }
                .getOrNull() ?: context.contentResolver.openOutputStream(target)
            stream!!.use { it.write(bytes) }
        }.isSuccess
        if (!written) {
            Toast.makeText(context, "Couldn't export the TextPack.", Toast.LENGTH_LONG).show()
        }
    }

    /** Read an image the document references by relative path, if it sits beside
     *  the document and md can reach it. The resolved path is constrained to the
     *  document's own folder — a `../…` ref that would climb out is treated as
     *  not found (and so left untouched), the same containment the iOS resolver
     *  and the preview scheme handler enforce.
     *
     *  PLATFORM LIMIT — this only works for a `file://` document. A SAF
     *  `content://` document (the common case: anything opened through the
     *  document picker) cannot enumerate its siblings without a whole-tree grant
     *  the view model does not hold, so its refs simply resolve to null and are
     *  left exactly as the author wrote them — the same honest outcome an unsaved
     *  document gets on every platform (empty `assets/`, nothing deleted). The
     *  pure rewrite is identical to iOS regardless; only what the resolver can
     *  reach differs. */
    private fun readLocalAsset(docUri: Uri?, relativePath: String): ByteArray? {
        val uri = docUri ?: return null
        if (uri.scheme != "file") return null
        val folder = File(uri.path ?: return null).parentFile ?: return null
        val base = runCatching { folder.canonicalFile }.getOrNull() ?: return null
        val candidate = runCatching { File(base, relativePath).canonicalFile }.getOrNull() ?: return null
        if (candidate.path != base.path && !candidate.path.startsWith(base.path + File.separator)) return null
        return candidate.takeIf { it.isFile }?.let { runCatching { it.readBytes() }.getOrNull() }
    }

    // ---- LaTeX --------------------------------------------------------

    /** Write the document to [target] as LaTeX source — the one export
     *  where the author's mathematics survives as editable mathematics
     *  rather than a picture of it (see markdown/LaTeXExport.kt).
     *
     *  No WebView, nothing to wait for and so no callback: by the time
     *  this returns the file is written or the failure has been toasted.
     *  A `.tex` file is prose-sized, so the write stays on the caller's
     *  thread like the other exports' final write does. */
    fun exportLaTeX(context: Context, target: Uri, source: String) {
        writeTex(context, target, LaTeXExport.document(source))
    }

    /** Write the whole book to [target] as one `book`-class .tex file, in
     *  the same reading order the compiled PDF and the EPUB use. */
    fun exportBookLaTeX(context: Context, target: Uri, content: BookContent) {
        writeTex(context, target, LaTeXExport.book(content))
    }

    private fun writeTex(context: Context, target: Uri, tex: String) {
        val written = runCatching {
            // The same "wt"-then-"w" fallback the PDF and HTML exports use:
            // "wt" truncates, but not every documents provider supports it.
            val stream = runCatching { context.contentResolver.openOutputStream(target, "wt") }
                .getOrNull() ?: context.contentResolver.openOutputStream(target)
            stream!!.use { it.write(tex.toByteArray(Charsets.UTF_8)) }
        }.isSuccess
        if (!written) {
            // Don't leave the freshly created, empty .tex silently in place.
            Toast.makeText(context, "Couldn't export the LaTeX.", Toast.LENGTH_LONG).show()
        }
    }

    /** Render the document to a PDF and offer
     *  it through the system share sheet. The file is staged under
     *  `cacheDir/exports` and exposed to the chosen app via FileProvider
     *  with a one-off read grant. Failures surface as a toast — silently
     *  doing nothing after a long render would read as a dead menu item. */
    fun sharePdf(context: Context, source: String, title: String, dark: Boolean, pageSize: PageSize = PageSize.A4) {
        renderPdf(context, source, title, dark, pageSize) { bytes ->
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
