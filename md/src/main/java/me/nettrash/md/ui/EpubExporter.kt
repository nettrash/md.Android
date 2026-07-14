/*
 * EpubExporter.kt
 * md (Android)
 *
 * The impure half of Export as EPUB (the pure builders live in
 * book/Epub.kt): turns an already-read BookContent into EPUB bytes.
 * Article bodies come from MarkdownHtml.document() with the wrapper
 * stripped and the markup fixed to XHTML; math / Mermaid / PlantUML
 * appear as images — each rich article is rendered once in an offscreen
 * WebView (the same discipline as Exporter: detached view, pre-load
 * layout, render-complete polling on a main-looper Handler), every rich
 * element's rect is read from the DOM, and the element is drawn into its
 * own PNG. Articles without rich content never touch a WebView. Assembly
 * runs on Dispatchers.Default, PNG encoding and zipping on
 * Dispatchers.IO, all WebView work on Main.
 */

package me.nettrash.md.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Handler
import android.os.Looper
import android.view.View
import android.webkit.WebView
import androidx.core.graphics.createBitmap
import kotlin.coroutines.resume
import kotlin.math.ceil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import me.nettrash.md.book.BookContent
import me.nettrash.md.book.EpubBook
import me.nettrash.md.book.EpubChapter
import me.nettrash.md.book.EpubImage
import me.nettrash.md.book.EpubUnit
import me.nettrash.md.book.buildEpub
import me.nettrash.md.book.documentBody
import me.nettrash.md.book.escapeXml
import me.nettrash.md.book.findRichElements
import me.nettrash.md.book.replaceRichElements
import me.nettrash.md.book.toXhtml
import me.nettrash.md.markdown.MarkdownHtml
import org.json.JSONObject
import org.json.JSONTokener
import java.io.ByteArrayOutputStream

object EpubExporter {

    /** The export viewport width in CSS px — the same page width as the
     *  PDF export, so diagrams lay out identically. */
    private const val WIDTH_CSS = 595

    /** Main-looper scheduling for the offscreen WebViews (a detached
     *  view's own postDelayed never fires — see Exporter.handler). */
    private val handler = Handler(Looper.getMainLooper())

    /** Main-dispatched scope: WebView work stays here, the pure assembly
     *  hops to Default and the encoding/zipping to IO. */
    private val scope = MainScope()

    /** Keeps capture WebViews alive until they're drawn and destroyed. */
    private val liveViews = mutableListOf<WebView>()

    /** A rendered rich element: its CSS-px display size (the img tag's
     *  width/height, so readers show it at the authored size) and the
     *  captured pixels, PNG-encoded later on IO. */
    private class RichCapture(val cssWidth: Int, val cssHeight: Int, val bitmap: Bitmap)

    /** One content document being assembled: the XHTML [body] still
     *  carries its rich elements; [richKinds] lists them in document
     *  order (empty = no WebView pass needed). */
    private class Prepared(
        val fileName: String,
        val title: String,
        val body: String,
        val source: String,
        val richKinds: List<String>,
    )

    private class Plan(
        val titlePage: Prepared,
        val rootArticles: List<Prepared>,
        val chapters: List<Pair<Prepared, List<Prepared>>>,
    ) {
        val all: List<Prepared> =
            listOf(titlePage) + rootArticles + chapters.flatMap { listOf(it.first) + it.second }
    }

    /** Build the EPUB and hand the bytes to [done] on the main thread —
     *  null when anything failed (an image capture, the zip), so the
     *  caller can toast instead of writing a broken book. */
    fun export(context: Context, content: BookContent, done: (ByteArray?) -> Unit) {
        scope.launch {
            done(runCatching { build(context, content) }.getOrNull())
        }
    }

    private suspend fun build(context: Context, content: BookContent): ByteArray? {
        val plan = withContext(Dispatchers.Default) { prepare(content) }
        // Rich articles render one at a time — sequential WebViews bound
        // peak memory, and books rarely have many rich articles.
        val images = ArrayList<EpubImage>()
        val units = HashMap<String, EpubUnit>()
        for (prepared in plan.all) {
            units[prepared.fileName] = resolve(context, prepared, images) ?: return null
        }
        val book = EpubBook(
            title = content.title,
            titlePage = units.getValue(plan.titlePage.fileName),
            rootArticles = plan.rootArticles.map { units.getValue(it.fileName) },
            chapters = plan.chapters.map { (heading, articles) ->
                EpubChapter(units.getValue(heading.fileName), articles.map { units.getValue(it.fileName) })
            },
            images = images,
        )
        return withContext(Dispatchers.IO) { buildEpub(book) }
    }

    /** Lay the book out as content documents in reading order — identical
     *  to compileBook: title page, root articles, then each chapter's
     *  heading page followed by its articles. Pure CPU (parse + render +
     *  fix); runs on Dispatchers.Default. */
    private fun prepare(content: BookContent): Plan {
        var counter = 0
        fun nextName(): String {
            counter++
            return "unit-" + counter.toString().padStart(3, '0') + ".xhtml"
        }
        fun headingPage(title: String) =
            Prepared(nextName(), title, "<h1>${escapeXml(title)}</h1>", "", emptyList())
        fun article(articleTitle: String, source: String): Prepared {
            val body = toXhtml(documentBody(MarkdownHtml.document(source, articleTitle, dark = false)))
            return Prepared(nextName(), articleTitle, body, source, findRichElements(body).map { it.kind })
        }
        val titlePage = headingPage(content.title)
        val rootArticles = content.articles.map { article(it.title, it.source) }
        val chapters = content.chapters.map { chapter ->
            headingPage(chapter.title) to chapter.articles.map { article(it.title, it.source) }
        }
        return Plan(titlePage, rootArticles, chapters)
    }

    /** Finish one unit: capture its rich elements as PNGs (if any) and
     *  swap them for img tags. Null when a capture failed — a book with
     *  silently missing formulas would be worse than a toast. */
    private suspend fun resolve(
        context: Context,
        prepared: Prepared,
        images: MutableList<EpubImage>,
    ): EpubUnit? {
        if (prepared.richKinds.isEmpty()) {
            return EpubUnit(prepared.fileName, prepared.title, prepared.body)
        }
        val captures = renderRich(context, prepared.source, prepared.title, prepared.richKinds.size)
            ?: return null
        val pngs = withContext(Dispatchers.IO) {
            captures.map { capture ->
                val out = ByteArrayOutputStream()
                capture.bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
                capture.bitmap.recycle()
                out.toByteArray()
            }
        }
        val tags = pngs.mapIndexed { index, bytes ->
            val fileName = "images/rich-" + (images.size + 1).toString().padStart(3, '0') + ".png"
            images.add(EpubImage(fileName, bytes))
            val kind = prepared.richKinds[index]
            "<img src=\"$fileName\" alt=\"$kind\" class=\"md-$kind\"" +
                " width=\"${captures[index].cssWidth}\" height=\"${captures[index].cssHeight}\"/>"
        }
        return EpubUnit(prepared.fileName, prepared.title, replaceRichElements(prepared.body, tags))
    }

    /** Render [source] in an offscreen WebView (engines and all), wait for
     *  md-init's render-complete flag, read every rich element's rect from
     *  the DOM, and draw each into its own bitmap. Resumes with null when
     *  the DOM doesn't report [expected] elements or the draw failed.
     *  Runs on Main end to end; rects are CSS px, scaled by the display
     *  density into device px (`initial-scale=1`: CSS px == dp). */
    @SuppressLint("SetJavaScriptEnabled")
    private suspend fun renderRich(
        context: Context,
        source: String,
        title: String,
        expected: Int,
    ): List<RichCapture>? = suspendCancellableCoroutine { continuation ->
        val html = MarkdownHtml.document(source, title, dark = false)
        val density = context.resources.displayMetrics.density
        val widthPx = (WIDTH_CSS * density).toInt()
        val webView = WebView(context)
        webView.settings.javaScriptEnabled = true
        // Lay out at the final width BEFORE loading, as in Exporter — the
        // diagram engines' text measurement needs the real viewport.
        webView.measure(
            View.MeasureSpec.makeMeasureSpec(widthPx, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(widthPx * 842 / WIDTH_CSS, View.MeasureSpec.EXACTLY),
        )
        webView.layout(0, 0, webView.measuredWidth, webView.measuredHeight)

        // onPageFinished isn't contractually once-only — the guard keeps a
        // second callback chain from double-resuming the coroutine.
        var finished = false
        fun finish(result: List<RichCapture>?) {
            if (finished) return
            finished = true
            liveViews.remove(webView)
            webView.destroy()
            continuation.resume(result)
        }

        fun capture(view: WebView) {
            // One DOM round-trip: full height plus every rich element's
            // page-space rect, in document order (querySelectorAll) — the
            // same order findRichElements found them in the markup.
            view.evaluateJavascript(
                "JSON.stringify({h: document.documentElement.scrollHeight," +
                    " els: Array.from(document.querySelectorAll(" +
                    "'.md-mathi, .md-mathd, .mermaid, .plantuml'))" +
                    ".map(e => { const r = e.getBoundingClientRect();" +
                    " return [r.left + window.scrollX, r.top + window.scrollY, r.width, r.height]; })})",
            ) { value ->
                val rects = ArrayList<FloatArray>()
                var cssHeight = 0f
                runCatching {
                    val unquoted = JSONTokener(value ?: "null").nextValue() as? String
                        ?: return@runCatching
                    val json = JSONObject(unquoted)
                    cssHeight = json.optDouble("h", 0.0).toFloat()
                    val els = json.optJSONArray("els") ?: return@runCatching
                    for (index in 0 until els.length()) {
                        val el = els.optJSONArray(index) ?: continue
                        rects.add(
                            floatArrayOf(
                                el.optDouble(0, 0.0).toFloat(),
                                el.optDouble(1, 0.0).toFloat(),
                                el.optDouble(2, 0.0).toFloat(),
                                el.optDouble(3, 0.0).toFloat(),
                            ),
                        )
                    }
                }
                if (rects.size != expected || cssHeight <= 0f) {
                    finish(null)
                    return@evaluateJavascript
                }
                // Grow the view to the full document, give it a beat to
                // repaint (as in Exporter.captureLaidOutPdf), then draw
                // each element into its own bitmap — small crops, never a
                // full-document bitmap.
                val heightPx = ceil(cssHeight * density).toInt().coerceAtLeast(1)
                view.measure(
                    View.MeasureSpec.makeMeasureSpec(widthPx, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(heightPx, View.MeasureSpec.EXACTLY),
                )
                view.layout(0, 0, view.measuredWidth, view.measuredHeight)
                handler.postDelayed({
                    val captures = runCatching {
                        rects.map { (x, y, w, h) ->
                            val bitmapWidth = ceil(w * density).toInt().coerceAtLeast(1)
                            val bitmapHeight = ceil(h * density).toInt().coerceAtLeast(1)
                            val bitmap = createBitmap(bitmapWidth, bitmapHeight)
                            Canvas(bitmap).apply {
                                translate(-x * density, -y * density)
                                view.draw(this)
                            }
                            RichCapture(
                                ceil(w).toInt().coerceAtLeast(1),
                                ceil(h).toInt().coerceAtLeast(1),
                                bitmap,
                            )
                        }
                    }.getOrNull()
                    finish(captures)
                }, 250)
            }
        }

        webView.webViewClient = object : MdAssetWebViewClient(context.assets, { html }) {
            override fun onPageFinished(view: WebView, url: String?) {
                Exporter.waitForRenderComplete(view, 0) { capture(view) }
            }
        }
        liveViews.add(webView)
        webView.loadUrl(MdAssetWebViewClient.INDEX_URL)
    }
}
