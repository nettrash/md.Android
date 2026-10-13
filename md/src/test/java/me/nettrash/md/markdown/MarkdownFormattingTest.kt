/*
 * MarkdownFormattingTest.kt
 * md (Android)
 *
 * The formatting commands against the family's oracle (#13).
 * `src/test/resources/formatting-vectors.json` is copied verbatim from
 * md.win (tests/Md.Core.Tests/Fixtures) — the same bytes md and md.macOS
 * carry — and every vector is run: no skips, nothing "close enough". A
 * disputed vector is changed in the TypeScript reference first and
 * regenerated in one commit across the repos. JSON is read by the
 * hand-written parser at the foot of PlotTest.kt.
 */

package me.nettrash.md.markdown

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MarkdownFormattingTest {

    private val vectors: JsonValue by lazy {
        val stream = checkNotNull(javaClass.classLoader?.getResourceAsStream("formatting-vectors.json")) {
            "missing formatting-vectors.json"
        }
        Json.parse(stream.bufferedReader(Charsets.UTF_8).use { it.readText() })
    }

    private fun expected(row: JsonValue): FormatEdit? {
        val x = row["expected"]
        if (x.isNull) return null
        return FormatEdit(x["start"].int(), x["end"].int(), x["text"].string(), x["selectionStart"].int(), x["selectionEnd"].int())
    }

    /** The JSON's op names, camelCase, to the enum. */
    private fun op(name: String): FormatOp = FormatOp.valueOf(
        when (name) {
            "headingCycle" -> "HEADING_CYCLE"
            "bulletList" -> "BULLET_LIST"
            "taskList" -> "TASK_LIST"
            else -> name.uppercase()
        },
    )

    @Test fun everyFormatVectorMatches() {
        val rows = vectors["format"].array()
        assertEquals(127, rows.size)
        val failures = rows.mapNotNull { row ->
            val got = MarkdownFormatting.format(row["text"].string(), row["selectionStart"].int(), row["selectionEnd"].int(), op(row["op"].string()))
            val want = expected(row)
            if (got != want) "${row["id"].string()}: expected $want, actual $got" else null
        }
        assertTrue(failures.joinToString("\n"), failures.isEmpty())
    }

    @Test fun everyWrapTypedVectorMatches() {
        val rows = vectors["wrapTyped"].array()
        assertEquals(10, rows.size)
        val failures = rows.mapNotNull { row ->
            val got = MarkdownFormatting.wrapTyped(row["text"].string(), row["selectionStart"].int(), row["selectionEnd"].int(), row["typed"].string())
            val want = expected(row)
            if (got != want) "${row["id"].string()}: expected $want, actual $got" else null
        }
        assertTrue(failures.joinToString("\n"), failures.isEmpty())
    }

    @Test fun aReversedSelectionIsTheSameSelection() {
        assertEquals(
            MarkdownFormatting.format("say hello now", 4, 9, FormatOp.BOLD),
            MarkdownFormatting.format("say hello now", 9, 4, FormatOp.BOLD),
        )
    }

    @Test fun everyOpIsCoveredHeading5ByThisPort() {
        // The oracle has no heading5 row; it is the same rule as 1–6, pinned here.
        val used = vectors["format"].array().map { op(it["op"].string()) }.toSet()
        assertEquals(FormatOp.entries.toSet() - FormatOp.HEADING5, used)
        assertEquals(FormatEdit(0, 1, "##### x", 7, 7), MarkdownFormatting.format("x", 1, 1, FormatOp.HEADING5))
        assertEquals(FormatEdit(0, 7, "x", 1, 1), MarkdownFormatting.format("##### x", 7, 7, FormatOp.HEADING5))
    }
}
