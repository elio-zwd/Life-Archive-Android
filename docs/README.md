# 文档索引

当前一期开发及验证已完成（2026-11-01 用户确认），进入使用反馈与维护阶段，其他临时页面保留。最终结论与历史证据以[一期完成度核对与接手清单](engineering/phase-1-completion-audit-20261031.md)为入口；历史未填验收表不再作为一期未完成待办，后续按用户反馈定向处理。阶段计划描述产品目标，架构文档描述边界，交接记录描述实现和验证，三者不能互相代替。

## 计划与决策

- [整体功能版图与旧版迁移评估](plans/overall-feature-roadmap.md)：功能分类、既有分期映射及待确认的后期候选，不等于全部获准实现。
- [第一期：Android 本地 Markdown 笔记](plans/phase-1-local-notes.md)
- [一期补充：视频摘记助手](plans/phase-1-video-notes.md)：首页粘贴预览建笔记、四类含 YAML／大类标签／阅读标记规则的提示词、来源保留与备份兼容。
- [第一期：编辑器交互与视觉决策](plans/phase-1-editor-visual-decisions.md)
- [第一期：UI 优化实施计划](plans/phase-1-ui-polish.md)：首页及已认可编辑／阅读视觉的 Android 移植、保护边界和分层验证。
- [第一期：阅读打开提速](plans/phase-1-reader-open-performance.md)：首屏优先、按需持久缓存及性能验收要求。
- [第一期：工具栏增强与回车续写](plans/phase-1-editor-toolbar.md)：标题手势、格式／插入面板、选区保护与输入事务约定。
- [第二期：单 App 整合与共用 Markdown](plans/phase-2-ai-and-shared-capabilities.md)：Life 唯一宿主、默认 Life、可拖动悬浮切换、各自底栏与状态保留；见域按需初始化和共用 Markdown，不访问 Life 文档。数据、外观及备份独立；已记录整合基线，生产模块尚未接入。
- [第三期：简化双向同步与 AI 修改前备份](plans/phase-3-computer-obsidian.md)：本期先行，含 ID、改名移动和初步交付门槛。
- [第四期：电脑端阅读显示与修改差异](plans/phase-4-pc-reading.md)：仅记录两个方向，暂不详细规划。
- [第四期优化：笔记分类与整理制度](plans/phase-4-note-organization.md)：独立实施主题／类型归档、三级标签、批量预览整理与真实内容时间；不强设 AI 内容第三类。
- [分类整理交接](engineering/note-organization-handoff.md)：接口、备份迁移、工具用法、实际检查和产物；[独立真机清单](testing/note-organization-manual-acceptance.md)区分电脑与设备结果。
- [第五期：知识库与资料联动](plans/phase-5-knowledge-base.md)：Life 文档知识库、AI 全库搜索、自动调用资料、其他新增笔记与对话联动及整 App 统一备份；仅记录方向，不展开设计。

一期范围及已确认交互以一期计划和决策为依据；三期按 2026-11-04 用户确认先行，计划、实现和验收分别记录。

## 架构讨论的阅读顺序与文档职责

新对话可只读取本仓库：根目录 AGENTS → 整体功能版图 → 一期范围与编辑器决策 → 任务涉及的阶段计划 → 架构要求。旧项目来源链接是可选溯源，不是架构讨论的前置材料。

| 文档 | 维护内容 |
| --- | --- |
| 根目录 README | 项目定位、当前状态、阶段摘要和阅读入口 |
| AGENTS | 长期协作规则、必须遵守的架构边界及更新要求 |
| 整体功能版图与阶段计划 | 已确认需求、候选范围、不做事项及用户决定 |
| 架构说明 | 技术方向、选型约束、未决问题；区分初步参考与已冻结方案 |

功能细节在计划中维护，README 和 AGENTS 通过链接引用。只在定位、入口或长期规则变化时同步摘要，避免多份完整清单发生冲突。整体架构已由 ADR-001 选定；模块、存储和状态管理按该文实施，编辑器样板和未定产品细节仍须完成各自门槛。

## 工程与资料

- [二期整合基线](engineering/phase-2-integration-baseline.md)：Life 与见域源码版本、入口、模块及依赖差异、渲染与迁移缺口，以及实际基线检查。
- [二期共用 Markdown 宿主阶段记录](engineering/phase-2-shared-markdown-handoff.md)：共享资源与 WebView 的独立边界、Life 适配及验证；不代表见域流式样板或整合完成。

- [三期同步实现与初步交付](engineering/phase-3-sync-handoff.md)：双向同步、备份脚本、恢复兼容、产物和实际检查；[真机验收清单](testing/phase-3-manual-acceptance.md)单独记录手机结果。

- [独立更新视频链接](engineering/phase-1-update-video-link-20261101.md)：平台分享文字提取网址、预览和已有笔记更新 YAML source、保存与原文保护及验证。

- [职场与生活视频提示词优化](engineering/phase-1-video-prompts-20261101.md)：四类完整模板小于 1000 字符、源码复制格式、通用阅读规则及本轮定向验证。

- [Life Archive 图标图片 v1](design/brand/life-archive-app-icon-v1.md)：已确认的书页／档案图形、原始 PNG、完整提示词、Android 图标接入及定向检查；手机效果待实测。

- [粘贴建笔记、提示词与共用主题](engineering/phase-1-paste-notes-handoff-20261101.md)：自动名称／标签预览、原文保留、规则提炼、全 App 三种风格共用和本轮验证。
- [视频摘记实现与验证](engineering/phase-1-video-notes-handoff-20261101.md)：0.2.6 的初版历史记录、设置快照兼容及当时验证边界。

- [核心流程扩展排查与修复](engineering/phase-1-core-resilience-handoff-20261031.md)：WebView 能力降级、缓存结构与异常隔离、渲染库及 PDF 重试、初始化时序和保存竞争回归；设备结果另验。

- [三页紧凑布局交接](engineering/phase-1-compact-layout-handoff-20261031.md)：首页、阅读与编辑上下间距优化、前后对比、三主题与键盘占位／收起预览及最新包身份。

- [独立封面功能交接](engineering/phase-1-note-cover-handoff-20261031.md)：首页左图、预设／自定义、阅读横幅、状态库 v3 与备份兼容、真实示例及电脑预览。
- [笔记封面设计](design/note-cover-design.md)：生成图片参考、四张插画资源、完整提示词与交互约定。

- [一期完成度核对与接手清单](engineering/phase-1-completion-audit-20261031.md)：完整一期与本轮 UI 工作的完成状态、最终电脑检查、APK 身份、尚待确认的手动验收及接手边界。
- [一期手动验收清单](testing/phase-1-manual-acceptance.md)：供用户后续逐项查看并与新 AI 对齐，已确认项目不默认重测。

- [首页列表性能修复交接](engineering/phase-1-list-performance-handoff.md)：0.2.5 的行数据关联、列表复用与滚动位置、长筛选列表、查询开销及验证边界。

- [编辑器输入修复交接](engineering/phase-1-editor-input-handoff.md)：0.2.4 列表／引用交错回车、输入与焦点解耦、高亮索引、电脑性能采样及百度输入法手机验收。

- [工具栏与回车续写交接](engineering/phase-1-toolbar-handoff.md)：0.2.3 的使用、输入协议回归、APK 与手机验收；历史验证入口。

- [阅读打开提速交接](engineering/phase-1-reader-performance-handoff.md)：0.2.2 的首屏调度、缓存接口、性能对比和手机验收。

- [一期稳定性修复交接](engineering/phase-1-stability-handoff.md)：0.2.1 的渲染、高亮、保存/恢复、查询、链接及缓存并发修复和验收表。

- [一期功能交接](engineering/phase-1-function-handoff.md)：当前功能、数据与备份迁移、阅读兼容矩阵、远程缓存边界及手机验收表。

- [编辑器交互样板交接](engineering/editor-prototype-handoff.md)：首轮功能入口、编辑保存与恢复语义、语法支持清单、电脑验证及小米 14 Ultra 手动验收步骤。

- [架构与核心数据底层交接](engineering/foundation-handoff.md)：底层公开接口、数据布局、错误与恢复语义，以及独立底层阶段的验证记录；当前功能层另见一期功能交接。
- [工程底座历史交接](engineering/bootstrap-handoff.md)：初始锁定版本、两个模块阶段的构建与启动验证、当时的架构接手边界。
- [架构入口](architecture/README.md)、[ADR-001：模块化单体架构](architecture/adr-001-android-architecture.md)：已选技术、模块与依赖、数据归属、存储和目标机型基线、实施验收顺序。
- [设计资料](design/README.md)：设计依据、样板及后续资料归档方式。
- [小米 14 Ultra UI 实施交接](design/xiaomi14ultra-ui-handoff.md)：固定 20:9 的 HTML、1440 × 3200 截图导出、三主题及 Android UI 移植边界。
- [验证说明](testing/README.md)：现阶段可执行的检查和未验证范围。
- [工具说明](../tools/README.md)、[复制来源](../tools/UPSTREAM.md)：辅助脚本入口、来源及使用配置约定。
- [根目录构建与可选安装脚本](../build.ps1)：版本号修改、最新产物增量检查、APK 导出及明确指定设备的覆盖安装；[无设备验证](testing/README.md#根目录构建安装脚本验证)。
- [独立手机安装脚本](../install.ps1)：直接进入上述检查和覆盖安装流程，默认不修改版本。

新增资料放入对应目录并更新本索引。记录验收时注明日期、代码版本、运行环境及实际结果；不把旧项目历史验收用作本项目证据。
