/*
 * FormattingVectorsInstrumentedTest.kt
 * md (Android)
 *
 * The formatting oracle (#13) run on ART as well as the JVM: the same
 * formatting-vectors.json, every row, read with the platform's org.json.
 */

package me.nettrash.md.markdown

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class FormattingVectorsInstrumentedTest {

    private val vectors: JSONObject by lazy {
        val assets = InstrumentationRegistry.getInstrumentation().context.assets
        JSONObject(assets.open("formatting-vectors.json").bufferedReader().use { it.readText() })
    }

    private fun expected(row: JSONObject): FormatEdit? {
        if (row.isNull("expected")) return null
        val x = row.getJSONObject("expected")
        return FormatEdit(x.getInt("start"), x.getInt("end"), x.getString("text"), x.getInt("selectionStart"), x.getInt("selectionEnd"))
    }

    private fun op(name: String) = FormatOp.valueOf(
        when (name) {
            "headingCycle" -> "HEADING_CYCLE"
            "bulletList" -> "BULLET_LIST"
            "taskList" -> "TASK_LIST"
            else -> name.uppercase()
        },
    )

    @Test fun everyVectorOnArt() {
        val failures = ArrayList<String>()
        val format = vectors.getJSONArray("format")
        for (i in 0 until format.length()) {
            val r = format.getJSONObject(i)
            val got = MarkdownFormatting.format(r.getString("text"), r.getInt("selectionStart"), r.getInt("selectionEnd"), op(r.getString("op")))
            if (got != expected(r)) failures += r.getString("id")
        }
        val typed = vectors.getJSONArray("wrapTyped")
        for (i in 0 until typed.length()) {
            val r = typed.getJSONObject(i)
            val got = MarkdownFormatting.wrapTyped(r.getString("text"), r.getInt("selectionStart"), r.getInt("selectionEnd"), r.getString("typed"))
            if (got != expected(r)) failures += r.getString("id")
        }
        assertEquals(137, format.length() + typed.length())
        assertTrue(failures.joinToString(), failures.isEmpty())
    }
}
