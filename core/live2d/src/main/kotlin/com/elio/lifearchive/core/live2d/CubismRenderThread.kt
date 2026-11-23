package com.elio.lifearchive.core.live2d

import android.content.res.AssetManager
import android.graphics.SurfaceTexture
import android.opengl.*
import android.os.Handler
import android.os.HandlerThread
import android.view.Surface
import com.live2d.sdk.cubism.framework.CubismFramework
import com.live2d.sdk.cubism.framework.rendering.android.CubismOffscreenManagerAndroid
import java.util.IdentityHashMap

/** Framework 的 shader/offscreen 是进程静态缓存，所有窗口必须在同一线程、同一 EGLContext 绘制。 */
internal class CubismRenderThread {
    private val thread = HandlerThread("Companion-Cubism").apply { start() }
    private val handler = Handler(thread.looper)
    private val windows = IdentityHashMap<Any, Window>()
    private var display = EGL14.EGL_NO_DISPLAY
    private var context = EGL14.EGL_NO_CONTEXT
    private var config: EGLConfig? = null
    private var parking = EGL14.EGL_NO_SURFACE
    private var scheduled = false
    private var previous = 0L
    private class Window(
        val texture: SurfaceTexture,
        val assets: AssetManager,
        var width: Int,
        var height: Int,
        var modelSpec: Live2DModelSpec,
        val error: () -> Unit,
        val ready: (Live2DModelMetrics) -> Unit,
    ) {
        var surface: Surface? = null
        var egl = EGL14.EGL_NO_SURFACE
        var model: NativeCubismModel? = null
        var mood = "CALM"
        var compactFraming = false
        var fullWidthStage = false
        var stageX = 0.85f
        var autoWalk = false
        val cues = CueTracker()
        lateinit var session: RenderSession
    }

    fun attach(
        id: Any,
        texture: SurfaceTexture,
        assets: AssetManager,
        width: Int,
        height: Int,
        modelSpec: Live2DModelSpec,
        error: () -> Unit,
        ready: (Live2DModelMetrics) -> Unit,
    ) = handler.post {
        val window = Window(texture, assets, width.coerceAtLeast(1), height.coerceAtLeast(1), modelSpec, error, ready)
        window.session = RenderSession({ open(window) }, { release(window) })
        windows[id] = window
    }
    fun model(id: Any, spec: Live2DModelSpec) = handler.post {
        windows[id]?.let { window ->
            if (window.modelSpec == spec) return@let
            window.modelSpec = spec
            if (window.session.opened) {
                guarded(window) {
                    current(window)
                    window.model?.close()
                    window.model = null
                    val created = NativeCubismModel.create(window.modelSpec, window.assets, window.width, window.height).also {
                        it.cue(window.mood)
                        it.compactFraming(window.compactFraming)
                        it.stage(window.fullWidthStage, window.stageX)
                        it.autoWalk(window.autoWalk)
                    }
                    window.model = created
                    window.ready(created.metrics())
                }
            }
        }
    }
    fun stage(id: Any, fullWidth: Boolean, xFraction: Float) = handler.post {
        windows[id]?.let { window ->
            window.fullWidthStage = fullWidth
            window.stageX = xFraction
            window.model?.stage(fullWidth, xFraction)
        }
    }
    fun autoWalk(id: Any, enabled: Boolean) = handler.post {
        windows[id]?.let { window ->
            window.autoWalk = enabled
            window.model?.autoWalk(enabled)
        }
    }
    fun visible(id: Any, active: Boolean) = handler.post {
        windows[id]?.let { window -> guarded(window) { window.session.visible(active) } }
        if (windows.values.none { it.session.opened }) disposeEgl()
        schedule()
    }
    fun resize(id: Any, width: Int, height: Int) = handler.post {
        windows[id]?.let { window ->
            window.width = width.coerceAtLeast(1); window.height = height.coerceAtLeast(1)
            guarded(window) { if (window.session.opened) { current(window); window.model?.resize(window.width, window.height) } }
        }
    }
    fun cue(id: Any, token: String, mood: String) = handler.post {
        windows[id]?.let { window ->
            if (window.cues.accept(token)) { window.mood = mood; guarded(window) { window.model?.cue(mood) } }
        }
    }
    fun touch(id: Any, x: Float, y: Float, tapped: Boolean) = handler.post {
        windows[id]?.let { window -> guarded(window) { window.model?.touch(x, y, tapped) } }
    }
    fun react(id: Any, kind: String) = handler.post {
        windows[id]?.let { window -> guarded(window) { window.model?.react(kind) } }
    }
    fun framing(id: Any, enabled: Boolean) = handler.post {
        windows[id]?.let { window -> window.compactFraming = enabled; window.model?.compactFraming(enabled) }
    }
    // SurfaceTexture 归引擎接管，销毁 EGLSurface 后才 release，避免 UI 线程与 swapBuffers 竞争。
    fun detach(id: Any, texture: SurfaceTexture) = handler.post {
        windows.remove(id)?.let { window -> guarded(window) { window.session.close() } }
        texture.release()
        if (windows.values.none { it.session.opened }) disposeEgl()
    }
    private fun initialize(assets: AssetManager) {
        if (context != EGL14.EGL_NO_CONTEXT) return
        display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        check(EGL14.eglInitialize(display, IntArray(2), 0, IntArray(2), 1))
        val configs = arrayOfNulls<EGLConfig>(1)
        val count = IntArray(1)
        check(EGL14.eglChooseConfig(display, intArrayOf(EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8,
            EGL14.EGL_BLUE_SIZE, 8, EGL14.EGL_ALPHA_SIZE, 8, EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
            EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT or EGL14.EGL_PBUFFER_BIT, EGL14.EGL_NONE), 0, configs, 0, 1, count, 0) && count[0] > 0)
        config = configs[0]
        context = EGL14.eglCreateContext(display, config, EGL14.EGL_NO_CONTEXT,
            intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE), 0)
        check(context != EGL14.EGL_NO_CONTEXT)
        parking = EGL14.eglCreatePbufferSurface(display, config, intArrayOf(EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE), 0)
        check(parking != EGL14.EGL_NO_SURFACE)
        check(EGL14.eglMakeCurrent(display, parking, parking, context))
        val option = CubismFramework.Option().apply { loadFileFunction = com.live2d.sdk.cubism.framework.ICubismLoadFileFunction { path -> assets.open(path).use { it.readBytes() } } }
        check(CubismFramework.startUp(option))
        CubismFramework.initialize()
    }
    private fun open(window: Window) {
        try {
            initialize(window.assets)
            window.surface = Surface(window.texture)
            window.egl = EGL14.eglCreateWindowSurface(display, config, window.surface, intArrayOf(EGL14.EGL_NONE), 0)
            check(window.egl != EGL14.EGL_NO_SURFACE)
            current(window)
            val created = NativeCubismModel.create(window.modelSpec, window.assets, window.width, window.height).also {
                it.cue(window.mood)
                it.compactFraming(window.compactFraming)
                it.stage(window.fullWidthStage, window.stageX)
                it.autoWalk(window.autoWalk)
            }
            window.model = created
            window.ready(created.metrics())
        } catch (error: Exception) { release(window); throw error }
        catch (error: LinkageError) { release(window); throw error }
    }
    private fun current(window: Window) { check(EGL14.eglMakeCurrent(display, window.egl, window.egl, context)) }
    private fun release(window: Window) {
        try {
            if (context != EGL14.EGL_NO_CONTEXT) {
                EGL14.eglMakeCurrent(display, parking, parking, context)
                window.model?.close()
            }
        } finally {
            window.model = null
            if (window.egl != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(display, window.egl)
            window.egl = EGL14.EGL_NO_SURFACE
            window.surface?.release(); window.surface = null
        }
    }
    private fun guarded(window: Window, block: () -> Unit) {
        try { block() }
        catch (error: Exception) { android.util.Log.e("Live2D", "Native rendering unavailable", error); runCatching { window.session.close() }; window.error() }
        catch (error: LinkageError) { android.util.Log.e("Live2D", "Native library unavailable", error); runCatching { window.session.close() }; window.error() }
    }
    private fun schedule() {
        if (scheduled || windows.values.none { it.session.opened }) return
        scheduled = true
        handler.postDelayed({
            scheduled = false
            val now = System.nanoTime()
            val delta = if (previous == 0L) 1f / 30f else ((now - previous) / 1_000_000_000f).coerceIn(0f, 0.05f)
            previous = now
            windows.values.filter { it.session.opened }.forEach { window -> guarded(window) {
                current(window)
                GLES20.glViewport(0, 0, window.width, window.height)
                GLES20.glClearColor(0f, 0f, 0f, 0f)
                GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
                val manager = CubismOffscreenManagerAndroid.getInstance()
                manager.beginFrameProcess()
                try { window.model?.draw(delta) } finally { manager.endFrameProcess() }
                check(EGL14.eglSwapBuffers(display, window.egl))
            } }
            if (windows.values.none { it.session.opened }) disposeEgl()
            schedule()
        }, 33)
    }
    private fun disposeEgl() {
        if (display == EGL14.EGL_NO_DISPLAY) return
        if (context != EGL14.EGL_NO_CONTEXT && parking != EGL14.EGL_NO_SURFACE) {
            EGL14.eglMakeCurrent(display, parking, parking, context)
            CubismOffscreenManagerAndroid.releaseInstance()
            if (CubismFramework.isInitialized()) CubismFramework.dispose()
            CubismFramework.cleanUp()
        }
        EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
        if (parking != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(display, parking)
        if (context != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(display, context)
        EGL14.eglTerminate(display)
        EGL14.eglReleaseThread()
        display = EGL14.EGL_NO_DISPLAY; context = EGL14.EGL_NO_CONTEXT; parking = EGL14.EGL_NO_SURFACE; config = null; previous = 0
    }
}
