package io.legado.app.help.source

import com.google.gson.GsonBuilder
import com.google.gson.JsonElement
import io.legado.app.data.entities.BookSource
import java.net.URI
import java.util.Locale

/** 清理专用的比较规则，不改变书源导入和数据库的身份判定。 */
object SourceCleanup {
    data class Snapshot(val source: BookSource, val state: Map<String, String?>) {
        val definition: JsonElement = gson.toJsonTree(source)
        val key: JsonElement? = executionKey(source)?.apply {
            add("runtimeState", gson.toJsonTree(state))
        }
    }

    data class Candidate(val snapshot: Snapshot, val reason: String)
    data class Preview(val candidates: List<Candidate>, val kept: List<String>)

    private val gson = GsonBuilder().serializeNulls().create()
    private val managementFields = listOf(
        "bookSourceName", "bookSourceGroup", "bookSourceComment", "variableComment",
        "customOrder", "enabled", "enabledExplore", "lastUpdateTime", "respondTime", "weight"
    )

    fun normalizedUrl(url: String): String? = runCatching {
        val uri = URI(url)
        val scheme = uri.scheme?.lowercase(Locale.ROOT) ?: return null
        val host = uri.host?.lowercase(Locale.ROOT) ?: return null
        if (scheme != "http" && scheme != "https") return null
        val port = when {
            scheme == "http" && uri.port == 80 -> -1
            scheme == "https" && uri.port == 443 -> -1
            else -> uri.port
        }
        buildString {
            append(scheme).append("://")
            uri.rawUserInfo?.let { append(it).append('@') }
            append(host)
            if (port != -1) append(':').append(port)
            append(uri.rawPath.ifEmpty { "/" })
            uri.rawQuery?.let { append('?').append(it) }
            uri.rawFragment?.let { append('#').append(it) }
        }
    }.getOrNull()

    private fun executionKey(source: BookSource) = normalizedUrl(source.bookSourceUrl)?.let { url ->
        gson.toJsonTree(source).asJsonObject.apply {
            managementFields.forEach { remove(it) }
            addProperty("bookSourceUrl", url)
        }
    }

    fun duplicates(sources: List<Snapshot>, inUse: Set<String>): Preview {
        val candidates = arrayListOf<Candidate>()
        val kept = arrayListOf<String>()
        val order = compareByDescending<Snapshot> { it.source.enabled }
            .thenByDescending { it.source.lastUpdateTime }
            .thenBy { it.source.customOrder }
            .thenBy { it.source.bookSourceUrl }
        sources.filter { it.key != null }.groupBy { it.key }.values.forEach { group ->
            if (group.size < 2) return@forEach
            val protected = group.filter { it.source.bookSourceUrl in inUse }
            val survivors = protected.ifEmpty { listOf(group.minWith(order)) }
            val survivorUrls = survivors.map { it.source.bookSourceUrl }.toSet()
            survivors.forEach {
                val reason = if (it.source.bookSourceUrl in inUse) "书架正在使用" else "同组优先保留"
                kept.add("${it.source.bookSourceName}：$reason\n${it.source.bookSourceUrl}")
            }
            group.filterNot { it.source.bookSourceUrl in survivorUrls }.forEach {
                candidates.add(Candidate(it, "同站且规则、用户变量和登录状态相同；保留 ${survivors.first().source.bookSourceName}"))
            }
        }
        return Preview(candidates, kept)
    }

    fun invalid(sources: List<Snapshot>, inUse: Set<String>, completed: Boolean,
                failures: Map<String, List<String>>, verificationRequired: Set<String> = emptySet()): Preview {
        check(completed) { "校验已取消或中断，本次不生成删除候选" }
        val candidates = sources.filterNot {
            it.source.bookSourceUrl in inUse || it.source.bookSourceUrl in verificationRequired
        }.mapNotNull { snapshot ->
            val reasons = invalidReasons(failures[snapshot.source.bookSourceUrl].orEmpty())
            if (reasons.isEmpty()) null else Candidate(snapshot, reasons.joinToString("、"))
        }
        val kept = sources.filter { it.source.bookSourceUrl in inUse }
            .map { "${it.source.bookSourceName}：书架正在使用" }
        val manual = sources.filter { it.source.bookSourceUrl in verificationRequired && it.source.bookSourceUrl !in inUse }
            .map { "${it.source.bookSourceName}：需要手动验证，本轮已跳过；请单独验证后重新校验\n${it.source.bookSourceUrl}" }
        return Preview(candidates, kept + manual + "需要手动验证、仅超时、未校验和校验通过的书源不列入删除候选")
    }

    // 只接受校验器本轮产生的分类，不把用户自定义分组或仅超时当成删除依据。
    fun invalidReasons(groups: Collection<String>): List<String> = groups.filter {
        it in setOf(
            "js失效", "网站失效", "搜索失效", "发现失效", "搜索正文失效",
            "搜索目录失效", "发现正文失效", "发现目录失效"
        )
    }
}
