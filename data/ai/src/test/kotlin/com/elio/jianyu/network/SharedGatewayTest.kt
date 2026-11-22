package com.elio.jianyu.network

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.elio.lifearchive.data.ai.SharedAiRuntime
import com.elio.lifearchive.domain.ai.*
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.toList
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SharedGatewayTest {
    private class Store : ApiKeyStore {
        private var rows = listOf(ApiKeyRecord("a", "synthetic", "test-only-secret", "fingerprint"))
        override val lastError: String? = null
        override fun read() = rows
        override fun write(records: List<ApiKeyRecord>): Boolean { rows = records; return true }
        override fun clear(): Boolean { rows = emptyList(); return true }
    }
    private fun runtime(sse: String, count: () -> Unit = {}): SharedAiRuntime {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            count()
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body(sse.toResponseBody("text/event-stream".toMediaType())).build()
        }.build()
        return SharedAiRuntime(context, client) { app, provider ->
            ProviderKeyRepository(provider, Store(), app.getSharedPreferences("synthetic-${provider.name}-${java.util.UUID.randomUUID()}", 0))
        }
    }
    private fun request() = AiRequest(AiRequestId("pet", "same-session", "operation"), AiUseCase.PET_REPLY,
        "仅使用本次传入的上下文", listOf(AiMessage(AiRole.USER, "你好")))

    @Test fun geminiStreamingProducesOneCompletedAnswerFromActualGateway() = runBlocking {
        val runtime = runtime("""data: {"candidates":[{"content":{"parts":[{"text":"你"}]}}]}

data: {"candidates":[{"content":{"parts":[{"text":"好"}]},"finishReason":"STOP"}]}

""")
        runtime.configuration.selectModel(AiUseCase.PET_REPLY, AiModel.GEMINI_25_FLASH)
        val events = runtime.generate(request()).toList()
        assertEquals(listOf(AiEvent.Text("你"), AiEvent.Text("你好"), AiEvent.Complete("你好")), events)
    }
    @Test fun truncatedDeepSeekResponseIsNotCompletedOrAutomaticallyDuplicated() = runBlocking {
        var calls = 0
        val runtime = runtime("""data: {"choices":[{"delta":{"content":"未完成"}}]}

data: {"choices":[{"delta":{},"finish_reason":"length"}]}

data: [DONE]

""") { calls++ }
        runtime.configuration.selectModel(AiUseCase.PET_REPLY, AiModel.DEEPSEEK_V4_FLASH)
        try { runtime.generate(request()).toList(); fail("截断不能写成完成") }
        catch (error: AiRequestException) { assertEquals(AiFailure.INVALID_RESPONSE, error.failure) }
        assertEquals(1, calls)
    }
    @Test fun fullDeepSeekResponseAndCrossDomainRequestIdentitiesAreIsolated() = runBlocking {
        val runtime = runtime("""data: {"choices":[{"delta":{"content":"你好"}}]}

data: {"choices":[{"delta":{},"finish_reason":"stop"}]}

data: [DONE]

""")
        runtime.configuration.selectModel(AiUseCase.PET_REPLY, AiModel.DEEPSEEK_V4_FLASH)
        assertEquals(AiEvent.Complete("你好"), runtime.generate(request()).toList().last())
        val pet = runtime.sessionBinding(request().id)
        val same = runtime.sessionBinding(request().id.copy(operation = "other"))
        val other = runtime.sessionBinding(request().id.copy(business = "other-domain"))
        assertTrue(pet < 0 && other < 0); assertEquals(pet, same); assertNotEquals(pet, other)
    }
}
