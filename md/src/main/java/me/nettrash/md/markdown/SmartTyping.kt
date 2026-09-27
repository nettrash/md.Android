/*
 * SmartTyping.kt
 * md (Android)
 *
 * Smart typing for the editor: what Return does inside a list, a quote or a
 * table, and which typed letter becomes a capital. Two pure functions —
 *
 *     enter(text, selectionStart, selectionEnd)            -> EnterEdit?
 *     capitalize(text, selectionStart, selectionEnd, typed) -> String?
 *
 * — one shared specification (SmartTyping SPEC.md v1, whose section numbers
 * the comments below cite) and one cross-port oracle, `typing-vectors.json`,
 * exactly as `Plot` has `plot-vectors.json`. The Swift (md, md.macOS) and C#
 * (md.win) siblings are the same code rule for rule, and every one of the
 * 1350 vectors is asserted by `SmartTypingTest` on the JVM and by
 * `SmartTypingVectorsInstrumentedTest` on a device, because ART's Unicode
 * tables are not the desktop JVM's.
 *
 * THE RULES THIS FILE KEEPS
 * -------------------------
 * Every offset is a UTF-16 unit — Kotlin's `String.length`, `NSString.length`,
 * C#'s `string.Length` — and every class is the Unicode general category of the
 * *scalar*, read with `Character.getType(Int)` after decoding surrogate pairs
 * with `Character.toCodePoint`. Nothing here calls `isWhitespace`, `trim()`,
 * `isLetter()`, `uppercaseChar()` or a regular expression: the family has
 * already been bitten by "whitespace" having four answers across Rust, Kotlin,
 * Swift and Foundation (§0.3 names the nineteen scalars that are whitespace
 * here, and no other), and `java.util.regex`'s `\s` and `\w` are not ICU's
 * (§0.8). Case is `Character.toUpperCase(Int)`, the simple one-to-one mapping,
 * declared undefined for the scalars where the runtimes disagree (§0.7).
 *
 * This file is pure and synchronous, reaches for no `android.*` API and no
 * Compose, so the unit-test classpath — JUnit and nothing else — can run the
 * whole oracle over it; the same rule `Plot`, `MarkdownHtml` and `LaTeXExport`
 * keep.
 *
 * ON LINES
 * --------
 * The reference the vectors were generated from materialises a line array;
 * §0.14 asks a port not to. This port keeps the reference's shape — every rule
 * reads `lines.text(k)` — but the table of line offsets is two `IntArray`s
 * filled in one forward pass, and a line's text is cut out only when a rule
 * actually reads it, so the cost stays the one forward pass to the caret's
 * line that §0.14 budgets for plus the run around the caret. The three reads
 * below the caret are the ones §0.14 names: the front-matter closer, the
 * delimiter row under a header, and "does the table continue" on line c+1.
 */

package me.nettrash.md.markdown

import kotlin.math.max
import kotlin.math.min

object SmartTyping {

    /**
     * §0.9: the one edit `enter` asks the platform for. Replace
     * `[location, location + length)` of the *original* text with
     * [replacement] and put a collapsed caret at [caret], an offset into the
     * text after the edit — as one undoable step.
     */
    data class EnterEdit(val location: Int, val length: Int, val replacement: String, val caret: Int)

    // -----------------------------------------------------------------------
    // §0.1 units and scalars
    // -----------------------------------------------------------------------

    private const val SP = 0x20
    private const val TAB = 0x09
    private const val CR = 0x0d
    private const val LF = 0x0a

    /**
     * The unit at [i], or −1 past either end. The reference reads
     * `charCodeAt` there and gets NaN, which fails every comparison; −1 does
     * the same here, so each `s.u(i) == X` below ports one to one.
     */
    private fun String.u(i: Int): Int = if (i in 0 until length) this[i].code else -1

    private fun isHigh(u: Int): Boolean = u in 0xd800..0xdbff
    private fun isLow(u: Int): Boolean = u in 0xdc00..0xdfff

    /** The scalar starting at unit index [i] (a lone surrogate is its own scalar); [scalarLen] is its width. */
    private fun scalarAt(s: String, i: Int): Int {
        val u = s[i].code
        if (isHigh(u) && i + 1 < s.length && isLow(s[i + 1].code)) return Character.toCodePoint(s[i], s[i + 1])
        return u
    }

    private fun scalarLen(cp: Int): Int = if (cp > 0xffff) 2 else 1

    /** The scalar ending just before unit index [i], or −1 at the start. */
    private fun scalarBefore(s: String, i: Int): Int {
        if (i <= 0) return -1
        val u = s[i - 1].code
        if (isLow(u) && i - 2 >= 0 && isHigh(s[i - 2].code)) return Character.toCodePoint(s[i - 2], s[i - 1])
        return u
    }

    private fun scalarsOf(s: String): IntArray {
        val out = IntArray(s.length)
        var n = 0
        var i = 0
        while (i < s.length) {
            val cp = scalarAt(s, i)
            out[n++] = cp
            i += scalarLen(cp)
        }
        return if (n == out.size) out else out.copyOf(n)
    }

    private fun scalarToString(cp: Int): String = StringBuilder(2).appendCodePoint(cp).toString()

    // -----------------------------------------------------------------------
    // §0.3 WS19, blank, trim
    // -----------------------------------------------------------------------

    private fun isWS19(u: Int): Boolean =
        u == 0x09 || u == 0x20 || u == 0xa0 || u == 0x1680 ||
            (u in 0x2000..0x200a) || u == 0x200b || u == 0x202f || u == 0x205f || u == 0x3000

    private fun isBlank(s: String): Boolean {
        for (i in s.indices) if (!isWS19(s[i].code)) return false
        return true
    }

    private fun trim(s: String): String {
        var a = 0
        var b = s.length
        while (a < b && isWS19(s[a].code)) a++
        while (b > a && isWS19(s[b - 1].code)) b--
        return if (a == 0 && b == s.length) s else s.substring(a, b)
    }

    /** `trim(text[start, end)) == literal`, without cutting the line out (the front-matter closer search, §0.14). */
    private fun trimmedEquals(t: String, start: Int, end: Int, literal: String): Boolean {
        var a = start
        var b = end
        while (a < b && isWS19(t[a].code)) a++
        while (b > a && isWS19(t[b - 1].code)) b--
        if (b - a != literal.length) return false
        for (k in literal.indices) if (t[a + k] != literal[k]) return false
        return true
    }

    // -----------------------------------------------------------------------
    // §0.4 character classes (general category of the scalar)
    // -----------------------------------------------------------------------

    private fun isLetter(cp: Int): Boolean {
        if (cp < 0) return false
        return when (Character.getType(cp)) {
            Character.UPPERCASE_LETTER.toInt(), Character.LOWERCASE_LETTER.toInt(),
            Character.TITLECASE_LETTER.toInt(), Character.MODIFIER_LETTER.toInt(),
            Character.OTHER_LETTER.toInt(),
            -> true
            else -> false
        }
    }

    private fun isLowercase(cp: Int): Boolean = cp >= 0 && Character.getType(cp) == Character.LOWERCASE_LETTER.toInt()
    private fun isUppercase(cp: Int): Boolean = cp >= 0 && Character.getType(cp) == Character.UPPERCASE_LETTER.toInt()

    private fun isMark(cp: Int): Boolean {
        if (cp < 0) return false
        return when (Character.getType(cp)) {
            Character.NON_SPACING_MARK.toInt(), Character.COMBINING_SPACING_MARK.toInt(),
            Character.ENCLOSING_MARK.toInt(),
            -> true
            else -> false
        }
    }

    private fun isSymbolSoSk(cp: Int): Boolean {
        if (cp < 0) return false
        val t = Character.getType(cp)
        return t == Character.OTHER_SYMBOL.toInt() || t == Character.MODIFIER_SYMBOL.toInt()
    }

    private fun isDigit(u: Int): Boolean = u in 0x30..0x39

    // -----------------------------------------------------------------------
    // §0.7 case mapping — simple, one-to-one, locale-independent
    // -----------------------------------------------------------------------

    /**
     * `upper(s)`, or −1 when undefined (§0.7). `Character.toUpperCase(Int)` is
     * the simple mapping and always one scalar; the ranges excluded here are
     * the ones where it and the full mapping (Swift, JS) would disagree.
     */
    private fun upper(cp: Int): Int {
        if ((cp in 0x10d0..0x10ff) || (cp in 0x2d00..0x2d2f)) return -1 // Georgian
        // Greek letters with ypogegrammeni: the simple mapping (Java, .NET) is one
        // titlecase scalar, the full mapping (Swift, JS) is two — undefined, so
        // all ports agree (§0.7).
        if ((cp in 0x1f80..0x1faf) || cp == 0x1fb3 || cp == 0x1fc3 || cp == 0x1ff3) return -1
        if (cp == 0xb5) return -1 // MICRO SIGN: never GREEK CAPITAL MU in a unit prefix (§0.7)
        if (cp in 0xd800..0xdfff) return -1
        val mapped = Character.toUpperCase(cp)
        if (mapped == cp) return -1
        return mapped
    }

    // -----------------------------------------------------------------------
    // §0.2 lines
    // -----------------------------------------------------------------------

    /**
     * The line table of one text: `starts[i]` / `ends[i]` are unit offsets,
     * `ends` excludes the terminator (§0.2). Built in one forward pass; a
     * line's text is cut out on first use and kept.
     */
    private class Lines(val t: String) {
        val count: Int
        val starts: IntArray
        val ends: IntArray
        private val texts: Array<String?>

        init {
            var n = 1
            var i = 0
            while (i < t.length) {
                val u = t[i].code
                if (u == CR) {
                    n++
                    i += if (i + 1 < t.length && t[i + 1].code == LF) 2 else 1
                } else if (u == LF) {
                    n++
                    i += 1
                } else {
                    i += 1
                }
            }
            count = n
            starts = IntArray(n)
            ends = IntArray(n)
            texts = arrayOfNulls(n)
            var k = 0
            var start = 0
            i = 0
            while (i < t.length) {
                val u = t[i].code
                if (u == CR) {
                    starts[k] = start
                    ends[k] = i
                    k++
                    i += if (i + 1 < t.length && t[i + 1].code == LF) 2 else 1
                    start = i
                } else if (u == LF) {
                    starts[k] = start
                    ends[k] = i
                    k++
                    i += 1
                    start = i
                } else {
                    i += 1
                }
            }
            starts[k] = start
            ends[k] = t.length
        }

        fun text(i: Int): String = texts[i] ?: t.substring(starts[i], ends[i]).also { texts[i] = it }

        /** Index of the line containing [caret], or −1 when the caret sits strictly between the CR and the LF of one terminator (§0.2). */
        fun lineOf(caret: Int): Int {
            var lo = 0
            var hi = count - 1
            while (lo < hi) {
                val mid = (lo + hi + 1) ushr 1
                if (starts[mid] <= caret) lo = mid else hi = mid - 1
            }
            return if (caret <= ends[lo]) lo else -1
        }

        /** `trim(lines[k])` is one of [closers] — the front-matter closer test, on the raw text. */
        fun isCloser(k: Int, closers: List<String>): Boolean {
            for (closer in closers) if (trimmedEquals(t, starts[k], ends[k], closer)) return true
            return false
        }
    }

    // -----------------------------------------------------------------------
    // §0.6 columns
    // -----------------------------------------------------------------------

    private fun columns(run: String): Int {
        var col = 0
        for (i in run.indices) {
            if (run[i].code == TAB) col += 4 - (col % 4) else col += 1
        }
        return col
    }

    // -----------------------------------------------------------------------
    // §0.12 block tests on one line
    // -----------------------------------------------------------------------

    private fun isThematicBreak(s: String): Boolean {
        var n = 0
        var ch = -1
        for (i in s.indices) {
            val u = s[i].code
            if (u == SP || u == TAB) continue
            if (u != 0x2d && u != 0x2a && u != 0x5f) return false // - * _
            if (ch == -1) ch = u else if (u != ch) return false
            n++
        }
        return n >= 3
    }

    private fun isATXHeading(s: String): Boolean {
        var i = 0
        while (i < s.length && s[i].code == SP) i++
        var n = 0
        while (i < s.length && s[i].code == 0x23) { n++; i++ }
        if (n < 1 || n > 6) return false
        return i == s.length || s[i].code == SP
    }

    private fun isPageBreak(s: String): Boolean {
        val t = trim(s)
        return t == "\\newpage" || t == "\\pagebreak"
    }

    private fun isFootnoteIdUnit(u: Int): Boolean =
        (u in 0x41..0x5a) || (u in 0x61..0x7a) || isDigit(u) || u == 0x2d || u == 0x5f

    /** Length of the footnote prefix `[^id]:` + WS19* measured on [t] from [from] (positioned at the `[`), or −1. */
    private fun footnotePrefixLength(t: String, from: Int): Int {
        if (!(t.u(from) == 0x5b && t.u(from + 1) == 0x5e)) return -1
        var i = from + 2
        val idStart = i
        while (i < t.length && isFootnoteIdUnit(t[i].code)) i++
        if (i == idStart) return -1
        if (!(t.u(i) == 0x5d && t.u(i + 1) == 0x3a)) return -1
        i += 2
        while (i < t.length && isWS19(t[i].code)) i++
        return i - from
    }

    private fun isFootnoteDefinition(s: String): Boolean = footnotePrefixLength(trim(s), 0) >= 0

    private fun isQuoteLine(s: String): Boolean {
        var i = 0
        while (i < s.length && s[i].code == SP) i++
        return s.u(i) == 0x3e
    }

    private fun isCommentStart(s: String): Boolean {
        var i = 0
        while (i < s.length && s[i].code == SP) i++
        return s.startsWith("<!--", i)
    }

    // -----------------------------------------------------------------------
    // §0.11 prefix grammar
    // -----------------------------------------------------------------------

    private class Marker(val bullet: Char?, val number: Int, val delimiter: Char, val text: String)

    private class Prefix(
        val indent0: String,
        val quotes: String,             // the raw quote groups, verbatim
        val quoteGroupStarts: IntArray, // offsets (within `quotes`) where each group starts (§1.3c)
        val depth: Int,
        val indent1: String,
        val innerStart: Int,            // after indent0 + quotes (the "inner text", §0.13)
        val marker: Marker?,
        val box: String?,               // "[ ]" | "[x]" | "[X]"
        val boxAtEnd: Boolean,          // the content is exactly a box (`- [ ]`): content for the caret, a box for §1.3a / §1.4
        val prefixEnd: Int,             // where content starts
        val listIndentCols: Int,        // §0.11 list indent in columns
        val listLevel: Int,             // §0.11 list level = floor(columns / 2), the parser's `indent / 2`
    )

    /**
     * Parse the §0.11 prefix of one line. A marker or box counts only when
     * followed by SP: one at the line end is content for both functions (the
     * parser renders a bare `-` as an empty item, but Enter must not delete a
     * `2020.` line and a typed letter would land directly after the unit —
     * `-` + `a` is `-a`; §6 records the difference).
     */
    private fun parsePrefix(s: String): Prefix {
        var i = 0
        // indent0 := run of SP / TAB
        while (i < s.length && (s[i].code == SP || s[i].code == TAB)) i++
        val indent0 = s.substring(0, i)
        // quotes := ( run of SP, ">", at most one SP )*  — only when indent0 has no
        // TAB (the parser's `isQuote` drops SP only: `\t> x` is a paragraph).
        val quotesStart = i
        var groupStarts = IntArray(4)
        var depth = 0
        if (indent0.indexOf('\t') < 0) {
            while (true) {
                var j = i
                while (j < s.length && s[j].code == SP) j++
                if (j < s.length && s[j].code == 0x3e) {
                    if (depth == groupStarts.size) groupStarts = groupStarts.copyOf(depth * 2)
                    groupStarts[depth] = i - quotesStart
                    j++
                    if (j < s.length && s[j].code == SP) j++
                    i = j
                    depth++
                } else {
                    break
                }
            }
        }
        val quotes = s.substring(quotesStart, i)
        // indent1 := run of SP / TAB, only after quotes
        var indent1 = ""
        if (depth > 0) {
            val a = i
            while (i < s.length && (s[i].code == SP || s[i].code == TAB)) i++
            indent1 = s.substring(a, i)
        }
        val innerStart = quotesStart + quotes.length // inner text = line minus indent0 and quotes (§0.13)
        val listIndentCols = columns(if (depth > 0) indent1 else indent0)
        val afterIndent = i

        var marker: Marker? = null
        var box: String? = null
        var boxAtEnd = false
        var prefixEnd = afterIndent

        // marker is absent when the inner text is a thematic break (tested first, as the parser does)
        if (!isThematicBreak(s.substring(innerStart))) {
            var m = afterIndent
            var bullet: Char? = null
            var num = 0
            var delim = ' '
            var ok = false
            val u = s.u(m)
            if (u == 0x2d || u == 0x2a || u == 0x2b) {
                bullet = s[m]; m++; ok = true
            } else if (isDigit(u)) {
                var d = m
                while (d < s.length && isDigit(s[d].code)) d++
                val digits = d - m
                if (digits <= 9 && (s.u(d) == 0x2e || s.u(d) == 0x29)) {
                    num = s.substring(m, d).toInt(); delim = s[d]; m = d + 1; ok = true
                }
            }
            if (ok) {
                // followed by SP (a marker at the line end is content, §0.11)
                val followedBySP = m < s.length && s[m].code == SP
                if (followedBySP) {
                    marker = Marker(bullet, num, delim, s.substring(afterIndent, m))
                    // the marker absorbs the run of SP after it (the parser drops every SP)
                    while (m < s.length && s[m].code == SP) m++
                    prefixEnd = m
                    // box := "[ ]" | "[x]" | "[X]", followed by SP (same refinement)
                    val b = if (m + 3 <= s.length) s.substring(m, m + 3) else ""
                    if (b == "[ ]" || b == "[x]" || b == "[X]") {
                        var e = m + 3
                        val bSP = e < s.length && s[e].code == SP
                        if (bSP) {
                            box = b
                            while (e < s.length && s[e].code == SP) e++
                            prefixEnd = e
                        } else if (e == s.length) {
                            // §0.11: a box that is the whole content (`- [ ]`, `1. [x]`) is
                            // content for the caret test (§1.2) and for §1.3 (the item is not
                            // empty), but the line HAS a box for §1.3a and §1.4: the parser
                            // renders an empty task, and Enter continues the checklist
                            // (`enter-empty-task-no-space`).
                            boxAtEnd = true
                        }
                    }
                }
            }
        }
        return Prefix(
            indent0, quotes, groupStarts.copyOf(depth), depth, indent1, innerStart, marker, box, boxAtEnd, prefixEnd,
            listIndentCols, listIndentCols / 2,
        )
    }

    // -----------------------------------------------------------------------
    // §0.13 table grammar
    // -----------------------------------------------------------------------

    private fun splitCells(row: String): List<String> {
        var r = trim(row)
        if (r.isNotEmpty() && r[0].code == 0x7c) r = r.substring(1)
        if (r.isNotEmpty() && r[r.length - 1].code == 0x7c) r = r.substring(0, r.length - 1)
        val cells = ArrayList<String>()
        val cur = StringBuilder()
        var escaped = false
        for (i in r.indices) {
            val ch = r[i]
            if (escaped) {
                if (ch != '|') cur.append('\\')
                cur.append(ch)
                escaped = false
            } else if (ch == '\\') {
                escaped = true
            } else if (ch == '|') {
                cells.add(trim(cur.toString())); cur.setLength(0)
            } else {
                cur.append(ch)
            }
        }
        if (escaped) cur.append('\\')
        cells.add(trim(cur.toString()))
        return cells
    }

    private fun innerMarkerExists(inner: String): Boolean {
        // the inner text has no quotes by construction; its prefix's marker
        return parsePrefix(inner).marker != null
    }

    private fun isTablePair(header: String, delimiter: String): Boolean {
        if (header.indexOf('|') < 0) return false
        if (innerMarkerExists(header)) return false
        if (isATXHeading(header) || isFootnoteDefinition(header) || isCommentStart(header)) return false
        if (trim(delimiter).indexOf('-') < 0) return false
        run {
            // §0.13: a delimiter line with a `marker` is excluded only when its
            // content contains no `|`: `- ` and `- x` are list items to a writer,
            // while `- | -` and `- |` are delimiter rows (the pipe says table, and
            // the parser tests tables before lists) — §5 table-delimiter-list-marker
            val dp = parsePrefix(delimiter)
            if (dp.marker != null && delimiter.indexOf('|', dp.prefixEnd) < 0) return false
        }
        val dCells = splitCells(delimiter)
        for (c in dCells) {
            if (c.isEmpty()) return false
            var dash = false
            for (i in c.indices) {
                val u = c[i].code
                if (u == 0x2d) dash = true else if (u != 0x3a) return false
            }
            if (!dash) return false
        }
        return splitCells(header).size == dCells.size
    }

    // -----------------------------------------------------------------------
    // §0.10 state scan
    // -----------------------------------------------------------------------

    private class Scan(
        val code: BooleanArray,    // per line ≤ c: front matter / fence line / comment line
        val special: BooleanArray, // per line ≤ c: blank, code (as above) or ATX heading
        val caretCode: Boolean,    // the caret's line is code (incl. tentative front matter)
    )

    private fun leadingSPCount(s: String): Int {
        var i = 0
        while (i < s.length && s[i].code == SP) i++
        return i
    }

    private class Fence(val ch: Char, val n: Int)

    /** Fence opener test (§0.10): ≤ 3 leading SP, run of ` or ~ of length ≥ 3, no backtick in the rest of a backtick fence. */
    private fun fenceOpener(s: String): Fence? {
        val i = leadingSPCount(s)
        if (i > 3) return null
        if (i >= s.length) return null
        val ch = s[i]
        if (ch != '`' && ch != '~') return null
        var j = i
        while (j < s.length && s[j] == ch) j++
        val n = j - i
        if (n < 3) return null
        if (ch == '`' && s.indexOf('`', j) >= 0) return null
        return Fence(ch, n)
    }

    private fun fenceCloses(s: String, ch: Char, n: Int): Boolean {
        val i = leadingSPCount(s)
        var j = i
        while (j < s.length && s[j] == ch) j++
        if (j - i < n) return false
        return isBlank(s.substring(j))
    }

    private val YAML_CLOSERS = listOf("---", "...")
    private val TOML_CLOSERS = listOf("+++")

    private fun scanState(lines: Lines, c: Int, forEnter: Boolean): Scan {
        val code = BooleanArray(c + 1)
        val special = BooleanArray(c + 1)
        var caretCode = false

        // --- front matter (all the parser's guards)
        var fmEnd = -1
        val opener = trim(lines.text(0))
        var separator = ' '
        var closers: List<String> = emptyList()
        if (opener == "---") { closers = YAML_CLOSERS; separator = ':' } else if (opener == "+++") { closers = TOML_CLOSERS; separator = '=' }
        if (closers.isNotEmpty()) {
            var holds = lines.count > 1 && !isBlank(lines.text(1))
            var close = -1
            if (holds) {
                for (k in 1 until lines.count) if (lines.isCloser(k, closers)) { close = k; break }
                if (close < 0) holds = false
            }
            if (holds) {
                var field = false
                for (k in 1 until close) {
                    val t = trim(lines.text(k))
                    if (t.isEmpty() || t.startsWith('#') || t.startsWith('-')) continue
                    val sep = t.indexOf(separator)
                    if (sep < 0) continue
                    if (isBlank(t.substring(0, sep))) continue
                    field = true; break
                }
                if (!field) holds = false
            }
            if (holds) {
                fmEnd = close
            } else if (c >= 1) {
                // tentative front matter (§0.10): a block that is already closed above
                // the caret is complete and rejected — prose. Otherwise the caret's
                // line is code iff every line of the tested range is non-blank and
                // field-like. The range is 1 ..< c for capitalize (the caret's line
                // is being typed) and 1 … c for enter (the line is complete).
                var closed = false
                for (k in 1 until c) if (lines.isCloser(k, closers)) { closed = true; break }
                if (!closed) {
                    val last = if (forEnter) c else c - 1
                    var tentative = true
                    var fieldSeen = false
                    for (k in 1..last) {
                        val t = trim(lines.text(k))
                        if (t.isEmpty()) { tentative = false; break }
                        val sep = t.indexOf(separator)
                        val hash = t.startsWith('#')
                        val dash = t.startsWith('-')
                        // a field mirrors parseFrontMatter's guard: a `#` or `-` line is never
                        // a field (`---⏎- key: v⏎---` renders as rule + list + rule)
                        val isField = !hash && !dash && sep > 0 && !isBlank(t.substring(0, sep))
                        // a `#` comment (YAML and TOML) or a `-` list item (YAML only: TOML has
                        // no `-` lists) counts only under a field line: a list or heading
                        // directly under a bare `---` is Markdown
                        val fieldLike = isField || ((hash || (dash && separator == ':')) && fieldSeen)
                        if (!fieldLike) { tentative = false; break }
                        if (isField) fieldSeen = true
                    }
                    if (tentative) caretCode = true
                }
            }
        }
        for (k in 0..min(fmEnd, c)) { code[k] = true; special[k] = true }
        if (fmEnd >= c) caretCode = true

        // --- fences and comments, at quote depth 0 only
        var state = 0 // 0 normal, 1 fence, 2 comment
        var fenceCh = ' '
        var fenceN = 0
        for (k in fmEnd + 1..c) {
            val s = lines.text(k)
            if (state == 0) {
                val f = fenceOpener(s)
                if (f != null) {
                    state = 1; fenceCh = f.ch; fenceN = f.n; code[k] = true
                } else if (isCommentStart(s)) {
                    code[k] = true
                    state = if (s.indexOf("-->") >= 0) 0 else 2
                }
            } else if (state == 1) {
                code[k] = true
                if (fenceCloses(s, fenceCh, fenceN)) state = 0
            } else {
                code[k] = true
                if (s.indexOf("-->") >= 0) state = 0
            }
            if (code[k] || isBlank(s) || isATXHeading(s)) special[k] = true
        }
        if (code[c]) caretCode = true
        return Scan(code, special, caretCode)
    }

    // -----------------------------------------------------------------------
    // §0.13 tableContext
    // -----------------------------------------------------------------------

    private class TableCtx(val header: Int, val n: Int)

    private fun inner(lines: Lines, i: Int): String {
        val s = lines.text(i)
        return s.substring(parsePrefix(s).innerStart)
    }

    private fun depthOf(lines: Lines, i: Int): Int = parsePrefix(lines.text(i)).depth

    private fun tableContext(lines: Lines, scan: Scan, k: Int): TableCtx? {
        val d = depthOf(lines, k)
        var top = k
        while (top - 1 >= 0 && !isBlank(lines.text(top - 1)) && depthOf(lines, top - 1) == d && !scan.code[top - 1]) top--
        var h = top
        while (h <= k) {
            if (h + 1 >= lines.count || depthOf(lines, h + 1) != d || !isTablePair(inner(lines, h), inner(lines, h + 1))) { h++; continue }
            // the pair at h opens a table; it runs while the lines below contain a
            // pipe. A line without one ends it (the parser's row loop stops there)
            // and is not a header itself, so the search resumes below that line —
            // greedy consumption, never a second pair inside the first table's rows.
            var ended = -1
            for (j in h + 2..k) if (lines.text(j).indexOf('|') < 0) { ended = j; break }
            if (ended < 0) return TableCtx(h, splitCells(inner(lines, h)).size)
            h = ended + 1
        }
        return null
    }

    private fun isPipeLine(s: String): Boolean {
        // §0.13: the inner text (line minus indent0 and quotes), after indent1, starts with `|`
        val p = parsePrefix(s)
        val i = p.indent0.length + p.quotes.length + p.indent1.length
        return s.u(i) == 0x7c
    }

    // -----------------------------------------------------------------------
    // §0.9 inputs, outputs and offsets
    // -----------------------------------------------------------------------

    private class Norm(val start: Int, val end: Int, val t: String, val caret: Int)

    private fun normalize(text: String, selectionStart: Int, selectionEnd: Int): Norm? {
        val start = min(selectionStart, selectionEnd)
        val end = max(selectionStart, selectionEnd)
        if (!(0 <= start && start <= end && end <= text.length)) return null
        fun betweenCRLF(o: Int) = o > 0 && o < text.length && text[o - 1].code == CR && text[o].code == LF
        if (betweenCRLF(start) || betweenCRLF(end)) return null
        // §0.9: an offset strictly between the high and the low half of a surrogate
        // pair would split one scalar in two (a corrupt document, and one Swift's
        // NSString bridge repairs differently from Kotlin and C#)
        fun betweenSurrogates(o: Int) = o > 0 && o < text.length && isHigh(text[o - 1].code) && isLow(text[o].code)
        if (betweenSurrogates(start) || betweenSurrogates(end)) return null
        val t = if (start == end) text else text.substring(0, start) + text.substring(end)
        return Norm(start, end, t, start)
    }

    // -----------------------------------------------------------------------
    // §1 enter
    // -----------------------------------------------------------------------

    /** §1.4 next(n) */
    private fun nextNumber(n: Int): Int = if (n + 1 > 999999999) n else n + 1

    private fun markerPrime(m: Marker, number: Int): String =
        if (m.bullet != null) "${m.bullet} " else "$number${m.delimiter} "

    fun enter(text: String, selectionStart: Int, selectionEnd: Int): EnterEdit? {
        val norm = normalize(text, selectionStart, selectionEnd) ?: return null
        val start = norm.start
        val end = norm.end
        val t = norm.t
        val caret = norm.caret
        val lines = Lines(t)
        val c = lines.lineOf(caret)
        if (c < 0) return null                                    // §0.2
        val scan = scanState(lines, c, true)
        if (scan.caretCode) return null                           // §1.0

        val removed = end - start
        val line = lines.text(c)
        val lineStart = lines.starts[c]

        // §1.1 table row: the selection must lie within one line of `text`
        var withinLine = true
        for (i in start until end) {
            val u = text[i].code
            if (u == CR || u == LF) { withinLine = false; break }
        }
        if (withinLine) {
            val tc = tableContext(lines, scan, c)
            if (tc != null) {
                val p = parsePrefix(line)
                val rowPrefix = p.indent0 + p.quotes + p.indent1
                val row = "\n" + rowPrefix + "|" + "  |".repeat(tc.n)
                val inner = line.substring(p.innerStart)
                // insert the row after the line end `xPrime` of text′ (at or after caret′);
                // with a selection the same edit is one range from `start` that also
                // deletes the selection (§0.9)
                fun insertAfter(xPrime: Int): EnterEdit {
                    val x = xPrime + removed
                    if (removed == 0) return EnterEdit(x, 0, row, x + 1 + rowPrefix.length + 2)
                    val tail = text.substring(end, x)
                    return EnterEdit(start, x - start, tail + row, start + tail.length + 1 + rowPrefix.length + 2)
                }
                if (c == tc.header) {
                    if (caret == lineStart) return null // column 0 of the header: a plain newline pushes the table down
                    return insertAfter(lines.ends[tc.header + 1])
                }
                // §1.1: the table continues below the caret's row iff line c+1 exists,
                // has the same quote depth and contains a `|` unit (the parser's row
                // loop). A blank row ends the table only when it is the LAST row: a
                // blank line in the middle would orphan the rows below it
                // (`enter-table-blank-row-mid-table`).
                val continuesBelow = c + 1 < lines.count && depthOf(lines, c + 1) == p.depth && lines.text(c + 1).indexOf('|') >= 0
                if (removed == 0 && !continuesBelow && splitCells(inner).all { it.isEmpty() }) {
                    // end the table; inside a quote the writer stays in the quote (as §1.3b)
                    val r = if (p.depth > 0) p.indent0 + p.quotes else ""
                    return EnterEdit(lineStart, lines.ends[c] - lineStart, r, lineStart + r.length)
                }
                if (removed == 0 && c >= tc.header + 2 && caret == lineStart) {
                    // column 0 of a body row: the new row goes ABOVE the caret's row, which
                    // moves down intact exactly as a plain newline would push it — the
                    // writer's only "insert a row above" gesture (A.1). The delimiter row
                    // keeps the insert-after branch: a row between header and delimiter
                    // would break the table.
                    val above = rowPrefix + "|" + "  |".repeat(tc.n) + "\n"
                    return EnterEdit(lineStart, 0, above, lineStart + rowPrefix.length + 2)
                }
                return insertAfter(lines.ends[c])
            }
        }

        val p = parsePrefix(line)
        val rel = caret - lineStart
        if (rel < p.prefixEnd) return null                        // §1.2
        val content = line.substring(p.prefixEnd)

        // replaces text′[lineStart, caret′) with r
        fun replaceHead(r: String): EnterEdit =
            EnterEdit(lineStart, (caret - lineStart) + removed, r, lineStart + r.length)

        // §1.3 empty item
        if (isBlank(content) && (p.marker != null || p.depth > 0)) {
            if (p.marker != null) {
                // a. outdent
                val anc = ancestor(lines, scan, c, p)
                if (anc >= 0) {
                    val a = parsePrefix(lines.text(anc))
                    val m = a.marker!!
                    val r = a.indent0 + a.quotes + a.indent1 + markerPrime(m, nextNumber(m.number)) +
                        (if (a.box != null || a.boxAtEnd) "[ ] " else "")
                    return replaceHead(r)
                }
                // b. terminate a list item
                return replaceHead(if (p.depth > 0) p.indent0 + p.quotes else "")
            }
            // c. terminate a quote level
            val last = p.quoteGroupStarts[p.quoteGroupStarts.size - 1]
            val quotesPrime = p.quotes.substring(0, last)
            return replaceHead(if (quotesPrime.isEmpty()) "" else p.indent0 + quotesPrime)
        }

        // §1.4 continue
        if (p.marker != null || p.depth > 0) {
            val mp = if (p.marker != null) markerPrime(p.marker, nextNumber(p.marker.number)) else ""
            val r = "\n" + p.indent0 + p.quotes + p.indent1 + mp + (if (p.box != null || p.boxAtEnd) "[ ] " else "")
            return EnterEdit(start, removed, r, start + r.length)
        }

        return null                                               // §1.5
    }

    /** §1.3a ancestor walk: the ancestor's line index, or −1. */
    private fun ancestor(lines: Lines, scan: Scan, c: Int, p: Prefix): Int {
        for (k in c - 1 downTo 0) {
            val s = lines.text(k)
            if (isBlank(s)) return -1
            val pk = parsePrefix(s)
            if (pk.depth != p.depth) return -1
            if (scan.code[k]) return -1
            if (tableContext(lines, scan, k) != null) return -1
            val inner = s.substring(pk.innerStart)
            if (isBlank(inner)) return -1 // `> ` between quoted items: the renderer sees a blank line
            if (isThematicBreak(inner) || isATXHeading(inner) || isPageBreak(inner)) return -1
            // §1.3a: the ancestor's list LEVEL (floor(columns / 2), the parser's
            // `indent / 2`) is strictly smaller — a 1-SP or 3-SP item renders as a
            // sibling, so Enter on it must not manufacture another sibling
            if (pk.marker != null && pk.listLevel < p.listLevel) return k
        }
        return -1
    }

    // -----------------------------------------------------------------------
    // §2 capitalize
    // -----------------------------------------------------------------------

    // §2.2 openers: * _ ~ " ' ( [ { « » ‹ › “ ” ‘ ’ „ ‚ ¿ ¡ (and `!` only before `[`)
    private val OPENERS: Set<Char> = hashSetOf(
        '*', '_', '~', '"', '\'', '(', '[', '{', '«', '»', '‹', '›',
        '“', '”', '‘', '’', '„', '‚', '¿', '¡',
    )
    // §2.2 closers: ) ] } " ' » « ‹ › ” ’ “ ‘ * _ ~
    private val CLOSERS: Set<Char> = hashSetOf(
        ')', ']', '}', '"', '\'', '»', '«', '‹', '›',
        '”', '’', '“', '‘', '*', '_', '~',
    )
    /** §2.2: the quotation marks among the openers (the colon rule, §2.5 1a). */
    private val QUOTE_OPENERS: Set<Char> = hashSetOf(
        '"', '\'', '«', '»', '‹', '›', '“', '”', '‘', '’', '„', '‚',
    )
    /** §2.2: guillemets, which carry French spacing after them when opening. */
    private val GUILLEMETS: Set<Char> = hashSetOf('«', '‹', '»', '›')

    private fun isTerminator(u: Int): Boolean = u == 0x2e || u == 0x21 || u == 0x3f

    /**
     * §2.3 dash units: em dash, en dash, minus, horizontal bar (Unicode's
     * "quotation dash"), figure dash, the pasted bullets • ‣ · and the section /
     * pilcrow signs § ¶ (Po since Unicode 6.1, leads to a writer).
     */
    private fun isDashUnit(u: Int): Boolean =
        u == 0x2014 || u == 0x2013 || u == 0x2212 || u == 0x2015 || u == 0x2012 ||
            u == 0x2022 || u == 0x2023 || u == 0xb7 || u == 0xa7 || u == 0xb6

    /** §2.3: arrows (U+2190–U+21FF) are leads at the content start only (`→ next step`, the arrow-bullet note style); they are not in the mid-line group. */
    private fun isArrowUnit(u: Int): Boolean = u in 0x2190..0x21ff

    /** §2.3 `dash` lead run: a run of dash units, arrows or U+002D that is not a single hyphen-minus (`- ` is a list marker; `-- Привет` is a dialogue line typed on a keyboard without an em dash). */
    private fun isLeadDashUnit(u: Int): Boolean = isDashUnit(u) || isArrowUnit(u) || u == 0x2d

    /** §2.5 step 1b: the mid-line dash group is a run of units each of which is a dash unit or the hyphen-minus (`- `, `--`, `—-`), mixed freely; arrows are not in it (`a → b. c` is untouched). */
    private fun isMidLineDashUnit(u: Int): Boolean = isDashUnit(u) || u == 0x2d

    private fun isSectionUnit(u: Int): Boolean = u == 0xa7 || u == 0xb6

    private fun isRomanUnit(u: Int): Boolean =
        u == 0x69 || u == 0x76 || u == 0x78 || u == 0x49 || u == 0x56 || u == 0x58

    /** §2.3 `number` body: Digit{1,9} ( "." Digit{1,9} )* ( "." | ")" )? — the end index after it, or −1. */
    private fun numberBodyEnd(s: String, i: Int): Int {
        fun digits(from: Int): Int {
            var j = from
            while (j < s.length && isDigit(s[j].code) && j - from < 9) j++
            return j
        }
        var j = digits(i)
        if (j == i) return -1
        while (s.u(j) == 0x2e && j + 1 < s.length && isDigit(s[j + 1].code)) j = digits(j + 1)
        if (s.u(j) == 0x2e || s.u(j) == 0x29) j++
        return j
    }

    /** §2.3 `enum` body: "(" ( Digit{1,3} | Letter | roman{1,4} ) ")" or the same without the "(" — the end index after the ")", or −1. */
    private fun enumBodyEnd(s: String, i: Int): Int {
        var j = i
        val paren = s.u(j) == 0x28
        if (paren) j++
        val b = j
        var e = -1
        run {
            var d = b
            while (d < s.length && isDigit(s[d].code) && d - b < 3) d++
            if (d > b && !isDigit(s.u(d))) e = d
        }
        if (e < 0) {
            var r = b
            while (r < s.length && isRomanUnit(s[r].code) && r - b < 4) r++
            if (r > b && s.u(r) == 0x29) e = r
        }
        if (e < 0 && b < s.length) {
            val cp = scalarAt(s, b)
            if (isLetter(cp)) e = b + scalarLen(cp)
        }
        if (e < 0 || s.u(e) != 0x29) return -1
        return e + 1
    }

    /** §2.3 `citation` lead body: "[" Digit{1,4} "]" — the end index after the "]", or −1 (`[12] Author, Title` in a reference list). */
    private fun citationBodyEnd(s: String, i: Int): Int {
        if (s.u(i) != 0x5b) return -1
        var d = i + 1
        while (d < s.length && isDigit(s[d].code) && d - (i + 1) < 4) d++
        if (d == i + 1 || s.u(d) != 0x5d) return -1
        return d + 1
    }

    /** §2.5 step 1b: start of the `symbols` run (§2.3, keycaps included) that ends at unit [end] of [pre], or [end] when there is none. */
    private fun symbolsRunStart(pre: String, end: Int): Int {
        var j = end
        while (true) {
            val cp = scalarBefore(pre, j)
            if (cp == -1) break
            if (cp == 0x20e3) {
                // keycap: ( Digit | "#" | "*" ) U+FE0F? U+20E3
                var k = j - 1
                if (k > 0 && pre[k - 1].code == 0xfe0f) k--
                val u = if (k > 0) pre[k - 1].code else -1
                if (isDigit(u) || u == 0x23 || u == 0x2a) { j = k - 1; continue }
                j -= 1
                continue
            }
            if (isSymbolSoSk(cp) || cp == 0xfe0e || cp == 0xfe0f || cp == 0x200d || (cp in 0x1f3fb..0x1f3ff)) {
                j -= scalarLen(cp)
                continue
            }
            break
        }
        return j
    }

    /** §2.5 step 2: a footnote reference `[^id]` or a bracketed digit run `[12]` whose `]` sits at `end - 1` of [pre] — its start, or −1. */
    private fun referenceStart(pre: String, end: Int): Int {
        if (end < 3 || pre.u(end - 1) != 0x5d) return -1
        var j = end - 1
        while (j > 0 && isFootnoteIdUnit(pre[j - 1].code)) j--
        if (j == end - 1) return -1
        if (j >= 2 && pre[j - 1].code == 0x5e && pre[j - 2].code == 0x5b) return j - 2
        var allDigits = true
        for (k in j until end - 1) if (!isDigit(pre[k].code)) { allDigits = false; break }
        if (allDigits && j >= 1 && pre[j - 1].code == 0x5b) return j - 1
        return -1
    }

    private fun stripOpeners(tok: String): String {
        var o = 0
        while (o < tok.length) {
            if (tok[o] in OPENERS) { o++; continue }
            if (tok[o] == '!' && tok.u(o + 1) == 0x5b) { o++; continue }
            break
        }
        return if (o == 0) tok else tok.substring(o)
    }

    /** §2.5 B(ii): one Letter scalar followed by zero or more Marks. */
    private fun isSingleLetter(cps: IntArray): Boolean {
        if (cps.isEmpty() || !isLetter(cps[0])) return false
        for (i in 1 until cps.size) if (!isMark(cps[i])) return false
        return true
    }

    /** §2.5 B(ii): a compact initials chain — two or more groups of one Uppercase letter (+ Marks) separated by single `.` units (`И.И`, `J.R.R`, `U.S.A`; the final `.` is the terminator run). */
    private fun isInitialsChain(cps: IntArray): Boolean {
        var groups = 0
        var i = 0
        while (i < cps.size) {
            if (!isUppercase(cps[i])) return false
            i++
            while (i < cps.size && isMark(cps[i])) i++
            groups++
            if (i == cps.size) break
            if (cps[i] != 0x2e) return false
            i++
            if (i == cps.size) return false
        }
        return groups >= 2
    }

    /** §2.5 B(ii): the token before [tokStart] (over ≥ 1 WS19) is itself a single letter plus a terminator run — an initials chain (`J. R.`) or a spaced abbreviation (`z. B.`). */
    private fun prevTokenIsInitial(pre: String, tokStart: Int): Boolean {
        var i = tokStart
        while (i > 0 && isWS19(pre[i - 1].code)) i--
        if (i == tokStart) return false
        val e = i
        while (i > 0 && !isWS19(pre[i - 1].code)) i--
        val t = stripOpeners(pre.substring(i, e))
        var k = t.length
        while (k > 0 && isTerminator(t[k - 1].code)) k--
        if (k == t.length) return false
        return isSingleLetter(scalarsOf(t.substring(0, k)))
    }

    /** §2.6 abbreviation list, stored exactly as listed (final dot removed). */
    private val ABBREVIATIONS: Set<String> = hashSetOf(
        // English
        "a.d", "a.m", "al", "approx", "apr", "assn", "aug", "ave", "b.c", "blvd", "ca", "cf", "ch", "co",
        "corp", "dec", "dept", "dr", "e.g", "e.u", "ed", "eds", "eq", "eqs", "esp", "etc", "excl", "ext",
        "feb", "ff", "fig", "figs", "fri", "govt", "i.e", "ibid", "inc", "incl", "jan", "jr", "jul", "jun",
        "ltd", "misc", "mr", "mrs", "ms", "mt", "nov", "oct", "p.m", "ph.d", "pp", "prof", "rd", "resp",
        "sep", "sept", "sr", "st", "tel", "thu", "tue", "u.k", "u.s", "univ", "viz", "vol", "vs",
        // German
        "abs", "bspw", "bzgl", "bzw", "d.h", "evtl", "exkl", "geb", "ggf", "hrsg", "inkl", "jh", "mio",
        "mrd", "nr", "o.ä", "o.g", "s.o", "s.u", "sog", "std", "str", "tsd", "u.a", "u.u", "usw",
        "vgl", "z.b", "z.t", "zzgl",
        // French
        "art", "av", "chap", "éd", "env", "ex", "mlle", "mme", "p.ex", "réf", "ste", "tél",
        "trad",
        // Spanish
        "aprox", "avda", "cap", "dña", "dpto", "ej", "núm", "p.ej", "pág", "págs",
        "sra", "srta", "ud", "uds",
        // Russian
        "акад", "англ", "г", "гг", "гл",
        "гос", "греч", "др", "зам",
        "изд", "им", "исп", "ит", "кв",
        "коп", "корп", "лат", "млн",
        "млрд", "напр", "нем", "н.э",
        "обл", "пер", "перев", "пл",
        "пп", "пр", "прим", "просп",
        "проф", "ред", "руб", "рус",
        "св", "см", "сокр", "сост",
        "ст", "стр", "табл", "тел",
        "т.д", "т.е", "т.к", "т.н", "т.о", "т.п",
        "т.ч", "тыс", "укр", "ул", "фр",
        "чел", "чл", "шт", "экз",
        // Ukrainian
        "буд", "вул", "грн", "див",
        "ін", "рр", "стор", "тис",
        "т.зв",
    )

    /** §2.6 fold: ASCII A–Z, Latin-1 À–Þ (not ×), Cyrillic А–Я, Ё Є І Ї Ґ only. */
    private fun fold(s: String): String {
        val out = StringBuilder(s.length)
        for (i in s.indices) {
            val u = s[i].code
            var f = u
            if (u in 0x41..0x5a) f = u + 0x20
            else if (u in 0xc0..0xde && u != 0xd7) f = u + 0x20
            else if (u in 0x410..0x42f) f = u + 0x20
            else if (u == 0x401) f = 0x451
            else if (u == 0x404) f = 0x454
            else if (u == 0x406) f = 0x456
            else if (u == 0x407) f = 0x457
            else if (u == 0x490) f = 0x491
            out.append(f.toChar())
        }
        return out.toString()
    }

    /** §2.3 prefix2 length on line [s]. */
    private fun prefix2Length(s: String): Int {
        val p = parsePrefix(s)
        var i = p.prefixEnd
        var heading = false
        var footnote = false
        if (p.marker == null) {
            val base = p.indent0.length + p.quotes.length + p.indent1.length
            // headingPrefix := indent0 quotes indent1 "#"{1,6} SP  (+ the WS19 run after it)
            var h = base
            var n = 0
            while (h < s.length && s[h].code == 0x23) { n++; h++ }
            // the parser's parseHeading drops SP only: `\t# h` is a paragraph (§0.12)
            val tabFree = p.indent0.indexOf('\t') < 0 && p.indent1.indexOf('\t') < 0
            if (tabFree && n in 1..6 && s.u(h) == SP) {
                h++
                while (h < s.length && isWS19(s[h].code)) h++
                i = h
                heading = true
            } else {
                // footnotePrefix := indent0 quotes indent1 "[^" id "]:" WS19*
                val f = footnotePrefixLength(s, base)
                if (f >= 0) { i = base + f; footnote = true }
            }
        }
        if (!heading && !footnote) {
            // prefix WS19*: every §0.11 prefix — a marker, a box, a quote group or
            // nothing at all — absorbs the WS19 run after it, as headingPrefix does:
            // the parser drops SP only, but `- \tt`, `> t` and `​t` all
            // render `t` as the first visible unit of the line
            while (i < s.length && isWS19(s[i].code)) i++
        }
        // lead := ( section | enum | citation | number | dash | symbols ) WS19+
        //   enum and citation only as the first lead (directly after prefix,
        //   headingPrefix or footnotePrefix);
        //   number only as the first lead after headingPrefix (A.2: `2.5 cups`)
        fun wsAfter(j: Int): Int {
            var w = j
            while (w < s.length && isWS19(s[w].code)) w++
            return if (w > j) w else -1
        }
        var first = true
        while (true) {
            var j = i
            val u = s.u(j)
            var w = -1
            if (isSectionUnit(u)) {
                // section := run of ( "§" | "¶" ), WS19*, number
                var k = j
                while (k < s.length && isSectionUnit(s[k].code)) k++
                var m = k
                while (m < s.length && isWS19(s[m].code)) m++
                val n = numberBodyEnd(s, m)
                if (n >= 0) w = wsAfter(n)
                if (w < 0) w = wsAfter(k) // a bare `§ ` is a dash-group lead
            } else if (first && enumBodyEnd(s, j) >= 0 && wsAfter(enumBodyEnd(s, j)) >= 0) {
                w = wsAfter(enumBodyEnd(s, j))
            } else if (first && citationBodyEnd(s, j) >= 0 && wsAfter(citationBodyEnd(s, j)) >= 0) {
                w = wsAfter(citationBodyEnd(s, j))
            } else if (first && heading && numberBodyEnd(s, j) >= 0 && wsAfter(numberBodyEnd(s, j)) >= 0) {
                w = wsAfter(numberBodyEnd(s, j))
            }
            if (w >= 0) { i = w; first = false; continue }
            if (isLeadDashUnit(u)) {
                while (j < s.length && isLeadDashUnit(s[j].code)) j++
                if (j == i + 1 && u == 0x2d) break // a single hyphen-minus is never a lead
            } else {
                while (j < s.length) {
                    val cp = scalarAt(s, j)
                    // a keycap sequence (1️⃣ #️⃣ *️⃣): Digit, `#` or `*`, optional U+FE0F, U+20E3
                    if (isDigit(cp) || cp == 0x23 || cp == 0x2a) {
                        var k = j + 1
                        if (s.u(k) == 0xfe0f) k++
                        if (s.u(k) == 0x20e3) { j = k + 1; continue }
                        break
                    }
                    val sym = isSymbolSoSk(cp) || cp == 0xfe0e || cp == 0xfe0f || cp == 0x200d || cp == 0x20e3 ||
                        (cp in 0x1f3fb..0x1f3ff)
                    if (!sym) break
                    j += scalarLen(cp)
                }
                if (j == i) break
            }
            w = wsAfter(j)
            if (w < 0) break
            i = w
            first = false
        }
        return i
    }

    /** §2.1 inline scan over P = lines s+1 … c. True when the caret is inside a code span, display math or inline math. */
    private fun insideInline(lines: Lines, scan: Scan, c: Int, before: String, typed: String): Boolean {
        // s = the last special line above c (excluded from P); a thematic break
        // and a page break end a paragraph the same way (§2.1)
        // a table row (§0.13) ends a paragraph too: the parser's row loop consumed
        // it, so an unclosed `$$` in a cell does not leak into the prose below
        var s = -1
        for (k in c - 1 downTo 0) {
            if (scan.special[k] || isThematicBreak(lines.text(k)) || isPageBreak(lines.text(k)) || tableContext(lines, scan, k) != null) {
                s = k; break
            }
        }
        // a quote line or a marker line starts a new block: P begins there (included)
        var first = s + 1
        for (k in c downTo s + 1) {
            if (isQuoteLine(lines.text(k)) || parsePrefix(lines.text(k)).marker != null) { first = k; break }
        }
        var display = false
        for (k in first..c) {
            val isCaretLine = k == c
            val line = if (isCaretLine) before else lines.text(k)
            var code = 0
            var inline = false
            var i = 0
            // `next` on the caret line, when i+1 == caret′, is `typed` (§2.1): its first
            // unit, since a typed letter is never `$`, `]`, `)`, `[`, `(`, WS19 or a digit
            fun nextAt(idx: Int): Int =
                if (idx < line.length) line[idx].code else if (isCaretLine && idx == line.length) typed[0].code else -1
            fun runOf(idx: Int, ch: Char): Int {
                var j = idx
                while (j < line.length && line[j] == ch) j++
                return j - idx
            }
            while (i < line.length) {
                val u = line[i]
                if (code > 0) {                                                  // 1.
                    if (u == '`') { val n = runOf(i, '`'); if (n == code) code = 0; i += n; continue }
                    i++; continue
                }
                if (display) {                                                   // 2.
                    if ((u == '$' && nextAt(i + 1) == 0x24) || (u == '\\' && nextAt(i + 1) == 0x5d)) { display = false; i += 2; continue }
                    i++; continue
                }
                if (inline) {                                                    // 3.
                    if (u == '$') { inline = false; i++; continue }
                    if (u == '\\' && nextAt(i + 1) == 0x29) { inline = false; i += 2; continue }
                    if (u == '\\') { i += 2; continue }
                    i++; continue
                }
                // 4. normal
                if (u == '`') { code = runOf(i, '`'); i += code; continue }
                if (u == '\\') {
                    val n = nextAt(i + 1)
                    if (n == 0x5b) display = true
                    else if (n == 0x28) inline = true
                    i += 2; continue
                }
                if (u == '$') {
                    if (nextAt(i + 1) == 0x24) { display = true; i += 2; continue }
                    val prev = scalarBefore(line, i)
                    val prevOk = prev == -1 || !(isLetter(prev) || isDigit(prev) || prev == 0x5f || prev == 0x24)
                    val n = nextAt(i + 1)
                    val nextOk = n != -1 && !isWS19(n) && !isDigit(n)
                    if (prevOk && nextOk) inline = true
                    i++; continue
                }
                i++
            }
            // the caret line: state at the caret decides
            if (isCaretLine) return code > 0 || display || inline
        }
        return false
    }

    fun capitalize(text: String, selectionStart: Int, selectionEnd: Int, typed: String): String? {
        val norm = normalize(text, selectionStart, selectionEnd) ?: return null
        val t = norm.t
        val caret = norm.caret
        // §2.1a typed is one Lowercase letter with a defined upper()
        val tcp = scalarsOf(typed)
        if (tcp.size != 1) return null
        if (!isLowercase(tcp[0])) return null
        val up = upper(tcp[0])
        if (up == -1) return null
        val upperTyped = scalarToString(up)

        val lines = Lines(t)
        val c = lines.lineOf(caret)
        if (c < 0) return null                                    // §0.2
        val scan = scanState(lines, c, false)
        if (scan.caretCode) return null                           // §2.1b
        val line = lines.text(c)
        if (tableContext(lines, scan, c) != null || isPipeLine(line)) return null // §2.1c
        val rel = caret - lines.starts[c]
        val before = line.substring(0, rel)
        if (insideInline(lines, scan, c, before, typed)) return null // §2.1d

        // §2.1e link destination
        val lp = before.lastIndexOf("](")
        if (lp >= 0 && before.indexOf(')', lp + 2) < 0) return null

        // §2.1f current token. prefix2 is measured on `before` (§2.3): a caret
        // inside the SP run a marker absorbs is still at the content start.
        val p2 = prefix2Length(before)
        var lastWS = -1
        for (i in before.length - 1 downTo 0) if (isWS19(before[i].code)) { lastWS = i; break }
        val tokenStart = max(lastWS + 1, min(p2, before.length))
        val token = before.substring(tokenStart)
        if (token.contains("://") || token.contains("www.") || token.indexOf('@') >= 0 || token.indexOf('/') >= 0) return null

        // §2.4: a task box being typed by hand — `[` directly after the marker's SP
        // run and the typed letter is `x` — is not link text: `- [x] done` must not
        // become `- [X] done`
        run {
            val p = parsePrefix(line)
            if (p.marker != null && p.box == null && before.length == p.prefixEnd + 1 &&
                before.u(p.prefixEnd) == 0x5b && typed == "x"
            ) return null
        }

        // §2.2 pre = before minus its trailing run of openers. A guillemet opener
        // (« ‹ » ›) that is preceded by WS19, by another opener or by nothing but
        // prefix2 carries the WS19 run after it (French spacing: `« Bonjour »`);
        // a closing `»` or `«` (`Done.» then`, `»Hallo.« dann`) keeps its closer role.
        var preEnd = before.length
        var quoteStripped = false
        while (true) {
            if (preEnd == 0) break
            val u = before[preEnd - 1]
            if (u in OPENERS) { preEnd--; if (u in QUOTE_OPENERS) quoteStripped = true; continue }
            if (u == '!' && preEnd < before.length && before[preEnd] == '[') { preEnd--; continue }
            var w = preEnd
            while (w > 0 && isWS19(before[w - 1].code)) w--
            if (w < preEnd && w > 0 && before[w - 1] in GUILLEMETS) {
                val g = w - 1
                if (g == p2 || (g > 0 && (isWS19(before[g - 1].code) || before[g - 1] in OPENERS))) { preEnd = w; continue }
            }
            break
        }
        val pre = before.substring(0, preEnd)

        // §2.4 rule A — line start: pre is exactly prefix2, or pre is empty (the
        // caret is at column 0, ahead of whatever prefix the line has: `|- item`,
        // or a selection that starts at the line start is being replaced)
        if (pre == before.substring(0, min(p2, before.length)) || pre.isEmpty()) return upperTyped

        // §2.5 rule B — sentence start within the line
        var i = pre.length
        val wsEnd = i
        while (i > 0 && isWS19(pre[i - 1].code)) i--
        if (i == wsEnd) return null                                          // 1. ≥ 1 WS19
        // 1a. direct speech after a colon: `:` before the WS19 run and a quotation
        //     opener among the stripped openers (`Он сказал: «`, `He said: "`)
        if (quoteStripped && pre[i - 1].code == 0x3a) return upperTyped
        var viaDash = false                                                  // 1b. optional group: WS19+ (dash-run | symbols-run) WS19+
        run {
            var j = i
            while (j > 0 && isMidLineDashUnit(pre[j - 1].code)) j--
            if (j == i) j = symbolsRunStart(pre, i)                          //    an emoji / keycap between two sentences (`Done. 🎉 next`)
            else viaDash = true
            if (j < i) {
                var w = j
                while (w > 0 && isWS19(pre[w - 1].code)) w--
                if (w < j) i = w else viaDash = false
            }
        }
        var closers = 0
        while (true) {                                                       // 2. closers, and a footnote reference / citation
            if (i > 0 && pre[i - 1].code == 0x5d) {                          //    `text.[^1] Next`, `text.[12] Next` — skipped like a closer
                val r = referenceStart(pre, i)
                if (r >= 0) { i = r; closers++; continue }
            }
            if (i > 0 && pre[i - 1] in CLOSERS) { i--; closers++; continue }
            break
        }
        if (closers > 0) while (i > 0 && isWS19(pre[i - 1].code)) i--        //    French `? »`: WS19 between the terminator and a closer
        val termEnd = i
        var dots = 0
        var bangQ = false
        while (i > 0 && isTerminator(pre[i - 1].code)) { if (pre[i - 1].code == 0x2e) dots++ else bangQ = true; i-- }
        if (i == termEnd) return null                                        // 3. run length ≥ 1
        if (dots >= 2) return null                                           //    ellipsis
        if (viaDash && pre[termEnd - 1].code != 0x2e) return null            //    `? —` / `! —` are dialogue tags
        val tokEnd = i
        while (i > 0 && !isWS19(pre[i - 1].code)) i--                        // 4. token
        var tokStart = i
        var tok = stripOpeners(pre.substring(tokStart, tokEnd))
        if (tok.isEmpty() && dots == 0 && tokEnd >= 1 && isWS19(pre[tokEnd - 1].code) &&
            !(tokEnd >= 2 && isWS19(pre[tokEnd - 2].code))
        ) {
            // French spacing (`Bonjour !`, `Ça va ?`): exactly one WS19 unit before the
            // run, then the token — never reaching into prefix2
            var j = tokEnd - 1
            while (j > 0 && !isWS19(pre[j - 1].code)) j--
            if (j >= p2 && j < tokEnd - 1) { tokStart = j; tok = stripOpeners(pre.substring(j, tokEnd - 1)) }
        }
        if (tok.isEmpty()) return null                                       // (i)
        if (bangQ) return upperTyped                                         //    a run with `?` or `!` ends the sentence whatever the token
        val tcps = scalarsOf(tok)
        if (isSingleLetter(tcps) && tcps[0] != 0x44f) {                      // (ii) one Letter (+ Marks)
            if (isUppercase(tcps[0])) { if (prevTokenIsInitial(pre, tokStart)) return null } // `J. R.`, `z. B.`
            else return null                                                 // `p. 42`, `т. е.`, `J.` after a name is Lu
        }
        if (isInitialsChain(tcps)) return null                               // (ii) compact initials `И.И.`, `J.R.R.`, `U.S.A.`
        if (ABBREVIATIONS.contains(fold(tok))) return null                    // (iii)
        return upperTyped
    }
}
