package com.elio.jianyu.runtime

import android.app.Application
import android.util.AtomicFile
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.viewModelScope
import com.elio.jianyu.JianyuAppRuntimeProvider
import com.elio.jianyu.JianyuServices
import com.elio.jianyu.audio.workspace.RuntimeAudioAssetWorkspaceOperations
import com.elio.jianyu.backup.AndroidJianyuBackupService
import com.elio.jianyu.data.ConversationReadingStateRepository
import com.elio.jianyu.data.UserAvatarRepository
import com.elio.jianyu.material.AndroidTextMaterialReader
import com.elio.jianyu.network.AndroidAiServices
import com.elio.jianyu.preferences.StoredJianyuPreferences
import com.elio.jianyu.privacy.DefaultJianyuDataMaintenance
import com.elio.jianyu.skill.role.AndroidRolePresentationLoader
import com.elio.jianyu.storage.jianyuNoBackupFilesDir
import com.elio.jianyu.telemetry.AndroidDiagnosticServices
import com.elio.jianyu.viewmodel.RoundtableViewModel
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

class AndroidJianyuHostRuntime(private val application: Application) : JianyuHostRuntime {
    private val observerScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    override val availability = JianyuAppRuntimeProvider.states().map { state ->
        when (state) {
            JianyuRuntimeState.Uninitialized -> JianyuAvailability.Uninitialized
            is JianyuRuntimeState.Ready -> JianyuAvailability.Ready(state.generation)
            is JianyuRuntimeState.Maintenance -> JianyuAvailability.Maintenance(state.generation)
            is JianyuRuntimeState.Unavailable -> JianyuAvailability.Failed
        }
    }.stateIn(observerScope, SharingStarted.Eagerly, JianyuAvailability.Uninitialized)

    override suspend fun initialize(retry: Boolean) = withContext(Dispatchers.IO) {
        if (retry && JianyuAppRuntimeProvider.states().value is JianyuRuntimeState.Unavailable) {
            check(JianyuAppRuntimeProvider.retryOpen(application)) { "见域重开失败" }
        } else {
            JianyuAppRuntimeProvider.get(application)
        }
        Unit
    }

    override suspend fun openSession(generation: Long): JianyuHostSession {
        val lease = JianyuAppRuntimeProvider.tryAcquireReady(generation) ?: throw JianyuRuntimeUnavailableException()
        val owner = object : ViewModelStoreOwner { override val viewModelStore = ViewModelStore() }
        var model: RoundtableViewModel? = null
        try {
            val epoch = withContext(Dispatchers.IO) {
                JianyuAppRuntimeProvider.awaitInitialRecovery(lease.runtime)
                // 首次 XML／Key 读取在后台完成，ViewModel 构造仅装配已打开的服务。
                StoredJianyuPreferences(application)
                AndroidAiServices(application)
                JianyuContentEpoch.read(application)
            }
            currentCoroutineContext().ensureActive()
            check(JianyuAppRuntimeProvider.states().value.let { it is JianyuRuntimeState.Ready && it.generation == generation })
            val conversation = withContext(Dispatchers.Main.immediate) {
                val factory = object : ViewModelProvider.Factory {
                    @Suppress("UNCHECKED_CAST")
                    override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T {
                        check(modelClass == RoundtableViewModel::class.java)
                        return RoundtableViewModel(application, lease.runtime) as T
                    }
                }
                ViewModelProvider(owner, factory)[RoundtableViewModel::class.java]
            }
            model = conversation
            val services = withContext(Dispatchers.IO) {
                val runtime = lease.runtime
                JianyuServices(
                    repository = runtime.repository,
                    officialSkillCatalogRuntimeResult = runtime.officialSkillCatalogRuntimeResult,
                    executionCoordinator = runtime.executionCoordinator,
                    collaborationCoordinator = runtime.collaborationCoordinator,
                    skillKnowledgeRepository = runtime.skillKnowledgeRepository,
                    stageResultService = runtime.stageResultService,
                    audioWorkspace = RuntimeAudioAssetWorkspaceOperations(runtime.audioRuntime),
                    lifecycleRuntime = runtime.lifecycleRuntime,
                    preferences = StoredJianyuPreferences(application),
                    avatars = UserAvatarRepository(application),
                    readingState = ConversationReadingStateRepository(application),
                    materials = AndroidTextMaterialReader(application),
                    rolePresentation = AndroidRolePresentationLoader(application),
                    ai = AndroidAiServices(application),
                    diagnostics = AndroidDiagnosticServices(application),
                    maintenance = DefaultJianyuDataMaintenance(application, runtime, conversation),
                    backup = AndroidJianyuBackupService(application),
                )
            }
            return HostSession(generation, epoch, services, conversation, owner, lease)
        } catch (error: Throwable) {
            withContext(NonCancellable) {
                model?.viewModelScope?.coroutineContext?.get(Job)?.cancelAndJoin()
                withContext(Dispatchers.Main.immediate) { owner.viewModelStore.clear() }
                lease.close()
            }
            throw error
        }
    }
}

private class HostSession(
    override val generation: Long,
    override val contentEpoch: String,
    override val services: JianyuServices,
    override val conversation: RoundtableViewModel,
    private val owner: ViewModelStoreOwner,
    private val lease: JianyuRuntimeLease,
) : JianyuHostSession {
    private val closed = AtomicBoolean()
    override val tasks = SessionTasks()
    override suspend fun release() = withContext(NonCancellable) {
        if (closed.compareAndSet(false, true)) {
            try {
                tasks.cancelAndJoin()
                conversation.viewModelScope.coroutineContext[Job]?.cancelAndJoin()
                withContext(Dispatchers.Main.immediate) { owner.viewModelStore.clear() }
            } finally {
                lease.close()
            }
        }
    }
    override fun releaseAsync() {
        CoroutineScope(Dispatchers.Main.immediate).launch { release() }
    }
}

private class SessionTasks : JianyuSessionTasks {
    private val monitor = Any()
    private val jobs = mutableSetOf<Job>()
    private var closing = false
    override fun register(job: Job) {
        synchronized(monitor) {
            if (closing) job.cancel() else if (jobs.add(job)) job.invokeOnCompletion { synchronized(monitor) { jobs.remove(job) } }
        }
    }
    suspend fun cancelAndJoin() {
        val pending = synchronized(monitor) { closing = true; jobs.toList() }
        pending.forEach { it.cancel() }
        pending.joinAll()
    }
}

internal object JianyuContentEpoch {
    fun read(application: Application): String {
        val file = AtomicFile(File(application.jianyuNoBackupFilesDir, "content-epoch"))
        if (file.baseFile.exists()) return file.openRead().bufferedReader().use { UUID.fromString(it.readText()).toString() }
        return UUID.randomUUID().toString().also { value ->
            val output = file.startWrite()
            try {
                output.write(value.toByteArray(Charsets.UTF_8))
                output.fd.sync()
                file.finishWrite(output)
            } catch (error: Throwable) {
                file.failWrite(output)
                throw error
            }
        }
    }
}
