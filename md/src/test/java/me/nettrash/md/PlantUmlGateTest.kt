/*
 * PlantUmlGateTest.kt
 * md (Android)
 *
 * The one deliberate patch inside a vendored engine, pinned (#9).
 *
 * The bundled browser build of PlantUML (1.2026.4beta4) compares a laid-out
 * diagram's width and height against 4096.0 and, past either, throws the
 * drawing away for "Diagram too large for browser rendering: 7655x1445 (max
 * 4096)". md.vscode raised both literals to 1.0E9 in 1.1 and measured the
 * result in a real browser (its test/plantuml-limit.test.ts tells the
 * story); the app's copy now carries the same two literals and is
 * byte-identical to the extension's.
 *
 * A re-vendored plantuml.js would bring the gate back silently. This test
 * fails loudly instead, by design: the pinned fragment is minifier output a
 * fresh build will not reproduce. Re-apply with
 *
 *     perl -pi -e 's/o<=4096\.0\)\{p=m\.bDm;if\(p<=4096\.0\)/o<=1.0E9){p=m.bDm;if(p<=1.0E9)/' md/src/main/assets/rich/plantuml.js
 *
 * (adjusting the identifiers to the new build), re-verify, and update the pin.
 *
 * The one consumer here that rasterises — the EPUB snapshot — is bounded by
 * CaptureBudget.MAX_PIXELS, also pinned below: the page caps a
 * diagram's width, nothing caps its height.
 */

package me.nettrash.md

import me.nettrash.md.ui.CaptureBudget
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class PlantUmlGateTest {

    private val source: String = run {
        val relative = "src/main/assets/rich/plantuml.js"
        val direct = File(relative)
        val file = if (direct.exists()) direct
        else File(checkNotNull(System.getProperty("user.dir")) { "no user.dir" }).resolve(relative)
        file.readText(Charsets.ISO_8859_1)
    }

    @Test fun holdsTheRaisedGateOnce() {
        val gate = "o<=1.0E9){p=m.bDm;if(p<=1.0E9)"
        assertEquals(1, source.split(gate).size - 1)
    }

    @Test fun holdsNo4096PixelComparison() {
        // `4096` alone appears legitimately (palettes, Unicode tables); the
        // gate compared doubles, and no other 4096.0 exists in the build.
        assertFalse(source.contains("4096.0"))
    }

    @Test fun anOrdinaryDiagramIsCapturedAtFullDensity() {
        assertEquals(2.625f, CaptureBudget.scale(595f, 800f, 2.625f))
        assertEquals(3f, CaptureBudget.scale(0f, 0f, 3f))
    }

    @Test fun aVeryTallDiagramIsCapturedWithinTheBudget() {
        val density = 2.625f
        // 400 × 20 000 CSS px would be 1050 × 52 500 device px — 55 Mpx.
        val scale = CaptureBudget.scale(400f, 20_000f, density)
        assertTrue(scale < density)
        val pixels = (400f * scale).toLong() * (20_000f * scale).toLong()
        assertTrue("$pixels px", pixels <= CaptureBudget.MAX_PIXELS)
        assertTrue("not needlessly small", pixels > CaptureBudget.MAX_PIXELS * 9 / 10)
    }
}
