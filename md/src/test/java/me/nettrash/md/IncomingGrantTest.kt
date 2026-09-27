/*
 * IncomingGrantTest.kt
 * md (Android)
 *
 * JVM unit tests for what a document handed to md may be done with
 * (`IncomingGrant.kt`): whether an ACTION_VIEW / ACTION_EDIT hand-off opens
 * for editing, and which access modes are worth asking to persist.
 *
 * The flag bits are spelt out in the source so that file stays free of
 * `android.*`; here they are pinned against the `Intent` constants they
 * mirror. Reading a constant off the stubbed android.jar is safe — a
 * compile-time `static final int` carries its value, unlike a method, which
 * would throw "not mocked" — so a wrong bit fails here, on the JVM, instead
 * of on a device as "my edits vanish".
 */

package me.nettrash.md

import android.content.Intent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class IncomingGrantTest {

    // The bits themselves

    @Test fun theFlagBitsAreTheIntentFlagsTheyMirror() {
        assertEquals(Intent.FLAG_GRANT_READ_URI_PERMISSION, IncomingGrant.FLAG_READ)
        assertEquals(Intent.FLAG_GRANT_WRITE_URI_PERMISSION, IncomingGrant.FLAG_WRITE)
        assertEquals(Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION, IncomingGrant.FLAG_PERSISTABLE)
    }

    // isWritable — the whole of proposal (1)

    @Test fun aWriteGrantOnTheIntentOpensForEditing() {
        val flags = IncomingGrant.FLAG_READ or IncomingGrant.FLAG_WRITE
        assertTrue(IncomingGrant.isWritable(flags, holdsWriteGrant = false))
    }

    @Test fun aReadOnlyHandoverStaysReadOnly() {
        assertFalse(IncomingGrant.isWritable(IncomingGrant.FLAG_READ, holdsWriteGrant = false))
    }

    @Test fun noGrantAtAllStaysReadOnly() {
        // A file: URI from an older file manager carries no URI grant; it
        // opens exactly as it did before this feature.
        assertFalse(IncomingGrant.isWritable(intentFlags = 0, holdsWriteGrant = false))
    }

    @Test fun aGrantWeAlreadyHoldOpensForEditing() {
        // A persistable grant taken on an earlier launch is not re-stated in
        // the flags of every later intent, so the permission check has the
        // final word — it is an OR, not a fallback.
        assertTrue(IncomingGrant.isWritable(intentFlags = 0, holdsWriteGrant = true))
        assertTrue(IncomingGrant.isWritable(IncomingGrant.FLAG_READ, holdsWriteGrant = true))
    }

    @Test fun unrelatedFlagsDecideNothing() {
        // FLAG_ACTIVITY_NEW_TASK and friends ride along on plenty of intents
        // and must not be read as a grant.
        assertFalse(IncomingGrant.isWritable(Intent.FLAG_ACTIVITY_NEW_TASK, holdsWriteGrant = false))
        assertTrue(
            IncomingGrant.isWritable(
                Intent.FLAG_ACTIVITY_NEW_TASK or IncomingGrant.FLAG_WRITE,
                holdsWriteGrant = false,
            ),
        )
    }

    // persistableModes — ask only for what was offered

    @Test fun nothingIsAskedForWhenTheGrantIsNotPersistable() {
        // takePersistableUriPermission throws without the persistable flag,
        // so the common case is simply never asked for.
        val flags = IncomingGrant.FLAG_READ or IncomingGrant.FLAG_WRITE
        assertEquals(0, IncomingGrant.persistableModes(flags))
    }

    @Test fun onlyTheModesTheSenderGrantedAreAskedFor() {
        // Asking for write on a read-only grant throws.
        assertEquals(
            IncomingGrant.FLAG_READ,
            IncomingGrant.persistableModes(IncomingGrant.FLAG_READ or IncomingGrant.FLAG_PERSISTABLE),
        )
        assertEquals(
            IncomingGrant.FLAG_READ or IncomingGrant.FLAG_WRITE,
            IncomingGrant.persistableModes(
                IncomingGrant.FLAG_READ or IncomingGrant.FLAG_WRITE or IncomingGrant.FLAG_PERSISTABLE,
            ),
        )
    }

    @Test fun unrelatedFlagsAreNeverAskedFor() {
        val flags = Intent.FLAG_ACTIVITY_NEW_TASK or
            Intent.FLAG_GRANT_PREFIX_URI_PERMISSION or
            IncomingGrant.FLAG_READ or
            IncomingGrant.FLAG_PERSISTABLE
        assertEquals(IncomingGrant.FLAG_READ, IncomingGrant.persistableModes(flags))
    }

    @Test fun aPersistableFlagWithNoAccessAsksForNothing() {
        assertEquals(0, IncomingGrant.persistableModes(IncomingGrant.FLAG_PERSISTABLE))
    }
}
