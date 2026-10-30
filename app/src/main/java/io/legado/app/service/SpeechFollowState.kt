package io.legado.app.service

class SpeechFollowState {

    enum class NextChapterDecision {
        ContinueWithVisibleSync,
        ContinueSpeechOnly,
        Stop
    }

    var followReadAloudPosition: Boolean = true
        private set

    // 等待翻页或章节加载完成后再起读，期间旧朗读进度不能拉回显示页。
    var readFromVisiblePageAfterNavigation: Boolean = false
        private set

    fun detachForManualNavigation(readFromVisiblePage: Boolean = false) {
        followReadAloudPosition = false
        readFromVisiblePageAfterNavigation = readFromVisiblePage
    }

    fun restoreForNewSpeechSession() {
        followReadAloudPosition = true
        readFromVisiblePageAfterNavigation = false
    }

    fun clearPendingVisiblePageReadAloud() {
        readFromVisiblePageAfterNavigation = false
    }

    fun shouldSyncSpeechNavigation(): Boolean {
        return followReadAloudPosition
    }

    fun shouldApplySpeechProgressToVisibleReader(isSpeechPlaying: Boolean): Boolean {
        return isSpeechPlaying && followReadAloudPosition
    }

    fun nextChapterDecision(
        hasNextSpeechChapter: Boolean,
        visibleSyncMoved: Boolean
    ): NextChapterDecision {
        if (!hasNextSpeechChapter) {
            return NextChapterDecision.Stop
        }
        if (visibleSyncMoved) {
            return NextChapterDecision.ContinueWithVisibleSync
        }
        return if (followReadAloudPosition) {
            NextChapterDecision.Stop
        } else {
            NextChapterDecision.ContinueSpeechOnly
        }
    }
}
