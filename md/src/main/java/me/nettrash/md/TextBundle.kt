/*
 * TextBundle.kt
 * md (Android)
 *
 * TextBundle (`.textbundle`, a directory package) and TextPack (`.textpack`, a
 * zipped TextBundle) — the Markdown-with-assets container Ulysses, iA Writer
 * and Bear write. A bundle is `text.md` (or `text.markdown`), an `info.json`
 * declaring the type, and an `assets/` folder of images.
 *
 * Scope, stated honestly so it matches the iOS / macOS siblings: md's document
 * model is a single `String` and the preview has never shown a local image, so
 * the round-trip here is *text*, not pictures.
 *
 *   • Import loads a pack's `text.md` as the editable document (see
 *     `DocumentViewModel.load`). The `assets/` images are read past but not
 *     retained — the document is only the text — so a bundle carrying images
 *     opens with its `![](assets/…)` refs showing as broken images in the
 *     preview. That is the same honest failure a missing file already is, and
 *     it still lets the author edit the prose. Wiring imported asset bytes into
 *     the preview's asset scheme handler is the one thing md wholly lacks, and
 *     is deliberately *not* built here (it would have to thread asset storage
 *     through the document model and all three platforms' previews).
 *
 *   • Export writes the current document as a `.textpack`, copying any
 *     *findable* local images the Markdown references by relative path into
 *     `assets/` and rewriting those refs. A ref that can't be found is left
 *     exactly as the author wrote it — a broken ref stays a visible broken ref,
 *     nothing vanishes silently.
 *
 * PLATFORM NOTE — the Android container is a `.textpack`, not a `.textbundle`.
 * A `.textbundle` is a *directory* package; over the Storage Access Framework a
 * directory tree is awkward to read (a `content://…/tree/…` walk) and to write
 * (create-subfolder-then-files), while a `.textpack` is a single zip that the
 * ordinary create-document / open-document pickers and `java.util.zip` handle
 * cleanly. So Android reads and writes the zipped variant; the *pure* pieces
 * below — `info.json`, the ref rewrite, the bundle entry set — are identical to
 * the iOS bundle, only the outermost container differs (a zip instead of a
 * directory). A bare `.textbundle` directory dropped on the app is the reported
 * gap (see the README / task notes). Unlike Apple, the zip reader is not
 * hand-rolled: `java.util.zip` inflates DEFLATE transparently.
 *
 * The pure pieces (the ref rewrite, `info.json`, the pack read/write) carry no
 * Android types, so they are unit-tested directly on the JVM; the picker
 * plumbing and the local-image resolver live in `ui/Exporter`.
 */

package me.nettrash.md

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

object TextBundle {

    /** The `info.json` every bundle we write carries. A fixed, canonical
     *  document (stable key order) so it is trivially testable: version 2 of the
     *  spec, the Daring Fireball Markdown type md owns, and non-transient (this
     *  is a file the user is keeping, not a scratch hand-off). */
    val INFO_JSON: String = """
        {
          "version": 2,
          "type": "net.daringfireball.markdown",
          "transient": false
        }
    """.trimIndent()

    /** One image copied into a written bundle's `assets/`. */
    class Asset(val name: String, val bytes: ByteArray) {
        // Data-class-style value equality, but hand-written because a `data
        // class` over a `ByteArray` compares the array by *reference* — the
        // export tests assert on the copied bytes, so content equality is what
        // is wanted.
        override fun equals(other: Any?): Boolean =
            other is Asset && name == other.name && bytes.contentEquals(other.bytes)

        override fun hashCode(): Int = 31 * name.hashCode() + bytes.contentHashCode()

        override fun toString(): String = "Asset($name, ${bytes.size} bytes)"
    }

    // MARK: Import

    /** Whether an opened file should be read as a TextPack — either it is named
     *  `.textpack`, or it carries the ZIP local-file-header magic `PK\x03\x04`.
     *
     *  The magic is the full four-byte signature, not just "PK": a Markdown or
     *  text file that merely *begins* with the two letters "PK" is perfectly
     *  ordinary, and routing it here would keep it from opening. The two control
     *  bytes that follow are what no hand-written prose starts with, so this
     *  never shadows a real document — while still catching a pack that a file
     *  manager handed over without the `.textpack` name. A file that trips this
     *  but is not actually a readable pack falls back to *not opening* rather
     *  than being decoded as mojibake (see `DocumentViewModel.load`). */
    fun looksLikePack(name: String, bytes: ByteArray): Boolean =
        name.endsWith(".textpack", ignoreCase = true) ||
            (bytes.size >= 4 && bytes[0] == 0x50.toByte() && bytes[1] == 0x4B.toByte() &&
                bytes[2] == 0x03.toByte() && bytes[3] == 0x04.toByte())

    /** The `text.md` of a `.textpack` (a zipped bundle), decoded with the same
     *  detection a bare file gets (so a legacy-encoded bundle round-trips). The
     *  pack wraps a `.textbundle` folder, so the entry is nested under it —
     *  matched by its last path component. Prefers `text.md`, then
     *  `text.markdown`, then any `text.*` — the spec names the file `text` with
     *  an extension matching the declared type. Null when the bytes aren't a
     *  readable zip or carry no text file. */
    fun textFromPack(archive: ByteArray): String? {
        val entries = readZip(archive) ?: return null
        val entry = entries.firstOrNull { lastComponent(it.first) == "text.md" }
            ?: entries.firstOrNull { lastComponent(it.first) == "text.markdown" }
            ?: entries.firstOrNull { lastComponent(it.first).startsWith("text.") }
        val data = entry?.second ?: return null
        return TextCodec.decode(data)
    }

    /** Every file entry of a zip (directory entries dropped), or null if the
     *  bytes are not a zip this can parse. `ZipInputStream` inflates STORED and
     *  DEFLATE transparently, so — unlike the Apple sibling's hand-rolled
     *  reader — there is nothing to teach it about compression; a real,
     *  deflated pack reads with no extra code. The "PK" guard keeps a plain text
     *  file (which throws deep inside the stream) from a needless read. */
    private fun readZip(archive: ByteArray): List<Pair<String, ByteArray>>? {
        if (archive.size < 2 || archive[0] != 0x50.toByte() || archive[1] != 0x4B.toByte()) return null
        return runCatching {
            val out = ArrayList<Pair<String, ByteArray>>()
            ZipInputStream(ByteArrayInputStream(archive)).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    if (!entry.isDirectory) out.add(entry.name to zip.readBytes())
                    zip.closeEntry()
                }
            }
            out
        }.getOrNull()
    }

    // MARK: Export

    /** Whether a Markdown image destination names a *local file by relative
     *  path* — the only kind we can copy into `assets/`. Remote URLs (anything
     *  with a scheme), inline `data:` images, absolute paths and bare fragments
     *  are left untouched.
     *
     *  Kotlin's `String` is UTF-16 units, so `contains` / `startsWith` are
     *  code-unit exact — which is exactly the scalar-exact behaviour the iOS
     *  sibling has to reach for `ScalarText` to get (a `Char` walk and a
     *  code-point walk take the same decisions for these ASCII delimiters, and
     *  a combining mark after a `:` or `/` is a unit of its own that the search
     *  steps straight over). `lowercase()` with no locale is the invariant
     *  fold, matching the sibling's `lowercased()`; a locale-sensitive fold must
     *  not be used, or a Turkish-locale device would classify "DATA:" wrong. */
    fun isLocalRelativeReference(url: String): Boolean {
        if (url.isEmpty()) return false
        if (url.contains("://")) return false          // http:, https:, file:, custom
        val lower = url.lowercase()                     // schemes are ASCII; the fold is exact
        if (lower.startsWith("data:")) return false     // inline image bytes
        if (lower.startsWith("mailto:")) return false
        if (url.startsWith("/")) return false           // absolute path — not "next to" the source
        if (url.startsWith("#")) return false           // in-document fragment
        return true
    }

    /** Rewrite the document for export into a bundle: every local image ref the
     *  [resolveAsset] function can satisfy is copied into `assets/` and its ref
     *  rewritten to `assets/<name>`; refs it can't satisfy (a file that isn't
     *  there, a remote URL, an absolute path) are left exactly as written.
     *  Returns the rewritten text and the assets to write.
     *
     *  The image syntax recognized is exactly what `MarkdownHtml.inline` renders
     *  as an `<img>` — `![alt](dest)` and `![alt](dest "title")` / `'title'`,
     *  with `dest` a run of non-space non-`)` characters — matched with the same
     *  code-unit regex the renderer uses (`NSRegularExpression` is UTF-16 based,
     *  so this Kotlin `Regex` matches it unit-for-unit; this is the deliberate
     *  parity choice, NOT a hand-rolled grapheme scan). The one accepted
     *  imprecision matches the sibling's: an image-looking string inside a code
     *  span is also matched — but only rewritten when a real file of that name
     *  sits beside the document, in which case the ref still resolves (now from
     *  `assets/`), so nothing the author meant is lost. */
    fun exportRewriting(source: String, resolveAsset: (String) -> ByteArray?): Pair<String, List<Asset>> {
        val regex = runCatching {
            Regex("""!\[[^\]]*\]\(([^)\s]+)(?:\s+(?:"[^"]*"|'[^']*'))?\)""")
        }.getOrNull() ?: return source to emptyList()

        val assets = ArrayList<Asset>()
        val assetForPath = HashMap<String, String>()   // source ref -> assigned asset file name
        val usedNames = HashSet<String>()               // case-insensitive, for collision-free names
        // (start, endExclusive, replacement) for each URL-capture edit.
        val edits = ArrayList<Triple<Int, Int, String>>()

        for (match in regex.findAll(source)) {
            val group = match.groups[1] ?: continue
            val url = group.value
            if (!isLocalRelativeReference(url)) continue

            // The same ref written twice becomes one asset, both refs rewritten.
            val existing = assetForPath[url]
            if (existing != null) {
                edits.add(Triple(group.range.first, group.range.last + 1, "assets/$existing"))
                continue
            }
            // Unfindable → leave the author's ref exactly as it is.
            val data = resolveAsset(url) ?: continue
            val base = lastComponent(url)
            if (base.isEmpty()) continue

            // Two distinct source paths can share a file name; disambiguate so
            // one never overwrites the other in `assets/`. The fold is
            // locale-independent, matching the iOS sibling.
            var name = base
            var counter = 2
            while (usedNames.contains(name.lowercase())) {
                name = disambiguated(base, counter)
                counter++
            }
            usedNames.add(name.lowercase())
            assetForPath[url] = name
            assets.add(Asset(name, data))
            edits.add(Triple(group.range.first, group.range.last + 1, "assets/$name"))
        }

        if (edits.isEmpty()) return source to assets
        // Apply back-to-front so each still-untouched range stays valid.
        val builder = StringBuilder(source)
        for (edit in edits.asReversed()) {
            builder.replace(edit.first, edit.second, edit.third)
        }
        return builder.toString() to assets
    }

    /** The `(path → bytes)` entries of a `.textbundle`, nested under
     *  `<folderName>.textbundle/` ready to be zipped into a `.textpack`:
     *  `info.json`, `text.md`, and `assets/…` — or an explicit empty `assets/`
     *  directory entry when there are none, so the folder is always present
     *  (the sibling's `bundleWrapper` always carries an `assets` directory too).
     *  Pure, so the entry set is testable without a zip. */
    fun bundleEntries(folderName: String, text: String, assets: List<Asset>): List<Pair<String, ByteArray>> {
        val root = "$folderName.textbundle/"
        val entries = ArrayList<Pair<String, ByteArray>>()
        entries.add("${root}info.json" to INFO_JSON.toByteArray(Charsets.UTF_8))
        entries.add("${root}text.md" to text.toByteArray(Charsets.UTF_8))
        if (assets.isEmpty()) {
            entries.add("${root}assets/" to ByteArray(0))   // empty directory marker
        } else {
            for (asset in assets) entries.add("${root}assets/${asset.name}" to asset.bytes)
        }
        return entries
    }

    /** Zip [entries] into a `.textpack`. DEFLATED (the default, and what a real
     *  pack uses), so a round-trip back through `textFromPack` exercises the
     *  inflate path rather than only STORED. */
    fun pack(entries: List<Pair<String, ByteArray>>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            for ((name, bytes) in entries) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(bytes)
                zip.closeEntry()
            }
        }
        return out.toByteArray()
    }

    // MARK: Helpers

    /** The last `/`-separated component of a path (the file name). Only ever
     *  called on real file paths / refs (never a trailing-slash directory —
     *  those are filtered before this), so `substringAfterLast` agrees with the
     *  sibling's omit-empty split. */
    private fun lastComponent(path: String): String = path.substringAfterLast('/')

    /** `photo.png` → `photo-2.png`; a name with no extension → `README-2`. The
     *  dot must be past the first character so a dotfile (`.gitignore`) keeps
     *  its whole name as the stem. */
    private fun disambiguated(name: String, counter: Int): String {
        val dot = name.lastIndexOf('.')
        if (dot > 0) {
            val stem = name.substring(0, dot)
            val ext = name.substring(dot)   // includes the "."
            return "$stem-$counter$ext"
        }
        return "$name-$counter"
    }
}
