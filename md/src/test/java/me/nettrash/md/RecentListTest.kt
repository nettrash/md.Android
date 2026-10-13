/*
 * RecentListTest.kt
 * md (Android)
 *
 * Open Recent's rules (#11): newest first, one row per document, about 25
 * kept with what falls off reported (its grant is released), and a stored
 * list that survives any name and any damage.
 */

package me.nettrash.md

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RecentListTest {

    private fun entry(i: Int) = RecentEntry("content://p/document/$i", "Doc $i.md")

    @Test fun theNewestGoesFirstAndADocumentIsListedOnce() {
        val (list, evicted) = RecentList.record(listOf(entry(1), entry(2), entry(3)), entry(2).copy(name = "Renamed.md"))
        assertEquals(listOf("Renamed.md", "Doc 1.md", "Doc 3.md"), list.map { it.name })
        assertTrue(evicted.isEmpty())
    }

    @Test fun pastTheLimitTheOldestFallsOffAndIsReported() {
        var list = emptyList<RecentEntry>()
        for (i in 1..RecentList.LIMIT) list = RecentList.record(list, entry(i)).first
        assertEquals(RecentList.LIMIT, list.size)
        val (next, evicted) = RecentList.record(list, entry(100))
        assertEquals(RecentList.LIMIT, next.size)
        assertEquals(entry(100), next.first())
        assertEquals(listOf(entry(1)), evicted)
        // Re-recording one already listed evicts nothing.
        assertTrue(RecentList.record(next, entry(5)).second.isEmpty())
    }

    @Test fun theStoredListRoundTripsAnyName() {
        val list = listOf(
            RecentEntry("content://com.android.externalstorage.documents/document/primary%3ANotes.md", "Notes.md"),
            RecentEntry("content://p/document/tab", "a\tb\nc\\d\re"),
            RecentEntry("content://p/document/ru", "Заметки 🙂.md"),
        )
        assertEquals(list, RecentList.decode(RecentList.encode(list)))
    }

    @Test fun damageIsSkippedNotFatal() {
        assertEquals(emptyList<RecentEntry>(), RecentList.decode(null))
        assertEquals(emptyList<RecentEntry>(), RecentList.decode(""))
        assertEquals(
            listOf(RecentEntry("content://a", "A"), RecentEntry("content://c", "Untitled")),
            RecentList.decode("content://a\tA\nno tab here\n\tno uri\ncontent://a\tduplicate\ncontent://c\t"),
        )
        val many = (1..40).joinToString("\n") { "content://x/$it\tn$it" }
        assertEquals(RecentList.LIMIT, RecentList.decode(many).size)
    }
}
