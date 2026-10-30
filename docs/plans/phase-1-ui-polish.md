# 一期 UI 优化实施计划

日期：2026-10-30，更新：2026-10-31。状态：实现及完整电脑检查通过；设备与最终视觉验收未统一收口，用户将另开 AI 接手。

> 本页勾选表示实现及相应电脑回归完成，不代表手机行为或用户最终视觉认可。用户最新要求按类型分批提交 Git，保持当前 main、不推送；原主管已停止开发调度。当前证据与剩余清单见[一期完成度核对](../engineering/phase-1-completion-audit-20261031.md)。

**目标：** 将已确认的编辑、阅读三主题及最终审定首页移植到现有 Android，保留完整本地笔记能力。

**架构：** 文档主题在文档目的地局部生效，通过现有 PreferencesRepository、DataStore 和 preferences 备份参与者记忆。编辑继续使用同一 EditorSession；正式阅读继续使用受控 WebView、原文定位和按需缓存。首页展示派生值来自既有索引查询，不引入权威正文或新模块。

**技术：** Kotlin、Compose、Material 3、DataStore、CommonMark、现有本地阅读资源；不新增或升级依赖。

## 1. 文档主题与持久化

文件：`core/preferences/.../PreferencesStore.kt`、对应测试；`core/designsystem/.../DocumentTheme.kt`；`app/.../ArchiveApp.kt`、`MainActivity.kt`；`feature/editor/.../EditorScreen.kt`。

- [x] 测试三主题独立保存、重开、备份往返、旧 format=1 缺字段、未知主题拒绝。
- [x] 新增 `DocumentTheme` 三值与可空偏好，未选择的旧数据按首页实际浅深回退 A/C，明确选择后独立；写入失败不公开成功状态。新建空设置默认 A。
- [x] 保留现有备份参与者及 StorageGate，restore 同步恢复文档主题；不修改外层备份格式。
- [x] 定义局部文档颜色，首页与设置保留现有全局主题；系统栏明暗随当前目的地恢复，实际系统栏效果列入设备验收。

## 2. 正式阅读呈现

文件：`feature/editor/.../ReaderDocumentLoader.kt`、`WebReaderRenderer.kt`、`ReaderRenderer.kt`、`EditorScreen.kt`；`feature/editor/src/main/assets/reader/reader.js`、`reader.css`；阅读宿主、缓存和浏览器测试。

- [x] 补充主题切换不重建 WebView、目录原文定位及无开头 H1 不造标题的行为回归。
- [x] 保留单个正文 H1 与原文 data-source；展示真实目录、笔记本、更新时间及属性标签，不生成封面；保存通知后独立刷新元信息。
- [x] 阅读小文件名顶栏、更多菜单主题、右下角小编辑入口；正文末尾留出可滚动空间，原始属性仍可展开查看。
- [x] 三主题传入正式 reader 配置与派生环境；浅色 A/B 切换也更新公式/图表样式，保留首屏优先及缓存代次隔离。

## 3. 编辑壳与工具

文件：`feature/editor/.../EditorScreen.kt`、`EditorToolbar.kt`、`EditorEngine.kt`；`core/document/.../EditingCommands.kt`、`InlineEditing.kt`；工具、原文命令及输入交互测试。

- [x] 验证安全清除简单行内格式、拒绝复杂/代码区间及一步撤销。
- [x] 文件名与保存状态合并顶栏，完整保留失败重试、草稿恢复、附件、管理和模式入口。
- [x] 展开面板改为互斥单行横向图标，保留中文无障碍名称；长按约 500 ms 仅提示，松手不执行，拖动/取消/换页取消。
- [x] 标题单击加深、双击减浅；级别选择移更多菜单，使用会话锚点拒绝过期操作。
- [x] 保留 Insets/IME 布局及系统键盘，不绘制应用键盘；保留输入连接、选区、撤销、200 ms 解析、300 ms 草稿和 800 ms 保存。实际键盘遮挡与系统交互待对照用户手动结果。

## 4. 审定首页

文件：`feature/notes/.../NotesScreen.kt`、`NoteListPresentation.kt`、必要的 `NotesViewModel.kt`；`domain/notes/.../LibraryRepository.kt`、`data/notes/.../NotesStorage.kt`；对应列表与数据测试。

- [x] 接主管发放的最终 HTML/截图，文字优先布局，保留搜索、笔记本/标签组合、排序、置顶、管理、新建、快速记录及原有工具入口。
- [x] 在既有查询快照准备限长摘要和首图候选；沿用公开资源能力，限尺寸、可取消、按需加载，缺图自然回退，不改 Room schema。
- [x] 复验 500 篇 O(1) 关联、稳定 key/锚点、更新期间禁用和 500 项筛选懒组合，滚动不扫描正文；电脑回归不代表目标机帧时间通过。

## 5. 验证与交付

使用 PowerShell 7、JAVA_HOME 指定的 JDK 17；所有 Gradle 命令排除依赖安装，不操作设备：

```powershell
.\gradlew.bat checkArchitecture :core:preferences:testDebugUnitTest :core:document:test :data:notes:testDebugUnitTest :feature:notes:testDebugUnitTest :feature:editor:testDebugUnitTest :app:testDebugUnitTest -x :feature:editor:installReader --console=plain
.\gradlew.bat verifyPhaseOne -x :feature:editor:installReader --console=plain
git diff --check
```

- [x] 聚焦新增持久化、命令、工具、阅读缓存与首页回归后执行完整验证；最终 `verifyPhaseOne -x :feature:editor:installReader --console=plain` 通过，3m 3s，539 个任务中 37 个执行、502 个复用。
- [x] 工程交接记录通过/失败/未验证范围、APK 路径与 SHA-256，见[完成度核对](../engineering/phase-1-completion-audit-20261031.md)；不引用旧产物证明新实现通过。
- [ ] 新接手 AI 先对照用户已完成的手动验证，仅补验仍有缺口的设备截图与交互；截图和完整日志保存在仓库外。模拟器结果与真实手机输入/性能分别记录。

设计 HTML 已冻结；后续根据用户反馈收尾，不默认重做设置等临时页面。本计划不额外授权安装依赖、清库或抢占用户正在使用的模拟器。
