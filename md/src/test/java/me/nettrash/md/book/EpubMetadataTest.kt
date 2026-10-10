/*
 * EpubMetadataTest.kt
 * md (Android)
 *
 * EPUB metadata and cover from front matter (#12). These are the family's
 * vectors — md.vscode's test/epub-metadata.test.ts, which md for iPhone and
 * iPad, Mac and Windows carry too — so the five package documents agree on
 * which keys count, which values are accepted and the exact lines they
 * become. Plus the Android pieces: the OPF with no front matter is the 1.5
 * one byte for byte, the cover is the last manifest item and the last zip
 * entry, and the https fetch follows redirects by hand over https only.
 */

package me.nettrash.md.book

import me.nettrash.md.markdown.MarkdownParser
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.Base64
import java.util.zip.ZipInputStream

class EpubMetadataTest {

    private fun bytes(vararg b: Int) = ByteArray(b.size) { b[it].toByte() }
    private val png = bytes(0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0, 0, 0, 13)
    private val jpeg = bytes(0xFF, 0xD8, 0xFF, 0xE0, 0, 16)
    private val gif = "GIF89a\u0001\u0000".toByteArray(Charsets.ISO_8859_1)
    private val webp = "RIFF\u0024\u0000\u0000\u0000WEBPVP8 ".toByteArray(Charsets.ISO_8859_1)

    private fun fields(matter: String) = MarkdownParser.frontMatter("---\n$matter\n---\n\n# Body\n")
    private fun metadata(matter: String) = EpubFrontMatter.metadata(fields(matter))
    private fun lines(matter: String, cover: EpubCover? = null) = EpubFrontMatter.metadataLines(metadata(matter), cover)

    // ---- metadata lines ------------------------------------------------------

    @Test fun noFrontMatterIsTheOneLanguageLine() {
        assertEquals(listOf("<dc:language>en</dc:language>"), EpubFrontMatter.metadataLines(EpubFrontMatter.metadata(emptyList()), null))
        assertEquals(listOf("<dc:language>en</dc:language>"), EpubFrontMatter.metadataLines(EpubMetadata.DEFAULT, null))
        assertEquals(listOf("<dc:language>en</dc:language>"), lines("title: Only a title"))
    }

    @Test fun everyKeyInTheFamilyOrder() {
        assertEquals(
            listOf(
                "<dc:creator>Ada Lovelace</dc:creator>",
                "<dc:language>ru</dc:language>",
                "<dc:date>2026-10-10</dc:date>",
                "<dc:description>Notes on the &quot;Engine&quot; &amp; more</dc:description>",
                "<meta property=\"belongs-to-collection\" id=\"series\">Analytical</meta>",
                "<meta refines=\"#series\" property=\"collection-type\">series</meta>",
                "<meta refines=\"#series\" property=\"group-position\">2</meta>",
                "<meta name=\"cover\" content=\"cover-image\"/>",
            ),
            lines(
                listOf(
                    "title: The Book",
                    "author: Ada Lovelace",
                    "language: ru",
                    "date: 2026-10-10",
                    "description: Notes on the \"Engine\" & more",
                    "series: Analytical",
                    "series_index: 2",
                ).joinToString("\n"),
                EpubFrontMatter.recogniseCover(png),
            ),
        )
    }

    @Test fun keysMatchCaseInsensitivelyFirstNonEmptyWins() {
        assertEquals(
            listOf("<dc:creator>Grace Hopper</dc:creator>", "<dc:language>en</dc:language>"),
            lines("Author:\nAUTHOR: \"Grace Hopper\"\nauthor: Someone Else"),
        )
    }

    @Test fun aLanguageTagOrEn() {
        for ((value, expected) in listOf(
            "en" to "en", "pt-BR" to "pt-BR", "zh-Hant-TW" to "zh-Hant-TW", "ast" to "ast",
            "English" to "en", "e" to "en", "en_US" to "en", "en-" to "en", "ру" to "en", "en-toolongsubtag" to "en",
        )) {
            assertEquals(value, expected, metadata("language: $value").language)
        }
    }

    @Test fun aW3CDateOrNothing() {
        for (value in listOf("2026", "2026-10", "2026-10-10", "2026-10-10T09:30Z", "2026-10-10T09:30:15+03:00")) {
            assertEquals(value, value, metadata("date: $value").date)
        }
        for (value in listOf("10/10/2026", "October 2026", "2026-10-10 09:30", "2026-10-10T09:30", "26-10-10", "２０２６")) {
            assertNull(value, metadata("date: $value").date)
        }
    }

    @Test fun aDateOfTheRightShapeThatDoesNotExistIsLeftOut() {
        for (value in listOf(
            "2026-13", "2026-00", "2026-10-00", "2026-02-29", "1900-02-29", "2026-04-31",
            "2026-10-10T24:00Z", "2026-10-10T23:60Z", "2026-10-10T23:59:60Z",
            "2026-10-10T10:00+24:00", "2026-10-10T10:00+05:60",
        )) {
            assertNull(value, metadata("date: $value").date)
        }
        for (value in listOf("2024-02-29", "2000-02-29", "2026-12-31T23:59:59-11:30", "2026-01-31", "2026-10-10T00:00+00:00")) {
            assertEquals(value, value, metadata("date: $value").date)
        }
    }

    @Test fun aSeriesPositionOnlyWhenNumericAndOnlyWithASeries() {
        assertTrue(lines("series: S\nseries_index: 2.5").contains("<meta refines=\"#series\" property=\"group-position\">2.5</meta>"))
        assertEquals(3, lines("series: S\nseries_index: two").size)
        assertEquals(listOf("<dc:language>en</dc:language>"), lines("series_index: 3"))
    }

    @Test fun escapedAsTheRestApostropheLeftAlone() {
        assertEquals("<dc:creator>O'Brien &lt;ob@example.com&gt;</dc:creator>", lines("author: O'Brien <ob@example.com>")[0])
    }

    // ---- the cover reference -------------------------------------------------

    @Test fun aBase64DataImageAndNoOtherData() {
        val b64 = Base64.getEncoder().encodeToString(png)
        assertArrayEquals(png, (EpubFrontMatter.coverReference("data:image/png;base64,$b64") as CoverReference.Data).bytes)
        assertArrayEquals(png, (EpubFrontMatter.coverReference("DATA:image/png;BASE64,$b64") as CoverReference.Data).bytes)
        for (value in listOf("data:image/svg+xml,<svg/>", "data:image/png;base64,", "data:image/png;base64,abc", "data:image/png;base64,ab!d")) {
            assertNull(value, EpubFrontMatter.coverReference(value))
        }
    }

    @Test fun httpsOnly() {
        assertEquals(CoverReference.Https("https://example.com/c.png"), EpubFrontMatter.coverReference("https://example.com/c.png"))
        assertEquals(CoverReference.Https("HTTPS://example.com/c.png"), EpubFrontMatter.coverReference("HTTPS://example.com/c.png"))
        for (value in listOf("http://example.com/c.png", "ftp://example.com/c.png", "file:///tmp/c.png")) {
            assertNull(value, EpubFrontMatter.coverReference(value))
        }
    }

    @Test fun aRelativePathAndNothingAbsolute() {
        assertEquals(CoverReference.Local("cover.png"), EpubFrontMatter.coverReference("cover.png"))
        assertEquals(CoverReference.Local("images/cover.jpg"), EpubFrontMatter.coverReference("images/cover.jpg"))
        assertEquals(CoverReference.Local("../cover.png"), EpubFrontMatter.coverReference("../cover.png"))
        for (value in listOf("/tmp/cover.png", "\\\\server\\c.png", "C:\\c.png", "c:/c.png", "#cover", "mailto:x", "", null)) {
            assertNull("$value", EpubFrontMatter.coverReference(value))
        }
    }

    // ---- recognising a cover --------------------------------------------------

    @Test fun pngJpegGifWebp() {
        assertEquals("png" to "image/png", EpubFrontMatter.recogniseCover(png)!!.let { it.extension to it.mediaType })
        assertEquals("jpg" to "image/jpeg", EpubFrontMatter.recogniseCover(jpeg)!!.let { it.extension to it.mediaType })
        assertEquals("gif" to "image/gif", EpubFrontMatter.recogniseCover(gif)!!.let { it.extension to it.mediaType })
        assertEquals("webp" to "image/webp", EpubFrontMatter.recogniseCover(webp)!!.let { it.extension to it.mediaType })
    }

    @Test fun anythingElseIsNoCover() {
        assertNull(EpubFrontMatter.recogniseCover("<svg xmlns=\"http://www.w3.org/2000/svg\"/>".toByteArray()))
        assertNull(EpubFrontMatter.recogniseCover("RIFF\u0024\u0000\u0000\u0000WAVE".toByteArray(Charsets.ISO_8859_1)))
        assertNull(EpubFrontMatter.recogniseCover(bytes(0x89, 0x50, 0x4E, 0x47)))
        assertNull(EpubFrontMatter.recogniseCover(ByteArray(0)))
        assertNull(EpubFrontMatter.recogniseCover(null))
        val big = ByteArray(EpubFrontMatter.MAX_COVER_BYTES + 1).also { png.copyInto(it) }
        assertNull(EpubFrontMatter.recogniseCover(big))
    }

    // ---- the package document --------------------------------------------------

    private fun book(metadata: EpubMetadata = EpubMetadata.DEFAULT, cover: EpubCover? = null) = EpubBook(
        title = "T",
        titlePage = EpubUnit("content.xhtml", "T", "<p>x</p>"),
        rootArticles = emptyList(),
        chapters = emptyList(),
        images = listOf(EpubImage("images/rich-001.png", png)),
        metadata = metadata,
        cover = cover,
    )

    @Test fun withoutFrontMatterTheOpfIsThe15One() {
        assertEquals(
            """
            <?xml version="1.0" encoding="UTF-8"?>
            <package xmlns="http://www.idpf.org/2007/opf" version="3.0" unique-identifier="book-id">
            <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
            <dc:identifier id="book-id">urn:uuid:x</dc:identifier>
            <dc:title>T</dc:title>
            <dc:language>en</dc:language>
            <meta property="dcterms:modified">2026-10-10T00:00:00Z</meta>
            </metadata>
            <manifest>
            <item id="nav" href="nav.xhtml" media-type="application/xhtml+xml" properties="nav"/>
            <item id="style" href="style.css" media-type="text/css"/>
            <item id="content" href="content.xhtml" media-type="application/xhtml+xml"/>
            <item id="img-0" href="images/rich-001.png" media-type="image/png"/>
            </manifest>
            <spine>
            <itemref idref="content"/>
            </spine>
            </package>

            """.trimIndent(),
            contentOpf(book(), "urn:uuid:x", "2026-10-10T00:00:00Z"),
        )
    }

    @Test fun theCoverIsTheLastItemAndNotInTheSpine() {
        val opf = contentOpf(book(EpubMetadata(creator = "A", language = "de"), EpubFrontMatter.recogniseCover(jpeg)), "urn:uuid:x", "m")
        assertTrue(opf.contains(
            "<dc:title>T</dc:title>\n<dc:creator>A</dc:creator>\n<dc:language>de</dc:language>\n" +
                "<meta name=\"cover\" content=\"cover-image\"/>\n<meta property=\"dcterms:modified\">m</meta>",
        ))
        assertTrue(opf.contains(
            "<item id=\"img-0\" href=\"images/rich-001.png\" media-type=\"image/png\"/>\n" +
                "<item id=\"cover-image\" href=\"images/cover.jpg\" media-type=\"image/jpeg\" properties=\"cover-image\"/>\n</manifest>",
        ))
        assertFalse(opf.contains("<itemref idref=\"cover-image\"/>"))
    }

    @Test fun theCoverIsTheLastZipEntry() {
        val epub = buildEpub(book(cover = EpubFrontMatter.recogniseCover(png)), "urn:uuid:x", "m")
        val names = ArrayList<String>()
        var last = ByteArray(0)
        ZipInputStream(epub.inputStream()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                names += entry.name
                last = zip.readBytes()
            }
        }
        assertEquals(listOf("OEBPS/images/rich-001.png", "OEBPS/images/cover.png"), names.takeLast(2))
        assertArrayEquals(png, last)
    }

    // ---- the https fetch ---------------------------------------------------------

    /** A scripted connection: a status, an optional Location, a body. */
    private class Fake(url: URL, val status: Int, val location: String? = null, val body: ByteArray = ByteArray(0)) : HttpURLConnection(url) {
        var followed: Boolean? = null
        override fun connect() {}
        override fun disconnect() {}
        override fun usingProxy() = false
        override fun getResponseCode(): Int = status.also { followed = instanceFollowRedirects }
        override fun getHeaderField(name: String?): String? = if (name.equals("Location", true)) location else null
        override fun getContentLengthLong(): Long = body.size.toLong()
        override fun getInputStream(): InputStream = ByteArrayInputStream(body)
    }

    private fun fetch(script: Map<String, (URL) -> Fake>): Pair<ByteArray?, List<String>> {
        val asked = ArrayList<String>()
        val result = EpubFrontMatter.fetchHttps("https://a.example/cover.png", open = { url ->
            asked += url.toString()
            val fake = (script[url.toString()] ?: { u: URL -> Fake(u, 404) })(url)
            fake
        })
        return result to asked
    }

    @Test fun aDirectHttpsCover() {
        val (bytes, asked) = fetch(mapOf("https://a.example/cover.png" to { u -> Fake(u, 200, body = png) }))
        assertArrayEquals(png, bytes)
        assertEquals(listOf("https://a.example/cover.png"), asked)
    }

    @Test fun redirectsAreFollowedByHandRelativeOnesResolved() {
        val (bytes, asked) = fetch(
            mapOf(
                "https://a.example/cover.png" to { u -> Fake(u, 301, "https://b.example/x/cover") },
                "https://b.example/x/cover" to { u -> Fake(u, 302, "../real.png") },
                "https://b.example/real.png" to { u -> Fake(u, 200, body = png) },
            ),
        )
        assertArrayEquals(png, bytes)
        assertEquals(listOf("https://a.example/cover.png", "https://b.example/x/cover", "https://b.example/real.png"), asked)
    }

    @Test fun aRedirectToHttpIsRefusedBeforeItIsRequested() {
        val (bytes, asked) = fetch(mapOf("https://a.example/cover.png" to { u -> Fake(u, 302, "http://a.example/cover.png") }))
        assertNull(bytes)
        assertEquals(listOf("https://a.example/cover.png"), asked)
    }

    @Test fun atMostFiveRedirects() {
        val script = (0..6).associate { i ->
            (if (i == 0) "https://a.example/cover.png" else "https://a.example/$i") to { u: URL -> Fake(u, 302, "https://a.example/${i + 1}") }
        }
        val (bytes, asked) = fetch(script)
        assertNull(bytes)
        assertEquals(6, asked.size)
    }

    @Test fun anErrorOrAnOversizedBodyIsNoCover() {
        assertNull(fetch(mapOf("https://a.example/cover.png" to { u -> Fake(u, 500) })).first)
        assertNull(fetch(mapOf("https://a.example/cover.png" to { u -> Fake(u, 302) })).first)
        val big = ByteArray(EpubFrontMatter.MAX_COVER_BYTES + 1)
        assertNull(fetch(mapOf("https://a.example/cover.png" to { u -> Fake(u, 200, body = big) })).first)
    }

    @Test fun aSlowFetchGivesUp() {
        var clock = 0L
        val result = EpubFrontMatter.fetchHttps(
            "https://a.example/cover.png",
            open = { u -> clock += 16_000; Fake(u, 200, body = png) },
            now = { clock },
        )
        assertNull(result)
    }
}
