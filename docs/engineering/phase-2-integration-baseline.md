# 二期整合基线

记录日期：2026-11-09。对应[二期计划](../plans/phase-2-ai-and-shared-capabilities.md)。

本文记录整合开始前的源码事实，不代表见域已接入或整合验收通过。未安装、启动、清理或读取手机数据；未修改见域来源仓库。

## 1. 源码与环境

| 项目 | Life Archive | 见域 |
| --- | --- | --- |
| 本地仓库 | `D:/My_Elio/Life-Archive-Android` | `D:/My_Elio/AI-Skill-Roundtable` |
| 分支 | `main` | `main` |
| 基线提交 | `d0e7432076e2475fe94158ddef5ceab570dadfaf` | `2d474464100d70269ef43d838408e7573461e93d` |
| 本轮开始时工作区 | 干净；此前分类整理改动已进入基线 | 干净 |
| 包名 | `com.elio.lifearchive` | `com.elio.jianyu` |
| 版本配置 | 0.4.0 / versionCode 10 | 默认 0.1.0 / versionCode 1，支持构建属性覆盖 |
| 模块 | 十二个实际模块 | 单个 `app`；主源码目录有 320 个 Kotlin 文件 |
| SDK / 工具链配置 | compile / target 36，AGP 8.13.2、Kotlin 2.3.21、Room 2.8.4 | compile / target 35，AGP 8.7.2、Kotlin 2.0.21、Room 2.6.1 |
| 导航 | Navigation 3 1.1.2 | Navigation Compose 2.8.4 |

共同要求：Windows 10 x64、PowerShell 7、JDK 17、minSdk 26。接入时以 Life 的固定工具链为底座，不能合并两套 Gradle 插件或顺手升级依赖；见域依赖适配结果需实际构建验证。上述是配置事实，不宣称二者已经兼容。

## 2. 现有入口与数据边界

- Life：`MainActivity` → `AppViewModel` 初始化笔记恢复 → `ArchiveApp`。后者以 `NotesHome` 为首个 Navigation 3 目标，管理笔记、阅读编辑、分类整理、视频摘记及设置。`FoundationRuntime` 在 `filesDir/library` 装配同一个活动笔记库、门锁、同步与备份。
- 见域：`MainActivity` → 外观设置／主题 → `MainAppContent` → 运行时 Ready 世代内的页面。一级导航是“对话、角色、资料、我的”，保留原底栏；详细会话页不等同一级“对话”首页。
- 见域 `JianyuAppRuntimeProvider.observe()` 当前会同步触发 `ensureReady()`。移入新宿主时不能在 Life 启动主路径上直接调用，应先改为明确的按需初始化边界。
- 见域 ViewModel 和页面使用 `com.elio.jianyu.data` 中的模型、Repository、运行时及部分具体类型；部分底层代码反向引用 UI 设置。不能仅复制文件到 feature 后让其依赖 data，必须先整理纯模型、业务契约、平台适配和装配依赖。
- 见域静态资源包含 `avatars`、`skills`、`skill_knowledge`；音频、用户资料与自定义内容需与数据库标识关系一起迁移，不能只搬静态 assets。

## 3. 渲染与迁移的现有缺口

Life 的 `core:document` 已有纯 Kotlin 文档解析；完整展示由 `feature:editor` 的 `WebReaderRenderer`、本地 reader 资源和 `ReaderServices` 配合实现。后者当前持有笔记、资源缓存、元信息等服务，不能直接给见域引用。共用能力抽取后，这些笔记专属操作仍由笔记侧适配层提供。

见域当前使用 Compose Markdown 组件，消息卡片依赖完整正文高度实现折叠、展开和文本选择。新渲染必须验证流式更新、卡片高度、复制与长列表回收，不以 Life 单篇完整阅读成功推断消息场景通过。

见域 `PortableBackupService` 明确只提供导出，正式导入尚不可用。`RepositoryBackupMapper` 使用白名单，当前会拒绝未归属 issue 的旧会话／消息；导出中有可用音频和部分安全设置，不是整个数据库或所有设置的镜像。不能把它直接视为完整历史迁移方案，也不能绕过拒绝条件静默丢弃资料。下一子任务需核对完整逻辑数据、附件和设置映射，再确定兼容协议与失败恢复。

Life 的独立完整备份外层仍为 v1，notes 快照为 format=4、状态库 v4；本次不改变其范围。见域沿用独立备份边界，整 App 统一备份留第五期。

## 4. 本轮实际检查与后续门槛

- 已核对两仓库根目录、分支、HEAD、工作区、模块配置、版本配置、启动／导航入口、渲染调用和见域导出限制。
- Life 基线 `./gradlew.bat checkArchitecture --console=plain` 通过。未重复运行一期全部检查，也未运行见域测试或设备验收。
- 下一步先完成见域依赖整理和生产模块接入，再做宿主切换；渲染样板与迁移协议分别细化并验证后进入正式路径。
- 后续验收以实际测试结果更新实现交接，不把本基线或计划状态改写为功能完成。
