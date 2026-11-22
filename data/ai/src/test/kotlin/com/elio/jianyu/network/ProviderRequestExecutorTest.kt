package com.elio.jianyu.network

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.elio.jianyu.network.retry.*
import com.elio.lifearchive.data.ai.ProviderRequestExecutor
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ProviderRequestExecutorTest {
    private class Store : ApiKeyStore {
        var rows = listOf(ApiKeyRecord("a", "G1", "synthetic-a", "a"), ApiKeyRecord("b", "G2", "synthetic-b", "b"))
        override val lastError: String? = null
        override fun read() = rows
        override fun write(records: List<ApiKeyRecord>): Boolean { rows = records; return true }
        override fun clear(): Boolean { rows = emptyList(); return true }
    }
    private fun repository(): ProviderKeyRepository {
        val context = ApplicationProvider.getApplicationContext<Context>()
        return ProviderKeyRepository(AiProvider.GEMINI, Store(), context.getSharedPreferences(java.util.UUID.randomUUID().toString(), 0), { 1000L })
    }
    @Test fun rateLimitUsesSamePoolAndAdvancesToNextKey() = runBlocking {
        val keys = repository()
        var attempts = 0
        val output = ProviderRequestExecutor(AiProvider.GEMINI, keys).execute(-1, keys.createAttemptPlan(-1), "pet", classify = { ApiCallFailure.Http(429, 5000) }) {
            attempts++
            if (it == "synthetic-a") throw java.io.IOException("synthetic failure")
            "ok"
        }
        assertEquals("ok", output); assertEquals(2, attempts)
        assertEquals(6000L, keys.summaries.value.first { it.id == "a" }.banExpireTime)
        assertEquals("b", keys.createAttemptPlan(-1).first().keyId)
    }
    @Test fun cancellationDoesNotPunishKeyOrCancelAnotherBusiness() = runBlocking {
        val keys = repository(); val executor = ProviderRequestExecutor(AiProvider.GEMINI, keys)
        val entered = CompletableDeferred<Unit>()
        val pet = launch { executor.execute(-1, keys.createAttemptPlan(-1), "pet", classify = { ApiCallFailure.Unknown(it) }) {
            entered.complete(Unit); awaitCancellation()
        } }
        entered.await()
        val jianyu = async { executor.execute(1, keys.createAttemptPlan(1), "jianyu", classify = { ApiCallFailure.Unknown(it) }) { "见域完成" } }
        pet.cancelAndJoin()
        assertEquals("见域完成", jianyu.await())
        assertTrue(keys.summaries.value.all { it.validationState == ApiKeyValidationState.UNVERIFIED && it.banExpireTime == 0L })
    }
    @Test fun partialOutputFailureDoesNotRestartAndPreparationDoesNotPunishKey() = runBlocking {
        val keys = repository(); val executor = ProviderRequestExecutor(AiProvider.GEMINI, keys)
        var attempts = 0
        try { executor.execute(-1, keys.createAttemptPlan(-1), "pet", classify = { ApiCallFailure.Network(it) }, canRetry = { false }) {
            attempts++; throw java.io.IOException("partial response")
        }; fail() } catch (_: ApiExecutionException) {}
        assertEquals(1, attempts)
        try { executor.execute(-1, keys.createAttemptPlan(-1), "pet", classify = { ApiCallFailure.Http(403) }, prepare = { error("budget") }) {
            attempts++
        }; fail() } catch (_: IllegalStateException) {}
        assertEquals(1, attempts)
        assertTrue(keys.summaries.value.all { it.validationState == ApiKeyValidationState.UNVERIFIED })
    }
}
