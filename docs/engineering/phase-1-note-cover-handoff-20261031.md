# 独立笔记封面功能交接

后续用户要求三页上下布局更紧凑，最新布局与包身份见[紧凑布局交接](phase-1-compact-layout-handoff-20261031.md)。本文封面功能、截图与 APK 保留原轮次含义，交付目录未被后续预览覆盖。

日期：2026-10-31。实现交付时保持 main、未提交、未推送；后续用户授权的分类本地提交见[紧凑布局交接](phase-1-compact-layout-handoff-20261031.md#本地提交)。未操作模拟器、手机或用户资料库。

## 功能与入口

- 首页将封面移至左侧，右侧标题／摘要，下方日期／笔记本／标签／管理。默认封面统一为山野，不再自动用正文首图。
- 首页管理与阅读／编辑更多菜单的“设置封面”共用原生面板，包含预设、自定义、效果预览、保存和取消。
- 预设提供山野、湖泊、林间、纸页；自定义支持当前笔记已保存引用的本地图片及系统选图，手机图片复制入库，不依赖外部原文件。上限 8 MiB，使用系统图片解码能力；EXIF 方向应用于原生缩略图。
- 阅读顺序为开头 H1（存在时）、元信息、2.4:1 横幅、原正文。无 H1 不造标题；正文原图不隐藏；缺失／损坏封面回退山野，但保留原选择。
- 编辑自动保存可以继续运行，封面操作不更改 Markdown、YAML、正文版本、草稿、选区或撤销历史；面板随保存通知刷新正文图片候选。

生成图仅作为设计参考；[封面设计说明](../design/note-cover-design.md)记录资源路径、交互及内置 image_gen 完整提示词。实际电脑预览来自实现，不以生图充当代码效果。

## 数据与兼容

- domain:notes 提供 `CoverPreset`、`NoteCover.Preset/Attachment`、`CoverImage`；`LibraryNote.cover` 为当前选择，`LibraryRepository.setCover/coverImages` 为公开写入与候选能力。
- data:notes 显式迁移状态库 v2→v3，新增稳定预设 key／库内附件路径，v1 仍经 v2 迁移；旧数据默认山野。不重建状态库，不引入新模块或依赖。
- 写入经过现有共享 StorageGate，落盘后通知观察者；正文保存读取最新状态，避免覆盖封面。笔记移动／重命名、回收与恢复保留选择，附件随笔记本移动时维护封面路径。
- notes 参与者快照为 format=3，兼容读取 1／2；外层备份仍为 v1。完整备份包含状态及附件，普通 Markdown／ZIP 不承诺保留封面选择。缺失附件仍可备份与恢复，显示层回退，不擅自清空用户选择。
- 预设位于共享设计系统 assets；首页小图按需加载，默认采样到 256px、4 MiB 缓存、两个并发槽；面板使用独立的 1024px／8 MiB 预算。阅读通过既有受控同源资源接口加载，横幅不混入正文投影缓存或原文偏移。

## 两篇真实示例与电脑预览

`NoteCoverPreviewTest` 在临时真实 NotesStorage 中创建“山野慢行”和“窗边阅读”，分别设置山野预设与复制入库的自定义纸页图片，然后关闭重开、查询并读取附件。确认保存与取消行为后销毁临时库，不给 App 首次启动自动灌入示例。

预览目录：`D:/My_Elio/Life-Archive-Deliveries/note-covers-20261031/`，入口 `index.html`，汇总图 `overview.png`。包含首页浅／深色、预设／自定义／空候选面板、两篇阅读正文 A/B/C 三主题及末尾截图、导出的示例 Markdown、元信息与 `preview-manifest.json`。

首页／面板来自正式 Compose 的 Robolectric 渲染；阅读使用同一入库文档的正式投影及 reader.js／reader.css，在电脑 Chrome 中渲染，不包含 Android 顶栏或系统栏。临时库退出测试后清理，目录内的 Markdown／图片仅是预览副本。

复现（Windows 10 x64、PowerShell 7、既有 JDK 17／SDK／Node／Chrome）：

```powershell
$env:LIFE_ARCHIVE_COVER_PREVIEW = 'D:/My_Elio/Life-Archive-Deliveries/新的封面预览目录'
.\gradlew.bat :app:testDebugUnitTest --tests com.elio.lifearchive.NoteCoverPreviewTest -x :feature:editor:installReader --console=plain
node reader/capture-covers.mjs --fixtures $env:LIFE_ARCHIVE_COVER_PREVIEW
.\gradlew.bat verifyPhaseOne -x :feature:editor:installReader --console=plain
git diff --check
```

`LIFE_ARCHIVE_COVER_PREVIEW` 仅设置当前进程环境，预览命令会重写该目录的同名预览文件；使用新的专用目录，不指向用户资料或历史交付目录。

## 本轮验证与产物

- 最终 `verifyPhaseOne -x :feature:editor:installReader --console=plain` 通过：3m 5s，539 个任务中 94 执行、445 复用。架构检查、Debug App／仪器测试 APK 构建、Release Kotlin 编译均通过；没有运行设备仪器测试。最终日志固定为预览目录中的 `verifyPhaseOne.log`。
- 当前 JUnit 合计 230 项，失败／错误／跳过均为 0：document 45、backup 7、preferences 7、data:notes 54、editor 69、notes 20、settings 4、app 24。新回归包含迁移、持久化与草稿／保存并行、附件移动／回收恢复、备份往返、坏图／失败保护、导入流关闭及 EXIF 方向、真实示例／面板保存取消。复用任务不表示全部测试在最后一条命令重新执行，NO-SOURCE 不计作测试。
- 正式阅读浏览器 38 项检查通过；两篇真实入库示例 × 三主题的预览、标题／元信息／封面／正文顺序及目录定位通过。已回读首页浅深、选择面板与正式阅读预览；导出脚本等待独立封面解码和绘制完成，避免把首屏占位当作图片已展示。
- 七个 Android 模块 Debug Lint 均无错误：app 36、editor 1、notes 2、designsystem 1 条警告，其余 0。新增 3 条为封面组件 Modifier 参数顺序和系统 ExifInterface 建议；为遵守不新增依赖的范围，沿用 minSdk 26 可用的系统 EXIF，未屏蔽检查。它们不等于设备照片兼容已验收。
- UTF-8、37 个改动／新增文本文件的尾部空白与 Markdown 本地链接检查通过，`git diff --check` 通过；未暂存，没有需要追加检查的暂存差异。原始 JUnit／Lint XML 及计数固定在同目录的 `reports/`、`test-summary.json`。

固定 Debug APK：`D:/My_Elio/Life-Archive-Deliveries/note-covers-20261031/Life-Archive-note-covers-debug.apk`。包名 `com.elio.lifearchive`，仍为 0.2.5／versionCode 7，本轮未改版本号，不可仅用版本号区分此前包。

- 66,098,782 字节，SHA-256：`45200c4ed25e78114a316a987ead7d0853e84aceb774085f5b9b85fb0b1e4781`。
- `apksigner verify --print-certs` 成功，证书 SHA-256：`baef356dff648a2d92863b2309e67ac604e71935fa71b48d493f5dc466dccb54`，原始输出 `apksigner.txt`。
- `delivery-manifest.json` 记录基准提交、未提交工作区各文件 SHA-256、APK 与检查摘要。以上路径仅为本机交付，不替代跨机器分发。

未验证：模拟器／小米 14 Ultra 的系统选图、真实输入法与系统栏、实际照片格式兼容与性能、设备视觉及用户最终认可。没有清库、卸载、安装或连接设备，也不将电脑浏览器截图报告为 Android WebView 设备通过。
