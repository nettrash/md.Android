/*
 * DocumentTypes.kt
 * md (Android)
 *
 * The one list of file extensions md opens, shared by every port (iOS /
 * iPadOS / macOS declare the same sets as UTIs, Windows in its package
 * manifest, VS Code in its language contribution) and by every place on
 * Android that reasons about a document's name:
 *
 *   - the ACTION_VIEW `pathSuffix` filters in AndroidManifest.xml, which a
 *     unit test (ManifestDocumentTypesTest) pins to exactly these sets — the
 *     manifest cannot read Kotlin, so the test is what keeps the two in step;
 *   - the in-app Open… picker's MIME list;
 *   - the Save As / export name suggestions, which strip the document's own
 *     extension before adding the new one (so `Notes.mkd` suggests
 *     `Notes.pdf`, not `Notes.mkd.pdf`).
 *
 * Only `.gv` is claimed for Graphviz: `.dot` is a Word template type on the
 * Apple and Windows siblings, and claiming it on one platform alone would
 * break parity. A `.textbundle` DIRECTORY is not on this list — over the
 * Storage Access Framework it is a content:// tree, which ACTION_VIEW never
 * hands out (see TextBundle.kt); only the zipped `.textpack` is.
 */

package me.nettrash.md

object DocumentTypes {

    /** Markdown, in the canonical cross-port order. */
    val MARKDOWN: List<String> = listOf(
        "md", "markdown", "mdown", "markdn", "mdtext", "mdtxt", "mkd", "mkdn", "mdwn", "mkdown",
    )

    /** PlantUML source. No registered MIME type — matched by name only. */
    val PLANTUML: List<String> = listOf("puml", "plantuml", "iuml", "pu")

    /** Graphviz DOT source — `.gv` only, see the file comment. */
    val GRAPHVIZ: List<String> = listOf("gv")

    /** Plain text. Opened by MIME (`text/plain`), so these never need a
     *  `pathSuffix` filter; listed so name suggestions strip them too. */
    val PLAIN_TEXT: List<String> = listOf("txt", "text")

    /** A zipped TextBundle — imported, never opened for writing. */
    const val TEXTPACK: String = "textpack"

    /** The extensions the manifest must claim by `pathSuffix`: everything
     *  a picker cannot be relied on to type by MIME. Plain text is absent
     *  because `text/plain` is universally typed and already matched. */
    val PATH_SUFFIX_EXTENSIONS: List<String> = MARKDOWN + PLANTUML + GRAPHVIZ + TEXTPACK

    /** Every extension a name suggestion strips (lower-case, no dot). */
    val ALL: Set<String> = (PATH_SUFFIX_EXTENSIONS + PLAIN_TEXT).toSet()

    /** What the Open… picker offers. `text/x-markdown` is the older Markdown
     *  registration some providers still emit; `application/zip` surfaces a
     *  `.textpack` in pickers that type it as a zip; `application/octet-stream`
     *  covers every extension no provider maps — which, for most pickers, is
     *  every Markdown alias beyond `.md` / `.markdown` as well as PlantUML and
     *  Graphviz (see DocumentViewModel.load, which imports a pack and reads a
     *  plain file otherwise). */
    val OPENABLE_MIME_TYPES: Array<String> = arrayOf(
        "text/markdown", "text/x-markdown", "text/plain",
        "application/octet-stream", "application/zip",
    )

    /** [name] without its document extension, when it carries one of [ALL]
     *  (matched in any letter case — `NOTES.MD` → `NOTES`); unchanged
     *  otherwise. Exactly one extension is stripped, so `Notes.mkd.md` →
     *  `Notes.mkd` and `archive.tar.gz` is left alone; a name that is only
     *  an extension (`.md`) strips to the empty string. */
    fun baseName(name: String): String {
        val dot = name.lastIndexOf('.')
        if (dot < 0) return name
        val extension = name.substring(dot + 1).lowercase()
        return if (extension in ALL) name.substring(0, dot) else name
    }

    /** The `.md` file name to suggest for a document shown as [displayName]:
     *  its base with `.md` appended, `Untitled.md` for a blank name. Save As
     *  always suggests `.md` — the CreateDocument contract is `text/markdown`,
     *  and the documents provider normalises a name whose extension does not
     *  map to that type — so an alias such as `.mkd` is written back in
     *  place by Save, and suggested as `.md` only when the writer picks a
     *  new file. */
    fun markdownFileName(displayName: String): String =
        baseName(displayName).ifBlank { "Untitled" } + ".md"
}
