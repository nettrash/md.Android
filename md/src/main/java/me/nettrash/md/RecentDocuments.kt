/*
 * RecentDocuments.kt
 * md (Android)
 *
 * Open Recent and the launcher's long-press shortcuts (#11), built on the
 * persistable Storage Access Framework grants md already takes when a file is
 * opened, created or handed over with one. md used to take those grants and
 * never list them or give them back; now the documents it holds a grant for
 * are remembered, newest first, and:
 *
 *  - the list keeps [RecentList.LIMIT] entries; a grant pushed off the end is
 *    released — unless that document is the one open — so md never sits on
 *    access to files it no longer offers;
 *  - an entry whose grant has gone (the file was deleted, the provider was
 *    uninstalled, the user revoked access in Settings) is dropped quietly,
 *    when the list is read and when it fails to open;
 *  - the newest few are the launcher's dynamic shortcuts, each an ACTION_VIEW
 *    of the document, which MainActivity hands to the same path as any other
 *    "Open with md" — the grant md holds makes it writable again.
 *
 * Book articles are not recorded: md's grant there is the book's folder, and
 * the book navigator is their recent list. No new permission.
 */

package me.nettrash.md

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat

/** One remembered document: its URI (as a string) and the name it was shown under. */
data class RecentEntry(val uri: String, val name: String)

/** The list's rules, pure so the JVM tests reach them. */
object RecentList {

    /** How many documents are remembered. */
    const val LIMIT = 25

    /** How many become launcher shortcuts (most launchers show four). */
    const val SHORTCUTS = 4

    /** [entry] at the front, any older row for the same document removed, cut
     *  to [LIMIT]. Returns the new list and what fell off the end. */
    fun record(list: List<RecentEntry>, entry: RecentEntry): Pair<List<RecentEntry>, List<RecentEntry>> {
        val merged = listOf(entry) + list.filter { it.uri != entry.uri }
        return merged.take(LIMIT) to merged.drop(LIMIT)
    }

    /** One line per document, "uri<TAB>name". A URI string is already
     *  percent-encoded, so it holds no tab or line break; a name may, and
     *  backslash-escapes them. Plain Kotlin rather than org.json, so the JVM
     *  tests run the real thing. */
    fun encode(list: List<RecentEntry>): String =
        list.joinToString("\n") { it.uri + "\t" + escape(it.name) }

    /** The stored list; a line that cannot be read is skipped, never a crash. */
    fun decode(stored: String?): List<RecentEntry> {
        if (stored.isNullOrEmpty()) return emptyList()
        return stored.split('\n').mapNotNull { line ->
            val tab = line.indexOf('\t')
            if (tab <= 0) return@mapNotNull null
            RecentEntry(line.substring(0, tab), unescape(line.substring(tab + 1)).ifEmpty { "Untitled" })
        }.distinctBy { it.uri }.take(LIMIT)
    }

    private fun escape(name: String): String = buildString {
        for (c in name) when (c) {
            '\\' -> append("\\\\")
            '\t' -> append("\\t")
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            else -> append(c)
        }
    }

    private fun unescape(name: String): String = buildString {
        var i = 0
        while (i < name.length) {
            val c = name[i]
            if (c == '\\' && i + 1 < name.length) {
                when (name[i + 1]) {
                    't' -> append('\t')
                    'n' -> append('\n')
                    'r' -> append('\r')
                    else -> append(name[i + 1])
                }
                i += 2
            } else {
                append(c)
                i++
            }
        }
    }
}

class RecentDocuments(private val context: Context) {

    private val prefs = context.getSharedPreferences("recent_documents", Context.MODE_PRIVATE)
    private val resolver: ContentResolver get() = context.contentResolver

    /** The remembered documents whose grant md still holds, newest first. */
    fun entries(): List<RecentEntry> {
        val stored = RecentList.decode(prefs.getString(KEY, null))
        val held = heldGrants()
        val live = stored.filter { Uri.parse(it.uri) in held }
        if (live.size != stored.size) save(live)
        return live
    }

    /** Remember [uri] as the newest document — only when md holds a
     *  persistable grant for it, so every row can be opened again. A grant
     *  pushed off the end is released unless it is [open]. */
    fun record(uri: Uri, name: String, open: Uri? = uri) {
        if (uri !in heldGrants()) return
        val (list, evicted) = RecentList.record(entries(), RecentEntry(uri.toString(), name))
        save(list)
        // Remembered again: a release Clear Recent deferred is called off.
        deferred = deferred - uri.toString()
        evicted.forEach { release(Uri.parse(it.uri), open) }
    }

    /** Forget [uri] — it could not be opened. */
    fun remove(uri: Uri) {
        val list = entries().filter { it.uri != uri.toString() }
        save(list)
    }

    /** Forget every document and give back their grants — the [open] one's
     *  once it is no longer open (see [releaseDeferred]). */
    fun clear(open: Uri?) {
        val list = entries()
        save(emptyList())
        list.forEach { release(Uri.parse(it.uri), open) }
    }

    /** Give back the grants whose release had to wait because their document
     *  was open — now that it is not ([open] is the document on screen), and
     *  unless it has been remembered again since. Called on every document
     *  change. */
    fun releaseDeferred(open: Uri?) {
        val waiting = deferred
        if (waiting.isEmpty()) return
        val listed = RecentList.decode(prefs.getString(KEY, null)).map { it.uri }.toSet()
        val stillOpen = waiting.filter { it == open?.toString() }.toSet()
        deferred = stillOpen
        (waiting - stillOpen - listed).forEach { release(Uri.parse(it), open) }
    }

    /** URIs whose grant is to be given back once their document is closed. */
    private var deferred: Set<String>
        get() = prefs.getStringSet(DEFERRED, null)?.toSet().orEmpty()
        set(value) { prefs.edit().putStringSet(DEFERRED, value).apply() }

    private fun save(list: List<RecentEntry>) {
        prefs.edit().putString(KEY, RecentList.encode(list)).apply()
        publishShortcuts(list)
    }

    private fun heldGrants(): Set<Uri> = runCatching {
        resolver.persistedUriPermissions.filter { it.isReadPermission }.map { it.uri }.toSet()
    }.getOrDefault(emptySet())

    private fun release(uri: Uri, open: Uri?) {
        if (uri == open) {
            // Still on screen: given back when it is replaced, not forgotten.
            deferred = deferred + uri.toString()
            return
        }
        val grant = runCatching { resolver.persistedUriPermissions.firstOrNull { it.uri == uri } }.getOrNull() ?: return
        val modes = (if (grant.isReadPermission) Intent.FLAG_GRANT_READ_URI_PERMISSION else 0) or
            (if (grant.isWritePermission) Intent.FLAG_GRANT_WRITE_URI_PERMISSION else 0)
        runCatching { resolver.releasePersistableUriPermission(uri, modes) }
    }

    /** The newest few as the launcher's long-press shortcuts. */
    private fun publishShortcuts(list: List<RecentEntry>) {
        runCatching {
            val shortcuts = list.take(RecentList.SHORTCUTS).map { entry ->
                ShortcutInfoCompat.Builder(context, "recent:" + entry.uri)
                    .setShortLabel(entry.name.take(25))
                    .setLongLabel(entry.name)
                    .setIcon(IconCompat.createWithResource(context, R.drawable.ic_shortcut_document))
                    .setIntent(
                        Intent(Intent.ACTION_VIEW, Uri.parse(entry.uri))
                            .setClass(context, MainActivity::class.java)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    )
                    .build()
            }
            // False when the launcher is rate-limiting md; the next change publishes.
            if (!ShortcutManagerCompat.setDynamicShortcuts(context, shortcuts)) {
                android.util.Log.w("md", "The launcher refused the recent-document shortcuts for now")
            }
        }.onFailure { android.util.Log.w("md", "Couldn't publish the recent-document shortcuts", it) }
    }

    private companion object {
        const val KEY = "entries"
        const val DEFERRED = "deferred_releases"
    }
}
