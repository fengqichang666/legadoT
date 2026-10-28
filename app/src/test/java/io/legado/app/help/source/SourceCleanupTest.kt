package io.legado.app.help.source

import io.legado.app.data.entities.BookSource
import org.junit.Assert.*
import org.junit.Test

class SourceCleanupTest {
    private fun source(url: String, name: String = url) = BookSource(bookSourceUrl = url, bookSourceName = name)
    private fun snapshot(source: BookSource, state: Map<String, String?> = emptyMap()) = SourceCleanup.Snapshot(source, state)
    private fun duplicates(sources: List<SourceCleanup.Snapshot>, inUse: Set<String>): SourceCleanup.Preview {
        val scanner = SourceCleanup.DuplicateScanner(inUse)
        sources.forEach { SourceCleanup.duplicateSource(it.source, it.state)?.let(scanner::add) }
        return scanner.preview()
    }

    @Test
    fun onlyNormalizesHostDefaultPortAndRootPath() {
        assertEquals(SourceCleanup.normalizedUrl("HTTPS://EXAMPLE.COM:443"), SourceCleanup.normalizedUrl("https://example.com/"))
        val base = SourceCleanup.normalizedUrl("https://example.com/")
        listOf("http://example.com/", "https://www.example.com/", "https://example.com/a",
            "https://example.com:8443/", "https://example.com/?a=1").forEach {
            assertNotEquals(base, SourceCleanup.normalizedUrl(it))
        }
        assertNull(SourceCleanup.normalizedUrl("custom-source-id"))
        assertNull(SourceCleanup.normalizedUrl("javascript:search()"))
    }

    @Test
    fun keepsEveryInUseCopyEvenWhenAnUnusedOneIsNewer() {
        val a = source("https://example.com")
        val b = source("https://example.com/")
        val c = source("https://EXAMPLE.COM:443").apply { lastUpdateTime = 999 }
        val result = duplicates(listOf(a, b, c).map { snapshot(it) }, setOf(a.bookSourceUrl, b.bookSourceUrl))
        assertEquals(listOf(c.bookSourceUrl), result.candidates.map { it.bookSourceUrl })
        assertEquals(2, result.kept.size)
    }

    @Test
    fun prefersEnabledThenUpdatedThenOrderedAndDeterministicUrl() {
        val a = source("https://example.com").apply { enabled = false; lastUpdateTime = 999 }
        val b = source("https://example.com/").apply { lastUpdateTime = 10; customOrder = 4 }
        val c = source("https://EXAMPLE.COM:443").apply { lastUpdateTime = 10; customOrder = 1 }
        val snapshots = listOf(a, b, c).map { snapshot(it) }
        assertEquals(setOf(a.bookSourceUrl, b.bookSourceUrl),
            duplicates(snapshots, emptySet()).candidates.map { it.bookSourceUrl }.toSet())
        b.customOrder = 1
        val tied = listOf(snapshot(b), snapshot(c))
        assertEquals(duplicates(tied, emptySet()).candidates.map { it.bookSourceUrl },
            duplicates(tied.reversed(), emptySet()).candidates.map { it.bookSourceUrl })
    }

    @Test
    fun differentExecutionRulesAndRuntimeStateAreNotDuplicates() {
        val a = source("https://example.com", "相同名称")
        val base = a.copy(bookSourceUrl = "https://example.com/")
        val variants = listOf(
            base.copy(searchUrl = "/search?q={{key}}"), base.copy(mainJs = "function search() {}"),
            base.copy(header = "{\"Authorization\":\"x\"}"), base.copy(loginUrl = "/login"),
            base.copy(exploreScreen = "screen"), base.copy(eventListener = true), base.copy(bookSourceType = 1)
        )
        variants.forEach {
            assertTrue(duplicates(listOf(snapshot(a), snapshot(it)), emptySet()).candidates.isEmpty())
        }
        assertTrue(duplicates(listOf(snapshot(a, mapOf("sourceVariable_" to "a")),
            snapshot(base, mapOf("sourceVariable_" to "b"))), emptySet()).candidates.isEmpty())
        assertTrue(duplicates(listOf(snapshot(a, mapOf("userInfo_" to "a")),
            snapshot(base, mapOf("userInfo_" to "b"))), emptySet()).candidates.isEmpty())
    }

    @Test
    fun pagedDuplicateScannerKeepsOnlyLightweightCandidatesAndHonorsProtectedSources() {
        val protected = source("https://example.com")
        val older = source("https://EXAMPLE.COM:443").apply { lastUpdateTime = 1 }
        val newer = source("https://example.com/").apply { lastUpdateTime = 2 }
        val scanner = SourceCleanup.DuplicateScanner(setOf(protected.bookSourceUrl))
        listOf(protected, older, newer).forEach {
            scanner.add(SourceCleanup.duplicateSource(it, emptyMap())!!)
        }

        val preview = scanner.preview()
        assertEquals(setOf(older.bookSourceUrl, newer.bookSourceUrl), preview.candidates.map { it.bookSourceUrl }.toSet())
        assertTrue(preview.candidates.all { it.snapshot == null })
        assertEquals(listOf("${protected.bookSourceName}：书架正在使用\n${protected.bookSourceUrl}"), preview.kept)
    }

    @Test
    fun pagedDuplicateScannerUsesExistingSurvivorPriority() {
        val disabledNewer = source("https://example.com").apply { enabled = false; lastUpdateTime = 10 }
        val enabledOlder = source("https://example.com/").apply { lastUpdateTime = 1 }
        val scanner = SourceCleanup.DuplicateScanner(emptySet())
        listOf(disabledNewer, enabledOlder).forEach {
            scanner.add(SourceCleanup.duplicateSource(it, emptyMap())!!)
        }

        val preview = scanner.preview()
        assertEquals(listOf(disabledNewer.bookSourceUrl), preview.candidates.map { it.bookSourceUrl })
        assertTrue(preview.candidates.single().reason.contains(enabledOlder.bookSourceName))
    }

    @Test
    fun usesOnlyCurrentFailuresAndProtectsNewlyUsedSources() {
        val old = source("https://old.test").apply { bookSourceGroup = "网站失效" }
        val failed = source("https://failed.test")
        val timeout = source("https://timeout.test")
        val used = source("https://used.test")
        val result = SourceCleanup.invalid(listOf(old, failed, timeout, used).map { snapshot(it) },
            setOf(used.bookSourceUrl), true,
            mapOf(failed.bookSourceUrl to listOf("搜索正文失效"), timeout.bookSourceUrl to listOf("校验超时"),
                used.bookSourceUrl to listOf("网站失效")))
        assertEquals(listOf(failed.bookSourceUrl), result.candidates.map { it.bookSourceUrl })
        assertTrue(SourceCleanup.invalidReasons(listOf("自定义失效分组", "校验超时")).isEmpty())
    }

    @Test(expected = IllegalStateException::class)
    fun cancelledCheckCannotProduceDeletionCandidates() {
        SourceCleanup.invalid(listOf(snapshot(source("https://a.test"))), emptySet(), false,
            mapOf("https://a.test" to listOf("网站失效")))
    }

    @Test
    fun manualVerificationIsKeptEvenWhenEarlierChecksFailed() {
        val manual = source("https://verify.test", "需要验证的源")
        val failed = source("https://failed.test")
        val result = SourceCleanup.invalid(listOf(manual, failed).map { snapshot(it) }, emptySet(), true,
            mapOf(manual.bookSourceUrl to listOf("搜索失效", "js失效"), failed.bookSourceUrl to listOf("网站失效")),
            setOf(manual.bookSourceUrl))
        assertEquals(listOf(failed.bookSourceUrl), result.candidates.map { it.bookSourceUrl })
        assertTrue(result.kept.any { it.contains(manual.bookSourceName) && it.contains("需要手动验证") })
    }

    @Test
    fun anOldManualVerificationGroupDoesNotOverrideTheCurrentCheck() {
        val old = source("https://old-verify.test").apply { bookSourceGroup = "需要手动验证" }
        val result = SourceCleanup.invalid(listOf(snapshot(old)), emptySet(), true,
            mapOf(old.bookSourceUrl to listOf("网站失效")))
        assertEquals(listOf(old.bookSourceUrl), result.candidates.map { it.bookSourceUrl })
    }
}
