/*
 * PreviewRetryPolicy.kt
 * md (Android)
 *
 * What the preview does when its render process dies.
 *
 * WebView runs the preview's page in a process of its own, and that process
 * can be taken away: memory pressure with several apps open, a WebView
 * update landing under a running app, or a Graphviz / PlantUML layout that
 * runs the renderer out of memory on its own. Android's documented rule is
 * blunt — a `WebViewClient` that returns false from `onRenderProcessGone`
 * has the whole app killed — so md answers the event, and the answer is this
 * decision.
 *
 * The cure is to build a fresh WebView and load the document again. The cure
 * has to be bounded, though: a diagram that kills the renderer will kill it
 * again on the reload, and an unbounded retry is a loop the reader cannot get
 * out of. So the second failure in a row stops the retrying and shows one
 * quiet line instead.
 *
 * What counts as "in a row" is the whole of the bound, and it is
 * deliberately narrow: only a page that got all the way through its own
 * render puts the count back to zero. A *finished navigation* does not —
 * `onPageFinished` fires when the shell page has loaded, and `md-init.js`
 * runs Mermaid, Graphviz and the 7 MB PlantUML engine after that, so every
 * death this policy exists for lands after the navigation finished, and
 * crediting one would zero the count on every cycle (RichWebView.kt feeds
 * RENDERED from the page's own `MdRenderBridge.onRenderComplete()` report,
 * never from `onPageFinished`). Nor does an edit: a changed document is a
 * fresh thing to try, so it lifts the give-up and buys one more attempt, but
 * in Split mode it arrives on every keystroke, and a reader typing over a
 * document whose render keeps killing the process would otherwise refill
 * the budget faster than the deaths could empty it.
 *
 * Kept here, away from the client, because it is a decision and not a side
 * effect: three inputs, three outputs, no WebView, testable on its own. It is
 * the same decision as `md/PreviewRetryPolicy.swift` — the same limit, the
 * same narrow meaning of "rendered", the same one-attempt edit rule — written
 * in Kotlin: a class the preview state owns rather than a Swift value type,
 * so the two read alike but not identically. md.win draws the same
 * distinction from the other end (`PreviewHost.cs`, `OnCoreProcessFailed`),
 * and names the event that is deliberately *not* an input here:
 * "unresponsive". A slow PlantUML render looks exactly like an unresponsive
 * one, and reloading on it would kill the render that made the page slow in
 * the first place.
 */

package me.nettrash.md.ui

/**
 * The preview's retry state machine: terminations in, "reload" or "give up"
 * out. Plain Kotlin — the preview owns one.
 */
internal class PreviewRetryPolicy {

    /** What happened to the preview. */
    enum class Event {
        /** The render process died. */
        TERMINATED,

        /** The page finished rendering itself — `md-init.js` has run every
         *  engine the document asked for and reported it (`notifyComplete`,
         *  through the `MdRenderBridge` interface). Emphatically *not* "a
         *  navigation finished": the engines run after the load event, which
         *  is precisely the window the process dies in. */
        RENDERED,

        /** The document (or the theme, or the title) changed, so whatever the
         *  dead page held is not what would be loaded now. */
        DOCUMENT_CHANGED,
    }

    /** What the preview should do about it. */
    enum class Action {
        /** Build a fresh WebView and load the document again. */
        RELOAD,

        /** Stop reloading and show the notice. */
        GIVE_UP,

        /** Nothing to do. */
        NONE,
    }

    /** Terminations since the last *completed* render. */
    var failures: Int = 0
        private set

    /** True once [LIMIT] terminations in a row have been seen: the pane is
     *  showing the notice and further terminations are not retried. */
    var hasGivenUp: Boolean = false
        private set

    /** The rendered document the pane last showed, as `RichPreview` builds
     *  it. The policy outlives the pane — it belongs to the document, not to
     *  a mode (see `DocumentViewModel.previewRetry`) — so a pane composed
     *  again by a mode switch must not report its first document as a change:
     *  that would lift a give-up for a document that has not changed, and
     *  every visit to Preview would cost two more renderer deaths. iOS keeps
     *  the give-up across a mode switch; so does this. */
    internal var shownDocument: String? = null

    /** Fold an event in and say what to do about it. */
    fun handle(event: Event): Action = when (event) {
        Event.TERMINATED -> {
            // Already given up: a page that is not being reloaded can still
            // report its process dying (the give-up itself leaves a dead
            // process behind). Count it, but do not act on it — acting is
            // exactly the loop this policy exists to stop.
            if (hasGivenUp) {
                Action.NONE
            } else {
                failures += 1
                if (failures < LIMIT) {
                    Action.RELOAD
                } else {
                    hasGivenUp = true
                    Action.GIVE_UP
                }
            }
        }
        Event.RENDERED -> {
            // The page survived its own render — engines and all. That, and
            // only that, ends the run: the thing that killed the process
            // demonstrably did not kill it this time.
            failures = 0
            hasGivenUp = false
            Action.NONE
        }
        Event.DOCUMENT_CHANGED -> {
            // A different document is worth trying, so the pane stops
            // having given up and the next death is answered again. The
            // count stands, though: nothing rendered, and an edit that
            // zeroed it would hand a reader typing in Split an endless
            // supply of retries for a document that cannot render.
            hasGivenUp = false
            Action.NONE
        }
    }

    companion object {
        /** How many terminations in a row are worth a reload. The second one
         *  is the one that gives up: the first reload is the cure, and a
         *  failure straight after it says the cure is not working. */
        const val LIMIT = 2
    }
}
