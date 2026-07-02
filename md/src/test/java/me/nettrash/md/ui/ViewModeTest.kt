/*
 * ViewModeTest.kt
 * md (Android)
 *
 * JVM unit tests for the width-adaptive mode rule (`ViewMode.kt`): which
 * modes a window offers at a given width, and how a remembered Split is
 * coerced on a narrow window without being discarded. Mirrors the intent of
 * the iOS `availableModes` / `effectiveMode` on `DocumentView`.
 */

package me.nettrash.md.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ViewModeTest {

    // isWideLayout — the 600dp compact/medium boundary

    @Test fun narrowPhonePortraitIsNotWide() {
        assertFalse(isWideLayout(360))
        assertFalse(isWideLayout(411))
        assertFalse(isWideLayout(WIDE_LAYOUT_MIN_WIDTH_DP - 1))
    }

    @Test fun tabletAndLandscapeAreWide() {
        assertTrue(isWideLayout(WIDE_LAYOUT_MIN_WIDTH_DP))
        assertTrue(isWideLayout(800))
        assertTrue(isWideLayout(1280))
    }

    // availableModes — Split only when there's room

    @Test fun narrowOffersEditAndPreviewOnly() {
        assertEquals(listOf(Mode.EDIT, Mode.PREVIEW), availableModes(isWide = false))
    }

    @Test fun wideOffersAllThree() {
        assertEquals(listOf(Mode.EDIT, Mode.SPLIT, Mode.PREVIEW), availableModes(isWide = true))
    }

    // effectiveMode — coercion without discarding the stored preference

    @Test fun storedSplitCollapsesToEditWhenNarrow() {
        assertEquals(Mode.EDIT, effectiveMode(Mode.SPLIT, isWide = false))
    }

    @Test fun storedSplitStaysSplitWhenWide() {
        assertEquals(Mode.SPLIT, effectiveMode(Mode.SPLIT, isWide = true))
    }

    @Test fun editAndPreviewSurviveBothWidths() {
        for (wide in listOf(true, false)) {
            assertEquals(Mode.EDIT, effectiveMode(Mode.EDIT, wide))
            assertEquals(Mode.PREVIEW, effectiveMode(Mode.PREVIEW, wide))
        }
    }

    @Test fun coercionRoundTripsWhenWindowWidensAgain() {
        // A remembered Split shown as Edit on a phone must return to Split
        // once the window is wide again — the raw preference is never lost.
        val stored = Mode.SPLIT
        assertEquals(Mode.EDIT, effectiveMode(stored, isWide = false))
        assertEquals(Mode.SPLIT, effectiveMode(stored, isWide = true))
    }
}
