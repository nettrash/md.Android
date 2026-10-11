/*
 * ClobberRescueInstrumentedTest.kt
 * md (Android)
 *
 * #10 on a real file and the real view model: opening reads off the main
 * thread; a file changed on disk under unsaved edits is never overwritten
 * until the writer chooses Keep Mine (or Reload); a save that fails keeps the
 * text in md's own storage and says so; and a document whose edits could not
 * be landed is not replaced.
 */

package me.nettrash.md

import android.app.Application
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class ClobberRescueInstrumentedTest {

    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val app: Application get() = instrumentation.targetContext.applicationContext as Application
    private lateinit var file: File
    private val uri: Uri get() = Uri.fromFile(file)
    private val rescued: File get() = File(app.filesDir, "rescued")

    @Before fun setUp() {
        file = File.createTempFile("clobber", ".md", app.cacheDir).apply { writeText("on disk\n") }
        rescued.deleteRecursively()
    }

    @After fun tearDown() {
        file.setWritable(true)
        file.delete()
        rescued.deleteRecursively()
    }

    private fun <T> onMain(block: () -> T): T {
        var result: Result<T>? = null
        instrumentation.runOnMainSync { result = runCatching(block) }
        return result!!.getOrThrow()
    }

    private fun waitUntil(timeoutMs: Long = 5_000, condition: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (onMain(condition)) return true
            Thread.sleep(20)
        }
        return onMain(condition)
    }

    private fun opened(): DocumentViewModel {
        val vm = onMain { DocumentViewModel(app) }
        onMain { vm.load(uri, writable = true) }
        assertTrue(waitUntil { vm.uri != null })
        return vm
    }

    /** Someone else rewrites the file: new bytes and a modification time md has not seen. */
    private fun changeOnDisk(text: String) {
        file.writeText(text)
        file.setLastModified(file.lastModified() + 5_000)
    }

    @Test fun openingDoesNotReadOnTheMainThread() {
        val vm = onMain { DocumentViewModel(app) }
        // The read is dispatched to IO, so nothing has landed when load returns.
        val landedAtOnce = onMain { vm.load(uri, writable = true); vm.uri != null }
        assertFalse(landedAtOnce)
        assertTrue(waitUntil { vm.text == "on disk\n" })
    }

    @Test fun aFileChangedOnDiskIsNotClobberedByTheAutosave() {
        val vm = opened()
        changeOnDisk("theirs\n")
        onMain { vm.onTextChange("mine\n") }
        assertTrue(waitUntil(4_000) { vm.notice == DocumentNotice.ChangedOnDisk })
        assertEquals("theirs\n", file.readText())
        // An explicit Save does not get past it either, and is not a Save As.
        assertFalse(onMain { vm.save() })
        assertTrue(onMain { vm.hasWritableFile })
        assertEquals("theirs\n", file.readText())
    }

    @Test fun keepMineWritesOverIt() {
        val vm = opened()
        changeOnDisk("theirs\n")
        onMain { vm.onTextChange("mine\n") }
        assertTrue(waitUntil(4_000) { vm.notice == DocumentNotice.ChangedOnDisk })
        onMain { vm.keepMine() }
        assertEquals("mine\n", file.readText())
        assertNull(onMain { vm.notice })
        assertFalse(onMain { vm.isDirty })
        // And the next edit autosaves normally against the new stamp.
        onMain { vm.onTextChange("mine, again\n") }
        assertTrue(waitUntil(4_000) { !vm.isDirty })
        assertEquals("mine, again\n", file.readText())
    }

    @Test fun reloadTakesTheirs() {
        val vm = opened()
        changeOnDisk("theirs\n")
        onMain { vm.onTextChange("mine\n") }
        assertTrue(waitUntil(4_000) { vm.notice == DocumentNotice.ChangedOnDisk })
        onMain { vm.reloadFromDisk() }
        assertTrue(waitUntil { vm.text == "theirs\n" })
        assertNull(onMain { vm.notice })
        assertEquals("theirs\n", file.readText())
    }

    @Test fun aDocumentInConflictIsNotReplaced() {
        val vm = opened()
        changeOnDisk("theirs\n")
        onMain { vm.onTextChange("mine\n") }
        assertTrue(waitUntil(4_000) { vm.notice == DocumentNotice.ChangedOnDisk })
        onMain { vm.newDocument() }
        assertEquals("mine\n", onMain { vm.text })
        assertEquals(uri, onMain { vm.uri })
        assertEquals("theirs\n", file.readText())
    }

    @Test fun aFailedSaveIsRescuedAndSaysSo() {
        val vm = opened()
        assertTrue(file.setWritable(false))
        onMain { vm.onTextChange("words worth keeping\n") }
        assertTrue(waitUntil(4_000) { vm.notice is DocumentNotice.SaveFailed })
        val copies = rescued.listFiles().orEmpty()
        assertEquals(1, copies.size)
        assertEquals("words worth keeping\n", copies.single().readText())
        assertTrue(copies.single().name, copies.single().name.endsWith(").md") && "(rescued " in copies.single().name)
        // A later failure rewrites the same copy rather than adding one.
        onMain { vm.onTextChange("words worth keeping, and more\n") }
        assertTrue(waitUntil(4_000) { rescued.listFiles().orEmpty().singleOrNull()?.readText() == "words worth keeping, and more\n" })
        assertEquals(1, rescued.listFiles().orEmpty().size)
        // The edits that could not be landed hold the document in place.
        onMain { vm.newDocument() }
        assertEquals(uri, onMain { vm.uri })
        // Once the file can be written again, the next save lands and the bar goes.
        file.setWritable(true)
        assertTrue(onMain { vm.save() })
        assertNull(onMain { vm.notice })
        assertEquals("words worth keeping, and more\n", file.readText())
    }

    @Test fun aSaveAsAfterAFailureCarriesOnInTheNewFile() {
        val vm = opened()
        file.setWritable(false)
        onMain { vm.onTextChange("rescue me\n") }
        assertTrue(waitUntil(4_000) { vm.notice is DocumentNotice.SaveFailed })
        val copy = File.createTempFile("copy", ".md", app.cacheDir)
        try {
            assertTrue(onMain { vm.saveAs(Uri.fromFile(copy)) })
            assertEquals("rescue me\n", copy.readText())
            assertNull(onMain { vm.notice })
            assertEquals(Uri.fromFile(copy), onMain { vm.uri })
        } finally {
            copy.delete()
        }
    }

    // ---- Copilot review on #17 / #18 / #20 ------------------------------

    @Test fun aFailedFirstSaveAsIsRescuedAndSaysSo() {
        val vm = onMain { DocumentViewModel(app) }
        onMain { vm.newDocument(); vm.onTextChange("untitled words\n") }
        assertTrue(file.setWritable(false))
        assertFalse(onMain { vm.saveAs(uri) })
        assertTrue(onMain { vm.notice is DocumentNotice.SaveFailed })
        assertEquals("untitled words\n", rescued.listFiles().orEmpty().single().readText())
        assertNull(onMain { vm.uri })
    }

    @Test fun aReloadThatCannotReadKeepsTheEditsOwed() {
        val vm = opened()
        changeOnDisk("theirs\n")
        onMain { vm.onTextChange("mine\n") }
        assertTrue(waitUntil(4_000) { vm.notice == DocumentNotice.ChangedOnDisk })
        assertTrue(file.delete())
        onMain { vm.reloadFromDisk() }
        assertTrue(waitUntil { !vm.isLoading })
        assertEquals("mine\n", onMain { vm.text })
        assertTrue("the edits are still owed to the file", onMain { vm.isDirty })
    }

    @Test fun theEditorIsReadOnlyWhileADocumentLoads() {
        val vm = onMain { DocumentViewModel(app) }
        assertTrue(onMain { vm.load(uri, writable = true); vm.isLoading })
        assertTrue(waitUntil { !vm.isLoading && vm.text == "on disk\n" })
    }

    @Test fun anEditMadeWhileTheNextDocumentLoadsLandsInItsOwnFile() {
        val next = File.createTempFile("next", ".md", app.cacheDir).apply { writeText("next\n") }
        try {
            val vm = opened()
            onMain {
                vm.load(Uri.fromFile(next), writable = true)
                vm.onTextChange("typed while it loaded\n")   // a command, say — the field itself is read-only
            }
            assertTrue(waitUntil { vm.text == "next\n" })
            assertEquals("typed while it loaded\n", file.readText())
        } finally {
            next.delete()
        }
    }

    @Test fun anEditWithNowhereToGoKeepsTheDocumentInsteadOfTheLoad() {
        val vm = onMain { DocumentViewModel(app) }
        onMain {
            vm.newDocument()
            vm.load(uri, writable = true)
            vm.onTextChange("untitled typing\n")
        }
        assertTrue(waitUntil { !vm.isLoading })
        assertEquals("untitled typing\n", onMain { vm.text })
        assertNull(onMain { vm.uri })
    }
}
