package com.elio.jianyu.network

// 公共 AI 契约保留原命名空间，避免改写现有见域调用方和持久化枚举。

import kotlinx.serialization.Serializable
import java.security.MessageDigest

@Serializable
enum class ApiKeySource {
    LOCAL,
    REMOTE
}

@Serializable
enum class ApiKeyValidationState {
    UNVERIFIED,
    CHECKING,
    AVAILABLE,
    INVALID,
    NETWORK_ERROR,
    RATE_LIMITED
}

/** 验证结果保留服务端限流提示，不能把一次短期限流直接转换成全天冷却。 */
data class ApiKeyValidationResult(
    val state: ApiKeyValidationState,
    val httpStatus: Int? = null,
    val retryAfterMs: Long? = null,
)

@Serializable
data class ApiKeyRecord(
    val id: String,
    val displayName: String,
    val key: String,
    val fingerprint: String,
    val source: ApiKeySource = ApiKeySource.LOCAL,
    val enabled: Boolean = true,
    val validationState: ApiKeyValidationState = ApiKeyValidationState.UNVERIFIED,
    val validationMessage: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val lastValidatedAt: Long? = null
)

data class ApiKeySummary(
    val id: String,
    val displayName: String,
    val maskedKey: String,
    val source: ApiKeySource,
    val enabled: Boolean,
    val validationState: ApiKeyValidationState,
    val validationMessage: String?,
    val lastValidatedAt: Long?,
    val banExpireTime: Long,
)

data class BatchImportResult(
    val added: Int,
    val duplicates: Int,
    val invalid: Int,
    val overflow: Int,
    val importedIds: List<String> = emptyList()
)

data class ParsedKeyBatch(
    val keys: List<String>,
    val duplicates: Int,
    val invalid: Int
)

@Serializable
data class ApiLog(
    val keyId: String,
    val model: String,
    val requestTime: Long,
    val responseTime: Long,
    val statusCode: Int,
    val errorMessage: String? = null,
    val prompt: String = "",
    val responseText: String = ""
)

data class KeyStatus(
    val id: String,
    val displayName: String,
    val maskedKey: String,
    val source: ApiKeySource,
    val validationState: ApiKeyValidationState,
    val validationMessage: String?,
    val isBanned: Boolean,
    val banExpireTime: Long,
    val remainingBanTimeMs: Long,
    val isManualDisabled: Boolean
)

object ApiKeyBatchParser {
    private const val MAX_KEY_LENGTH = 512

    fun parse(raw: String): ParsedKeyBatch {
        val normalized = raw.trim().removeSurrounding("[", "]")
        if (normalized.isBlank()) return ParsedKeyBatch(emptyList(), 0, 0)

        val unique = linkedSetOf<String>()
        var duplicates = 0
        var invalid = 0
        normalized
            .split(Regex("[,，\\r\\n]+"))
            .forEach { token ->
                val key = token.trim().removeSurrounding("\"").removeSurrounding("'").trim()
                when {
                    key.isBlank() -> Unit
                    key.length > MAX_KEY_LENGTH || key.any(Char::isWhitespace) -> invalid++
                    !unique.add(key) -> duplicates++
                }
            }
        return ParsedKeyBatch(unique.toList(), duplicates, invalid)
    }
}

fun fingerprintApiKey(key: String): String {
    return MessageDigest.getInstance("SHA-256")
        .digest(key.toByteArray(Charsets.UTF_8))
        .joinToString("") { byte -> "%02x".format(byte) }
}

fun maskApiKey(key: String): String = "••••••••${key.takeLast(4)}"
