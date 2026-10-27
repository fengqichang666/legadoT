package io.legado.app.model

import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import org.junit.Assert.*
import org.junit.Test

class BookDownloadRequestTest {
    @Test
    fun onlyTheAttachedCacheTaskCanStartExportExactlyOnce() {
        val request = BookDownload.Request(Book(), "/downloads", "txt")
        assertFalse(request.beginExport(request.id))
        request.chapters = listOf(BookChapter(index = 0))
        assertFalse(request.beginExport("previous-task"))
        assertEquals(BookDownload.Phase.CACHING, request.phase)
        assertTrue(request.beginExport(request.id))
        assertEquals(BookDownload.Phase.EXPORTING, request.phase)
        assertFalse(request.beginExport(request.id))
    }
}
