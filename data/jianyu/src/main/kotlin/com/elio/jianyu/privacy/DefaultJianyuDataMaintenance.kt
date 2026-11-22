package com.elio.jianyu.privacy

import android.content.Context
import androidx.work.WorkManager
import com.elio.jianyu.JianyuAppRuntime
import com.elio.jianyu.audio.AudioPlaybackManager
import com.elio.jianyu.backup.AndroidKeystoreSnapshotKeyProvider
import com.elio.jianyu.backup.BackupOperationGate
import com.elio.jianyu.backup.SnapshotCatalog
import com.elio.jianyu.data.ConversationSessionPreferences
import com.elio.jianyu.domain.*
import com.elio.jianyu.lifecycle.IssueLifecycleTaskStopResult
import com.elio.jianyu.network.AiManager
import com.elio.jianyu.network.AiProvider
import com.elio.jianyu.preferences.AppPreferences
import com.elio.jianyu.profile.UserAvatarMutationResult
import com.elio.jianyu.data.UserAvatarRepository
import com.elio.jianyu.skill.catalog.*
import com.elio.jianyu.storage.*
import com.elio.jianyu.telemetry.TelemetryRepository
import com.elio.jianyu.viewmodel.ConversationSession
import java.io.File

class DefaultJianyuDataMaintenance(
    context: Context,
    private val runtime: JianyuAppRuntime,
    private val conversation: ConversationSession,
) : JianyuDataMaintenance {
    private val context = context.applicationContext
    override suspend fun clearAll(): String = com.elio.jianyu.backup.complete.AndroidJianyuCompleteBackup.forContext(context).clearOwned {
        var databaseCleared = false
        val preferences = (runtime.officialSkillCatalogRuntimeResult as? OfficialSkillCatalogRuntimeResult.Success)?.runtime?.preferences
        val result = clearAllLocalData(context, runtime.repository, preferences, {
            conversation.prepareForLocalDataDeletion()
            val issues = runtime.repository.listIssueNavigation(IssueLifecycleState.entries.toSet())
            check(issues is RepositoryResult.Success) { "无法核对见域运行任务" }
            for (issue in issues.value) {
                val tasks = runtime.lifecycleRuntime.taskController.inspect(issue.issue.id)
                if (tasks.hasActiveWork) check(runtime.lifecycleRuntime.taskController.stopAll(tasks) is IssueLifecycleTaskStopResult.Stopped) {
                    "见域任务尚未停止"
                }
            }
        }, conversation::clearLocalPreferencesAfterDataDeletion, { databaseCleared = true })
        val message = if (!databaseCleared || UserAvatarRepository(context).resetToDefault() == UserAvatarMutationResult.Success) result
            else "$result；头像清理失败，请重试。"
        message to databaseCleared
    }
}

private suspend fun clearAllLocalData(
    context: Context,
    repository: JianyuRepository,
    officialSkillPreferences: OfficialSkillPreferences?,
    onPrepareForLocalDataDeletion: (suspend () -> Unit)?,
    onClearConversationPreferences: (() -> Boolean)?,
    onDatabaseCleared: () -> Unit,
): String {
    return try {
        onPrepareForLocalDataDeletion?.invoke()
        if (!cancelAppOwnedWork(context)) {
            return "本地数据清除失败；后台任务未能安全停止，请重试。"
        }
        AudioPlaybackManager.stopAudio()
        BackupOperationGate.forContext(context).withWriteLock {
            when (repository.clearAllData()) {
                is RepositoryResult.Success -> {
                    onDatabaseCleared()
                    // 清理见域内容不能撤销宠物等业务正在使用的公共凭据或配置。
                    val telemetryCleared = TelemetryRepository.clearAllTelemetry(context)
                    val appPreferencesCleared = AppPreferences.reset(context)
                    val officialSkillPreferencesCleared =
                        officialSkillPreferences?.clearAll()
                            ?: clearStoredOfficialSkillPreferences(context)
                    val conversationPreferencesCleared =
                        onClearConversationPreferences?.invoke()
                            ?: clearConversationPreferenceFiles(context)
                    val snapshotsCleared = SnapshotCatalog.clearAll(context)
                    val snapshotKeyCleared = AndroidKeystoreSnapshotKeyProvider().deleteExisting()
                    val audioFilesCleared = clearAppOwnedAudioFiles(context)
                    val cleanupSucceeded = telemetryCleared &&
                        appPreferencesCleared &&
                        officialSkillPreferencesCleared &&
                        conversationPreferencesCleared &&
                        snapshotsCleared &&
                        snapshotKeyCleared &&
                        audioFilesCleared
                    if (cleanupSucceeded) {
                        "本地数据已清除"
                    } else {
                        "数据库已清空，但部分本地设置、文件或设备快照清理失败，请重试。"
                    }
                }
                is RepositoryResult.Failure -> "本地数据清除失败；未确认清除完成。"
            }
        }
    } catch (canceled: kotlinx.coroutines.CancellationException) {
        throw canceled
    } catch (_: Exception) {
        "本地数据清除失败；未确认清除完成。"
    }
}
private fun cancelAppOwnedWork(context: Context): Boolean = runCatching {
    val manager = WorkManager.getInstance(context.applicationContext)
    listOf("jianyu-formal-audio-generation", "jianyu-issue-purge", "jianyu-transcode").forEach {
        manager.cancelAllWorkByTag(it).result.get()
    }
    true
}.getOrDefault(false)

private fun clearConversationPreferenceFiles(context: Context): Boolean {
    val sessionPreferencesCleared = ConversationSessionPreferences(context).clearAll()
    val roundtableSettingsCleared = context.applicationContext
        .jianyuPreferences("roundtable_settings", Context.MODE_PRIVATE)
        .edit()
        .clear()
        .commit()
    return sessionPreferencesCleared && roundtableSettingsCleared
}

internal fun clearAppOwnedAudioFiles(context: Context): Boolean {
    val audioDirectory = File(context.applicationContext.jianyuFilesDir, "jianyu-audio")
    val audioCleared = !audioDirectory.exists() || audioDirectory.deleteRecursively()
    val cacheCleared = context.applicationContext.jianyuCacheDir
        .listFiles()
        .orEmpty()
        .map { file -> runCatching { file.deleteRecursively() }.getOrDefault(false) }
        .all { it }
    return audioCleared && cacheCleared
}
