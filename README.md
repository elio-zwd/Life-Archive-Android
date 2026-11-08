# Life Archive

**第四期分类整理已初步交付：** Android 0.4.0 / PC 源码 1.2.0 增加主题优先归档、层级标签、预览确认整理与真实内容时间；统一检查及 314 项 Android、179 项 PC 检查通过，见[制度](docs/plans/phase-4-note-organization.md)和[交接](docs/engineering/note-organization-handoff.md)。旧笔记不自动搬动，手机实测另列；按用户要求不构建 PC 安装包。

**三期已达到初步交付：** Android 0.3.0 与 PC 1.1.0 的局域网手动双向同步、笔记身份与改名移动、AI 修改前备份已完成电脑检查；产物、校验值及使用方式见[三期交接](docs/engineering/phase-3-sync-handoff.md)，真机验收待用户进行。

面向个人使用的 Android 应用，第一期以本地 Markdown 笔记起步，按 Obsidian 官方内置文档语法规划文件兼容能力。整体功能范围仍在确认，架构须能承接后续选中的任务、生活管理、AI 和出行等能力。

**当前状态：一期开发及验证完成（2026-11-01 用户确认），进入使用反馈与维护阶段。** HTML 编辑与阅读设计已认可，首页已按最新确认更新，其他临时页面保留；阶段结论和历史证据见[一期完成度核对与接手清单](docs/engineering/phase-1-completion-audit-20261031.md)，实施范围及当前 APK 见[UI 优化计划](docs/plans/phase-1-ui-polish.md)。历史未填验收表不再作为一期未完成待办。现有功能包含组织搜索、源码编辑与本地完整阅读、附件、回收站、导入导出、备份恢复及可清理缓存。首页滚动、长标签／笔记本选择与刷新优化见[列表性能修复交接](docs/engineering/phase-1-list-performance-handoff.md)。列表与引用混合回车、正文快照与高亮调度见[输入修复交接](docs/engineering/phase-1-editor-input-handoff.md)；标题手势、格式／插入面板与续写用法见[工具栏交接](docs/engineering/phase-1-toolbar-handoff.md)，首屏优先与按需缓存见[阅读提速交接](docs/engineering/phase-1-reader-performance-handoff.md)，完整支持清单和迁移见[一期功能交接](docs/engineering/phase-1-function-handoff.md)。设计认可、电脑验证和设备实测分别记录。

## 项目标识与方向

| 项目 | 约定 |
| --- | --- |
| 项目名 / 显示名 | Life Archive |
| namespace / applicationId | `com.elio.lifearchive` |
| 个人域名 | `my-elio.online`；本阶段不建设网站或服务端 |
| 平台 | 仅 Android |
| 已选架构 | 模块化单体；Kotlin、Compose、ViewModel + UDF、Navigation 3、Hilt |
| 数据方向 | UTF-8 Markdown 文件保存正文，Room 用于索引、缓存与应用状态 |
| 开发环境 | Windows 10 x64，PowerShell 7（`pwsh.exe`） |
| 使用基线 | 小米 14 Ultra，约 500 篇笔记；App 管理私有笔记库 |

## 阶段计划

整体产品范围还在梳理，参见[整体功能版图与旧版迁移评估](docs/plans/overall-feature-roadmap.md)。其中后期候选等待确认，不自动扩充以下阶段。

1. [第一期：本地笔记](docs/plans/phase-1-local-notes.md)：编辑、组织、搜索、附件、导入导出和简单备份恢复。交互与视觉以[已确认的决策文档](docs/plans/phase-1-editor-visual-decisions.md)为准。
2. [第二期：单 App 整合与共用 Markdown](docs/plans/phase-2-ai-and-shared-capabilities.md)：一个 App 内提供“Life 笔记”“见域”两个入口，见域现有功能和交互大部分保持不变，Markdown 处理与展示接入 Life 共用接口；见域侧本期不查看、检索、调用或修改 Life 文档。范围已确认，首页样式和技术设计待对齐，尚未实施，暂不另定实施顺序。
3. [第三期：简化双向同步](docs/plans/phase-3-computer-obsidian.md)：先实施局域网手动双向同步、YAML ID、改名移动及 AI 修改前备份；实现、检查和产物见[三期交接](docs/engineering/phase-3-sync-handoff.md)，随后由用户[真机验证](docs/testing/phase-3-manual-acceptance.md)。
4. 第四期优化：[电脑端阅读显示与修改差异](docs/plans/phase-4-pc-reading.md)继续只记录方向；[笔记分类与整理制度](docs/plans/phase-4-note-organization.md)独立实施，按主题／类型归档，支持三级标签、批量预览整理和真实内容时间，不新增 AI 分类或强制 AI 标记。笔记同步身份机制仍归第三期。
5. [第五期：知识库与资料联动](docs/plans/phase-5-knowledge-base.md)：Life 文档作为见域知识库，承接原二期的 AI 全库搜索、自动调用资料及笔记与对话之间的其他新增联动；仅记录方向，本次不展开设计。

目标范围、实现与验收分别记录。第一期不接入 AI、不抽取两款 App 的共享模块；旧数据库不迁移。Android App 内版本对比、关系图和手写暂不计划实现。

## 仓库入口

| 入口 | 用途 |
| --- | --- |
| [AGENTS.md](AGENTS.md) | AI 与开发者协作规则 |
| [docs/README.md](docs/README.md) | 文档索引与状态区分 |
| [docs/architecture/](docs/architecture/README.md) | 数据约定与未来代码组织 |
| [docs/design/](docs/design/README.md) | 设计依据和视觉资料归档 |
| [docs/testing/](docs/testing/README.md) | 当前可执行检查与验证边界 |
| [底座历史交接](docs/engineering/bootstrap-handoff.md) | 初始工具链、两个模块阶段的验证和接手边界 |
| [架构交接](docs/engineering/foundation-handoff.md) | 核心接口、数据可靠性、模块规则、功能开发接入与验证结果 |
| [编辑器样板交接](docs/engineering/editor-prototype-handoff.md) | 首轮页面、保存会话、兼容状态、APK 与手机手动验收步骤 |
| [一期功能交接](docs/engineering/phase-1-function-handoff.md) | 当前功能、迁移、阅读与缓存边界、验证及验收表 |
| [稳定性修复交接](docs/engineering/phase-1-stability-handoff.md) | 0.2.1 打开/输入闪烁、数据安全修复及历史验收记录 |
| [阅读提速交接](docs/engineering/phase-1-reader-performance-handoff.md) | 0.2.2 首屏优先、32/256 MiB 阅读缓存、性能与验收 |
| [工具栏交接](docs/engineering/phase-1-toolbar-handoff.md) | 0.2.3 标题手势、格式与插入、回车续写和手机验收 |
| [tools/](tools/README.md) | 手机控制、日志证据辅助工具 |
| [tools/UPSTREAM.md](tools/UPSTREAM.md) | 工具来源、复制清单及使用配置约定 |

## 当前使用方式

粘贴预览页及笔记阅读／编辑的更多菜单新增“更新链接”：复制小红书、抖音等平台分享文字，提取真实网址，确认后更新 YAML source；预览保存后生效，已有笔记提交成功后反馈，正文和其他属性保留。见[链接更新交接](docs/engineering/phase-1-update-video-link-20261101.md)。

整个 App 共用一个“主题”：A 浅灰蓝、B 暖纸色、C 深石墨。可从首页更多菜单、阅读／编辑更多菜单或设置页选择，首页与各页面同步生效；不再分别选择首页浅深色与阅读风格，已有阅读主题和备份兼容保留。

2026-11-01 启动图标已替换为确认的紫蓝书页／档案图形，接入普通／圆形自适应与单色主题版本；[图标设计和接入记录](docs/design/brand/life-archive-app-icon-v1.md)包含新 APK 与验证边界。

首页新增“粘贴”：主动读取剪贴板，自动识别文件名、YAML 和标签，预览确认后保存并打开阅读；普通文本也可保存，同名自动添加后缀。原“更多 → 快速记录／视频摘记”保留，视频收录复用粘贴预览并可补来源。四类默认提示词包含 YAML、2～4 个大类标签及关键词／高亮阅读规则，可复制、修改保存、恢复默认；已有自定义提示词保留并进入完整备份。总结由用户在小红书点点中完成，App 不接入 AI。范围见[视频摘记计划](docs/plans/phase-1-video-notes.md)，检查及设备边界见[粘贴建笔记交接](docs/engineering/phase-1-paste-notes-handoff-20261101.md)。

2026-10-31 阅读重开及核心流程兼容／恢复修复，问题表、电脑验证和修复 APK 见[核心流程修复交接](docs/engineering/phase-1-core-resilience-handoff-20261031.md)；该包的设备结果需定向复验。

首页、阅读与编辑已按后续要求压缩上下间距，最新三页预览、前后对比及 APK 见[紧凑布局交接](docs/engineering/phase-1-compact-layout-handoff-20261031.md)；原封面交付目录保留为历史证据。

2026-10-31 新增首页左侧封面、预设／自定义设置及阅读横幅；新旧笔记默认山野插画，完整备份保留选择，Markdown 原文不变。实现、两篇真实入库的隔离示例与验证边界见[封面交接](docs/engineering/phase-1-note-cover-handoff-20261031.md)。

架构依据为 [ADR-001：Android 模块化单体架构](docs/architecture/adr-001-android-architecture.md)。当前有十二个实际模块，含三期同步域；四期分类沿用既有模块，职责和依赖见架构说明。Debug 与 Release 均接入真实笔记库，Debug 额外提供会话内正文保存失败模拟。默认源码编辑，实时预览仍为实验项；输入、阅读兼容和视觉分别验收。

进行整体架构选型时，先读 [AGENTS.md](AGENTS.md)，再读[整体功能版图](docs/plans/overall-feature-roadmap.md)、[一期范围](docs/plans/phase-1-local-notes.md)、[编辑器决策](docs/plans/phase-1-editor-visual-decisions.md)、第二/三期方向，最后核对[架构约束与待决事项](docs/architecture/README.md)。这些本仓库文档已足够开展架构讨论，不需要先查看旧项目。

强解耦、可扩展、可维护是选型要求；待确定功能不等于获准实施。旧项目链接只用于历史溯源，旧版功能状态不作为新项目的能力或验收证明。

先阅读任务对应的计划与说明。工具的帮助和无设备测试可按[验证说明](docs/testing/README.md)运行，无需安装 Android 应用。

本机需要 JDK 17、Android SDK 36、Node.js 24/npm，浏览器测试使用 Windows Chrome/Edge。设置 `JAVA_HOME`，通过 `ANDROID_HOME` 或被忽略的 `local.properties` 指定 SDK。仓库根目录使用 PowerShell 7：

快速构建并导出 APK 到系统“图片”目录，或检查最新产物后覆盖安装到唯一在线设备：

```powershell
pwsh.exe -NoProfile -File .\build.ps1 -NoPause
pwsh.exe -NoProfile -File .\build.ps1 -Install -NoPause
```

独立的手机安装入口为 `pwsh.exe -NoProfile -File .\install.ps1`，等效于构建脚本的 `-Install`，支持 `-DeviceSerial`、`-AdbPath`、`-JavaHome`、`-OutputDir`、`-Release`、`-NoPause` 和 `-WhatIf`；`-Help` 查看帮助。

脚本每次通过 Gradle 的增量检查判断产物是否对应当前源码，已有 APK 最新时直接复用；不单凭版本号、文件时间或 Git 提交判断。多台在线设备用 `-DeviceSerial YOUR_SERIAL` 指定，安装使用 `adb install -r` 保留数据，并核对设备上的包名及版本，不自动启动应用。

默认不改版本号。使用 `-EditVersion` 交互修改，或传入 `-VersionName 0.2.7 -VersionCode 9`；仅改变显示版本时，内部版本默认加一。`-WhatIf` 预演，不改源码、不构建、不操作设备。其他参数：`-OutputDir`、`-JavaHome`、`-Release`、`-AdbPath`；完整帮助为 `pwsh.exe -NoProfile -File .\build.ps1 -Help`。未签名 Release 仅导出并标注 `unsigned`，不能安装。版本修改在构建失败后仍保留，方便修复后重试。

完整一期验证仍使用底层 Gradle 入口：

```powershell
.\gradlew.bat verifyPhaseOne --console=plain
```

Debug APK 位于 `app/build/outputs/apk/debug/app-debug.apk`。模拟器安装与启动测试见[验证说明](docs/testing/README.md)，不会随构建自动操作设备。

手机工具从见域原样复制，通用能力可直接复用，无需先改代码。统一 CLI 启动 Life Archive 时显式传入 `--package com.elio.lifearchive`，或使用已核对的仓库外 Profile；默认包名与示例参数仍保留上游值，实际操作前须核对目标设备和包名。详见[工具说明](tools/README.md)。
