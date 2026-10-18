# Life Archive 协作指南

适用于本仓库。先按任务读取相关入口，不默认通读所有计划或历史资料。

## 1. 项目与环境

- 使用简体中文沟通、编写文档和必要注释；注释解释约束与原因。
- 面向 Windows 10 x64，Windows 命令使用 `pwsh.exe`（PowerShell 7）。
- 当前已接入模块化架构与核心数据底层，正式笔记功能层尚未实现；不能把底层测试等同产品验收。后续开发入口见 [架构交接](docs/engineering/foundation-handoff.md)，原始底座记录见 [底座交接](docs/engineering/bootstrap-handoff.md)。
- 产品只做 Android，技术方向为 Kotlin + Jetpack Compose。应用标识为 `com.elio.lifearchive`，显示名为 Life Archive。
- 第一阶段聚焦本地笔记；第二阶段才讨论 AI 与见域共用能力，第三阶段讨论电脑协作。具体范围见 [文档索引](docs/README.md)。
- 整体架构讨论必须读取[功能版图](docs/plans/overall-feature-roadmap.md)和[架构要求](docs/architecture/README.md)，再按需阅读对应阶段计划；新项目需求以本仓库记录为依据，不默认回查旧仓库或继承旧版功能。

## 2. 开始与编辑

- 先执行 `git rev-parse --show-toplevel`、`git branch --show-current`、`git status --short`；读取目标目录的 `AGENTS.md`。
- 优先用 `rg` / `rg --files` 定位文件，按任务阅读已有实现、配置与测试。
- 保持当前分支，保护已有未提交改动；不自动切分支、创建 worktree 或 PR。提交、推送按用户授权执行。
- 只修改任务必需内容，不顺手升级依赖、全仓格式化或复制其他项目的业务实现。
- 手动编辑优先使用 `apply_patch`；不修改依赖、缓存或构建产物，不提交本地配置、密钥和签名材料。
- 当前工具初始化不意味着获准修改见域仓库。跨项目修改须在用户任务范围内。

## 3. 产品与架构边界

- Markdown 文件是正文事实来源，Room 不维护另一套独立正文；未知 YAML 属性及文档原文应保留。
- 文件名、正文标题和 YAML `title` 独立，不隐式同步。文件命名与链接维护按已确认计划实施。
- 已选模块化单体架构，工程任务必须阅读 [ADR-001](docs/architecture/adr-001-android-architecture.md)。一个 APK，按真实职责建立 Gradle 模块；feature 不依赖 data 实现或其他 feature，纯 Kotlin domain 不依赖 Android。此前单 app 模块参考已被本决策替代。
- 已接入 Compose + Material 3、ViewModel + UDF、Navigation 3、Hilt/KSP、Room 与 DataStore。版本集中固定到 `gradle/libs.versions.toml`；具体编辑/渲染库仍遵循样板门槛，不把未验证方案写成已兼容。
- 一个活动库只装配一个存储运行实例，共享 `StorageGate`；先装配备份恢复协调器，再公开 Repository。业务不得直接读写私有文件、DAO 或备份参与者；状态库禁止破坏性重建，索引可重建。
- 新模块必须更新并通过根工程 `checkArchitecture`；新增权威数据必须同时实现备份参与者，并由 app 显式装配。底层可以独立测试先行，正式编辑器仍须通过交互样板与真机门槛。
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
