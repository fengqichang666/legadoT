package io.legado.app.help.source

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.legado.app.data.appDb
import io.legado.app.data.entities.BookSource
import io.legado.app.model.CheckSource
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Assume.assumeFalse
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class SourceVerificationCheckInstrumentationTest {
    @Test
    fun cleanupFinishesAndKeepsSourcesThatRequestVerification() = runBlocking {
        assumeFalse("Do not interrupt an existing source check", CheckSource.isRunning)
        val context = ApplicationProvider.getApplicationContext<Context>()
        val id = UUID.randomUUID().toString()
        val firstOrder = appDb.bookSourceDao.maxOrder + 1
        val sources = listOf(
            "java.startBrowserAwait('about:blank', 'verification fixture');",
            "try { java.getVerificationCode('about:blank'); } catch (e) {}",
            ""
        ).mapIndexed { index, script ->
            BookSource(
                bookSourceUrl = "https://verification-$id.test/$index",
                bookSourceName = "verification fixture $index",
                customOrder = firstOrder + index,
                mainJs = "function search() { $script return []; }"
            )
        }
        val oldTimeout = CheckSource.timeout
        val oldChecks = listOf(CheckSource.checkSearch, CheckSource.checkDiscovery, CheckSource.checkInfo,
            CheckSource.checkCategory, CheckSource.checkContent)
        var started = false
        var finished = false
        try {
            CheckSource.timeout = 10000L
            CheckSource.checkSearch = true
            CheckSource.checkDiscovery = false
            CheckSource.checkInfo = false
            CheckSource.checkCategory = false
            CheckSource.checkContent = false
            appDb.bookSourceDao.insert(*sources.toTypedArray())
            val parts = sources.map { requireNotNull(appDb.bookSourceDao.getBookSourcePart(it.bookSourceUrl)) }
            started = CheckSource.start(context, parts, id)
            assertTrue(started)
            val result = withTimeout(30000) { CheckSource.result.first { it?.id == id }!! }
            finished = true
            val manualUrls = sources.take(2).map { it.bookSourceUrl }.toSet()
            val checked = sources.map { requireNotNull(appDb.bookSourceDao.getBookSource(it.bookSourceUrl)) }
            assertTrue(result.completed)
            assertEquals(checked.joinToString { "${it.bookSourceName}: ${it.bookSourceGroup} ${it.bookSourceComment}" },
                manualUrls, result.verificationRequired)
            assertEquals(setOf(sources.last().bookSourceUrl), result.failures.keys)
            checked.take(2).forEach {
                assertTrue(it.bookSourceGroup.orEmpty().contains(SourceVerificationHelp.MANUAL_VERIFICATION))
                assertTrue(it.getInvalidGroupNames().isBlank())
            }
            val preview = SourceCleanup.invalid(checked.map { SourceCleanup.Snapshot(it, emptyMap()) },
                emptySet(), result.completed, result.failures, result.verificationRequired)
            assertEquals(listOf(sources.last().bookSourceUrl), preview.candidates.map { it.snapshot.source.bookSourceUrl })
            assertEquals(2, preview.kept.count { it.contains("本轮已跳过") })
        } finally {
            if (started && !finished) CheckSource.stop(context)
            appDb.bookSourceDao.delete(*sources.toTypedArray())
            CheckSource.timeout = oldTimeout
            CheckSource.checkSearch = oldChecks[0]
            CheckSource.checkDiscovery = oldChecks[1]
            CheckSource.checkInfo = oldChecks[2]
            CheckSource.checkCategory = oldChecks[3]
            CheckSource.checkContent = oldChecks[4]
        }
    }
}
