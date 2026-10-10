/*
 * CaptureBudget.kt
 * md (Android)
 *
 * How large a snapshot of one rich element the EPUB export may take. Pure,
 * so the JVM tests reach it (EpubExporter itself needs a main Looper).
 */

package me.nettrash.md.ui

import kotlin.math.sqrt

object CaptureBudget {

    /** The most pixels one rich element's snapshot may take: 16 Mpx, 64 MB
     *  of ARGB. A diagram's width is capped by the page (`max-width: 100%`),
     *  but its height is not — and since 1.6 PlantUML no longer refuses a
     *  diagram past 4096 px (#9), so a tall one could ask for a bitmap the
     *  heap cannot hold. The out-of-memory error would be caught, and with it
     *  every snapshot of the document would be lost. */
    const val MAX_PIXELS = 16L * 1024 * 1024

    /** The scale a [cssWidth] × [cssHeight] element is captured at: the
     *  display [density], or less when that would pass [MAX_PIXELS]. */
    fun scale(cssWidth: Float, cssHeight: Float, density: Float): Float {
        val area = cssWidth.toDouble() * cssHeight.toDouble()
        if (area <= 0.0) return density
        val fit = sqrt(MAX_PIXELS / area).toFloat()
        return minOf(density, fit)
    }
}
