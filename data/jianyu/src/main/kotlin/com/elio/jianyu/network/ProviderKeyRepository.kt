package com.elio.jianyu.network

import com.elio.jianyu.storage.jianyuPreferences

import android.content.Context
import android.content.SharedPreferences
import com.elio.jianyu.network.retry.saturatingAddMs
import com.elio.jianyu.network.keys.ApiKeyLease
import com.elio.jianyu.network.retry.ApiCallFailure
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.util.UUID

data class ProviderSessionKey(
    val id: String,
    val account: String,
    val provider: AiProvider,
)

/**
 * 一个提供商的 BYOK 密钥管理入口。
 *
 * 负责该提供商的加密持久化、导入、状态、冷却和会话绑定；不负责发起业务请求或解析协议。
 */
class ProviderKeyRepository internal constructor(
    private val provider: AiProvider,
    private val store: EncryptedApiKeyStore,
    private val prefs: SharedPreferences,
    private val nowMs: () -> Long = System::currentTimeMillis,
) : ProviderKeys {
    override fun sessionKeyAccount(sessionId: Long): String? = getOrBindSessionKey(sessionId)?.account
    constructor(context: Context, provider: AiProvider) : this(
        provider,
        EncryptedApiKeyStore(context.applicationContext, "${provider.storageId}_api_keys.enc"),
        context.applicationContext.jianyuPreferences("${provider.storageId}_api_key_prefs", Context.MODE_PRIVATE),
    )

    private companion object {
        const val KEY_LAST_USED_ID = "last_used_key_id"
        const val MAX_KEYS = 50
        const val DEFAULT_BAN_DURATION_MS = 24 * 60 * 60 * 1000L
        const val COOLDOWN_POLICY_VERSION = 2
        const val KEY_COOLDOWN_POLICY = "cooldown_policy_version"
    }
    private var validationSequence = 0L
    private val validations = mutableMapOf<String, Long>()
    private var migrationError: String? = null

    private val _summaries = MutableStateFlow<List<ApiKeySummary>>(emptyList())
    override val summaries: StateFlow<List<ApiKeySummary>> = _summaries.asStateFlow()

    private val _storageError = MutableStateFlow<String?>(null)
    override val storageError: StateFlow<String?> = _storageError.asStateFlow()

    init {
        if (prefs.getInt(KEY_COOLDOWN_POLICY, 0) < COOLDOWN_POLICY_VERSION) {
            val editor = prefs.edit()
            prefs.all.keys.filter { it.startsWith("ban_") || it.startsWith("rate_limit_count_") }.forEach(editor::remove)
            if (!editor.putInt(KEY_COOLDOWN_POLICY, COOLDOWN_POLICY_VERSION).commit()) {
                migrationError = "旧冷却迁移写入失败，下次启动会重试"
            }
        }
        val stored = records()
        if (stored.any { it.validationState == ApiKeyValidationState.CHECKING }) {
            store.write(stored.map { if (it.validationState == ApiKeyValidationState.CHECKING) {
                it.copy(validationState = ApiKeyValidationState.UNVERIFIED, validationMessage = null)
            } else it })
        }
        refreshSummaries()
    }

    @Synchronized
    override fun importBatch(raw: String): BatchImportResult {
        val parsed = ApiKeyBatchParser.parse(raw)
        val existing = records().toMutableList()
        val existingFingerprints = existing.mapTo(mutableSetOf()) { it.fingerprint }
        var duplicates = parsed.duplicates
        val candidates = parsed.keys.filter { key ->
            val isNew = existingFingerprints.add(fingerprintApiKey(key))
            if (!isNew) duplicates++
            isNew
        }
        val accepted = candidates.take((MAX_KEYS - existing.size).coerceAtLeast(0))
        val overflow = candidates.size - accepted.size
        var nextNumber = existing.mapNotNull { it.displayName.removePrefix(providerKeyPrefix()).toIntOrNull() }
            .maxOrNull()?.plus(1) ?: 1
        val created = accepted.map { key ->
            ApiKeyRecord(
                id = "${provider.storageId}-${UUID.randomUUID()}",
                displayName = "${providerKeyPrefix()}${nextNumber++}",
                key = key,
                fingerprint = fingerprintApiKey(key),
            )
        }
        if (created.isNotEmpty()) save(existing + created)
        return BatchImportResult(
            added = created.size,
            duplicates = duplicates,
            invalid = parsed.invalid,
            overflow = overflow,
            importedIds = created.map(ApiKeyRecord::id),
        )
    }

    suspend fun validateKeys(
        keyIds: List<String>,
        validator: suspend (String) -> ApiKeyValidationResult,
    ) = coroutineScope {
        val semaphore = Semaphore(2)
        keyIds.distinct().map { keyId ->
            async { semaphore.withPermit { validateKey(keyId, validator) } }
        }.awaitAll()
    }

    suspend fun validateKey(
        keyId: String,
        validator: suspend (String) -> ApiKeyValidationResult,
    ): ApiKeyValidationState {
        val (record, token) = synchronized(this) {
            val record = records().firstOrNull { it.id == keyId } ?: return ApiKeyValidationState.INVALID
            val token = ++validationSequence
            validations[keyId] = token
            refreshSummaries()
            record to token
        }
        try {
            val result = try {
                validator(record.key)
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                ApiKeyValidationResult(ApiKeyValidationState.NETWORK_ERROR)
            }
            currentCoroutineContext().ensureActive()
            synchronized(this) {
                // 后完成的旧任务不能覆盖新验证、删除/清空或真实请求的鉴权结果。
                if (validations[keyId] == token && records().any { it.id == keyId && it.fingerprint == record.fingerprint }) {
                    if (result.state == ApiKeyValidationState.RATE_LIMITED) {
                        ban(keyId, result.retryAfterMs ?: 60_000L, countFailure = false)
                    }
                    update(keyId) {
                        it.copy(validationState = result.state, validationMessage = validationMessage(result.state), lastValidatedAt = nowMs())
                    }
                }
            }
            return result.state
        } finally {
            synchronized(this) {
                if (validations[keyId] == token) validations.remove(keyId)
                refreshSummaries()
            }
        }
    }

    @Synchronized
    override fun setDisabled(keyId: String, disabled: Boolean) {
        update(keyId) { it.copy(enabled = !disabled) }
    }

    @Synchronized
    override fun delete(keyId: String): Boolean {
        validations.remove(keyId)
        val remaining = records().filterNot { it.id == keyId }
        if (!save(remaining)) return false
        val editor = prefs.edit().remove("ban_$keyId").remove("rate_limit_count_$keyId")
        prefs.all.forEach { (name, value) ->
            if (name.startsWith("session_key_") && value == keyId) editor.remove(name)
        }
        editor.apply()
        return true
    }

    @Synchronized
    override fun clear(): Boolean {
        val success = store.clear()
        if (!success) return false
        validations.clear()
        // 清空 Key 后仍保留迁移标记，避免下一次启动误清新 Key 的冷却。
        prefs.edit().clear().putInt(KEY_COOLDOWN_POLICY, COOLDOWN_POLICY_VERSION).apply()
        refreshSummaries()
        return true
    }

    @Synchronized
    override fun hasAvailableKeys(): Boolean = availableRecords().isNotEmpty()

    @Synchronized
    fun createAttemptPlan(sessionId: Long, preferredKeyId: String? = null): List<ApiKeyLease> {
        val available = availableRecords().map { record ->
            ApiKeyLease(
                keyId = record.id,
                displayName = record.displayName,
                provider = provider,
                source = record.source,
            )
        }
        return ProviderKeyAttemptPlanner.create(
            available = available,
            sessionBoundKeyId = prefs.getString("session_key_$sessionId", null),
            lastUsedKeyId = prefs.getString(KEY_LAST_USED_ID, null),
            preferredKeyId = preferredKeyId,
        )
    }

    @Synchronized
    fun getOrBindSessionKey(sessionId: Long): ProviderSessionKey? {
        val selected = createAttemptPlan(sessionId).firstOrNull() ?: return null
        bindSessionKey(sessionId, selected.keyId)
        return ProviderSessionKey(selected.keyId, selected.displayName, provider)
    }

    @Synchronized
    fun findKeyIdOrNull(secret: String): String? = records()
        .firstOrNull { it.fingerprint == fingerprintApiKey(secret) }
        ?.displayName

    @Synchronized
    fun getKeyStatuses(): List<KeyStatus> {
        val now = nowMs()
        return records().map { record ->
            val banExpire = prefs.getLong("ban_${record.id}", 0L)
            KeyStatus(
                id = record.id,
                displayName = record.displayName,
                maskedKey = maskApiKey(record.key),
                source = record.source,
                validationState = record.validationState,
                validationMessage = record.validationMessage,
                isBanned = banExpire > now,
                banExpireTime = banExpire,
                remainingBanTimeMs = (banExpire - now).coerceAtLeast(0L),
                isManualDisabled = !record.enabled,
            )
        }
    }

    @Synchronized
    fun clearBans() {
        val editor = prefs.edit()
        records().forEach { record ->
            editor.remove("ban_${record.id}")
            editor.remove("rate_limit_count_${record.id}")
        }
        editor.apply()
        refreshSummaries()
    }

    @Synchronized
    internal fun recordSuccess(sessionId: Long, keyId: String) {
        if (records().none { it.id == keyId }) return
        prefs.edit()
            .putString("session_key_$sessionId", keyId)
            .putString(KEY_LAST_USED_ID, keyId)
            .remove("rate_limit_count_$keyId")
            .apply()
    }

    @Synchronized
    internal fun recordFailure(keyId: String, failure: ApiCallFailure) {
        if (records().none { it.id == keyId }) return
        when (failure) {
            is ApiCallFailure.Http -> when (failure.code) {
                401, 403 -> {
                    validations.remove(keyId)
                    update(keyId) {
                        it.copy(
                            validationState = ApiKeyValidationState.INVALID,
                            validationMessage = "鉴权失败或权限不足 (HTTP ${failure.code})",
                            lastValidatedAt = nowMs(),
                        )
                    }
                }
                429 -> {
                    validations.remove(keyId)
                    ban(keyId, failure.retryAfterMs ?: nextCooldownDuration(keyId))
                }
            }
            else -> Unit
        }
    }

    @Synchronized
    internal fun secretFor(keyId: String): String? = records()
        .firstOrNull { it.id == keyId }
        ?.key

    @Synchronized
    internal fun availableSecretFor(keyId: String): String? = availableRecords().firstOrNull { it.id == keyId }?.key

    private fun records(): List<ApiKeyRecord> = store.read()

    private fun availableRecords(): List<ApiKeyRecord> {
        val now = nowMs()
        return records().filter { record ->
            record.enabled &&
                record.validationState != ApiKeyValidationState.INVALID &&
                prefs.getLong("ban_${record.id}", 0L) <= now
        }
    }

    private fun bindSessionKey(sessionId: Long, keyId: String) {
        prefs.edit().putString("session_key_$sessionId", keyId).apply()
    }

    private fun ban(keyId: String, durationMs: Long, countFailure: Boolean = true) {
        val editor = prefs.edit().putLong("ban_$keyId", maxOf(
            prefs.getLong("ban_$keyId", 0), saturatingAddMs(nowMs(), durationMs),
        ))
        if (countFailure) editor.putInt("rate_limit_count_$keyId", (prefs.getInt("rate_limit_count_$keyId", 0).toLong() + 1L).coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
        editor.apply()
        refreshSummaries()
    }

    private fun nextCooldownDuration(keyId: String): Long = when (
        prefs.getInt("rate_limit_count_$keyId", 0)
    ) {
        0 -> 60 * 1000L
        1 -> 5 * 60 * 1000L
        2 -> 30 * 60 * 1000L
        else -> DEFAULT_BAN_DURATION_MS
    }

    private fun update(keyId: String, transform: (ApiKeyRecord) -> ApiKeyRecord) {
        val current = records()
        if (current.none { it.id == keyId }) return
        save(current.map { if (it.id == keyId) transform(it) else it })
    }

    private fun save(records: List<ApiKeyRecord>): Boolean {
        val success = store.write(records)
        refreshSummaries()
        return success
    }

    private fun refreshSummaries() {
        _storageError.value = store.lastError ?: migrationError
        _summaries.value = records().map { record ->
            val banExpire = prefs.getLong("ban_${record.id}", 0L)
            ApiKeySummary(
                id = record.id,
                displayName = record.displayName,
                maskedKey = maskApiKey(record.key),
                source = record.source,
                enabled = record.enabled,
                validationState = if (record.id in validations) ApiKeyValidationState.CHECKING else record.validationState,
                validationMessage = record.validationMessage,
                lastValidatedAt = record.lastValidatedAt,
                banExpireTime = banExpire,
            )
        }
    }

    private fun providerKeyPrefix(): String = when (provider) {
        AiProvider.GEMINI -> "G"
        AiProvider.DEEPSEEK -> "D"
    }

    private fun validationMessage(state: ApiKeyValidationState): String? = when (state) {
        ApiKeyValidationState.AVAILABLE -> null
        ApiKeyValidationState.INVALID -> "鉴权失败"
        ApiKeyValidationState.NETWORK_ERROR -> "网络验证失败"
        ApiKeyValidationState.RATE_LIMITED -> "请求频率受限"
        ApiKeyValidationState.UNVERIFIED,
        ApiKeyValidationState.CHECKING -> null
    }
}
