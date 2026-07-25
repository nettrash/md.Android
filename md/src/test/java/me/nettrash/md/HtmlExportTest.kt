/*
 * HtmlExportTest.kt
 * md (Android)
 *
 * The pure side of Export as HTML — one self-contained file that opens
 * anywhere with no engines, no folder of assets and no network. Mirrors the
 * iOS mdTests "HTML export" section.
 *
 * The font inlining reads bundled assets, which aren't on the unit-test
 * classpath — so `HtmlExport.embeddedKatexCss` takes its reader as a
 * parameter (the AssetManager in the app, a plain file read here). That is
 * what keeps this test a plain JVM one, next to its siblings, instead of an
 * instrumented test that needs a device to check string work.
 */

package me.nettrash.md

import me.nettrash.md.markdown.HtmlExport
import me.nettrash.md.markdown.MarkdownHtml
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class HtmlExportTest {

    /** `assets/rich`, read off disk the way ExamplesTest reads the samples. */
    private val assetsDir: File = run {
        val direct = File("src/main/assets")
        if (direct.exists()) direct
        else File(checkNotNull(System.getProperty("user.dir")) { "no user.dir" })
            .resolve("src/main/assets")
    }

    private fun readAsset(path: String): ByteArray? =
        assetsDir.resolve(path).takeIf { it.isFile }?.readBytes()

    @Test fun embeddedKatexCssInlinesEveryFaceAsWoff2() {
        val css = HtmlExport.embeddedKatexCss(::readAsset)
        assertTrue("the app should bundle rich/katex.min.css", css != null)
        checkNotNull(css)

        // Every face must end up as a data: URI. A relative `fonts/…` path
        // left behind is a dead link in a file that has no folder beside it.
        assertFalse("no relative font path may survive", css.contains("url(fonts/"))
        assertTrue(css.contains("url(data:font/woff2;base64,"))

        // Only woff2 — carrying the woff and ttf alternates as well would
        // quadruple the payload for formats nothing in use needs.
        assertFalse(css.contains("format(\"woff\")"))
        assertFalse(css.contains("format(\"truetype\")"))

        // One embedded source per face, and the stylesheet's own rules are
        // still there — a formula is positioned by this CSS, not just glyphed.
        val faces = css.split("@font-face").size - 1
        val embedded = css.split("url(data:font/woff2;base64,").size - 1
        assertEquals("every @font-face carries exactly one embedded source", faces, embedded)
        assertTrue(css.contains(".katex"))
    }

    @Test fun embeddedKatexCssLeavesARuleAloneWhenItsFaceIsMissing() {
        // A missing face must not produce a `src:` pointing at nothing —
        // better a rule that falls back to the reader's fonts than a broken
        // data: URI. (Only the stylesheet is served here.)
        val cssOnly = HtmlExport.embeddedKatexCss { path ->
            if (path == HtmlExport.KATEX_CSS_ASSET) readAsset(path) else null
        }
        checkNotNull(cssOnly)
        assertFalse(cssOnly.contains("url(data:font/woff2;base64,"))
        assertTrue(cssOnly.contains("url(fonts/"))

        // And no stylesheet at all is no stylesheet, not an empty one.
        assertEquals(null, HtmlExport.embeddedKatexCss { null })
    }

    @Test fun htmlExportKeepsPageBreaksVisible() {
        // The export stylesheet turns `\newpage` into `break-after: page`,
        // which means nothing on a screen — a reader scrolling the exported
        // file would find the author's page breaks silently gone. The HTML
        // export swaps that rule back for the dashed rule the preview shows.
        val exportCss = MarkdownHtml.document("a\n\n\\newpage\n\nb", "t", dark = false, export = true)
        assertTrue(
            "the PDF path still wants a real page break",
            exportCss.contains("break-after: page"),
        )
        assertTrue(exportCss.contains("<div class=\"md-pagebreak\"></div>"))

        val page = HtmlExport.visiblePageBreaks(exportCss)
        assertFalse(page.contains("break-after: page"))
        assertTrue(page.contains(".md-pagebreak { border-top: 2px dashed rgba(43,38,32,0.16); margin: 1.6em 0; }"))
        // The marker itself is untouched — only the rule that styles it.
        assertTrue(page.contains("<div class=\"md-pagebreak\"></div>"))
    }

    @Test fun onlyADocumentWithMathCarriesTheFontPayload() {
        // The trigger is the same signal that decided whether to load KaTeX
        // in the first place, so a plain document exports without ~300 KB of
        // fonts riding along.
        val math = MarkdownHtml.document("\$\\alpha\$", "t", dark = false, export = true)
        val plain = MarkdownHtml.document("plain prose, \$5 and \$10", "t", dark = false, export = true)
        assertTrue(HtmlExport.needsKatex(math))
        assertFalse(HtmlExport.needsKatex(plain))
    }

    @Test fun theEmbeddedStylesheetTravelsWithItsLicenceNotice() {
        val page = HtmlExport.withEmbeddedKatex("<html><head><title>t</title></head><body></body></html>", ".katex{}")
        // Inside the head, and after the document's own styles.
        assertTrue(page.contains("<title>t</title><style>.katex{}</style>"))
        assertTrue(page.endsWith("</head><body></body></html>"))
        // The faces are OFL 1.1 with a reserved name; the notice has to
        // travel with them.
        assertTrue(page.contains("SIL Open Font"))
        assertTrue(page.contains("Reserved Font"))
        assertTrue(page.contains("https://katex.org"))
    }
}
