/*
 * PageSize.kt
 * md (Android)
 *
 * The PDF/print "trim" sizes and the app-wide remembered choice.
 *
 * [PageSize] is the pure, JVM-testable side: the shared table of named page
 * sizes in PostScript points, and the CSS margin each one scales to. It is the
 * single source of truth the three platforms copy VERBATIM — iOS feeds the
 * points to paperRect/printableRect, macOS to NSPrintInfo.paperSize, and here
 * Exporter builds a custom PrintAttributes.MediaSize from them (see
 * Exporter.mediaSize / styledForExport) — so the numbers must live in exactly
 * one place per platform and never be typed twice; a drift here would paginate
 * the same document differently on Android than on iOS.
 *
 * [PageSizeState] is the impure side: the last-chosen size, persisted in
 * SharedPreferences under the app-wide key "md.pdfPageSize" and exposed as
 * Compose state so both the document share menu and the book navigator read and
 * write one choice (the same class-holding-mutableStateOf pattern as BookState).
 */

package me.nettrash.md.ui

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.edit
import kotlin.math.roundToInt

/** A named PDF page ("trim") size in PostScript points — 1 inch = 72 pt.
 *
 *  A4 keeps the historical `595.2 × 841.8` (210 × 297 mm rounded to a tenth of
 *  a point — the value the app paginated to before trim sizes existed) purely
 *  as the reference the margin scaling divides by, so choosing A4 reproduces
 *  the old `48px 56px` margin to the pixel; the *page* an A4 export prints on
 *  stays the framework's own ISO_A4 (see Exporter.mediaSize), so that default
 *  export is byte-for-byte what it always was. The imperial sizes are exact
 *  (6 × 9" = 432 × 648 pt); A5 is 148 × 210 mm converted the same way A4 was. */
data class PageSize(
    val id: String,       // stable SharedPreferences key / cross-platform parity — never localized
    val label: String,    // the menu title
    val width: Double,    // points, portrait
    val height: Double,
) {
    /** The body margin (CSS `padding`) for this trim size, scaled down from
     *  A4's `48px 56px` so a small page doesn't wear A4-sized margins — a 6 × 9"
     *  booklet with A4 margins wastes a quarter of its width. Each axis scales
     *  with its own dimension, so A4 reproduces `48px 56px` to the pixel (the
     *  historical value, hence an A4 export is byte-for-byte what it always was)
     *  and every smaller page gets a proportionate frame; Letter, wider than A4,
     *  grows horizontally. Rounded to whole pixels: sub-pixel margins are
     *  invisible, and the integer string is what keeps the A4 case identical.
     *  `roundToInt` is round-half-up, which agrees with Swift's `.rounded()` for
     *  these (all-positive) values. */
    val cssPadding: String
        get() {
            val vertical = (48.0 * height / A4.height).roundToInt()
            val horizontal = (56.0 * width / A4.width).roundToInt()
            return "${vertical}px ${horizontal}px"
        }

    companion object {
        val A4 = PageSize("a4", "A4", 595.2, 841.8)
        val A5 = PageSize("a5", "A5", 419.5, 595.3)
        val US_LETTER = PageSize("letter", "US Letter", 612.0, 792.0)
        val US_LEGAL = PageSize("legal", "US Legal", 612.0, 1008.0)
        val SIX_BY_NINE = PageSize("6x9", "6 × 9\"", 432.0, 648.0)
        val FIVE_BY_EIGHT = PageSize("5x8", "5 × 8\"", 360.0, 576.0)
        val DIGEST = PageSize("5.5x8.5", "5.5 × 8.5\"", 396.0, 612.0)

        /** Every offered size, in menu order — A4 first, since it is the default. */
        val ALL: List<PageSize> = listOf(A4, A5, US_LETTER, US_LEGAL, SIX_BY_NINE, FIVE_BY_EIGHT, DIGEST)

        /** The size stored under [id], falling back to A4 for an empty or
         *  unknown key — so a first launch, or a preference written by some
         *  future version that offered a size this build doesn't, still lands on
         *  the default. */
        fun named(id: String): PageSize = ALL.firstOrNull { it.id == id } ?: A4
    }
}

/**
 * The last-chosen PDF page size, remembered app-wide. Constructed with the
 * application context so it outlives any one screen; the choice is Compose
 * state so the menu's checkmark and both export surfaces react the instant it
 * changes, and mirrored into SharedPreferences so it survives a restart. One
 * instance is created in EditorScreen and handed to BookSheet, so the document
 * menu and the book navigator share a single choice (the book PDF is where trim
 * sizes matter most — no POD service accepts A4 interiors).
 */
class PageSizeState(context: Context) {

    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    var selected: PageSize by mutableStateOf(PageSize.named(prefs.getString(KEY, PageSize.A4.id) ?: PageSize.A4.id))
        private set

    /** Adopt [size] as the remembered choice and persist it. */
    fun choose(size: PageSize) {
        selected = size
        prefs.edit { putString(KEY, size.id) }
    }

    private companion object {
        const val PREFS = "export"
        const val KEY = "md.pdfPageSize"
    }
}
