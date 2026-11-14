package com.elio.jianyu.network

import android.content.Context
import com.elio.lifearchive.data.jianyu.BuildConfig
import com.elio.jianyu.network.keys.ApiKeyLease
import com.elio.jianyu.roundtable.RequestBudgetTracker
import com.elio.jianyu.network.retry.ApiRetryPolicy
import kotlinx.coroutines.CancellationException
import com.elio.jianyu.telemetry.CloudInteractionRequestPolicy
import com.elio.jianyu.telemetry.PrivacySafeLogger
import com.elio.jianyu.telemetry.TelemetryInterceptor
import com.elio.jianyu.telemetry.TelemetryRepository
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerialName
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import retrofit2.http.Body
import retrofit2.http.Header
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query
import java.util.concurrent.TimeUnit

@Serializable
data class GenerateContentRequest(
    val contents: List<Content>,
    val generationConfig: GenerationConfig? = null,
    val systemInstruction: Content? = null,
    val tools: List<Tool>? = null
)

@Serializable
data class Content(
    val role: String? = null,
    val parts: List<Part>
)

@Serializable
data class Part(
    val text: String? = null,
    val inlineData: Blob? = null
)

@Serializable
data class Blob(
    val mimeType: String,
    val data: String
)

@Serializable
data class GenerationConfig(
    val temperature: Float? = null,
    val topP: Float? = null,
    val topK: Int? = null,
    val maxOutputTokens: Int? = null,
    val thinkingConfig: ThinkingConfig? = null
)

@Serializable
data class ThinkingConfig(
    val thinkingLevel: String
)

@Serializable
data class GenerateContentResponse(
    val candidates: List<Candidate> = emptyList()
)

@Serializable
data class Candidate(
    val content: Content,
    val finishReason: String? = null,
    val groundingMetadata: GroundingMetadata? = null
)

@Serializable
data class Tool(
    val type: String
)

@Serializable
data class GroundingMetadata(
    val webSearchQueries: List<String>? = null,
    val groundingChunks: List<GroundingChunk>? = null
)

@Serializable
data class GroundingChunk(
    val web: WebSource? = null
)

@Serializable
data class WebSource(
    val uri: String? = null,
    val title: String? = null
)

@Serializable
data class CreateInteractionRequest(
    val model: String? = null,
    val agent: String? = null,
    val input: JsonElement? = null,
    @SerialName("system_instruction") val systemInstruction: String? = null,
    val tools: List<Tool>? = null,
    val store: Boolean? = null,
    val stream: Boolean? = null,
    @SerialName("previous_interaction_id") val previousInteractionId: String? = null,
    @SerialName("generation_config") val generationConfig: InteractionGenerationConfig? = null
)

@Serializable
data class InteractionGenerationConfig(
    @SerialName("max_output_tokens") val maxOutputTokens: Int? = null,
    val temperature: Float? = null,
    @SerialName("thinking_level") val thinkingLevel: String? = null,
    @SerialName("thinking_summaries") val thinkingSummaries: String? = null
)

@Serializable
data class Interaction(
    val id: String,
    val model: String? = null,
    @SerialName("object") val objType: String? = null,
    val steps: List<InteractionStep> = emptyList(),
    val status: String? = null,
    val usage: InteractionUsage? = null,
    val error: JsonObject? = null,
)

@Serializable
data class InteractionStep(
    val type: String,
    val content: List<InteractionContent> = emptyList(),
    val signature: String? = null,
    val summary: List<InteractionContent> = emptyList(),
    val result: JsonElement? = null
)

@Serializable
data class InteractionAnnotation(
    val type: String? = null,
    val url: String? = null,
    val title: String? = null,
    @SerialName("start_index") val startIndex: Int? = null,
    @SerialName("end_index") val endIndex: Int? = null
)

@Serializable
data class InteractionContent(
    val type: String,
    val text: String? = null,
    val annotations: List<InteractionAnnotation>? = null
)

/**
 * 按 Interactions SDK 的 output_text 语义汇总最终文本。
 * 一次交互结尾可能包含多个连续的 model_output 步骤，且单个步骤也可能拆分为多个内容块。
 */
val Interaction.outputText: String
    get() = steps.asReversed()
        .takeWhile { it.type == "model_output" }
        .asReversed()
        .asSequence()
        .flatMap { it.content.asSequence() }
        .filter { it.type == "text" }
        .mapNotNull { it.text?.takeIf(String::isNotBlank) }
        .joinToString(separator = "")
        .trim()

@Serializable
data class GoogleSearchResultItem(
    val title: String? = null,
    val url: String? = null,
    val snippet: String? = null
)

@Serializable
data class InteractionUsage(
    @SerialName("total_input_tokens") val totalInputTokens: Int? = null,
    @SerialName("total_output_tokens") val totalOutputTokens: Int? = null,
    @SerialName("total_thought_tokens") val totalThoughtTokens: Int? = null,
    @SerialName("total_tokens") val totalTokens: Int? = null
)

@Serializable
data class EmbedContentConfig(
    @SerialName("output_dimensionality") val outputDimensionality: Int? = 768
)

@Serializable
data class EmbedContentRequest(
    val model: String = "models/gemini-embedding-001",
    val content: Content,
    val config: EmbedContentConfig = EmbedContentConfig()
)

@Serializable
data class EmbedContentResponse(
    val embedding: Embedding
)

@Serializable
data class Embedding(
    val values: List<Float>
)

interface GeminiApiService {
    @GET("v1beta/models/gemini-3.1-flash-lite")
    suspend fun validateApiKey(
        @Query("key") apiKey: String
    ): retrofit2.Response<JsonElement>

    @POST("v1beta/models/{model}:generateContent")
    suspend fun generateContent(
        @Path("model") model: String,
        @Query("key") apiKey: String,
        @Body request: GenerateContentRequest
    ): GenerateContentResponse

    @POST("v1beta/interactions")
    suspend fun createInteraction(
        @Query("key") apiKey: String,
        @Header("Api-Revision") apiRevision: String = "2026-05-20",
        @Body request: CreateInteractionRequest
    ): Interaction

    @GET("v1beta/interactions/{id}")
    suspend fun getInteraction(
        @Path("id") id: String,
        @Query("key") apiKey: String,
        @Header("Api-Revision") apiRevision: String = "2026-05-20",
    ): Interaction

    @POST("v1beta/models/gemini-embedding-001:embedContent")
    suspend fun embedContent(
        @Query("key") apiKey: String,
        @Body request: EmbedContentRequest
    ): EmbedContentResponse
}

object GeminiRestTransport {
    private const val TAG = "GeminiRestTransport"
    private const val BASE_URL = "https://generativelanguage.googleapis.com/"

    internal val okHttpClient = OkHttpClient.Builder()
        .connectTimeout(90, TimeUnit.SECONDS)
        .readTimeout(300, TimeUnit.SECONDS)
        .writeTimeout(90, TimeUnit.SECONDS)
        .pingInterval(15, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .addNetworkInterceptor(TelemetryInterceptor())
        .eventListenerFactory { com.elio.jianyu.telemetry.DiagnosticCallListener(it, com.elio.jianyu.telemetry.DiagnosticContext.current()) }
        .addInterceptor(HttpLoggingInterceptor { rawMessage ->
            PrivacySafeLogger.d("OkHttp", rawMessage)
        }.apply {
            level = if (BuildConfig.DEBUG) {
                HttpLoggingInterceptor.Level.BASIC
            } else {
                HttpLoggingInterceptor.Level.NONE
            }
        })
        .build()

    val service: GeminiApiService by lazy {
        createService(okHttpClient)
    }

    internal val conversationClient: OkHttpClient by lazy {
        okHttpClient.newBuilder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .readTimeout(90, TimeUnit.SECONDS)
            // 生成 POST 的尝试次数由统一执行器计数，避免底层隐式重发。
            .retryOnConnectionFailure(false)
            .build()
    }

    internal val conversationService: GeminiApiService by lazy {
        createService(conversationClient)
    }

    private fun createService(client: OkHttpClient): GeminiApiService {
        val json = Json {
            ignoreUnknownKeys = true
            coerceInputValues = true
        }
        return Retrofit.Builder()
            .baseUrl(BASE_URL)
            .client(client)
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()
            .create(GeminiApiService::class.java)
    }

    suspend fun validateKey(apiKey: String): ApiKeyValidationResult = try {
        val response = service.validateApiKey(apiKey = apiKey)
        val state = when (response.code()) {
            in 200..299 -> ApiKeyValidationState.AVAILABLE
            400, 401, 403 -> ApiKeyValidationState.INVALID
            429 -> ApiKeyValidationState.RATE_LIMITED
            else -> ApiKeyValidationState.NETWORK_ERROR
        }
        ApiKeyValidationResult(state, response.code(), ApiRetryPolicy.parseRetryAfterMs(response.headers()["Retry-After"]))
    } catch (error: CancellationException) {
        throw error
    } catch (_: Exception) {
        ApiKeyValidationResult(ApiKeyValidationState.NETWORK_ERROR)
    }

    /** 通过统一 AI 管理模块执行 Gemini REST 请求；本 transport 不自行轮换 Key 或处理重试。 */
    suspend fun <T> executeWithBudgetAndRetry(
        context: Context,
        sessionId: Long,
        attemptPlan: List<ApiKeyLease>,
        tracker: RequestBudgetTracker,
        operationName: String,
        delayProvider: com.elio.jianyu.roundtable.DelayProvider = com.elio.jianyu.roundtable.DefaultDelayProvider,
        isRequired: Boolean = true,
        reserveForRequired: Int = 0,
        onAttemptStarted: suspend () -> Unit = {},
        block: suspend (String) -> T,
    ): T {
        return AiManager.requests(context, AiProvider.GEMINI).execute(
            sessionId = sessionId,
            attemptPlan = attemptPlan,
            operationName = operationName,
            delayProvider = delayProvider,
            onAttemptStarted = {
                if (isRequired) tracker.tryConsumeRequired() else tracker.tryConsumeOptional()
                onAttemptStarted()
            },
            block = block,
        )
    }

    suspend fun createInteraction(
        context: Context,
        request: CreateInteractionRequest,
        sessionId: Long,
        attemptPlan: List<ApiKeyLease>,
        tracker: RequestBudgetTracker,
        operationName: String = "CreateInteraction",
        delayProvider: com.elio.jianyu.roundtable.DelayProvider = com.elio.jianyu.roundtable.DefaultDelayProvider,
        isRequired: Boolean = true,
        reserveForRequired: Int = 0,
        onAttemptStarted: suspend () -> Unit = {},
    ): Interaction {
        TelemetryRepository.init(context)
        val cloudPolicy = CloudInteractionRequestPolicy.apply(
            requestedStore = request.store,
            requestedPreviousInteractionId = request.previousInteractionId
        )
        val privacySafeRequest = request.copy(
            store = cloudPolicy.store,
            previousInteractionId = cloudPolicy.previousInteractionId
        )
        val response = executeWithBudgetAndRetry(
            context = context,
            sessionId = sessionId,
            attemptPlan = attemptPlan,
            tracker = tracker,
            operationName = operationName,
            delayProvider = delayProvider,
            isRequired = isRequired,
            reserveForRequired = reserveForRequired,
            onAttemptStarted = onAttemptStarted,
        ) { secret ->
            service.createInteraction(apiKey = secret, request = privacySafeRequest)
        }
        return response
    }

    suspend fun generateContent(
        context: Context,
        model: String,
        request: GenerateContentRequest,
        sessionId: Long,
        attemptPlan: List<ApiKeyLease>,
        tracker: RequestBudgetTracker,
        operationName: String = "GenerateContent",
        delayProvider: com.elio.jianyu.roundtable.DelayProvider = com.elio.jianyu.roundtable.DefaultDelayProvider,
        isRequired: Boolean = true,
        reserveForRequired: Int = 0
    ): GenerateContentResponse {
        TelemetryRepository.init(context)
        return executeWithBudgetAndRetry(
            context = context,
            sessionId = sessionId,
            attemptPlan = attemptPlan,
            tracker = tracker,
            operationName = operationName,
            delayProvider = delayProvider,
            isRequired = isRequired,
            reserveForRequired = reserveForRequired,
        ) { secret ->
            service.generateContent(model = model, apiKey = secret, request = request)
        }
    }

    suspend fun embedContent(
        context: Context,
        text: String,
        sessionId: Long,
        attemptPlan: List<ApiKeyLease>,
        tracker: RequestBudgetTracker,
        operationName: String = "EmbedContent",
        delayProvider: com.elio.jianyu.roundtable.DelayProvider = com.elio.jianyu.roundtable.DefaultDelayProvider,
        isRequired: Boolean = true,
        reserveForRequired: Int = 0
    ): List<Float> {
        TelemetryRepository.init(context)
        val request = EmbedContentRequest(
            content = Content(parts = listOf(Part(text = text)))
        )
        return executeWithBudgetAndRetry(
            context = context,
            sessionId = sessionId,
            attemptPlan = attemptPlan,
            tracker = tracker,
            operationName = operationName,
            delayProvider = delayProvider,
            isRequired = isRequired,
            reserveForRequired = reserveForRequired,
        ) { secret ->
            service.embedContent(apiKey = secret, request = request).embedding.values
        }
    }

}
