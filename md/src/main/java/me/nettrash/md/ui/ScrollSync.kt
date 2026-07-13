/*
 * ScrollSync.kt
 * md (Android)
 *
 * Links the two panes of Split so they scroll as one: each pane reports
 * the fraction of its scrollable range it sits at, and the other follows.
 * Proportional, not line-mapped — the panes' heights diverge around tall
 * rendered content (a diagram is one source line), but the neighborhood
 * always matches, which is what side-by-side writing needs.
 *
 * A plain class, deliberately not Compose state: scroll events arrive at
 * display rate and must never recompose anything. Each pane registers its
 * "follow" closure here and calls the opposite report method; echo
 * suppression lives inside the panes — the editor consumes the exact
 * relayed scroll target, the preview timestamps its programmatic ones (see
 * SyncWebView) — so a relayed scroll never relays back.
 */

package me.nettrash.md.ui

class ScrollSync {
    /** Set by the editor pane: scroll the editor to a fraction [0, 1]. */
    var scrollEditor: ((Float) -> Unit)? = null

    /** Set by the preview pane: scroll the preview to a fraction [0, 1]. */
    var scrollPreview: ((Float) -> Unit)? = null

    fun editorDidScroll(fraction: Float) {
        scrollPreview?.invoke(fraction)
    }

    fun previewDidScroll(fraction: Float) {
        scrollEditor?.invoke(fraction)
    }
}
