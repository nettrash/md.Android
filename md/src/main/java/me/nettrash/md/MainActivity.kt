/*
 * MainActivity.kt
 * md (Android)
 *
 * Single Activity that hosts the Compose editor. It also routes incoming
 * intents into the document model:
 *   - ACTION_VIEW  — a Markdown / text file opened from a file manager or
 *                    "Open with md"; loaded read-only (the in-app Open
 *                    flow re-opens it read-write through SAF if needed).
 *   - ACTION_SEND  — shared text, opened as a new untitled document.
 */

package me.nettrash.md

import android.content.Intent
import android.os.Bundle
import android.webkit.WebView
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import me.nettrash.md.ui.EditorScreen
import me.nettrash.md.ui.theme.MdTheme

class MainActivity : ComponentActivity() {

    private val viewModel: DocumentViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Must run before the first WebView in the process exists: lets a
        // WebView draw its FULL document into a canvas rather than just the
        // visible tiles — what the single-page PDF export captures (see
        // Exporter.renderPdf). Merely disables a tiling optimization.
        WebView.enableSlowWholeDocumentDraw()
        enableEdgeToEdge()
        if (savedInstanceState == null) handleIntent(intent)
        setContent {
            MdTheme {
                EditorScreen(viewModel)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        when (intent?.action) {
            Intent.ACTION_VIEW -> intent.data?.let { viewModel.load(it, writable = false) }
            Intent.ACTION_SEND -> intent.getStringExtra(Intent.EXTRA_TEXT)?.let { viewModel.acceptSharedText(it) }
        }
    }
}
