package io.legado.app.service

import android.content.Intent
import androidx.core.net.toUri
import androidx.core.app.NotificationCompat
import androidx.lifecycle.lifecycleScope
import io.legado.app.R
import io.legado.app.base.BaseService
import io.legado.app.constant.AppConst
import io.legado.app.constant.AppLog
import io.legado.app.constant.EventBus
import io.legado.app.constant.IntentAction
import io.legado.app.constant.NotificationId
import io.legado.app.data.appDb
import io.legado.app.help.audio.AudioCacheManager
import io.legado.app.help.book.BookDownloadFiles
import io.legado.app.help.book.isAudio
import io.legado.app.model.AudioCacheStateChanged
import io.legado.app.model.BookDownload
import io.legado.app.model.webBook.WebBook
import io.legado.app.ui.book.audio.AudioPlayActivity
import io.legado.app.utils.activityPendingIntent
import io.legado.app.utils.FileDoc
import io.legado.app.utils.delete
import io.legado.app.utils.list
import io.legado.app.utils.postEvent
import io.legado.app.utils.servicePendingIntent
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import splitties.init.appCtx
import splitties.systemservices.notificationManager
import java.util.ArrayDeque
import kotlin.math.max
import kotlin.math.min

class AudioCacheService : BaseService() {

    companion object {
        const val ACTION_CACHE_RANGE = "audioCacheRange"
        const val ACTION_DOWNLOAD_BOOK = "audioDownloadBook"
    }

    private data class CacheTask(
        val bookUrl: String,
        val startIndex: Int,
        val endIndex: Int,
        val downloadId: String? = null,
    )

    private val taskQueue = ArrayDeque<CacheTask>()
    private var workerJob: Job? = null
    private var titleText = appCtx.getString(R.string.audio_cache_notification_title)
    private var doneCount = 0
    private var totalCount = 0
    private var failCount = 0

    private val notificationBuilder by lazy {
        NotificationCompat.Builder(this, AppConst.channelIdDownload)
            .setSmallIcon(R.drawable.ic_download)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentTitle(getString(R.string.audio_cache_notification_title))
            .setContentIntent(activityPendingIntent<AudioPlayActivity>("audioPlay"))
            .addAction(
                R.drawable.ic_stop_black_24dp,
                getString(R.string.stop),
                servicePendingIntent<AudioCacheService>(IntentAction.stop)
            )
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        var shouldCheckIdle = false
        when (intent?.action) {
            ACTION_DOWNLOAD_BOOK -> {
                val bookUrl = intent.getStringExtra("bookUrl")
                val request = bookUrl?.let { BookDownload.get(it) }
                if (request != null && request.id == intent.getStringExtra("downloadId")) {
                    enqueueTask(CacheTask(request.book.bookUrl, 0, Int.MAX_VALUE, request.id))
                    startWorkerIfNeeded()
                } else shouldCheckIdle = true
            }
            ACTION_CACHE_RANGE -> {
                val bookUrl = intent.getStringExtra("bookUrl")
                val startIndex = intent.getIntExtra("start", -1)
                val endIndex = intent.getIntExtra("end", -1)
                if (!bookUrl.isNullOrBlank()
                    && startIndex >= 0
                    && endIndex >= startIndex
                    && AudioCacheManager.isCacheDirAvailable()
                ) {
                    enqueueTask(CacheTask(bookUrl, startIndex, endIndex))
                    startWorkerIfNeeded()
                } else {
                    shouldCheckIdle = true
                }
            }

            IntentAction.stop -> stopSelf()
            else -> shouldCheckIdle = true
        }
        if (shouldCheckIdle && isIdle()) {
            stopSelfResult(startId)
            return START_NOT_STICKY
        }
        return super.onStartCommand(intent, flags, startId)
    }

    override fun onDestroy() {
        workerJob?.cancel()
        BookDownload.cancelPhase(BookDownload.Phase.AUDIO)
        super.onDestroy()
        notificationManager.cancel(NotificationId.AudioCacheService)
    }

    override fun startForegroundNotification() {
        startForeground(NotificationId.AudioCacheService, buildNotification().build())
    }

    private fun enqueueTask(task: CacheTask) {
        synchronized(taskQueue) {
            taskQueue.add(task)
        }
    }

    private fun isIdle(): Boolean {
        val hasTask = synchronized(taskQueue) { taskQueue.isNotEmpty() }
        return workerJob?.isActive != true && !hasTask
    }

    private fun startWorkerIfNeeded() {
        synchronized(taskQueue) {
            if (workerJob?.isActive == true) return
            workerJob = lifecycleScope.launch(IO) {
                val currentWorker = coroutineContext[Job] ?: return@launch
                runWorkerLoop(currentWorker)
            }
        }
    }

    private suspend fun runWorkerLoop(currentWorker: Job) {
        while (true) {
            var workerReplaced = false
            val task = synchronized(taskQueue) {
                if (workerJob !== currentWorker) {
                    workerReplaced = true
                    null
                } else if (taskQueue.isEmpty()) {
                    workerJob = null
                    null
                } else {
                    taskQueue.removeFirst()
                }
            }
            if (workerReplaced) return
            if (task == null) break
            if (task.downloadId == null) processTask(task) else processDownload(task)
        }
        val shouldStop = synchronized(taskQueue) {
            workerJob == null && taskQueue.isEmpty()
        }
        if (shouldStop) {
            stopSelf()
        }
    }

    private suspend fun processTask(task: CacheTask) {
        if (!AudioCacheManager.isCacheDirAvailable()) return
        val book = appDb.bookDao.getBook(task.bookUrl) ?: return
        if (!book.isAudio) return
        val source = appDb.bookSourceDao.getBookSource(book.origin) ?: return
        ensureChapterList(book.bookUrl, source, book)
        val chapterCount = appDb.bookChapterDao.getChapterCount(book.bookUrl)
        if (chapterCount <= 0) return
        val start = min(max(task.startIndex, 0), chapterCount - 1)
        val end = min(max(task.endIndex, start), chapterCount - 1)
        notificationBuilder.setContentTitle(getString(R.string.audio_cache_notification_title))
        titleText = book.name
        doneCount = 0
        failCount = 0
        totalCount = end - start + 1
        val cachedIndexes = AudioCacheManager.listCachedChapterIndexes(book.bookUrl).toMutableSet()
        upNotification()
        for (index in start..end) {
            val chapter = appDb.bookChapterDao.getChapter(book.bookUrl, index)
            if (chapter == null) {
                doneCount++
                upNotification()
                continue
            }
            if (chapter.isVolume) {
                doneCount++
                upNotification()
                continue
            }
            if (index in cachedIndexes) {
                doneCount++
                upNotification()
                continue
            }
            AudioCacheManager.cacheChapter(source, book, chapter)
                .onSuccess {
                    cachedIndexes.add(index)
                    postEvent(
                        EventBus.AUDIO_CACHE_CHANGED,
                        AudioCacheStateChanged(
                            bookUrl = book.bookUrl,
                            chapterIndex = index,
                            cached = true
                        )
                    )
                }
                .onFailure {
                    failCount++
                    AppLog.put("音频缓存失败 ${book.name}-${chapter.title}\n${it.localizedMessage}", it)
                }
            doneCount++
            upNotification()
        }
    }

    private suspend fun processDownload(task: CacheTask) {
        val request = BookDownload.get(task.bookUrl)?.takeIf { it.id == task.downloadId } ?: return
        try {
            val book = requireNotNull(appDb.bookDao.getBook(task.bookUrl)) { "书籍不存在" }
            val source = requireNotNull(appDb.bookSourceDao.getBookSource(book.origin)) { "书源不存在" }
            check(book.isAudio) { "此书不是有声书" }
            ensureChapterList(book.bookUrl, source, book)
            currentCoroutineContext().ensureActive()
            val chapters = appDb.bookChapterDao.getChapterList(book.bookUrl).filterNot { it.isVolume }
            check(chapters.isNotEmpty()) { "目录为空，无法下载整本" }
            request.chapters = chapters
            val folder = BookDownloadFiles.createBookFolder(request.directory, book)
            request.output = folder
            notificationBuilder.setContentTitle(getString(R.string.download_whole_book))
            titleText = "下载 ${book.name}"
            doneCount = 0
            failCount = 0
            totalCount = chapters.size
            var lastError: String? = null
            upNotification()
            for (chapter in chapters) {
                currentCoroutineContext().ensureActive()
                try {
                    val previous = BookDownloadFiles.previousAudio(book, chapter)
                        ?: AudioCacheManager.getCachedUriString(book.bookUrl, chapter.index)?.let {
                            FileDoc.fromUri(it.toUri(), false)
                        }
                    val file = if (previous != null) {
                        BookDownloadFiles.copyAudio(previous, folder, chapter)
                    } else {
                        val uri = AudioCacheManager.cacheChapter(source, book, chapter, folder).getOrThrow()
                        FileDoc.fromUri(uri.toUri(), false)
                    }
                    BookDownloadFiles.rememberAudio(book, chapter, file)
                } catch (e: Exception) {
                    currentCoroutineContext().ensureActive()
                    failCount++
                    lastError = e.localizedMessage
                    AppLog.put("下载音频 ${book.name}-${chapter.title} 失败", e)
                }
                doneCount++
                upNotification()
            }
            currentCoroutineContext().ensureActive()
            val message = "成功 ${totalCount - failCount} 章，失败 $failCount 章\n保存到：$folder" +
                if (failCount > 0) "\n$lastError" else ""
            BookDownload.finish(request, if (failCount == 0) BookDownload.Outcome.COMPLETED else BookDownload.Outcome.FAILED, message)
        } catch (e: Exception) {
            BookDownload.finish(request, if (e is CancellationException) BookDownload.Outcome.CANCELLED else BookDownload.Outcome.FAILED,
                e.localizedMessage ?: "音频下载失败")
            currentCoroutineContext().ensureActive()
            AppLog.put("整本音频下载失败", e)
        } finally {
            request.output?.let { folder ->
                runCatching { if (folder.list()?.isEmpty() == true) folder.delete() }
                    .onFailure { AppLog.put("清理空下载目录失败", it) }
            }
        }
    }

    private suspend fun ensureChapterList(
        bookUrl: String,
        source: io.legado.app.data.entities.BookSource,
        book: io.legado.app.data.entities.Book,
    ) {
        if (appDb.bookChapterDao.getChapterCount(bookUrl) > 0) return
        kotlin.runCatching {
            if (book.tocUrl.isEmpty()) {
                WebBook.getBookInfoAwait(source, book)
                appDb.bookDao.update(book)
            }
            WebBook.getChapterListAwait(source, book).getOrThrow().let { toc ->
                appDb.bookChapterDao.delByBook(book.bookUrl)
                appDb.bookChapterDao.insert(*toc.toTypedArray())
            }
        }.onFailure {
            AppLog.put("音频缓存前加载目录失败 ${book.name}\n${it.localizedMessage}", it, true)
        }
    }

    private fun upNotification() {
        notificationManager.notify(NotificationId.AudioCacheService, buildNotification().build())
    }

    private fun buildNotification(): NotificationCompat.Builder {
        val text = getString(
            R.string.audio_cache_notification_text,
            titleText,
            doneCount,
            totalCount,
            failCount
        )
        return notificationBuilder.setContentText(text)
    }
}
