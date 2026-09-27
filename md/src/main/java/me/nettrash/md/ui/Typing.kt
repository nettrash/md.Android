/*
 * Typing.kt
 * md (Android)
 *
 * The editor's smart-typing adapter, the pure half. `SmartTyping`
 * (markdown/SmartTyping.kt) is two functions over a text and a selection —
 * what Return does in a list, a quote or a table, and which typed letter
 * becomes a capital — and this file is what stands between them and a Compose
 * text field: the reduction of a change list to the one edit those functions
 * are asked about (SPEC §3.3), the "word insertion" rule that turns Android's
 * composed and committed words into the single scalar `capitalize` takes, the
 * plan the transformation applies to its buffer, and the override of §3.4 —
 * a tracked capital and an armed offset — that lets a writer keep `md`
 * lowercase at a sentence start by deleting the capital and typing the letter
 * again.
 *
 * `SmartTypingTransformation.kt` is the thin Compose shell around these: it
 * reads the buffer's change list, asks [Typing.plan] what to do, and applies
 * the answer. Everything here except [TypingSettings] is free of Compose,
 * Context and Uri, so `./gradlew test` reaches all of it — the same split
 * ViewMode.kt keeps — and the JVM tests drive the very code the field runs.
 *
 * WHY A REDUCTION
 * ---------------
 * `capitalize` takes exactly one scalar, the letter being typed. Android
 * never hands an editor one letter: Gboard composes, so the field sees "h",
 * then "he" over the same region, then "hello " when the word is committed;
 * glide typing commits a whole word at once; a paste is one replacement.
 * §3.3 reduces each to its first scalar when — and only when — the insertion
 * is a *word*: no line break, no whitespace but at most one trailing space, a
 * lowercase first letter, and nothing that reads as a URL, a path or a handle.
 * The rule is idempotent under the re-sends: "He" re-sent as "hel" is
 * capitalized again, to "Hel".
 *
 * Nothing here calls `isWhitespace`, `isLetter`, `trim()` or a regular
 * expression — §0.3 names the nineteen scalars that are whitespace, §0.4 reads
 * a letter's class off `Character.getType(Int)`, and §0.8 bans regexes — for
 * the reason SmartTyping.kt gives: "whitespace" has four answers across the
 * family's runtimes.
 */

package me.nettrash.md.ui

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.edit
import kotlin.math.max
import kotlin.math.min
import me.nettrash.md.markdown.SmartTyping

/**
 * One change the text field reports: `[originalStart, originalEnd)` of the
 * text *before* the edit was replaced by [inserted] (empty for a deletion).
 * A plain value so the classifier can be tested without a `TextFieldBuffer`.
 */
internal data class TypingChange(val originalStart: Int, val originalEnd: Int, val inserted: String)

/** The one edit a change set reduces to, or nothing (`null`). */
internal sealed class TypingEdit {

    /** Return pressed with a collapsed caret at [start] (`== end`). */
    data class Enter(val start: Int, val end: Int) : TypingEdit()

    /**
     * A word insertion (§3.3) replacing `[start, end)` of the original text.
     * [first] is its first scalar as a string — the `typed` `capitalize`
     * sees — and [firstScalar] the same as a code point.
     */
    data class Word(val start: Int, val end: Int, val inserted: String) : TypingEdit() {
        val firstScalar: Int get() = inserted.codePointAt(0)
        val first: String get() = inserted.substring(0, Character.charCount(firstScalar))
    }
}

/**
 * What the transformation does to its buffer after the platform's edit: take
 * the platform's newline back out and apply `enter`'s one replacement, or
 * replace the first scalar of the word just inserted with its capital.
 * `null` keeps the edit as the platform made it.
 */
internal sealed class TypingPlan {

    /** §0.9: revert the platform's "\n" and apply [edit] to the original text. */
    data class Enter(val edit: SmartTyping.EnterEdit) : TypingPlan()

    /** Replace `[at, at + length)` of the edited text — the word's first scalar — with [upper]. */
    data class Capital(val at: Int, val length: Int, val upper: String) : TypingPlan()
}

internal object Typing {

    /** §0.3: the nineteen scalars that are whitespace here, and no other. */
    fun isWS19(u: Int): Boolean =
        u == 0x09 || u == 0x20 || u == 0xa0 || u == 0x1680 || (u in 0x2000..0x200a) ||
            u == 0x200b || u == 0x202f || u == 0x205f || u == 0x3000

    /**
     * §3.3: an insertion is a **word insertion** iff its text contains no line
     * terminator and no WS19 unit except at most one trailing SP, its first
     * scalar is a Lowercase letter (`Ll` — a lone surrogate is `Cs` and
     * fails), and it contains none of `://`, `www.`, `@`, `/`. A pasted or
     * predicted `https://a.b`, `~/Documents/x` or `@nettrash` is inserted
     * unchanged: `capitalize` sees only the first scalar and cannot tell.
     */
    fun isWordInsertion(text: String): Boolean {
        if (text.isEmpty()) return false
        if (Character.getType(text.codePointAt(0)) != Character.LOWERCASE_LETTER.toInt()) return false
        val last = text.length - 1
        for (i in 0..last) {
            val u = text[i].code
            if (u == 0x0a || u == 0x0d) return false
            if (isWS19(u) && !(i == last && u == 0x20)) return false
        }
        return text.indexOf("://") < 0 && text.indexOf("www.") < 0 &&
            text.indexOf('@') < 0 && text.indexOf('/') < 0
    }

    /**
     * §3.3 (Android): the change set is exactly one insertion of `"\n"` at a
     * collapsed original selection → [TypingEdit.Enter]; exactly one
     * replacement whose inserted text is a word insertion → [TypingEdit.Word];
     * anything else — a deletion, several changes, a newline over a selection,
     * a phrase, a paste with spaces — `null`, and the field keeps the edit as
     * the platform made it.
     *
     * [selectionStart] / [selectionEnd] are the selection *before* the edit, in
     * either order (Android reports anchor > focus for a backwards selection).
     */
    fun classify(changes: List<TypingChange>, selectionStart: Int, selectionEnd: Int): TypingEdit? {
        if (changes.size != 1) return null
        val change = changes[0]
        if (change.inserted == "\n") {
            val s0 = min(selectionStart, selectionEnd)
            val s1 = max(selectionStart, selectionEnd)
            val collapsedAtCaret = change.originalStart == change.originalEnd &&
                change.originalStart == s0 && s0 == s1
            return if (collapsedAtCaret) TypingEdit.Enter(s0, s0) else null
        }
        if (isWordInsertion(change.inserted)) {
            return TypingEdit.Word(change.originalStart, change.originalEnd, change.inserted)
        }
        return null
    }

    /**
     * The adapter, whole: what the field does with one change set against
     * [text], the text *before* the edit, typed while the selection was
     * `[selectionStart, selectionEnd)` (either order). Every change set —
     * a word, a Return, a deletion, a paste, several changes at once — is
     * fed to [override] so the tracked capital and the armed offset of §3.4
     * follow the text; a function whose setting is off is never called.
     * Returns the plan the caller applies on top of the platform's edit, or
     * `null` to keep that edit as it is.
     */
    fun plan(
        text: String,
        selectionStart: Int,
        selectionEnd: Int,
        changes: List<TypingChange>,
        continueLists: Boolean,
        capitalizeSentences: Boolean,
        override: TypingOverride,
    ): TypingPlan? {
        val s0 = min(selectionStart, selectionEnd)
        val s1 = max(selectionStart, selectionEnd)
        when (val edit = classify(changes, s0, s1)) {
            is TypingEdit.Enter -> {
                val e = if (continueLists) SmartTyping.enter(text, edit.start, edit.end) else null
                if (e == null) {
                    override.edited(text, edit.start, edit.end, "\n", s0, s1)
                    return null
                }
                // The machine is told the edit that actually lands — `enter`
                // may take a marker back out or put three units in — not the
                // platform's plain newline.
                override.edited(text, e.location, e.location + e.length, e.replacement, s0, s1)
                return TypingPlan.Enter(e)
            }
            is TypingEdit.Word -> {
                if (!capitalizeSentences) {
                    override.edited(text, edit.start, edit.end, edit.inserted, s0, s1)
                    return null
                }
                if (override.insertsAsTyped(text, edit.start, edit.end, edit.inserted, s0, s1, edit.firstScalar)) {
                    return null
                }
                val result = SmartTyping.capitalize(text, edit.start, edit.end, edit.first)
                if (result == null) {
                    // An IME re-send over the tracked capital that the rules
                    // now refuse (the line became a table row, say): the
                    // capital is gone from the text and the machine learns it
                    // from the text, the way it learns an Undo.
                    if (override.capital == edit.start) {
                        override.reconcile(text.substring(0, edit.start) + edit.inserted + text.substring(edit.end))
                    }
                    return null
                }
                override.produced(edit.start, result.codePointAt(0))
                return TypingPlan.Capital(edit.start, edit.first.length, result)
            }
            null -> {
                override.edited(text, changes, s0, s1)
                return null
            }
        }
    }
}

/**
 * §3.4, the override — the tracked-capital state machine, pure.
 *
 * The adapter tracks the LAST capital md produced: [capital] is `p`, the
 * UTF-16 offset of that one scalar ([capitalScalar]), until it is removed.
 * Every edit to the text updates the tracking — an edit whose range lies
 * before `p` shifts `p` by the edit's length delta; an edit that REMOVES the
 * capital scalar (a deletion or a replacement whose range covers `p`, unless
 * the inserted text puts that same scalar back at the same offset) forgets it
 * and ARMS the override at the edit's start offset `q` ([armedAt]); an edit
 * after `p` leaves it alone; producing a new capital replaces the tracked one
 * (the old one is forgotten, not armed). While armed at `q`: a word insertion
 * that starts exactly at `q` is inserted as typed; any insertion that starts
 * elsewhere clears the override; deletions never clear it (a deletion before
 * `q` shifts `q`, one covering `q` moves `q` to its start); an external
 * replacement (open, revert, a book article) clears both. That covers
 * backspace-and-retype (`M` → backspace → `m`), select-and-retype and Undo,
 * which is how `md`, `iOS` and `npm` survive a sentence start without
 * autocorrect.
 *
 * Undo bypasses the hooks: Compose applies it with no transformation, and on
 * Android it removes the letter (the capital is atomic with the keystroke,
 * §3.5). The machine learns it from the text — [reconcile], run before every
 * edit, finds the capital gone from `p` and treats that as the removal it was:
 * armed at `p`.
 *
 * Independently, a single lowercase letter typed over a one-scalar selection
 * whose scalar is that letter's upper is inserted as typed — and, having
 * removed a capital, arms the override at that offset.
 *
 * Three Android readings, all forced by IME composition — Gboard re-sends the
 * whole word over the same region on every key ("m", "md", "md "):
 *
 *  - While armed at `q`, the override stays armed as long as the IME keeps
 *    replacing a composing region that starts at `q` with a word (each
 *    re-send is inserted as typed), and clears when an insertion starts
 *    elsewhere — or when what lands at `q` is not a word insertion (a paste,
 *    a digit, a Return), which no IME re-send is. Clearing on first use would
 *    re-capitalize the very next re-send; staying armed through a paste
 *    would make a letter later inserted before it lowercase.
 *  - A re-send whose range starts at the tracked capital `p`, with nothing
 *    selected, and whose text begins with the lowercase form of that capital
 *    ("md" over the "M" this machine just recorded) is the IME's echo, not a
 *    removal: `capitalize` re-applies the capital (idempotent) and `p` stays.
 *    A change that empties the region, or replaces it with text that no
 *    longer starts with that letter, removes the capital. The writer's retype
 *    is told apart by the selection: a letter typed over a selection that
 *    starts at `p` is a replacement, never an echo.
 *  - The tracked capital reads every change by its **core** — what is left
 *    after the common prefix and suffix of the replaced and the inserted
 *    text are stripped — because a keyboard that re-composes the word the
 *    caret was placed at (AOSP LatinIME does; Gboard does not) sends the
 *    whole word over the whole region: an "a" typed before "Md" arrives as
 *    "aMd" over `[p, p + 2)`, which as sent covers `p` but is an insertion of
 *    "a" at `p`, and shifts it (consequence 5). The armed offset reads the
 *    change as sent: `q` is the region's start.
 */
internal class TypingOverride {

    /** `p`: the offset of the last capital md produced, or −1 once it was removed (or never was). */
    var capital: Int = -1
        private set

    /** That capital, as a scalar (−1 with no capital tracked). */
    var capitalScalar: Int = -1
        private set

    /** `q`: the offset the override is armed at, or −1 when it is not. */
    var armedAt: Int = -1
        private set

    val isArmed: Boolean get() = armedAt >= 0

    /** An external replacement, or the setting turned off: nothing tracked, nothing armed. */
    fun clear() {
        capital = -1
        capitalScalar = -1
        armedAt = -1
    }

    /**
     * `capitalize` produced (or, under an IME echo, re-applied) the scalar
     * [upper] at [at]: it replaces the tracked capital; the old one is
     * forgotten, not armed. Nothing stays armed either — the capital md just
     * put there is the one to delete.
     */
    fun produced(at: Int, upper: Int) {
        capital = at
        capitalScalar = upper
        armedAt = -1
    }

    /**
     * The text as it is now, before an edit: if the tracked capital is no
     * longer at `p` — Undo took it, with no hook run — that was its removal,
     * and the override is armed at `p`.
     */
    fun reconcile(text: CharSequence) {
        if (capital < 0) return
        val len = Character.charCount(capitalScalar)
        if (capital + len <= text.length && Character.codePointAt(text, capital) == capitalScalar) return
        armedAt = min(capital, text.length)
        capital = -1
        capitalScalar = -1
    }

    /** One edit: `[start, end)` of [text] (the text before it) replaced by [inserted]. */
    fun edited(text: CharSequence, start: Int, end: Int, inserted: String, selectionStart: Int, selectionEnd: Int) {
        reconcile(text)
        track(text, start, end, inserted, selectionStart, selectionEnd, isWord = false)
    }

    /**
     * A whole change set, every range in the coordinates of [text], the text
     * before it. Walked last to first, so each change is applied while the
     * ones before it — which alone can move `p` and `q` — are still pending.
     */
    fun edited(text: CharSequence, changes: List<TypingChange>, selectionStart: Int, selectionEnd: Int) {
        reconcile(text)
        val ordered = if (changes.size > 1) changes.sortedByDescending { it.originalStart } else changes
        for (c in ordered) {
            track(text, c.originalStart, c.originalEnd, c.inserted, selectionStart, selectionEnd, isWord = false)
        }
    }

    /**
     * Decide a word insertion — [inserted], whose first scalar is [first], a
     * Lowercase letter — replacing `[start, end)` of [text], the text before
     * the edit, typed while the selection was `[selectionStart, selectionEnd)`
     * (either order). The edit is tracked first, so a retype over the capital
     * arms the override and is then judged by it. Returns true when the
     * letter is to be inserted **as typed** and false when `capitalize`
     * decides.
     */
    fun insertsAsTyped(
        text: CharSequence,
        start: Int,
        end: Int,
        inserted: String,
        selectionStart: Int,
        selectionEnd: Int,
        first: Int,
    ): Boolean {
        val s0 = min(selectionStart, selectionEnd)
        val s1 = max(selectionStart, selectionEnd)
        // The independent rule, read off the text before the edit: one
        // lowercase letter over a one-scalar selection holding its upper.
        var lowerOverItsUpper = false
        if (inserted.length == Character.charCount(first) && s0 == start && s1 == end && end > start && start < text.length) {
            val selected = Character.codePointAt(text, start)
            lowerOverItsUpper = end - start == Character.charCount(selected) && selected != first &&
                Character.toUpperCase(first) == selected
        }
        reconcile(text)
        track(text, start, end, inserted, s0, s1, isWord = true)
        if (lowerOverItsUpper) {
            armedAt = start
            return true
        }
        return armedAt >= 0 && start == armedAt
    }

    /**
     * One change — `[start, end)` of [text] replaced by [inserted]; [isWord]
     * iff it is a word insertion (§3.3) — applied to `q` and to `p`.
     *
     * `q` reads the change **as sent**: the Android exception is about the
     * composing region the IME keeps re-sending, and its start is `q`
     * whatever the re-send has in common with what it replaces. Only a word
     * insertion at `q` keeps the override armed — a digit, a paste, a Return
     * at `q` clears it as an insertion elsewhere does, or a stale `q` would
     * make the letter later inserted there lowercase.
     *
     * `p` reads the change's **core**: the common prefix and suffix of the
     * replaced text and the inserted text are stripped first (never splitting
     * a surrogate pair), because an IME that re-composes the word the caret
     * was placed at sends the whole word over the whole region — "aMd" over
     * "Md" for an "a" typed before the capital — and taken as sent that
     * covers `p` and reads as a removal, where it is an insertion of "a" at
     * `p` that shifts it (§3.4's consequence 5 under composition). The IME's
     * echo ("md" over "M") shares nothing and is judged as before.
     */
    private fun track(
        text: CharSequence,
        start: Int,
        end: Int,
        inserted: String,
        selectionStart: Int,
        selectionEnd: Int,
        isWord: Boolean,
    ) {
        // The armed offset, off the change as sent.
        if (armedAt >= 0) {
            if (inserted.isEmpty()) {
                if (end <= armedAt) armedAt -= end - start
                else if (start < armedAt) armedAt = start
            } else if (start != armedAt || !isWord) {
                armedAt = -1
            }
        }
        // The tracked capital, off the change's core.
        if (capital < 0) return
        val len = Character.charCount(capitalScalar)
        val oldEnd = min(end, text.length)
        val oldStart = min(start, oldEnd)
        var pre = 0
        val maxPre = min(oldEnd - oldStart, inserted.length)
        while (pre < maxPre && text[oldStart + pre] == inserted[pre]) pre++
        if (pre > 0 && Character.isHighSurrogate(text[oldStart + pre - 1])) pre--
        var suf = 0
        val maxSuf = min(oldEnd - oldStart - pre, inserted.length - pre)
        while (suf < maxSuf && text[oldEnd - 1 - suf] == inserted[inserted.length - 1 - suf]) suf++
        if (suf > 0 && Character.isLowSurrogate(text[oldEnd - suf])) suf--
        val coreStart = oldStart + pre
        val coreEnd = oldEnd - suf
        val core = inserted.substring(pre, inserted.length - suf)
        if (coreEnd <= capital) {
            capital += core.length - (coreEnd - coreStart)
        } else if (coreStart < capital + len) {
            val rel = capital - coreStart
            val putBack = rel >= 0 && rel + len <= core.length &&
                Character.codePointAt(core, rel) == capitalScalar
            val echo = coreStart == capital && core.isNotEmpty() && selectionStart == selectionEnd &&
                Character.toUpperCase(core.codePointAt(0)) == capitalScalar
            if (!putBack && !echo) {
                capital = -1
                capitalScalar = -1
                armedAt = coreStart
            }
        }
    }
}

/**
 * The two typing settings of §3.1 — `md.continueLists` and
 * `md.capitalizeSentences`, both on by default — remembered app-wide in the
 * "view" SharedPreferences file the per-file view-mode memory already lives
 * in (no second store), and exposed as Compose state so the overflow menu's
 * checkboxes and [SmartTypingTransformation] read the same value: the
 * transformation reads it at every keystroke, so a toggle takes effect on the
 * next key in an open editor. The same class-holding-mutableStateOf shape as
 * PageSizeState.
 */
internal class TypingSettings(context: Context) {

    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Return continues lists, quotes and tables, and ends an empty item. */
    var continueLists: Boolean by mutableStateOf(prefs.getBoolean(CONTINUE_LISTS_KEY, true))
        private set

    /** The first letter of a line and of a sentence is capitalized. */
    var capitalizeSentences: Boolean by mutableStateOf(prefs.getBoolean(CAPITALIZE_SENTENCES_KEY, true))
        private set

    fun chooseContinueLists(on: Boolean) {
        continueLists = on
        prefs.edit { putBoolean(CONTINUE_LISTS_KEY, on) }
    }

    fun chooseCapitalizeSentences(on: Boolean) {
        capitalizeSentences = on
        prefs.edit { putBoolean(CAPITALIZE_SENTENCES_KEY, on) }
    }

    companion object {
        /** The preferences keys, verbatim on every platform (§3.1). */
        const val CONTINUE_LISTS_KEY = "md.continueLists"
        const val CAPITALIZE_SENTENCES_KEY = "md.capitalizeSentences"

        /** The file: ViewModeStore's. */
        const val PREFS = "view"
    }
}
