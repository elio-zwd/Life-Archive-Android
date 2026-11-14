package com.elio.jianyu.telemetry
import kotlinx.serialization.Serializable

object TelemetryEventFactory {
    fun create(
        level: TelemetryLevel,
        id: String,
        timestamp: Long,
        durationMs: Long,
        endpoint: String,
        model: String?,
        keyId: String?,
        statusCode: Int?,
        failureType: String?,
        requestPreview: String?,
        responsePreview: String?,
        hasThoughtStep: Boolean,
        contentExpiresAt: Long?,
        errorMessage: String? = null,
        phase: String? = null,
        recoveryResult: String? = null,
        retryCount: Int = 0,
        inputTokens: Int? = null,
        outputTokens: Int? = null,
    ): TelemetryEvent? {
        if (level == TelemetryLevel.OFF) return null
        val contentEnabled = level == TelemetryLevel.CONTENT_DEBUG
        val trace = DiagnosticContext.current()
        val safeErrorMessage = errorMessage?.let { DiagnosticCredentials.text(it) }
        return TelemetryEvent(
            id = id,
            timestamp = timestamp,
            durationMs = durationMs,
            endpoint = endpoint,
            model = model,
            keyId = keyId,
            statusCode = statusCode,
            failureType = failureType,
            errorMessage = safeErrorMessage,
            phase = phase,
            recoveryResult = recoveryResult,
            retryCount = retryCount,
            inputTokens = inputTokens,
            outputTokens = outputTokens,
            hasThoughtStep = hasThoughtStep,
            requestPreview = requestPreview.takeIf { contentEnabled },
            responsePreview = responsePreview.takeIf { contentEnabled },
            expiresAt = null,
            operationId = trace?.operationId ?: id,
            sessionId = trace?.sessionId,
            questionId = trace?.questionId,
            characterId = trace?.characterId,
            purpose = trace?.purpose,
        )
    }
}
