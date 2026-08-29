/*
 * ViewMode.kt
 * md (Android)
 *
 * The editor's three display modes, the width-adaptive rule for which of
 * them are offered, and the per-file memory of the one the reader last used.
 *
 * The width rule mirrors the iOS `DocumentView.swift`: a phone-width window
 * offers only Edit and Preview, while a wide window — a tablet, an unfolded
 * foldable, a desktop / freeform window, or a large phone in landscape —
 * also offers Split, the two panes side by side re-rendering as you type.
 * macOS always has room and shows all three; on Android, as on iPhone, Split
 * is dropped when there is no horizontal room for it.
 *
 * [openViewMode] is the mode a document *opens* in, and [ViewModeMemory] is
 * the little codec behind it: a per-file record of the mode the reader last
 * chose, so a file comes back the way they left it. Both are shared verbatim
 * with the iOS and macOS ports — same rule, same storage key, same tokens,
 * same identity hash — so the three apps agree about one document.
 *
 * Everything here except [ViewModeStore] is kept free of Compose, Context and
 * Uri imports so it is plain, unit-testable Kotlin — the unit-test classpath
 * is JUnit alone, so a single `Context` or `Uri` touch would put the logic
 * beyond the reach of `./gradlew test`. `EditorScreen` supplies the current
 * window width and the document, and renders the result.
 */

package me.nettrash.md.ui

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import androidx.core.content.edit
import java.security.MessageDigest
import java.util.Locale

/** Editor display modes, in the order they appear in the switch. */
internal enum class Mode { EDIT, SPLIT, PREVIEW }

/**
 * The window width (in dp) at or above which Split is offered. 600dp is the
 * Material compact/medium boundary — phones in portrait fall below it while
 * tablets, unfolded foldables, desktop windows and large phones in landscape
 * sit above — the closest analogue to iOS's compact-vs-regular horizontal
 * size class, which is what gates Split there.
 */
internal const val WIDE_LAYOUT_MIN_WIDTH_DP = 600

/** Whether a window this wide (dp) has room to offer Split. */
internal fun isWideLayout(widthDp: Int): Boolean = widthDp >= WIDE_LAYOUT_MIN_WIDTH_DP

/**
 * The modes offered at the current width: all three when there's room for
 * Split, otherwise just Edit and Preview (like iPhone).
 */
internal fun availableModes(isWide: Boolean): List<Mode> =
    if (isWide) listOf(Mode.EDIT, Mode.SPLIT, Mode.PREVIEW)
    else listOf(Mode.EDIT, Mode.PREVIEW)

/**
 * The mode actually shown: the remembered preference, coerced to one the
 * current width supports. A remembered Split collapses to Edit on a narrow
 * window — without discarding the stored preference, so widening the window
 * (rotating, unfolding, leaving split-screen) brings Split back. Mirrors the
 * iOS `effectiveMode`.
 */
internal fun effectiveMode(stored: Mode, isWide: Boolean): Mode {
    val available = availableModes(isWide)
    return when {
        stored in available -> stored
        stored == Mode.PREVIEW -> Mode.PREVIEW
        else -> Mode.EDIT
    }
}

/**
 * The mode a table-of-contents tap has to switch to before the preview can
 * be scrolled to the heading, or null when the pane the reader is already in
 * shows the preview (Split and Preview both do).
 *
 * [displayed] is what is actually on screen — the screen's `currentMode`,
 * after [effectiveMode] has narrowed the preference to fit the window —
 * because "is the preview visible?" is the only question this asks.
 *
 * It once took the RAW preference instead. That was a fix aimed at the wrong
 * half of the problem: the tap adopted its answer through `chooseMode`, which
 * *writes* to the per-file memory, so on a narrow window — where a remembered
 * Split renders as Edit — deciding from the rendered mode rewrote that
 * reader's stored Split to Preview, permanently, at a width where
 * [availableModes] would never let them pick Split back. Reading the raw mode
 * stopped the damage but broke the feature: a raw-Split file rendered as Edit
 * saw Split here, no switch happened, and the tap scrolled a preview that was
 * not on screen — the tap did nothing visible.
 *
 * The question was right; the *action* was wrong. Jumping to a heading is a
 * navigation nudge, not a layout choice, so the screen now adopts the answer
 * into a separate, non-persisted `navigationMode` that overrides only what is
 * displayed. The file's remembered preference is not touched, and comes
 * straight back the moment the reader picks a mode from the switch or opens
 * another document. Deciding from what is on screen is therefore safe again,
 * because it no longer writes anything — which is how these apps behaved
 * before per-file memory existed, when the mode was session-only: a jump
 * moved you, and it was never a preference.
 */
internal fun contentsTapMode(displayed: Mode): Mode? =
    if (displayed == Mode.EDIT) Mode.PREVIEW else null

/**
 * The mode a document opens in — the same four-line rule on all three apps.
 *
 *  - a file we have seen before opens in exactly the mode it was left in,
 *    **raw and uncoerced**: the caller assigns this to the raw preference and
 *    lets [effectiveMode] narrow it for the current window. Storing the
 *    coerced value instead would destroy a remembered Split the first time
 *    the file was opened on a phone;
 *  - a document with nothing in it — File > New, shared-in empty text, a real
 *    0-byte file — opens in Edit, because there is nothing to read;
 *  - an unknown file with content opens in Split where Split exists, which is
 *    what a tablet, a foldable and a desktop window did before this feature
 *    and still do: nothing about first launch changes there;
 *  - an unknown file with content on a narrow window, where Split is not on
 *    offer, opens in Preview — reader first.
 *
 * [hasFileIdentity] is not consulted: a document with no identity can never
 * have a [remembered] mode, so the fallback branches already govern it. It is
 * part of the signature because the three ports share one rule verbatim, and
 * it states the caller's obligation — look the mode up, and store it, only
 * for a document that has an identity to key it by.
 */
internal fun openViewMode(
    remembered: Mode?,
    isEmptyDocument: Boolean,
    hasFileIdentity: Boolean,
    isWide: Boolean,
): Mode = when {
    remembered != null -> remembered
    isEmptyDocument -> Mode.EDIT
    isWide -> Mode.SPLIT
    else -> Mode.PREVIEW
}

/**
 * Whether this document takes part in per-file view-mode memory at all:
 * false for an article opened through the writer-mode book, true for every
 * ordinary document.
 *
 * A book is stepped through chapter by chapter in the one editor, so an
 * article must neither be **looked up** — re-running [openViewMode] on each
 * step would drop a writer into Preview on the chapter they came to write —
 * nor **stored**, the pane being a property of the book session rather than
 * of that one chapter file. Both halves are the same question, which is why
 * this is one predicate and not two flag checks spelled out at the call
 * sites; and it is here, pure, so `./gradlew test` can reach it at all.
 *
 * The exemption follows the *open*, not the file: `DocumentViewModel.saveAs`
 * clears its book flag, because saving a chapter out as a new file makes it
 * an ordinary standalone document — so the new file starts being remembered
 * at once, and so is every later mode change on it.
 *
 * The same exemption ships on all three ports.
 */
internal fun remembersViewMode(isBookArticle: Boolean): Boolean = !isBookArticle

/**
 * The stored spelling of a mode — written out by hand, never `name` or
 * `toString()`, so renaming the enum can never silently invalidate every
 * reader's saved preferences (and so the three ports store the same bytes).
 */
internal val Mode.token: String
    get() = when (this) {
        Mode.EDIT -> "edit"
        Mode.SPLIT -> "split"
        Mode.PREVIEW -> "preview"
    }

/** The mode a stored token names, or null when it names none. Case-
 *  insensitive and whitespace-tolerant; an unreadable token is skipped, never
 *  fatal. */
internal fun modeFromToken(token: String): Mode? =
    when (token.trim().lowercase(Locale.ROOT)) {
        "edit" -> Mode.EDIT
        "split" -> Mode.SPLIT
        "preview" -> Mode.PREVIEW
        else -> null
    }

/**
 * The codec and the identity hash behind per-file view-mode memory — the
 * whole of the logic, and every line of it pure, so `./gradlew test` can
 * reach all of it. [ViewModeStore] is the thin impure shell around it.
 *
 * The stored value is one string, MRU-ordered, newest first:
 *
 * ```
 * v1
 * <16 hex> <token>
 * …
 * ```
 *
 * Line 0 must be exactly `v1` or the whole value is treated as absent — that
 * is the version gate, and it is how a future format retires this one without
 * having to read it. Malformed entry lines are skipped, never fatal.
 *
 * Deliberately **not** JSON: this module's unit tests have only JUnit on the
 * classpath, and `org.json` is the stubbed android.jar under `./gradlew test`
 * ("not mocked"), so a JSON codec could not be tested at all.
 *
 * The list is capped at [MAX_ENTRIES] (≈5 KB), oldest dropped first, so the
 * preference can never grow without bound on a reader with a large library.
 */
internal object ViewModeMemory {

    /** The preferences key, verbatim on iOS, macOS and here — the same family
     *  convention as `md.pdfPageSize`. */
    const val KEY = "md.viewModeMemory"

    /** The most files remembered at once; the oldest fall off the end. */
    const val MAX_ENTRIES = 200

    private const val HEADER = "v1"

    /** One remembered file: its [id] (see [idFor]) and the raw [mode]. */
    data class Entry(val id: String, val mode: Mode)

    /**
     * The identity string a document is remembered under, or null when the
     * document has none and must therefore be neither looked up nor stored.
     *
     * A pure function of two strings on purpose — the `DocumentsContract`
     * call that produces [documentId] lives in [ViewModeStore], leaving this
     * half (the half the cross-platform test vector pins) unit-testable.
     *
     * A blank [authority] is tolerated so a bare `file:` URI from an older
     * file manager still gets stable memory; a missing document id is not,
     * since nothing then distinguishes one document from another.
     */
    fun identityString(authority: String?, documentId: String?): String? {
        val id = documentId?.trim().orEmpty()
        if (id.isEmpty()) return null
        return "saf:" + authority.orEmpty() + ":" + id
    }

    /**
     * The first 16 hex digits of SHA-256 over the UTF-8 bytes of [identity] —
     * short enough to keep the whole list small, wide enough that a
     * collision is not a practical concern for a personal library.
     *
     * UTF-8 explicitly: hashing Java's native UTF-16 here would silently
     * disagree with the Apple ports for any non-ASCII path.
     */
    fun idFor(identity: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(identity.toByteArray(Charsets.UTF_8))
        // `and 0xFF` because a Kotlin Byte is signed: without it every byte
        // over 0x7F formats sign-extended ("ffffffb0"). Locale.ROOT so a
        // locale with its own digits can't reach the hex.
        return digest.take(8).joinToString("") { String.format(Locale.ROOT, "%02x", it.toInt() and 0xFF) }
    }

    /** Parse a stored value into MRU order, newest first. A value that is
     *  absent, empty or not headed by `v1` reads as no memory at all; an
     *  unreadable line is skipped and a repeated id keeps only its newest
     *  (first) occurrence. */
    fun decode(stored: String?): List<Entry> {
        val lines = stored?.split('\n') ?: return emptyList()
        if (lines.firstOrNull() != HEADER) return emptyList()
        val entries = mutableListOf<Entry>()
        val seen = mutableSetOf<String>()
        for (line in lines.drop(1)) {
            val parts = line.trim().split(' ')
            if (parts.size != 2) continue
            // The mode is parsed BEFORE the id is marked seen: a line with an
            // unreadable token must not consume its file's one slot and hide
            // the good line for the same file further down the list.
            val mode = modeFromToken(parts[1]) ?: continue
            val id = parts[0]
            if (!isIdentity(id) || !seen.add(id)) continue
            entries += Entry(id, mode)
        }
        return entries
    }

    /** Render [entries] back to the stored form. Always headed by `v1`, so
     *  an empty list is still a well-formed (and empty) memory. */
    fun encode(entries: List<Entry>): String =
        (listOf(HEADER) + entries.map { "${it.id} ${it.mode.token}" }).joinToString("\n")

    /** The mode remembered for [id], or null when that file is unknown. */
    fun lookup(stored: String?, id: String): Mode? =
        decode(stored).firstOrNull { it.id == id }?.mode

    /** [stored] with [id] recorded as [mode] and moved to the front of the
     *  MRU list, truncated to [MAX_ENTRIES]. Storing the same file again just
     *  refreshes it — the list never grows a duplicate. */
    fun touched(stored: String?, id: String, mode: Mode): String {
        val rest = decode(stored).filterNot { it.id == id }
        return encode((listOf(Entry(id, mode)) + rest).take(MAX_ENTRIES))
    }

    /** Whether [id] has the shape [idFor] produces: 16 lowercase hex digits. */
    private fun isIdentity(id: String): Boolean =
        id.length == 16 && id.all { it in '0'..'9' || it in 'a'..'f' }
}

/**
 * The impure shell over [ViewModeMemory]: SharedPreferences, and the one
 * `DocumentsContract` call that turns a document URI into an identity.
 * Constructed with the application context so it outlives any one screen —
 * the same shape as [PageSizeState].
 *
 * Its own preferences file, "view": "export" and "book" are taken.
 */
internal class ViewModeStore(context: Context) {

    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /**
     * The identity to remember [uri]'s mode under, or null when it has none.
     *
     * `DocumentsContract.getDocumentId` is preferred: it is the provider's own
     * stable name for the document, unchanged across sessions and across the
     * flags a URI happens to be granted. Deliberately **not** gated on holding
     * a persistable grant — the ordinary read-only URI a file manager hands in
     * with ACTION_VIEW is perfectly stable, and gating would throw away memory
     * for exactly the files most people open. A URI that is not a document URI
     * at all (a FileProvider hand-off, a bare `file:`) falls back to its path,
     * which is as stable as that URI gets. A genuinely ephemeral URI simply
     * never matches again and ages out of the MRU list.
     *
     * Rename or move outside the app loses the memory and the file re-enters
     * as unknown — accepted, and the same on all three ports.
     */
    fun identityFor(uri: Uri?): String? {
        val target = uri ?: return null
        val documentId = runCatching { DocumentsContract.getDocumentId(target) }.getOrNull()
            ?: target.path
        val identity = ViewModeMemory.identityString(target.authority, documentId) ?: return null
        return ViewModeMemory.idFor(identity)
    }

    /** The raw mode remembered for [id] — uncoerced, exactly as it was
     *  stored — or null for an unknown file or a document with no identity. */
    fun remembered(id: String?): Mode? =
        id?.let { ViewModeMemory.lookup(prefs.getString(ViewModeMemory.KEY, null), it) }

    /** Record [mode] as [id]'s mode, newest first. A null [id] (a document
     *  with no identity: untitled, shared-in text, an imported pack) is
     *  silently ignored — there is nothing to key it by. */
    fun remember(id: String?, mode: Mode) {
        val key = id ?: return
        val updated = ViewModeMemory.touched(prefs.getString(ViewModeMemory.KEY, null), key, mode)
        prefs.edit { putString(ViewModeMemory.KEY, updated) }
    }

    private companion object {
        const val PREFS = "view"
    }
}
