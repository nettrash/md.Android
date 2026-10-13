/*
 * TextCodecInstrumentedTest.kt
 * md (Android)
 *
 * The codec on the device's own charset tables. The JVM tests run on the
 * desktop JDK; the app runs on ART, whose charsets come from ICU — so the
 * cases that depend on a table (Windows-1251's undefined 0x98, a strict
 * encoder refusing an emoji) are asked again here, and the round trip is
 * driven through the real view model and a real file: what was read as
 * CRLF Windows-1251 is written back as CRLF Windows-1251, and a UTF-8 BOM
 * is gone from both the buffer and the file.
 */

package me.nettrash.md

import android.app.Application
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.charset.Charset

@RunWith(AndroidJUnit4::class)
class TextCodecInstrumentedTest {

    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val app: Application get() = instrumentation.targetContext.applicationContext as Application
    private lateinit var file: File

    @Before fun makeFile() { file = File.createTempFile("codec", ".md", app.cacheDir) }
    @After fun removeFile() { file.delete() }

    private fun cp1251(s: String) = s.toByteArray(Charset.forName("windows-1251"))
    private fun bytes(vararg b: Int) = ByteArray(b.size) { b[it].toByte() }

    private fun <T> onMain(block: () -> T): T {
        var result: Result<T>? = null
        instrumentation.runOnMainSync { result = runCatching(block) }
        return result!!.getOrThrow()
    }

    /** Load [file], replace the text, and land it with the flush a New does. */
    private fun editAndFlush(edit: (String) -> String): DocumentViewModel {
        val vm = onMain { DocumentViewModel(app) }
        onMain { vm.load(Uri.fromFile(file), writable = true) }
        waitForText(vm)
        onMain { vm.onTextChange(edit(vm.text)) }
        onMain { vm.newDocument() }
        return vm
    }

    /** Loading may finish off the main thread; wait for the buffer to fill. */
    private fun waitForText(vm: DocumentViewModel) {
        val deadline = System.currentTimeMillis() + 5_000
        while (onMain { vm.uri } == null && System.currentTimeMillis() < deadline) Thread.sleep(20)
    }

    @Test fun theDeviceTablesAgreeWithTheJvm() {
        assertEquals(TextCodec.Decoded("A\u0098B", TextEncoding.ISO_8859_1), TextCodec.decodeWithEncoding(bytes(0x41, 0x98, 0x42)))
        assertEquals(TextEncoding.UTF8, TextCodec.encode("A\u0098B", TextEncoding.WINDOWS_1251).encoding)
        assertEquals(TextEncoding.UTF8, TextCodec.encode("Привет 🙂", TextEncoding.WINDOWS_1251).encoding)
        assertEquals(TextCodec.Decoded("Привет, мир!", TextEncoding.WINDOWS_1251), TextCodec.decodeWithEncoding(cp1251("Привет, мир!")))
        assertArrayEquals(bytes(0x61, 0xEF, 0xBF, 0xBD, 0x62), TextCodec.encode("a\uD800b", TextEncoding.UTF8).data)
        assertEquals(TextCodec.Decoded("A", TextEncoding.UTF8), TextCodec.decodeWithEncoding(bytes(0xEF, 0xBB, 0xBF, 0x41)))
    }

    @Test fun aCrlfWindows1251FileIsWrittenBackInKind() {
        file.writeBytes(cp1251("# Заголовок\r\n\r\nТекст.\r\n"))
        editAndFlush { "$it Ещё.\n" }
        assertArrayEquals(cp1251("# Заголовок\r\n\r\nТекст.\r\n Ещё.\r\n"), file.readBytes())
    }

    @Test fun anEmojiUpgradesTheFileToUtf8AndKeepsItsLineEndings() {
        file.writeBytes(cp1251("Привет\r\n"))
        editAndFlush { "${it}🙂\n" }
        assertArrayEquals("Привет\r\n🙂\r\n".toByteArray(), file.readBytes())
    }

    @Test fun aUtf8BomLeavesTheBufferAndTheFile() {
        file.writeBytes(bytes(0xEF, 0xBB, 0xBF) + "# Title\n".toByteArray())
        val vm = onMain { DocumentViewModel(app) }
        onMain { vm.load(Uri.fromFile(file), writable = true) }
        waitForText(vm)
        assertEquals("# Title\n", onMain { vm.text })
        onMain { vm.onTextChange(vm.text + "More.\n") }
        onMain { vm.newDocument() }
        assertArrayEquals("# Title\nMore.\n".toByteArray(), file.readBytes())
    }
}
