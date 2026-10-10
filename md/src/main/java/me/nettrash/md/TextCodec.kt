/*
 * TextCodec.kt
 * md (Android)
 *
 * Decoding and encoding for the plain-text files the app edits, with the
 * family's round-trip rule (1.6): a file is written back in the encoding and
 * the line endings it was read in, so nothing about it changes because it
 * was opened in md.
 *
 *  - The trial order is the iOS/macOS/Windows one: UTF-16 only behind an
 *    explicit byte-order mark (a strict UTF-16 decoder happily pairs up the
 *    bytes of almost any even-length legacy single-byte file into CJK
 *    mojibake), then strict UTF-8, strict Windows-1251, and ISO-8859-1, which
 *    maps every byte and so never fails.
 *  - A UTF-8 byte-order mark is dropped: Java's UTF-8 decoder keeps EF BB BF
 *    as a U+FEFF at the start of the text — an invisible character at the
 *    caret that also hid the first heading and the front matter from the
 *    parser — and the save writes none back, as Foundation does on the Mac
 *    and the iPhone (decided 2026-10-10).
 *  - A character the file's own encoding cannot hold — an emoji typed into a
 *    Windows-1251 file — saves the file as UTF-8 (md.win's `PlainTextCodec`
 *    upgrade), and the caller remembers the upgrade so the next autosave does
 *    not retry the encoding that cannot hold the text.
 *  - The text in memory is always LF. The first line break of the file
 *    decides how it is written back (md.win's `LineEndings`): CRLF stays
 *    CRLF; a lone CR, an LF and a file with no break at all write LF.
 *
 * Windows-1251 leaves exactly one byte undefined, 0x98, and Foundation
 * refuses it both ways; the codec refuses it by hand as md.win does, so the
 * answer does not depend on which charset table the device ships.
 */

package me.nettrash.md

import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

/** The encodings md reads and writes back. */
enum class TextEncoding { UTF8, UTF16, WINDOWS_1251, ISO_8859_1 }

/** The line break a file uses on disk. The text in memory is always LF. */
enum class NewLine { LF, CRLF }

/** Everything about a file except its text: what it is written back with. */
data class TextDressing(val encoding: TextEncoding, val newLine: NewLine) {
    companion object {
        /** A new document, shared-in text, an imported pack: UTF-8, LF. */
        val DEFAULT = TextDressing(TextEncoding.UTF8, NewLine.LF)
    }
}

/** Bytes to write, and the encoding actually used — not necessarily the one asked for. */
class EncodedText(val data: ByteArray, val encoding: TextEncoding)

object LineEndings {

    /** The first break decides; only CR+LF is CRLF. */
    fun detect(text: String): NewLine {
        for (i in text.indices) {
            when (text[i]) {
                '\n' -> return NewLine.LF
                '\r' -> return if (i + 1 < text.length && text[i + 1] == '\n') NewLine.CRLF else NewLine.LF
            }
        }
        return NewLine.LF
    }

    /** Any mixture of CRLF / CR / LF to LF — CRLF first, or each pair becomes two breaks. */
    fun normalize(text: String): String =
        if (text.indexOf('\r') < 0) text else text.replace("\r\n", "\n").replace('\r', '\n')

    /** LF text with [newLine] applied for writing. */
    fun apply(lfText: String, newLine: NewLine): String =
        if (newLine == NewLine.CRLF) lfText.replace("\n", "\r\n") else lfText
}

object TextCodec {

    private val windows1251: Charset? = runCatching { Charset.forName("windows-1251") }.getOrNull()
    private const val UNDEFINED_CP1251_BYTE: Byte = 0x98.toByte()
    private const val UNDEFINED_CP1251_CHAR = '\u0098'

    /** A decoded file: its text, exactly as decoded (BOM dropped, breaks untouched), and the encoding that matched. */
    data class Decoded(val text: String, val encoding: TextEncoding)

    /** Decode file bytes — see the file comment for the trial order. */
    fun decodeWithEncoding(data: ByteArray): Decoded {
        if (data.size >= 2 &&
            ((data[0] == 0xFF.toByte() && data[1] == 0xFE.toByte()) ||
             (data[0] == 0xFE.toByte() && data[1] == 0xFF.toByte()))
        ) {
            // Java's "UTF-16" reads the byte order from the BOM and consumes it.
            strict(Charsets.UTF_16, data)?.let { return Decoded(it, TextEncoding.UTF16) }
        }
        strict(Charsets.UTF_8, data)?.let { return Decoded(it.removePrefix("\uFEFF"), TextEncoding.UTF8) }
        if (UNDEFINED_CP1251_BYTE !in data) {
            windows1251?.let { cp1251 -> strict(cp1251, data)?.let { return Decoded(it, TextEncoding.WINDOWS_1251) } }
        }
        strict(Charsets.ISO_8859_1, data)?.let { return Decoded(it, TextEncoding.ISO_8859_1) }
        return Decoded(String(data, Charsets.UTF_8), TextEncoding.UTF8)
    }

    /** Decode file bytes as text (BOM dropped, line breaks as in the file). */
    fun decode(data: ByteArray): String = decodeWithEncoding(data).text

    /** Read a file for editing: LF text, and how to write it back. */
    fun undress(data: ByteArray): Pair<String, TextDressing> {
        val decoded = decodeWithEncoding(data)
        return LineEndings.normalize(decoded.text) to
            TextDressing(decoded.encoding, LineEndings.detect(decoded.text))
    }

    /** The bytes for LF [text] in [dressing]: its line breaks restored and its encoding, or UTF-8 when that cannot hold the text. Never a BOM for UTF-8. */
    fun dress(text: String, dressing: TextDressing): EncodedText =
        encode(LineEndings.apply(text, dressing.newLine), dressing.encoding)

    /** Encode in [preferred], upgrading to UTF-8 when the text does not fit. UTF-16 is written little-endian with a BOM. */
    fun encode(text: String, preferred: TextEncoding): EncodedText = when (preferred) {
        TextEncoding.UTF8 -> utf8(text)
        TextEncoding.UTF16 -> EncodedText(
            byteArrayOf(0xFF.toByte(), 0xFE.toByte()) +
                lenientEncode(Charsets.UTF_16LE, text, byteArrayOf(0xFD.toByte(), 0xFF.toByte())),
            TextEncoding.UTF16,
        )
        TextEncoding.WINDOWS_1251 -> {
            val cp1251 = windows1251
            if (cp1251 == null || text.indexOf(UNDEFINED_CP1251_CHAR) >= 0) utf8(text)
            else strictEncode(cp1251, text)?.let { EncodedText(it, TextEncoding.WINDOWS_1251) } ?: utf8(text)
        }
        TextEncoding.ISO_8859_1 ->
            strictEncode(Charsets.ISO_8859_1, text)?.let { EncodedText(it, TextEncoding.ISO_8859_1) } ?: utf8(text)
    }

    /** UTF-8 never fails. A lone surrogate becomes U+FFFD (EF BF BD), as Swift's
     *  `Data(text.utf8)` and .NET write it — not the `?` that Java's
     *  `toByteArray` substitutes. */
    private fun utf8(text: String) = EncodedText(
        lenientEncode(Charsets.UTF_8, text, byteArrayOf(0xEF.toByte(), 0xBF.toByte(), 0xBD.toByte())),
        TextEncoding.UTF8,
    )

    private fun lenientEncode(charset: Charset, text: String, replacement: ByteArray): ByteArray {
        val encoder = charset.newEncoder()
            .onMalformedInput(CodingErrorAction.REPLACE)
            .onUnmappableCharacter(CodingErrorAction.REPLACE)
            .replaceWith(replacement)
        val buffer = encoder.encode(CharBuffer.wrap(text))
        return ByteArray(buffer.remaining()).also { buffer.get(it) }
    }

    /** Decode with a throwing decoder — the lenient `String(...)` constructor
     *  would replace malformed bytes with U+FFFD and defeat the trial order. */
    private fun strict(charset: Charset, data: ByteArray): String? = runCatching {
        charset.newDecoder().decode(ByteBuffer.wrap(data)).toString()
    }.getOrNull()

    /** Encode with a throwing encoder — never a best-fit substitute or a `?`. */
    private fun strictEncode(charset: Charset, text: String): ByteArray? = runCatching {
        val buffer = charset.newEncoder().encode(CharBuffer.wrap(text))
        ByteArray(buffer.remaining()).also { buffer.get(it) }
    }.getOrNull()
}
