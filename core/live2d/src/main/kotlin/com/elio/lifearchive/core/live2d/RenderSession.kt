package com.elio.lifearchive.core.live2d

/** 将可见性与 Surface 分开；隐藏立即释放模型，再显现时从业务快照重建。 */
internal class RenderSession(private val create: () -> Unit, private val release: () -> Unit) {
    var opened = false
        private set
    private var closed = false
    fun visible(value: Boolean) {
        if (closed || value == opened) return
        if (value) { create(); opened = true }
        else { opened = false; release() }
    }
    fun close() {
        if (closed) return
        visible(false)
        closed = true
    }
}

/** 同一次回复的 cue 只触发一次；同情感的下一次回复仍可再次触发动作。 */
internal class CueTracker {
    private var token: String? = null
    fun accept(value: String): Boolean = if (value == token) false else { token = value; true }
}
