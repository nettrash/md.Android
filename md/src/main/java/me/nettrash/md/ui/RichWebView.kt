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
 * The renderer runs in a process of its own, and that process can be taken
 * away (memory pressure, a WebView update, a diagram that runs it out of
 * memory). Android's rule is blunt: a `WebViewClient` that does not answer
 * `onRenderProcessGone` has the whole app killed. This one answers — the dead
 * view comes off screen and a fresh one is built in its place — and the
 * answer is bounded by `PreviewRetryPolicy`, because a diagram that killed
 * the renderer kills it again on the reload: after the second failure in a
 * row the pane shows one quiet line instead. What ends a run of failures is
 * the page's own word that it rendered — `md-init.js` pokes
 * `window.MdRenderBridge.onRenderComplete()` once every engine has finished,
 * and the preview WebView (only the preview WebView; see Exporter.kt for why
 * the export path deliberately has no such interface) registers that bridge.
 * `onPageFinished` is NOT that word: it is a finished navigation, and the
 * diagram engines run after it, in exactly the window a diagram kills the
 * process in. An edit lifts the notice and buys one more attempt.
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
import android.util.Log
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
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
 * engines) on every keystroke. Scroll position is preserved across reloads
 * of the same [document] (the ViewModel's `documentToken`); a different
 * document starts at the top.
 *
 * [navigation] scrolls the page to a heading (the table of contents drives
 * it). When it arrives before the page has finished loading — the TOC tap
 * that just switched the mode to Preview lands on a WebView still parsing —
 * the scroll is parked and performed in `onPageFinished`, the same hook that
 * restores the reload scroll position.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
internal fun RichPreview(
    text: String,
    title: String,
    modifier: Modifier = Modifier,
    navigation: PreviewNavigation? = null,
    scrollSync: ScrollSync? = null,
    document: Long = 0L,
    // The document's retry policy, owned by the caller so it outlives this
    // pane: a mode switch takes the preview out of composition, and a policy
    // remembered here would forget a give-up with it.
    retry: PreviewRetryPolicy = remember { PreviewRetryPolicy() },
    // Supplied only by `PreviewRenderBridgeInstrumentedTest`, which has to
    // watch the state to see the bundled script's render report arrive.
    // One state for the pane's whole life: the WebView's client and asset
    // loader hold it, so a new document must not bring a new one — the page
    // would keep serving the old document (PreviewScrollResetInstrumentedTest
    // caught exactly that). It is pointed at each document's policy instead.
    state: PreviewState = remember { PreviewState() },
) {
    // The policy the WebView's callbacks answer to is the open document's.
    // A plain field, and assigning the same one again is a no-op.
    if (state.retry !== retry) state.retry = retry
    val context = LocalContext.current
    val dark = isSystemInDarkTheme()
    // A dead renderer is answered with a fresh WebView: [generation] keys the
    // AndroidView, so bumping it disposes the old one — `onRelease` destroys
    // it, which is what Android asks of a WebView whose process is gone — and
    // the factory builds another that loads the document again. [gaveUp] is
    // the second failure in a row: the pane shows one line and stops
    // rebuilding until the document changes, which buys it one more attempt
    // (see PreviewRetryPolicy).
    var generation by remember { mutableIntStateOf(0) }
    // Seeded from the policy, per policy: a pane composed again after a
    // give-up (a mode switch, a rotation) comes back showing the notice, not
    // a fresh WebView — and a new document, which brings a new policy, starts
    // with a live preview whatever the last one's fate.
    var gaveUp by remember(retry) { mutableStateOf(retry.hasGivenUp) }

    val html = remember(text, title, dark) { MarkdownHtml.document(text, title, dark) }
    // A document (or theme) change brings the preview back when it had given
    // up: whatever killed the renderer is no longer what would be loaded. It
    // does not end the run of failures — only a completed render does — so
    // a second death straight after gives up again rather than looping.
    LaunchedEffect(html) {
        if (state.documentChanged(html)) {
            gaveUp = false
            generation++
        }
    }

    if (gaveUp) {
        // One quiet line, in the pane the preview was in. No alert, no retry
        // button and no diagnostics: a preview that has stopped twice needs
        // the document to change before it can do anything else, so the
        // sentence is also the instruction.
        Box(modifier, contentAlignment = Alignment.Center) {
            Text(
                "The preview stopped twice in a row — it comes back as soon as " +
                    "you edit the document.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(24.dp),
            )
        }
        return
    }

    key(generation) {
        AndroidView(
            modifier = modifier,
            factory = { ctx ->
                // A WebView of this generation: the page state starts over,
                // because whatever the last one had loaded went with it.
                state.reset()
                SyncWebView(ctx).apply {
                    settings.javaScriptEnabled = true            // bundled engines; net = doc images only
                    setBackgroundColor(Color.TRANSPARENT)        // let the CSS paper show
                    // The page's own word that it rendered: `md-init.js`'s
                    // `notifyComplete` pokes `window.MdRenderBridge
                    // .onRenderComplete()` once every engine it ran has
                    // finished. It is the ONLY event that ends a run of
                    // render-process failures — `onPageFinished` is a finished
                    // navigation, and the diagram engines run after it, in
                    // exactly the window a diagram kills the process in. The
                    // call lands on the WebView's JavaBridge thread and is
                    // answered on Main, and only while this view is still the
                    // pane's: a view whose process died was taken out of its
                    // parent (`onRenderProcessGone`), and a late report from
                    // it must not be credited to the one that replaced it.
                    // Registered on the preview WebView alone: the export
                    // WebViews read their page off `evaluateJavascript` on
                    // purpose (see the comment on `captureHtml` in
                    // Exporter.kt).
                    addJavascriptInterface(
                        RenderBridge {
                            post {
                                if (parent == null) return@post
                                Log.i(PREVIEW_LOG_TAG, "render complete: generation $generation, run of failures over")
                                state.renderCompleted()
                            }
                        },
                        "MdRenderBridge",
                    )
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
                        /** The render process died. Returning false here has
                         *  the whole app killed — Android's documented
                         *  behaviour — so md answers it: the dead view comes
                         *  off screen at once, and the retry policy says
                         *  whether another one is built or the notice goes
                         *  up. The view itself is destroyed by `onRelease`
                         *  when this AndroidView leaves the composition,
                         *  which either answer causes. */
                        override fun onRenderProcessGone(
                            view: WebView,
                            detail: RenderProcessGoneDetail,
                        ): Boolean {
                            (view.parent as? ViewGroup)?.removeView(view)
                            val action = state.processTerminated()
                            Log.w(
                                PREVIEW_LOG_TAG,
                                "render process gone (crashed=${detail.didCrash()}): " +
                                    "failures=${state.retry.failures} -> $action",
                            )
                            when (action) {
                                PreviewRetryPolicy.Action.RELOAD -> generation++
                                PreviewRetryPolicy.Action.GIVE_UP -> gaveUp = true
                                PreviewRetryPolicy.Action.NONE -> Unit
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
                state.render(webView, html, document)
                navigation?.let { state.navigate(webView, it) }
            },
            // Destroy the WebView when the preview leaves composition, so it (and
            // the Activity context it holds) isn't leaked across Preview/Split toggles.
            onRelease = { webView -> webView.destroy() },
        )
    }
}

/**
 * The preview pane's state, outliving any one WebView: what is loaded, where
 * it was scrolled, and — through [retry] — how the render process's deaths
 * are answered. The three things the WebView can report are three methods
 * here ([navigationFinished], [renderCompleted], [processTerminated]), so
 * `PreviewStateTest` can play WebView's real order — a finished navigation,
 * then the death the engines caused — without a WebView.
 */
internal class PreviewState(
    /** What the preview does when its render process dies — see
     *  PreviewRetryPolicy.kt. It lives outside the WebView, so building a
     *  fresh WebView does not forget the failure that made it necessary, and
     *  outside this state too (the caller's), so a mode switch does not. */
    var retry: PreviewRetryPolicy = PreviewRetryPolicy(),
) {
    @Volatile var html: String = ""
    private var savedScrollY = 0
    /** The document (`DocumentViewModel.documentToken`) last rendered. */
    private var document: Long? = null
    /** A different document replaced the page: the next `onPageFinished`
     *  puts it at the top instead of restoring the old page's position. The
     *  pane (and its WebView) outlives a document change, so without this a
     *  file opened while the reader was deep in another came up scrolled to
     *  wherever that one had been — and `reload()` restores the position
     *  natively, so leaving `savedScrollY` at zero is not enough. Sticky until
     *  that load finishes: a keystroke's debounced re-render inside the
     *  window must not capture the OLD page's scroll and carry it over. */
    private var scrollToTop = false
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
    // Lazy so the state can be built off the main thread and on the JVM (the
    // tests never schedule a debounced reload, so never touch it).
    private val handler by lazy { Handler(Looper.getMainLooper()) }
    private var pending: Runnable? = null

    /** How many times the page has reported its render complete. Read by
     *  `PreviewRenderBridgeInstrumentedTest`, the only thing that can see
     *  whether the bundled `md-init.js` actually reaches the bridge. */
    @Volatile var renderCompletions: Int = 0
        private set

    /** The page got all the way through its own render — engines and all;
     *  `md-init.js` said so through `MdRenderBridge`. That, and only that,
     *  ends the run of render-process failures. */
    fun renderCompleted() {
        renderCompletions += 1
        retry.handle(PreviewRetryPolicy.Event.RENDERED)
    }

    /** A navigation finished (`onPageFinished`): the DOM is up, anchors exist
     *  to scroll to — and that is all. The diagram engines run after the
     *  load event, so this is emphatically not a render that survived, and
     *  it tells the retry policy nothing. */
    fun navigationFinished() {
        pageReady = true
        reloadPending = false
    }

    /** The render process died: nothing is loaded any more, and the policy
     *  says whether another WebView is built or the notice goes up. */
    fun processTerminated(): PreviewRetryPolicy.Action {
        reset()
        return retry.handle(PreviewRetryPolicy.Event.TERMINATED)
    }

    /** The document (or the theme) changed. A give-up is lifted — the new
     *  document is worth one attempt — and the count is left standing.
     *  Returns whether the pane had given up, so the caller can rebuild it. */
    fun documentChanged(html: String): Boolean {
        // The same document seen again — the pane was composed again, not
        // edited — is no change at all.
        if (retry.shownDocument == html) return false
        retry.shownDocument = html
        return documentChanged()
    }

    fun documentChanged(): Boolean {
        val recovering = retry.hasGivenUp
        retry.handle(PreviewRetryPolicy.Event.DOCUMENT_CHANGED)
        return recovering
    }

    /** A WebView is going away (its process died) or a fresh one is being
     *  built: nothing is loaded any more, no page is up, and the debounced
     *  reload that was waiting must not run — it would reach into a WebView
     *  that no longer has a renderer. The retry count deliberately survives;
     *  only the policy clears that. */
    fun reset() {
        pending?.let { handler.removeCallbacks(it) }
        pending = null
        loaded = false
        pageReady = false
        reloadPending = false
        savedScrollY = 0
    }

    fun render(webView: WebView, newHtml: String, newDocument: Long) {
        val documentChanged = document != null && document != newDocument
        document = newDocument
        if (documentChanged) {
            scrollToTop = true
            savedScrollY = 0
        }
        if (loaded && newHtml == html && !documentChanged) return
        html = newHtml
        pending?.let { handler.removeCallbacks(it) }
        if (!loaded) {
            loaded = true
            webView.loadUrl(MdAssetWebViewClient.INDEX_URL)
        } else {
            reloadPending = true
            val work = Runnable {
                webView.evaluateJavascript("window.scrollY") { value ->
                    savedScrollY = if (scrollToTop) 0 else value?.toFloatOrNull()?.toInt() ?: 0
                    // The reload cycle emits scrolls of its own (the
                    // commit-time reset, Chromium's native restoration) —
                    // none of them the reader's.
                    (webView as? SyncWebView)?.markProgrammatic()
                    if (scrollToTop) {
                        // A different document is a new page, not a reload:
                        // `reload()` has Chromium restore the old page's
                        // position natively, and that restore can land after
                        // onPageFinished's scroll to the top, once the
                        // diagrams have made the page tall enough to hold it —
                        // PreviewScrollResetInstrumentedTest failed 3 runs in 8
                        // on exactly that (2026-09-27). A navigation restores
                        // nothing. The client serves the index by path, so
                        // the query only makes the URL a new one.
                        webView.loadUrl("${MdAssetWebViewClient.INDEX_URL}?document=$document")
                    } else {
                        webView.reload()
                    }
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
     *  anchor wins. A navigation, not a render: see [navigationFinished]. */
    fun onPageFinished(view: WebView) {
        navigationFinished()
        // Everything the load's tail end does to the scroll position is
        // programmatic (see SyncWebView) — none of it must read as the
        // reader's hand and yank the editor around.
        (view as? SyncWebView)?.markProgrammatic()
        if (scrollToTop) {
            scrollToTop = false
            view.evaluateJavascript("window.scrollTo(0, 0)") {
                (view as? SyncWebView)?.markProgrammatic()
            }
        } else if (savedScrollY > 0) {
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

private const val PREVIEW_LOG_TAG = "md.preview"

/**
 * What `rich/md-init.js` pokes when every engine has finished
 * (`window.MdRenderBridge.onRenderComplete()`). Only this one method is
 * exposed to the page — a JavaScript interface reaches nothing but its
 * `@JavascriptInterface` members — and it takes no arguments and returns
 * nothing, so the page can say "rendered" and not one thing more. Called on
 * the WebView's JavaBridge thread, never on Main; the caller posts.
 */
private class RenderBridge(private val onComplete: () -> Unit) {
    @JavascriptInterface
    fun onRenderComplete() = onComplete()
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
