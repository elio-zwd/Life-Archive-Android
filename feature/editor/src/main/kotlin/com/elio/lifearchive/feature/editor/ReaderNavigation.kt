package com.elio.lifearchive.feature.editor

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

data class ReaderHeading(val title: String, val level: Int, val offset: Int)

/** 阅读器只公开标题及原文偏移，跳转仍由受控 WebView 完成。 */
class ReaderNavigation {
    var headings by mutableStateOf<List<ReaderHeading>>(emptyList())
        internal set
    internal var request: (() -> Unit)? = null
    internal var jump: ((Int) -> Unit)? = null

    fun refresh() { request?.invoke() }
    fun moveTo(offset: Int) { jump?.invoke(offset) }
}
