/*
 * DiskStamp.kt
 * md (Android)
 *
 * What a file looked like on disk the last time md read or wrote it: its
 * modification time and its size, as the documents provider reports them —
 * md.win's `FileStamp`, modelled on `TextFileSession`. Before every write the
 * stamp is taken again; if it moved, something else changed the file under
 * md's unsaved edits, and the write is held back rather than clobbering it
 * (the editor offers Reload or Keep Mine).
 *
 * Best effort, by decision: a provider that reports neither column, a query
 * that fails, a field one side does not know — none of these ever blocks a
 * save. Only a value known on both sides and different counts as a change. A
 * modification time of 0 is "unknown" (what several providers report), not
 * the epoch.
 */

package me.nettrash.md

data class DiskStamp(val modified: Long?, val size: Long?) {

    companion object {

        /** A stamp from raw provider values, 0 / negative read as unknown. */
        fun of(modified: Long?, size: Long?): DiskStamp? {
            val m = modified?.takeIf { it > 0 }
            val s = size?.takeIf { it >= 0 }
            return if (m == null && s == null) null else DiskStamp(m, s)
        }

        /** Whether the file moved from [expected] to [current]. False when
         *  either is unknown — a missing stamp never blocks a save. */
        fun changed(expected: DiskStamp?, current: DiskStamp?): Boolean {
            if (expected == null || current == null) return false
            val modified = expected.modified != null && current.modified != null && expected.modified != current.modified
            val size = expected.size != null && current.size != null && expected.size != current.size
            return modified || size
        }
    }
}
