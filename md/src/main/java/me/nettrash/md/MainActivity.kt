/*
 * MainActivity.kt
 * md (Android)
 *
 * Single Activity that hosts the Compose editor. It also hands incoming
 * intents to the document model:
 *   - ACTION_VIEW / ACTION_EDIT — a Markdown / text / diagram file opened
 *                    from a file manager, Files, Drive or a mail client
 *                    ("Open with md"). It opens for EDITING whenever the
 *                    sender granted write access, and read-only otherwise
 *                    (see DocumentViewModel.openIncoming / IncomingGrant.kt).
 *   - ACTION_SEND  — shared text, opened as a new untitled document.
 *
 * The Activity does not open them itself: it offers each one to the model,
 * which either opens it at once or parks it behind the unsaved-changes
 * dialog when the buffer on screen has edits with nowhere to autosave them.
 * The parked intent lives in the model, so a rotation while the dialog is up
 * cannot lose it.
 *
 * It is also one of the two places the hardware-keyboard chords are answered
 * ([chordHandler], installed by `EditorScreen`), and it is the half that
 * exists for the preview pane: that pane is a WebView inside an
 * `AndroidView`, and while it is on screen the composition holds no focus at
 * all, so a `Modifier.onPreviewKeyEvent` in the tree never runs. The
 * Activity's [onKeyDown] sees every press the view hierarchy did not want,
 * whatever has focus, which is what makes Ctrl+1 work from Preview.
 *
 * Being the leftovers, it is also the half that can be beaten: a focused
 * control that claims a press consumes it, and the Activity is then never
 * asked. That is harmless for Ctrl+A / Z / Y / X / C / V — the field's own
 * keys, and not rows in md's table — but Compose Foundation reads Alt+Up and
 * Alt+Down as Home and End without consulting Ctrl, so the editor's text
 * field used to swallow Ctrl+Alt+Up / Ctrl+Alt+Down and md never saw
 * Previous / Next Article at all. `EditorScreen` therefore ALSO installs the
 * same handler in the composition's preview pass, which runs ahead of the
 * focused field; the two never both fire, because a press claimed there is
 * consumed before it can reach here.
 */

package me.nettrash.md

import android.content.Intent
import android.os.Bundle
import android.webkit.WebView
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.ui.input.key.KeyEvent
import me.nettrash.md.ui.EditorScreen
import me.nettrash.md.ui.theme.MdTheme

class MainActivity : ComponentActivity() {

    private val viewModel: DocumentViewModel by viewModels()

    /** What the editor does with a hardware-keyboard press: answered while
     *  the editor is on screen, and null otherwise. `EditorScreen` re-fills
     *  it on every composition so the closure that runs a chord is the
     *  freshest one — it reads the window width, the mode and the document
     *  as they are now. */
    var chordHandler: ((KeyEvent) -> Boolean)? = null

    /**
     * A press nothing in the window wanted. The view hierarchy is asked
     * first, as always — which is what leaves the text field's own editing
     * keys alone without md having to know what they are — and whatever is
     * left over is offered to the chord table (see `Shortcuts.kt`). This is
     * the route a chord takes while the preview pane is on screen, when the
     * composition holds no focus and a key modifier inside the tree would
     * never run; a chord pressed with the editor focused is claimed earlier,
     * in `EditorScreen`'s preview pass, and never arrives here.
     *
     * The key-up of a chord md answered is taken too, so a focused field is
     * never handed half a keystroke; a dialog has a window of its own and
     * never comes here, so a chord cannot fire behind one.
     */
    override fun onKeyDown(keyCode: Int, event: android.view.KeyEvent): Boolean =
        chordHandler?.invoke(KeyEvent(event)) == true || super.onKeyDown(keyCode, event)

    override fun onKeyUp(keyCode: Int, event: android.view.KeyEvent): Boolean =
        chordHandler?.invoke(KeyEvent(event)) == true || super.onKeyUp(keyCode, event)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Must run before the first WebView in the process exists: lets a
        // WebView draw its FULL document into a canvas rather than just the
        // visible tiles — what the EPUB export's rich-block snapshots
        // capture (see EpubExporter.renderRich). Merely disables a tiling
        // optimization.
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
        intent?.let { viewModel.handOver(it) }
    }
}
