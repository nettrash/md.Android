/*
 * DiskStampRescueTest.kt
 * md (Android)
 *
 * The two pure halves of #10: when a file counts as changed on disk (only a
 * value both sides know, and different — a missing stamp never blocks a
 * save), and what a rescued copy is called and how many are kept.
 */

package me.nettrash.md

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class DiskStampRescueTest {

    @Test fun aMovedTimeOrSizeIsAChange() {
        val read = DiskStamp(1_000L, 10L)
        assertFalse(DiskStamp.changed(read, DiskStamp(1_000L, 10L)))
        assertTrue(DiskStamp.changed(read, DiskStamp(2_000L, 10L)))
        assertTrue(DiskStamp.changed(read, DiskStamp(1_000L, 11L)))
    }

    @Test fun anUnknownNeverBlocks() {
        val read = DiskStamp(1_000L, 10L)
        assertFalse(DiskStamp.changed(null, read))
        assertFalse(DiskStamp.changed(read, null))
        // Only what both sides know is compared.
        assertFalse(DiskStamp.changed(DiskStamp(null, 10L), DiskStamp(5_000L, 10L)))
        assertFalse(DiskStamp.changed(DiskStamp(1_000L, null), DiskStamp(1_000L, 99L)))
        assertTrue(DiskStamp.changed(DiskStamp(null, 10L), DiskStamp(null, 12L)))
    }

    @Test fun zeroTimeIsUnknownNotTheEpoch() {
        assertEquals(DiskStamp(null, 10L), DiskStamp.of(0L, 10L))
        assertEquals(DiskStamp(5L, null), DiskStamp.of(5L, -1L))
        assertNull(DiskStamp.of(null, null))
        assertNull(DiskStamp.of(0L, null))
        assertEquals(DiskStamp(5L, 0L), DiskStamp.of(5L, 0L))
    }

    @Test fun theSiblingIsNamedAsOnWindowsAndTheMac() {
        assertEquals("Notes (rescued).md", Rescue.siblingName("Notes.md"))
        assertEquals("Notes (rescued).mkd", Rescue.siblingName("Notes.mkd"))
        assertEquals("Chapter 1 (rescued).markdown", Rescue.siblingName("Chapter 1.markdown"))
        assertEquals("Untitled (rescued).md", Rescue.siblingName(""))
        assertEquals("diagram (rescued).puml", Rescue.siblingName("diagram.puml"))
        assertEquals("README (rescued).md", Rescue.siblingName("README"))
        assertEquals("text/markdown", Rescue.siblingMimeType("Notes (rescued).mkd"))
        assertEquals("text/plain", Rescue.siblingMimeType("a (rescued).txt"))
        assertEquals("application/octet-stream", Rescue.siblingMimeType("diagram (rescued).puml"))
    }

    @Test fun theAppPrivateCopyIsTimestampedAndSafe() {
        // 2026-10-10 12:34:56 UTC
        assertEquals("Notes (rescued 20261010-123456).md", Rescue.fileName("Notes.md", 1_791_635_696_000L))
        assertEquals("a_b_c (rescued 20261010-123456).md", Rescue.fileName("a/b:c.md", 1_791_635_696_000L))
    }

    @Test fun pruneKeepsTheNewest() {
        val dir = Files.createTempDirectory("rescued").toFile()
        try {
            repeat(Rescue.KEEP + 5) { i ->
                File(dir, "copy-$i.md").apply { writeText("$i"); setLastModified(1_000_000L + i * 1_000L) }
            }
            Rescue.prune(dir)
            val left = dir.listFiles()!!.map { it.name }.toSet()
            assertEquals(Rescue.KEEP, left.size)
            assertTrue("copy-${Rescue.KEEP + 4}.md" in left)
            assertFalse("copy-0.md" in left)
        } finally {
            dir.deleteRecursively()
        }
    }
}
