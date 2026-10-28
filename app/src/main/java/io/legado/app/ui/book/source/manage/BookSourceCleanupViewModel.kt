package io.legado.app.ui.book.source.manage

import android.app.Application
import androidx.lifecycle.MutableLiveData
import io.legado.app.base.BaseViewModel
import io.legado.app.constant.AppLog
import io.legado.app.data.appDb
import io.legado.app.data.entities.BookSource
import io.legado.app.help.http.CookieManager
import io.legado.app.help.source.SourceCleanup
import io.legado.app.help.source.SourceHelp
import io.legado.app.model.CheckSource
import io.legado.app.utils.NetworkUtils
import kotlinx.coroutines.flow.first
import java.util.UUID

class BookSourceCleanupViewModel(application: Application) : BaseViewModel(application) {
    val busy = MutableLiveData(false)
    val preview = MutableLiveData<SourceCleanup.Preview?>()
    val message = MutableLiveData<String?>()
    private var duplicateScan = false

    private fun inUse() = appDb.bookDao.allShelfOrigins().toSet()

    private fun runtimeState(url: String): Map<String, String?> {
        val state = appDb.cacheDao.getSourceVariables(url, System.currentTimeMillis()).associate {
            val key = if (it.key.startsWith("v_${url}_")) {
                "v_" + it.key.removePrefix("v_${url}_")
            } else {
                it.key.removeSuffix(url)
            }
            key to it.value
        }.toMutableMap()
        if (SourceCleanup.normalizedUrl(url) != null) {
            state["cookie"] = CookieManager.getCookieNoSession(url)
            CookieManager.getSessionCookie(NetworkUtils.getSubDomain(url))?.let { state["sessionCookie"] = it }
        }
        return state
    }

    private fun snapshot(source: BookSource) = SourceCleanup.Snapshot(source, runtimeState(source.bookSourceUrl))

    private fun duplicateSource(source: BookSource) =
        if (SourceCleanup.normalizedUrl(source.bookSourceUrl) == null) null
        else SourceCleanup.duplicateSource(source, runtimeState(source.bookSourceUrl))

    private fun scanDuplicates(protected: Set<String>): SourceCleanup.Preview {
        val scanner = SourceCleanup.DuplicateScanner(protected)
        var afterUrl: String? = null
        while (true) {
            val page = appDb.bookSourceDao.cleanupPage(afterUrl, CLEANUP_PAGE_SIZE)
            if (page.isEmpty()) break
            page.forEach { source -> duplicateSource(source)?.let(scanner::add) }
            afterUrl = page.last().bookSourceUrl
            if (page.size < CLEANUP_PAGE_SIZE) break
        }
        return scanner.preview()
    }

    fun scan(duplicates: Boolean) {
        if (busy.value == true) return
        busy.value = true
        duplicateScan = duplicates
        execute {
            val protected = inUse()
            if (duplicates) {
                scanDuplicates(protected)
            } else {
                val sources = appDb.bookSourceDao.allPart.filterNot { it.bookSourceUrl in protected }
                if (sources.isEmpty()) {
                    SourceCleanup.Preview(emptyList(), listOf("没有可校验的未使用书源；书架使用中的书源已排除"))
                } else {
                    val id = UUID.randomUUID().toString()
                    check(CheckSource.start(context, sources, id)) { "已有书源在校验，请稍后重试" }
                    val result = CheckSource.result.first { it?.id == id }!!
                    val current = appDb.bookSourceDao.all.map(::snapshot)
                    val unchanged = current.filter {
                        it.definition == result.definitions[it.source.bookSourceUrl]
                    }.map { it.source.bookSourceUrl }.toSet()
                    SourceCleanup.invalid(current, inUse(), result.completed,
                        result.failures.filterKeys { it in unchanged }, result.verificationRequired.intersect(unchanged))
                }
            }
        }.onSuccess {
            preview.value = it
        }.onError {
            AppLog.put("书源清理扫描失败", it)
            message.value = it.localizedMessage ?: "扫描失败"
        }.onFinally {
            busy.value = false
        }
    }

    fun delete(selected: List<SourceCleanup.Candidate>) {
        if (busy.value == true || selected.isEmpty()) return
        busy.value = true
        execute {
            var deleted = 0
            // 预览后书籍可能入架、书源可能被编辑；删除前重新判定并在同一事务内执行。
            appDb.runInTransaction {
                val protected = inUse()
                if (duplicateScan) {
                    val current = scanDuplicates(protected).candidates.associateBy { it.bookSourceUrl }
                    val safeUrls = selected.mapNotNull { old ->
                        val now = current[old.bookSourceUrl] ?: return@mapNotNull null
                        if (old.bookSourceUrl in protected ||
                            old.definitionFingerprint != now.definitionFingerprint ||
                            old.executionFingerprint != now.executionFingerprint) return@mapNotNull null

                        val source = appDb.bookSourceDao.getBookSource(old.bookSourceUrl)
                            ?: return@mapNotNull null
                        val survivorUrl = now.survivorUrl ?: return@mapNotNull null
                        val survivor = appDb.bookSourceDao.getBookSource(survivorUrl)
                            ?: return@mapNotNull null
                        val state = runtimeState(old.bookSourceUrl)
                        val survivorState = runtimeState(survivorUrl)
                        val fresh = duplicateSource(source) ?: return@mapNotNull null
                        if (fresh.definitionFingerprint != old.definitionFingerprint ||
                            fresh.executionFingerprint != old.executionFingerprint ||
                            !SourceCleanup.sameExecution(source, state, survivor, survivorState)) {
                            return@mapNotNull null
                        }
                        old.bookSourceUrl
                    }
                    if (safeUrls.isNotEmpty()) SourceHelp.deleteBookSourcesByUrl(safeUrls)
                    deleted = safeUrls.size
                }
                if (!duplicateScan) {
                    val current = appDb.bookSourceDao.all.map(::snapshot)
                    val byUrl = current.associateBy { it.source.bookSourceUrl }
                    val safe = selected.mapNotNull { candidate ->
                        val old = candidate.snapshot ?: return@mapNotNull null
                        val url = old.source.bookSourceUrl
                        val now = byUrl[url] ?: return@mapNotNull null
                        if (url in protected) return@mapNotNull null
                        if (old.definition != now.definition || old.state != now.state) {
                            return@mapNotNull null
                        }
                        now.source
                    }
                    SourceHelp.deleteBookSources(safe)
                    deleted = safe.size
                }
            }
            "已删除 $deleted 个书源，跳过 ${selected.size - deleted} 个已变化或正在使用的书源"
        }.onSuccess {
            message.value = it
        }.onError {
            AppLog.put("删除书源失败", it)
            message.value = "删除失败：${it.localizedMessage}"
        }.onFinally {
            busy.value = false
        }
    }

    private companion object {
        const val CLEANUP_PAGE_SIZE = 32
    }
}
