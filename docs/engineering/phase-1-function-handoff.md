# 一期功能版交接

日期：2026-10-23。版本：0.2.0（versionCode 2）。状态：一期功能及必要操作入口已接入，整体视觉重做后置；目标机输入、系统交互、性能及最终视觉尚未验收。

本文件保留 0.2.0 的功能清单与历史验证数字。后续 0.2.1 的高亮、阅读首屏、查询、链接、恢复及缓存并发修复、最新 APK 和验收记录以[稳定性修复交接](phase-1-stability-handoff.md)为准。

## 1. 当前入口与边界

列表提供命名创建、快速记录、搜索、笔记本/标签筛选、时间排序、置顶、重命名/移动和回收站。点击笔记进入阅读；点击编辑默认源码模式，保留实验性实时预览。列表“创建验收样例”是明确操作，不自动注入资料。

笔记“管理”或编辑页顶部文件名提供属性标签、重命名/移动、删除和单篇导出。属性标签操作只修改 YAML/JSON frontmatter 的 tags；正文 #标签参与识别和筛选，通过源码修改。笔记本筛选后可移动或删除整个笔记本；删除的笔记可在回收站逐篇恢复，共享附件保留。永久删除要求确认。

“设置”提供主题、字号、远程加载策略、缓存清理、Markdown/目录导入、便携 ZIP 导出及完整备份恢复。命名失败保留名称；导入先暂存预览，确认后落库；恢复先验证包，再确认替换。

当前十个实际模块：app、core:document、core:designsystem、core:backup、core:preferences、domain:notes、data:notes、feature:notes、feature:editor、feature:settings。没有 AI、电脑同步或远期占位模块。保留现有 Android 工具链，不自动操作手机。

## 2. 数据、接口及恢复

- Markdown/附件文件仍是正文和附件的事实来源。公共解析和 HTML 投影位于纯 Kotlin core:document；ReaderRenderer/EditorEngine 仍隔离具体控件。投影不会写回正文。
- NotesRepository 保留版本保存、草稿修订和阅读偏移语义。LibraryRepository 提供组织查询、链接定位、带版本的移动/删除、回收站、附件流及导入导出。RemoteCacheRepository 单独管理可丢弃缓存。
- 状态库 v1→v2 显式迁移，新增创建/修改/删除时间和空笔记本记录；不启用破坏性状态迁移。旧记录以现有文件时间建立排序基线，不伪造原始历史创建时间。
- 搜索读取派生索引，不按每次输入扫描正文文件。中英关键词按空白拆分并共同匹配，支持中文连续词；默认置顶优先、最近修改在前。提供索引进度、失败提示及重建。
- 移动/重命名按实际解析目标改写 WikiLink、Markdown 及可识别的 Mermaid 内部节点引用，保留别名和标题/块定位。代码、注释和公式内相似文字不作为普通文件链接替换。歧义目标和受影响的未解决草稿阻止操作。
- 多文件操作在共享 StorageGate 下保留逻辑旧快照及持久提交标记。提交标记前中断回滚旧状态，标记后继续清理；不把部分修改报告为成功。大附件按流处理，代价是批量操作需要临时磁盘空间。
- 回收站保留正文、ID 和草稿。恢复路径冲突不覆盖现有资料，可选新路径；永久删除清除该正文、草稿和派生索引，不自动删除共享附件。
- 便携导出是普通 Markdown/附件目录 ZIP；单篇 ZIP 包含可解析的关联笔记和附件。单篇 Markdown 导出已保存原文。远程 URL 保留原样，缓存不混入正文。
- 完整备份继续使用外层格式 v1、notes/preferences 两个参与者；notes 逻辑状态格式升级为 v2，读取兼容 v1。包含空笔记本、置顶、阅读位置、时间、回收站、草稿及设置；索引和远程缓存不入包。新版可恢复旧包，旧版不承诺读取新版 notes 状态。

## 3. 阅读兼容矩阵

本轮以一期计划列出的内置文档格式为边界，不复刻 Obsidian 全部应用或插件执行环境。下面区分代码接入、电脑样例验证和目标机验收；不能由库的支持声明推导所有文件均已验收。

| 能力 | 已接入及电脑验证 | 小米 14 Ultra |
| --- | --- | --- |
| 标题、段落、强调、删除线、列表、引用、代码 | 公共 CommonMark 投影；源码/实时预览保真回归 | NOT_VERIFIED |
| 表格及单元格格式、高亮 | HTML 展示、横向滚动；浏览器样例断言 | NOT_VERIFIED |
| 任务清单 | 阅读显示状态，源码/工具栏编辑；不引入独立任务系统 | NOT_VERIFIED |
| 属性、标签、别名 | 原文折叠、识别和搜索；标准列表/行内列表/JSON 属性测试，未知字段保留 | NOT_VERIFIED |
| WikiLink、Markdown 链接、显示别名 | 内部解析、跨笔记/同笔记跳转；外链系统打开 | NOT_VERIFIED |
| 标题、子标题链及块引用 | 原文位置定位、章节/块嵌入；嵌套标题和格式标题测试 | NOT_VERIFIED |
| 笔记嵌入 | 同一解析投影、源文件更新后重读，循环及过深嵌入明确反馈 | NOT_VERIFIED |
| Callout、折叠及嵌套 | 本地阅读转换；浅深色浏览器样例 | NOT_VERIFIED |
| 脚注、行内脚注、注释 | 脚注及内容展示，注释隐藏；正文不改写 | NOT_VERIFIED |
| 行内/块级公式 | 本地 MathJax，含矩阵样例；不是源码占位 | NOT_VERIFIED |
| Mermaid | 本地完整运行资源、内部节点链接、错误反馈；流程图浏览器样例 | NOT_VERIFIED |
| HTML | 清理后展示，下划线/普通块等；笔记内脚本及事件不执行 | NOT_VERIFIED |
| 图片及尺寸 | PNG 样例验证；其他列入范围的格式交给 WebView 解码，仍需目标机逐项验证 | NOT_VERIFIED |
| 音视频 | 本地资源流、控件及时间片段；合成 WAV/MP4 浏览器解码和定位 | NOT_VERIFIED |
| PDF | 本地 PDF.js、页码/高度参数、翻页/缩放、范围读取；50 MiB 级合成 PDF | NOT_VERIFIED |
| 远程图片/网页 | 默认不请求；显式加载或自动缓存；网页提取正文与图片 | NOT_VERIFIED |

数学/图表/字体/PDF worker 随 APK 本地打包，不运行时访问 CDN。Reader WebView 仅通过受控本地地址取得 Repository 投影及资源，关闭文件/Content URI 直接访问，没有原生 JavaScript 文件桥接。第三方在线网页使用另一个无资料桥接的 WebView。

本地阅读资源固定版本和许可证见 reader/package-lock.json 及打包资源 licenses。MathJax 4.1.3、Mermaid 12.2.0、PDF.js 6.4.299、DOMPurify 3.4.17；CommonMark 0.30.1、jsoup 1.18.3、AndroidX WebKit 1.14.0。目标机实际 WebView 版本和媒体编码支持仍需记录。Canvas/Bases 等作为文件保存和交换，不提供其白板/数据库编辑器或插件执行能力。

## 4. 远程缓存约定

默认手动加载；设置“自动缓存”后，仅处理笔记实际引用的嵌入资源。缓存命中优先离线读取，刷新由用户触发。最多两个下载并发，已完成缓存总容量 512 MiB，按最近使用清理。

网页副本提取公开 HTML 的可阅读正文和正文图片，显示来源及时间；不自动创建 Markdown，不修改笔记的原 URL。需要登录、仅靠网页脚本生成正文或无法提取正文时明确失败，部分图片失败标记“部分缓存”。不承诺镜像整个交互网站。网页离线副本与资源都可被清理，不是长期归档，不进入完整备份；失败刷新保留旧副本，清理后进行中的旧下载不能重新落回缓存。

## 5. 电脑检查与交付

```powershell
.\gradlew.bat verifyPhaseOne --console=plain
```

使用 Windows 10 x64、PowerShell 7、JDK 17、SDK 36、现有 Node.js 24 和 Chrome/Edge；不连接设备。首次构建从锁文件安装本地阅读依赖，生成资源位于忽略的 build 目录，不手改 node_modules。

最终冻结检查：**verifyPhaseOne PASS，1 分 45 秒，540 个任务中 41 个执行、499 个 up-to-date**。Gradle 任务数不等于测试数；未变化模块复用有效结果。本地测试 **74 项、0 失败、0 错误**：core:document 11、core:backup 6、core:preferences 1、data:notes 26、feature:editor 16、feature:notes 1、feature:settings 2、app 11。另有 **10 项浅深色浏览器检查通过**。

七个 Android 模块 Debug Lint 均 0 错误；app 有 33 条警告（28 条版本/target 提示、1 条必要的受控 WebView JavaScript 提示、4 条 Bitmap/URI KTX 写法建议），feature:editor 有 1 条 JavaScript 提示，其余五个模块 0 警告。没有加入 baseline 或关闭检查。Debug/仪器测试 APK 构建、Release Kotlin 编译通过；仪器测试未上设备执行。

迁移、中断回滚、草稿保护、回收站、导入取消、旧包恢复、缓存清理/刷新失败均有自动化覆盖；另测错误类型响应的网络流关闭及失败导入后清除进度状态。500 篇库执行 20 次电脑热搜索，记录 p95 81 ms，仅为本机样例；200 MiB（100 个附件）与 50 MiB 附件完成流式备份恢复，浏览器另外验证 50 MiB 级有效 PDF 翻页和合成 WAV/MP4。Android 系统 WebView、文件选择器、真实输入法和目标机性能不由上述结果代替。

APK 字节数 53,937,945；SHA-256：`70c07b89ffcdbeadc3145689342897b0a0765732884c43741098123b12556b53`。

交付目录：仓库同级 Life-Archive-Deliveries/phase-1-functions-20261023。交付 APK、最终日志、阅读预览、兼容清单和验收表。

验证对应底层实现提交 `a57661f`、应用功能提交 `11e2f16` 和测试提交 `e4fa97b`。已按用户后续授权分类提交，保持 main，不推送；未连接或操作手机。Release 仅编译，不作签名交付。

## 6. 手机简洁验收表

用户手动保留数据覆盖安装，不卸载、不清库。记录 HyperOS/Android、输入法、Android System WebView 版本。先用合成资料，不用唯一真实原件做故障验收。

| 操作与预期 | 结果 |
| --- | --- |
| 旧资料仍在；新建取消无文件，失败保留名称；新建默认源码、已有笔记默认阅读 | NOT_VERIFIED |
| 中文组合输入、选区、粘贴、撤销、键盘遮挡、返回/重开/旋转不丢已落盘内容 | NOT_VERIFIED |
| 搜索、笔记本/标签筛选、排序、置顶和快速记录可用 | NOT_VERIFIED |
| 重命名/移动后链接及附件仍可用；失败/草稿冲突不误报完成 | NOT_VERIFIED |
| 回收站恢复冲突可换路径；永久删除确认后生效，共享附件仍可读 | NOT_VERIFIED |
| 公式、Mermaid、表格、嵌套 Callout、脚注、章节/块嵌入显示并可跳转 | NOT_VERIFIED |
| 图片尺寸、音视频播放/拖动、PDF 翻页/缩放及系统打开附件可用 | NOT_VERIFIED |
| Markdown/目录导入取消不改库；便携 ZIP 在电脑 Obsidian 核对原文和附件关系 | NOT_VERIFIED |
| 完整备份实际恢复出 ID/笔记/草稿/回收站/空笔记本/设置，重启后可读 | NOT_VERIFIED |
| 手动/自动缓存、断网重读网页、刷新失败保留旧副本、清缓存不改笔记 | NOT_VERIFIED |

目标机性能仍按 ADR-001 采样至少 20 次；500 篇、50 KiB/1 MiB 文本、约 200 MiB 附件和单个 50 MiB PDF 分别验证。当前页面是功能入口，统一视觉重做和最终视觉验收另行推进。
