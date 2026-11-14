package com.elio.jianyu.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.coroutines.delay

/** 仅在页面存在且仍有未来到期时间时刷新，不为已经结束的冷却保持后台计时。 */
@Composable
internal fun rememberExpiryClock(expiresAt: List<Long>): Long {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(expiresAt) {
        while (true) {
            now = System.currentTimeMillis()
            val next = expiresAt.filter { it > now }.minOrNull() ?: break
            delay(minOf(1000L, next - now))
        }
    }
    return now
}
