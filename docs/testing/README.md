# 当前验证方法

## 二期完整迁移与独立恢复（无设备）

支持范围、互通夹具来源与失败保护见[完整迁移交接](../engineering/phase-2-complete-backup-handoff.md)。完整见域测试与真实装配入口：

```powershell
./gradlew.bat :data:jianyu:testDebugUnitTest :feature:jianyu:testDebugUnitTest :app:testDebugUnitTest --tests '*CompleteBackupIntegrationTest' --tests '*BusinessHostUiTest' --console=plain --max-workers=1
```

数据测试包含来源 App 生成的合成 `.jyfull` 夹具、实际 Schema／正文／附件、替换日志中断、提交后补清理以及重开校验失败后的显式重试回滚。真实 App 测试包含备份不丢输入、非空目标先备份、替换／清理失效旧输入与会话、两域互不覆盖。测试间显式关闭见域运行实例，避免 Robolectric 类加载器复用后跨临时目录持有旧 Room。

旧来源完整导出在来源仓库定向测试与构建，命令及身份见其 `docs/guides/life-complete-migration.md`。所有上述操作仅用电脑合成资料；硬件 Keystore、系统文件选择器、实际迁入和设备性能仍按[二期真机清单](phase-2-manual-acceptance.md)记录。

## 二期见域业务与双域入口（无设备）

真实入口、模块边界和未完成项见[宿主交接](../engineering/phase-2-business-host-handoff.md)。Windows / PowerShell 7 / JDK 17 下运行：

```powershell
./gradlew.bat checkArchitecture :domain:jianyu:test :data:jianyu:testDebugUnitTest :app:testDebugUnitTest --tests '*BusinessHostViewModelTest' --tests '*BusinessSwitcherTest' --tests '*VisibilityLifecycleOwnerTest' --console=plain --max-workers=1
./gradlew.bat :app:testDebugUnitTest --tests '*BusinessHostUiTest' --tests '*NavigationHostTest' --tests '*OrganizationNavigationTest' --tests '*ReaderHostStabilityTest' --console=plain --max-workers=1
./gradlew.bat :app:assembleDebug :app:lintDebug :data:jianyu:lintDebug :feature:jianyu:lintDebug --console=plain --max-workers=1
node reader/test/fragment.test.mjs
```

浏览器样板需先运行 `:core:document:test` 生成真实投影及已有 reader 资源任务。上述宿主检查不单独证明完整迁入／恢复，数据闭环见上节；手机流式长列表性能另验，不操作真实数据或手机。

## 二期共用 Markdown 宿主（无设备）

当前只验证共享宿主及 Life 适配，不代表见域接入、悬浮切换、历史迁移或流式消息性能通过。实现与结果见[阶段记录](../engineering/phase-2-shared-markdown-handoff.md)。Windows 10 x64、PowerShell 7、JDK 17 下运行：

```powershell
./gradlew.bat checkArchitecture :core:markdown:testDebugUnitTest :feature:editor:testDebugUnitTest --console=plain --max-workers=1
./gradlew.bat :app:testDebugUnitTest --tests '*SharedMarkdownHostTest' --tests '*ReaderHostStabilityTest' --tests '*NavigationHostTest' --tests '*OrganizationNavigationTest' --tests '*FoundationRuntimeTest' --console=plain --max-workers=1
./gradlew.bat :core:markdown:lintDebug :feature:editor:lintDebug :app:lintDebug :app:assembleDebug testReader --console=plain --max-workers=1
```

reader 源资源与生成资源现由 `core:markdown` 提供；原 `:feature:editor:readerAssets` 入口转交该模块。浏览器及 Robolectric 结果不代替真实 System WebView 和目标机验收，不安装或连接手机。

## 四期分类整理（无设备）

在 PowerShell 7、JDK 17 下运行 `./gradlew.bat verifyNoteOrganization --console=plain --max-workers=1`，包含三期回归、领域规则、元数据契约、分类存储及 Compose 测试、架构、Lint 和 APK 构建；不安装应用或连接手机。500 篇合成笔记仅在临时库验证整理，真实 Kotlin 客户端另通过临时 PC HTTP 服务验证分类后往返及电脑 AI 完成工具。PC 运行 `npm.cmd test`、`npm.cmd run test:contracts` 和其打包／冒烟入口。检查、日志及版本见[分类交接](../engineering/note-organization-handoff.md)，手机单列[真机清单](note-organization-manual-acceptance.md)。

## 三期同步（仅电脑、合成资料）

```powershell
.\gradlew.bat --no-daemon --max-workers=2 verifyPhaseThree --console=plain
```

汇总一期回归、架构、构建与 Lint，新增 ID／协议样例、规则、真实 Kotlin 客户端与 PC HTTP 服务、笔记存储和 Compose 入口重建检查。跨端用相邻 `life-archive-pc` 的专用测试夹具，可通过 `LIFE_ARCHIVE_PC_ROOT` 指定该仓库；PC 同步源文件作为测试输入，变更后不会复用过期通过结果。全部数据在临时合成目录，不运行真实库。

大库检查使用 500 篇笔记、100 个共 200 MiB 附件，其中单文件 50 MiB，结果默认写到仓库外系统 TEMP 的 `life-archive-phase3/sync-scale.json`；`LIFE_ARCHIVE_PHASE3_EVIDENCE` 可指定证据目录。PC 单测、契约、打包和真实静态页面冒烟见其 `docs/SIMPLE_SYNC.md`。实际结果与产物见[三期交接](../engineering/phase-3-sync-handoff.md)，手机另按[三期真机清单](phase-3-manual-acceptance.md)验收。

2026-11-01 用户明确确认一期验证完成，当前进入使用反馈与维护阶段；结论见[完成度核对](../engineering/phase-1-completion-audit-20261031.md)。下方按日期／版本记录的检查及未实测范围保留历史含义，后续按具体改动和问题选择验证入口。

2026-10-31 核心流程兼容与恢复检查继续使用 `testReader` 和 `verifyPhaseOne`：新增能力缺失、布局／投影缓存类型错误、消息桥失败、库加载重试、PDF 页错误、图片超时、初始化配置及保存竞争回归。问题表、最终检查及 APK 见[核心流程扩展修复交接](../engineering/phase-1-core-resilience-handoff-20261031.md)，电脑故障注入与设备验收分开记录。

三页上下间距调整的前后对比、三主题／键盘占位与收起预览、当前完整验证及最新包身份见[紧凑布局交接](../engineering/phase-1-compact-layout-handoff-20261031.md)。

独立封面的真实存储示例、Compose／阅读资源预览及复现命令见[封面功能交接](../engineering/phase-1-note-cover-handoff-20261031.md)。封面回归接入既有数据、首页、App 与阅读浏览器检查入口；不操作设备。

一期状态与证据入口：[完成度核对与接手清单](../engineering/phase-1-completion-audit-20261031.md)、[用户手动验收清单](phase-1-manual-acceptance.md)。用户已确认一期验证完成；以下带版本的数字保留历史含义，已确认项目不默认重测。

## 根目录构建／安装脚本验证

2026-11-01 新增 `build.ps1`，来源及本项目适配见[工具来源](../../tools/UPSTREAM.md#2026-11-01根目录构建脚本适配)。PowerShell 7 下执行：

```powershell
pwsh.exe -NoProfile -File .\build.ps1 -Help
pwsh.exe -NoProfile -File .\build.ps1 -VersionName 0.2.7 -Install -WhatIf -NoPause
pwsh.exe -NoProfile -File .\install.ps1 -Help
pwsh.exe -NoProfile -File .\install.ps1 -WhatIf -NoPause
pwsh.exe -NoProfile -File .\tools\tests\Run-BuildScriptTests.ps1
```

无设备回归 **63 项断言 PASS**：默认保留版本，参数／交互修改，显示版本变化时内部版本递增，UTF-8 BOM／CRLF／其他改动保留，帮助／预演无写入，Gradle 错误码保留，缺失／错误包名／旧版本产物拒绝；已存在 APK 且源码变化但版本未变时仍调用 Gradle，安装新产物，唯一设备／显式目标、多设备及未授权门禁，未签名 Release 拒绝安装，安装失败／安装后状态不符不报成功。新增独立 `install.ps1` 入口验证帮助、预演、设备参数转发、不改版本及保留安装失败退出码。

本机实际运行 `build.ps1 -OutputDir D:/Temp/life-archive-build-script-20261101 -NoPause` **PASS**，Gradle 用时约 7 秒、177 个任务全部 UP-TO-DATE；APK 哈希和修改时间、`app/build.gradle.kts` 哈希均保持不变。导出 `Life-Archive-0.2.6-debug.apk`，SHA-256 为 `13c5fbf3e67e0944f2121f85f53f97d0df758f17cfbfb88626927e6bbdee9218`。测试与实际构建日志分别保存在仓库外 `%TEMP%/life-archive-build-script-tests.log`、`%TEMP%/life-archive-build-script-smoke.log`。

未操作真实设备，真实 ADB 覆盖安装与设备行为仍为 **NOT_VERIFIED**；脚本变更未修改 Android 业务源码，不重复既有完整一期验收。

## 粘贴建笔记与提示词优化验证

独立更新 source 的验证见[链接更新交接](../engineering/phase-1-update-video-link-20261101.md)。定向入口为 `:core:document:test :feature:notes:testDebugUnitTest :feature:editor:testDebugUnitTest :app:testDebugUnitTest`；`SourceLinkNavigationTest` 检查预览／阅读／编辑、真实剪贴板接口、取消、多链接与重建；`SourceLinkCompositionTest` 使用真实 InputConnection 检查中文组合输入阻断。`SourceLinkDialogPreviewTest` 生成三主题长链接对话框截图，默认目录为仓库外 `D:/Temp/life-archive-update-link-20261101/`，可用 `LIFE_ARCHIVE_SOURCE_PREVIEW` 指定。

本轮职场／生活提示词文案优化见[提示词交接](../engineering/phase-1-video-prompts-20261101.md)，定向执行架构检查、notes 单测、`VideoNotesNavigationTest` 与 Debug APK 构建；校验每类完整文本不超过 1000 字符及 YAML 格式示例可解析。此前完整一期检查仍保留其历史范围。

2026-11-01 后续需求见[粘贴交接](../engineering/phase-1-paste-notes-handoff-20261101.md)。定向入口为 `:feature:notes:testDebugUnitTest :app:testDebugUnitTest :core:preferences:testDebugUnitTest`，完整入口仍为 `checkArchitecture verifyPhaseOne`，使用 PowerShell 7、JDK 17，不连接设备。

新增检查覆盖 YAML 标题及两种标签列表、未知属性／BOM／CRLF 保留、普通文本补属性、标题回退和安全转义、全文围栏与内部代码、异常 YAML、Windows 名称／长度、重名后缀、失败重试、重复点击、首页预览取消不落盘、Activity 重建不重读剪贴板、真实 Repository 创建与标签筛选。原自定义提示词与完整备份回归保留；阅读浏览器检查继续验证标题、粗体和高亮展示。Compose 预览增加粘贴页和含可见粘贴按钮的首页三种风格图片。

同日用户追加统一主题，`SharedThemeNavigationTest` 验证唯一的三风格选择器、首页实际文字配色、阅读切换返回首页、设置切换、重建持久化。全 App 沿用 documentTheme 与原备份结构，旧首页浅深设置只用于历史兼容，当前界面不再提供独立选择。

## 0.2.6 视频摘记补充验证（初版历史）

这是一期完成后的新增需求，检查范围与设备待验收项见[视频摘记交接](../engineering/phase-1-video-notes-handoff-20261101.md)，不重新打开历史未填验收表。

```powershell
.\gradlew.bat :core:preferences:testDebugUnitTest :feature:notes:testDebugUnitTest :app:testDebugUnitTest --console=plain
.\gradlew.bat verifyPhaseOne --console=plain
```

设置回归覆盖四类提示词持久化、逐类恢复默认、真实写入失败、format=2 往返、format=1／省略版本号的旧快照及最大转义体积。App 真实运行实例验证完整备份恢复提示词、笔记与附件；导航回归覆盖四类系统剪贴板复制、主动粘贴／替换确认、未保存返回确认、Activity 重建、目标笔记本、同名不覆盖及保存后打开编辑器。ViewModel 另外检查异常、失败重试和重复点击。

`VideoNotesScreenTest` 生成浅深色正式 Compose 预览，默认保存在仓库外 `D:/Temp/life-archive-video-notes-20261101/`，可通过 `LIFE_ARCHIVE_VIDEO_PREVIEW` 指定目录；预览和 Robolectric 剪贴板不等于真实手机、输入法或点点总结效果验收。

## 0.2.5 首页列表性能修复验证

入口仍为 `verifyPhaseOne`，不连接设备。`NotesScreenTest` 用 500 篇合成笔记检查上下滚动不再扫描详情列表、提示增删及排序后保留可见笔记和偏移、点击与管理使用正确资料；500 项标签／笔记本选择检查屏外选项按需组合并可选到末项。数据测试覆盖无搜索词列表、跨文件名／标题／别名／正文搜索、匹配偏移、目录／标签和回收站过滤；ViewModel 检查重复查询不再触发刷新。

```powershell
.\gradlew.bat :feature:notes:testDebugUnitTest :data:notes:testDebugUnitTest --console=plain
.\gradlew.bat verifyPhaseOne --console=plain
```

这些检查验证工作量和交互正确性，不测手机帧时间。掉帧率、连续滑动及目标机验收见[列表性能修复交接](../engineering/phase-1-list-performance-handoff.md)。

## 0.2.4 编辑器输入修复验证

入口仍为 `verifyPhaseOne`，不连接设备。新增 64 种混合容器、空项退出、编号上限、CRLF、检查点失效与扫描范围、正文快照复用、样式区间查询、输入／回车按键／粘贴协议、初次高亮未就绪仍可输入及 200 ms 防抖回归。既有草稿、保存和全部一期回归继续执行。

`EditorPerformanceTest` 每种 50 KiB／1 MiB UTF-8 合成正文采样 20 次，对比同版本完整扫描／全文投影与索引查询；默认写到系统临时目录 `life-archive-editor-performance/editor-query-samples.json`，可用环境变量 `LIFE_ARCHIVE_EVIDENCE` 指定仓库外目录。它只测 Kotlin 查询，不包含 Android 排版、百度输入法和系统键盘动画。最新结果与小米 14 Ultra 手动验收见[输入修复交接](../engineering/phase-1-editor-input-handoff.md)。

## 0.2.3 工具栏与回车续写验证

入口仍为 `verifyPhaseOne`，不连接设备。增加纯 Kotlin 命令／模板／续写测试、EditorSession 一步撤销与原文保真、实际 Android InputConnection 组合协议、真实触摸单击／双击／长按、面板焦点、过期链接／附件回调及 Activity 重建表单回归。阅读脚本使用 Kotlin 命令实际生成的模板检查表格、代码、分隔线、Callout、公式、Mermaid 与脚注，不另写一套手工 HTML 充当模板结果。

```powershell
.\gradlew.bat :core:document:test :feature:editor:testDebugUnitTest :app:testDebugUnitTest --console=plain
.\gradlew.bat verifyPhaseOne --console=plain
```

新增测试在电脑验证 InputConnection 调用协议，不运行用户的手机输入法。双击手感、候选词、系统选择器、键盘遮挡、滚动与最终视觉另按[工具栏交接](../engineering/phase-1-toolbar-handoff.md)手动验收。已有阅读提速基线与性能测试入口保留下方；本轮未重复性能对比，不用编译通过推断目标机速度。

## 0.2.2 阅读提速验证

仍执行下方 verifyPhaseOne。新增纯 Kotlin 阅读投影范围测试、阅读缓存持久化/损坏/容量/清理代次测试、根快照不重复读取、缓存失败回退、受限消息校验、首屏后预备高亮及设置/恢复清理回归。浏览器检查区分首屏可读与按需内容完成，完整兼容检查会主动遍历待加载内容。

性能对比先保存 0.2.1 的 reader 源资源到仓库外 baseline 根目录（内含 reader/index.html），共享固定版本的生成资源。app 本地测试会在系统 TEMP/life-archive-reader-fixtures 导出实际验收样例、sampleLandscape 配图及精确 UTF-8 的 50 KiB/1 MiB 文档。无需连接设备。

```powershell
$env:LIFE_ARCHIVE_READER_BASELINE = 'D:/Temp/life-archive-reader-022/baseline'
$env:LIFE_ARCHIVE_EVIDENCE = 'D:/Temp/life-archive-reader-022'
node reader/test/open-performance.mjs
```

每组至少 20 次，按样例、50 KiB、1 MiB 和 50 MiB PDF 交替比较两个版本，输出原始记录、p50/p95、浏览器 JS 堆及命中率。`LIFE_ARCHIVE_PERF_DOCUMENTS` 可指定单组名称补测；不指定则测全部已导出样例。脚本在开始时冻结源资源并记录指纹，PDF 通过范围流读取，并分别记录首屏占位与指定页真正完成；不会把占位当作 PDF 渲染成功。

HTTP 测试适配模拟原生缓存通信，共用相同正文投影、配图和固定版本的阅读库，不能将桌面数值等同完整 Android 打开时间；Native 缓存与读取边界另由本地测试覆盖。目标机手动记录从点击到可读的时间。Debug 的 LifeArchiveReader 日志另外记录 BODY/FIRST_SCREEN/IDLE、进入页面/网页耗时、HTML 命中及派生物命中数，不包含正文。

最新实际结果和手动验收步骤见[阅读提速交接](../engineering/phase-1-reader-performance-handoff.md)。

## 一期功能版验证（当前入口，无设备）

```powershell
.\gradlew.bat verifyPhaseOne --console=plain
```

包含已有 verifyFoundation、七个 Android 模块 Lint、全部 feature 回归、Debug/仪器测试 APK 及 Release Kotlin 编译，新增迁移、多文件恢复、回收站、导入导出和缓存测试。读取 reader/package-lock.json，用本机 Node.js 24/npm 安装并组装本地阅读资源；不升级 Android 工具链。浏览器检查使用本机 Chrome/Edge，其他路径通过 LIFE_ARCHIVE_BROWSER 指定。

`testReader` 可单独运行，使用公共解析生成的投影验证浅深色公式、Mermaid、嵌入、脚注、HTML 清理、默认离线、50 MiB 级 PDF 翻页及音视频元数据/定位。截图输出仓库外 TEMP/life-archive-phase1-browser。Node 输出及 Android 检查不代替实际 System WebView、系统选择器、输入法或目标机性能。

最新实现、验证记录和手机验收步骤以[阅读提速交接](../engineering/phase-1-reader-performance-handoff.md)为准，0.2.1 缺陷修复见[稳定性修复交接](../engineering/phase-1-stability-handoff.md)，完整支持矩阵见[一期功能交接](../engineering/phase-1-function-handoff.md)。既有回归覆盖 SOURCE/LIVE 连续空行高亮、解析与保存并发、WebView 无关状态更新、旧查询回执、YAML 锚点、多文件回滚清理、排队下载清理、分享失败和恢复后会话失效。下面的样板与底层入口继续作为历史子集保留。

以下为首轮样板阶段的验证说明：当时已有架构、核心数据底层及一期编辑器交互样板，以及 JVM、Robolectric 和启动冒烟测试源码；完整一期与 CI 尚未建立。底层历史结果见[架构交接](../engineering/foundation-handoff.md)，样板与手动验收见[样板交接](../engineering/editor-prototype-handoff.md)。这些检查仅在电脑执行，不连接设备；构建、测试、设备启动和视觉检查分别记录。

## 编辑器样板验证（无设备）

```powershell
.\gradlew.bat verifyEditorPrototype --console=plain
```

包含下方全部底层回归，以及两个 feature 的测试/Lint、app 的输入/导航/Activity 重建/视觉测试。长文测试覆盖 50 KiB 与 1 MiB 原文及文本控件输入、模式切换。桌面预览输出至 `%TEMP%/life-archive-editor-preview/`，编辑图明确使用键盘占位；不能作为真实输入法验收。用户手动安装目标机后按样板交接逐项记录。

## 核心底层一键验证（无设备）

Windows 10 x64、PowerShell 7、JDK 17，在仓库根目录执行：

```powershell
.\gradlew.bat verifyFoundation --console=plain
```

包括：模块依赖检查、core:document/core:backup JVM 测试、data:notes/core:preferences/app 及两个 feature 的 Robolectric 与本地状态测试、六个 Android 模块的 Debug Lint、Debug App/仪器测试 APK 构建和 Release Kotlin 编译。不会安装 APK 或调用 ADB。

Robolectric 会下载自己的 Android 测试运行库，不需要模拟器；它的结果不能表述为真机通过。测试使用临时目录的合成文档及附件。`domain:notes` 主要为契约，不用无源码测试数量充当覆盖率。

报告位于各模块 `build/test-results/`、`build/reports/tests/` 和 Android 模块 `build/reports/lint-results-debug.html`。单独运行存储测试：

```powershell
.\gradlew.bat :data:notes:testDebugUnitTest :core:preferences:testDebugUnitTest :app:testDebugUnitTest --console=plain
.\gradlew.bat checkArchitecture --console=plain
```

## Android 构建检查

需要 Windows 10 x64、PowerShell 7、JDK 17、Android SDK Platform 36 和 Build Tools 35.0.0。SDK 与 JDK 使用本机现有安装，不把个人路径写入版本控制：设置 `JAVA_HOME`，通过 `ANDROID_HOME` 或根目录被忽略的 `local.properties` 中的 `sdk.dir` 指定 SDK。Java 命令行 PATH 可能指向旧 Java，检查 Wrapper 实际使用的 JVM：

```powershell
& "$env:JAVA_HOME\bin\java.exe" -version
.\gradlew.bat --version
.\gradlew.bat :app:assembleDebug :app:lintDebug :core:designsystem:lintDebug :app:assembleDebugAndroidTest --console=plain
```

首次构建会下载 Wrapper 与 Maven 依赖；已固定版本见 `gradle/libs.versions.toml`。以上命令不安装 APK、不连接设备。输出入口：

- App：`app/build/outputs/apk/debug/app-debug.apk`。
- 仪器测试：`app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk`。
- Lint 报告：`app/build/reports/lint-results-debug.html`，主题模块另有 `core/designsystem/build/reports/lint-results-debug.html`。

核心存储及恢复已有真实测试；`testDebugUnitTest` 显示 `NO-SOURCE` 的模块仍不能报告为单测通过。

## 已授权模拟器上的启动检查

以下命令只在用户已授权安装与启动的模拟器上执行。先用 `adb devices -l` 与模拟器管理程序核对实例，再输入已确认的 Serial；出现多个候选或 `offline` 时先解决目标身份或连接问题。不得因设备型号字符串显示小米就把雷电当作真实手机。

```powershell
$deviceSerial = Read-Host '输入已核对的模拟器 Serial'
adb -s $deviceSerial shell getprop ro.build.version.sdk
adb -s $deviceSerial shell pm path com.elio.lifearchive
adb -s $deviceSerial install -r app/build/outputs/apk/debug/app-debug.apk
adb -s $deviceSerial shell am start -W -n com.elio.lifearchive/.MainActivity
adb -s $deviceSerial install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb -s $deviceSerial shell am instrument -w com.elio.lifearchive.test/androidx.test.runner.AndroidJUnitRunner
```

`install -r` 用于保留数据的安装/覆盖更新，不为解决签名冲突而卸载或清数据。这里直接调用仪器测试，不使用会额外管理安装生命周期的设备测试任务。

冒烟测试断言应用名称与“全部笔记”均可见，并等待异步初始化。本轮只构建该测试 APK，未运行设备测试。后续需实际检查 Home 后返回、导航返回、浅深主题和启动日志。主题切换前记录原值，结束后恢复；截图、UI XML 和日志保存在仓库外，命令成功不能替代 UI 验收。

## 文档检查

在仓库根目录使用 PowerShell 7，执行 `git diff --check`；存在暂存改动时补充 `git diff --cached --check`。同时检查新增未跟踪文件的编码、尾部空白、相对链接及文档事实，Git 差异检查不覆盖未跟踪文件。

## 无设备工具检查

需要本机可用的 Python 3 和 PowerShell 7。脚本采用 Python 标准库，测试无需安装第三方依赖或连接手机；具体 Python 版本兼容性以实际测试结果为准。

```powershell
python tools/device/cli.py --help
python tools/screencap.py --help
python tools/click.py --help
python tools/uidump.py --help
python tools/adb_verbose_diagnose.py --help
python -m compileall -q tools/device tools/screencap.py tools/click.py tools/uidump.py tools/adb_verbose_diagnose.py
python -m unittest discover -s tools/device/tests -p 'test_*.py'
pwsh.exe -NoProfile -File tools/local-verification/tests/Run-LocalVerificationToolTests.ps1
pwsh.exe -NoProfile -File tools/local-verification/tests/Run-EmptyLineRegressionTest.ps1
```

Python 编译检查可能产生被忽略的缓存。PowerShell 测试使用系统临时目录并清理自己创建的文件。完整运行日志保存到仓库外，终端只需汇报结果和失败摘要。

## 后续验证

手机工具无需先修改代码；按[使用配置约定](../../tools/UPSTREAM.md)指定启动包名，使用 Profile 时核对其中的设备参数，再在授权范围内验证预期状态。设备操作命令执行成功不能替代 UI 状态验证。

后续业务单测随真实功能增加。编辑器和完整笔记功能按[一期验收要求](../plans/phase-1-local-notes.md)验证；核心底层电脑检查不替代设备文件系统行为、编辑器交互、性能或小米 14 Ultra 真机验收，也不沿用历史底座及见域的设备通过记录。
