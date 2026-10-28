package io.legado.app.help.source

import com.google.gson.GsonBuilder
import com.google.gson.JsonElement
import io.legado.app.data.entities.BookSource
import java.net.URI
import java.io.OutputStream
import java.io.OutputStreamWriter
import java.security.DigestOutputStream
import java.security.MessageDigest
import java.util.Locale

/** 清理专用的比较规则，不改变书源导入和数据库的身份判定。 */
object SourceCleanup {
    data class Snapshot(val source: BookSource, val state: Map<String, String?>) {
        val definition: JsonElement = gson.toJsonTree(source)
    }

    data class Candidate(
        val bookSourceUrl: String,
        val bookSourceName: String,
        val reason: String,
        val snapshot: Snapshot? = null,
        val definitionFingerprint: String? = null,
        val executionFingerprint: String? = null,
        val survivorUrl: String? = null
    ) {
        constructor(snapshot: Snapshot, reason: String) : this(
            snapshot.source.bookSourceUrl, snapshot.source.bookSourceName, reason, snapshot
        )
    }

    data class Preview(val candidates: List<Candidate>, val kept: List<String>)

    data class DuplicateSource(
        val bookSourceUrl: String,
        val bookSourceName: String,
        val enabled: Boolean,
        val lastUpdateTime: Long,
        val customOrder: Int,
        val definitionFingerprint: String,
        val executionFingerprint: String
    )

    private val gson = GsonBuilder().serializeNulls().create()
    private val managementFields = listOf(
        "bookSourceName", "bookSourceGroup", "bookSourceComment", "variableComment",
        "customOrder", "enabled", "enabledExplore", "lastUpdateTime", "respondTime", "weight"
    )
    private val hexDigits = "0123456789abcdef"

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

    private fun executionKey(source: BookSource, state: Map<String, String?>): JsonElement? =
        normalizedUrl(source.bookSourceUrl)?.let { url ->
            gson.toJsonTree(source).asJsonObject.apply {
                managementFields.forEach { remove(it) }
                addProperty("bookSourceUrl", url)
                add("runtimeState", gson.toJsonTree(state))
            }
        }

    private fun fingerprint(value: JsonElement): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val sink = object : OutputStream() {
            override fun write(b: Int) = Unit
            override fun write(b: ByteArray, off: Int, len: Int) = Unit
        }
        OutputStreamWriter(DigestOutputStream(sink, digest), Charsets.UTF_8.name()).use { output ->
            gson.toJson(value, output)
        }
        return buildString(64) {
            digest.digest().forEach {
                val byte = it.toInt() and 0xff
                append(hexDigits[byte ushr 4])
                append(hexDigits[byte and 0x0f])
            }
        }
    }

    fun duplicateSource(source: BookSource, state: Map<String, String?>): DuplicateSource? {
        if (normalizedUrl(source.bookSourceUrl) == null) return null
        val definition = gson.toJsonTree(source)
        val definitionFingerprint = fingerprint(definition)
        val stateJson = gson.toJsonTree(state.toSortedMap())
        val key = definition.asJsonObject.apply {
            managementFields.forEach { remove(it) }
            addProperty("bookSourceUrl", normalizedUrl(source.bookSourceUrl))
            add("runtimeState", stateJson)
        }
        return DuplicateSource(
            source.bookSourceUrl, source.bookSourceName, source.enabled, source.lastUpdateTime,
            source.customOrder, definitionFingerprint, fingerprint(key)
        )
    }

    fun sameExecution(a: BookSource, aState: Map<String, String?>, b: BookSource, bState: Map<String, String?>): Boolean {
        val aKey = executionKey(a, aState) ?: return false
        return aKey == executionKey(b, bState)
    }

    class DuplicateScanner(private val inUse: Set<String>) {
        private class Group(first: DuplicateSource, inUse: Boolean) {
            var count = 1
            var best: DuplicateSource? = if (inUse) null else first
            val inUseSources = arrayListOf<DuplicateSource>().apply { if (inUse) add(first) }
            val candidates = arrayListOf<DuplicateSource>()

            fun add(source: DuplicateSource, inUse: Boolean) {
                count++
                if (inUse) {
                    if (inUseSources.isEmpty()) best?.let(candidates::add)
                    best = null
                    inUseSources.add(source)
                } else if (inUseSources.isNotEmpty()) {
                    candidates.add(source)
                } else {
                    val current = best!!
                    if (SourceCleanup.preferred(source, current)) {
                        candidates.add(current)
                        best = source
                    } else {
                        candidates.add(source)
                    }
                }
            }
        }

        private val groups = LinkedHashMap<String, Group>()

        fun add(source: DuplicateSource) {
            val inUse = source.bookSourceUrl in inUse
            val group = groups[source.executionFingerprint]
            if (group == null) groups[source.executionFingerprint] = Group(source, inUse)
            else group.add(source, inUse)
        }

        fun preview(): Preview {
            val candidates = arrayListOf<Candidate>()
            val kept = arrayListOf<String>()
            groups.values.forEach { group ->
                if (group.count < 2) return@forEach
                val survivors = if (group.inUseSources.isNotEmpty()) {
                    group.inUseSources.sortedWith(compareBy<DuplicateSource> { it.customOrder }.thenBy { it.bookSourceUrl })
                } else listOf(group.best!!)
                val survivor = survivors.first()
                survivors.forEach {
                    val reason = if (it.bookSourceUrl in inUse) "书架正在使用" else "同组优先保留"
                    kept.add("${it.bookSourceName}：$reason\n${it.bookSourceUrl}")
                }
                group.candidates.forEach { candidate ->
                    candidates.add(Candidate(
                        candidate.bookSourceUrl, candidate.bookSourceName,
                        "同站且规则、用户变量和登录状态相同；保留 ${survivor.bookSourceName}",
                        definitionFingerprint = candidate.definitionFingerprint,
                        executionFingerprint = candidate.executionFingerprint,
                        survivorUrl = survivor.bookSourceUrl
                    ))
                }
            }
            return Preview(candidates, kept)
        }
    }

    private fun preferred(a: DuplicateSource, b: DuplicateSource): Boolean = when {
        a.enabled != b.enabled -> a.enabled
        a.lastUpdateTime != b.lastUpdateTime -> a.lastUpdateTime > b.lastUpdateTime
        a.customOrder != b.customOrder -> a.customOrder < b.customOrder
        else -> a.bookSourceUrl < b.bookSourceUrl
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
