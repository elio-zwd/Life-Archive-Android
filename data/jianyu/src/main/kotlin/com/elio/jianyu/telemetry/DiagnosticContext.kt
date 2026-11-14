package com.elio.jianyu.telemetry

import kotlinx.coroutines.ThreadContextElement
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import java.util.UUID

/** 随协程传播，OkHttp 请求创建时复制到 tag；网络线程不依赖 ThreadLocal。 */
data class DiagnosticContext(
    val operationId: String = UUID.randomUUID().toString(),
    val sessionId: Long? = null,
    val questionId: Long? = null,
    val characterId: String? = null,
    val purpose: String? = null,
    val attempt: Int = 0,
    val keyId: String? = null,
    val model: String? = null,
) : ThreadContextElement<DiagnosticContext?>, AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<DiagnosticContext> {
        private val local = ThreadLocal<DiagnosticContext?>()
        fun current(): DiagnosticContext? = local.get()
    }
    override fun updateThreadContext(context: CoroutineContext): DiagnosticContext? =
        local.get().also { local.set(this) }
    override fun restoreThreadContext(context: CoroutineContext, oldState: DiagnosticContext?) { local.set(oldState) }
}
