package io.legado.app.help.book

import io.legado.app.data.entities.BookChapter
import org.junit.Assert.*
import org.junit.Test

class BookDownloadFilesTest {
    @Test
    fun choosesUnusedFolderWithoutOverwritingAnyExistingName() {
        val existing = setOf("书名", "书名 (2)", "书名 (3)")
        assertEquals("书名 (4)", BookDownloadFiles.availableName("书名", existing::contains))
        assertEquals("新书", BookDownloadFiles.availableName("新书", existing::contains))
    }

    @Test
    fun completeCachedBookPassesAndVolumeHeadingsNeedNoContent() {
        val chapters = listOf(BookChapter(index = 0, isVolume = true), BookChapter(index = 1), BookChapter(index = 2))
        BookDownloadFiles.requireComplete(chapters) { if (it.index > 0) "正文" else null }
    }

    @Test(expected = IllegalStateException::class)
    fun missingChapterBlocksExportEvenIfDownloadQueueHasFinished() {
        BookDownloadFiles.requireComplete(listOf(BookChapter(index = 0), BookChapter(index = 1))) {
            if (it.index == 0) "已缓存正文" else null
        }
    }

    @Test(expected = IllegalStateException::class)
    fun emptyCatalogCannotBeReportedAsComplete() {
        BookDownloadFiles.requireComplete(emptyList()) { "" }
    }

    @Test(expected = IllegalStateException::class)
    fun emptyContentIsNotACompleteChapter() {
        BookDownloadFiles.requireComplete(listOf(BookChapter(index = 0))) { "  " }
    }

    @Test(expected = IllegalStateException::class)
    fun volumeOnlyCatalogCannotBeReportedAsComplete() {
        BookDownloadFiles.requireComplete(listOf(BookChapter(isVolume = true))) { null }
    }

    @Test
    fun audioNamesSortByChapterAndKeepFormat() {
        assertEquals("00001_开始.mp3", BookDownloadFiles.audioName(BookChapter(index = 0, title = "开始"), "mp3"))
        assertEquals("00012_继续.m4a", BookDownloadFiles.audioName(BookChapter(index = 11, title = "继续"), "m4a"))
    }
}
