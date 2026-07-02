/*
 * ViewMode.kt
 * md (Android)
 *
 * The editor's three display modes and the width-adaptive rule for which of
 * them are offered. Mirrors the iOS `DocumentView.swift`: a phone-width
 * window offers only Edit and Preview, while a wide window — a tablet, an
 * unfolded foldable, a desktop / freeform window, or a large phone in
 * landscape — also offers Split, the two panes side by side re-rendering as
 * you type. macOS always has room and shows all three; on Android, as on
 * iPhone, Split is dropped when there is no horizontal room for it.
 *
 * Kept free of Compose imports so the rule is plain, unit-testable Kotlin;
 * `EditorScreen` supplies the current window width and renders the result.
 */

package me.nettrash.md.ui

/** Editor display modes, in the order they appear in the switch. */
internal enum class Mode { EDIT, SPLIT, PREVIEW }

/**
 * The window width (in dp) at or above which Split is offered. 600dp is the
 * Material compact/medium boundary — phones in portrait fall below it while
 * tablets, unfolded foldables, desktop windows and large phones in landscape
 * sit above — the closest analogue to iOS's compact-vs-regular horizontal
 * size class, which is what gates Split there.
 */
internal const val WIDE_LAYOUT_MIN_WIDTH_DP = 600

/** Whether a window this wide (dp) has room to offer Split. */
internal fun isWideLayout(widthDp: Int): Boolean = widthDp >= WIDE_LAYOUT_MIN_WIDTH_DP

/**
 * The modes offered at the current width: all three when there's room for
 * Split, otherwise just Edit and Preview (like iPhone).
 */
internal fun availableModes(isWide: Boolean): List<Mode> =
    if (isWide) listOf(Mode.EDIT, Mode.SPLIT, Mode.PREVIEW)
    else listOf(Mode.EDIT, Mode.PREVIEW)

/**
 * The mode actually shown: the remembered preference, coerced to one the
 * current width supports. A remembered Split collapses to Edit on a narrow
 * window — without discarding the stored preference, so widening the window
 * (rotating, unfolding, leaving split-screen) brings Split back. Mirrors the
 * iOS `effectiveMode`.
 */
internal fun effectiveMode(stored: Mode, isWide: Boolean): Mode {
    val available = availableModes(isWide)
    return when {
        stored in available -> stored
        stored == Mode.PREVIEW -> Mode.PREVIEW
        else -> Mode.EDIT
    }
}
