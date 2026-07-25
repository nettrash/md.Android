/*
 * TextBundleTest.kt
 * md (Android)
 *
 * The pure pieces of TextBundle / TextPack import + export (Feature 2),
 * mirroring the iOS mdTests "TextBundle / TextPack" section: reading a pack's
 * text.md (STORED and — crucially, since real packs deflate — DEFLATE), the
 * info.json shape, the export image-ref rewrite, and the bundle entry set.
 *
 * Android has no separate hand-rolled zip reader: `java.util.zip` inflates
 * transparently, so the reader is exercised through `textFromPack` over both a
 * hand-built STORED archive and a genuine DEFLATE one (produced by the app's
 * own `TextBundle.pack`, which is what a real export writes). The picker
 * plumbing and the local-image resolver in ui/Exporter are device-only and not
 * here; the directory `.textbundle` case is out of scope on Android (a SAF
 * content:// tree — see TextBundle.kt's platform note).
 */

package me.nettrash.md

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.charset.Charset
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

class TextBundleTest {

    // MARK: Import — reading text.md out of a pack

    @Test fun textFromPackReadsStoredZip() {
        // A pack wraps a `.textbundle` folder, so text.md is nested under it and
        // must be matched by its last path component. STORED (uncompressed),
        // which some tools write.
        val zip = storedPack(
            listOf(
                "Doc.textbundle/info.json" to TextBundle.INFO_JSON.toByteArray(Charsets.UTF_8),
                "Doc.textbundle/text.md" to "# Packed\n".toByteArray(Charsets.UTF_8),
            ),
        )
        assertEquals("# Packed\n", TextBundle.textFromPack(zip))
    }

    @Test fun textFromPackInflatesDeflatedEntry() {
        // Real TextPacks deflate their entries; prove the inflate path against a
        // genuine DEFLATE archive — the very one the app's own export writes
        // (TextBundle.pack). A stored-only reader would miss every real pack.
        val payload = "# Deflated\n\n" + "Some longer body text to compress well.\n".repeat(20)
        val archive = TextBundle.pack(TextBundle.bundleEntries("Doc", payload, emptyList()))
        assertEquals(payload, TextBundle.textFromPack(archive))
    }

    @Test fun textFromPackRejectsNonZip() {
        assertNull(TextBundle.textFromPack("plain text, not a pack".toByteArray(Charsets.UTF_8)))
        assertNull(TextBundle.textFromPack(ByteArray(0)))
    }

    @Test fun textFromPackDecodesLegacyEncoding() {
        // A text.md in a legacy encoding must decode with the right one (via the
        // shared TextCodec), so a Windows-1251 bundle round-trips rather than
        // arriving as mojibake — the pack-path counterpart of the iOS
        // directory-import encoding test.
        val cyrillic = "Привет".toByteArray(Charset.forName("windows-1251"))
        val zip = storedPack(listOf("B.textbundle/text.md" to cyrillic))
        assertEquals("Привет", TextBundle.textFromPack(zip))
    }

    @Test fun textFromPackPrefersTextMd() {
        val zip = storedPack(
            listOf(
                "B.textbundle/text.markdown" to "markdown\n".toByteArray(Charsets.UTF_8),
                "B.textbundle/text.md" to "md\n".toByteArray(Charsets.UTF_8),
            ),
        )
        // text.md wins even when text.markdown is the earlier entry.
        assertEquals("md\n", TextBundle.textFromPack(zip))
    }

    @Test fun textFromPackNilWithoutATextFile() {
        val zip = storedPack(listOf("B.textbundle/info.json" to "{}".toByteArray(Charsets.UTF_8)))
        assertNull(TextBundle.textFromPack(zip))
    }

    @Test fun looksLikePackByNameOrZipMagic() {
        val zip = TextBundle.pack(TextBundle.bundleEntries("Doc", "hi\n", emptyList()))
        // The 4-byte local-file-header magic identifies an unnamed pack…
        assertTrue(TextBundle.looksLikePack("whatever", zip))
        // …and so does the extension, whatever the bytes.
        assertTrue(TextBundle.looksLikePack("Notes.textpack", "not a zip".toByteArray()))
        // A plain Markdown file — even one whose text starts with the two
        // letters "PK" — is NOT mistaken for a pack (only PK + 03 04 is).
        assertFalse(TextBundle.looksLikePack("notes.md", "# Notes\n".toByteArray()))
        assertFalse(TextBundle.looksLikePack("pk.md", "PKZIP is a program\n".toByteArray()))
    }

    // MARK: info.json

    @Test fun infoJsonHasTheTextBundleShape() {
        // Parsed by the readers we hand it to; here we pin the three fields the
        // spec requires, without an org.json parser (the JVM unit-test stub
        // would throw), since the constant is ours and canonical.
        val info = TextBundle.INFO_JSON
        assertTrue(info.contains("\"version\": 2"))
        assertTrue(info.contains("\"type\": \"net.daringfireball.markdown\""))
        assertTrue(info.contains("\"transient\": false"))
    }

    // MARK: Export — the image-ref rewrite

    @Test fun exportCopiesFoundImageAndLeavesTheRestUntouched() {
        // A findable local ref is copied to assets/ and rewritten; an unfindable
        // local ref and a remote URL are left exactly as written.
        val png = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47)
        val source = "![cat](photo.png) ![dog](missing.png) ![web](https://e/x.png)"
        val (text, assets) = TextBundle.exportRewriting(source) { path ->
            if (path == "photo.png") png else null
        }
        assertTrue(text.contains("![cat](assets/photo.png)"))
        assertTrue(text.contains("![dog](missing.png)"))
        assertTrue(text.contains("![web](https://e/x.png)"))
        assertEquals(listOf(TextBundle.Asset("photo.png", png)), assets)
    }

    @Test fun exportKeepsImageTitleWhenRewriting() {
        // Only the URL is rewritten; a following "title" is preserved.
        val (text, _) = TextBundle.exportRewriting("![alt](pic.png \"A cat\")") { byteArrayOf(1) }
        assertTrue(text.contains("![alt](assets/pic.png \"A cat\")"))
    }

    @Test fun exportDedupesAssetNamesButReusesOnePathOnce() {
        // Two distinct paths sharing a file name get distinct assets; the same
        // path used again reuses its asset (copied once, both refs rewritten).
        val source = "![a](a/logo.png) ![b](b/logo.png) ![c](a/logo.png)"
        val (text, assets) = TextBundle.exportRewriting(source) { byteArrayOf(7) }
        assertEquals(listOf("logo.png", "logo-2.png"), assets.map { it.name })
        assertTrue(text.contains("![a](assets/logo.png)"))
        assertTrue(text.contains("![b](assets/logo-2.png)"))
        assertTrue(text.contains("![c](assets/logo.png)"))
    }

    @Test fun exportDisambiguatesDotfilesAndExtensionlessNames() {
        // Hand-written character test: the extension split is past the FIRST
        // character, so a dotfile keeps its whole name as the stem
        // (`.keep` → `.keep-2`, not `-2.keep`), and a name with no dot at all
        // gets the counter appended (`README` → `README-2`).
        val (_, dotfiles) = TextBundle.exportRewriting("![a](x/.keep) ![b](y/.keep)") { byteArrayOf(1) }
        assertEquals(listOf(".keep", ".keep-2"), dotfiles.map { it.name })
        val (_, plain) = TextBundle.exportRewriting("![a](x/README) ![b](y/README)") { byteArrayOf(1) }
        assertEquals(listOf("README", "README-2"), plain.map { it.name })
    }

    @Test fun exportDedupIsCaseInsensitiveAndLocaleIndependent() {
        // `Logo.PNG` and `logo.png` collide (the fold is case-insensitive), and
        // the fold is locale-independent — so this holds on a Turkish-locale
        // device too, where a naive lowercase would map I/İ differently.
        val (_, assets) = TextBundle.exportRewriting("![a](x/Logo.PNG) ![b](y/logo.png)") { byteArrayOf(1) }
        assertEquals(listOf("Logo.PNG", "logo-2.png"), assets.map { it.name })
    }

    @Test fun localRelativeReferenceClassification() {
        assertTrue(TextBundle.isLocalRelativeReference("photo.png"))
        assertTrue(TextBundle.isLocalRelativeReference("images/photo.png"))
        assertFalse(TextBundle.isLocalRelativeReference("https://x/y.png"))
        assertFalse(TextBundle.isLocalRelativeReference("http://x/y.png"))
        assertFalse(TextBundle.isLocalRelativeReference("data:image/png;base64,AAAA"))
        assertFalse(TextBundle.isLocalRelativeReference("/abs/photo.png"))
        assertFalse(TextBundle.isLocalRelativeReference("#anchor"))
        assertFalse(TextBundle.isLocalRelativeReference(""))
    }

    @Test fun referenceClassificationIsCodeUnitExactAroundMarks() {
        // Hand-written character test. Kotlin's `contains`/`startsWith` are
        // UTF-16 code-unit exact, which is precisely the scalar-exact behaviour
        // the iOS sibling reaches for `ScalarText` to get (and NOT the
        // canonical-equivalence-folding a naive Swift `String` op would do).
        // A relative path carrying a combining mark is still local — the mark is
        // a unit of its own and never manufactures a "://".
        assertTrue(TextBundle.isLocalRelativeReference("café/photo.png"))
        // A real scheme is still caught when a mark sits elsewhere in the URL.
        assertFalse(TextBundle.isLocalRelativeReference("https://hóst/x.png"))
        // A ':' wearing a combining mark is NOT the "://" delimiter: the
        // sequence ":́//" has no bare ':' immediately followed by "//", so
        // the ref stays local rather than being read as a scheme.
        assertTrue(TextBundle.isLocalRelativeReference("weird:́//name.png"))
    }

    // MARK: Export — the bundle entry set and the pack container

    @Test fun bundleEntriesHoldTextInfoAndAssets() {
        val assets = listOf(TextBundle.Asset("p.png", byteArrayOf(1, 2)))
        val entries = TextBundle.bundleEntries("Doc", "# Hi\n", assets).toMap()
        assertEquals("# Hi\n", entries["Doc.textbundle/text.md"]?.toString(Charsets.UTF_8))
        assertTrue(entries.containsKey("Doc.textbundle/info.json"))
        assertArrayEqualsMsg(byteArrayOf(1, 2), entries["Doc.textbundle/assets/p.png"])
        // No stray empty-directory marker when there are real assets.
        assertFalse(entries.containsKey("Doc.textbundle/assets/"))
    }

    @Test fun bundleEntriesWithNoImagesStillCarryEmptyAssets() {
        val entries = TextBundle.bundleEntries("Doc", "plain\n", emptyList()).toMap()
        // assets/ is always present — here as an explicit empty-directory entry.
        assertTrue(entries.containsKey("Doc.textbundle/assets/"))
        assertTrue(entries.containsKey("Doc.textbundle/text.md"))
        assertTrue(entries.containsKey("Doc.textbundle/info.json"))
    }

    @Test fun packRoundTripsTextAndAssetsThroughDeflate() {
        // The whole export→import loop over a genuine DEFLATE container: build
        // the entries, zip them, and confirm both the text.md and a copied asset
        // survive (assets read back byte-exact; text via the importer).
        val png = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A)
        val (rewritten, assets) = TextBundle.exportRewriting("![c](photo.png)") { png }
        val archive = TextBundle.pack(TextBundle.bundleEntries("Doc", rewritten, assets))
        assertEquals(rewritten, TextBundle.textFromPack(archive))
        val back = readAll(archive)
        assertArrayEqualsMsg(png, back["Doc.textbundle/assets/photo.png"])
        assertEquals("![c](assets/photo.png)", rewritten)
    }

    // MARK: helpers

    /** A STORED (uncompressed) pack — some tools write these, and a STORED
     *  entry needs its CRC and size set by hand, exactly like the EPUB writer's
     *  mimetype. Proves the reader isn't DEFLATE-only. */
    private fun storedPack(entries: List<Pair<String, ByteArray>>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            for ((name, bytes) in entries) {
                zip.putNextEntry(
                    ZipEntry(name).apply {
                        method = ZipEntry.STORED
                        size = bytes.size.toLong()
                        crc = CRC32().apply { update(bytes) }.value
                    },
                )
                zip.write(bytes)
                zip.closeEntry()
            }
        }
        return out.toByteArray()
    }

    /** Every file entry of an archive as name → bytes (directories dropped). */
    private fun readAll(archive: ByteArray): Map<String, ByteArray> {
        val out = HashMap<String, ByteArray>()
        ZipInputStream(ByteArrayInputStream(archive)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                if (!entry.isDirectory) out[entry.name] = zip.readBytes()
                zip.closeEntry()
            }
        }
        return out
    }

    private fun assertArrayEqualsMsg(expected: ByteArray, actual: ByteArray?) {
        assertTrue("expected bytes present", actual != null)
        assertTrue("byte content differs", expected.contentEquals(actual))
    }
}
