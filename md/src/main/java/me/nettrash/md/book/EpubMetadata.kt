/*
 * EpubMetadata.kt
 * md (Android)
 *
 * An EPUB's metadata and cover from the document's front matter (#12).
 *
 * The package document used to carry a title, an identifier and a language
 * fixed at "en", and nothing else. From 1.6 every md EPUB writer — iPhone and
 * iPad, Mac, Windows, Android, VS Code — reads the same front-matter keys by
 * the same rules and writes the same lines in the same order (md.vscode's
 * src/export/epub-metadata.ts is the reference; EpubMetadataTest carries its
 * vectors):
 *
 *   author        → <dc:creator>
 *   language      → <dc:language>, when it is a language tag; otherwise "en"
 *   date          → <dc:date>, when it is a W3C date that exists; otherwise left out
 *   description   → <dc:description>
 *   series        → a belongs-to-collection of collection-type "series",
 *   series_index    with its group-position when the index is a number
 *   cover         → a cover-image item, when the image can be read
 *
 * Keys are matched as `title:` is ([documentTitle]): case-insensitively, the
 * first non-empty value winning. The cover is used when its bytes can be had —
 * a `data:` image, an `https:` image, or a file beside the document md can
 * already reach — and are a PNG, JPEG, GIF or WebP by their own magic numbers;
 * anything else is skipped silently and the book has no cover.
 *
 * A single document's EPUB only: a book keeps the defaults until book
 * metadata (title page, Contents) is designed (proposal 25).
 */

package me.nettrash.md.book

import me.nettrash.md.markdown.MetadataField
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.Base64

/** What a document's front matter adds to its package document. */
internal data class EpubMetadata(
    val creator: String? = null,
    /** Always set: the front matter's tag, or "en". */
    val language: String = "en",
    val date: String? = null,
    val description: String? = null,
    val series: String? = null,
    val seriesIndex: String? = null,
) {
    companion object {
        /** The package document as it always was. */
        val DEFAULT = EpubMetadata()
    }
}

/** A cover image, recognised by its bytes. */
internal class EpubCover(val extension: String, val mediaType: String, val bytes: ByteArray) {
    /** Its archive path inside OEBPS/. */
    val href: String get() = "images/cover.$extension"
}

/** Where a `cover:` value says the image is. */
internal sealed interface CoverReference {
    class Data(val bytes: ByteArray) : CoverReference
    data class Https(val url: String) : CoverReference
    data class Local(val path: String) : CoverReference
}

internal object EpubFrontMatter {

    /** A cover larger than this is skipped. */
    const val MAX_COVER_BYTES = 20 * 1024 * 1024

    /** Redirects a cover fetch follows before giving up. */
    const val MAX_REDIRECTS = 5

    /** The whole fetch's budget. */
    const val FETCH_TIMEOUT_MS = 15_000

    private val LANGUAGE_TAG = Regex("^[A-Za-z]{2,3}(?:-[A-Za-z0-9]{1,8})*$")
    private val W3C_DATE = Regex(
        "^([0-9]{4})(?:-([0-9]{2})(?:-([0-9]{2})(?:T([0-9]{2}):([0-9]{2})(?::([0-9]{2}))?(?:Z|[+-]([0-9]{2}):([0-9]{2})))?)?)?$",
    )
    private val SERIES_INDEX = Regex("^[0-9]+(?:\\.[0-9]+)?$")
    private val SCHEME = Regex("^[A-Za-z][A-Za-z0-9+.-]*:")
    private val BASE64 = Regex("^[A-Za-z0-9+/]*={0,2}$")

    /** The first non-empty value of [key], matched case-insensitively — [documentTitle]'s rule. */
    fun fieldValue(fields: List<MetadataField>, key: String): String? =
        fields.firstOrNull { it.key.equals(key, ignoreCase = true) && it.value.isNotEmpty() }?.value

    /** Whether [value] is a W3CDTF date that exists: the shape, then every
     *  field in range — month 01–12, a day the month has (February 29 in a
     *  Gregorian leap year only), hour 00–23, minute and second 00–59, and an
     *  offset of 00–23 hours and 00–59 minutes. */
    fun isW3CDate(value: String): Boolean {
        val m = W3C_DATE.matchEntire(value) ?: return false
        fun group(i: Int): Int? = m.groups[i]?.value?.toInt()
        fun inRange(i: Int, low: Int, high: Int): Boolean = group(i)?.let { it in low..high } ?: true
        if (!inRange(2, 1, 12)) return false
        val day = group(3)
        if (day != null) {
            val year = group(1)!!
            val leap = (year % 4 == 0 && year % 100 != 0) || year % 400 == 0
            val days = intArrayOf(31, if (leap) 29 else 28, 31, 30, 31, 30, 31, 31, 30, 31, 30, 31)[group(2)!! - 1]
            if (day !in 1..days) return false
        }
        return inRange(4, 0, 23) && inRange(5, 0, 59) && inRange(6, 0, 59) && inRange(7, 0, 23) && inRange(8, 0, 59)
    }

    /** The package document's metadata from a document's front matter. */
    fun metadata(fields: List<MetadataField>): EpubMetadata {
        val language = fieldValue(fields, "language")
        val date = fieldValue(fields, "date")
        val series = fieldValue(fields, "series")
        val seriesIndex = fieldValue(fields, "series_index")
        return EpubMetadata(
            creator = fieldValue(fields, "author"),
            language = if (language != null && LANGUAGE_TAG.matches(language)) language else "en",
            date = if (date != null && isW3CDate(date)) date else null,
            description = fieldValue(fields, "description"),
            series = series,
            seriesIndex = if (series != null && seriesIndex != null && SERIES_INDEX.matches(seriesIndex)) seriesIndex else null,
        )
    }

    /** ASCII-only prefix fold, as md.vscode and md.win: a Turkish İ is never an "i" here. */
    private fun startsWithAsciiIgnoringCase(text: String, prefix: String): Boolean {
        if (text.length < prefix.length) return false
        for (i in prefix.indices) {
            val c = text[i]
            if ((if (c in 'A'..'Z') c + 32 else c) != prefix[i]) return false
        }
        return true
    }

    /** Classify a `cover:` value, or null when it can never be read. */
    fun coverReference(value: String?): CoverReference? {
        if (value.isNullOrEmpty()) return null
        if (startsWithAsciiIgnoringCase(value, "data:")) {
            val comma = value.indexOf(',')
            if (comma < 0) return null
            val header = value.substring(5, comma)
            if (header.length < 7 || !startsWithAsciiIgnoringCase(header.substring(header.length - 7), ";base64")) return null
            val length = value.length - comma - 1
            // Past the cover cap once decoded: refused before the payload is
            // copied, matched or decoded.
            if (length <= 0 || length % 4 != 0 || length / 4 * 3 > MAX_COVER_BYTES + 2) return null
            val payload = value.substring(comma + 1)
            if (!BASE64.matches(payload)) return null
            return runCatching { CoverReference.Data(Base64.getDecoder().decode(payload)) }.getOrNull()
        }
        if (startsWithAsciiIgnoringCase(value, "https://")) return CoverReference.Https(value)
        if (value.contains("://") || SCHEME.containsMatchIn(value)) return null
        if (value.startsWith("/") || value.startsWith("\\") || value.startsWith("#")) return null
        return CoverReference.Local(value)
    }

    /** A cover from its bytes: PNG, JPEG, GIF or WebP by magic number, no larger than the cap. */
    fun recogniseCover(bytes: ByteArray?): EpubCover? {
        if (bytes == null || bytes.isEmpty() || bytes.size > MAX_COVER_BYTES) return null
        fun at(offset: Int, vararg expected: Int) =
            bytes.size >= offset + expected.size && expected.indices.all { bytes[offset + it] == expected[it].toByte() }
        return when {
            at(0, 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A) -> EpubCover("png", "image/png", bytes)
            at(0, 0xFF, 0xD8, 0xFF) -> EpubCover("jpg", "image/jpeg", bytes)
            at(0, 0x47, 0x49, 0x46, 0x38, 0x37, 0x61) || at(0, 0x47, 0x49, 0x46, 0x38, 0x39, 0x61) ->
                EpubCover("gif", "image/gif", bytes)
            at(0, 0x52, 0x49, 0x46, 0x46) && at(8, 0x57, 0x45, 0x42, 0x50) -> EpubCover("webp", "image/webp", bytes)
            else -> null
        }
    }

    /** The metadata lines between `<dc:title>` and `dcterms:modified` — the
     *  same text in all five writers. Without front matter, exactly the one
     *  `<dc:language>en</dc:language>` line md EPUBs always carried. */
    fun metadataLines(metadata: EpubMetadata, cover: EpubCover?): List<String> = buildList {
        metadata.creator?.let { add("<dc:creator>${escapeXml(it)}</dc:creator>") }
        add("<dc:language>${escapeXml(metadata.language)}</dc:language>")
        metadata.date?.let { add("<dc:date>${escapeXml(it)}</dc:date>") }
        metadata.description?.let { add("<dc:description>${escapeXml(it)}</dc:description>") }
        metadata.series?.let { series ->
            add("<meta property=\"belongs-to-collection\" id=\"series\">${escapeXml(series)}</meta>")
            add("<meta refines=\"#series\" property=\"collection-type\">series</meta>")
            metadata.seriesIndex?.let { add("<meta refines=\"#series\" property=\"group-position\">${escapeXml(it)}</meta>") }
        }
        // EPUB 3 finds the cover by the manifest property; this EPUB 2 meta is
        // for the readers — Kindle's converter among them — that still look for it.
        if (cover != null) add("<meta name=\"cover\" content=\"cover-image\"/>")
    }

    /** The cover's manifest item, the last item of every md EPUB that has one. */
    fun coverItem(cover: EpubCover): String =
        "<item id=\"cover-image\" href=\"${cover.href}\" media-type=\"${cover.mediaType}\" properties=\"cover-image\"/>"

    /**
     * GET [url] and every redirect after it over https only. Redirects are
     * followed by hand, at most [MAX_REDIRECTS], so a `Location` that leaves
     * https is refused before any request is made to it; a relative Location
     * resolves against the URL that gave it. [FETCH_TIMEOUT_MS] in all — a
     * watchdog disconnects the connection at the deadline, so no blocking
     * connect, header wait or read can outlast it — and a body past
     * [MAX_COVER_BYTES] is not read to the end. Null on any failure.
     * [open] is the connection factory, a seam for the tests.
     */
    fun fetchHttps(
        url: String,
        open: (URL) -> HttpURLConnection = { it.openConnection() as HttpURLConnection },
        now: () -> Long = System::currentTimeMillis,
        timeoutMs: Long = FETCH_TIMEOUT_MS.toLong(),
    ): ByteArray? {
        val watchdog = Executors.newSingleThreadScheduledExecutor { task ->
            Thread(task, "md-cover-deadline").apply { isDaemon = true }
        }
        try {
            return fetchHttps(url, open, now, timeoutMs, watchdog)
        } finally {
            watchdog.shutdownNow()
        }
    }

    private fun fetchHttps(
        url: String,
        open: (URL) -> HttpURLConnection,
        now: () -> Long,
        timeoutMs: Long,
        watchdog: ScheduledExecutorService,
    ): ByteArray? = runCatching {
        val deadline = now() + timeoutMs
        var current = URL(url)
        for (hop in 0..MAX_REDIRECTS) {
            if (!current.protocol.equals("https", ignoreCase = true)) return null
            val left = (deadline - now()).toInt()
            if (left <= 0) return null
            val connection = open(current)
            // The timeouts below bound each blocking phase on its own; the
            // alarm bounds them together, by cutting the connection.
            val alarm = watchdog.schedule({ connection.disconnect() }, left.toLong(), TimeUnit.MILLISECONDS)
            try {
                connection.instanceFollowRedirects = false
                connection.connectTimeout = left
                connection.readTimeout = left
                val status = connection.responseCode
                if (status in 300..399) {
                    val location = connection.getHeaderField("Location") ?: return null
                    current = URL(current, location)
                    continue
                }
                if (status !in 200..299) return null
                if (connection.contentLengthLong > MAX_COVER_BYTES) return null
                val out = ByteArrayOutputStream()
                connection.inputStream.use { input ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        if (now() > deadline) return null
                        val n = input.read(buffer)
                        if (n < 0) break
                        out.write(buffer, 0, n)
                        if (out.size() > MAX_COVER_BYTES) return null
                    }
                }
                return out.toByteArray()
            } finally {
                alarm.cancel(false)
                connection.disconnect()
            }
        }
        null
    }.getOrNull()
}
