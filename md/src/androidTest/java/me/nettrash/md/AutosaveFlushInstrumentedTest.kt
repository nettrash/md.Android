/*
 * AutosaveFlushInstrumentedTest.kt
 * md (Android)
 *
 * The premise the unsaved-changes guard lets a writable document through on.
 *
 * `needsDiscardPrompt` (DiscardGuard.kt) answers false for a dirty document
 * with a writable file behind it, and says why: "its edits are a beat from
 * disk". That beat is `scheduleAutosave`'s one-second debounce — and every
 * path that replaced the document opened by cancelling exactly that job, so
 * a replacement asked for inside the beat took the edits with it and nothing
 * ever asked. New and an example are a single keystroke or a single tap, and
 * a document handed over by another app arrives whenever it arrives, so the
 * window is not theoretical.
 *
 * `DiscardGuardTest` pins the rule on the JVM; this is the other half, and it
 * has to be here: the flush is a ContentResolver write, and the buffer, the
 * URI and the debounce all live in an AndroidViewModel. The document is a
 * real file in the app's own cache directory, opened through the resolver by
 * `file:` URI exactly as a SAF document is opened by `content:` URI.
 *
 * Run with (emulator only; see the workspace rules):
 *   adb -s emulator-5554 shell am instrument -w \
 *     -e class me.nettrash.md.AutosaveFlushInstrumentedTest \
 *     me.nettrash.md.test/androidx.test.runner.AndroidJUnitRunner
 */

package me.nettrash.md

import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class AutosaveFlushInstrumentedTest {

    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()

    private val app: Application
        get() = instrumentation.targetContext.applicationContext as Application

    private lateinit var file: File
    private lateinit var other: File

    private val uri: Uri get() = Uri.fromFile(file)
    private val otherUri: Uri get() = Uri.fromFile(other)

    private val onDisk = "on disk\n"
    private val typed = "typed in the beat before the document was replaced"

    @Before fun makeFiles() {
        file = File.createTempFile("flush", ".md", app.cacheDir).apply { writeText(onDisk) }
        other = File.createTempFile("other", ".md", app.cacheDir).apply { writeText("elsewhere\n") }
    }

    @After fun removeFiles() {
        file.delete()
        other.delete()
    }

    /** The ViewModel is Compose state over a debounced `viewModelScope` job:
     *  everything it is asked here is asked from the main thread, as the
     *  editor asks it. */
    private fun <T> onMain(block: () -> T): T {
        var result: Result<T>? = null
        instrumentation.runOnMainSync { result = runCatching(block) }
        return result!!.getOrThrow()
    }

    /** A writable document with one second of unwritten edits in it — the
     *  state every test below starts from, and the one the guard waves
     *  through without a word. */
    private fun dirtyWritableDocument(): DocumentViewModel {
        val vm = onMain { DocumentViewModel(app) }
        onMain { vm.load(uri, writable = true) }
        assertEquals(onDisk, onMain { vm.text })
        onMain { vm.onTextChange(typed) }
        assertTrue(onMain { vm.isDirty })
        // The guard hands the replacement straight back: no dialog, no delay.
        assertFalse(onMain { vm.needsDiscardPrompt })
        return vm
    }

    @Test fun newDocumentLandsWhatTheAutosaveStillOwes() {
        val vm = dirtyWritableDocument()
        onMain { vm.newDocument() }
        assertEquals(typed, file.readText())
        assertEquals("", onMain { vm.text })
    }

    @Test fun anExampleLandsThemToo() {
        // A bundled example replaces the buffer through acceptSharedText.
        val vm = dirtyWritableDocument()
        onMain { vm.acceptSharedText("# Example\n") }
        assertEquals(typed, file.readText())
    }

    @Test fun anIncomingDocumentLandsThemToo() {
        // "Open with md" while the writer is mid-word: the hand-off runs
        // through load, which replaces the buffer on the spot.
        val vm = dirtyWritableDocument()
        onMain { vm.load(otherUri, writable = true) }
        assertEquals(typed, file.readText())
        assertEquals("elsewhere\n", onMain { vm.text })
    }

    @Test fun anIncomingIntentLandsThemToo() {
        // The same hand-off as the Activity delivers it, guard and all.
        val vm = dirtyWritableDocument()
        val intent = Intent(Intent.ACTION_VIEW, otherUri)
        onMain { vm.handOver(intent) }
        assertEquals(typed, file.readText())
        assertFalse(onMain { vm.isPromptingDiscard })
    }

    @Test fun steppingToABookArticleLandsThemToo() {
        // loadAsync reads off the main thread, but the flush is synchronous:
        // the edits are on disk before the read is even started.
        val vm = dirtyWritableDocument()
        onMain { vm.loadAsync(otherUri, writable = true, fromBook = true) }
        assertEquals(typed, file.readText())
    }

    @Test fun aReadOnlyDocumentIsNeverWrittenBehindItsSendersBack() {
        // The control, and the other half of the rule: a document handed over
        // without write access has nowhere to autosave, so it is the dialog
        // that answers for it and nothing is written — least of all to the
        // file its sender withheld write access to.
        val vm = onMain { DocumentViewModel(app) }
        onMain { vm.load(uri, writable = false) }
        onMain { vm.onTextChange(typed) }
        assertTrue(onMain { vm.needsDiscardPrompt })
        onMain { vm.newDocument() }
        assertEquals(onDisk, file.readText())
    }

    @Test fun aCleanDocumentIsNotRewrittenOnTheWayOut() {
        // Nothing owed, nothing written: replacing a document nobody typed
        // into must not touch its file at all.
        val vm = onMain { DocumentViewModel(app) }
        onMain { vm.load(uri, writable = true) }
        val stamp = file.lastModified()
        Thread.sleep(1_100)      // coarse file-system timestamps
        onMain { vm.newDocument() }
        assertEquals(onDisk, file.readText())
        assertEquals(stamp, file.lastModified())
    }
}
