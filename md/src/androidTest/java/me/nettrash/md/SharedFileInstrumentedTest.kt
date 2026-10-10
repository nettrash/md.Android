/*
 * SharedFileInstrumentedTest.kt
 * md (Android)
 *
 * "Share → md" with a file (#8): an ACTION_SEND carrying EXTRA_STREAM goes
 * through the same hand-off as Open with md — the text opens, a pack is
 * imported, the grant decides whether it is writable — and a file md does
 * not open is refused before the unsaved-changes guard could ask about it.
 */

package me.nettrash.md

import android.app.Application
import android.content.ClipData
import android.content.Intent
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

@RunWith(AndroidJUnit4::class)
class SharedFileInstrumentedTest {

    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val app: Application get() = instrumentation.targetContext.applicationContext as Application
    private val made = ArrayList<File>()

    @After fun clean() { made.forEach { it.delete() } }

    private fun file(name: String, bytes: ByteArray): Uri {
        val f = File(app.cacheDir, name).apply { writeBytes(bytes) }
        made += f
        return Uri.fromFile(f)
    }

    private fun <T> onMain(block: () -> T): T {
        var result: Result<T>? = null
        instrumentation.runOnMainSync { result = runCatching(block) }
        return result!!.getOrThrow()
    }

    private fun send(uri: Uri, type: String, flags: Int = Intent.FLAG_GRANT_READ_URI_PERMISSION, viaClipOnly: Boolean = false) =
        Intent(Intent.ACTION_SEND).apply {
            this.type = type
            if (!viaClipOnly) putExtra(Intent.EXTRA_STREAM, uri)
            clipData = ClipData.newRawUri("", uri)
            addFlags(flags)
        }

    private fun waitFor(vm: DocumentViewModel, condition: (DocumentViewModel) -> Boolean) {
        val deadline = System.currentTimeMillis() + 5_000
        while (!onMain { condition(vm) } && System.currentTimeMillis() < deadline) Thread.sleep(20)
    }

    @Test fun aSharedMarkdownFileOpensReadOnly() {
        val uri = file("shared.mkd", "# Shared\n".toByteArray())
        val vm = onMain { DocumentViewModel(app) }
        onMain { vm.handOver(send(uri, "application/octet-stream")) }
        waitFor(vm) { it.uri != null }
        assertEquals("# Shared\n", onMain { vm.text })
        assertEquals("shared.mkd", onMain { vm.displayName })
        assertFalse(onMain { vm.canWrite })
        assertTrue(onMain { vm.isReadOnly })
    }

    @Test fun aWriteGrantOpensItWritable() {
        val uri = file("writable.md", "text\n".toByteArray())
        val vm = onMain { DocumentViewModel(app) }
        onMain {
            vm.handOver(send(uri, "text/markdown", Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION))
        }
        waitFor(vm) { it.uri != null }
        assertTrue(onMain { vm.canWrite })
    }

    @Test fun theClipAloneIsEnough() {
        val uri = file("clip.puml", "@startuml\nA -> B\n@enduml\n".toByteArray())
        val vm = onMain { DocumentViewModel(app) }
        onMain { vm.handOver(send(uri, "application/octet-stream", viaClipOnly = true)) }
        waitFor(vm) { it.uri != null }
        assertTrue(onMain { vm.text }.startsWith("@startuml"))
    }

    @Test fun aSharedPackIsImported() {
        val zip = java.io.ByteArrayOutputStream().also { out ->
            ZipOutputStream(out).use { z ->
                z.putNextEntry(ZipEntry("Doc.textbundle/text.md")); z.write("# Packed\n".toByteArray()); z.closeEntry()
            }
        }.toByteArray()
        val uri = file("Doc.textpack", zip)
        val vm = onMain { DocumentViewModel(app) }
        onMain { vm.handOver(send(uri, "application/zip")) }
        waitFor(vm) { it.text.isNotEmpty() }
        assertEquals("# Packed\n", onMain { vm.text })
        assertNull("a pack is imported, never adopted", onMain { vm.uri })
        assertEquals("Doc", onMain { vm.displayName })
    }

    @Test fun aFileMdDoesNotOpenIsRefusedBeforeTheGuard() {
        val vm = onMain { DocumentViewModel(app) }
        // An untitled document with unsaved text: replacing it would ask.
        onMain { vm.acceptSharedText("unsaved words") }
        assertTrue(onMain { vm.needsDiscardPrompt })
        val photo = file("photo.jpg", byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte()))
        onMain { vm.handOver(send(photo, "application/octet-stream")) }
        assertFalse("no dialog for a refused file", onMain { vm.isPromptingDiscard })
        assertEquals("unsaved words", onMain { vm.text })
    }

    @Test fun sharedTextStillOpensUntitled() {
        val vm = onMain { DocumentViewModel(app) }
        onMain { vm.handOver(Intent(Intent.ACTION_SEND).apply { type = "text/plain"; putExtra(Intent.EXTRA_TEXT, "hello\r\nworld") }) }
        assertEquals("hello\nworld", onMain { vm.text })
        assertNull(onMain { vm.uri })
    }
}
