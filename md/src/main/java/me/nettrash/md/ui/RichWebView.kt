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
 * Everything is offline. `MdAssetWebViewClient` serves the document HTML and
 * the bundled `rich/` assets over a private `https://appassets.androidplatform.net`
 * origin (a reserved, non-resolvable host); that real origin — rather than a
 * `loadDataWithBaseURL` opaque origin — is what lets `md-init.js`'s ES-module
 * import of the PlantUML engine resolve. The app declares no INTERNET
 * permission, so "offline" is structurally guaranteed regardless.
 */

package me.nettrash.md.ui

import android.annotation.SuppressLint
import android.content.Intent
import android.content.res.AssetManager
import android.graphics.Color
import android.net.Uri
import android.os.Handler
import android.os.Looper
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
 * The preview WebView. Loads immediately, then debounces reloads on text/theme
 * change so live typing in Split mode doesn't reload (and re-run the diagram
 * engines) on every keystroke. Scroll position is preserved across reloads.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun RichPreview(text: String, title: String, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val dark = isSystemInDarkTheme()
    val state = remember { PreviewState() }

    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            WebView(ctx).apply {
                settings.javaScriptEnabled = true            // local, bundled content only
                setBackgroundColor(Color.TRANSPARENT)        // let the CSS paper show
                webViewClient = object : MdAssetWebViewClient(ctx.assets, { state.html }) {
                    override fun onPageFinished(view: WebView, url: String?) {
                        if (state.savedScrollY > 0) {
                            view.evaluateJavascript("window.scrollTo(0, ${state.savedScrollY})", null)
                        }
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
            state.render(webView, MarkdownHtml.document(text, title, dark))
        },
        // Destroy the WebView when the preview leaves composition, so it (and
        // the Activity context it holds) isn't leaked across Preview/Split toggles.
        onRelease = { webView -> webView.destroy() },
    )
}

private class PreviewState {
    @Volatile var html: String = ""
    var savedScrollY = 0
    private var loaded = false
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
            val work = Runnable {
                webView.evaluateJavascript("window.scrollY") { value ->
                    savedScrollY = value?.toFloatOrNull()?.toInt() ?: 0
                    webView.reload()
                }
            }
            pending = work
            handler.postDelayed(work, 350)
        }
    }
}
