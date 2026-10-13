/*
 * MarkdownFormatting.kt
 * md (Android)
 *
 * The formatting commands (#13) — Bold, Italic, Code, Strikethrough, Link,
 * Heading 1–6 and the phone bar's heading cycle, Bullet List, Task List,
 * Quote, Indent, Outdent — and typing `*` `_` `` ` `` `$` over a selection.
 *
 * A port of the family's shared specification (md.win docs/formatting.md,
 * written against the TypeScript reference in md.win
 * tools/formatting-reference/). formatting-vectors.json — 127 `format` and 10
 * `wrapTyped` vectors, the same bytes in md, md.macOS, md.win and here — is
 * the oracle and is run in full by MarkdownFormattingTest. A disputed vector
 * is changed in the reference first, never here.
 *
 * Pure: offsets are UTF-16 code units (a Kotlin String's own), a line break is
 * U+000A only, and "blank" is SP, TAB or LF — spelled out, never a platform
 * whitespace predicate. SmartTyping is not touched by any of this.
 */

package me.nettrash.md.markdown

/** One formatting command. */
enum class FormatOp {
    BOLD, ITALIC, CODE, STRIKETHROUGH, LINK,
    HEADING1, HEADING2, HEADING3, HEADING4, HEADING5, HEADING6, HEADING_CYCLE,
    BULLET_LIST, TASK_LIST, QUOTE, INDENT, OUTDENT,
}

/** Replace `[start, end)` of the ORIGINAL text with [text]; the selection is in the RESULTING text. */
data class FormatEdit(
    val start: Int,
    val end: Int,
    val text: String,
    val selectionStart: Int,
    val selectionEnd: Int,
)

object MarkdownFormatting {

    private const val SP = ' '
    private const val TAB = '\t'
    private const val LF = '\n'

    private fun isBlankUnit(c: Char) = c == SP || c == TAB || c == LF

    // MARK: - Inline

    private class Inline(val ch: Char, val width: Int, val present: (Int, Int) -> Boolean, val remove: Int)

    private val BOLD = Inline('*', 2, { a, b -> a >= 2 && b >= 2 }, 2)
    private val ITALIC = Inline('*', 1, { a, b -> a % 2 == 1 && b % 2 == 1 }, 1)
    private val CODE = Inline('`', 1, { a, b -> a == 1 && b == 1 }, 1)
    private val STRIKE = Inline('~', 2, { a, b -> a >= 2 && b >= 2 }, 2)

    private fun runBefore(text: String, p: Int, ch: Char, floor: Int = 0): Int {
        var n = 0
        while (p - n - 1 >= floor && text[p - n - 1] == ch) n++
        return n
    }

    private fun runAfter(text: String, p: Int, ch: Char, ceiling: Int = text.length): Int {
        var n = 0
        while (p + n < ceiling && text[p + n] == ch) n++
        return n
    }

    private fun trimmed(text: String, s: Int, e: Int): Pair<Int, Int> {
        var a = s
        var b = e
        while (a < b && isBlankUnit(text[a])) a++
        while (b > a && isBlankUnit(text[b - 1])) b--
        return a to b
    }

    private fun inline(text: String, s: Int, e: Int, spec: Inline, alternative: Char?): FormatEdit {
        val m = spec.ch.toString().repeat(spec.width)
        if (s == e) {
            if (runBefore(text, s, spec.ch) == spec.width && runAfter(text, s, spec.ch) == spec.width) {
                return FormatEdit(s - spec.width, s + spec.width, "", s - spec.width, s - spec.width)
            }
            return FormatEdit(s, s, m + m, s + spec.width, s + spec.width)
        }
        val markers = if (alternative != null) listOf(spec.ch, alternative) else listOf(spec.ch)
        // 1. The selection carries the markers itself.
        for (ch in markers) {
            val lead = runAfter(text, s, ch, e)
            val trail = runBefore(text, e, ch, s)
            if (lead + trail < e - s) {
                val present = if (ch == spec.ch) spec.present(lead, trail) else lead == 1 && trail == 1
                val remove = if (ch == spec.ch) spec.remove else 1
                if (present) {
                    val inner = text.substring(s + remove, e - remove)
                    return FormatEdit(s, e, inner, s, s + inner.length)
                }
            }
        }
        // 2. The markers sit just outside the selection.
        for (ch in markers) {
            val before = runBefore(text, s, ch)
            val after = runAfter(text, e, ch)
            val present = if (ch == spec.ch) spec.present(before, after) else before == 1 && after == 1
            val remove = if (ch == spec.ch) spec.remove else 1
            if (present) {
                val inner = text.substring(s, e)
                return FormatEdit(s - remove, e + remove, inner, s - remove, s - remove + inner.length)
            }
        }
        // 3. Wrap what is selected, without its surrounding blanks.
        val (a, b) = trimmed(text, s, e)
        if (a == b) return FormatEdit(s, s, m + m, s + spec.width, s + spec.width)
        return FormatEdit(a, b, m + text.substring(a, b) + m, a + spec.width, b + spec.width)
    }

    private fun startsWithAsciiIgnoringCase(text: String, prefix: String): Boolean {
        if (text.length < prefix.length) return false
        for (i in prefix.indices) {
            val c = text[i]
            if ((if (c in 'A'..'Z') c + 32 else c) != prefix[i]) return false
        }
        return true
    }

    private fun link(text: String, s: Int, e: Int): FormatEdit {
        val (a, b) = trimmed(text, s, e)
        if (a == b) return FormatEdit(s, s, "[]()", s + 1, s + 1)
        val inner = text.substring(a, b)
        val blank = inner.any { isBlankUnit(it) }
        if (!blank && (startsWithAsciiIgnoringCase(inner, "http://") || startsWithAsciiIgnoringCase(inner, "https://"))) {
            return FormatEdit(a, b, "[]($inner)", a + 1, a + 1)
        }
        val urlAt = a + 1 + inner.length + 2
        return FormatEdit(a, b, "[$inner](url)", urlAt, urlAt + 3)
    }

    // MARK: - Lines

    private class Line(val start: Int, val end: Int)

    /** Within a line: replace `[at, at + old)` with [text]. */
    private class LineChange(val at: Int, val old: Int, val text: String)

    private fun lineAt(text: String, p: Int): Line {
        var start = p
        while (start > 0 && text[start - 1] != LF) start--
        var end = p
        while (end < text.length && text[end] != LF) end++
        return Line(start, end)
    }

    private fun touchedLines(text: String, s: Int, e: Int): List<Line> {
        val first = lineAt(text, s)
        var lastPos = e
        if (e > s && e > 0 && text[e - 1] == LF) lastPos = e - 1
        val lines = arrayListOf(first)
        var cur = first
        while (cur.end < lastPos) {
            cur = lineAt(text, cur.end + 1)
            lines += cur
        }
        return lines
    }

    private fun isBlankLine(line: String) = line.all { it == SP || it == TAB }

    private fun indentEnd(line: String): Int {
        var i = 0
        while (i < line.length && (line[i] == SP || line[i] == TAB)) i++
        return i
    }

    private fun charAt(s: String, i: Int): Char? = if (i in s.indices) s[i] else null

    private fun isDigit(c: Char?) = c != null && c in '0'..'9'

    private class Heading(val at: Int, val level: Int, val length: Int)

    private fun heading(line: String): Heading? {
        var i = 0
        while (i < 3 && charAt(line, i) == SP) i++
        var n = 0
        while (charAt(line, i + n) == '#') n++
        if (n < 1 || n > 6) return null
        val after = i + n
        if (after == line.length) return Heading(i, n, n)
        if (line[after] == SP) return Heading(i, n, n + 1)
        return null
    }

    private class ListMarker(val at: Int, val bullet: Boolean, val task: Boolean, val contentAt: Int)

    private fun listMarker(line: String): ListMarker? {
        val at = indentEnd(line)
        var i = at
        val c = charAt(line, i)
        val bullet: Boolean
        if (c == '-' || c == '*' || c == '+') {
            bullet = true
            i++
        } else {
            var d = 0
            while (d < 9 && isDigit(charAt(line, i + d))) d++
            if (d == 0) return null
            val p = charAt(line, i + d)
            if (p != '.' && p != ')') return null
            bullet = false
            i += d + 1
        }
        if (i == line.length) return ListMarker(at, bullet, false, i)
        if (line[i] != SP) return null
        val box = if (i + 4 <= line.length) line.substring(i + 1, i + 4) else ""
        if (box == "[ ]" || box == "[x]" || box == "[X]") {
            val after = i + 4
            if (after == line.length) return ListMarker(at, bullet, true, after)
            if (line[after] == SP) return ListMarker(at, bullet, true, after + 1)
        }
        return ListMarker(at, bullet, false, i + 1)
    }

    private class QuoteMarker(val at: Int, val length: Int)

    private fun quoteMarker(line: String): QuoteMarker? {
        var i = 0
        while (i < 3 && charAt(line, i) == SP) i++
        if (charAt(line, i) != '>') return null
        return QuoteMarker(i, if (charAt(line, i + 1) == SP) 2 else 1)
    }

    private fun applyLines(
        text: String, s: Int, e: Int,
        plan: (lines: List<String>, participating: List<Boolean>) -> List<LineChange?>,
    ): FormatEdit? {
        val lines = touchedLines(text, s, e)
        val strings = lines.map { text.substring(it.start, it.end) }
        val multi = lines.size > 1
        val participating = strings.map { !(multi && isBlankLine(it)) }
        val changes = plan(strings, participating)
        if (changes.all { it == null || (it.old == 0 && it.text.isEmpty()) }) return null

        fun map(p: Int): Int {
            var delta = 0
            for (k in lines.indices) {
                val change = changes[k]
                val line = lines[k]
                val lineDelta = if (change != null) change.text.length - change.old else 0
                if (p > line.end) {
                    delta += lineDelta
                    continue
                }
                if (change == null) return p + delta
                val a = line.start + change.at
                if (p >= a + change.old) return p + delta + lineDelta
                if (p <= a) return p + delta
                return a + delta + change.text.length
            }
            return p + delta
        }

        val out = strings.mapIndexed { k, l ->
            val c = changes[k]
            if (c != null) l.substring(0, c.at) + c.text + l.substring(c.at + c.old) else l
        }
        return FormatEdit(lines.first().start, lines.last().end, out.joinToString("\n"), map(s), map(e))
    }

    private fun setHeading(line: String, level: Int): LineChange? {
        val h = heading(line)
        if (h != null) {
            if (level == 0) return LineChange(h.at, h.length, "")
            if (h.level == level && h.length == level + 1) return null
            return LineChange(h.at, h.length, "#".repeat(level) + " ")
        }
        if (level == 0) return null
        val indent = indentEnd(line)
        val at = if (indent <= 3 && !line.substring(0, indent).contains(TAB)) indent else 0
        return LineChange(at, 0, "#".repeat(level) + " ")
    }

    private fun headingOp(text: String, s: Int, e: Int, level: Int?): FormatEdit? =
        applyLines(text, s, e) { lines, on ->
            val first = lines.indices.firstOrNull { on[it] }
            val target = if (level == null) {
                when (if (first == null) 0 else heading(lines[first])?.level ?: 0) {
                    0 -> 1
                    1 -> 2
                    2 -> 3
                    else -> 0
                }
            } else {
                val all = lines.indices.all { !on[it] || heading(lines[it])?.level == level }
                if (all) 0 else level
            }
            lines.mapIndexed { k, l -> if (on[k]) setHeading(l, target) else null }
        }

    private fun bulletOp(text: String, s: Int, e: Int): FormatEdit? =
        applyLines(text, s, e) { lines, on ->
            val all = lines.indices.all { k ->
                if (!on[k]) true else listMarker(lines[k]).let { it != null && it.bullet && !it.task }
            }
            lines.mapIndexed { k, l ->
                if (!on[k]) return@mapIndexed null
                val m = listMarker(l)
                when {
                    all -> LineChange(m!!.at, m.contentAt - m.at, "")
                    m == null -> LineChange(indentEnd(l), 0, "- ")
                    m.task || !m.bullet -> LineChange(m.at, m.contentAt - m.at, "- ")
                    else -> null
                }
            }
        }

    private fun taskOp(text: String, s: Int, e: Int): FormatEdit? =
        applyLines(text, s, e) { lines, on ->
            val all = lines.indices.all { k -> !on[k] || listMarker(lines[k])?.task == true }
            lines.mapIndexed { k, l ->
                if (!on[k]) return@mapIndexed null
                val m = listMarker(l)
                when {
                    all -> LineChange(m!!.at, m.contentAt - m.at, "")
                    m == null -> LineChange(indentEnd(l), 0, "- [ ] ")
                    m.task -> null
                    !m.bullet -> LineChange(m.at, m.contentAt - m.at, "- [ ] ")
                    // A bullet: the box goes after its marker and space (a bare `-` gets its space too).
                    else -> LineChange(m.at, m.contentAt - m.at, l[m.at] + " [ ] ")
                }
            }
        }

    private fun quoteOp(text: String, s: Int, e: Int): FormatEdit? {
        val strings = touchedLines(text, s, e).map { text.substring(it.start, it.end) }
        val multi = strings.size > 1
        // Blank lines do not decide whether the selection is "all quoted", but when quoting they get a bare `>`.
        val all = strings.all { (multi && isBlankLine(it)) || quoteMarker(it) != null }
        return applyLines(text, s, e) { lines, _ ->
            lines.map { l ->
                when {
                    all -> quoteMarker(l)?.let { q -> LineChange(q.at, q.length, "") }
                    quoteMarker(l) != null -> null
                    multi && isBlankLine(l) -> LineChange(0, 0, ">")
                    else -> LineChange(0, 0, "> ")
                }
            }
        }
    }

    private fun indentOp(text: String, s: Int, e: Int): FormatEdit? =
        applyLines(text, s, e) { lines, on -> lines.indices.map { k -> if (on[k]) LineChange(0, 0, "  ") else null } }

    private fun outdentOp(text: String, s: Int, e: Int): FormatEdit? =
        applyLines(text, s, e) { lines, _ ->
            lines.map { l ->
                when {
                    charAt(l, 0) == TAB -> LineChange(0, 1, "")
                    charAt(l, 0) == SP -> LineChange(0, if (charAt(l, 1) == SP) 2 else 1, "")
                    else -> null
                }
            }
        }

    /** The edit [op] makes of [text] with the selection `[selectionStart, selectionEnd)`, or null for none. */
    fun format(text: String, selectionStart: Int, selectionEnd: Int, op: FormatOp): FormatEdit? {
        val s = minOf(selectionStart, selectionEnd)
        val e = maxOf(selectionStart, selectionEnd)
        return when (op) {
            FormatOp.BOLD -> inline(text, s, e, BOLD, null)
            FormatOp.ITALIC -> inline(text, s, e, ITALIC, '_')
            FormatOp.CODE -> inline(text, s, e, CODE, null)
            FormatOp.STRIKETHROUGH -> inline(text, s, e, STRIKE, null)
            FormatOp.LINK -> link(text, s, e)
            FormatOp.HEADING1 -> headingOp(text, s, e, 1)
            FormatOp.HEADING2 -> headingOp(text, s, e, 2)
            FormatOp.HEADING3 -> headingOp(text, s, e, 3)
            FormatOp.HEADING4 -> headingOp(text, s, e, 4)
            FormatOp.HEADING5 -> headingOp(text, s, e, 5)
            FormatOp.HEADING6 -> headingOp(text, s, e, 6)
            FormatOp.HEADING_CYCLE -> headingOp(text, s, e, null)
            FormatOp.BULLET_LIST -> bulletOp(text, s, e)
            FormatOp.TASK_LIST -> taskOp(text, s, e)
            FormatOp.QUOTE -> quoteOp(text, s, e)
            FormatOp.INDENT -> indentOp(text, s, e)
            FormatOp.OUTDENT -> outdentOp(text, s, e)
        }
    }

    /** The units whose typing over a selection wraps it. */
    const val WRAPPING = "*_`$"

    /** Typing [typed] over a non-empty selection: wrap it when [typed] is one of `* _ ` $`; otherwise null (ordinary typing). */
    fun wrapTyped(text: String, selectionStart: Int, selectionEnd: Int, typed: String): FormatEdit? {
        val s = minOf(selectionStart, selectionEnd)
        val e = maxOf(selectionStart, selectionEnd)
        if (s == e || typed.length != 1 || typed[0] !in WRAPPING) return null
        return FormatEdit(s, e, typed + text.substring(s, e) + typed, s + 1, e + 1)
    }
}
