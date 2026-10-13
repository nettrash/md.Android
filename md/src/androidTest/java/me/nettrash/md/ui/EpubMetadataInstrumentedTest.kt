/*
 * EpubMetadataInstrumentedTest.kt
 * md (Android)
 *
 * #12 through the real exporter: a document whose front matter names its
 * author, language, date, description, series and a cover beside it exports
 * an EPUB that carries all of them — the cover read through LocalAssets, the
 * same resolver the TextPack export uses — and one whose cover cannot be read
 * is still written, without a cover. The full book is also left in the app's
 * files for an EPUBCheck run on the host (see the PR).
 */

package me.nettrash.md.ui

import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.zip.ZipInputStream

@RunWith(AndroidJUnit4::class)
class EpubMetadataInstrumentedTest {

    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val folder: File by lazy { File(context.cacheDir, "epub-meta").apply { mkdirs() } }

    @After fun clean() { folder.deleteRecursively() }

    private val png: ByteArray by lazy {
        // A real 1×1 PNG, so EPUBCheck can read it as an image.
        android.util.Base64.decode(
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==",
            android.util.Base64.DEFAULT,
        )
    }

    private fun export(source: String, docUri: Uri?): Map<String, ByteArray> {
        val latch = CountDownLatch(1)
        var bytes: ByteArray? = null
        instrumentation.runOnMainSync {
            EpubExporter.exportDocument(context, source, "Doc.md", docUri) { bytes = it; latch.countDown() }
        }
        assertTrue(latch.await(60, TimeUnit.SECONDS))
        val epub = checkNotNull(bytes) { "the export failed" }
        File(context.filesDir, "epub-meta-last.epub").writeBytes(epub)
        val entries = LinkedHashMap<String, ByteArray>()
        ZipInputStream(epub.inputStream()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                entries[entry.name] = zip.readBytes()
            }
        }
        return entries
    }

    private val matter = """
        ---
        title: The Engine
        author: Ada Lovelace
        language: en-GB
        date: 1843-09-01
        description: Notes & sketches
        series: Analytical
        series_index: 1
        cover: images/cover.png
        ---

        # Notes

        Text.
    """.trimIndent() + "\n"

    @Test fun theFrontMatterAndTheCoverBesideTheDocumentReachTheBook() {
        File(folder, "images").mkdirs()
        File(folder, "images/cover.png").writeBytes(png)
        val doc = File(folder, "Doc.md").apply { writeText(matter) }
        val entries = export(matter, Uri.fromFile(doc))
        val opf = String(entries.getValue("OEBPS/content.opf"))
        for (line in listOf(
            "<dc:title>The Engine</dc:title>\n<dc:creator>Ada Lovelace</dc:creator>\n<dc:language>en-GB</dc:language>\n<dc:date>1843-09-01</dc:date>",
            "<dc:description>Notes &amp; sketches</dc:description>",
            "<meta property=\"belongs-to-collection\" id=\"series\">Analytical</meta>",
            "<meta refines=\"#series\" property=\"group-position\">1</meta>",
            "<meta name=\"cover\" content=\"cover-image\"/>",
            "<item id=\"cover-image\" href=\"images/cover.png\" media-type=\"image/png\" properties=\"cover-image\"/>",
        )) {
            assertTrue(line, opf.contains(line))
        }
        assertArrayEquals(png, entries.getValue("OEBPS/images/cover.png"))
        assertTrue(entries.keys.last() == "OEBPS/images/cover.png")
    }

    @Test fun aCoverThatClimbsOutOfTheFolderIsLeftOut() {
        File(context.cacheDir, "outside.png").writeBytes(png)
        val doc = File(folder, "Doc.md").apply { writeText(matter) }
        val source = matter.replace("cover: images/cover.png", "cover: ../outside.png")
        val entries = export(source, Uri.fromFile(doc))
        File(context.cacheDir, "outside.png").delete()
        assertFalse(String(entries.getValue("OEBPS/content.opf")).contains("cover-image"))
        assertFalse(entries.containsKey("OEBPS/images/cover.png"))
    }

    @Test fun aDataCoverNeedsNoFileAtAll() {
        val b64 = android.util.Base64.encodeToString(png, android.util.Base64.NO_WRAP)
        val source = matter.replace("cover: images/cover.png", "cover: data:image/png;base64,$b64")
        val entries = export(source, null)
        assertArrayEquals(png, entries.getValue("OEBPS/images/cover.png"))
    }
}
