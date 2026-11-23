package com.elio.lifearchive.core.live2d

import androidx.test.core.app.ApplicationProvider
import java.io.File
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class Live2DViewTest {
    @Test fun viewConstructionIsTransparentAndDoesNotRequireNativeContext() {
        val view = Live2DView(ApplicationProvider.getApplicationContext())
        assertFalse(view.isOpaque)
        assertTrue(view.contentDescription.toString().contains("轻触"))
        view.setActive(false); view.setCue("same-reply", "UNTRUSTED_ACTION")
        assertFalse(view.isAvailable)
        assertEquals(Live2DModelCatalog.DEFAULT_MODEL_ID, view.currentModelSpec.id)
    }

    @Test fun accessibilityClickUsesSameTouchFeedbackCallback() {
        val view = Live2DView(ApplicationProvider.getApplicationContext())
        var clicks = 0
        view.onTap = { clicks++ }
        assertTrue(view.performClick()); assertEquals(1, clicks)
    }

    @Test fun catalogContainsAllEightBuiltInModelsAndResolvesFallback() {
        val ids = Live2DModelCatalog.builtIn.map { it.id }
        assertEquals(listOf("Hiyori", "Mao", "Ren", "Haru", "Rice", "Mark", "Wanko", "Natori"), ids)
        assertEquals("Mao", Live2DModelCatalog.resolve("Mao").id)
        assertEquals(Live2DModelCatalog.DEFAULT_MODEL_ID, Live2DModelCatalog.resolve("missing-model").id)
        val view = Live2DView(ApplicationProvider.getApplicationContext())
        view.setModel("Wanko")
        assertEquals("Wanko", view.currentModelSpec.id)
        view.setModel("unknown-id")
        assertEquals("Hiyori", view.currentModelSpec.id)
    }

    @Test fun catalogSupportsRegisteringAndDiscoveringExternalDirectoryModels() {
        val dir = File.createTempFile("live2d-custom", "").apply { delete(); mkdirs() }
        try {
            File(dir, "CustomAvatar.model3.json").writeText("{}")
            val spec = Live2DModelCatalog.discoverInDirectory("custom-1", "自定义", "外部目录导入", dir)
            assertNotNull(spec)
            Live2DModelCatalog.register(spec!!)
            assertEquals("custom-1", Live2DModelCatalog.resolve("custom-1").id)
            assertTrue(Live2DModelCatalog.all().any { it.id == "custom-1" })
            assertTrue(Live2DModelCatalog.unregister("custom-1"))
            assertEquals(Live2DModelCatalog.DEFAULT_MODEL_ID, Live2DModelCatalog.resolve("custom-1").id)
        } finally {
            Live2DModelCatalog.unregister("custom-1")
            dir.deleteRecursively()
        }
    }
}
