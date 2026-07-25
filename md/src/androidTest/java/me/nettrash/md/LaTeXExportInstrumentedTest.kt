/*
 * LaTeXExportInstrumentedTest.kt
 * md (Android)
 *
 * The `.tex` export is pure string work and [LaTeXExportTest] covers it
 * densely — but it covers it on the desktop JVM, and two of the things it
 * pins are not the JVM's to decide:
 *
 *  1. **The regular-expression engine.** Android's is ICU's, the desktop's
 *     is `java.util.regex`, and they disagree about `\w` outright — which
 *     is why the word guards around inline math and underscore italic are
 *     spelled `[\p{L}\p{N}_]`. The whole export exists to carry the
 *     author's mathematics, so "is this span a formula" has to have the
 *     same answer on the phone as in the unit tests and on iOS.
 *
 *  2. **The UTF-8 decoder.** An image path is percent-decoded through a
 *     `CharsetDecoder` set to report malformed input rather than repair
 *     it; a decoder that repaired instead would put U+FFFD into a file
 *     name and the graphic would silently not be in the PDF.
 *
 * Run with:
 *   ./gradlew :md:connectedDebugAndroidTest
 */

package me.nettrash.md

import androidx.test.ext.junit.runners.AndroidJUnit4
import me.nettrash.md.markdown.LaTeXExport
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LaTeXExportInstrumentedTest {

    /** The body between `\begin{document}` and `\end{document}`, trimmed. */
    private fun texBody(source: String): String {
        val tex = LaTeXExport.document(source)
        val opening = "\\begin{document}\n"
        val start = tex.indexOf(opening)
        val end = tex.lastIndexOf("\n\\end{document}")
        return tex.substring(start + opening.length, end).trim()
    }

    @Test fun wordGuardMeansTheSameThingOnICU() {
        // A superscript two is `\p{N}` and is not in ICU's `\w`; a
        // combining mark is in ICU's `\w` and is none of `\p{L}`, `\p{N}`
        // or `_`. Either one would flip on a `\w` guard, and the phone
        // would call a different span mathematics from the tests.
        assertEquals("\u00b2\\\$x\\\$", texBody("\u00b2\$x\$"))
        assertEquals("\u00b2\\_x\\_", texBody("\u00b2_x_"))
        assertEquals("e\u0301\$x\$", texBody("e\u0301\$x\$"))
        assertEquals("e\u0301\\emph{x}", texBody("e\u0301_x_"))
        // And the plain cases still behave: a formula is a formula, a
        // price is not, and snake_case is not italic.
        assertEquals("Then \$a_1^{2}\$ follows.", texBody("Then \$a_1^{2}\$ follows."))
        assertEquals("It costs \\\$5 and \\\$10.", texBody("It costs \$5 and \$10."))
        assertEquals("a\\_b\\_c is one word", texBody("a_b_c is one word"))
    }

    @Test fun percentDecodingMeansTheSameThingOnART() {
        assertEquals("my dir/a.png", LaTeXExport.percentDecoded("my%20dir/a.png"))
        assertEquals("\u041f.png", LaTeXExport.percentDecoded("%D0%9F.png"))
        // Not percent-encoding, and not repaired into one: the decoder
        // reports malformed input rather than handing back U+FFFD.
        assertEquals("100%.png", LaTeXExport.percentDecoded("100%.png"))
        assertEquals("a%C0%80b", LaTeXExport.percentDecoded("a%C0%80b"))
    }
}
