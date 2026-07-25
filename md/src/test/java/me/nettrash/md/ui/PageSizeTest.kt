/*
 * PageSizeTest.kt
 * md (Android)
 *
 * JVM tests for the shared PDF trim-size table (ui/PageSize.kt) — the pure
 * side: the agreed point dimensions, the id→size lookup with its A4 fallback,
 * and the per-axis margin scaling. The numbers here are the same ones the iOS
 * and macOS siblings carry, so a drift on any platform fails a test rather than
 * paginating the same document differently. The persisted choice
 * (PageSizeState) is a thin SharedPreferences wrapper, exercised on a device.
 */

package me.nettrash.md.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class PageSizeTest {

    @Test fun tableHasTheAgreedPointDimensions() {
        // Portrait width × height in points (1 inch = 72 pt). Imperial sizes
        // exact; A4 is the historical 595.2 × 841.8, A5 148 × 210 mm.
        fun dims(size: PageSize) = size.width to size.height
        assertEquals(595.2 to 841.8, dims(PageSize.A4))
        assertEquals(419.5 to 595.3, dims(PageSize.A5))
        assertEquals(612.0 to 792.0, dims(PageSize.US_LETTER))
        assertEquals(612.0 to 1008.0, dims(PageSize.US_LEGAL))
        assertEquals(432.0 to 648.0, dims(PageSize.SIX_BY_NINE))
        assertEquals(360.0 to 576.0, dims(PageSize.FIVE_BY_EIGHT))
        assertEquals(396.0 to 612.0, dims(PageSize.DIGEST))

        // Menu order is A4-first (the default), then the agreed sequence.
        assertEquals(
            listOf("a4", "a5", "letter", "legal", "6x9", "5x8", "5.5x8.5"),
            PageSize.ALL.map { it.id },
        )
        assertSame(PageSize.A4, PageSize.ALL.first())
        // Labels are the menu titles (never the id) — the imperial ones carry
        // their inch mark and a real × sign.
        assertEquals("US Letter", PageSize.US_LETTER.label)
        assertEquals("6 × 9\"", PageSize.SIX_BY_NINE.label)
        assertEquals("5.5 × 8.5\"", PageSize.DIGEST.label)
    }

    @Test fun namedRoundTripsAndDefaultsToA4() {
        // Every id round-trips to its own entry …
        for (size in PageSize.ALL) {
            assertSame(size, PageSize.named(size.id))
        }
        // … and an empty or unknown key falls back to A4 (first launch, or a
        // preference from some future build that offered a size this one lacks).
        assertSame(PageSize.A4, PageSize.named(""))
        assertSame(PageSize.A4, PageSize.named("a6"))
        assertSame(PageSize.A4, PageSize.named("A4")) // ids are lower-case; the wrong case is unknown
    }

    @Test fun a4MarginIsUnchangedAndSmallerPagesScaleTheMarginDown() {
        // A4 reproduces the historical margin to the pixel — this is what keeps
        // the default export byte-for-byte what it always was.
        assertEquals("48px 56px", PageSize.A4.cssPadding)
        // Each axis scales with its own dimension: smaller pages shrink both …
        assertEquals("34px 39px", PageSize.A5.cssPadding)
        assertEquals("37px 41px", PageSize.SIX_BY_NINE.cssPadding)
        assertEquals("33px 34px", PageSize.FIVE_BY_EIGHT.cssPadding)
        assertEquals("35px 37px", PageSize.DIGEST.cssPadding)
        // … while Letter, wider than A4, GROWS horizontally (58 > 56) — proof
        // the scaling is proportional, not a blanket cap.
        assertEquals("45px 58px", PageSize.US_LETTER.cssPadding)
        assertEquals("57px 58px", PageSize.US_LEGAL.cssPadding)
    }
}
