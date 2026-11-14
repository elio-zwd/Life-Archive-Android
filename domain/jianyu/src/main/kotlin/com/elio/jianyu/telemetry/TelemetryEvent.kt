package com.elio.jianyu.telemetry
import kotlinx.serialization.Serializable

data class DiagnosticFilter(
    val query: String = "", val provider: String = "全部", val model: String = "",
    val key: String = "", val purpose: String = "", val session: String = "",
    val outcome: String = "全部", val hours: Int = 0,
)

@Serializable
data class TelemetryEvent(
    val id: String,
    val timestamp: Long,
    val durationMs: Long,
    val endpoint: String,
    val model: String? = null,
    val keyId: String? = null,
    val statusCode: Int? = null,
    val failureType: String? = null,
    val retryCount: Int = 0,
    val inputTokens: Int? = null,
    val outputTokens: Int? = null,
    val hasThoughtStep: Boolean = false,
    val requestPreview: String? = null,
    val responsePreview: String? = null,
    val expiresAt: Long? = null,
    val errorMessage: String? = null,
    val phase: String? = null,
    val recoveryResult: String? = null,
    val operationId: String = id,
    val sessionId: Long? = null,
    val questionId: Long? = null,
    val characterId: String? = null,
    val purpose: String? = null,
    val provider: String? = null,
    val keyName: String? = null,
    val method: String? = null,
    val requestHeaders: String? = null,
    val responseHeaders: String? = null,
    val protocol: String? = null,
    val connection: String? = null,
    val firstByteMs: Long? = null,
    val requestFile: String? = null,
    val responseFile: String? = null,
    val responseEncoding: String? = null,
    val contentType: String? = null,
    val captureState: String = "COMPLETE",
    val captureError: String? = null,
    val historical: Boolean = false,
    val streamEventsFile: String? = null,
    val phaseDetails: String? = null,
) {
    val containsContentPreview: Boolean
        get() = requestPreview != null || responsePreview != null
}
