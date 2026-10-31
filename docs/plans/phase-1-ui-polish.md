# 一期 UI 优化实施计划

日期：2026-10-30，更新：2026-11-01。状态：**一期开发及验证完成（用户确认），进入使用反馈与维护阶段。** 当日追加的首页极简信息调整及电脑验证见本页第 8 节。

> 本页勾选记录实现及相应电脑回归，用户于 2026-11-01 明确确认一期验证完成；实测证据保留各自范围。Git 按类型分批提交，保持当前 main、不推送。当前结论与历史记录见[一期完成度核对](../engineering/phase-1-completion-audit-20261031.md)。

**目标：** 将已确认的编辑、阅读三主题及最终审定首页移植到现有 Android，保留完整本地笔记能力。

2026-10-31 用户追加并确认独立封面方案：旧首页右侧首图改为左侧封面，提供预设／自定义及阅读专门横幅。该决定覆盖本页历史“右侧小图／不生成封面”的对应边界，其他冻结区沿用原设计。具体约定见[封面设计](../design/note-cover-design.md)。

同日用户进一步要求首页、阅读、编辑上下布局紧凑、减少留白。本次覆盖旧截图对应的上下间距，以[三页紧凑布局交接](../engineering/phase-1-compact-layout-handoff-20261031.md)中的实际实现、前后预览与分层验证为准；其他页面保持现状。

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
- [x] 2026-11-01 用户明确确认一期验证完成，阶段验收收口；模拟器与真实手机的既有实测结果分别保留，后续按实际使用反馈定向处理。

设计 HTML 已冻结；后续根据用户反馈收尾，不默认重做设置等临时页面。本计划不额外授权安装依赖、清库或抢占用户正在使用的模拟器。

## 6. 用户追加的封面功能

- 首页左侧小图与阅读标题／元信息下方横幅共用封面；内置山野、湖泊、林间、纸页四张生图插画，新旧笔记缺省使用山野。
- 首页管理与阅读／编辑更多菜单共用设置面板，支持笔记已有本地图片和系统选图复制入库；保存／取消不改正文与草稿。
- 封面由笔记状态库持久化，显式 v2→v3 迁移；notes 备份快照 format=3 向后读取 1／2，完整备份恢复保留，普通 Markdown／ZIP 不承诺保留选择。
- 底层回归后通过真实 Repository 创建两篇隔离示例，生成正式 Compose 与阅读资源的电脑预览；不操作模拟器或用户资料库。
- 实际验证与交付以[封面功能交接](../engineering/phase-1-note-cover-handoff-20261031.md)为准，本节不将历史电脑检查沿用为新实现通过证据。

## 7. 三页上下紧凑布局

- 首页压缩标题栏、搜索分类上下距与底部导航，将每条笔记的元信息纳入文字列，管理保留独立点击区域，末条仍可滚到浮钮上方。
- 阅读收紧顶距、标题／元信息／封面／段落间距与行高，保留封面比例和目录原文定位；排版环境换代，避免复用旧几何缓存。
- 阅读／编辑共享 56 dp 顶栏，源码／实时预览正文缩短内边距与行高，展开工具面板改为 48 dp；保留原文字号、系统 Insets 与输入会话。
- 独立导出三页前后对比及三主题、键盘占位／收起预览，复用现有功能回归验证。结果和包身份见[紧凑布局交接](../engineering/phase-1-compact-layout-handoff-20261031.md)。

## 8. 首页极简信息（2026-11-01）

- 用户选择第二轮概念图中的 A：元信息仍位于摘要下方，与标题左对齐，保持紧凑单行。
- 根目录笔记省略默认“未分类”及文件夹图标，显示为“日期 · #标签”；有实际目录的笔记保留目录名称，以圆点分隔。缺失字段不产生多余分隔符。
- 日期、目录和标签统一使用主题适配的低强调灰蓝文字，无胶囊、底色或边框；目录限宽、标签单行省略，保留右侧独立管理按钮。
- 只调整 `NotesScreen.kt` 的展示，不改变目录、标签、筛选、正文、封面或存储。
- 本轮执行 `checkArchitecture :feature:notes:testDebugUnitTest :feature:notes:lintDebug :app:assembleDebug :app:testDebugUnitTest --tests com.elio.lifearchive.NoteCoverPreviewTest -x :feature:editor:installReader --console=plain` 通过，耗时 2m 10s；笔记模块 20 项测试与 1 项 App 封面预览测试均无失败、错误或跳过，notes Lint 无错误、2 项警告。没有运行完整 `verifyPhaseOne`。
- 已回读正式 Compose 浅深色电脑预览，使用隔离真实库中的两篇有目录笔记；本轮预览不包含根目录笔记，也不代表设备视觉验收。APK、`home-light.png`、`home-dark.png` 和构建日志固定在 `D:/My_Elio/Life-Archive-Deliveries/minimal-metadata-20261101/`。
- APK 为 `Life-Archive-minimal-metadata-debug.apk`，SHA-256：`2231689508492bb00178817f9f98426ef7ec1d930b43a2d2344aab3c4e595ec6`。本轮未操作模拟器或手机，未安装、卸载或清库；未提交或推送 Git。
