/*
 * TextCodecTest.kt
 * md (Android)
 *
 * Pins the decode trial order — in particular that BOM-less legacy
 * single-byte files are never mistaken for UTF-16 (the mojibake the
 * BOM gate exists to prevent), mirroring the iOS/macOS codec tests.
 */

package me.nettrash.md

import org.junit.Assert.assertEquals
import org.junit.Test
import java.nio.charset.Charset

class TextCodecTest {

    @Test
    fun decodesUtf8() {
        assertEquals("# Привет\n", TextCodec.decode("# Привет\n".toByteArray(Charsets.UTF_8)))
    }

    @Test
    fun doesNotMistakeBomlessCp1251ForUtf16() {
        // Cyrillic prose in Windows-1251 — even-length and BOM-less, the
        // shape a naive UTF-16 trial happily (and wrongly) accepts as CJK
        // mojibake. It must come back as the Cyrillic text it is.
        val original = "Привет, мир!"
        val data = original.toByteArray(Charset.forName("windows-1251"))
        assertEquals(original, TextCodec.decode(data))
    }

    @Test
    fun readsBomedUtf16() {
        val original = "# Chapter\n"
        // Charsets.UTF_16 writes a BOM when encoding.
        val data = original.toByteArray(Charsets.UTF_16)
        assertEquals(original, TextCodec.decode(data))
    }

    @Test
    fun fallsBackLosslesslyForArbitraryBytes() {
        // ISO-8859-1 maps every byte, so even binary-ish input decodes to
        // *something* rather than throwing — one char per byte.
        val data = byteArrayOf(0x41, 0xFF.toByte(), 0x42)
        assertEquals(3, TextCodec.decode(data).length)
    }
}
