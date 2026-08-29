/*
 * ViewModeTest.kt
 * md (Android)
 *
 * JVM unit tests for the width-adaptive mode rule and the per-file view-mode
 * memory (`ViewMode.kt`): which modes a window offers at a given width, how a
 * remembered Split is coerced on a narrow window without being discarded,
 * which mode a document *opens* in, which mode a table-of-contents tap
 * nudges to, which documents take part in the memory at all, and the little
 * codec and identity hash that remember it. Mirrors the intent of the iOS
 * `availableModes` / `effectiveMode` on `DocumentView`, and shares the open
 * rule, the storage format and the SHA-256 identity with the iOS and macOS
 * ports verbatim.
 */

package me.nettrash.md.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.MessageDigest
import java.util.Locale

class ViewModeTest {

    // isWideLayout — the 600dp compact/medium boundary

    @Test fun narrowPhonePortraitIsNotWide() {
        assertFalse(isWideLayout(360))
        assertFalse(isWideLayout(411))
        assertFalse(isWideLayout(WIDE_LAYOUT_MIN_WIDTH_DP - 1))
    }

    @Test fun tabletAndLandscapeAreWide() {
        assertTrue(isWideLayout(WIDE_LAYOUT_MIN_WIDTH_DP))
        assertTrue(isWideLayout(800))
        assertTrue(isWideLayout(1280))
    }

    // availableModes — Split only when there's room

    @Test fun narrowOffersEditAndPreviewOnly() {
        assertEquals(listOf(Mode.EDIT, Mode.PREVIEW), availableModes(isWide = false))
    }

    @Test fun wideOffersAllThree() {
        assertEquals(listOf(Mode.EDIT, Mode.SPLIT, Mode.PREVIEW), availableModes(isWide = true))
    }

    // effectiveMode — coercion without discarding the stored preference

    @Test fun storedSplitCollapsesToEditWhenNarrow() {
        assertEquals(Mode.EDIT, effectiveMode(Mode.SPLIT, isWide = false))
    }

    @Test fun storedSplitStaysSplitWhenWide() {
        assertEquals(Mode.SPLIT, effectiveMode(Mode.SPLIT, isWide = true))
    }

    @Test fun editAndPreviewSurviveBothWidths() {
        for (wide in listOf(true, false)) {
            assertEquals(Mode.EDIT, effectiveMode(Mode.EDIT, wide))
            assertEquals(Mode.PREVIEW, effectiveMode(Mode.PREVIEW, wide))
        }
    }

    @Test fun coercionRoundTripsWhenWindowWidensAgain() {
        // A remembered Split shown as Edit on a phone must return to Split
        // once the window is wide again — the raw preference is never lost.
        val stored = Mode.SPLIT
        assertEquals(Mode.EDIT, effectiveMode(stored, isWide = false))
        assertEquals(Mode.SPLIT, effectiveMode(stored, isWide = true))
    }

    // openViewMode - the mode a document OPENS in

    @Test fun openRuleTruthTable() {
        // A known file opens in exactly what was remembered, at either width
        // and whatever the content - the file is known, so nothing else is
        // consulted.
        for (wide in listOf(true, false)) {
            for (empty in listOf(true, false)) {
                for (remembered in Mode.entries) {
                    assertEquals(
                        remembered,
                        openViewMode(remembered, isEmptyDocument = empty, hasFileIdentity = true, isWide = wide),
                    )
                }
            }
        }
        // Nothing to read: Edit, at every width and with or without a file.
        for (wide in listOf(true, false)) {
            for (identity in listOf(true, false)) {
                assertEquals(
                    Mode.EDIT,
                    openViewMode(null, isEmptyDocument = true, hasFileIdentity = identity, isWide = wide),
                )
            }
        }
        // Unknown, with content: today's Split where Split is on offer...
        assertEquals(
            Mode.SPLIT,
            openViewMode(null, isEmptyDocument = false, hasFileIdentity = true, isWide = true),
        )
        assertEquals(
            Mode.SPLIT,
            openViewMode(null, isEmptyDocument = false, hasFileIdentity = false, isWide = true),
        )
        // ...and reader-first where it is not.
        assertEquals(
            Mode.PREVIEW,
            openViewMode(null, isEmptyDocument = false, hasFileIdentity = true, isWide = false),
        )
        assertEquals(
            Mode.PREVIEW,
            openViewMode(null, isEmptyDocument = false, hasFileIdentity = false, isWide = false),
        )
    }

    @Test fun rememberedSplitSurvivesANarrowOpen() {
        // The trap this whole feature turns on: the rule returns the RAW
        // remembered mode even on a phone, where Split cannot be shown. The
        // caller stores that and lets `effectiveMode` narrow it, so the
        // preference is still Split when the window - or the next device -
        // has room for it. Storing `effectiveMode`'s output here would erase
        // the reader's Split the first time they opened the file on a phone.
        val opened = openViewMode(Mode.SPLIT, isEmptyDocument = false, hasFileIdentity = true, isWide = false)
        assertEquals(Mode.SPLIT, opened)
        assertEquals(Mode.EDIT, effectiveMode(opened, isWide = false))
        assertEquals(Mode.SPLIT, effectiveMode(opened, isWide = true))
    }

    @Test fun contentsTapNeverRewritesARememberedSplit() {
        // Two different things reach this screen and must not be conflated: a
        // deliberate layout choice (the mode switch), which is persisted, and
        // a navigation nudge (a Contents tap needs the preview on screen
        // before it can scroll it to a heading), which is not.
        // `contentsTapMode` decides from what is DISPLAYED - whether the
        // preview is visible is the only question it asks - and the screen
        // adopts the answer into a transient `navigationMode` that overrides
        // the display and writes nothing.

        // The store as this reader left the file: Split.
        val id = ViewModeMemory.idFor("saf:com.android.externalstorage.documents:primary:Documents/a.md")
        var stored: String? = ViewModeMemory.touched(null, id, Mode.SPLIT)

        // Opened on a phone-width window: the screen holds the RAW Split and
        // shows it as Edit, Split being neither offered nor pickable here.
        val raw = openViewMode(
            remembered = ViewModeMemory.lookup(stored, id),
            isEmptyDocument = false,
            hasFileIdentity = true,
            isWide = false,
        )
        assertEquals(Mode.SPLIT, raw)
        assertFalse(Mode.SPLIT in availableModes(isWide = false))

        // Tap a heading. What is on screen is Edit, so the preview is not
        // visible and the tap does have to move something - the earlier fix
        // that asked the RAW mode instead saw Split here, switched nothing,
        // and scrolled a preview the reader could not see.
        var navigationMode: Mode? = null
        assertEquals(Mode.EDIT, effectiveMode(navigationMode ?: raw, isWide = false))
        navigationMode = contentsTapMode(effectiveMode(navigationMode ?: raw, isWide = false))
        assertEquals(Mode.PREVIEW, navigationMode)
        // The preview is now on screen...
        assertEquals(Mode.PREVIEW, effectiveMode(navigationMode ?: raw, isWide = false))
        // ...and nothing was written: the nudge never reaches the store, so
        // the remembered Split is still Split. That is what matters at this
        // width, where the reader could never pick Split back.
        assertEquals(Mode.SPLIT, ViewModeMemory.lookup(stored, id))
        // Tapping a second heading while already nudged switches nothing more.
        assertNull(contentsTapMode(effectiveMode(navigationMode ?: raw, isWide = false)))

        // Picking a mode from the switch IS deliberate: it clears the nudge
        // and persists, so from then on it is the reader's own preference
        // that is on screen and in the store.
        navigationMode = null
        stored = ViewModeMemory.touched(stored, id, Mode.PREVIEW)
        assertEquals(Mode.PREVIEW, ViewModeMemory.lookup(stored, id))
        assertEquals(Mode.PREVIEW, effectiveMode(navigationMode ?: Mode.PREVIEW, isWide = false))

        // And the rest of the little function: Split and Preview already show
        // the heading, so neither nudges; only a document displaying Edit does.
        assertEquals(Mode.PREVIEW, contentsTapMode(Mode.EDIT))
        assertNull(contentsTapMode(Mode.PREVIEW))
        assertNull(contentsTapMode(Mode.SPLIT))
    }

    // remembersViewMode - book articles are exempt from the memory entirely

    @Test fun bookArticlesNeitherReadNorWriteTheStore() {
        // The predicate itself, which is the whole of the exemption.
        assertFalse(remembersViewMode(isBookArticle = true))
        assertTrue(remembersViewMode(isBookArticle = false))

        // A chapter this reader once opened standalone and left in Preview.
        val chapter = ViewModeMemory.idFor("saf:com.android.externalstorage.documents:primary:Book/ch1.md")
        val store = MemoryProbe(ViewModeMemory.touched(null, chapter, Mode.PREVIEW))

        // Stepping to it through the book navigator, while writing in Edit:
        // the rule is not run at all, so the writer stays in Edit rather than
        // being dropped into Preview on the chapter they came to write...
        val pane = store.applyOpenRule(
            isBookArticle = true, id = chapter, isEmpty = false, isWide = false, current = Mode.EDIT,
        )
        assertEquals(Mode.EDIT, pane)
        // ...and the store was neither read nor written.
        assertEquals(0, store.reads)
        assertEquals(0, store.writes)

        // Nor does a mode picked while inside the book reach the store: the
        // pane belongs to the book session, not to that one chapter file.
        assertEquals(Mode.SPLIT, store.choose(isBookArticle = true, id = chapter, chosen = Mode.SPLIT))
        assertEquals(0, store.writes)
        assertEquals(Mode.PREVIEW, ViewModeMemory.lookup(store.stored, chapter))
    }

    @Test fun ordinaryDocumentsBothReadAndWriteTheStore() {
        val known = ViewModeMemory.idFor("saf:com.example:known.md")
        val store = MemoryProbe(ViewModeMemory.touched(null, known, Mode.SPLIT))

        // A file the app has seen opens in exactly what was remembered - the
        // lookup happens, and the open is written back, refreshing the MRU.
        assertEquals(
            Mode.SPLIT,
            store.applyOpenRule(
                isBookArticle = false, id = known, isEmpty = false, isWide = false, current = Mode.EDIT,
            ),
        )
        assertEquals(1, store.reads)
        assertEquals(1, store.writes)
        assertEquals(Mode.SPLIT, ViewModeMemory.lookup(store.stored, known))

        // A file it has not: the rule decides (narrow window, content -
        // reader first) and that decision is remembered from the first open.
        val fresh = ViewModeMemory.idFor("saf:com.example:fresh.md")
        assertEquals(
            Mode.PREVIEW,
            store.applyOpenRule(
                isBookArticle = false, id = fresh, isEmpty = false, isWide = false, current = Mode.EDIT,
            ),
        )
        assertEquals(Mode.PREVIEW, ViewModeMemory.lookup(store.stored, fresh))

        // And a deliberate pick on it is recorded.
        store.choose(isBookArticle = false, id = fresh, chosen = Mode.EDIT)
        assertEquals(Mode.EDIT, ViewModeMemory.lookup(store.stored, fresh))
        assertEquals(3, store.writes)
    }

    @Test fun saveAsEndsTheExemptionForTheNewFile() {
        // Save As on a book article: `DocumentViewModel.saveAs` clears the
        // flag, because what is open now is an ordinary standalone file the
        // writer just created outside the book.
        val chapter = ViewModeMemory.idFor("saf:com.example:Book/ch1.md")
        val saved = ViewModeMemory.idFor("saf:com.example:Documents/draft.md")
        val store = MemoryProbe()

        var isBookArticle = true
        assertFalse(remembersViewMode(isBookArticle))

        // The create-document callback migrates the RAW mode the writer is in
        // onto the new file's identity, ungated: the flag is already cleared
        // by the time it runs, and skipping the write would leave the new file
        // with no memory at all.
        val raw = Mode.EDIT
        isBookArticle = false                        // what `saveAs` does
        store.remember(saved, raw)

        assertTrue(remembersViewMode(isBookArticle))
        assertEquals(Mode.EDIT, ViewModeMemory.lookup(store.stored, saved))
        // The chapter it came from is untouched - it never entered the memory.
        assertNull(ViewModeMemory.lookup(store.stored, chapter))

        // From here it is an ordinary document: later mode changes are
        // recorded, where before Save As they would have been dropped.
        store.choose(isBookArticle = isBookArticle, id = saved, chosen = Mode.PREVIEW)
        assertEquals(Mode.PREVIEW, ViewModeMemory.lookup(store.stored, saved))
    }

    // Tokens - the three literal spellings, shared with iOS and macOS

    @Test fun modeTokensAreTheThreeLiterals() {
        assertEquals("edit", Mode.EDIT.token)
        assertEquals("split", Mode.SPLIT.token)
        assertEquals("preview", Mode.PREVIEW.token)
        // Round-trip, and a token that names nothing is null rather than fatal.
        for (mode in Mode.entries) assertEquals(mode, modeFromToken(mode.token))
        assertEquals(Mode.PREVIEW, modeFromToken("PREVIEW"))
        assertEquals(Mode.SPLIT, modeFromToken("  Split "))
        assertNull(modeFromToken("reader"))
        assertNull(modeFromToken(""))
    }

    // The stored value - format, round-trip, and the v1 gate

    @Test fun codecRoundTripsAndRejectsAForeignHeader() {
        val entries = listOf(
            ViewModeMemory.Entry("53ba23f60734adf1", Mode.SPLIT),
            ViewModeMemory.Entry("427542472354b900", Mode.PREVIEW),
        )
        val encoded = ViewModeMemory.encode(entries)
        assertEquals("v1\n53ba23f60734adf1 split\n427542472354b900 preview", encoded)
        assertEquals(entries, ViewModeMemory.decode(encoded))
        assertEquals(Mode.PREVIEW, ViewModeMemory.lookup(encoded, "427542472354b900"))
        assertNull(ViewModeMemory.lookup(encoded, "0000000000000000"))

        // Line 0 must be exactly `v1`, or the whole value is absent.
        assertEquals(emptyList<ViewModeMemory.Entry>(), ViewModeMemory.decode("v2\n53ba23f60734adf1 split"))
        assertEquals(emptyList<ViewModeMemory.Entry>(), ViewModeMemory.decode("53ba23f60734adf1 split"))
        assertEquals(emptyList<ViewModeMemory.Entry>(), ViewModeMemory.decode(""))
        assertEquals(emptyList<ViewModeMemory.Entry>(), ViewModeMemory.decode(null))
        // An empty memory is still well formed, and reads back as empty.
        assertEquals("v1", ViewModeMemory.encode(emptyList()))
        assertEquals(emptyList<ViewModeMemory.Entry>(), ViewModeMemory.decode("v1"))

        // A malformed line is skipped, never fatal: the good ones still read,
        // and a repeated id keeps only its newest (first) row.
        val messy = "v1\nnot-an-id split\n53ba23f60734adf1 sideways\n\n427542472354b900 edit\n53ba23f60734adf1 split"
        assertEquals(
            listOf(
                ViewModeMemory.Entry("427542472354b900", Mode.EDIT),
                ViewModeMemory.Entry("53ba23f60734adf1", Mode.SPLIT),
            ),
            ViewModeMemory.decode(messy),
        )
    }

    // touched() - MRU order, no duplicates, and the 200-entry cap

    @Test fun touchedIsMruAndTruncatesAtTwoHundred() {
        val a = "aaaaaaaaaaaaaaaa"
        val b = "bbbbbbbbbbbbbbbb"
        val newest = ViewModeMemory.touched(ViewModeMemory.touched(null, a, Mode.EDIT), b, Mode.PREVIEW)
        assertEquals(
            listOf(ViewModeMemory.Entry(b, Mode.PREVIEW), ViewModeMemory.Entry(a, Mode.EDIT)),
            ViewModeMemory.decode(newest),
        )
        // Touching a file already in the list moves it to the front and
        // updates it, rather than adding a second row for it.
        val again = ViewModeMemory.touched(newest, a, Mode.SPLIT)
        assertEquals(
            listOf(ViewModeMemory.Entry(a, Mode.SPLIT), ViewModeMemory.Entry(b, Mode.PREVIEW)),
            ViewModeMemory.decode(again),
        )

        // 250 distinct files: the list holds exactly the newest 200, in order,
        // and the 50 oldest are gone.
        var stored: String? = null
        val ids = (0 until 250).map { ViewModeMemory.idFor("saf:test:$it") }
        for (id in ids) stored = ViewModeMemory.touched(stored, id, Mode.SPLIT)
        val kept = ViewModeMemory.decode(stored)
        assertEquals(ViewModeMemory.MAX_ENTRIES, kept.size)
        assertEquals(200, kept.size)
        assertEquals(ids.reversed().take(200), kept.map { it.id })
        assertNull(ViewModeMemory.lookup(stored, ids[49]))
        assertEquals(Mode.SPLIT, ViewModeMemory.lookup(stored, ids[50]))

        // Exactly 200 in, exactly 200 out - the boundary itself, not 199/201.
        var atCap: String? = null
        for (id in ids.take(200)) atCap = ViewModeMemory.touched(atCap, id, Mode.EDIT)
        assertEquals(200, ViewModeMemory.decode(atCap).size)
        assertEquals(Mode.EDIT, ViewModeMemory.lookup(atCap, ids[0]))
    }

    // Identity - the cross-platform SHA-256 vectors

    @Test fun identityMatchesTheSharedShaVectors() {
        // The Apple spelling, hashed here too so a drift between the ports
        // shows up in this suite rather than on a reader's device.
        assertEquals("53ba23f60734adf1", ViewModeMemory.idFor("file:/tmp/a.md"))
        // The Android spelling - the one this app actually stores.
        assertEquals(
            "saf:com.android.externalstorage.documents:primary:Documents/a.md",
            ViewModeMemory.identityString(
                "com.android.externalstorage.documents",
                "primary:Documents/a.md",
            ),
        )
        assertEquals(
            "427542472354b900",
            ViewModeMemory.idFor("saf:com.android.externalstorage.documents:primary:Documents/a.md"),
        )
        // 16 lowercase hex digits, always - the shape `decode` will accept.
        val id = ViewModeMemory.idFor("saf:com.example:doc")
        assertEquals(16, id.length)
        assertTrue(id.all { it in '0'..'9' || it in 'a'..'f' })
        // Hashed as UTF-8, not Java's native UTF-16: a non-ASCII path must
        // agree with the Apple ports byte for byte.
        val utf8 = MessageDigest.getInstance("SHA-256")
            .digest("saf:com.example:é中".toByteArray(Charsets.UTF_8))
            .take(8).joinToString("") { b -> String.format(Locale.ROOT, "%02x", b.toInt() and 0xFF) }
        assertEquals(utf8, ViewModeMemory.idFor("saf:com.example:é中"))
        // No document id, nothing to key the memory by. A blank authority is
        // tolerated, so a bare `file:` URI still gets stable memory.
        assertNull(ViewModeMemory.identityString("com.example", null))
        assertNull(ViewModeMemory.identityString("com.example", "   "))
        assertEquals("saf::/tmp/a.md", ViewModeMemory.identityString(null, "/tmp/a.md"))
    }
}

/**
 * A pure stand-in for the screen's per-file memory plumbing: `ViewModeStore`
 * needs a `Context`, which this classpath (JUnit alone - no Robolectric, no
 * MockK) cannot give it, so the stored value is just the String the codec
 * produces. Reads and writes are counted, which is what lets a test assert
 * that an exempt document touched neither.
 */
private class MemoryProbe(var stored: String? = null) {
    var reads = 0
        private set
    var writes = 0
        private set

    fun remembered(id: String?): Mode? {
        reads++
        return id?.let { ViewModeMemory.lookup(stored, it) }
    }

    fun remember(id: String?, mode: Mode) {
        writes++
        val key = id ?: return
        stored = ViewModeMemory.touched(stored, key, mode)
    }
}

/**
 * The screen's open hook (`LaunchedEffect(documentToken)`), reduced to the
 * part that is pure: an exempt document is left in the pane it is already in,
 * with no lookup and no store; every other document is decided by
 * [openViewMode] and the raw result written back.
 */
private fun MemoryProbe.applyOpenRule(
    isBookArticle: Boolean,
    id: String?,
    isEmpty: Boolean,
    isWide: Boolean,
    current: Mode,
): Mode {
    if (!remembersViewMode(isBookArticle)) return current
    val opened = openViewMode(remembered(id), isEmpty, id != null, isWide)
    remember(id, opened)
    return opened
}

/** The screen's `chooseMode`, same reduction: a deliberate pick always takes
 *  effect on screen, and is stored for everything but a book article. */
private fun MemoryProbe.choose(isBookArticle: Boolean, id: String?, chosen: Mode): Mode {
    if (remembersViewMode(isBookArticle)) remember(id, chosen)
    return chosen
}
