package com.elio.lifearchive

import android.content.Context
import kotlin.coroutines.intrinsics.COROUTINE_SUSPENDED
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.suspendCancellableCoroutine

/** Robolectric 复用类加载器但更换 App 目录，关闭上一用例实例；不增加业务公开测试接口。 */
internal fun resetJianyuRuntimeForTest(context: Context) = runBlocking(Dispatchers.IO) {
    val type = Class.forName("com.elio.jianyu.JianyuAppRuntimeProvider")
    val provider = type.getField("INSTANCE").get(null)
    val reset = type.declaredMethods.single { it.name.startsWith("resetForTests") }.apply { isAccessible = true }
    suspendCancellableCoroutine<Unit> { continuation ->
        try {
            val result = reset.invoke(provider, context, continuation)
            if (result !== COROUTINE_SUSPENDED) continuation.resume(Unit)
        } catch (error: java.lang.reflect.InvocationTargetException) {
            continuation.resumeWithException(error.targetException)
        }
    }
}
