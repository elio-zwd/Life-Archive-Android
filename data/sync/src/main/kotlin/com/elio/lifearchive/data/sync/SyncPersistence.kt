package com.elio.lifearchive.data.sync

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import com.elio.lifearchive.core.backup.*
import com.elio.lifearchive.domain.notes.SyncEntry
import java.io.File
import java.security.KeyStore
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

interface DeviceCredentials { fun read(): String?; fun write(token: String); fun clear() }

/** 设备凭据与可导出的同步配置分离；没有明文降级路径。 */
class KeystoreCredentials(private val file: File) : DeviceCredentials {
    private val alias = "life-archive-sync-device"
    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(alias, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }
    override fun read(): String? {
        if (!file.exists()) return null
        val bytes = file.readBytes(); require(bytes.size > 12)
        return Cipher.getInstance("AES/GCM/NoPadding").run { init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes.copyOfRange(0, 12))); doFinal(bytes.copyOfRange(12, bytes.size)).toString(Charsets.UTF_8) }
    }
    override fun write(token: String) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
        atomicWrite(file, cipher.iv + cipher.doFinal(token.toByteArray()))
    }
    override fun clear() { if (file.exists()) check(file.delete()) }
}

@Serializable internal data class SyncConfig(val format: Int = 1, val address: String = "", val libraryId: String = "")
internal class SyncPersistence(val root: File, val gate: StorageGate, val credentials: DeviceCredentials) {
    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = true }
    private val configFile = File(root, "config.json")
    private val baselineFile = File(root, "baseline.json")
    @Volatile var generation = UUID.randomUUID().toString(); private set
    fun configLocked(): SyncConfig = if (configFile.exists()) json.decodeFromString(configFile.readText()) else SyncConfig()
    fun baseLocked(): Map<String, SyncEntry> = if (baselineFile.exists()) json.decodeFromString(baselineFile.readText()) else emptyMap()
    fun saveConfigLocked(config: SyncConfig) { atomicWrite(configFile, json.encodeToString(config).toByteArray()); invalidateLocked() }
    fun saveBaseLocked(base: Map<String, SyncEntry>) = atomicWrite(baselineFile, json.encodeToString(base).toByteArray())
    private fun invalidateLocked() { generation = UUID.randomUUID().toString(); if (baselineFile.exists()) check(baselineFile.delete()) }
    val participant = object : BackupParticipant {
        override val id = "sync"
        override val optionalInLegacy = true
        override suspend fun snapshot(directory: File) { atomicWrite(File(directory, "config.json"), json.encodeToString(configLocked()).toByteArray()) }
        override suspend fun validate(directory: File) {
            require(directory.list()?.toSet() == setOf("config.json"))
            val file = File(directory, "config.json"); require(file.length() in 1..65536)
            val config = json.decodeFromString<SyncConfig>(file.readText()); require(config.format == 1)
            if (config.address.isNotEmpty()) normalizeSyncAddress(config.address)
            if (config.libraryId.isNotEmpty()) require(UUID.fromString(config.libraryId).toString() == config.libraryId)
        }
        override suspend fun restore(directory: File) { validate(directory); credentials.clear(); saveConfigLocked(json.decodeFromString(File(directory, "config.json").readText())) }
        override suspend fun restoreMissing() { credentials.clear(); saveConfigLocked(SyncConfig()) }
    }
}
