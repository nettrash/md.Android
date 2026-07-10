/*
 * PdfLayout.kt
 * md (Android)
 *
 * The persistent "PDF layout" setting: whether Share Rendered PDF and
 * Export as PDF produce one content-tall page per `\newpage` section (the
 * classic export — see Exporter.renderSinglePdf) or real A4 pages,
 * paginated line-aware by the WebView print pipeline (Exporter.renderA4Pdf).
 * Stored in the app-level "settings" SharedPreferences; the editor's
 * "PDF Layout…" dialog reads and writes it.
 */

package me.nettrash.md.ui

import android.content.Context
import androidx.core.content.edit

enum class PdfLayout(private val storedValue: String, val label: String) {
    /** One page exactly as tall as the content (per `\newpage` section) —
     *  nothing sliced at a page boundary. The default. */
    SINGLE("single", "One long page"),

    /** Real A4 pages with line-aware page breaks (`\newpage` honored via
     *  `break-after: page`). */
    A4("a4", "A4 pages");

    companion object {
        private const val PREFS_NAME = "settings"
        private const val KEY_PDF_LAYOUT = "pdf_layout"

        /** The stored choice, defaulting to [SINGLE] — today's behavior —
         *  when nothing (or something unrecognized) is stored. */
        fun load(context: Context): PdfLayout {
            val stored = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getString(KEY_PDF_LAYOUT, null)
            return entries.firstOrNull { it.storedValue == stored } ?: SINGLE
        }

        fun save(context: Context, layout: PdfLayout) {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit { putString(KEY_PDF_LAYOUT, layout.storedValue) }
        }
    }
}
