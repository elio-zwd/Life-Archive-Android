package com.elio.lifearchive.core.document

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import kotlinx.serialization.json.*

class DocumentAnalysisTest {
    @Test fun metadataAndPatchesPreserveUnknownPropertiesAndCrLf() {
        val raw = "\uFEFF---\r\nunknown: &keep {custom: true}\r\ntags: [旧标签, 'a/b'] # 说明\r\naliases:\r\n  - 别名\r\ntitle: 独立标题\r\n---\r\n# 正文\r\n#正文标签 [[本子/记录#小节|别名]]\r\n"
        val analyzed = DocumentAnalyzer.analyze(raw)
        assertEquals(listOf("旧标签", "a/b", "正文标签"), analyzed.tags)
        assertEquals(listOf("别名"), analyzed.aliases)
        assertEquals("独立标题", analyzed.title)
        val changed = DocumentAnalyzer.withTags(raw, listOf("新标签"))
        assertTrue(changed.startsWith("\uFEFF---\r\nunknown: &keep {custom: true}\r\n"))
        assertTrue(changed.endsWith("---\r\n# 正文\r\n#正文标签 [[本子/记录#小节|别名]]\r\n"))
        assertEquals("本子/记录#小节", analyzed.links.single().target)
    }
    @Test fun codeCommentsAndFootnotesAreNotFileLinks() {
        val raw = "```md\n[[代码]]\n```\n`[[行内]]` %%[[注释]]%%\n[[真实|显示]]\n[^n]: 注解正文\n"
        assertEquals(listOf("真实"), DocumentAnalyzer.analyze(raw).links.map { it.target })
    }
    @Test fun jsonPropertiesAndTableEscapesKeepTheirOriginalRepresentation() {
        val raw = "---\n{ \"unknown\": {\"tags\": [\"不应修改\"]}, \"tags\" : [\"旧\"], \"aliases\": [\"别名\"] }\n---\n| [[目标\\|显示]] | ![[图.png\\|100]] |\n"
        val changed = DocumentAnalyzer.withTags(raw, listOf("新"))
        assertEquals(listOf("新"), DocumentAnalyzer.analyze(changed).tags)
        assertTrue(changed.contains("\"unknown\": {\"tags\": [\"不应修改\"]}"))
        assertTrue(HtmlProjection.render(raw).contains("data-target=\"图.png|100\""))
        assertEquals("目标", DocumentAnalyzer.analyze(raw).links.first().target)
    }
    @Test fun indentedCodeAndMathAreExcludedFromLinkMaintenance() {
        val raw = "    [[代码]] ==原样== %%原样%%\n\n\$ [[变量]] \$\n\n[[链接]]\n"
        assertEquals(listOf("链接"), DocumentAnalyzer.analyze(raw).links.map { it.target })
        assertTrue(HtmlProjection.render(raw).contains("[[代码]] ==原样== %%原样%%"))
    }
    @Test fun formattedAndNestedHeadingsResolveWithinTheCorrectParent() {
        val source = "\uFEFF# **甲**\n\n## 子标题\n\n# 乙\n\n## 子标题\n"
        val analysis = DocumentAnalyzer.analyze(source)
        assertEquals(source.lastIndexOf("## 子标题"), DocumentAnalyzer.heading(analysis, "乙#子标题")!!.start)
        assertEquals("甲", analysis.headings.first().name)
    }
    @Test fun blockAndSetextLocationsUseOriginalOffsets() {
        val raw = "前言\r\n\r\n章节\r\n===\r\n\r\n> 引用内容\r\n\r\n^quote\r\n"
        val analyzed = DocumentAnalyzer.analyze(raw)
        assertTrue(analyzed.headings.any { it.name == "章节" })
        assertEquals(raw.indexOf("> 引用"), analyzed.blocks.single().start)
    }
    @Test fun htmlProjectionRetainsAnchorsAndRendersInlineSyntaxWithoutChangingSource() {
        val source = "# 标题\n\n==**高亮加粗**== [[目标#小节|显示]]\n\n\$x^2\$\n\n[^a]: 脚注\n\n引用[^a] 与行内^[说明]。\n\n![[文件.pdf#page=2]]\n"
        val html = HtmlProjection.render(source)
        assertTrue(html.contains("<mark><strong")); assertTrue(html.contains("data-tex=\"x^2\""))
        assertTrue(html.contains("data-target=\"目标#小节\"")); assertTrue(html.contains("fn-a"))
        assertTrue(html.contains("data-source=\"0\"")); assertTrue(html.contains("class=\"embed\""))
    }
    @Test fun writeSharedBrowserFixtures() {
        val source = """
            ---
            unknown: {keep: true}
            tags: [测试]
            ---
            # 验收标题

            正文 **粗体**、*斜体*、==**高亮**==、~~删除~~、[[目标#章节|内部链接]]。

            - [ ] 未完成
            - [x] 已完成

            > [!note]- 可折叠
            > 外层正文
            > > [!tip] 嵌套提示
            > > 内层正文

            | 名称 | 内容 |
            | -- | -- |
            | 格式 | **单元格粗体** |

            行内公式 ${'$'}e^{2i\pi}=1${'$'}，脚注[^note]，行内^[注解内容]。

            ${'$'}${'$'}
            \begin{pmatrix}a&b\\c&d\end{pmatrix}
            ${'$'}${'$'}

            ```mermaid
            graph TD
              A[开始] --> B[结束]
              class A internal-link;
            ```

            ![[目标#章节]]

            ![[文档.pdf#page=1&height=300]]

            ![[图片.png|100x80]]

            ![[声音.wav]]

            ![[视频.mp4#t=0.2]]

            ![80](https://example.invalid/image.png)

            <iframe src="https://example.invalid/article"></iframe>

            <u>HTML 下划线</u><script>window.noteScriptExecuted=true</script><img src="x" onerror="window.noteScriptExecuted=true">

            %%隐藏注释%%

            [^note]: 脚注内容 **可格式化**。
        """.trimIndent()
        val nested = "# 目标\n\n## 章节\n\n嵌入段落。\n\n![[循环]]\n"
        val data = buildJsonObject { put("root", HtmlProjection.render(source)); put("nested", HtmlProjection.render(nested)); put("nestedHeadingOffset", DocumentAnalyzer.heading(DocumentAnalyzer.analyze(nested), "章节")!!.start); put("source", source) }
        File("build/reader-fixtures.json").apply { parentFile.mkdirs(); writeText(data.toString()) }
    }
}
