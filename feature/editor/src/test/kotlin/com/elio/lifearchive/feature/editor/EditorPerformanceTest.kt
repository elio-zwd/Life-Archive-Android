package com.elio.lifearchive.feature.editor

import com.elio.lifearchive.core.document.ContinuationContext
import com.elio.lifearchive.core.document.MarkdownContinuation
import java.nio.file.Files
import java.nio.file.Paths
import org.junit.Assert.*
import org.junit.Test

/** 对比同一 JVM 的完整扫描与索引查询；记录采样，不把电脑时间当成输入法成绩。 */
class EditorPerformanceTest {
    @Test fun indexedQueriesMatchFullProjectionAndExportTwentySamples() {
        val records = mutableListOf<String>()
        for (size in listOf(50 * 1024, 1024 * 1024)) {
            val token = "正文 **保留**\n\n"
            val tail = "\n\n> 9. 内容"
            val repeats = (size - tail.toByteArray(Charsets.UTF_8).size) / token.toByteArray(Charsets.UTF_8).size
            val prefix = token.repeat(repeats)
            val raw = prefix + "a".repeat(size - prefix.toByteArray(Charsets.UTF_8).size - tail.toByteArray(Charsets.UTF_8).size) + tail
            assertEquals(size, raw.toByteArray(Charsets.UTF_8).size)
            val current = raw + "新增"
            val parsed = MarkdownParser().parse(raw)
            val projection = EditorRenderProjection(0, parsed)
            val display = EditorDisplaySnapshot(0, parsed)
            val changes = listOf(EditorTextChange(0, 1, raw.length, raw.length, current.length))
            val window = previewWindow(current.length, current.length)
            val expected = projection.forText(current).styles.filter { it.end > window.first && it.start <= window.last }.toSet()
            assertEquals(expected, display.styles(window, changes, current.length).toSet())
            val context = ContinuationContext.build(raw)
            assertEquals(MarkdownContinuation.calculate(raw, raw.length), MarkdownContinuation.calculate(raw as CharSequence, raw.length, context, raw.length, "\n"))
            repeat(5) { display.styles(window, changes, current.length); MarkdownContinuation.calculate(raw as CharSequence, raw.length, context, raw.length, "\n") }
            repeat(20) { sample ->
                val fullDisplay = elapsed { projection.forText(current).styles.filter { it.end > window.first && it.start <= window.last } }
                val indexedDisplay = elapsed { display.styles(window, changes, current.length) }
                val fullEnter = elapsed { MarkdownContinuation.calculate(raw, raw.length) }
                val indexedEnter = elapsed { MarkdownContinuation.calculate(raw as CharSequence, raw.length, context, raw.length, "\n") }
                records += "{\"characters\":${raw.length},\"utf8Bytes\":${raw.toByteArray(Charsets.UTF_8).size},\"sample\":$sample,\"fullDisplayMs\":$fullDisplay,\"indexedDisplayMs\":$indexedDisplay,\"fullEnterMs\":$fullEnter,\"indexedEnterMs\":$indexedEnter}"
            }
        }
        val directory = Paths.get(System.getenv("LIFE_ARCHIVE_EVIDENCE") ?: requireNotNull(System.getProperty("java.io.tmpdir")) + "/life-archive-editor-performance")
        Files.createDirectories(directory)
        Files.write(directory.resolve("editor-query-samples.json"), ("[\n" + records.joinToString(",\n") + "\n]\n").toByteArray(Charsets.UTF_8))
    }

    private inline fun elapsed(action: () -> Any?): Double {
        val started = System.nanoTime(); action(); return (System.nanoTime() - started) / 1_000_000.0
    }
}
