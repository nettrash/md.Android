/*
 * IncomingGrant.kt
 * md (Android)
 *
 * What a document handed to md by another app may be done with — the whole
 * of the decision, and every line of it pure, so `./gradlew test` can reach
 * it (the unit-test classpath is JUnit alone; see ViewMode.kt for the same
 * split).
 *
 * "Open with md" arrives as ACTION_VIEW or ACTION_EDIT over a content:// URI
 * the sender has granted us. The grant is the only thing that says whether
 * the file may be written back: a Files / Drive / mail-client hand-off that
 * carries FLAG_GRANT_WRITE_URI_PERMISSION is an editable document and must
 * autosave like any file opened through the in-app picker, while one granted
 * read-only opens for reading and offers Save As. md used to assume the
 * second for every incoming document, which is why a file opened from Files
 * silently never saved.
 *
 * The flag bits are spelt out here rather than imported so this file stays
 * free of `android.*` — ManifestDocumentTypesTest's neighbour,
 * IncomingGrantTest, pins each of them against the `Intent` constant it
 * mirrors, so a wrong bit fails on the JVM rather than as "my edits vanish"
 * on a device.
 */

package me.nettrash.md

internal object IncomingGrant {

    /** `Intent.FLAG_GRANT_READ_URI_PERMISSION`. */
    const val FLAG_READ = 0x00000001

    /** `Intent.FLAG_GRANT_WRITE_URI_PERMISSION`. */
    const val FLAG_WRITE = 0x00000002

    /** `Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION` — the sender offering a
     *  grant that outlives this launch, which only some providers do. */
    const val FLAG_PERSISTABLE = 0x00000040

    /**
     * Whether a document handed in with these [intentFlags] may be written.
     *
     * Two sources, because neither alone is enough. The flags are what the
     * sender attached to *this* intent, and are the usual answer. But a grant
     * we already hold — one taken persistably on an earlier launch, or one
     * this process was given for the same URI through another route — is not
     * re-stated in the flags of every later intent, so [holdsWriteGrant] (the
     * caller's `checkCallingOrSelfUriPermission` result) is consulted as well.
     * Either one is a write grant; a file: URI from an older file manager
     * carries neither and keeps opening read-only, exactly as before.
     */
    fun isWritable(intentFlags: Int, holdsWriteGrant: Boolean): Boolean =
        (intentFlags and FLAG_WRITE) != 0 || holdsWriteGrant

    /**
     * The access modes worth asking `takePersistableUriPermission` for, or 0
     * when the sender offered nothing persistable.
     *
     * Only what the intent actually granted is asked for: requesting write on
     * a read-only grant throws, and requesting anything at all when
     * FLAG_GRANT_PERSISTABLE_URI_PERMISSION is absent always throws. The
     * caller still wraps the call — a provider may refuse for its own reasons
     * and a refusal has to be harmless — but the common refusals are simply
     * never asked for.
     */
    fun persistableModes(intentFlags: Int): Int =
        if ((intentFlags and FLAG_PERSISTABLE) == 0) 0
        else intentFlags and (FLAG_READ or FLAG_WRITE)
}
