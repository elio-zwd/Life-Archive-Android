package com.elio.lifearchive.core.live2d

import android.content.Context
import android.graphics.SurfaceTexture
import android.view.MotionEvent
import android.view.TextureView
import kotlin.math.abs

/** 原生透明 TextureView；宿主明确设置 active 与 model，避免页面存活就持续渲染。 */
class Live2DView(context: Context) : TextureView(context), TextureView.SurfaceTextureListener {
    var onStatus: (String?) -> Unit = {}
    var onTap: () -> Unit = {}
    var onModelLoaded: (Live2DModelMetrics) -> Unit = {}
    var allowParentScroll: Boolean = false
    private var renderThread: CubismRenderThread? = null
    private var lease: Any? = null
    private var active = false
    private var mood = "CALM"
    private var token = "initial"
    private var modelSpec: Live2DModelSpec = Live2DModelCatalog.resolve(null)
    private var downX = 0f
    private var downY = 0f
    private var compactFraming = false
    private var fullWidthStage = false
    private var stageX = 0.85f
    private var autoWalk = false
    init {
        isOpaque = false
        surfaceTextureListener = this
        isClickable = true
        contentDescription = "伙伴形象，轻触互动"
    }
    val currentModelSpec: Live2DModelSpec get() = modelSpec
    fun setModel(modelId: String?) = setModel(Live2DModelCatalog.resolve(modelId))
    fun setModel(spec: Live2DModelSpec) {
        if (modelSpec == spec) return
        modelSpec = spec
        lease?.let { renderThread?.model(it, spec) }
    }
    fun setActive(value: Boolean) { active = value; updateVisibility() }
    fun setCompactFraming(enabled: Boolean) { compactFraming = enabled; lease?.let { renderThread?.framing(it, enabled) } }
    fun setStage(fullWidth: Boolean, xFraction: Float) {
        fullWidthStage = fullWidth
        stageX = xFraction.coerceIn(0f, 1f)
        lease?.let { renderThread?.stage(it, fullWidthStage, stageX) }
    }
    fun setAutoWalk(enabled: Boolean) {
        autoWalk = enabled
        lease?.let { renderThread?.autoWalk(it, enabled) }
    }
    /** 系统角色窗口自行识别手势；不改变页面原有的触摸路径。 */
    fun followTouch(x: Float, y: Float) { lease?.let { renderThread?.touch(it, x.coerceIn(-1f, 1f), y.coerceIn(-1f, 1f), false) } }
    fun react(kind: String) { if (kind in setOf("HEAD", "BODY", "STROKE")) lease?.let { renderThread?.react(it, kind) } }
    fun setCue(token: String, mood: String) {
        this.token = token
        this.mood = mood.takeIf { it in setOf("CALM", "HAPPY", "THINKING", "CARING") } ?: "CALM"
        lease?.let { renderThread?.cue(it, this.token, this.mood) }
    }
    override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
        val id = Any()
        lease = id
        renderThread = Pool.acquire().also { engine ->
            engine.attach(id, surface, context.applicationContext.assets, width, height, modelSpec,
                error = { post { if (lease === id) onStatus("形象暂时无法显示，可切换页面重试；对话和记忆仍可使用。") } },
                ready = { metrics -> post { if (lease === id) { onStatus(null); onModelLoaded(metrics) } } })
            engine.cue(id, token, mood)
            engine.framing(id, compactFraming)
            engine.stage(id, fullWidthStage, stageX)
            engine.autoWalk(id, autoWalk)
        }
        updateVisibility()
    }
    override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) {
        lease?.let { renderThread?.resize(it, width, height) }
    }
    override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
        val id = lease
        val engine = renderThread
        lease = null; renderThread = null
        if (id != null && engine != null) engine.detach(id, surface)
        else surface.release()
        return false
    }
    override fun onSurfaceTextureUpdated(surface: SurfaceTexture) = Unit
    override fun onWindowVisibilityChanged(visibility: Int) { super.onWindowVisibilityChanged(visibility); updateVisibility() }
    override fun onVisibilityChanged(changedView: android.view.View, visibility: Int) { super.onVisibilityChanged(changedView, visibility); updateVisibility() }
    private fun updateVisibility() {
        lease?.let { renderThread?.visible(it, active && isShown && windowVisibility == VISIBLE) }
    }
    override fun onTouchEvent(event: MotionEvent): Boolean {
        val id = lease ?: return super.onTouchEvent(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> { downX = event.x; downY = event.y; if (!allowParentScroll) parent?.requestDisallowInterceptTouchEvent(true) }
            MotionEvent.ACTION_MOVE -> renderThread?.touch(id, (event.x / width.coerceAtLeast(1) * 2 - 1).coerceIn(-1f, 1f),
                (1 - event.y / height.coerceAtLeast(1) * 2).coerceIn(-1f, 1f), false)
            MotionEvent.ACTION_UP -> {
                renderThread?.touch(id, 0f, 0f, false)
                if (abs(event.x - downX) < resources.displayMetrics.density * 12 && abs(event.y - downY) < resources.displayMetrics.density * 12) performClick()
                parent?.requestDisallowInterceptTouchEvent(false)
            }
            MotionEvent.ACTION_CANCEL -> { renderThread?.touch(id, 0f, 0f, false); parent?.requestDisallowInterceptTouchEvent(false) }
        }
        return true
    }
    override fun performClick(): Boolean {
        super.performClick()
        lease?.let { renderThread?.touch(it, 0f, 0f, true) }
        onTap()
        return true
    }
    // 保留一个空闲线程序列化重新打开；零窗口时没有 EGL、模型、纹理或帧循环。
    private object Pool {
        private var engine: CubismRenderThread? = null
        fun acquire(): CubismRenderThread = engine ?: CubismRenderThread().also { engine = it }
    }
}
