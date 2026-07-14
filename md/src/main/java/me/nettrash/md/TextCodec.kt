/*
 * TextCodec.kt
 * md (Android)
 *
 * Decoding for the plain-text files the app opens — extracted from the
 * view model so the trial order is unit-testable, and hardened the same
 * way as the iOS/macOS siblings: UTF-16 is only considered behind an
 * explicit byte-order mark. Without one, a strict UTF-16 decoder happily
 * pairs up the bytes of almost any even-length legacy single-byte file
 * (BOM-less Windows-1251 prose, say) into CJK mojibake — and the autosave
 * would then bake that corruption back into the file as UTF-8.
 *
 * Saving is unchanged and always UTF-8 (see DocumentViewModel.write) —
 * unlike the Apple siblings, Android has no round-trip-in-original-encoding
 * path, so decoding is the only side with an order to get right.
 */

package me.nettrash.md

import java.nio.ByteBuffer
import java.nio.charset.Charset

object TextCodec {

    private val windows1251: Charset? = runCatching { Charset.forName("windows-1251") }.getOrNull()

    /** Decode file bytes as text: BOM'd UTF-16 first, then strict UTF-8
     *  (the Markdown convention), strict Windows-1251 (the common legacy
     *  Cyrillic encoding the Apple siblings also read), and ISO-8859-1,
     *  which maps every byte and so never fails — the lossless last
     *  resort. */
    fun decode(data: ByteArray): String {
        if (data.size >= 2 &&
            ((data[0] == 0xFF.toByte() && data[1] == 0xFE.toByte()) ||
             (data[0] == 0xFE.toByte() && data[1] == 0xFF.toByte()))
        ) {
            strict(Charsets.UTF_16, data)?.let { return it }
        }
        strict(Charsets.UTF_8, data)?.let { return it }
        windows1251?.let { cp1251 -> strict(cp1251, data)?.let { return it } }
        strict(Charsets.ISO_8859_1, data)?.let { return it }
        return String(data, Charsets.UTF_8)
    }

    /** Decode with a throwing decoder — the default lenient `String(...)`
     *  constructor would replace malformed bytes with U+FFFD and defeat
     *  the trial order. */
    private fun strict(charset: Charset, data: ByteArray): String? = runCatching {
        charset.newDecoder().decode(ByteBuffer.wrap(data)).toString()
    }.getOrNull()
}
