/*
 * CaptureBudget.kt
 * md (Android)
 *
 * How large a snapshot of one rich element the EPUB export may take. Pure,
 * so the JVM tests reach it (EpubExporter itself needs a main Looper).
 */

package me.nettrash.md.ui

import kotlin.math.ceil
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

    /** One element's capture: the scale it is drawn at, and the bitmap it is
     *  drawn into. */
    data class Capture(val scale: Float, val width: Int, val height: Int)

    /** The capture for a [cssWidth] × [cssHeight] element: [scale], with the
     *  bitmap's dimensions rounded up (and at least one pixel) exactly as
     *  they are allocated — and the scale shrunk until *those* stay within
     *  [MAX_PIXELS], which the unrounded area alone does not promise. */
    fun capture(cssWidth: Float, cssHeight: Float, density: Float): Capture {
        var s = scale(cssWidth, cssHeight, density)
        repeat(64) {
            val width = ceil(cssWidth * s).toInt().coerceAtLeast(1)
            val height = ceil(cssHeight * s).toInt().coerceAtLeast(1)
            val pixels = width.toLong() * height.toLong()
            if (pixels <= MAX_PIXELS) return Capture(s, width, height)
            s *= (sqrt(MAX_PIXELS.toDouble() / pixels) * 0.999).toFloat()
        }
        return Capture(s, 1, 1)
    }
}
