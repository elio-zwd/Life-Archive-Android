package com.elio.lifearchive.core.markdown

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

data class MarkdownHeading(val title: String, val level: Int, val offset: Int)

/** 仅暴露原文定位；宿主释放时撤销回调，避免跨文档持有已销毁的 WebView。 */
class MarkdownNavigation {
    var headings by mutableStateOf<List<MarkdownHeading>>(emptyList())
        internal set
    internal var request: (() -> Unit)? = null
    internal var jump: ((Int) -> Unit)? = null
    fun refresh() { request?.invoke() }
    fun moveTo(offset: Int) { jump?.invoke(offset) }
}

enum class MarkdownLoadPhase { BODY, FIRST_SCREEN, IDLE }
data class MarkdownLoadEvent(val phase: MarkdownLoadPhase, val revision: String, val elapsedMs: Long, val cacheHit: Boolean, val artifactHits: Int = 0)
