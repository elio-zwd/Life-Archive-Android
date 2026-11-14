package com.elio.jianyu.runtime

import com.elio.jianyu.JianyuServices
import com.elio.jianyu.viewmodel.ConversationSession
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.StateFlow

sealed interface JianyuAvailability {
    data object Uninitialized : JianyuAvailability
    data class Ready(val generation: Long) : JianyuAvailability
    data class Maintenance(val generation: Long) : JianyuAvailability
    data object Failed : JianyuAvailability
}

interface JianyuSessionTasks {
    fun register(job: Job)
}

interface JianyuHostSession {
    val generation: Long
    val contentEpoch: String
    val services: JianyuServices
    val conversation: ConversationSession
    val tasks: JianyuSessionTasks
    suspend fun release()
    fun releaseAsync()
}

/** 只在用户进入见域时由 app 创建和调用，不参与 Life 的启动依赖链。 */
interface JianyuHostRuntime {
    val availability: StateFlow<JianyuAvailability>
    suspend fun initialize(retry: Boolean = false)
    suspend fun openSession(generation: Long): JianyuHostSession
}
