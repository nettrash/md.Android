/*
 * DiscardGuardTest.kt
 * md (Android)
 *
 * JVM unit tests for the unsaved-changes guard (`DiscardGuard.kt`): the
 * truth table for when replacing the open document has to ask first, its
 * complement (when the document is flushed instead of asked about), and the
 * state machine that parks the replacement while it asks — park, answer with
 * Save / Discard / Cancel, and the Save As round trip that either releases
 * the parked request or drops it.
 *
 * The flush itself is a ContentResolver write and lives in the ViewModel, so
 * `AutosaveFlushInstrumentedTest` is the half that drives it on a device;
 * what is pinned here is the rule the two of them share.
 *
 * The guard is generic and free of `android.*`, so these drive the very code
 * the editor runs, with a `String` standing in for the `Replacement` the
 * screen parks. The one part that cannot be reached from here is the
 * ViewModel's mirror of it (`isPromptingDiscard`) and the Compose dialog —
 * both are a thin shell over what is pinned below, and Robolectric is not on
 * this project's classpath.
 */

package me.nettrash.md

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DiscardGuardTest {

    // needsDiscardPrompt — a dirty buffer with nowhere to autosave

    @Test fun cleanDocumentNeverAsks() {
        // Nothing to lose, whatever is behind it.
        assertFalse(needsDiscardPrompt(isDirty = false, hasUri = false, canWrite = false))
        assertFalse(needsDiscardPrompt(isDirty = false, hasUri = true, canWrite = true))
        assertFalse(needsDiscardPrompt(isDirty = false, hasUri = true, canWrite = false))
        assertFalse(needsDiscardPrompt(isDirty = false, hasUri = false, canWrite = true))
    }

    @Test fun dirtyWritableFileNeverAsks() {
        // The autosave is a beat behind the editor and the ON_STOP flush
        // catches the rest: Back on one of these still leaves in silence.
        assertFalse(needsDiscardPrompt(isDirty = true, hasUri = true, canWrite = true))
    }

    @Test fun dirtyUntitledAsks() {
        // File > New, shared-in text, an imported .textpack: edits, no file.
        assertTrue(needsDiscardPrompt(isDirty = true, hasUri = false, canWrite = false))
    }

    @Test fun dirtyReadOnlyHandoverAsks() {
        // A document opened from a sender that granted no write access.
        assertTrue(needsDiscardPrompt(isDirty = true, hasUri = true, canWrite = false))
    }

    @Test fun dirtyWritableWithoutAFileAsks() {
        // canWrite without a URI cannot happen in the ViewModel, but the rule
        // is "somewhere to autosave to", and that is a file: it must not read
        // the flag alone and let a buffer through with nothing behind it.
        assertTrue(needsDiscardPrompt(isDirty = true, hasUri = false, canWrite = true))
    }

    // needsAutosaveFlush — the other half, and the premise the rule rests on

    @Test fun everyDirtyDocumentIsEitherAskedAboutOrWrittenDown() {
        // The two predicates partition a dirty document: exactly one of them
        // is true of it, always. That is the whole of the promise — a buffer
        // with a file behind it is never asked about because it is flushed,
        // and a buffer with nowhere to go is never flushed because it is
        // asked about. If they ever overlapped, a document would be both
        // written and questioned; if they ever left a gap, edits would go.
        for (isDirty in listOf(false, true)) {
            for (hasUri in listOf(false, true)) {
                for (canWrite in listOf(false, true)) {
                    val asks = needsDiscardPrompt(isDirty, hasUri, canWrite)
                    val flushes = needsAutosaveFlush(isDirty, hasUri, canWrite)
                    assertFalse("both for $isDirty/$hasUri/$canWrite", asks && flushes)
                    assertEquals("neither for $isDirty/$hasUri/$canWrite", isDirty, asks || flushes)
                }
            }
        }
    }

    @Test fun onlyADirtyWritableFileIsFlushed() {
        // The one state the discard guard waves through, and the one state
        // `DocumentViewModel.flushPendingAutosave` writes in.
        assertTrue(needsAutosaveFlush(isDirty = true, hasUri = true, canWrite = true))

        // A clean document is not rewritten on the way out...
        assertFalse(needsAutosaveFlush(isDirty = false, hasUri = true, canWrite = true))
        // ...a read-only hand-off is never written behind its sender's back...
        assertFalse(needsAutosaveFlush(isDirty = true, hasUri = true, canWrite = false))
        // ...and a buffer with no file has nowhere to be written to.
        assertFalse(needsAutosaveFlush(isDirty = true, hasUri = false, canWrite = false))
        assertFalse(needsAutosaveFlush(isDirty = true, hasUri = false, canWrite = true))
    }

    // submit — run now, or park

    @Test fun aDocumentWithNothingToLoseRunsTheReplacementAtOnce() {
        val guard = DiscardGuard<String>()
        assertEquals("open", guard.submit("open", needsPrompt = false))
        assertNull(guard.parked)
        assertFalse(guard.isAsking)
    }

    @Test fun aDocumentWithSomethingToLoseParksIt() {
        val guard = DiscardGuard<String>()
        assertNull(guard.submit("open", needsPrompt = true))
        assertEquals("open", guard.parked)
        assertTrue(guard.isAsking)
    }

    @Test fun theNewestReplacementSupersedesAParkedOne() {
        // There is only ever one document on screen, so only ever one parked
        // request: a second offer replaces the first rather than queueing.
        val guard = DiscardGuard<String>()
        guard.submit("open", needsPrompt = true)
        assertNull(guard.submit("incoming", needsPrompt = true))
        assertEquals("incoming", guard.parked)
        assertTrue(guard.isAsking)
    }

    @Test fun aSafeDocumentClearsWhateverWasParked() {
        val guard = DiscardGuard<String>()
        guard.submit("open", needsPrompt = true)
        assertEquals("new", guard.submit("new", needsPrompt = false))
        assertNull(guard.parked)
        assertFalse(guard.isAsking)
    }

    // choose — Discard / Cancel / Save

    @Test fun discardHandsTheReplacementBackAndClosesTheDialog() {
        val guard = DiscardGuard<String>()
        guard.submit("open", needsPrompt = true)
        assertEquals("open", guard.choose(DiscardChoice.DISCARD))
        assertNull(guard.parked)
        assertFalse(guard.isAsking)
    }

    @Test fun cancelDropsTheReplacement() {
        val guard = DiscardGuard<String>()
        guard.submit("open", needsPrompt = true)
        assertNull(guard.choose(DiscardChoice.CANCEL))
        assertNull(guard.parked)
        assertFalse(guard.isAsking)
    }

    @Test fun saveKeepsTheReplacementUntilTheSaveLands() {
        val guard = DiscardGuard<String>()
        guard.submit("open", needsPrompt = true)
        // Nothing to run yet — the Save As picker is on its way up — but the
        // request must survive, and the dialog must come down behind it.
        assertNull(guard.choose(DiscardChoice.SAVE))
        assertEquals("open", guard.parked)
        assertFalse(guard.isAsking)
    }

    @Test fun anAnswerWithNoDialogUpChangesNothing() {
        val guard = DiscardGuard<String>()
        assertNull(guard.choose(DiscardChoice.DISCARD))
        assertNull(guard.choose(DiscardChoice.SAVE))
        assertNull(guard.choose(DiscardChoice.CANCEL))
        assertNull(guard.parked)
        assertFalse(guard.isAsking)
    }

    @Test fun theDialogCannotBeAnsweredTwice() {
        val guard = DiscardGuard<String>()
        guard.submit("open", needsPrompt = true)
        assertEquals("open", guard.choose(DiscardChoice.DISCARD))
        assertNull(guard.choose(DiscardChoice.DISCARD))
    }

    // saveFinished — the Save As round trip

    @Test fun aSaveThatLandsReleasesTheReplacement() {
        val guard = DiscardGuard<String>()
        guard.submit("open", needsPrompt = true)
        guard.choose(DiscardChoice.SAVE)
        assertEquals("open", guard.saveFinished(saved = true))
        assertNull(guard.parked)
    }

    @Test fun aDismissedSaveAsPickerDropsTheReplacement() {
        // The edits the reader asked to keep are still unsaved; replacing the
        // document now would lose the very thing they said to save.
        val guard = DiscardGuard<String>()
        guard.submit("open", needsPrompt = true)
        guard.choose(DiscardChoice.SAVE)
        assertNull(guard.saveFinished(saved = false))
        assertNull(guard.parked)
        assertFalse(guard.isAsking)
    }

    @Test fun anOrdinarySaveAsReleasesNothing() {
        // The same Create Document callback serves the menu's Save As, which
        // nothing is waiting on: the guard is Idle and must stay so.
        val guard = DiscardGuard<String>()
        assertNull(guard.saveFinished(saved = true))
        assertNull(guard.saveFinished(saved = false))
        assertNull(guard.parked)
        assertFalse(guard.isAsking)
    }

    @Test fun aSaveResultArrivingWhileTheDialogIsUpChangesNothing() {
        val guard = DiscardGuard<String>()
        guard.submit("open", needsPrompt = true)
        assertNull(guard.saveFinished(saved = true))
        assertEquals("open", guard.parked)
        assertTrue(guard.isAsking)
    }

    // The whole round trip, as the screen drives it

    @Test fun saveThenPickAFileRunsTheParkedIncomingDocument() {
        val guard = DiscardGuard<String>()
        // A file arrives from Files while an untitled draft is on screen.
        assertNull(guard.submit("incoming:notes.md", needsPrompt = true))
        // Save → the Save As picker → the reader picks a name.
        assertNull(guard.choose(DiscardChoice.SAVE))
        assertEquals("incoming:notes.md", guard.saveFinished(saved = true))
        // And the guard is clear for the next document.
        assertNull(guard.parked)
        assertFalse(guard.isAsking)
    }
}
