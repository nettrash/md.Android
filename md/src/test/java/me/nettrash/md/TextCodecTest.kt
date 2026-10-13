/*
 * TextCodecTest.kt
 * md (Android)
 *
 * Pins the decode trial order — in particular that BOM-less legacy
 * single-byte files are never mistaken for UTF-16 (the mojibake the BOM gate
 * exists to prevent) — and, from 1.6, the round trip: a file is written back
 * in its own encoding and line endings, a UTF-8 BOM is dropped, and a
 * character the encoding cannot hold upgrades the file to UTF-8. The cases
 * are md.win's PlainTextCodecTests and LineEndings rules, so the four apps
 * read and write a file's bytes the same way.
 */

package me.nettrash.md

import me.nettrash.md.markdown.MarkdownBlock
import me.nettrash.md.markdown.MarkdownParser
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.charset.Charset

class TextCodecTest {

    private fun cp1251(s: String) = s.toByteArray(Charset.forName("windows-1251"))
    private fun bytes(vararg b: Int) = ByteArray(b.size) { b[it].toByte() }

    // ---- decode ---------------------------------------------------------

    @Test
    fun decodesUtf8() {
        assertEquals(TextCodec.Decoded("# Привет\n", TextEncoding.UTF8), TextCodec.decodeWithEncoding("# Привет\n".toByteArray()))
        assertEquals(TextCodec.Decoded("", TextEncoding.UTF8), TextCodec.decodeWithEncoding(ByteArray(0)))
    }

    @Test
    fun doesNotMistakeBomlessCp1251ForUtf16() {
        // Cyrillic prose in Windows-1251 — even-length and BOM-less, the
        // shape a naive UTF-16 trial happily (and wrongly) accepts as CJK
        // mojibake. It must decode as CP1251 and round-trip byte-exactly.
        val original = "Привет, мир!"
        val data = cp1251(original)
        assertArrayEquals(bytes(0xCF, 0xF0, 0xE8, 0xE2, 0xE5, 0xF2, 0x2C, 0x20, 0xEC, 0xE8, 0xF0, 0x21), data)
        val decoded = TextCodec.decodeWithEncoding(data)
        assertEquals(TextCodec.Decoded(original, TextEncoding.WINDOWS_1251), decoded)
        assertArrayEquals(data, TextCodec.encode(decoded.text, decoded.encoding).data)
    }

    @Test
    fun roundTripsBomedUtf16() {
        val original = "# Chapter\n"
        val data = bytes(0xFF, 0xFE) + original.toByteArray(Charsets.UTF_16LE)
        val decoded = TextCodec.decodeWithEncoding(data)
        assertEquals(TextCodec.Decoded(original, TextEncoding.UTF16), decoded)
        val encoded = TextCodec.encode(original, TextEncoding.UTF16)
        assertEquals(TextEncoding.UTF16, encoded.encoding)
        assertArrayEquals(data, encoded.data)
        assertArrayEquals(bytes(0xFF, 0xFE), TextCodec.encode("", TextEncoding.UTF16).data)
    }

    @Test
    fun honoursABigEndianBomAndWritesLittleEndian() {
        assertEquals(
            TextCodec.Decoded("AП", TextEncoding.UTF16),
            TextCodec.decodeWithEncoding(bytes(0xFE, 0xFF, 0x00, 0x41, 0x04, 0x1F)),
        )
        assertArrayEquals(bytes(0xFF, 0xFE, 0x41, 0x00, 0x1F, 0x04), TextCodec.encode("AП", TextEncoding.UTF16).data)
    }

    @Test
    fun stripsAUtf8BomOnDecodeAndWritesNoneBack() {
        // Java's UTF-8 decoder keeps EF BB BF as U+FEFF; md drops it, as
        // Foundation does, and never writes one back (decided 2026-10-10).
        val decoded = TextCodec.decodeWithEncoding(bytes(0xEF, 0xBB, 0xBF, 0x41))
        assertEquals(TextCodec.Decoded("A", TextEncoding.UTF8), decoded)
        val (text, dressing) = TextCodec.undress(bytes(0xEF, 0xBB, 0xBF) + "# Title\r\n".toByteArray())
        assertEquals("# Title\n", text)
        assertArrayEquals("# Title\r\n".toByteArray(), TextCodec.dress(text, dressing).data)
    }

    @Test
    fun aBomdFileShowsItsHeadingAndFrontMatter() {
        val (text, _) = TextCodec.undress(bytes(0xEF, 0xBB, 0xBF) + "---\ntitle: T\n---\n# Heading\n".toByteArray())
        assertFalse(text.startsWith("\uFEFF"))
        val blocks = MarkdownParser.parse(text)
        assertTrue(blocks.first() is MarkdownBlock.FrontMatter)
        assertEquals("Heading", MarkdownParser.outline(text).single().text)
    }

    @Test
    fun theParserIgnoresALeadingBomItIsHandedAnyway() {
        val withBom = "\uFEFF# Heading\n\nText.\n"
        assertEquals(MarkdownParser.parse(withBom.drop(1)), MarkdownParser.parse(withBom))
        assertEquals("Heading", MarkdownParser.outline(withBom).single().text)
        assertEquals(
            listOf(me.nettrash.md.markdown.MetadataField("title", "T")),
            MarkdownParser.frontMatter("\uFEFF---\ntitle: T\n---\n"),
        )
    }

    @Test
    fun rejectsInvalidUtf8StrictlyInsteadOfSubstituting() {
        val decoded = TextCodec.decodeWithEncoding(bytes(0xD0, 0x9F, 0xE0))
        assertEquals(TextEncoding.WINDOWS_1251, decoded.encoding)
        assertFalse(decoded.text.contains('�'))
        assertArrayEquals(bytes(0xD0, 0x9F, 0xE0), TextCodec.encode(decoded.text, decoded.encoding).data)
    }

    @Test
    fun anOddTailAfterAUtf16BomIsNotSwallowed() {
        val decoded = TextCodec.decodeWithEncoding(bytes(0xFF, 0xFE, 0x41))
        assertEquals(TextCodec.Decoded("яюA", TextEncoding.WINDOWS_1251), decoded)
        assertArrayEquals(bytes(0xFF, 0xFE, 0x41), TextCodec.encode(decoded.text, decoded.encoding).data)
    }

    @Test
    fun fallsBackLosslesslyForArbitraryBytes() {
        assertEquals(TextCodec.Decoded("AяB", TextEncoding.WINDOWS_1251), TextCodec.decodeWithEncoding(bytes(0x41, 0xFF, 0x42)))
        // 0x98 is the one byte Windows-1251 leaves undefined: ISO-8859-1 takes it.
        val latin = TextCodec.decodeWithEncoding(bytes(0x41, 0x98, 0x42))
        assertEquals(TextCodec.Decoded("A\u0098B", TextEncoding.ISO_8859_1), latin)
        assertArrayEquals(bytes(0x41, 0x98, 0x42), TextCodec.encode(latin.text, latin.encoding).data)
        val upgraded = TextCodec.encode("A\u0098B", TextEncoding.WINDOWS_1251)
        assertEquals(TextEncoding.UTF8, upgraded.encoding)
        assertArrayEquals(bytes(0x41, 0xC2, 0x98, 0x42), upgraded.data)
        assertEquals(TextEncoding.WINDOWS_1251, TextCodec.decodeWithEncoding(bytes(0x80, 0x99, 0x9F)).encoding)
    }

    // ---- encode ---------------------------------------------------------

    @Test
    fun upgradesToUtf8AndSaysSo() {
        val encoded = TextCodec.encode("Привет 🙂", TextEncoding.WINDOWS_1251)
        assertEquals(TextEncoding.UTF8, encoded.encoding)
        assertEquals("Привет 🙂", String(encoded.data, Charsets.UTF_8))
        val latin = TextCodec.encode("Привет", TextEncoding.ISO_8859_1)
        assertEquals(TextEncoding.UTF8, latin.encoding)
        assertArrayEquals("Привет".toByteArray(), latin.data)
    }

    @Test
    fun legacyEncodingRoundTripsThroughAnEdit() {
        val (text, dressing) = TextCodec.undress(cp1251("Привет, мир!"))
        assertEquals(TextDressing(TextEncoding.WINDOWS_1251, NewLine.LF), dressing)
        val edited = "$text Ещё."
        val encoded = TextCodec.dress(edited, dressing)
        assertEquals(TextEncoding.WINDOWS_1251, encoded.encoding)
        assertArrayEquals(cp1251(edited), encoded.data)
    }

    @Test
    fun encodingUtf8NeverFails() {
        val encoded = TextCodec.encode("a\uD800b", TextEncoding.UTF8)
        assertEquals(TextEncoding.UTF8, encoded.encoding)
        assertArrayEquals(bytes(0x61, 0xEF, 0xBF, 0xBD, 0x62), encoded.data)
        assertEquals(TextEncoding.UTF8, TextCodec.encode("a\uD800b", TextEncoding.WINDOWS_1251).encoding)
    }

    // ---- line endings ---------------------------------------------------

    @Test
    fun theFirstBreakDecides() {
        assertEquals(NewLine.LF, LineEndings.detect(""))
        assertEquals(NewLine.LF, LineEndings.detect("no break"))
        assertEquals(NewLine.LF, LineEndings.detect("a\nb\r\nc"))
        assertEquals(NewLine.CRLF, LineEndings.detect("a\r\nb\nc"))
        assertEquals(NewLine.LF, LineEndings.detect("a\rb"))
        assertEquals(NewLine.LF, LineEndings.detect("a\r"))
    }

    @Test
    fun normalizeTakesEveryMixtureToLf() {
        assertEquals("a\nb\nc\nd", LineEndings.normalize("a\r\nb\rc\nd"))
        assertEquals("a\n\nb", LineEndings.normalize("a\r\r\nb"))
        assertEquals("plain", LineEndings.normalize("plain"))
    }

    @Test
    fun aCrlfCp1251FileComesBackAsItWent() {
        val original = cp1251("# Заголовок\r\n\r\nТекст.\r\n")
        val (text, dressing) = TextCodec.undress(original)
        assertEquals("# Заголовок\n\nТекст.\n", text)
        assertEquals(TextDressing(TextEncoding.WINDOWS_1251, NewLine.CRLF), dressing)
        assertArrayEquals(original, TextCodec.dress(text, dressing).data)
        // An edit keeps both, and an emoji upgrades only the encoding.
        val emoji = TextCodec.dress("$text🙂\n", dressing)
        assertEquals(TextEncoding.UTF8, emoji.encoding)
        assertArrayEquals("# Заголовок\r\n\r\nТекст.\r\n🙂\r\n".toByteArray(), emoji.data)
    }

    @Test
    fun aLoneCrFileIsWrittenBackAsLf() {
        val (text, dressing) = TextCodec.undress("a\rb\r".toByteArray())
        assertEquals("a\nb\n", text)
        assertArrayEquals("a\nb\n".toByteArray(), TextCodec.dress(text, dressing).data)
    }

    @Test
    fun theDefaultIsUtf8Lf() {
        assertEquals(TextDressing(TextEncoding.UTF8, NewLine.LF), TextDressing.DEFAULT)
        assertArrayEquals("é\n".toByteArray(), TextCodec.dress("é\n", TextDressing.DEFAULT).data)
    }

    @Test
    fun aPastedBreakTakesTheFilesLineEnding() {
        val crlf = TextDressing(TextEncoding.UTF8, NewLine.CRLF)
        assertArrayEquals("a\r\nb\r\nc\r\nd".toByteArray(), TextCodec.dress("a\nb\r\nc\rd", crlf).data)
        assertArrayEquals("a\nb\nc\nd".toByteArray(), TextCodec.dress("a\nb\r\nc\rd", TextDressing.DEFAULT).data)
    }
}
