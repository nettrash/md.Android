/*
 * LocalAssetsTest.kt
 * md (Android)
 *
 * The bounded read behind the EPUB cover's 20 MiB cap: a file past the
 * limit is refused having read at most one byte past it.
 */

package me.nettrash.md.ui

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.InputStream

class LocalAssetsTest {

    /** An endless stream that counts what was taken from it. */
    private class Endless : InputStream() {
        var taken = 0L
        override fun read(): Int = 0.also { taken++ }
        override fun read(b: ByteArray, off: Int, len: Int): Int {
            taken += len
            return len
        }
    }

    @Test fun aStreamPastTheLimitIsRefusedAfterOneByteMore() {
        val endless = Endless()
        assertNull(LocalAssets.readBounded(endless, 1_000_000))
        assertTrue("took ${endless.taken}", endless.taken <= 1_000_001)
    }

    @Test fun aStreamAtTheLimitIsReadWhole() {
        val bytes = ByteArray(1_000) { it.toByte() }
        assertArrayEquals(bytes, LocalAssets.readBounded(bytes.inputStream(), 1_000))
        assertNull(LocalAssets.readBounded(bytes.inputStream(), 999))
        assertEquals(1_000, LocalAssets.readBounded(bytes.inputStream(), null)?.size)
    }
}
