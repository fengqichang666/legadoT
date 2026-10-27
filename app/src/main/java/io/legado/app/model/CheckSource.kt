package io.legado.app.model

import android.content.Context
import com.google.gson.JsonElement
import io.legado.app.R
import io.legado.app.constant.IntentAction
import io.legado.app.data.entities.BookSourcePart
import io.legado.app.help.CacheManager
import io.legado.app.help.IntentData
import io.legado.app.service.CheckSourceService
import io.legado.app.utils.startService
import io.legado.app.utils.toastOnUi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import splitties.init.appCtx

object CheckSource {
    data class Result(val id: String?, val completed: Boolean, val failures: Map<String, List<String>>,
                      val definitions: Map<String, JsonElement>, val verificationRequired: Set<String>)
    private val mutableResult = MutableStateFlow<Result?>(null)
    val result = mutableResult.asStateFlow()
    @Volatile var isRunning = false
        private set
    var keyword = "我的"

    //校验设置
    var timeout = CacheManager.getLong("checkSourceTimeout") ?: 180000L
    var checkSearch = CacheManager.get("checkSearch")?.toBoolean() ?: true
    var checkDiscovery = CacheManager.get("checkDiscovery")?.toBoolean() ?: true
    var checkInfo = CacheManager.get("checkInfo")?.toBoolean() ?: true
    var checkCategory = CacheManager.get("checkCategory")?.toBoolean() ?: true
    var checkContent = CacheManager.get("checkContent")?.toBoolean() ?: true
    val summary get() = upSummary()

    @Synchronized
    fun start(context: Context, sources: List<BookSourcePart>, id: String? = null): Boolean {
        if (isRunning) {
            context.toastOnUi("已有书源在校验,等完成后再试")
            return false
        }
        isRunning = true
        mutableResult.value = null
        val selectedIds = sources.map {
            it.bookSourceUrl
        }
        IntentData.put("checkSourceSelectedIds", selectedIds)
        try {
            context.startService<CheckSourceService> {
                action = IntentAction.start
                putExtra("checkRunId", id)
            }
        } catch (e: Exception) {
            isRunning = false
            throw e
        }
        return true
    }

    fun finish(id: String?, completed: Boolean, failures: Map<String, List<String>>,
               definitions: Map<String, JsonElement>, verificationRequired: Set<String>) {
        isRunning = false
        mutableResult.value = Result(id, completed, failures, definitions, verificationRequired)
    }

    fun stop(context: Context) {
        context.startService<CheckSourceService> {
            action = IntentAction.stop
        }
    }

    fun resume(context: Context) {
        context.startService<CheckSourceService> {
            action = IntentAction.resume
        }
    }

    fun putConfig() {
        CacheManager.put("checkSourceTimeout", timeout)
        CacheManager.put("checkSearch", checkSearch)
        CacheManager.put("checkDiscovery", checkDiscovery)
        CacheManager.put("checkInfo", checkInfo)
        CacheManager.put("checkCategory", checkCategory)
        CacheManager.put("checkContent", checkContent)
    }

    private fun upSummary(): String {
        var checkItem = ""
        if (checkSearch) checkItem = "$checkItem ${appCtx.getString(R.string.search)}"
        if (checkDiscovery) checkItem = "$checkItem ${appCtx.getString(R.string.discovery)}"
        if (checkInfo) checkItem = "$checkItem ${appCtx.getString(R.string.source_tab_info)}"
        if (checkCategory) checkItem = "$checkItem ${appCtx.getString(R.string.chapter_list)}"
        if (checkContent) checkItem = "$checkItem ${appCtx.getString(R.string.main_body)}"
        return appCtx.getString(
            R.string.check_source_config_summary,
            (timeout / 1000).toString(),
            checkItem
        )
    }
}
