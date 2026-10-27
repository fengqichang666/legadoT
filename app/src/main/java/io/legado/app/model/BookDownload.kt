package io.legado.app.model

import android.content.Context
import androidx.core.app.NotificationCompat
import io.legado.app.R
import io.legado.app.constant.AppConst
import io.legado.app.constant.AppLog
import io.legado.app.constant.EventBus
import io.legado.app.constant.IntentAction
import io.legado.app.constant.NotificationId
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.help.book.isAudio
import io.legado.app.service.AudioCacheService
import io.legado.app.service.CacheBookService
import io.legado.app.service.ExportBookService
import io.legado.app.utils.FileDoc
import io.legado.app.utils.postEvent
import io.legado.app.utils.startService
import splitties.init.appCtx
import splitties.systemservices.notificationManager
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** 只衔接现有缓存和导出服务；服务销毁即取消，不持久化后台任务。 */
object BookDownload {
    enum class Phase { CACHING, EXPORTING, AUDIO }
    enum class Outcome { COMPLETED, FAILED, CANCELLED }

    class Request(val book: Book, val directory: String, val format: String) {
        val id: String = UUID.randomUUID().toString()
        @Volatile var phase = if (book.isAudio) Phase.AUDIO else Phase.CACHING
            private set
        @Volatile var chapters: List<BookChapter>? = null
        @Volatile var output: FileDoc? = null

        @Synchronized
        fun beginExport(downloadId: String): Boolean {
            if (id != downloadId || phase != Phase.CACHING || chapters == null) return false
            phase = Phase.EXPORTING
            return true
        }
    }

    private val requests = ConcurrentHashMap<String, Request>()
    fun get(bookUrl: String): Request? = requests[bookUrl]
    fun isRunning(bookUrl: String) = requests.containsKey(bookUrl)

    @Synchronized
    fun start(context: Context, book: Book, directory: String, format: String): Boolean {
        if (isRunning(book.bookUrl) || ExportBookService.isExporting(book.bookUrl)) return false
        require(book.isAudio || format in listOf("txt", "epub", "pdf")) { "不支持的下载格式" }
        val request = Request(book, directory, format)
        requests[book.bookUrl] = request
        postEvent(EventBus.BOOK_DOWNLOAD, book.bookUrl)
        try {
            if (book.isAudio) {
                context.startService<AudioCacheService> {
                    action = AudioCacheService.ACTION_DOWNLOAD_BOOK
                    putExtra("bookUrl", book.bookUrl)
                    putExtra("downloadId", request.id)
                }
            } else {
                context.startService<CacheBookService> {
                    action = IntentAction.start
                    putExtra("bookUrl", book.bookUrl)
                    putExtra("start", 0)
                    putExtra("end", -1)
                    putExtra("downloadId", request.id)
                }
            }
        } catch (e: Exception) {
            finish(request, Outcome.FAILED, e.localizedMessage ?: "无法启动下载服务")
            throw e
        }
        return true
    }

    @Synchronized
    fun textCached(bookUrl: String, downloadId: String) {
        val request = requests[bookUrl] ?: return
        if (!request.beginExport(downloadId)) return
        try {
            appCtx.startService<ExportBookService> {
                action = IntentAction.start
                putExtra("bookUrl", bookUrl)
                putExtra("exportPath", request.directory)
                putExtra("exportType", request.format)
                putExtra("downloadId", request.id)
            }
        } catch (e: Exception) {
            AppLog.put("启动下载文件保存失败", e)
            finish(request, Outcome.FAILED, e.localizedMessage ?: "无法启动文件保存")
        }
    }

    fun cancelPhase(phase: Phase) {
        requests.values.filter { it.phase == phase }.forEach {
            finish(it, Outcome.CANCELLED, "已取消，已完成的缓存和音频文件保留")
        }
    }

    fun cancelCache(bookUrl: String) {
        get(bookUrl)?.takeIf { it.phase == Phase.CACHING }?.let {
            finish(it, Outcome.CANCELLED, "下载已取消，已完成的缓存保留")
        }
    }

    fun finish(request: Request, outcome: Outcome, message: String) {
        if (!requests.remove(request.book.bookUrl, request)) return
        val status = when (outcome) {
            Outcome.COMPLETED -> "下载完成"
            Outcome.FAILED -> "下载未完成"
            Outcome.CANCELLED -> "下载已取消"
        }
        val notification = NotificationCompat.Builder(appCtx, AppConst.channelIdDownload)
            .setSmallIcon(R.drawable.ic_download)
            .setContentTitle("${request.book.name} · $status")
            .setContentText(message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setAutoCancel(true)
            .build()
        runCatching {
            notificationManager.notify("book-download:${request.book.bookUrl}", NotificationId.BookDownloadComplete, notification)
        }.onFailure { AppLog.put("显示下载结果通知失败", it) }
        postEvent(EventBus.BOOK_DOWNLOAD, request.book.bookUrl)
    }
}
