/*
 * PreviewStateTest.kt
 * md (Android)
 *
 * The order WebView really delivers when a diagram engine eats the render
 * process, which is the case the retry bound was built for: the shell page
 * loads and `onPageFinished` fires, *then* `md-init.js` imports the engine
 * and the render kills the process. A finished navigation must therefore
 * not count as a render — if it did, the count would be zeroed on every
 * cycle, the notice would never appear, and the pane would reload for ever.
 * That is exactly what `PreviewState` did until 1.5: `onPageFinished` fed
 * the policy RENDERED, and nothing registered the `MdRenderBridge` that the
 * bundled script has always poked.
 *
 * The same cases as the hosted half of the iOS port's
 * `PreviewRetryPolicyTests` (`testAFinishedNavigationIsNotAPageThat
 * SurvivedItsRender`, `testAPageThatReportsItsRenderStartsTheCountOver`),
 * played against the state the preview WebView reports to. No WebView: the
 * three reports are three methods. That the real bundled script reaches
 * `renderCompleted` through the bridge is `PreviewRenderBridgeInstrumentedTest`.
 */

package me.nettrash.md.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PreviewStateTest {

    @Test fun aFinishedNavigationIsNotAPageThatSurvivedItsRender() {
        val state = PreviewState()

        state.navigationFinished()
        assertEquals(PreviewRetryPolicy.Action.RELOAD, state.processTerminated())
        assertEquals(1, state.retry.failures)

        state.navigationFinished()
        assertEquals("the navigation finished; the diagrams had not run yet", 1, state.retry.failures)
        assertEquals(
            "two deaths after two loads is twice in a row — the pane must stop",
            PreviewRetryPolicy.Action.GIVE_UP,
            state.processTerminated(),
        )
        assertTrue(state.retry.hasGivenUp)
    }

    /** And a page that really did get through its render starts the count
     *  over, so an isolated death stays an isolated death. */
    @Test fun aPageThatReportsItsRenderStartsTheCountOver() {
        val state = PreviewState()

        state.navigationFinished()
        state.renderCompleted()
        assertEquals(PreviewRetryPolicy.Action.RELOAD, state.processTerminated())
        assertEquals(1, state.retry.failures)

        state.navigationFinished()
        state.renderCompleted()
        assertEquals(0, state.retry.failures)
        assertEquals(2, state.renderCompletions)

        assertEquals(PreviewRetryPolicy.Action.RELOAD, state.processTerminated())
        assertFalse(state.retry.hasGivenUp)
    }

    /** The edit that lifts a give-up is worth one attempt, and says so: the
     *  caller rebuilds the pane exactly when the state had given up. */
    @Test fun aDocumentChangeReportsWhetherThePaneHadGivenUp() {
        val state = PreviewState()
        assertFalse("a fresh pane has nothing to recover from", state.documentChanged())

        state.processTerminated()
        state.processTerminated()
        assertTrue(state.retry.hasGivenUp)
        assertTrue("had given up: the caller must rebuild the pane", state.documentChanged())
        assertFalse(state.retry.hasGivenUp)
        assertEquals("the count is kept", PreviewRetryPolicy.LIMIT, state.retry.failures)
        assertFalse("a second keystroke has nothing more to lift", state.documentChanged())
    }

    /** A mode switch builds a new pane over the same document's policy: the
     *  give-up survives it, and only a real change of document lifts it. */
    @Test fun aGiveUpSurvivesTheSameDocumentShownAgainAndLiftsOnAChange() {
        val retry = PreviewRetryPolicy()
        val first = PreviewState(retry)
        assertFalse(first.documentChanged("<doc 1>"))
        first.processTerminated()
        first.processTerminated()
        assertTrue(retry.hasGivenUp)

        val again = PreviewState(retry)                       // Edit, then Preview again
        assertFalse("the same document is no change", again.documentChanged("<doc 1>"))
        assertTrue("still given up: no third renderer for it", retry.hasGivenUp)
        assertEquals(PreviewRetryPolicy.Action.NONE, again.processTerminated())

        assertTrue("an edit lifts it and the pane is rebuilt", again.documentChanged("<doc 1, edited>"))
        assertFalse(retry.hasGivenUp)
        assertEquals("one attempt, not a fresh budget", PreviewRetryPolicy.Action.GIVE_UP, again.processTerminated())
    }
}
