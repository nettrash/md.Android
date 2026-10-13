/*
 * Rescue.kt
 * md (Android)
 *
 * What the editor says about the file under a document (#10), and where the
 * text goes when a save fails.
 *
 * A failed save never loses the text (decided 2026-10-10): it is written at
 * once to md's own storage, `files/rescued/`, and — when the document sits in
 * a folder md holds a grant on, as a book article does — beside the original
 * as "name (rescued)" with the original's own extension — the name md.win
 * gives the same copy.
 * A bar then says "Couldn't save — your text is kept. Save a copy…", and Save
 * a copy… opens the system Save dialog. The app-private copies are only a
 * safety net, so the folder keeps the newest few and drops the rest.
 */

package me.nettrash.md

import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** A bar under the app bar, until it is answered or the document replaced. */
sealed interface DocumentNotice {

    /** The file changed on disk under unsaved edits: Reload or Keep Mine. */
    data object ChangedOnDisk : DocumentNotice

    /** A save failed; [besideOriginal] names the copy next to the original
     *  when there is one, and [keptPrivately] says whether md's own copy
     *  could be written. With neither, only the buffer holds the text. */
    data class SaveFailed(val besideOriginal: String?, val keptPrivately: Boolean = true) : DocumentNotice
}

object Rescue {

    /** How many app-private copies are kept. */
    const val KEEP = 20

    /** The sibling's name, md.win's: the original's stem + " (rescued)" + its
     *  own extension — "Notes (rescued).mkd", "diagram (rescued).puml" — and
     *  `.md` for a name with none. */
    fun siblingName(displayName: String): String {
        val base = DocumentTypes.baseName(displayName)
        val extension = if (base.length < displayName.length) displayName.substring(base.length) else ".md"
        return base.ifBlank { "Untitled" } + " (rescued)" + extension
    }

    /** The MIME type to create the sibling with: one whose extension is the
     *  name's, so the provider does not tack `.md` onto "diagram (rescued).puml". */
    fun siblingMimeType(name: String): String {
        val extension = name.substringAfterLast('.', "").lowercase()
        return when (extension) {
            in DocumentTypes.MARKDOWN -> "text/markdown"
            in DocumentTypes.PLAIN_TEXT -> "text/plain"
            else -> "application/octet-stream"
        }
    }

    /** The app-private copy's name: the sibling's, with the moment it was made
     *  (UTC, so two failures in different time zones never collide), and no
     *  character a file system could refuse. */
    fun fileName(displayName: String, nowMillis: Long): String {
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.ROOT)
            .apply { timeZone = TimeZone.getTimeZone("UTC") }
            .format(Date(nowMillis))
        val base = DocumentTypes.baseName(displayName).ifBlank { "Untitled" }
            .replace(Regex("[/\\\\:*?\"<>|\\u0000-\\u001F]"), "_")
        return "$base (rescued $stamp).md"
    }

    /** Drop all but the [KEEP] newest copies in [dir]. */
    fun prune(dir: File) {
        val files = dir.listFiles { f -> f.isFile } ?: return
        files.sortedByDescending { it.lastModified() }.drop(KEEP).forEach { it.delete() }
    }
}
