/*
 * ShortcutsTest.kt
 * md (Android)
 *
 * JVM unit tests for the hardware-keyboard chord table (`ui/Shortcuts.kt`).
 *
 * The first test is the one that matters across the family: the twelve tuples
 * written out by hand, exactly as `md.win/src/Md.App.Logic/Commands/
 * CommandTable.cs` declares them and as `md/EditorShortcutsTests.swift` pins
 * them on iOS. A wrong row fails here rather than shipping as a chord that
 * does the wrong thing on one platform only.
 *
 * The rest is the resolution: modifiers are matched exactly, the keys the
 * text field owns are claimed by nobody, and Ctrl+2 runs nothing on a window
 * too narrow to show Split.
 */

package me.nettrash.md.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ShortcutsTest {

    private val ctrl = ShortcutModifiers(ctrl = true)
    private val ctrlShift = ShortcutModifiers(ctrl = true, shift = true)
    private val ctrlAlt = ShortcutModifiers(ctrl = true, alt = true)

    /** CommandTable.cs, row for row. */
    private val windowsTable: Map<ShortcutAction, Chord> = mapOf(
        ShortcutAction.NEW to Chord(ShortcutKey.N, ctrl),
        ShortcutAction.OPEN to Chord(ShortcutKey.O, ctrl),
        ShortcutAction.SAVE to Chord(ShortcutKey.S, ctrl),
        ShortcutAction.SAVE_AS to Chord(ShortcutKey.S, ctrlShift),
        ShortcutAction.PRINT to Chord(ShortcutKey.P, ctrl),
        ShortcutAction.VIEW_EDIT to Chord(ShortcutKey.NUMBER_1, ctrl),
        ShortcutAction.VIEW_SPLIT to Chord(ShortcutKey.NUMBER_2, ctrl),
        ShortcutAction.VIEW_PREVIEW to Chord(ShortcutKey.NUMBER_3, ctrl),
        ShortcutAction.SHOW_BOOK to Chord(ShortcutKey.B, ctrlShift),
        ShortcutAction.FIND to Chord(ShortcutKey.F, ctrl),
        ShortcutAction.PREVIOUS_ARTICLE to Chord(ShortcutKey.UP, ctrlAlt),
        ShortcutAction.NEXT_ARTICLE to Chord(ShortcutKey.DOWN, ctrlAlt),
    )

    @Test fun everyChordIsTheWindowsTablesOwn() {
        assertEquals(windowsTable, Shortcuts.table)
    }

    @Test fun everyActionHasARow() {
        ShortcutAction.entries.forEach { action ->
            assertEquals(windowsTable.getValue(action), Shortcuts.chord(action))
        }
    }

    @Test fun everyChordIsClaimedByExactlyOneCommand() {
        assertEquals(Shortcuts.table.size, Shortcuts.table.values.toSet().size)
    }

    // ---- Resolution ------------------------------------------------------

    @Test fun everyChordResolvesBackToItsCommand() {
        Shortcuts.table.forEach { (action, chord) ->
            val resolved = Shortcuts.resolve(
                chord.key, chord.modifiers.ctrl, chord.modifiers.shift, chord.modifiers.alt,
                isWide = true,
            )
            assertEquals(action, resolved)
        }
    }

    @Test fun modifiersAreMatchedExactly() {
        // Ctrl+S and Ctrl+Shift+S are two different commands, and the one
        // with Alt held is neither.
        assertEquals(ShortcutAction.SAVE, Shortcuts.resolve(ShortcutKey.S, ctrl = true, shift = false, alt = false, isWide = true))
        assertEquals(ShortcutAction.SAVE_AS, Shortcuts.resolve(ShortcutKey.S, ctrl = true, shift = true, alt = false, isWide = true))
        assertNull(Shortcuts.resolve(ShortcutKey.S, ctrl = true, shift = false, alt = true, isWide = true))
        assertNull(Shortcuts.resolve(ShortcutKey.S, ctrl = false, shift = false, alt = false, isWide = true))

        // A bare Ctrl+B is not Show Book, and Ctrl+Shift+N is not New.
        assertNull(Shortcuts.resolve(ShortcutKey.B, ctrl = true, shift = false, alt = false, isWide = true))
        assertNull(Shortcuts.resolve(ShortcutKey.N, ctrl = true, shift = true, alt = false, isWide = true))

        // The arrows are only the article chords with BOTH Ctrl and Alt held;
        // plain arrows and Ctrl+arrows belong to the text field.
        assertEquals(ShortcutAction.PREVIOUS_ARTICLE, Shortcuts.resolve(ShortcutKey.UP, ctrl = true, shift = false, alt = true, isWide = true))
        assertNull(Shortcuts.resolve(ShortcutKey.UP, ctrl = false, shift = false, alt = false, isWide = true))
        assertNull(Shortcuts.resolve(ShortcutKey.DOWN, ctrl = true, shift = false, alt = false, isWide = true))
    }

    @Test fun aKeyWithNoChordResolvesToNothing() {
        assertNull(Shortcuts.resolve(null, ctrl = true, shift = false, alt = false, isWide = true))
    }

    /**
     * §2.4's rows the focused control keeps: Ctrl+A, Ctrl+Z, Ctrl+Y, Ctrl+X,
     * Ctrl+C and Ctrl+V. md.win shows them in its Edit menu and never
     * registers them on the window root; here they are not rows at all, and
     * the keys they sit on are not even spellable — which is what keeps
     * select-all, undo, redo, cut, copy and paste working in the editor.
     */
    @Test fun noChordClaimsAKeyTheTextFieldOwns() {
        val owned = setOf("A", "Z", "Y", "X", "C", "V")
        assertTrue(Shortcuts.table.values.none { it.key.name in owned })
        assertTrue(ShortcutKey.entries.none { it.name in owned })
    }

    /**
     * The two rows the Android editor's own text field would otherwise take.
     * Compose Foundation maps Alt+Up / Alt+Down to Home / End and never looks
     * at Ctrl, so — unlike md.win, where the window root simply gets them —
     * these two have to be claimed in the composition's preview pass, ahead
     * of the focused field (`EditorScreen`'s Scaffold modifier; the press is
     * proven to arrive by `ChordFocusInstrumentedTest`). The table itself is
     * unchanged by that, and this says so.
     */
    @Test fun theArticleStepsAreCtrlAltUpAndCtrlAltDown() {
        assertEquals(Chord(ShortcutKey.UP, ctrlAlt), Shortcuts.chord(ShortcutAction.PREVIOUS_ARTICLE))
        assertEquals(Chord(ShortcutKey.DOWN, ctrlAlt), Shortcuts.chord(ShortcutAction.NEXT_ARTICLE))
        assertEquals(
            ShortcutAction.PREVIOUS_ARTICLE,
            Shortcuts.resolve(ShortcutKey.UP, ctrl = true, shift = false, alt = true, isWide = false),
        )
        assertEquals(
            ShortcutAction.NEXT_ARTICLE,
            Shortcuts.resolve(ShortcutKey.DOWN, ctrl = true, shift = false, alt = true, isWide = false),
        )
        // Alt alone is the field's Home / End and md claims no row for it, so
        // the preview pass must hand those two straight back.
        assertNull(Shortcuts.resolve(ShortcutKey.UP, ctrl = false, shift = false, alt = true, isWide = true))
        assertNull(Shortcuts.resolve(ShortcutKey.DOWN, ctrl = false, shift = false, alt = true, isWide = true))
        // ...and so is Ctrl+Shift+Alt+Arrow, which selects to Home / End.
        assertNull(Shortcuts.resolve(ShortcutKey.UP, ctrl = true, shift = true, alt = true, isWide = true))
        assertNull(Shortcuts.resolve(ShortcutKey.DOWN, ctrl = true, shift = true, alt = true, isWide = true))
    }

    /**
     * A narrow window offers Edit and Preview only (`availableModes`), so
     * Ctrl+2 runs nothing there rather than putting the writer in a pane the
     * switch cannot show. The other two layouts answer at every width.
     */
    @Test fun splitIsNotReachableOnANarrowWindow() {
        assertEquals(
            ShortcutAction.VIEW_SPLIT,
            Shortcuts.resolve(ShortcutKey.NUMBER_2, ctrl = true, shift = false, alt = false, isWide = true),
        )
        assertNull(Shortcuts.resolve(ShortcutKey.NUMBER_2, ctrl = true, shift = false, alt = false, isWide = false))

        listOf(ShortcutKey.NUMBER_1 to ShortcutAction.VIEW_EDIT, ShortcutKey.NUMBER_3 to ShortcutAction.VIEW_PREVIEW)
            .forEach { (key, action) ->
                assertEquals(action, Shortcuts.resolve(key, ctrl = true, shift = false, alt = false, isWide = false))
            }
    }

    /** Everything else a narrow window can do, it can still do from the
     *  keyboard: only Split is width-gated. */
    @Test fun aNarrowWindowKeepsEveryOtherChord() {
        Shortcuts.table.filterKeys { it != ShortcutAction.VIEW_SPLIT }.forEach { (action, chord) ->
            assertEquals(
                action,
                Shortcuts.resolve(
                    chord.key, chord.modifiers.ctrl, chord.modifiers.shift, chord.modifiers.alt,
                    isWide = false,
                ),
            )
        }
    }
}
