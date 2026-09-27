/*
 * ManifestDocumentTypesTest.kt
 * md (Android)
 *
 * Pins the file-type associations in AndroidManifest.xml to the canonical
 * extension sets every md port declares (DocumentTypes). The manifest is
 * parsed from the source tree with the JDK's own XML parser — no Android
 * class, no Robolectric — so a dropped or misspelt `pathSuffix`, a filter
 * that lost its typed/untyped twin, or a MIME type that vanished from the
 * Markdown filter fails here rather than as ".mkd no longer opens" on a
 * device.
 *
 * What is pinned:
 *   - the set of `android:pathSuffix` values across all ACTION_VIEW filters
 *     is exactly Markdown ∪ PlantUML ∪ { .gv, .textpack };
 *   - every suffix appears in exactly two filters — one with the wildcard
 *     mimeType (star-slash-star) for pickers that type the intent, one
 *     without for those that hand the URI over untyped (a filter with a mimeType only
 *     matches typed intents, one without only untyped ones), and each of
 *     those carries host="*", without which the path is never tested;
 *   - the MIME-typed Markdown filter still lists text/markdown,
 *     text/x-markdown and text/plain;
 *   - every document filter answers ACTION_EDIT as well as ACTION_VIEW, and
 *     neither action is ever declared without the other — "Edit with md" and
 *     "Open with md" hand over the same documents, and the one way to keep
 *     the two sets in step is for them to be one set;
 *   - DocumentTypes itself spells the same canonical sets, in order.
 */

package me.nettrash.md

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

class ManifestDocumentTypesTest {

    private companion object {
        const val ANDROID_NS = "http://schemas.android.com/apk/res/android"
        const val ACTION_VIEW = "android.intent.action.VIEW"
        const val ACTION_EDIT = "android.intent.action.EDIT"

        /** The canonical sets, spelt out here on purpose: the test is the pin,
         *  so it must not read them back from the code it checks. */
        val MARKDOWN = listOf(
            "md", "markdown", "mdown", "markdn", "mdtext", "mdtxt", "mkd", "mkdn", "mdwn", "mkdown",
        )
        val PLANTUML = listOf("puml", "plantuml", "iuml", "pu")
        val GRAPHVIZ = listOf("gv")
        val PLAIN_TEXT = listOf("txt", "text")
        const val TEXTPACK = "textpack"
        val MARKDOWN_MIME_TYPES = setOf("text/markdown", "text/x-markdown", "text/plain")
    }

    /** The manifest is not on the unit-test classpath, so it is read straight
     *  off disk: relative to the module directory Gradle runs tests in
     *  (`md/`), falling back through `user.dir` for runners with another
     *  working directory — the same resolution ExamplesTest uses. */
    private val manifest: File = run {
        val relative = "src/main/AndroidManifest.xml"
        val direct = File(relative)
        if (direct.exists()) direct
        else File(checkNotNull(System.getProperty("user.dir")) { "no user.dir" }).resolve(relative)
    }

    /** One `<intent-filter>`, reduced to what the association contract cares
     *  about. */
    private data class ViewFilter(
        val actions: List<String>,
        val mimeTypes: List<String>,
        val pathSuffixes: List<String>,
        val hosts: List<String>,
    )

    /** Every `<intent-filter>` in the manifest, in document order. */
    private fun allFilters(): List<ViewFilter> {
        assertTrue("missing ${manifest.absolutePath}", manifest.isFile)
        val document = DocumentBuilderFactory.newInstance()
            .apply { isNamespaceAware = true }
            .newDocumentBuilder()
            .parse(manifest)
        val filters = document.getElementsByTagName("intent-filter")
        return (0 until filters.length)
            .map { filters.item(it) as Element }
            .map { filter ->
                val data = filter.children("data")
                ViewFilter(
                    actions = filter.children("action").attrs("name"),
                    mimeTypes = data.attrs("mimeType"),
                    pathSuffixes = data.attrs("pathSuffix"),
                    hosts = data.attrs("host"),
                )
            }
    }

    /** The document filters: the ones that answer ACTION_VIEW. */
    private fun viewFilters(): List<ViewFilter> = allFilters().filter { ACTION_VIEW in it.actions }

    private fun Element.children(tag: String): List<Element> {
        val nodes = childNodes
        return (0 until nodes.length).map { nodes.item(it) }.filterIsInstance<Element>().filter { it.tagName == tag }
    }

    private fun List<Element>.attrs(name: String): List<String> =
        mapNotNull { it.getAttributeNS(ANDROID_NS, name).takeIf { value -> value.isNotEmpty() } }

    // MARK: The extension set

    @Test fun pathSuffixesAreExactlyTheCanonicalSets() {
        val expected = (MARKDOWN + PLANTUML + GRAPHVIZ + TEXTPACK).map { ".$it" }.toSet()
        val actual = viewFilters().flatMap { it.pathSuffixes }.toSet()
        assertEquals(expected, actual)
    }

    @Test fun dotIsNeverClaimed() {
        // `.dot` is a Word template type on the Apple and Windows siblings;
        // claiming it on Android alone would break parity (see the manifest).
        val suffixes = viewFilters().flatMap { it.pathSuffixes }
        assertTrue(suffixes.none { it.equals(".dot", ignoreCase = true) })
    }

    @Test fun plainTextIsMatchedByMimeNotByName() {
        // text/plain is universally typed by pickers, so .txt / .text need no
        // suffix filter — and must not grow one by accident.
        val suffixes = viewFilters().flatMap { it.pathSuffixes }.toSet()
        for (extension in PLAIN_TEXT) assertTrue(".$extension" !in suffixes)
    }

    // MARK: The two-filter shape

    @Test fun everySuffixAppearsInExactlyOneTypedAndOneUntypedFilter() {
        val filters = viewFilters()
        val suffixes = filters.flatMap { it.pathSuffixes }.toSet()
        assertTrue(suffixes.isNotEmpty())
        for (suffix in suffixes) {
            val carrying = filters.filter { suffix in it.pathSuffixes }
            assertEquals("$suffix must be in exactly two filters", 2, carrying.size)
            val typed = carrying.filter { it.mimeTypes == listOf("*/*") }
            val untyped = carrying.filter { it.mimeTypes.isEmpty() }
            assertEquals("$suffix needs one filter with mimeType=\"*/*\"", 1, typed.size)
            assertEquals("$suffix needs one filter with no mimeType", 1, untyped.size)
        }
    }

    @Test fun everySuffixFilterDeclaresWildcardHost() {
        // Without host="*" Android never tests the path at all.
        for (filter in viewFilters().filter { it.pathSuffixes.isNotEmpty() }) {
            assertEquals(listOf("*"), filter.hosts)
        }
    }

    @Test fun suffixFiltersNeverMixInAConcreteMimeType() {
        // A suffix filter that also names text/markdown would only ever match
        // an intent typed exactly so — the very case the suffix exists to
        // escape from.
        for (filter in viewFilters().filter { it.pathSuffixes.isNotEmpty() }) {
            assertTrue(filter.mimeTypes.all { it == "*/*" })
        }
    }

    @Test fun suffixesAreSpeltLowerCaseWithALeadingDot() {
        // pathSuffix is matched case-sensitively; the lower-case spelling is
        // the one every tool writes, and the dot keeps `.md` from matching
        // `something.cmd`.
        for (suffix in viewFilters().flatMap { it.pathSuffixes }) {
            assertTrue(suffix, suffix.startsWith("."))
            assertEquals(suffix, suffix.lowercase())
        }
    }

    // MARK: View and Edit are one set

    @Test fun everyDocumentFilterAnswersEditAsWellAsView() {
        // An app asking for an editor ("Edit with md") and one asking for a
        // viewer hand over the same document; what decides whether md may
        // write it back is the grant on the intent, not the action.
        val filters = viewFilters()
        assertTrue(filters.isNotEmpty())
        for (filter in filters) {
            assertTrue("$filter must also declare ACTION_EDIT", ACTION_EDIT in filter.actions)
        }
    }

    @Test fun editIsNeverDeclaredApartFromView() {
        // Declaring the two actions on one filter is what keeps the sets in
        // step: there is only ever one list of `data` elements to drift.
        assertEquals(
            allFilters().filter { ACTION_VIEW in it.actions },
            allFilters().filter { ACTION_EDIT in it.actions },
        )
    }

    @Test fun theSharedTextFilterClaimsNeitherViewNorEdit() {
        // ACTION_SEND is its own filter: shared text is not a document we
        // were handed, and must not be matched by name or MIME type.
        val send = allFilters().filter { "android.intent.action.SEND" in it.actions }
        assertEquals("exactly one ACTION_SEND filter", 1, send.size)
        assertEquals(listOf("android.intent.action.SEND"), send.single().actions)
        assertTrue(send.single().pathSuffixes.isEmpty())
    }

    // MARK: The MIME filter

    @Test fun markdownMimeFilterStillListsEveryMarkdownType() {
        val mimeFilters = viewFilters().filter { it.pathSuffixes.isEmpty() && it.mimeTypes.isNotEmpty() }
        assertEquals("exactly one MIME-typed Markdown filter", 1, mimeFilters.size)
        assertEquals(MARKDOWN_MIME_TYPES, mimeFilters.single().mimeTypes.toSet())
    }

    // MARK: DocumentTypes agrees

    @Test fun documentTypesSpellsTheSameCanonicalSetsInOrder() {
        assertEquals(MARKDOWN, DocumentTypes.MARKDOWN)
        assertEquals(PLANTUML, DocumentTypes.PLANTUML)
        assertEquals(GRAPHVIZ, DocumentTypes.GRAPHVIZ)
        assertEquals(PLAIN_TEXT, DocumentTypes.PLAIN_TEXT)
        assertEquals(TEXTPACK, DocumentTypes.TEXTPACK)
        assertEquals(
            (MARKDOWN + PLANTUML + GRAPHVIZ + TEXTPACK).map { ".$it" }.toSet(),
            DocumentTypes.PATH_SUFFIX_EXTENSIONS.map { ".$it" }.toSet(),
        )
    }

    @Test fun openPickerCoversEveryWayAPickerTypesTheseFiles() {
        val offered = DocumentTypes.OPENABLE_MIME_TYPES.toSet()
        for (mime in MARKDOWN_MIME_TYPES) assertTrue(mime, mime in offered)
        // What most pickers type every alias beyond .md / .markdown, PlantUML
        // and Graphviz as — and a .textpack in the pickers that type it as a zip.
        assertTrue("application/octet-stream" in offered)
        assertTrue("application/zip" in offered)
    }
}
