package io.legado.app.help.book

import androidx.core.net.toUri
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.help.CacheManager
import io.legado.app.utils.FileDoc
import io.legado.app.utils.MD5Utils
import io.legado.app.utils.checkWrite
import io.legado.app.utils.createFileIfNotExist
import io.legado.app.utils.createFolderIfNotExist
import io.legado.app.utils.delete
import io.legado.app.utils.exists
import io.legado.app.utils.normalizeFileName
import io.legado.app.utils.openInputStream
import io.legado.app.utils.openOutputStream
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.InputStream
import java.util.Locale

object BookDownloadFiles {
    fun requireComplete(chapters: List<BookChapter>, content: (BookChapter) -> String?) {
        check(chapters.any { !it.isVolume }) { "目录为空，无法保存整本" }
        val missing = chapters.count { !it.isVolume && content(it).isNullOrBlank() }
        check(missing == 0) { "还有 $missing 章未下载成功，请重试；本次未导出文件" }
    }

    fun availableName(name: String, exists: (String) -> Boolean): String {
        var candidate = name
        var number = 2
        while (exists(candidate)) candidate = "$name (${number++})"
        return candidate
    }

    @Synchronized
    fun createBookFolder(directory: String, book: Book): FileDoc {
        val root = FileDoc.fromDir(directory)
        check(root.exists() && root.checkWrite()) { "下载目录不可写，请重新选择目录" }
        val name = book.name.normalizeFileName().trim('_', '.', ' ').take(80).ifBlank { "book" }
        return root.createFolderIfNotExist(availableName(name) { root.exists(it) })
    }

    fun audioName(chapter: BookChapter, ext: String): String {
        val title = chapter.title.normalizeFileName().trim('_').take(80).ifBlank { "chapter" }
        return String.format(Locale.ROOT, "%05d_%s.%s", chapter.index + 1, title, ext)
    }

    suspend fun writeAudio(folder: FileDoc, chapter: BookChapter, ext: String, input: InputStream, expectedSize: Long?): FileDoc {
        val name = audioName(chapter, ext)
        check(!folder.exists(name)) { "目标文件已存在：$name" }
        val file = folder.createFileIfNotExist(name)
        try {
            file.openOutputStream().getOrThrow().use { output ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val count = input.read(buffer)
                    if (count < 0) break
                    output.write(buffer, 0, count)
                }
            }
            currentCoroutineContext().ensureActive()
            val saved = FileDoc.fromUri(file.uri, false)
            check(saved.size > 0 && (expectedSize == null || saved.size == expectedSize)) { "音频文件为空或不完整" }
            return saved
        } catch (e: Throwable) {
            file.delete()
            throw e
        }
    }

    suspend fun copyAudio(source: FileDoc, folder: FileDoc, chapter: BookChapter): FileDoc {
        val ext = source.name.substringAfterLast('.', "audio")
        return source.openInputStream().getOrThrow().use {
            writeAudio(folder, chapter, ext, it, source.size)
        }
    }

    private fun audioKey(book: Book, chapter: BookChapter) =
        "downloadAudio_" + MD5Utils.md5Encode16(book.bookUrl + "\n" + chapter.url)

    fun rememberAudio(book: Book, chapter: BookChapter, file: FileDoc) {
        CacheManager.put(audioKey(book, chapter), "${file.size}\n${file.uri}")
    }

    fun previousAudio(book: Book, chapter: BookChapter): FileDoc? {
        val saved = CacheManager.get(audioKey(book, chapter)) ?: return null
        val size = saved.substringBefore('\n').toLongOrNull() ?: return null
        return runCatching {
            FileDoc.fromUri(saved.substringAfter('\n').toUri(), false)
                .takeIf { it.exists() && it.size > 0 && it.size == size }
        }.getOrNull()
    }
}
