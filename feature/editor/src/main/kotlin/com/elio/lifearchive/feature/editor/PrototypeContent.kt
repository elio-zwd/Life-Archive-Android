package com.elio.lifearchive.feature.editor

/** 同一份合成文档用于视觉、输入和保真验收，不读取用户真实资料。 */
object PrototypeContent {
    fun document(image: String = "sample-landscape.png"): String = """
        ---
        title: 与文件名独立的属性标题
        tags: [记录, 样板]
        aliases: [山野散步]
        custom_unknown:
          keep: 原样保留
        ---
        # 把日常，慢慢留下

        傍晚沿着山路走了一会儿。风穿过树梢，城市的声音渐渐远了。
        这里可以写下 **值得记住的事**，也可以保留一点 *尚未想清楚的念头*。

        ## 今天的片段

        - 听见风，也听见自己的脚步
        - 带上一杯热茶，坐到天色渐暗
        - [ ] 下次试着走到山顶
        - [x] 留下一张照片

        > 不急着给每一天一个答案，先把它好好记下来。

        > [!note] 给未来的自己
        > 记得留一点空白，让新的想法长出来。

        ![傍晚的山野 · 本地合成示例图片]($image)

        ## 小小的整理

        | 时间 | 记录 |
        | --- | --- |
        | 傍晚 | 山路散步 |
        | 夜里 | 整理今天的片段 |

        ```kotlin
        val memory = "把日常慢慢留下"
        println(memory)
        ```

        ## 保真检查（部分暂以源码展示）

        [[另一篇笔记|显示别名]]，[[#今天的片段]]，[[另一篇笔记#^block-id]]。

        ![[示例附件.pdf#page=2]]

        行内公式 ${'$'}E=mc^2${'$'}，==高亮==，~~删除线~~，%%不丢失的注释%%。

        ${'$'}${'$'}
        a^2+b^2=c^2
        ${'$'}${'$'}

        ```mermaid
        flowchart LR
          记录 --> 回看
        ```

        这是一条脚注[^1]，也是一次原文保留检查。 ^block-id

        [^1]: 脚注内容与未知语法不会因切换模式而删除。

        <details><summary>HTML 样例</summary>保留原文</details>
    """.trimIndent() + "\n"
}
