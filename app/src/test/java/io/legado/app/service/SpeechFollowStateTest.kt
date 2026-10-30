package io.legado.app.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeechFollowStateTest {

    @Test
    fun followingManualPageWaitsForNewSpeechSessionBeforeApplyingOldProgress() {
        val state = SpeechFollowState()

        state.detachForManualNavigation(readFromVisiblePage = true)

        assertTrue(state.readFromVisiblePageAfterNavigation)
        assertFalse(state.shouldApplySpeechProgressToVisibleReader(isSpeechPlaying = true))

        state.restoreForNewSpeechSession()

        assertFalse(state.readFromVisiblePageAfterNavigation)
        assertTrue(state.shouldApplySpeechProgressToVisibleReader(isSpeechPlaying = true))
    }

    @Test
    fun manualNavigationWithFollowDisabledKeepsOriginalSpeakingPosition() {
        val state = SpeechFollowState()

        state.detachForManualNavigation(readFromVisiblePage = false)

        assertFalse(state.readFromVisiblePageAfterNavigation)
        assertFalse(state.shouldSyncSpeechNavigation())
    }

    @Test
    fun browsingWithoutFollowCancelsPendingPageRestart() {
        val state = SpeechFollowState()
        state.detachForManualNavigation(readFromVisiblePage = true)

        state.detachForManualNavigation()

        assertFalse(state.readFromVisiblePageAfterNavigation)
        assertFalse(state.followReadAloudPosition)
    }

    @Test
    fun clearingPendingPageRestartDoesNotRestoreSpeechFollow() {
        val state = SpeechFollowState()
        state.detachForManualNavigation(readFromVisiblePage = true)

        state.clearPendingVisiblePageReadAloud()

        assertFalse(state.readFromVisiblePageAfterNavigation)
        assertFalse(state.shouldSyncSpeechNavigation())
    }

    @Test
    fun detachedFollowDoesNotApplySpeechProgressToVisibleReader() {
        val state = SpeechFollowState()

        state.detachForManualNavigation()

        assertFalse(state.shouldApplySpeechProgressToVisibleReader(isSpeechPlaying = true))
    }

    @Test
    fun detachedFollowDoesNotStopSolelyBecauseVisibleChapterSyncWasSkipped() {
        val state = SpeechFollowState()

        state.detachForManualNavigation()

        assertNotEquals(
            SpeechFollowState.NextChapterDecision.Stop,
            state.nextChapterDecision(
                hasNextSpeechChapter = true,
                visibleSyncMoved = false
            )
        )
    }

    @Test
    fun detachedNextChapterContinuationStartsNextSpeechChapterWithoutVisibleSync() {
        val state = SpeechFollowState()

        state.detachForManualNavigation()
        val decision = state.nextChapterDecision(
            hasNextSpeechChapter = true,
            visibleSyncMoved = false
        )

        assertEquals(SpeechFollowState.NextChapterDecision.ContinueSpeechOnly, decision)
    }

    @Test
    fun attachedNextChapterContinuationUsesVisibleSyncWhenItMoved() {
        val state = SpeechFollowState()

        val decision = state.nextChapterDecision(
            hasNextSpeechChapter = true,
            visibleSyncMoved = true
        )

        assertEquals(SpeechFollowState.NextChapterDecision.ContinueWithVisibleSync, decision)
    }

    @Test
    fun noNextChapterStopsSpeech() {
        val state = SpeechFollowState()

        state.detachForManualNavigation()
        val decision = state.nextChapterDecision(
            hasNextSpeechChapter = false,
            visibleSyncMoved = false
        )

        assertEquals(SpeechFollowState.NextChapterDecision.Stop, decision)
    }
}
