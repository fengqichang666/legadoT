package io.legado.app.help.book

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.legado.app.data.entities.BookChapter
import io.legado.app.help.audio.AudioCacheManager
import io.legado.app.utils.FileDoc
import io.legado.app.utils.list
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class BookDownloadFilesInstrumentationTest {
    private lateinit var root: File
    private lateinit var folder: FileDoc
    private val chapter = BookChapter(index = 0, title = "第一章")

    @Before
    fun setUp() {
        root = File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir,
            "download-test-${UUID.randomUUID()}").apply { mkdirs() }
        folder = FileDoc.fromFile(root)
    }

    @After
    fun tearDown() {
        root.deleteRecursively()
    }

    @Test
    fun copyingAudioProducesIndependentFileAndDoesNotOverwrite() = runBlocking {
        val cache = File(root, "cached.mp3").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        val saved = BookDownloadFiles.copyAudio(FileDoc.fromFile(cache), folder, chapter)
        cache.delete()
        assertArrayEquals(byteArrayOf(1, 2, 3), saved.asFile()!!.readBytes())
        try {
            BookDownloadFiles.writeAudio(folder, chapter, "mp3", ByteArrayInputStream(byteArrayOf(9)), 1)
            fail("existing file must not be overwritten")
        } catch (_: IllegalStateException) {
            assertArrayEquals(byteArrayOf(1, 2, 3), saved.asFile()!!.readBytes())
        }
    }

    @Test
    fun emptyTruncatedAndFailedCopiesRemoveOnlyTheirPartialFile() = runBlocking {
        val existing = File(root, "keep.mp3").apply { writeText("keep") }
        val inputs = listOf(
            ByteArrayInputStream(byteArrayOf()),
            ByteArrayInputStream(byteArrayOf(1)),
            object : InputStream() { override fun read(): Int = throw IOException("interrupted") }
        )
        for (input in inputs) {
            try {
                input.use { BookDownloadFiles.writeAudio(folder, chapter, "mp3", it, 10) }
                fail("incomplete file must fail")
            } catch (_: Exception) {
                assertEquals(listOf(existing.name), folder.list()!!.map { it.name })
            }
        }
    }

    @Test
    fun cancellationRemovesTheFileBeingWritten() = runBlocking {
        val task = launch {
            val job = coroutineContext[Job]!!
            val input = object : InputStream() {
                override fun read(): Int = error("bulk read expected")
                override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
                    bytes[offset] = 1
                    job.cancel()
                    return 1
                }
            }
            input.use { BookDownloadFiles.writeAudio(folder, chapter, "mp3", it, null) }
        }
        task.join()
        assertTrue(task.isCancelled)
        assertTrue(folder.list()!!.isEmpty())
    }

    @Test
    fun rejectsHlsIncludingSignedPlaylistUrls() {
        listOf("https://example.test/audio.m3u8?token=abc", "https://example.test/audio.m3u8").forEach { url ->
            try {
                AudioCacheManager.detectExt("application/octet-stream", url, url)
                fail("HLS must not be saved as an audio file")
            } catch (_: IllegalStateException) { }
        }
        assertEquals("mp3", AudioCacheManager.detectExt("audio/mpeg", "https://example.test/play", "https://example.test/play"))
        assertEquals("m4a", AudioCacheManager.detectExt("audio/mp4", "https://example.test/play", "https://example.test/play"))
    }
}
