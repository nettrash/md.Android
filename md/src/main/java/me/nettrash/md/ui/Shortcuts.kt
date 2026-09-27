/*
 * Shortcuts.kt
 * md (Android)
 *
 * The hardware-keyboard chords, written down once.
 *
 * md answers to the same chords on every port, and the port that spells them
 * out in full is the Windows one: `md.win/src/Md.App.Logic/Commands/
 * CommandTable.cs` is the single table its menus, its accelerators and its
 * enablement are all built from. The rows below are that table's chords,
 * copied verbatim for the commands an Android window can reach — key and
 * *Windows* modifiers, so a diff between the two is a diff between two lists
 * of the same shape rather than a translation argument. The iPad's copy is
 * `md/EditorShortcuts.swift`, the same table again.
 *
 * The translation is the one rule the family follows. On Apple, Windows Ctrl
 * becomes ⌘ and Windows Alt becomes ⌃; on Android there is nothing to
 * translate — Ctrl is Ctrl, Alt is Alt, Shift is Shift — which is why the
 * rows below are read as written.
 *
 * What is NOT here matters as much as what is: Ctrl+A, Ctrl+Z, Ctrl+Y,
 * Ctrl+X, Ctrl+C and Ctrl+V are §2.4's rows that md.win shows in its Edit
 * menu and deliberately never registers on the window root, because the
 * focused control owns them. There is no row for them here either, so
 * [Shortcuts.resolve] answers null and the editor's text field keeps its own
 * editing keys.
 *
 * One row of this table is not free the way it is on md.win, and the table
 * cannot say so on its own: on Android the focused text field claims
 * Ctrl+Alt+Up and Ctrl+Alt+Down before anything outside it is asked. Compose
 * Foundation's Android key mapping (`KeyMapping.android.kt`) reads Alt+Up as
 * Home and Alt+Down as End and never looks at Ctrl, and its platform map is
 * tried ahead of the common one whose Ctrl branch would have let the press
 * through. So Previous / Next Article must be taken in the composition's
 * preview pass — `EditorScreen`'s Scaffold modifier, which runs on the way
 * down to the focused node — and not only in the Activity's leftovers
 * handler, which by definition never sees a press the field wanted.
 * `ChordFocusInstrumentedTest` is what holds that down.
 *
 * Plain Kotlin, no Compose: the table and the resolution are what a test can
 * pin, and `EditorScreen` does nothing but translate a Compose `KeyEvent`
 * into these three booleans and a [Shortcuts.Key] and run what comes back.
 */

package me.nettrash.md.ui

/**
 * One command a chord can run. Exactly the commands an Android window
 * offers — the Windows table is larger (Close, Zen Mode, Full Screen, the
 * Find Next / Replace rows the system find panel owns on Apple …), and the
 * commands Android has no surface for are simply not rows here.
 */
internal enum class ShortcutAction {
    /** Ctrl+N — a fresh untitled document. */
    NEW,

    /** Ctrl+O — the document picker. */
    OPEN,

    /** Ctrl+S — save, falling through to Save As when there is nowhere to save. */
    SAVE,

    /** Ctrl+Shift+S — the Create Document picker. */
    SAVE_AS,

    /** Ctrl+P — print the rendered document. */
    PRINT,

    /** Ctrl+1 / Ctrl+2 / Ctrl+3 — the three layouts. */
    VIEW_EDIT,
    VIEW_SPLIT,
    VIEW_PREVIEW,

    /** Ctrl+Shift+B — bring the book navigator up. */
    SHOW_BOOK,

    /** Ctrl+F — the find bar. */
    FIND,

    /** Ctrl+Alt+Up / Ctrl+Alt+Down — step through the book in reading order. */
    PREVIOUS_ARTICLE,
    NEXT_ARTICLE,
}

/**
 * The key a chord sits on, named the way `VirtualKeys` names it in md.win so
 * the two tables read the same. Only the keys md's own chords use: anything
 * else never reaches [Shortcuts.resolve].
 */
internal enum class ShortcutKey {
    NUMBER_1,
    NUMBER_2,
    NUMBER_3,
    B,
    F,
    N,
    O,
    P,
    S,
    UP,
    DOWN,
}

/**
 * A chord's modifiers, as Windows writes them. Kept in that spelling on
 * purpose: this is the copy of the shared table, and a copy that had already
 * been translated could not be diffed against its source.
 */
internal data class ShortcutModifiers(
    val ctrl: Boolean = false,
    val shift: Boolean = false,
    val alt: Boolean = false,
)

/** One row: a key and the modifiers held with it. */
internal data class Chord(val key: ShortcutKey, val modifiers: ShortcutModifiers)

internal object Shortcuts {

    private val CTRL = ShortcutModifiers(ctrl = true)
    private val CTRL_SHIFT = ShortcutModifiers(ctrl = true, shift = true)
    private val CTRL_ALT = ShortcutModifiers(ctrl = true, alt = true)

    /**
     * The table. Every value is CommandTable.cs's, unchanged:
     *
     *     New               Chord(VirtualKeys.N,       Ctrl)
     *     Open              Chord(VirtualKeys.O,       Ctrl)
     *     Save              Chord(VirtualKeys.S,       Ctrl)
     *     SaveAs            Chord(VirtualKeys.S,       CtrlShift)
     *     Print             Chord(VirtualKeys.P,       Ctrl)
     *     ViewEdit          Chord(VirtualKeys.Number1, Ctrl)
     *     ViewSplit         Chord(VirtualKeys.Number2, Ctrl)
     *     ViewPreview       Chord(VirtualKeys.Number3, Ctrl)
     *     ShowBook          Chord(VirtualKeys.B,       CtrlShift)
     *     Find              Chord(VirtualKeys.F,       Ctrl)
     *     PreviousArticle   Chord(VirtualKeys.Up,      CtrlAlt)
     *     NextArticle       Chord(VirtualKeys.Down,    CtrlAlt)
     */
    val table: Map<ShortcutAction, Chord> = mapOf(
        ShortcutAction.NEW to Chord(ShortcutKey.N, CTRL),
        ShortcutAction.OPEN to Chord(ShortcutKey.O, CTRL),
        ShortcutAction.SAVE to Chord(ShortcutKey.S, CTRL),
        ShortcutAction.SAVE_AS to Chord(ShortcutKey.S, CTRL_SHIFT),
        ShortcutAction.PRINT to Chord(ShortcutKey.P, CTRL),
        ShortcutAction.VIEW_EDIT to Chord(ShortcutKey.NUMBER_1, CTRL),
        ShortcutAction.VIEW_SPLIT to Chord(ShortcutKey.NUMBER_2, CTRL),
        ShortcutAction.VIEW_PREVIEW to Chord(ShortcutKey.NUMBER_3, CTRL),
        ShortcutAction.SHOW_BOOK to Chord(ShortcutKey.B, CTRL_SHIFT),
        ShortcutAction.FIND to Chord(ShortcutKey.F, CTRL),
        ShortcutAction.PREVIOUS_ARTICLE to Chord(ShortcutKey.UP, CTRL_ALT),
        ShortcutAction.NEXT_ARTICLE to Chord(ShortcutKey.DOWN, CTRL_ALT),
    )

    /** The chord for [action]. The table is complete by construction and a
     *  test says so, so the lookup is allowed to be total. */
    fun chord(action: ShortcutAction): Chord =
        table[action] ?: error("No chord for $action")

    /**
     * The command this key press runs, or null when it runs none.
     *
     * The modifiers are matched **exactly**: Ctrl+Shift+N is not New, and a
     * bare Ctrl+B is not Show Book, because a chord md does not claim must
     * reach the field (or the system) unchanged. The caller has already
     * refused a press with Meta held and anything but a key-down.
     *
     * [isWide] is the window's own answer to `availableModes` (ViewMode.kt):
     * Split is not on offer on a narrow window, so Ctrl+2 runs nothing there
     * rather than putting the writer in a pane the switch cannot show.
     */
    fun resolve(key: ShortcutKey?, ctrl: Boolean, shift: Boolean, alt: Boolean, isWide: Boolean): ShortcutAction? {
        key ?: return null
        val pressed = ShortcutModifiers(ctrl = ctrl, shift = shift, alt = alt)
        val action = table.entries.firstOrNull { (_, chord) ->
            chord.key == key && chord.modifiers == pressed
        }?.key ?: return null
        if (action == ShortcutAction.VIEW_SPLIT && !isWide) return null
        return action
    }
}
