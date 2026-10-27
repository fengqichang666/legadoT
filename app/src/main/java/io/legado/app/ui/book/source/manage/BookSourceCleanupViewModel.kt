package io.legado.app.ui.book.source.manage

import android.app.Application
import androidx.lifecycle.MutableLiveData
import io.legado.app.base.BaseViewModel
import io.legado.app.constant.AppLog
import io.legado.app.data.appDb
import io.legado.app.data.entities.BookSource
import io.legado.app.help.book.isNotShelf
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

    private fun inUse() = appDb.bookDao.all.filterNot { it.isNotShelf }.map { it.origin }.toSet()

    private fun snapshot(source: BookSource): SourceCleanup.Snapshot {
        val url = source.bookSourceUrl
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
        return SourceCleanup.Snapshot(source, state)
    }

    fun scan(duplicates: Boolean) {
        if (busy.value == true) return
        busy.value = true
        duplicateScan = duplicates
        execute {
            val protected = inUse()
            if (duplicates) {
                SourceCleanup.duplicates(appDb.bookSourceDao.all.map(::snapshot), protected)
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
                val current = appDb.bookSourceDao.all.map(::snapshot)
                val duplicates = if (duplicateScan) {
                    SourceCleanup.duplicates(current, protected).candidates
                        .map { it.snapshot.source.bookSourceUrl }.toSet()
                } else null
                val byUrl = current.associateBy { it.source.bookSourceUrl }
                val safe = selected.mapNotNull { candidate ->
                    val old = candidate.snapshot
                    val url = old.source.bookSourceUrl
                    val now = byUrl[url] ?: return@mapNotNull null
                    if (url in protected || (duplicates != null && url !in duplicates)) return@mapNotNull null
                    if (old.definition != now.definition || old.state != now.state) {
                        return@mapNotNull null
                    }
                    now.source
                }
                SourceHelp.deleteBookSources(safe)
                deleted = safe.size
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
}
