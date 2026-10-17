# Life Archive

面向个人使用的 Android 应用，第一期以本地 Markdown 笔记起步，按 Obsidian 官方内置文档语法规划文件兼容能力。整体功能范围仍在确认，架构须能承接后续选中的任务、生活管理、AI 和出行等能力。

**当前状态：仓库基础准备阶段。** 已有阶段计划、协作说明和从见域复制的辅助工具；尚未创建 Android 工程，没有可运行 App、APK 或已验收的笔记功能。

## 项目标识与方向

| 项目 | 约定 |
| --- | --- |
| 项目名 / 未来显示名 | Life Archive |
| 未来 namespace / applicationId | `com.elio.lifearchive` |
| 个人域名 | `my-elio.online`；本阶段不建设网站或服务端 |
| 平台 | 仅 Android |
| 已选架构 | 模块化单体；Kotlin、Compose、ViewModel + UDF、Navigation 3、Hilt；具体兼容版本在建立工程时固定 |
| 数据方向 | UTF-8 Markdown 文件保存正文，Room 用于索引、缓存与应用状态 |
| 开发环境 | Windows 10 x64，PowerShell 7（`pwsh.exe`） |
| 使用基线 | 小米 14 Ultra，约 500 篇笔记；App 管理私有笔记库 |

## 阶段计划

整体产品范围还在梳理，参见[整体功能版图与旧版迁移评估](docs/plans/overall-feature-roadmap.md)。其中后期候选等待确认，不自动扩充以下阶段。

1. [第一期：本地笔记](docs/plans/phase-1-local-notes.md)：编辑、组织、搜索、附件、导入导出和简单备份恢复。交互与视觉以[已确认的决策文档](docs/plans/phase-1-editor-visual-decisions.md)为准。
2. [第二期：AI 与共享能力](docs/plans/phase-2-ai-and-shared-capabilities.md)：对接 AI 进行全搜索、自动调用资料，并与见域共用重叠能力。
3. [第三期：电脑与 Obsidian](docs/plans/phase-3-computer-obsidian.md)：记录电脑保存、AI 整理、原件留存及回写目标，详细方案后续讨论。

这些是目标范围，不代表已实现。第一期不接入 AI、不抽取两款 App 的共享模块；旧数据库不迁移。版本对比、关系图和手写暂不计划实现。

## 仓库入口

| 入口 | 用途 |
| --- | --- |
| [AGENTS.md](AGENTS.md) | AI 与开发者协作规则 |
| [docs/README.md](docs/README.md) | 文档索引与状态区分 |
| [docs/architecture/](docs/architecture/README.md) | 数据约定与未来代码组织 |
| [docs/design/](docs/design/README.md) | 设计依据和视觉资料归档 |
| [docs/testing/](docs/testing/README.md) | 当前可执行检查与验证边界 |
| [tools/](tools/README.md) | 手机控制、日志证据辅助工具 |
| [tools/UPSTREAM.md](tools/UPSTREAM.md) | 工具来源、复制清单及待适配事项 |

## 当前使用方式

架构已选定，实施入口为 [ADR-001：Android 模块化单体架构](docs/architecture/adr-001-android-architecture.md)，包含模块依赖、数据归属、文件保存、编辑器门槛、性能基线与交接顺序；此前单 app 模块方案已被替代。当前仅文档决定，未创建或验收 Android 工程。

进行整体架构选型时，先读 [AGENTS.md](AGENTS.md)，再读[整体功能版图](docs/plans/overall-feature-roadmap.md)、[一期范围](docs/plans/phase-1-local-notes.md)、[编辑器决策](docs/plans/phase-1-editor-visual-decisions.md)、第二/三期方向，最后核对[架构约束与待决事项](docs/architecture/README.md)。这些本仓库文档已足够开展架构讨论，不需要先查看旧项目。

强解耦、可扩展、可维护是选型要求；待确定功能不等于获准实施。旧项目链接只用于历史溯源，旧版功能状态不作为新项目的能力或验收证明。

先阅读任务对应的计划与说明。工具的帮助和无设备测试可按[验证说明](docs/testing/README.md)运行，无需安装 Android 应用。

手机工具是从见域原样复制的参考版本，**默认包名与示例仍含见域标识，尚未完成 Life Archive 设备适配**。实际操作前必须核对目标设备和包名。本次不提供 Android 构建或启动命令；待工程建立后再补充经过验证的入口。
