package io.legado.app.service

import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.lifecycle.lifecycleScope
import com.google.gson.JsonElement
import com.script.ScriptException
import io.legado.app.R
import io.legado.app.base.BaseService
import io.legado.app.constant.AppConst
import io.legado.app.constant.AppLog
import io.legado.app.constant.BookSourceType
import io.legado.app.constant.EventBus
import io.legado.app.constant.IntentAction
import io.legado.app.constant.NotificationId
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookSource
import io.legado.app.exception.ContentEmptyException
import io.legado.app.exception.NoStackTraceException
import io.legado.app.exception.TocEmptyException
import io.legado.app.help.IntentData
import io.legado.app.help.config.AppConfig
import io.legado.app.help.source.exploreKinds
import io.legado.app.help.source.SourceCleanup
import io.legado.app.help.source.SourceVerificationHelp
import io.legado.app.model.CheckSource
import io.legado.app.model.Debug
import io.legado.app.model.webBook.WebBook
import io.legado.app.ui.book.source.manage.BookSourceActivity
import io.legado.app.utils.activityPendingIntent
import io.legado.app.utils.onEachParallel
import io.legado.app.utils.postEvent
import io.legado.app.utils.servicePendingIntent
import io.legado.app.utils.toastOnUi
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.htmlunit.corejs.javascript.WrappedException
import splitties.init.appCtx
import splitties.systemservices.notificationManager
import java.util.concurrent.Executors
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.coroutineContext
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.math.min

/**
 * 校验书源
 */
class CheckSourceService : BaseService() {
    private var threadCount = AppConfig.threadCount
    private var searchCoroutine =
        Executors.newFixedThreadPool(min(threadCount, AppConst.MAX_THREAD)).asCoroutineDispatcher()
    private var notificationMsg = appCtx.getString(R.string.service_starting)
    private var checkJob: Job? = null
    private var originSize = 0
    private var finishCount = 0
    private var runId: String? = null
    @Volatile private var completed = false
    private val failures = ConcurrentHashMap<String, List<String>>()
    private val definitions = ConcurrentHashMap<String, JsonElement>()
    private val checkedDefinitions = ConcurrentHashMap<String, JsonElement>()
    private val verificationRequired = ConcurrentHashMap.newKeySet<String>()

    private val notificationBuilder by lazy {
        NotificationCompat.Builder(this, AppConst.channelIdReadAloud)
            .setSmallIcon(R.drawable.ic_network_check)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentTitle(getString(R.string.check_book_source))
            .setContentIntent(
                activityPendingIntent<BookSourceActivity>("activity")
            )
            .addAction(
                R.drawable.ic_stop_black_24dp,
                getString(R.string.cancel),
                servicePendingIntent<CheckSourceService>(IntentAction.stop)
            )
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            IntentAction.start -> IntentData.get<List<String>>("checkSourceSelectedIds")?.let {
                check(it, intent.getStringExtra("checkRunId"))
            }

            IntentAction.resume -> upNotification()
            IntentAction.stop -> stopSelf()
        }
        return super.onStartCommand(intent, flags, startId)
    }

    override fun onDestroy() {
        super.onDestroy()
        Debug.finishChecking()
        searchCoroutine.close()
        CheckSource.finish(runId, completed, failures.toMap(), checkedDefinitions.toMap(), verificationRequired.toSet())
        postEvent(EventBus.CHECK_SOURCE_DONE, 0)
        notificationManager.cancel(NotificationId.CheckSourceService)
    }

    private fun check(ids: List<String>, id: String?) {
        if (checkJob?.isActive == true) {
            toastOnUi("已有书源在校验,等完成后再试")
            return
        }
        runId = id
        checkJob = lifecycleScope.launch(searchCoroutine) {
            flow {
                for (origin in ids) {
                    appDb.bookSourceDao.getBookSource(origin)?.let {
                        emit(it)
                    }
                }
            }.onStart {
                originSize = ids.size
                finishCount = 0
                notificationMsg = getString(R.string.progress_show, "", 0, originSize)
                upNotification()
            }.onEachParallel(threadCount) {
                if (runId != null) definitions[it.bookSourceUrl] = SourceCleanup.Snapshot(it, emptyMap()).definition
                checkSource(it)
            }.onEach {
                finishCount++
                notificationMsg = getString(
                    R.string.progress_show,
                    it.bookSourceName,
                    finishCount,
                    originSize
                )
                upNotification()
                if (runId == null) appDb.bookSourceDao.update(it) else appDb.runInTransaction {
                    val current = appDb.bookSourceDao.getBookSource(it.bookSourceUrl)
                    if (current != null && SourceCleanup.Snapshot(current, emptyMap()).definition == definitions[it.bookSourceUrl]) {
                        appDb.bookSourceDao.update(it)
                        if (failures.containsKey(it.bookSourceUrl) || it.bookSourceUrl in verificationRequired) {
                            checkedDefinitions[it.bookSourceUrl] = SourceCleanup.Snapshot(it, emptyMap()).definition
                        }
                    } else {
                        // 校验期间编辑过的书源不写回旧配置，也不作为本轮删除候选。
                        failures.remove(it.bookSourceUrl)
                        verificationRequired.remove(it.bookSourceUrl)
                    }
                    definitions.remove(it.bookSourceUrl)
                }
            }.onCompletion { cause ->
                completed = cause == null && finishCount == originSize
                stopSelf()
            }.catch { AppLog.put("校验书源中断", it, true) }.collect()
        }
    }

    private suspend fun checkSource(source: BookSource) {
        val verification = if (runId != null) SourceVerificationHelp.CheckContext() else null
        val result = kotlin.runCatching {
            withContext(verification ?: EmptyCoroutineContext) {
                withTimeout(CheckSource.timeout) {
                    doCheckSource(source)
                }
            }
        }
        coroutineContext.ensureActive()
        if (verification?.required == true) {
            // 验证请求可能被 JS 捕获后变成空结果；仍优先跳过，不能作为失败候选。
            source.removeInvalidGroups()
            source.removeErrorComment()
            source.addGroup(SourceVerificationHelp.MANUAL_VERIFICATION)
            verificationRequired.add(source.bookSourceUrl)
            Debug.updateFinalMessage(source.bookSourceUrl, "已跳过：需要手动验证")
        } else {
            result.onSuccess {
                Debug.updateFinalMessage(source.bookSourceUrl, "校验成功")
            }.onFailure {
                when (it) {
                    is TimeoutCancellationException -> source.addGroup("校验超时")
                    is ScriptException, is WrappedException -> source.addGroup("js失效")
                    !is NoStackTraceException -> source.addGroup("网站失效")
                }
                source.addErrorComment(it)
                Debug.updateFinalMessage(source.bookSourceUrl, "校验失败:${it.localizedMessage}")
            }
        }
        source.respondTime = Debug.getRespondTime(source.bookSourceUrl)
        if (runId != null && verification?.required != true) {
            val reasons = SourceCleanup.invalidReasons(source.getInvalidGroupNames().split(',').map { it.trim() })
            if (reasons.isNotEmpty()) failures[source.bookSourceUrl] = reasons
        }
    }

    private suspend fun doCheckSource(source: BookSource) {
        Debug.startChecking(source)
        source.removeInvalidGroups()
        source.removeGroup(SourceVerificationHelp.MANUAL_VERIFICATION)
        source.removeErrorComment()
        //校验搜索书籍
        if (CheckSource.checkSearch) {
            val searchWord = source.getCheckKeyword(CheckSource.keyword)
            if (!source.searchUrl.isNullOrBlank() || source.isJsSource()) {
                source.removeGroup("搜索链接规则为空")
                val searchBooks = WebBook.searchBookAwait(source, searchWord)
                if (searchBooks.isEmpty()) {
                    source.addGroup("搜索失效")
                } else {
                    source.removeGroup("搜索失效")
                    checkBook(searchBooks.first().toBook(), source)
                }
            } else {
                source.addGroup("搜索链接规则为空")
            }
        }
        //校验发现书籍(JS源声明 exploreUrl 后与声明式同管道,经 WebBook 分派)
        if (CheckSource.checkDiscovery) {
            if (!source.exploreUrl.isNullOrBlank()) {
                val url = source.exploreKinds().firstOrNull {
                    !it.url.isNullOrBlank()
                }?.url
                if (url.isNullOrBlank()) {
                    source.addGroup("发现规则为空")
                } else {
                    source.removeGroup("发现规则为空")
                    val exploreBooks = WebBook.exploreBookAwait(source, url)
                    if (exploreBooks.isEmpty()) {
                        source.addGroup("发现失效")
                    } else {
                        source.removeGroup("发现失效")
                        checkBook(exploreBooks.first().toBook(), source, false)
                    }
                }
            }
        }
        val finalCheckMessage = source.getInvalidGroupNames()
        if (finalCheckMessage.isNotBlank()) {
            throw NoStackTraceException(finalCheckMessage)
        }
    }

    /**
     *校验书源的详情目录正文
     */
    private suspend fun checkBook(book: Book, source: BookSource, isSearchBook: Boolean = true) {
        kotlin.runCatching {
            if (!CheckSource.checkInfo) {
                return
            }
            //校验详情
            if (book.tocUrl.isBlank()) {
                WebBook.getBookInfoAwait(source, book)
            }
            if (!CheckSource.checkCategory || source.bookSourceType == BookSourceType.file) {
                return
            }
            //校验目录
            val toc = WebBook.getChapterListAwait(source, book).getOrThrow().asSequence()
                .filter { !(it.isVolume && it.url.startsWith(it.title)) }
                .take(2)
                .toList()
            val nextChapterUrl = toc.getOrNull(1)?.url ?: toc.first().url
            if (!CheckSource.checkContent) {
                return
            }
            //校验正文
            WebBook.getContentAwait(
                bookSource = source,
                book = book,
                bookChapter = toc.first(),
                nextChapterUrl = nextChapterUrl,
                needSave = false
            )
        }.onFailure {
            val bookType = if (isSearchBook) "搜索" else "发现"
            when (it) {
                is ContentEmptyException -> source.addGroup("${bookType}正文失效")
                is TocEmptyException -> source.addGroup("${bookType}目录失效")
                else -> throw it
            }
        }.onSuccess {
            val bookType = if (isSearchBook) "搜索" else "发现"
            source.removeGroup("${bookType}目录失效")
            source.removeGroup("${bookType}正文失效")
        }
    }

    private fun upNotification() {
        notificationBuilder.setContentText(notificationMsg)
        notificationBuilder.setProgress(originSize, finishCount, false)
        postEvent(EventBus.CHECK_SOURCE, notificationMsg)
        notificationManager.notify(NotificationId.CheckSourceService, notificationBuilder.build())
    }

    /**
     * 更新通知
     */
    override fun startForegroundNotification() {
        notificationBuilder.setContentText(notificationMsg)
        notificationBuilder.setProgress(originSize, finishCount, false)
        postEvent(EventBus.CHECK_SOURCE, notificationMsg)
        startForeground(NotificationId.CheckSourceService, notificationBuilder.build())
    }

}
