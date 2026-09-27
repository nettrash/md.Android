/*
 * SmartTypingVectorsInstrumentedTest.kt
 * md (Android)
 *
 * The whole SmartTyping oracle — `typing-vectors.json`, 512 `enter` and 838
 * `capitalize` vectors — run on the device. [SmartTypingTest] runs the same
 * file on the desktop JVM, but SmartTyping asks the runtime three questions
 * the JVM does not answer for the phone:
 *
 *  1. **`Character.getType(Int)`** — which scalars are `Ll`, `Lu`, `Mn`,
 *     `So`… decides what is a letter, a mark and a symbol lead (§0.4). ART
 *     carries its own Unicode tables (ICU's), the desktop JVM its own.
 *  2. **`Character.toUpperCase(Int)`** — the simple uppercase mapping (§0.7),
 *     the same tables again.
 *  3. **Surrogate decoding** — `Character.toCodePoint` and the non-BMP
 *     vectors (`𐐨` → `𐐀`), which a `Char`-only reading would get wrong.
 *
 * The JSON is read from this test APK's own assets through the
 * instrumentation context — the app's assets hold the examples and the
 * engines, not test fixtures — with `org.json`, which is the real thing on
 * the device.
 *
 * Run with (emulator only; see the workspace rules):
 *   adb -s emulator-5554 shell am instrument -w \
 *     -e class me.nettrash.md.markdown.SmartTypingVectorsInstrumentedTest \
 *     me.nettrash.md.test/androidx.test.runner.AndroidJUnitRunner
 */

package me.nettrash.md.markdown

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

private const val TAG = "mdSmartTyping"

@RunWith(AndroidJUnit4::class)
class SmartTypingVectorsInstrumentedTest {

    private val vectors: JSONObject by lazy {
        val assets = InstrumentationRegistry.getInstrumentation().context.assets
        JSONObject(assets.open("typing-vectors.json").bufferedReader(Charsets.UTF_8).use { it.readText() })
    }

    /** A string spelt the way the JSON spells it, so a failure shows every invisible unit. */
    private fun show(s: String?): String {
        if (s == null) return "null"
        val out = StringBuilder("\"")
        for (ch in s) {
            val u = ch.code
            when {
                u == 0x22 -> out.append("\\\"")
                u == 0x5c -> out.append("\\\\")
                u == 0x0a -> out.append("\\n")
                u in 0x20..0x7e -> out.append(ch)
                else -> out.append("\\u").append(String.format("%04x", u))
            }
        }
        return out.append('"').toString()
    }

    private fun show(e: SmartTyping.EnterEdit?): String =
        if (e == null) "null"
        else "{location=${e.location}, length=${e.length}, replacement=${show(e.replacement)}, caret=${e.caret}}"

    @Test fun everyEnterVectorMatchesOnTheDevice() {
        val recorded = vectors.getJSONArray("enter")
        assertEquals(vectors.getJSONObject("_meta").getJSONObject("counts").getInt("enter"), recorded.length())
        assertEquals(512, recorded.length())
        val failures = ArrayList<String>()
        for (i in 0 until recorded.length()) {
            val row = recorded.getJSONObject(i)
            val id = row.getString("id")
            val want = if (row.isNull("expected")) null else row.getJSONObject("expected").let {
                SmartTyping.EnterEdit(it.getInt("location"), it.getInt("length"), it.getString("replacement"), it.getInt("caret"))
            }
            val got = SmartTyping.enter(row.getString("text"), row.getInt("start"), row.getInt("end"))
            if (got != want) failures.add("$id: expected ${show(want)}, actual ${show(got)}")
        }
        Log.i(TAG, "enter: ${recorded.length() - failures.size} of ${recorded.length()} vectors match")
        assertTrue(
            "${failures.size} of ${recorded.length()} enter vectors failed on the device:\n" + failures.joinToString("\n"),
            failures.isEmpty(),
        )
    }

    @Test fun everyCapitalizeVectorMatchesOnTheDevice() {
        val recorded = vectors.getJSONArray("capitalize")
        assertEquals(vectors.getJSONObject("_meta").getJSONObject("counts").getInt("capitalize"), recorded.length())
        assertEquals(838, recorded.length())
        val failures = ArrayList<String>()
        for (i in 0 until recorded.length()) {
            val row = recorded.getJSONObject(i)
            val id = row.getString("id")
            val want = if (row.isNull("expected")) null else row.getString("expected")
            val got = SmartTyping.capitalize(
                row.getString("text"), row.getInt("start"), row.getInt("end"), row.getString("typed"),
            )
            if (got != want) failures.add("$id: expected ${show(want)}, actual ${show(got)}")
        }
        Log.i(TAG, "capitalize: ${recorded.length() - failures.size} of ${recorded.length()} vectors match")
        assertTrue(
            "${failures.size} of ${recorded.length()} capitalize vectors failed on the device:\n" + failures.joinToString("\n"),
            failures.isEmpty(),
        )
    }

    @Test fun theLoneSurrogateCasesTheJsonCannotCarry() {
        // §4.2: no unpaired surrogate escape may be in the file, so these are
        // native — and they are exactly the ones ART's tables answer.
        assertEquals(null, SmartTyping.capitalize("", 0, 0, "\uD800"))
        assertEquals(null, SmartTyping.capitalize("Hello. ", 7, 7, "\uDC00"))
        assertEquals(null, SmartTyping.capitalize("\uDC00", 1, 1, "t"))
        assertEquals(null, SmartTyping.enter("- 😀 x", 3, 3))
        assertEquals(null, SmartTyping.capitalize("- 😀 x", 3, 3, "t"))
    }
}
