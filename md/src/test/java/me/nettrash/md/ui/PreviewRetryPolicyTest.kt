/*
 * PreviewRetryPolicyTest.kt
 * md (Android)
 *
 * JVM unit tests for what the preview does when its render process dies
 * (`ui/PreviewRetryPolicy.kt`): the first death is reloaded, the second in a
 * row gives up, a page that gets through its render puts the count back, and
 * a document that changes lifts the give-up without touching the count.
 *
 * The same cases as the iOS port's `PreviewRetryPolicyTests`, because the two
 * state machines are one decision written twice. What WebView reports and in
 * which order — a finished navigation, then the death — is `PreviewStateTest`.
 */

package me.nettrash.md.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PreviewRetryPolicyTest {

    @Test fun aFreshPolicyHasNothingToSay() {
        val policy = PreviewRetryPolicy()
        assertEquals(0, policy.failures)
        assertFalse(policy.hasGivenUp)
        assertEquals(PreviewRetryPolicy.Action.NONE, policy.handle(PreviewRetryPolicy.Event.RENDERED))
    }

    @Test fun theFirstDeathIsReloaded() {
        val policy = PreviewRetryPolicy()
        assertEquals(PreviewRetryPolicy.Action.RELOAD, policy.handle(PreviewRetryPolicy.Event.TERMINATED))
        assertEquals(1, policy.failures)
        assertFalse(policy.hasGivenUp)
    }

    @Test fun theSecondDeathInARowGivesUp() {
        val policy = PreviewRetryPolicy()
        policy.handle(PreviewRetryPolicy.Event.TERMINATED)
        assertEquals(PreviewRetryPolicy.Action.GIVE_UP, policy.handle(PreviewRetryPolicy.Event.TERMINATED))
        assertTrue(policy.hasGivenUp)
    }

    /** The loop this exists to stop: once it has given up, further deaths are
     *  counted by nobody and reloaded by nobody. */
    @Test fun aDeathAfterGivingUpChangesNothing() {
        val policy = PreviewRetryPolicy()
        policy.handle(PreviewRetryPolicy.Event.TERMINATED)
        policy.handle(PreviewRetryPolicy.Event.TERMINATED)
        repeat(5) {
            assertEquals(PreviewRetryPolicy.Action.NONE, policy.handle(PreviewRetryPolicy.Event.TERMINATED))
        }
        assertTrue(policy.hasGivenUp)
        assertEquals(PreviewRetryPolicy.LIMIT, policy.failures)
    }

    @Test fun aPageThatRendersEndsTheRunOfFailures() {
        val policy = PreviewRetryPolicy()
        assertEquals(PreviewRetryPolicy.Action.RELOAD, policy.handle(PreviewRetryPolicy.Event.TERMINATED))
        policy.handle(PreviewRetryPolicy.Event.RENDERED)
        assertEquals(0, policy.failures)
        // So the next death is a first death again, and is reloaded.
        assertEquals(PreviewRetryPolicy.Action.RELOAD, policy.handle(PreviewRetryPolicy.Event.TERMINATED))
    }

    /** An edit lifts the give-up — the changed document is worth trying —
     *  and that reload is the one attempt it buys: the count stands, so a
     *  death straight after it gives up again instead of starting a fresh
     *  pair. (The reload itself is the caller's: `RichPreview` rebuilds the
     *  pane when `documentChanged` says it had given up.) */
    @Test fun aDocumentChangeBringsThePreviewBackAfterGivingUpForOneAttempt() {
        val policy = PreviewRetryPolicy()
        policy.handle(PreviewRetryPolicy.Event.TERMINATED)
        policy.handle(PreviewRetryPolicy.Event.TERMINATED)
        assertTrue(policy.hasGivenUp)

        assertEquals(PreviewRetryPolicy.Action.NONE, policy.handle(PreviewRetryPolicy.Event.DOCUMENT_CHANGED))
        assertFalse(policy.hasGivenUp)
        assertEquals("nothing rendered, so the count is kept", PreviewRetryPolicy.LIMIT, policy.failures)
        assertEquals(PreviewRetryPolicy.Action.GIVE_UP, policy.handle(PreviewRetryPolicy.Event.TERMINATED))
        assertTrue(policy.hasGivenUp)
    }

    /** Split mode reports a document change on every keystroke. Typing over
     *  a document whose render keeps killing the process must still reach
     *  the notice: an edit is a fresh attempt, not evidence that anything
     *  rendered, so it must not refill the budget. */
    @Test fun typingInSplitDoesNotRefillTheRetryBudget() {
        val policy = PreviewRetryPolicy()
        assertEquals(PreviewRetryPolicy.Action.RELOAD, policy.handle(PreviewRetryPolicy.Event.TERMINATED))
        assertEquals(1, policy.failures)

        // Keystrokes, each one a genuine document change.
        policy.handle(PreviewRetryPolicy.Event.DOCUMENT_CHANGED)
        policy.handle(PreviewRetryPolicy.Event.DOCUMENT_CHANGED)
        assertEquals(1, policy.failures)
        assertFalse(policy.hasGivenUp)

        assertEquals(PreviewRetryPolicy.Action.GIVE_UP, policy.handle(PreviewRetryPolicy.Event.TERMINATED))
        assertTrue(policy.hasGivenUp)
    }

    /** And once the page really renders, the slate is clean again: the
     *  give-up and the count both go, so the next death is a first death. */
    @Test fun aRenderAfterGivingUpStartsOver() {
        val policy = PreviewRetryPolicy()
        policy.handle(PreviewRetryPolicy.Event.TERMINATED)
        policy.handle(PreviewRetryPolicy.Event.TERMINATED)
        policy.handle(PreviewRetryPolicy.Event.DOCUMENT_CHANGED)
        assertEquals(PreviewRetryPolicy.Action.NONE, policy.handle(PreviewRetryPolicy.Event.RENDERED))
        assertEquals(0, policy.failures)
        assertFalse(policy.hasGivenUp)
        assertEquals(PreviewRetryPolicy.Action.RELOAD, policy.handle(PreviewRetryPolicy.Event.TERMINATED))
    }

    /** Deaths that are not in a row never add up: reload, render, reload,
     *  render… is a preview that keeps coming back, not one that gives up. */
    @Test fun deathsSeparatedByARenderNeverAddUp() {
        val policy = PreviewRetryPolicy()
        repeat(4) {
            assertEquals(PreviewRetryPolicy.Action.RELOAD, policy.handle(PreviewRetryPolicy.Event.TERMINATED))
            policy.handle(PreviewRetryPolicy.Event.RENDERED)
        }
        assertFalse(policy.hasGivenUp)
    }

    @Test fun theLimitIsTwo() {
        assertEquals(2, PreviewRetryPolicy.LIMIT)
    }
}
