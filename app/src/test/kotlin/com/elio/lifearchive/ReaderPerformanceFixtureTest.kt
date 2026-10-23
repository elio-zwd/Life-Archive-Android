package com.elio.lifearchive

import com.elio.lifearchive.core.document.HtmlProjection
import com.elio.lifearchive.feature.editor.PrototypeContent
import java.io.File
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class) @Config(sdk = [35]) @GraphicsMode(GraphicsMode.Mode.NATIVE)
class ReaderPerformanceFixtureTest {
    @Test fun exportsTheActualAcceptanceNoteAndImageForBrowserMeasurements() {
        val directory = File(System.getenv("TEMP") ?: "D:/Temp", "life-archive-reader-fixtures").also { it.mkdirs() }
        val raw = PrototypeContent.document("验收图片.png")
        val image = sampleLandscape()
        assertTrue(image.size > 1000)
        File(directory, "sample.png").writeBytes(image)
        val sample = HtmlProjection.project(raw)
        val long = ("# 长文标题\n\n" + "中英文长文 **保留格式**，用于阅读打开与定位。Text and source offsets.\n\n".repeat(13000))
        fun exactUtf8(bytes: Int): String {
            var used = 0
            return buildString {
                for (c in long) {
                    val count = c.toString().toByteArray().size
                    if (used + count > bytes) break
                    append(c); used += count
                }
                append(" ".repeat(bytes - used))
            }
        }
        val sizes = linkedMapOf("sample" to raw, "50KiB" to exactUtf8(50 * 1024), "1MiB" to exactUtf8(1024 * 1024), "50MiBPDF" to "# PDF 阅读验证\n\n![[文档.pdf#page=2&height=360]]\n")
        val result = JSONObject()
        sizes.forEach { (name, source) ->
            val projection = if (name == "sample") sample else HtmlProjection.project(source)
            File(directory, "$name.md").writeText(source)
            File(directory, "$name.html").writeText(projection.html)
            result.put(name, JSONObject().put("rawBytes", source.toByteArray().size).put("source", source).put("html", projection.html).put("digest", projection.digest))
        }
        File(directory, "documents.json").writeText(result.toString())
    }
}
