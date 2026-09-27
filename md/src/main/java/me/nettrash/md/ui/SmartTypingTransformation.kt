/*
 * SmartTypingTransformation.kt
 * md (Android)
 *
 * The Compose shell around Typing.kt: the `InputTransformation` the editor's
 * `BasicTextField(state = …)` runs after every user edit — soft keyboard,
 * hardware keyboard, IME composition updates, glide, paste — and the
 * `TextFieldState` it edits, kept in step with `DocumentViewModel.text`.
 *
 * Why a transformation and not a key handler: a soft-keyboard Return never
 * arrives as a `KeyEvent` (it is `commitText("\n")`), and a composed word
 * arrives as a series of replacements over one region. The transformation
 * sees each as a change list against the text before the edit — exactly the
 * inputs `SmartTyping.enter` / `SmartTyping.capitalize` take — and rewrites
 * the buffer in place, atomically with the keystroke: one undo step, no
 * second edit for the IME to see. Composition is NOT a reason to skip:
 * Gboard re-sends "h", then "he", over the same region and the rule is
 * idempotent under that (§3.3). `TextFieldValue.composition` is never touched;
 * rewriting composed text through it restarts IMEs.
 *
 * The one platform difference §3.5 records: the capital is atomic with the
 * keystroke here, so Undo removes the letter too — and Undo runs no
 * transformation at all (Compose applies it with no side effects), so the
 * tracked capital of §3.4 (Typing.kt) learns of it from the text at the next
 * edit: gone from its offset, it counts as deleted, and the letter typed
 * there again stays lowercase, as after a backspace.
 */

package me.nettrash.md.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.text.input.InputTransformation
import androidx.compose.foundation.text.input.TextFieldBuffer
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.TextRange
import me.nettrash.md.DocumentViewModel

/**
 * SPEC §3.3, Android. Reads [settings] live — at every edit, not at
 * construction — so a menu toggle applies to the next key; a function whose
 * setting is off is never called.
 */
@OptIn(ExperimentalFoundationApi::class)
internal class SmartTypingTransformation(
    private val settings: TypingSettings,
    private val override: TypingOverride,
) : InputTransformation {

    override fun TextFieldBuffer.transformInput() {
        val count = changes.changeCount
        if (count == 0) return
        val list = ArrayList<TypingChange>(count)
        for (i in 0 until count) {
            val before = changes.getOriginalRange(i)
            val after = changes.getRange(i)
            list += TypingChange(
                before.min, before.max,
                asCharSequence().subSequence(after.min, after.max).toString(),
            )
        }
        val selection0 = originalSelection
        val original = originalText.toString()
        val plan = Typing.plan(
            original, selection0.min, selection0.max, list,
            settings.continueLists, settings.capitalizeSentences, override,
        )
        when (plan) {
            is TypingPlan.Enter -> {
                // `enter` addresses the ORIGINAL text (§0.9), so the platform's
                // own newline is taken back out first; the buffer then holds
                // exactly the one replacement the function asked for.
                //
                // §3.5: every enter edit is one undo step. Compose's undo
                // manager records an edit by its kind — insertion, deletion,
                // replacement — and merges contiguous insertions, and
                // same-direction deletions, made within five seconds into one
                // step, exempting only a bare "\n"; a replacement is never
                // merged. Applied as the function states it, a continuation
                // ("\n- " inserted at the caret) would be absorbed into the
                // typing before and after it, and one Undo would take the
                // item away with the continuation. So the edit is widened by
                // the scalar before it, put back unchanged: the same text,
                // recorded as a replacement. (An empty item's marker removal
                // is widened the same way — a deletion would merge with a
                // backspace run — except on line 0, where nothing precedes it.)
                val e = plan.edit
                revertAllChanges()
                var start = e.location
                var replacement = e.replacement
                if (start > 0) {
                    val pair = start >= 2 && Character.isLowSurrogate(original[start - 1]) &&
                        Character.isHighSurrogate(original[start - 2])
                    start -= if (pair) 2 else 1
                    replacement = original.substring(start, e.location) + replacement
                }
                replace(start, e.location + e.length, replacement)
                selection = TextRange(e.caret)
            }
            is TypingPlan.Capital -> {
                // The word already sits in the buffer at the change's new range;
                // only its first scalar changes, and the selection — after it —
                // follows the replacement by itself.
                replace(plan.at, plan.at + plan.length, plan.upper)
            }
            null -> Unit
        }
    }
}

/**
 * The editor's buffer — the `TextFieldState` the text field edits — with its
 * smart-typing hooks. One per screen, created by [rememberEditorBuffer], so
 * switching Edit / Split keeps the caret where it was.
 */
@OptIn(ExperimentalFoundationApi::class)
internal class EditorBuffer(initialText: String, settings: TypingSettings) {

    val state = TextFieldState(initialText, TextRange.Zero)
    val override = TypingOverride()
    val transformation = SmartTypingTransformation(settings, override)

    /** The `DocumentViewModel.documentToken` whose text [state] holds. */
    var loadedToken: Long = -1L

    /**
     * Bumped every time the selection was moved (or rewritten) from outside
     * the field and has to be brought on screen — the find bar's Next,
     * Previous, Replace and Replace All. The editor pane watches it and
     * scrolls its own scroll container, the one the Split sync drives (see
     * `EditorScreen.EditorPane`); the buffer itself knows nothing about
     * geometry. Compose state, so the pane hears about it by recomposing.
     */
    var revealToken by mutableIntStateOf(0)
        private set

    /** Ask the editor pane to scroll the selection on screen. */
    fun requestReveal() {
        revealToken++
    }

    /**
     * An external replacement — New, Open, a book article, shared-in text —
     * as one edit: the caret clamped into the new text, the old document's
     * undo history dropped (it must never replay into the new one), and the
     * override cleared (§3.4).
     */
    fun replaceDocument(text: String) {
        state.edit {
            val caret = selection.min.coerceIn(0, text.length)
            replace(0, length, text)
            selection = TextRange(caret)
        }
        state.undoState.clearHistory()
        override.clear()
    }

    /**
     * Select `[start, end)` and bring it on screen — the find bar landing on
     * a match. A selection change is not an edit: no text moves, so nothing
     * is dirtied, no undo step is recorded and the tracked capital of §3.4
     * has nothing to hear about.
     */
    fun select(start: Int, end: Int) {
        val limit = state.text.length
        state.edit { selection = TextRange(start.coerceIn(0, limit), end.coerceIn(0, limit)) }
        requestReveal()
    }

    /**
     * One edit made from outside the field — the find bar's Replace and
     * Replace All: `[start, end)` becomes [replacement], with the caret left
     * at [caret].
     *
     * Applied through `TextFieldState.edit`, which records a programmatic
     * edit as ONE undo step and never merges it with the typing around it
     * (that is what makes Replace All a single Undo). No transformation runs
     * on it — Compose runs those for user input only — so the smart-typing
     * machine is told about it here, against the text as it was, exactly as
     * the Shift+Return path below does. The dirty flag and the autosave
     * follow from the state change itself (see [rememberEditorBuffer]).
     */
    fun applyExternalEdit(start: Int, end: Int, replacement: String, caret: Int) {
        val before = state.text.toString()
        val selection0 = state.selection
        state.edit {
            replace(start, end, replacement)
            selection = TextRange(caret.coerceIn(0, length))
        }
        override.edited(before, start, end, replacement, selection0.min, selection0.max)
        requestReveal()
    }

    /**
     * §3.6: a hardware Shift+Return inserts a plain newline, bypassing
     * `enter`. Seen in the preview pass, before the field's own handler, which
     * would otherwise feed the "\n" through the transformation. A soft
     * keyboard has no Shift+Return; its Return is `commitText("\n")`.
     */
    fun onPreviewKeyEvent(event: KeyEvent): Boolean {
        if (event.type != KeyEventType.KeyDown || !event.isShiftPressed) return false
        if (event.key != Key.Enter && event.key != Key.NumPadEnter) return false
        // A programmatic edit runs no transformation, so the machine is told
        // about it here, against the text as it was.
        val before = state.text.toString()
        val s = state.selection
        state.edit {
            replace(s.min, s.max, "\n")
            selection = TextRange(s.min + 1)
        }
        override.edited(before, s.min, s.max, "\n", s.min, s.max)
        return true
    }
}

/**
 * The editor buffer for [viewModel], kept in step both ways:
 *
 *  - every edit of the field's state is relayed to `DocumentViewModel
 *    .onTextChange` (dirty flag, autosave, the preview and the outline), but
 *    only while the state still holds the document the model shows — a
 *    keystroke that lands in the same frame as an async load must not be
 *    written over the newly loaded text;
 *  - every document replacement (`documentToken` bumped) is written into the
 *    state as one edit, through [EditorBuffer.replaceDocument].
 *
 * Disposal flushes the state to the model once more, synchronously, so a
 * keystroke whose relay was still queued when the screen went away is not
 * lost.
 */
@Composable
internal fun rememberEditorBuffer(viewModel: DocumentViewModel, settings: TypingSettings): EditorBuffer {
    val editor = remember {
        EditorBuffer(viewModel.text, settings).also { it.loadedToken = viewModel.documentToken }
    }
    LaunchedEffect(editor) {
        snapshotFlow { editor.state.text.toString() }.collect { text ->
            if (viewModel.documentToken == editor.loadedToken) viewModel.onTextChange(text)
        }
    }
    LaunchedEffect(viewModel.documentToken) {
        val token = viewModel.documentToken
        if (token == editor.loadedToken) return@LaunchedEffect
        editor.replaceDocument(viewModel.text)
        editor.loadedToken = token
    }
    DisposableEffect(editor) {
        onDispose {
            if (viewModel.documentToken == editor.loadedToken) {
                viewModel.onTextChange(editor.state.text.toString())
            }
        }
    }
    return editor
}
