package com.elio.lifearchive.data.ai

import android.content.Context
import com.elio.jianyu.network.*
import com.elio.jianyu.network.retry.*
import com.elio.lifearchive.domain.ai.*
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.*
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody

/** 进程内唯一实例，不依赖见域数据库、页面或诊断生命周期。 */
class SharedAiRuntime internal constructor(
    context: Context,
    private val client: OkHttpClient = OkHttpClient.Builder().connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS).callTimeout(180, TimeUnit.SECONDS).build(),
    poolFactory: (Context, AiProvider) -> ProviderKeyRepository = { app, provider -> ProviderKeyRepository(app, provider) },
) : AiServices, AiGateway {
    override val configuration = AiConfigurationRepository(context.applicationContext)
    private val pools = AiProvider.entries.associateWith { poolFactory(context.applicationContext, it) }
    private val executions = pools.mapValues { (provider, pool) -> ProviderRequestExecutor(provider, pool) }
    private val bindings = context.applicationContext.getSharedPreferences("shared_ai_request_bindings", Context.MODE_PRIVATE)

    override fun keys(provider: AiProvider): ProviderKeyRepository = pools.getValue(provider)
    fun executor(provider: AiProvider): ProviderRequestExecutor = executions.getValue(provider)

    /** 旧见域使用正数会话 ID；新业务的独立命名空间稳定分配负数绑定。 */
    @Synchronized internal fun sessionBinding(id: AiRequestId): Long {
        val identity = Json.encodeToString(JsonArray.serializer(), buildJsonArray { add(id.business); add(id.session) })
        if (bindings.contains(identity)) return bindings.getLong(identity, -1)
        val next = bindings.getLong("next", -1)
        check(next > Long.MIN_VALUE) { "请求身份空间已耗尽" }
        check(bindings.edit().putLong(identity, next).putLong("next", next - 1).commit())
        return next
    }

    override suspend fun validateKey(provider: AiProvider, keyId: String) {
        keys(provider).validateKey(keyId) { validate(provider, it) }
    }
    override suspend fun validateKeys(provider: AiProvider, keyIds: List<String>) {
        keys(provider).validateKeys(keyIds) { validate(provider, it) }
    }
    private suspend fun validate(provider: AiProvider, secret: String): ApiKeyValidationResult = try {
        val request = Request.Builder().url(if (provider == AiProvider.GEMINI)
            "https://generativelanguage.googleapis.com/v1beta/models" else "https://api.deepseek.com/models")
            .apply { if (provider == AiProvider.GEMINI) header("x-goog-api-key", secret) else header("Authorization", "Bearer $secret") }.build()
        cancellableCall(request) { response ->
            ApiKeyValidationResult(when (response.code) {
                in 200..299 -> ApiKeyValidationState.AVAILABLE
                400, 401, 403 -> ApiKeyValidationState.INVALID
                429 -> ApiKeyValidationState.RATE_LIMITED
                else -> ApiKeyValidationState.NETWORK_ERROR
            }, response.code, ApiRetryPolicy.parseRetryAfterMs(response.header("Retry-After")))
        }
    } catch (cancelled: CancellationException) { throw cancelled }
    catch (_: Exception) { ApiKeyValidationResult(ApiKeyValidationState.NETWORK_ERROR) }

    override fun generate(request: AiRequest): Flow<AiEvent> = channelFlow {
        require(request.messages.isNotEmpty())
        val model = configuration.configuration.value.modelFor(request.useCase)
        val pool = keys(model.provider)
        val binding = sessionBinding(request.id)
        val plan = pool.createAttemptPlan(binding)
        if (plan.isEmpty()) throw AiRequestException(AiFailure.NO_KEY)
        var emitted = false
        try {
            val output = executor(model.provider).execute(binding, plan, request.useCase.name,
                classify = ::classifyCommonFailure, canRetry = { !emitted }) { secret ->
                val http = createTextRequest(request, model, secret)
                cancellableCall(http) { response ->
                    if (!response.isSuccessful) throw ProviderHttpException(response.code, response.header("Retry-After"))
                    val source = response.body?.source() ?: throw SerializationException("缺少响应")
                    val text = StringBuilder()
                    var complete = false
                    var completionReason: String? = null
                    while (!source.exhausted()) {
                        currentCoroutineContext().ensureActive()
                        val line = source.readUtf8Line() ?: break
                        if (!line.startsWith("data:")) continue
                        val payload = line.removePrefix("data:").trim()
                        if (payload == "[DONE]") { complete = completionReason == "stop"; break }
                        if (payload.isEmpty()) continue
                        val event = Json.parseToJsonElement(payload).jsonObject
                        if (event["error"] != null) throw SerializationException("流式服务错误")
                        if (model.provider == AiProvider.DEEPSEEK) {
                            event["choices"]?.jsonArray?.firstOrNull()?.jsonObject?.get("finish_reason")?.jsonPrimitive?.contentOrNull?.let {
                                completionReason = it
                            }
                        }
                        val delta = textDelta(model.provider, event)
                        if (delta.isNotEmpty()) {
                            text.append(delta)
                            if (text.length > 64_000) throw SerializationException("回答超过限制")
                            emitted = true
                            send(AiEvent.Text(text.toString()))
                        }
                        if (model.provider == AiProvider.GEMINI && event["candidates"]?.jsonArray?.firstOrNull()
                                ?.jsonObject?.get("finishReason")?.jsonPrimitive?.content == "STOP") { complete = true; break }
                    }
                    if (!complete || text.isBlank()) throw SerializationException("回答未完成")
                    text.toString()
                }
            }
            send(AiEvent.Complete(output))
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (error: ApiExecutionException) { throw AiRequestException(error.failure.toPublicFailure()) }
        catch (error: IOException) { throw AiRequestException(AiFailure.NETWORK) }
        catch (error: SerializationException) { throw AiRequestException(AiFailure.INVALID_RESPONSE) }
    }

    private suspend fun <T> cancellableCall(request: Request, read: suspend (Response) -> T): T = coroutineScope {
        val call = client.newCall(request)
        val cancel = launch(start = CoroutineStart.UNDISPATCHED) { try { awaitCancellation() } finally { call.cancel() } }
        try { withContext(Dispatchers.IO) { call.execute().use { read(it) } } }
        finally { cancel.cancel() }
    }

    companion object {
        @Volatile private var instance: SharedAiRuntime? = null
        fun get(context: Context): SharedAiRuntime = instance ?: synchronized(this) {
            instance ?: SharedAiRuntime(context.applicationContext).also { instance = it }
        }
    }
}

internal class ProviderHttpException(val status: Int, val retryAfter: String?) : IOException("HTTP_$status")
internal fun classifyCommonFailure(error: Exception): ApiCallFailure = when (error) {
    is ProviderHttpException -> ApiCallFailure.Http(error.status, ApiRetryPolicy.parseRetryAfterMs(error.retryAfter))
    is IOException -> ApiCallFailure.Network(error)
    is SerializationException -> ApiCallFailure.Serialization(error)
    else -> ApiCallFailure.Unknown(error)
}
private fun ApiCallFailure.toPublicFailure(): AiFailure = when (this) {
    is ApiCallFailure.Http -> when (code) { 401, 403 -> AiFailure.AUTHENTICATION; 429 -> AiFailure.RATE_LIMITED; else -> AiFailure.SERVICE }
    is ApiCallFailure.Network -> AiFailure.NETWORK
    is ApiCallFailure.Serialization -> AiFailure.INVALID_RESPONSE
    else -> AiFailure.SERVICE
}

internal fun textDelta(provider: AiProvider, event: JsonObject): String = when (provider) {
    AiProvider.GEMINI -> event["candidates"]?.jsonArray?.firstOrNull()?.jsonObject?.get("content")
        ?.jsonObject?.get("parts")?.jsonArray?.filter { it.jsonObject["thought"]?.jsonPrimitive?.booleanOrNull != true }
        ?.joinToString("") { it.jsonObject["text"]?.jsonPrimitive?.contentOrNull.orEmpty() }.orEmpty()
    AiProvider.DEEPSEEK -> event["choices"]?.jsonArray?.firstOrNull()?.jsonObject?.get("delta")
        ?.jsonObject?.get("content")?.jsonPrimitive?.contentOrNull.orEmpty()
}

internal fun createTextRequest(request: AiRequest, model: AiModel, secret: String): Request {
    val body = buildJsonObject {
        if (model.provider == AiProvider.GEMINI) {
            putJsonObject("systemInstruction") { putJsonArray("parts") { add(buildJsonObject { put("text", request.instruction) }) } }
            putJsonArray("contents") { request.messages.forEach { message -> add(buildJsonObject {
                put("role", if (message.role == AiRole.USER) "user" else "model")
                putJsonArray("parts") { add(buildJsonObject { put("text", message.text) }) }
            }) } }
            putJsonObject("generationConfig") { put("maxOutputTokens", 2048) }
        } else {
            put("model", model.modelId); put("stream", true); put("max_tokens", 2048)
            putJsonObject("thinking") { put("type", "disabled") }
            putJsonArray("messages") {
                add(buildJsonObject { put("role", "system"); put("content", request.instruction) })
                request.messages.forEach { message -> add(buildJsonObject {
                    put("role", if (message.role == AiRole.USER) "user" else "assistant"); put("content", message.text)
                }) }
            }
        }
    }
    return Request.Builder().url(if (model.provider == AiProvider.GEMINI)
        "https://generativelanguage.googleapis.com/v1beta/models/${model.modelId}:streamGenerateContent?alt=sse"
        else "https://api.deepseek.com/chat/completions")
        .apply { if (model.provider == AiProvider.GEMINI) header("x-goog-api-key", secret) else header("Authorization", "Bearer $secret") }
        .post(body.toString().toRequestBody("application/json".toMediaType())).build()
}
