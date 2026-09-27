/*
 * DiscardGuard.kt
 * md (Android)
 *
 * The unsaved-changes guard: the rule for when replacing the open document
 * has to ask first, and the little state machine that parks the replacement
 * while it asks.
 *
 * Most documents never ask. A file opened through the picker is writable, so
 * every edit is autosaved within a beat and there is nothing to lose — Back
 * on one of those still flushes silently, as it always has, and so does
 * every other way of replacing it ([needsAutosaveFlush]). The buffers that
 * CAN be lost are the ones with nowhere to autosave to: a brand-new untitled
 * document, text shared in from another app, an imported `.textpack`, and a
 * document handed in read-only. Those were replaced without a word by New,
 * Open…, an example, or the next incoming intent.
 *
 * Everything here is free of Compose, Context and Intent — the guard is
 * generic over whatever a port calls a "replacement", which on Android is
 * `Replacement` (DocumentViewModel.kt, where the incoming variant carries an
 * `Intent`) — so `./gradlew test` drives the very state machine the screen
 * runs. The same split ViewMode.kt and Typing.kt keep.
 */

package me.nettrash.md

/** The reader's answer to the unsaved-changes dialog. */
internal enum class DiscardChoice { SAVE, DISCARD, CANCEL }

/**
 * Whether replacing the open document has to ask first: it has unsaved edits
 * AND there is no writable file behind it to autosave them into.
 *
 * Spelt from the three pieces of document state rather than read off the
 * ViewModel so the truth table is testable on the JVM; `DocumentViewModel
 * .needsDiscardPrompt` is the one caller.
 */
internal fun needsDiscardPrompt(isDirty: Boolean, hasUri: Boolean, canWrite: Boolean): Boolean =
    isDirty && !(hasUri && canWrite)

/**
 * The other half, and the thing that makes [needsDiscardPrompt] honest:
 * whether the document being replaced has edits that must be written to its
 * file before it goes.
 *
 * The rule above waves a dirty writable document through on the strength of
 * "its edits are a beat from disk" — and that beat is a debounced autosave
 * job, which every replacement path used to cancel. A New, an example or an
 * incoming document inside that second took up to a second of typing with it
 * and never asked, because by the rule there was nothing to ask about. The
 * premise is now made true rather than the question made louder:
 * `DocumentViewModel.flushPendingAutosave` writes first and cancels after.
 *
 * The two predicates partition a dirty document exactly — one of them is
 * always true and never both — so every edit is either asked about or
 * written down, and `DiscardGuardTest` pins that.
 */
internal fun needsAutosaveFlush(isDirty: Boolean, hasUri: Boolean, canWrite: Boolean): Boolean =
    isDirty && hasUri && canWrite

/**
 * One replacement, parked while the reader answers.
 *
 * The phases, and what each of them is waiting for:
 *
 *  - **Idle** — nothing pending; a replacement offered here runs at once.
 *  - **Asking** — the dialog is on screen holding [DiscardGuard.parked].
 *    Discard hands the request back to be run, Cancel drops it, Save moves
 *    on to…
 *  - **Saving** — the Save the reader asked for is in flight. On Android
 *    that is always a round trip through the Save As picker (a document with
 *    somewhere to save to is exactly a document that never asked), so the
 *    answer arrives in another callback, and the request has to survive
 *    until it does. A save that lands releases the request; a picker the
 *    reader dismissed drops it, because the edits are still unsaved and
 *    replacing the document would lose the very thing they asked to keep.
 *
 * There is only ever one document on screen, so there is only ever one parked
 * request: a second offer supersedes the first in either waiting phase rather
 * than queueing behind it. A stray answer (a Save As picker returning with no
 * replacement waiting on it — the ordinary Save As case) finds the guard Idle
 * and is ignored.
 *
 * The guard holds no Compose state of its own: `DocumentViewModel` mirrors
 * [isAsking] into an observable property after every transition, so the
 * dialog follows without this file importing the runtime.
 */
internal class DiscardGuard<T : Any> {

    sealed interface Phase<out T> {
        data object Idle : Phase<Nothing>
        data class Asking<T>(val request: T) : Phase<T>
        data class Saving<T>(val request: T) : Phase<T>
    }

    var phase: Phase<T> = Phase.Idle
        private set

    /** The replacement waiting behind the dialog, or null when none is. */
    val parked: T?
        get() = when (val current = phase) {
            is Phase.Asking -> current.request
            is Phase.Saving -> current.request
            Phase.Idle -> null
        }

    /** Whether the unsaved-changes dialog should be on screen. */
    val isAsking: Boolean
        get() = phase is Phase.Asking

    /**
     * Offer [request]. Returns it when the caller must run it now, or null
     * when it has been parked behind the dialog.
     *
     * [needsPrompt] is the *document's* answer ([needsDiscardPrompt]), not the
     * request's: a document with nothing to lose replaces itself immediately,
     * whatever is replacing it. A false [needsPrompt] also clears anything
     * already parked — the document just became safe to replace, so the
     * question the dialog was asking no longer has an answer worth waiting for.
     */
    fun submit(request: T, needsPrompt: Boolean): T? {
        if (!needsPrompt) {
            phase = Phase.Idle
            return request
        }
        phase = Phase.Asking(request)
        return null
    }

    /**
     * Answer the dialog. Returns the parked replacement when it is to run
     * now (Discard), or null — Cancel drops it, and Save keeps it until
     * [saveFinished] says how the save went. An answer arriving with no
     * dialog up changes nothing.
     */
    fun choose(choice: DiscardChoice): T? {
        val asking = phase as? Phase.Asking ?: return null
        return when (choice) {
            DiscardChoice.DISCARD -> {
                phase = Phase.Idle
                asking.request
            }
            DiscardChoice.CANCEL -> {
                phase = Phase.Idle
                null
            }
            DiscardChoice.SAVE -> {
                phase = Phase.Saving(asking.request)
                null
            }
        }
    }

    /**
     * The save the reader asked for landed ([saved] true) or was abandoned.
     * Returns the parked replacement to run now, or null. A result arriving
     * while nothing is waiting on a save — an ordinary Save As — changes
     * nothing and returns null.
     */
    fun saveFinished(saved: Boolean): T? {
        val saving = phase as? Phase.Saving ?: return null
        phase = Phase.Idle
        return if (saved) saving.request else null
    }
}
