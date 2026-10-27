package io.legado.app.help.source

import com.script.ScriptBindings
import com.script.rhino.RhinoScriptEngine
import com.script.rhino.rhinoContext
import io.legado.app.data.entities.BookSource
import io.legado.app.help.JsExtensionsAndroid
import io.legado.app.utils.onEachParallel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.coroutines.CoroutineContext

class SourceVerificationHelpTest {
    class TestJs : JsExtensionsAndroid {
        override fun getSource() = BookSource(bookSourceUrl = "https://verification.test")

        fun isCleanupCheck() = rhinoContext.coroutineContext?.get(SourceVerificationHelp.CheckContext) != null
    }

    private fun eval(script: String, context: CoroutineContext): Any? {
        val bindings = ScriptBindings().apply { this["java"] = TestJs() }
        return RhinoScriptEngine.eval(script, bindings, context)
    }

    @Test(timeout = 5000)
    fun allManualVerificationEntriesStopBeforeOpeningUiOrWaiting() = runBlocking {
        listOf(
            "java.startBrowser('https://verification.test', '验证')",
            "java.startBrowserAwait('https://verification.test', '验证')",
            "java.startBrowserAwait('https://verification.test', '验证', false)",
            "java.getVerificationCode('https://verification.test/image')"
        ).forEach { script ->
            val check = SourceVerificationHelp.CheckContext()
            val result = runCatching { eval(script, coroutineContext + check) }
            assertTrue("Verification entry should abort: $script", result.isFailure)
            assertTrue("Verification must be recorded even when Rhino wraps the exception", check.required)
        }
    }

    @Test(timeout = 5000)
    fun verificationIsRememberedWhenTheSourceCatchesTheException() = runBlocking {
        val check = SourceVerificationHelp.CheckContext()
        assertEquals("fallback", eval(
            "try { java.getVerificationCode('https://verification.test/image'); } catch (e) {} 'fallback';",
            coroutineContext + check
        ))
        assertTrue(check.required)
    }

    @Test(timeout = 5000)
    fun aSkippedSourceDoesNotBlockTheRestOfTheBatchOrLeakToReading() = runBlocking {
        val contexts = listOf(SourceVerificationHelp.CheckContext(), SourceVerificationHelp.CheckContext())
        val finished = listOf(0, 1).asFlow().onEachParallel(2) { index ->
            withContext(Dispatchers.Default + contexts[index]) {
                runCatching {
                    eval(if (index == 0) "java.startBrowserAwait('https://verification.test', '验证')" else "'ok'",
                        coroutineContext)
                }
            }
        }.toList()
        assertEquals(listOf(0, 1), finished.sorted())
        assertTrue(contexts[0].required)
        assertFalse(contexts[1].required)
        assertEquals(false, eval("java.isCleanupCheck()", coroutineContext))
    }

    @Test(timeout = 5000)
    fun cleanupDoesNotWaitForAnInteractiveVerificationLock() {
        val executor = Executors.newSingleThreadExecutor()
        val locked = CountDownLatch(1)
        val release = CountDownLatch(1)
        val holder = executor.submit {
            synchronized(SourceVerificationHelp) {
                locked.countDown()
                release.await(3, TimeUnit.SECONDS)
            }
        }
        try {
            assertTrue(locked.await(1, TimeUnit.SECONDS))
            runBlocking {
                val check = SourceVerificationHelp.CheckContext()
                runCatching { eval("java.getVerificationCode('https://verification.test/image')", coroutineContext + check) }
                assertTrue(check.required)
                assertFalse("Cleanup must return while normal reading still holds the verification lock", holder.isDone)
            }
        } finally {
            release.countDown()
            executor.shutdownNow()
        }
    }
}
