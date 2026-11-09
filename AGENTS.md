# Life Archive 协作指南

适用于本仓库。先按任务读取相关入口，不默认通读所有计划或历史资料。

## 1. 项目与环境

- 使用简体中文沟通、编写文档和必要注释；注释解释约束与原因。
- 面向 Windows 10 x64，Windows 命令使用 `pwsh.exe`（PowerShell 7）。
- 当前一期开发及验证已按用户确认完成，进入使用反馈与维护阶段；HTML 编辑与阅读设计已认可，首页已按最新确认更新，其他临时页面保留。阶段结论与交付证据见 [一期完成度核对与接手清单](docs/engineering/phase-1-completion-audit-20261031.md)和 [UI 优化计划](docs/plans/phase-1-ui-polish.md)。默认源码编辑与完整阅读，源码/实时预览均保留高亮，实时预览仍为实验项；阅读首屏优先并使用可丢弃的按需缓存。用户确认完成的项目不因历史未验证表要求全部重测；模拟器与真实手机的实测结果分别记录，后续按实际问题定向验证。输入约束见 [编辑器输入修复交接](docs/engineering/phase-1-editor-input-handoff.md)，工具用法见 [工具栏与回车续写交接](docs/engineering/phase-1-toolbar-handoff.md)，阅读机制见 [阅读提速交接](docs/engineering/phase-1-reader-performance-handoff.md)，完整范围及底层从 [一期功能交接](docs/engineering/phase-1-function-handoff.md)按需读取。
- 产品只做 Android，技术方向为 Kotlin + Jetpack Compose。应用标识为 `com.elio.lifearchive`，显示名为 Life Archive。
- 一期本地笔记已完成；第三期同步及第四期分类整理已初步交付。[二期](docs/plans/phase-2-ai-and-shared-capabilities.md)沿用 Life 唯一宿主，默认进入 Life，以可拖动悬浮按钮切换 Life／见域、保留各自底栏与状态；见域按需初始化，共用 Markdown 能力，本期不访问 Life 文档。两域保持独立数据、外观和备份，整 App 统一备份留[第五期](docs/plans/phase-5-knowledge-base.md)，与知识库、AI 全库搜索及新增资料联动一并只记录方向。二期已开始基线与接入工作，不代表整合完成；具体范围及状态见计划。第四期采用主题／类型目录、层级标签和可靠内容时间；PC 阅读显示与修改差异继续只记录方向。具体范围见 [文档索引](docs/README.md)。
- 整体架构讨论必须读取[功能版图](docs/plans/overall-feature-roadmap.md)和[架构要求](docs/architecture/README.md)，再按需阅读对应阶段计划；新项目需求以本仓库记录为依据，不默认回查旧仓库或继承旧版功能。

## 2. 开始与编辑

- 先执行 `git rev-parse --show-toplevel`、`git branch --show-current`、`git status --short`；读取目标目录的 `AGENTS.md`。
- 优先用 `rg` / `rg --files` 定位文件，按任务阅读已有实现、配置与测试。
- 保持当前分支，保护已有未提交改动；不自动切分支、创建 worktree 或 PR。提交、推送按用户授权执行。
- Git 改动按功能或类型分批提交，保留实现与对应测试的关联，不把无关类别混成一个提交；本规则适用于本项目所有对话。同一工作区只由一个对话操作暂存区与提交，推送仍须用户授权。
- 只修改任务必需内容，不顺手升级依赖、全仓格式化或复制其他项目的业务实现。
- 手动编辑优先使用 `apply_patch`；不修改依赖、缓存或构建产物，不提交本地配置、密钥和签名材料。
- 当前工具初始化不意味着获准修改见域仓库。跨项目修改须在用户任务范围内。

## 3. 产品与架构边界

- Markdown 文件是正文事实来源，Room 不维护另一套独立正文；未知 YAML 属性及文档原文应保留。
- 参与同步的笔记使用 YAML `life_archive_id` UUID 保持跨端身份，改名／移动不改 ID；仅定点补字段，不重排其他 YAML。同步通过笔记域公开接口和共享 StorageGate；设备凭据不进入 Git、正文或备份，恢复后重新配对并核对资料。规则见[第三期计划](docs/plans/phase-3-computer-obsidian.md)。
- 独立笔记封面属于应用状态，自定义图片沿用附件公开接口，不写入 Markdown／YAML。首页左侧与阅读横幅共用封面，见[封面交接](docs/engineering/phase-1-note-cover-handoff-20261031.md)。四期状态库 v4、notes 快照 format=4 保留封面、时间待确认状态和主题顺序，兼容旧备份。
- 分类以“主题/类型”目录为主归属，三级标签用于跨主题查找；历史笔记先预览再整理。`life_archive_created_at` 与 `life_archive_updated_at` 只记录可靠的内容时间，不能用导入、同步或文件系统时间冒充；维护标签、路径、ID 和链接不刷新内容时间。规则及接入见[分类整理制度](docs/plans/phase-4-note-organization.md)。
- 文件名、正文标题和 YAML `title` 独立，不隐式同步。文件命名与链接维护按已确认计划实施。
- 已选模块化单体架构，工程任务必须阅读 [ADR-001](docs/architecture/adr-001-android-architecture.md)。一个 APK，按真实职责建立 Gradle 模块；feature 不依赖 data 实现或其他 feature，纯 Kotlin domain 不依赖 Android。此前单 app 模块参考已被本决策替代。
- 已接入 Compose + Material 3、ViewModel + UDF、Navigation 3、Hilt/KSP、Room 与 DataStore。版本集中固定到 `gradle/libs.versions.toml`；具体编辑/渲染库仍遵循样板门槛，不把未验证方案写成已兼容。
- 一个活动库只装配一个存储运行实例，共享 `StorageGate`；先装配备份恢复协调器，再公开 Repository。业务不得直接读写私有文件、DAO 或备份参与者；状态库禁止破坏性重建，索引可重建。
- 新模块必须更新并通过根工程 `checkArchitecture`；新增权威数据必须同时实现备份能力，并由 app 显式装配。笔记域沿用备份参与者；二期见域保持独立备份，不接入 Life 备份包，统一整 App 备份留第五期。底层可以独立测试先行，正式编辑器仍须通过交互样板与真机门槛。
- 用户选择 App 管理私有笔记库，外部通过导入导出和后期同步交换；目标仅小米 14 Ultra，约 500 篇笔记。索引可重建不等于应用状态可丢弃；文件可靠性和备份按 ADR-001 执行。
- 业务域明确数据所有权和依赖方向，通过公开能力接口协作，避免循环依赖及跨域直接操作内部存储；系统能力和外部服务与业务规则分离，支持独立验证。
- 待确定、不排期的功能用于检验架构扩展能力，不构成实现授权；不提前建立空模块、无调用方的抽象或庞大插件体系。具体候选及隐私范围以功能版图为准，不在本文件复制功能清单。
- 计划、当前实现和验收记录分开表达。用户最新明确决定优先；文档冲突时指出冲突，不擅自扩大一期范围。
- 变更需求时更新对应计划；变更长期架构约束时同步架构说明及本文件相关规则；项目定位、可运行状态或入口变化时同步 README 和文档索引。不要把逐次讨论与进度日志堆入 AGENTS.md。

## 4. 工具与设备

- 工具来源与使用约定以 [tools/UPSTREAM.md](tools/UPSTREAM.md) 为准。通用工具可直接复用，无需先修改代码；启动 Life Archive 时显式传入 `--package com.elio.lifearchive`，或使用已核对的仓库外 Profile，不直接套用见域默认包名或示例设备参数。
- 本次保留的目录级工具规则继续适用；其中上游 PR、CI 或见域标识不代表本项目已有对应设施。
- 手机控制需在任务授权范围内，先核对唯一设备与目标包名；不因文档示例自动执行手机操作。
- 清库、卸载、重装等可能影响数据的行为需要明确授权。截图、UI XML 和完整日志保存在仓库外。
- ADB 返回成功不代表交互验收通过；未经状态验证不能把 `NOT_VERIFIED` 报成 `PASS`。

## 5. 验证与交付

- 纯文档：核对路径、链接、UTF-8 编码和仓库事实，执行 `git diff --check`；有暂存改动时追加 `git diff --cached --check`。
- 工具复制或变更：执行相关帮助命令和已有无设备测试，具体入口见 [验证说明](docs/testing/README.md)。不为检查自动安装环境或连接手机。
- Android 构建入口为 `./gradlew.bat`，使用 JDK 17；架构检查、底层单测、Robolectric、构建与 Lint 命令见 [验证说明](docs/testing/README.md)。尚无 CI；无源码的单测任务 `NO-SOURCE` 不算单测通过。设备操作另需授权。
- 完成前回读差异，报告实际通过、失败和未验证范围。编译、工具测试、设备行为和视觉验收分别报告，不相互替代。
