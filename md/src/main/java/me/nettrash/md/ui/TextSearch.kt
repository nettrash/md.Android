/*
 * TextSearch.kt
 * md (Android)
 *
 * The find bar's engine: ordinal, case-insensitive, wrapping — for the search
 * and for the replace, which is the same one rule and no second one. No
 * regular expressions: a query is the characters typed into the box.
 *
 * This is `md.win/src/Md.App.Logic/Documents/TextSearch.cs` ported function
 * for function, names and all, so a diff between the two is a diff between
 * two lists of the same shape rather than a translation argument. Ordinal
 * because a Markdown document is not prose in one language — a culture-
 * sensitive search would fold "ß" into "ss" and hand back a range whose
 * length does not match the query, which is exactly what the caller then
 * selects in the editor. Kotlin's `ignoreCase` is the JVM's own simple case
 * folding (`String.regionMatches(true, …)`), which is locale-independent and
 * 1:1 in UTF-16 units, so a hit is always `query.length` long.
 *
 * Offsets are UTF-16 units — the text field's own — and everything here is
 * plain data: nothing in this file touches a control, a buffer or Compose,
 * so `./gradlew test` drives the very code the find bar runs. The same split
 * ViewMode.kt, Typing.kt and DiscardGuard.kt keep.
 */

package me.nettrash.md.ui

internal object TextSearch {

    /** Where a hit sits in the searched string; [length] is the query's,
     *  ordinal matching being 1:1 in UTF-16 units. */
    data class Match(val index: Int, val length: Int)

    /** One edit to the searched string: `[start, start + length)` becomes
     *  [text]. Plain data — nothing here touches a text field. */
    data class Edit(val start: Int, val length: Int, val text: String)

    /**
     * One press of Replace. [apply] is the edit to make, or null when the
     * caret is not standing on a hit — Replace is a plain Find Next then,
     * which is what every find bar does and what makes "Replace, Replace,
     * Replace…" walk the document. [searchFrom] is where the search that
     * follows starts, measured in the text the edit leaves behind: past the
     * replacement, so one that contains the query is not found again by the
     * step that made it.
     */
    data class ReplaceStep(val apply: Edit?, val searchFrom: Int)

    /**
     * What Replace All does, decided in one pass. [text] is the whole new
     * string and [count] the number of hits replaced; [apply] is the same
     * answer as the **single** edit that produces it — from the first hit's
     * start to the last hit's end, with everything between them carried
     * across — so the caller can put it in with one `TextFieldState.edit`
     * and the field's undo treats the lot as one step. The two agree by
     * construction:
     * `text.take(apply.start) + apply.text + text.drop(apply.start + apply.length) == text`.
     * With no hit, [count] is 0, [text] is the text as given and [apply] is
     * an empty edit at 0.
     */
    data class ReplaceAllPlan(val text: String, val count: Int, val apply: Edit)

    /** The first hit at or after [from], wrapping to the top; null when the
     *  query is empty or absent. */
    fun next(text: String, query: String, from: Int): Match? {
        if (query.isEmpty() || query.length > text.length) return null
        val start = from.coerceIn(0, text.length)
        var at = if (start > text.length - query.length) -1
        else text.indexOf(query, start, ignoreCase = true)
        if (at < 0) at = text.indexOf(query, 0, ignoreCase = true)
        return if (at < 0) null else Match(at, query.length)
    }

    /** The last hit starting before [from], wrapping to the bottom; null when
     *  the query is empty or absent. */
    fun previous(text: String, query: String, from: Int): Match? {
        if (query.isEmpty() || query.length > text.length) return null
        val limit = from.coerceIn(0, text.length)
        var at = lastIndexBefore(text, query, limit)
        if (at < 0) at = lastIndexBefore(text, query, text.length)
        return if (at < 0) null else Match(at, query.length)
    }

    /**
     * Whether the selection is itself a hit for [query] — "the match we are
     * standing on", which is the whole of Replace's decision. Ordinal and
     * case-insensitive like the search, so the `Alpha` that [next] selected
     * is replaceable with `alpha` still in the query box; a selection of any
     * other length never is, ordinal matching being 1:1 in UTF-16 units. A
     * selection outside the text, or a negative one, is not a match rather
     * than a throw: the caller reads it off a live field.
     */
    fun selectionIsMatch(text: String, query: String, selectionStart: Int, selectionLength: Int): Boolean {
        if (query.isEmpty() || selectionLength != query.length) return false
        if (selectionStart < 0 || selectionStart > text.length - query.length) return false
        return text.regionMatches(selectionStart, query, 0, query.length, ignoreCase = true)
    }

    /**
     * One press of Replace over the selection the editor reports. When the
     * selection is the hit ([selectionIsMatch]) the step replaces it and the
     * next search starts after what went in; otherwise nothing is edited and
     * the next search starts at the selection's end, which is where [next]
     * would start for Find Next. The caller applies the edit, re-reads the
     * text and calls [next] from [ReplaceStep.searchFrom].
     */
    fun replace(
        text: String,
        query: String,
        replacement: String,
        selectionStart: Int,
        selectionLength: Int,
    ): ReplaceStep {
        val start = selectionStart.coerceIn(0, text.length)
        val length = selectionLength.coerceIn(0, text.length - start)
        return if (selectionIsMatch(text, query, selectionStart, selectionLength)) {
            ReplaceStep(Edit(start, length, replacement), start + replacement.length)
        } else {
            ReplaceStep(null, start + length)
        }
    }

    /**
     * Every hit replaced, left to right, in one pass. The scan resumes after
     * each hit **in the old text** and never re-enters what was just put in,
     * so a replacement that contains the query ("a" → "aa") replaces each hit
     * exactly once instead of growing forever, and the pass is linear. Hits
     * do not overlap: "aa" over "aaaa" is two, not three.
     */
    fun replaceAll(text: String, query: String, replacement: String): ReplaceAllPlan {
        val nothing = ReplaceAllPlan(text, 0, Edit(0, 0, ""))
        if (query.isEmpty() || query.length > text.length) return nothing

        val built = StringBuilder()
        var first = -1
        var after = 0
        var from = 0
        var count = 0
        while (from <= text.length - query.length) {
            val at = text.indexOf(query, from, ignoreCase = true)
            if (at < 0) break
            if (first < 0) first = at
            else built.append(text, after, at)   // what stood between this hit and the last
            built.append(replacement)
            after = at + query.length
            from = after
            count++
        }
        if (count == 0) return nothing

        val edit = Edit(first, after - first, built.toString())
        val whole = text.substring(0, first) + edit.text + text.substring(after)
        return ReplaceAllPlan(whole, count, edit)
    }

    // Forward scan keeping the last hit that starts before the limit. indexOf
    // in a loop, not lastIndexOf: a backwards window is measured from its
    // start index and is the classic source of an off-by-one at either end of
    // the string.
    private fun lastIndexBefore(text: String, query: String, limit: Int): Int {
        var best = -1
        var i = 0
        while (i <= text.length - query.length) {
            val at = text.indexOf(query, i, ignoreCase = true)
            if (at < 0 || at >= limit) break
            best = at
            i = at + 1
        }
        return best
    }
}
