/*
 * TypingInstrumentedTest.kt
 * md (Android)
 *
 * The smart-typing settings on a real SharedPreferences, and the pure adapter
 * helpers (ui/Typing.kt) on ART — whose Unicode tables, not the desktop JVM's,
 * decide which scalar is a Lowercase letter and what its capital is.
 *
 * Run with (emulator only; see the workspace rules):
 *   adb -s emulator-5554 shell am instrument -w \
 *     -e class me.nettrash.md.ui.TypingInstrumentedTest \
 *     me.nettrash.md.test/androidx.test.runner.AndroidJUnitRunner
 */

package me.nettrash.md.ui

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TypingInstrumentedTest {

    private val context: Context get() = InstrumentationRegistry.getInstrumentation().targetContext

    private val prefs get() = context.getSharedPreferences(TypingSettings.PREFS, Context.MODE_PRIVATE)

    // The app's own preferences: leave them exactly as they were found.
    private var savedContinue: Boolean? = null
    private var savedCapitalize: Boolean? = null

    @Before fun stash() {
        val p = prefs
        savedContinue = if (p.contains(TypingSettings.CONTINUE_LISTS_KEY)) p.getBoolean(TypingSettings.CONTINUE_LISTS_KEY, true) else null
        savedCapitalize = if (p.contains(TypingSettings.CAPITALIZE_SENTENCES_KEY)) p.getBoolean(TypingSettings.CAPITALIZE_SENTENCES_KEY, true) else null
        p.edit().remove(TypingSettings.CONTINUE_LISTS_KEY).remove(TypingSettings.CAPITALIZE_SENTENCES_KEY).commit()
    }

    @After fun restore() {
        val e = prefs.edit().remove(TypingSettings.CONTINUE_LISTS_KEY).remove(TypingSettings.CAPITALIZE_SENTENCES_KEY)
        savedContinue?.let { e.putBoolean(TypingSettings.CONTINUE_LISTS_KEY, it) }
        savedCapitalize?.let { e.putBoolean(TypingSettings.CAPITALIZE_SENTENCES_KEY, it) }
        e.commit()
    }

    @Test fun bothSettingsDefaultOnAndPersistUnderTheAgreedKeys() {
        // §3.1: the keys, verbatim, in the file the view-mode memory uses.
        assertEquals("md.continueLists", TypingSettings.CONTINUE_LISTS_KEY)
        assertEquals("md.capitalizeSentences", TypingSettings.CAPITALIZE_SENTENCES_KEY)
        assertEquals("view", TypingSettings.PREFS)

        val fresh = TypingSettings(context)
        assertTrue(fresh.continueLists)
        assertTrue(fresh.capitalizeSentences)

        fresh.chooseContinueLists(false)
        assertFalse(fresh.continueLists)
        assertTrue(fresh.capitalizeSentences)
        // Written under the raw key, as a boolean, at once.
        assertTrue(prefs.contains("md.continueLists"))
        assertFalse(prefs.getBoolean("md.continueLists", true))
        assertFalse(prefs.contains("md.capitalizeSentences"))

        // A second instance — the next launch — reads it back.
        val reopened = TypingSettings(context)
        assertFalse(reopened.continueLists)
        assertTrue(reopened.capitalizeSentences)

        reopened.chooseCapitalizeSentences(false)
        reopened.chooseContinueLists(true)
        assertFalse(prefs.getBoolean("md.capitalizeSentences", true))
        assertTrue(prefs.getBoolean("md.continueLists", false))
        val third = TypingSettings(context)
        assertTrue(third.continueLists)
        assertFalse(third.capitalizeSentences)
    }

    @Test fun theWordRuleReadsArtsUnicodeTables() {
        // §0.4 on the device: Ll by Character.getType(Int), surrogates decoded.
        assertTrue(Typing.isWordInsertion("hello "))
        assertTrue(Typing.isWordInsertion("école"))
        assertTrue(Typing.isWordInsertion("\uD801\uDC28x"))   // U+10428 Deseret small long I
        assertFalse(Typing.isWordInsertion("\uD801\uDC00x"))  // U+10400 the capital
        assertFalse(Typing.isWordInsertion("\uD801"))         // a lone surrogate
        assertFalse(Typing.isWordInsertion("ǅ"))              // titlecase
        assertFalse(Typing.isWordInsertion("ª"))         // ª is Lo
        assertFalse(Typing.isWordInsertion("hello\u00a0"))    // NBSP is WS19, not the one trailing SP
        assertFalse(Typing.isWordInsertion("https://a.b"))
    }

    @Test fun theOverrideReadsCapitalsByScalarOnArt() {
        val o = TypingOverride()
        // Select "É", type "é": as typed, and armed — Character.toUpperCase(Int) on ART.
        assertTrue(o.insertsAsTyped("École", 0, 1, "é", 0, 1, 'é'.code))
        assertTrue(o.isArmed)
        assertEquals(0, o.armedAt)
        // U+10400 over two units, U+10428 typed over it.
        val p = TypingOverride()
        assertTrue(p.insertsAsTyped("\uD801\uDC00x", 0, 2, "\uD801\uDC28", 2, 0, 0x10428))
        // The echo after a fresh capital keeps the capitalize path; p stays.
        val q = TypingOverride()
        q.produced(0, 'É'.code)
        assertFalse(q.insertsAsTyped("É", 0, 1, "éc", 1, 1, 'é'.code))
        assertEquals(0, q.capital)
        assertFalse(q.isArmed)
        // Deleting it arms at p; the letter typed there again is as typed.
        q.edited("É", 0, 1, "", 1, 1)
        assertEquals(0, q.armedAt)
        assertTrue(q.insertsAsTyped("", 0, 0, "é", 0, 0, 'é'.code))
        // Undo took it with no hook run: learnt from the text.
        val r = TypingOverride()
        r.produced(0, 'É'.code)
        assertTrue(r.insertsAsTyped("", 0, 0, "é", 0, 0, 'é'.code))
    }
}
