package com.elio.lifearchive.core.live2d

import android.opengl.*
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.live2d.sdk.cubism.framework.CubismFramework
import com.live2d.sdk.cubism.framework.ICubismLoadFileFunction
import com.live2d.sdk.cubism.framework.rendering.android.CubismOffscreenManagerAndroid
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.nio.ByteBuffer

/** 真实 JNI/EGL 冒烟；电脑只编译，须在隔离测试包运行后才能报告渲染通过。 */
@RunWith(AndroidJUnit4::class)
class NativeRenderDeviceTest {
    @Test fun hiyoriRendersTransparentPixelsAndRebuildsNativeResources() {
        val assets = InstrumentationRegistry.getInstrumentation().targetContext.assets
        val display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        assertTrue(EGL14.eglInitialize(display, IntArray(2), 0, IntArray(2), 1))
        val configs = arrayOfNulls<EGLConfig>(1)
        val count = IntArray(1)
        assertTrue(EGL14.eglChooseConfig(display, intArrayOf(EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8,
            EGL14.EGL_BLUE_SIZE, 8, EGL14.EGL_ALPHA_SIZE, 8, EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
            EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT, EGL14.EGL_NONE), 0, configs, 0, 1, count, 0))
        assertTrue(count[0] > 0)
        val config = configs[0]
        val context = EGL14.eglCreateContext(display, config, EGL14.EGL_NO_CONTEXT, intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE), 0)
        val surface = EGL14.eglCreatePbufferSurface(display, config, intArrayOf(EGL14.EGL_WIDTH, 128, EGL14.EGL_HEIGHT, 256, EGL14.EGL_NONE), 0)
        try {
            assertTrue(EGL14.eglMakeCurrent(display, surface, surface, context))
            assertTrue(CubismFramework.startUp(CubismFramework.Option().apply {
                loadFileFunction = ICubismLoadFileFunction { path -> assets.open(path).use { it.readBytes() } }
            }))
            CubismFramework.initialize()
            repeat(2) {
                val model = NativeCubismModel.create(assets, 128, 256)
                try {
                    model.cue("HAPPY"); model.touch(0.2f, 0.1f, true)
                    GLES20.glViewport(0, 0, 128, 256)
                    GLES20.glClearColor(0f, 0f, 0f, 0f); GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
                    val manager = CubismOffscreenManagerAndroid.getInstance()
                    manager.beginFrameProcess()
                    try { model.draw(1f / 30f) } finally { manager.endFrameProcess() }
                    val pixels = ByteBuffer.allocateDirect(128 * 256 * 4)
                    GLES20.glReadPixels(0, 0, 128, 256, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, pixels)
                    assertEquals(GLES20.GL_NO_ERROR, GLES20.glGetError())
                    val alpha = (3 until pixels.capacity() step 4).map { pixels.get(it).toInt() and 255 }
                    assertTrue("应存在模型像素", alpha.any { it > 0 })
                    assertTrue("模型周围应透明", alpha.any { it == 0 })
                } finally { model.close() }
            }
        } finally {
            CubismOffscreenManagerAndroid.releaseInstance()
            if (CubismFramework.isInitialized()) CubismFramework.dispose()
            CubismFramework.cleanUp()
            EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
            EGL14.eglDestroySurface(display, surface); EGL14.eglDestroyContext(display, context)
            EGL14.eglTerminate(display); EGL14.eglReleaseThread()
        }
    }
}
