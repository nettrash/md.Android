/*
 * RichWebView.kt
 * md (Android)
 *
 * The live preview pane, and the WebView plumbing shared with the printer.
 * Renders the same themed HTML that Print / Save-as-PDF produce
 * (`MarkdownHtml.document`) inside a WebView, so the preview is identical to
 * the exported document and gains the rich renderers — LaTeX math (KaTeX),
 * Mermaid, PlantUML — that run from bundled assets under `assets/rich/`.
 *
 * The renderers are offline. `MdAssetWebViewClient` serves the document HTML
 * and the bundled `rich/` assets over a private
 * `https://appassets.androidplatform.net` origin (a reserved, non-resolvable
 * host); that real origin — rather than a `loadDataWithBaseURL` opaque
 * origin — is what lets `md-init.js`'s ES-module import of the PlantUML
 * engine resolve. The app's one use of the network is images the document
 * itself references (the INTERNET permission exists solely for them).
 */

package me.nettrash.md.ui

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.res.AssetManager
import android.graphics.Color
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import me.nettrash.md.markdown.MarkdownHtml
import java.io.ByteArrayInputStream

/**
 * Serves `index.html` (the current document HTML from [htmlProvider]) and every
 * bundled `rich/` asset for the private appassets origin. Runs on a WebView
 * worker thread, so [htmlProvider] must be safe to read there.
 */
open class MdAssetWebViewClient(
    private val assets: AssetManager,
    private val htmlProvider: () -> String,
) : WebViewClient() {

    override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
        val url = request.url
        if (url.host != HOST) return null
        val path = url.path ?: return null
        return when {
            path == "/" || path == "/index.html" ->
                WebResourceResponse("text/html", "UTF-8", htmlProvider().byteInputStream())
            path.startsWith("/rich/") -> serveAsset(path.removePrefix("/"))
            else -> null
        }
    }

    private fun serveAsset(assetPath: String): WebResourceResponse =
        try {
            val mime = mimeFor(assetPath)
            // Text types get a charset; fonts/binaries must not (breaks them).
            val encoding = if (mime.startsWith("text/") || mime == "image/svg+xml") "UTF-8" else null
            WebResourceResponse(mime, encoding, assets.open(assetPath))
        } catch (e: Exception) {
            WebResourceResponse("text/plain", "UTF-8", ByteArrayInputStream(ByteArray(0)))
        }

    companion object {
        const val HOST = "appassets.androidplatform.net"
        const val INDEX_URL = "https://appassets.androidplatform.net/index.html"

        fun mimeFor(path: String): String = when (path.substringAfterLast('.').lowercase()) {
            "js", "mjs" -> "text/javascript"   // a JS module served otherwise is rejected
            "css" -> "text/css"
            "html" -> "text/html"
            "svg" -> "image/svg+xml"
            "woff2" -> "font/woff2"
            "woff" -> "font/woff"
            "ttf" -> "font/ttf"
            "json" -> "application/json"
            else -> "application/octet-stream"
        }
    }
}

/**
 * A one-shot request to scroll the preview to a heading anchor. `slug` is the
 * heading's `id` in the rendered HTML (`MarkdownParser.slug`); `id` makes each
 * request distinct so tapping the same table-of-contents entry twice scrolls
 * twice — [RichPreview] acts once per unseen `id`.
 */
data class PreviewNavigation(val id: Long, val slug: String)

/**
 * The preview's WebView with its half of the Split scroll sync: it reports
 * the reader's scrolls as fractions of the scrollable range and follows the
 * editor on request. Programmatic scrolls — the sync's own relays, the
 * anchor jumps, the reload restore — mark themselves first (a short
 * timestamp window; the JS-driven ones land asynchronously, so a plain
 * flag can't cover them) and are never reported back: that one-way gate is
 * the whole feedback-loop guard.
 */
class SyncWebView(context: Context) : WebView(context) {

    /** Reports the fraction [0, 1] of a scroll the *reader* made. */
    var onUserScroll: ((Float) -> Unit)? = null

    private var programmaticUntil = 0L

    /** The scrollable range in view pixels; <= 0 when the content fits. */
    private fun maxScroll(): Int = computeVerticalScrollRange() - height

    /** The next ~300 ms of scroll events are ours, not the reader's. */
    fun markProgrammatic() {
        programmaticUntil = SystemClock.uptimeMillis() + 300
    }

    /** Follow the editor to [fraction] of this pane's range. */
    fun scrollToFraction(fraction: Float) {
        val max = maxScroll()
        if (max <= 0) return
        markProgrammatic()
        scrollTo(0, (fraction.coerceIn(0f, 1f) * max).toInt())
    }

    override fun onScrollChanged(l: Int, t: Int, oldl: Int, oldt: Int) {
        super.onScrollChanged(l, t, oldl, oldt)
        if (SystemClock.uptimeMillis() < programmaticUntil) return
        val max = maxScroll()
        if (max > 0) onUserScroll?.invoke((t.toFloat() / max).coerceIn(0f, 1f))
    }
}

/**
 * The preview WebView. Loads immediately, then debounces reloads on text/theme
 * change so live typing in Split mode doesn't reload (and re-run the diagram
 * engines) on every keystroke. Scroll position is preserved across reloads.
 *
 * [navigation] scrolls the page to a heading (the table of contents drives
 * it). When it arrives before the page has finished loading — the TOC tap
 * that just switched the mode to Preview lands on a WebView still parsing —
 * the scroll is parked and performed in `onPageFinished`, the same hook that
 * restores the reload scroll position.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun RichPreview(
    text: String,
    title: String,
    modifier: Modifier = Modifier,
    navigation: PreviewNavigation? = null,
    scrollSync: ScrollSync? = null,
) {
    val context = LocalContext.current
    val dark = isSystemInDarkTheme()
    val state = remember { PreviewState() }

    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            SyncWebView(ctx).apply {
                settings.javaScriptEnabled = true            // bundled engines; net = doc images only
                setBackgroundColor(Color.TRANSPARENT)        // let the CSS paper show
                webViewClient = object : MdAssetWebViewClient(ctx.assets, { state.html }) {
                    override fun onPageFinished(view: WebView, url: String?) {
                        state.onPageFinished(view)
                    }
                    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                        val target = request.url
                        // Our own origin stays in the WebView; http/https links open
                        // outside; everything else (javascript:, data:, file:, …) is
                        // blocked so a malicious link can't run in the WebView.
                        if (target.host == HOST) return false
                        if (target.scheme == "http" || target.scheme == "https") {
                            runCatching {
                                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(target.toString())))
                            }
                        }
                        return true
                    }
                }
            }
        },
        update = { webView ->
            // (Re-)wire the pane link on every update, so the freshest
            // composition owns the closures — a null sync (single-pane
            // modes) simply reports nowhere.
            webView.onUserScroll = scrollSync?.let { sync -> { f -> sync.previewDidScroll(f) } }
            scrollSync?.scrollPreview = { f -> webView.scrollToFraction(f) }
            state.render(webView, MarkdownHtml.document(text, title, dark))
            navigation?.let { state.navigate(webView, it) }
        },
        // Destroy the WebView when the preview leaves composition, so it (and
        // the Activity context it holds) isn't leaked across Preview/Split toggles.
        onRelease = { webView -> webView.destroy() },
    )
}

private class PreviewState {
    @Volatile var html: String = ""
    private var savedScrollY = 0
    /** True once `onPageFinished` has fired — anchors exist to scroll to. */
    private var pageReady = false
    /** True from the moment a debounced reload is scheduled until its
     *  `onPageFinished` — the visible DOM is stale (or about to be torn
     *  down), so navigations must not run against it. */
    private var reloadPending = false
    /** Anchor waiting for `onPageFinished`: a navigation that arrived while
     *  the page was loading or a debounced reload was pending. Survives the
     *  `reload()` itself — it's only consumed once the new DOM is up. */
    private var pendingSlug: String? = null
    private var loaded = false
    private var lastNavigationId = 0L
    private val handler = Handler(Looper.getMainLooper())
    private var pending: Runnable? = null

    fun render(webView: WebView, newHtml: String) {
        if (loaded && newHtml == html) return
        html = newHtml
        pending?.let { handler.removeCallbacks(it) }
        if (!loaded) {
            loaded = true
            webView.loadUrl(MdAssetWebViewClient.INDEX_URL)
        } else {
            reloadPending = true
            val work = Runnable {
                webView.evaluateJavascript("window.scrollY") { value ->
                    savedScrollY = value?.toFloatOrNull()?.toInt() ?: 0
                    // The reload cycle emits scrolls of its own (the
                    // commit-time reset, Chromium's native restoration) —
                    // none of them the reader's.
                    (webView as? SyncWebView)?.markProgrammatic()
                    webView.reload()
                }
            }
            pending = work
            handler.postDelayed(work, 350)
        }
    }

    /** Scroll to [navigation]'s anchor, once per `id`. Runs immediately only
     *  against a settled DOM — during the initial load *and* while a
     *  debounced reload is pending or in flight the anchor lookup would hit
     *  the old (or absent) document, so the request is parked for
     *  [onPageFinished] instead. A fresh WebView (the preview pane was just
     *  recreated by a mode switch) replays the latest request — deliberate:
     *  the recreated pane starts at the top anyway, so reopening at the
     *  last-navigated heading is strictly better. */
    fun navigate(webView: WebView, navigation: PreviewNavigation) {
        if (navigation.id == lastNavigationId) return
        lastNavigationId = navigation.id
        if (pageReady && !reloadPending) scrollToAnchor(webView, navigation.slug)
        else pendingSlug = navigation.slug
    }

    /** The page (initial load or reload) is up: restore the reload scroll
     *  position, then run any parked navigation — after the restore, so the
     *  anchor wins. */
    fun onPageFinished(view: WebView) {
        pageReady = true
        reloadPending = false
        // Everything the load's tail end does to the scroll position is
        // programmatic (see SyncWebView) — none of it must read as the
        // reader's hand and yank the editor around.
        (view as? SyncWebView)?.markProgrammatic()
        if (savedScrollY > 0) {
            view.evaluateJavascript("window.scrollTo(0, $savedScrollY)") {
                // The script queues behind md-init.js's diagram work, which
                // can outlast the mark's window on a rich document — re-arm
                // once the renderer has actually run it (this callback).
                (view as? SyncWebView)?.markProgrammatic()
            }
        }
        pendingSlug?.let { slug ->
            pendingSlug = null
            scrollToAnchor(view, slug)
        }
    }
}

/** Scroll the page so the element with id [slug] is at the top. Slugs keep
 *  only letters, digits, "-" and "_" (see `MarkdownParser.slug`) — no quote,
 *  backslash or other JS metacharacter survives — so interpolating one into
 *  the script cannot break out of the string literal. Marked programmatic:
 *  in Split, the sync must not mistake the jump for the reader's hand. */
private fun scrollToAnchor(webView: WebView, slug: String) {
    (webView as? SyncWebView)?.markProgrammatic()
    webView.evaluateJavascript("document.getElementById('$slug')?.scrollIntoView(true)") {
        // Re-arm at execution time too — the script can queue behind the
        // diagram engines and land after the first window expires.
        (webView as? SyncWebView)?.markProgrammatic()
    }
}
