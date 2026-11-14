# 架构入口与实施边界

2026-11-09 四期分类整理沿用十二个模块：笔记域提供分类预览／条件提交和内容编辑入口，data:notes 复用 StorageGate、链接维护与恢复。目录表示主归属，YAML 表示 ID、标签和真实内容时间；内部收录／活动时间保持独立。状态库显式 v3→v4，notes 快照 format=4 兼容 1／2／3，新增时间待确认状态和主题顺序随 notes 备份。正文同步仍原样传输，不生成内容时间。见[分类交接](../engineering/note-organization-handoff.md)。

2026-11-09 二期架构确认：沿用 Life 唯一宿主，默认进入 Life，以可拖动悬浮按钮切换 Life／见域，保留各自底栏、状态与外观。见域按需初始化，按 domain／data／feature 整理业务边界，共用渲染不依赖笔记库；见域侧本期不访问 Life 文档。两域备份独立，统一备份与知识库、新增资料联动留[第五期](../plans/phase-5-knowledge-base.md)。当前新增 `core:markdown` 并由 Life 使用，共十三个实际模块；见域生产模块和悬浮切换尚未接入。源码事实见[整合基线](../engineering/phase-2-integration-baseline.md)，当前实现见[共用宿主阶段记录](../engineering/phase-2-shared-markdown-handoff.md)，完整范围见[二期计划](../plans/phase-2-ai-and-shared-capabilities.md)。

2026-11-04 三期同步增加 `domain:sync` 与 `data:sync`，共十二个实际模块；笔记域提供专用同步接口，共享原有存储门锁，feature 仅依赖领域接口。YAML `life_archive_id` 保持跨端身份；同步配置和附件回收站接入可兼容历史缺失域的备份参与者，凭据及基线不导出，恢复后重新配对。原有模块数量描述保留历史含义；当前规则和检查见[三期计划](../plans/phase-3-computer-obsidian.md)及[三期交接](../engineering/phase-3-sync-handoff.md)。

2026-11-01 视频摘记补充沿用十个模块：`feature:notes` 通过 `core:preferences` 保存四类提示词覆盖值，preferences 备份快照升级为 format=2 并兼容历史 format=1；Markdown 正文、notes 快照与外层备份格式不变。交互及边界见[视频摘记计划](../plans/phase-1-video-notes.md)。

2026-10-31 独立笔记封面已接入现有笔记域，当前状态库升级到 v3（显式 v2→v3 迁移），notes 快照 format=3 兼容旧备份，外层备份维持 v1。下文 v2 描述保留其历史版本含义，当前封面数据归属及验证见[封面交接](../engineering/phase-1-note-cover-handoff-20261031.md)。

0.2.2 新增首屏优先调度和 ReaderCacheRepository 派生缓存接口，仍沿用十个模块、状态库 v2 和备份外层 v1。缓存通过领域接口调用，阅读结果不能回写正文；当前入口见[阅读提速交接](../engineering/phase-1-reader-performance-handoff.md)。

状态：2026-10-18 已选择模块化单体架构，接入核心数据底层及一期编辑器交互样板；完整功能和目标机验收尚未完成。完整决策见 [ADR-001](adr-001-android-architecture.md)，底层接口见[架构交接](../engineering/foundation-handoff.md)，当前样板见[编辑器交接](../engineering/editor-prototype-handoff.md)；原始底座交接保留为历史记录。

2026-10-23 已接入一期功能版，新增实际使用的 feature:settings。公共解析/原文定位下沉 core:document，完整阅读由 feature:editor 的受控本地 WebView 适配；data:notes 承担组织、索引、回收站与多文件恢复，远程缓存可丢弃且不进入备份。当前接口、状态库 v2 及旧包迁移见[功能交接](../engineering/phase-1-function-handoff.md)。用户决定功能先行、视觉重做后置；目标机验收仍待完成。

0.2.1 的显示修订、链接语法类型、恢复结果和会话换代协议见[稳定性修复交接](../engineering/phase-1-stability-handoff.md)，未改变模块依赖与权威数据归属。

## 技术和数据约定

- Kotlin + Jetpack Compose 原生 Android；已接入 Material 3、ViewModel/UDF、Navigation 3 与 Hilt，当前启动进入真实笔记库，Debug 提供会话内保存失败模拟。
- 已选 Navigation 3 稳定版、Hilt、Coroutines/StateFlow、Room 和 Preferences DataStore；采用 ViewModel + UDF，Repository 隔离数据，复杂操作按需使用 Use Case。
- 仅针对小米 14 Ultra，约 500 篇笔记；测试数据和性能基线见 ADR-001。
- 当前 namespace / applicationId 为 `com.elio.lifearchive`；项目与显示名为 Life Archive。
- 笔记正文保存为 App 私有目录中的 UTF-8 `.md` 文件，文件夹对应笔记本，附件独立管理；通过系统文件选择器导入导出，并由手机手动发起局域网同步到电脑固定目录。
- 正文始终以 Markdown 文件为准。Room 应用状态库保存内部 ID、路径映射等需备份的信息，独立索引库保存可重建的搜索/引用数据，两者不能混为缓存。
- 文件名、正文标题、YAML 属性相互独立；文件兼容与保存保真遵循[一期计划](../plans/phase-1-local-notes.md)。

## 已选工程组织

一个 APK、多个有真实职责的 Gradle 模块，按业务域和依赖方向组织。完整清单、依赖表及分批建立顺序以 ADR-001 为准。

| 模块组 | 职责 |
| --- | --- |
| `app` | 启动、导航、系统入口和依赖装配 |
| `feature` | 笔记列表、编辑阅读、设置等 UI 与 ViewModel；不依赖 data 实现或其他 feature |
| `domain:notes` | 纯 Kotlin 模型、业务接口与必要 Use Case |
| `data:notes` | 文件、附件、草稿、Room、索引协调与笔记备份参与实现 |
| `domain:sync` / `data:sync` | 纯 Kotlin 同步规则与公开接口 / HTTP、基线、连接配置及备份参与实现；正式代码不依赖笔记 data 实现 |
| `domain:jianyu` / `data:jianyu` / `feature:jianyu` | 纯 Kotlin 见域契约与协调器／独立 Room、网络、音频及平台实现／原见域页面和内部导航；app 装配运行租约，feature 不访问 data |
| `core` | 文档、设计系统、设置、备份等实际使用的基础能力；不依赖具体业务域 |
| `core:markdown` | 受控 WebView、渲染静态资源、修订与消息校验、原文定位；通过回调接收文档与资源，不依赖笔记业务模块 |

2026-11-14 新增 `domain:jianyu`、`data:jianyu`、`feature:jianyu`，当前共十六个实际模块。见域沿用 `com.elio.jianyu` 业务命名空间，应用标识仍为 `com.elio.lifearchive`。app 按需装配见域并持有运行租约，feature 只见公开领域契约；Room 持久化模型留 data。双域入口和状态机制见[交接](../engineering/phase-2-business-host-handoff.md)，完整迁移／独立恢复仍在实现。`core:markdown` 消息样板依赖纯 `core:document` 的投影，不引入笔记域。依赖由 `checkArchitecture` 检查；上方十三模块描述为 11 月 9 日历史状态。

## 本阶段边界

系统还需承接未来的任务、提醒、目标、专注、习惯、阅读、情绪/健康、写作成就和中山公交出行规划等候选功能。用户已明确这些功能暂待确定、不排期，但架构不能把全部业务限制为笔记。

强解耦、可扩展、可维护通过 ADR-001 的依赖表、数据归属和验证要求落实；新增业务通过公开接口关联，不直接操作其他域的内部存储。需求依据见[整体功能版图](../plans/overall-feature-roadmap.md)。当前不提前建立插件平台或未来业务空模块。

隐私功能范围仅保留应用锁/指纹，实施阶段未定；不纳入访客模式、伪装计算器、紧急清除/自毁等扩展，也不将应用锁等同于本地文件加密。中山公交出行规划的独立交付或集成形态仍未确定。

用户本轮明确划分职责：架构实施者交付体系与核心数据底层，后续功能 AI 实现正式页面、编辑渲染和笔记业务扩展。核心底层通过独立测试先行，编辑器和 Markdown 渲染仍须通过真实 Android 交互样板验证；共享能力和电脑协作分别留在第二、三期。

## 已完成决定与剩余门槛

只读本仓库即可按 ADR-001 开始实施；无需重新调查旧项目。旧版实现不决定新架构，回查仅用于用户指定的历史核对或复用评估。

架构、依赖方向、私有笔记库、目标机型与规模已确定。以下事项属于明确的后续验证或产品决策，不代表整体架构仍未选择：

| 待决事项 | 影响与处理时机 |
| --- | --- |
| 依赖版本 | 已集中固定并按实际构建检查兼容性；Navigation 3 1.2.0 与底座冲突，实际锁定组合及验证结果见架构交接 |
| 性能与系统环境 | 按小米 14 Ultra、500 篇及 ADR-001 默认样例实测；记录实际系统与输入法版本 |
| 编辑与阅读实现 | 完整 Obsidian 语法、中文输入、光标/选区和往返保真共同约束依赖选择；依据交互样板确定，不只看库的功能宣传 |
| 首页与导航 | 整体页面组织尚未冻结，需能逐步接入新业务；已有编辑器页面规则继续有效 |
| 生活与重要档案范围 | 用户尚未选择；可作为候选扩展场景分析，不提前实施，不要求为了架构讨论确认全部远期细节 |

见域整合的二期宿主、悬浮切换、状态与独立备份边界已确认；共用 Markdown 的流式样板和历史迁移协议按子任务细化后接入。Life 文档知识库、新增资料联动及统一备份留第五期，仅记录方向。出行交付形态继续后置。电脑同步协议及初步交付以三期交接为准，不再列为待定。它们都不追溯扩大一期范围。

实施交付需报告真实依赖图、测试及设备结果，对照 ADR-001 检查扩展边界，不以“架构强”或层数多代替证据。
