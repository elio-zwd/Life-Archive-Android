# 首页、阅读与编辑紧凑布局交接

日期：2026-10-31。依据用户最新要求，压缩三页上下空白，提高首屏内容密度。保持 main，承接上一轮封面功能；实现交付时未提交，后续用户已授权按功能分类本地提交，未推送或操作设备。

## 调整内容

| 区域 | 调整前 | 调整后 |
| --- | --- | --- |
| 首页标题栏 | 最小 66 dp | 最小 52 dp |
| 首页搜索／分类 | 搜索高 46 dp、底距 10 dp；分类上／下距 5／11 dp | 搜索高 44 dp、底距 6 dp；分类只留底距 4 dp |
| 首页笔记条目 | 标题摘要后另占一行 44 dp 元信息／管理区 | 日期、笔记本与标签收进文字列底部，管理移到行右侧，点击区域仍为 44 dp；上下距 8 dp |
| 首页底栏／列表末尾 | 底栏 70 dp；末尾留 104 dp | 底栏 56 dp；末尾留 80 dp，保留浮钮避让 |
| 阅读／编辑顶栏 | Material 顶栏 64 dp | 共用顶栏 56 dp，文件名与保存状态仍在同栏 |
| 阅读首屏 | 顶距 28 px；标题／元信息／封面下距 14／24／24 px | 顶距 16 px；对应下距 8／12／14 px |
| 阅读正文 | 1.9 行高；较宽段间距；底部 112 px | 1.65 行高；段落底距 .7em、小标题 .8em／.45em；底部 88 px |
| 编辑正文 | 源码／实时预览上下距 20／16 dp，行高 30.4／29 sp | 上下距统一 10 dp，行高 26／27.2 sp；字号保留 16／17 sp 并随设置缩放 |
| 编辑展开工具面板 | 52 dp | 48 dp，主工具与按钮保持原有 48 dp 点击区域 |

封面仍为左侧 60 × 66 dp 与阅读 2.4:1 横幅，正文标题与字号、A/B/C 颜色主题保留。原文空行、Markdown／YAML、自动保存、草稿、撤销／选区、输入连接与系统 Insets 沿用现有机制；短文的剩余编辑画布继续支持点击定位与输入。设置等页面不在本次调整范围。

阅读排版缓存环境由 `reading-3` 换为 `reading-4`，使旧几何占位及排版派生结果不影响新行距。正文投影、权威存储、状态库 v3 和备份格式不变，无新增模块或依赖。

## 电脑预览与复现

本轮独立目录：`D:/My_Elio/Life-Archive-Deliveries/compact-layout-20261031/`，不覆盖上轮封面交付目录。

- `index.html`：首页浅深、两篇真实入库笔记的阅读三主题、源码／实时预览编辑三主题及封面面板。
- `overview.png`：首页、阅读正文与源码编辑三页速览。
- `comparison.html`：三页调整前后并排。`before/` 是改动前已经固定的截图副本，后续检查不重写它们。
- 编辑预览含工具面板展开、键盘占位／收起；占位高度不是实际输入法测量。阅读为正式 reader.js／reader.css 的电脑 Chrome 渲染，不包含 Android 顶栏／系统栏；首页与编辑为正式 Compose 的 Robolectric 渲染。

Windows 10 x64、PowerShell 7、JDK 17，沿用本机已有 SDK／Node／Chrome：

```powershell
$env:LIFE_ARCHIVE_COVER_PREVIEW = 'D:/My_Elio/Life-Archive-Deliveries/新的紧凑预览目录'
$env:LIFE_ARCHIVE_COMPACT_PREVIEW = $env:LIFE_ARCHIVE_COVER_PREVIEW
.\gradlew.bat :feature:notes:testDebugUnitTest :feature:editor:testDebugUnitTest :app:testDebugUnitTest testReader -x :feature:editor:installReader --console=plain
node reader/capture-covers.mjs --fixtures $env:LIFE_ARCHIVE_COVER_PREVIEW --compact
.\gradlew.bat verifyPhaseOne -x :feature:editor:installReader --console=plain
git diff --check
```

源码编辑截图通过现有 `PrototypeVisualTest` 输出；两篇笔记仍由 `NoteCoverPreviewTest` 在隔离真实库中创建后关闭重开。比较页需先在新目录的 `before/` 放入本轮说明的旧截图，常规截图不依赖旧资料。

## 验证与产物

相关回归已通过：2m 13s，184 个任务，24 执行、160 复用；覆盖首页列表锚点、长筛选／末条浮钮避让、编辑输入／选区／工具操作和 38 项正式阅读浏览器检查。已回读三页及三主题电脑预览。

- 完整 `verifyPhaseOne -x :feature:editor:installReader --console=plain` 通过：1m 10s，539 个任务，50 执行、489 复用。架构检查、Debug App／仪器测试 APK 构建、Release Kotlin 编译通过；设备仪器测试没有运行。
- 当前 JUnit 共 230 项，失败／错误／跳过均为 0；正式阅读浏览器 38 项通过。复用任务保留其既有有效结果，不表示最后一条命令重新执行全部测试，NO-SOURCE 不计作测试。
- 七个 Android 模块 Debug Lint 无错误；警告沿用封面轮次：app 36、editor 1、notes 2、designsystem 1，其余 0。本轮没有通过屏蔽或升级依赖处理警告。
- 已核对 UTF-8、Markdown 相对链接、尾部空白和 `git diff --check`。完整日志 `verifyPhaseOne.log`，原始 JUnit／Lint XML 在 `reports/`，汇总在 `test-summary.json`；截图清单在 `preview-manifest.json`。

固定 APK：`D:/My_Elio/Life-Archive-Deliveries/compact-layout-20261031/Life-Archive-compact-layout-debug.apk`。

- 包名 `com.elio.lifearchive`，仍为 0.2.5／versionCode 7，应按哈希区分上一轮包。
- 66,099,106 字节；SHA-256：`439eb9430cbaebd26a9006a68015070584a17cac659c0862e67518e335014a3e`。
- `apksigner verify --print-certs` 成功；证书 SHA-256 为 `baef356dff648a2d92863b2309e67ac604e71935fa71b48d493f5dc466dccb54`，与上轮一致，输出保存在 `apksigner.txt`。
- `delivery-manifest.json` 固定 APK、基准提交和未提交源码哈希，并列出相对上一轮封面交付实际变化的文件。以上为本机产物路径，不作为跨机器路径承诺。

设备交互／视觉未验证：真实手机输入、键盘 Insets、系统栏和实际 WebView 显示仍需设备验收，电脑截图不能代替。没有操作模拟器、安装／卸载或清库；历史手动通过项目不默认重测。

## 本地提交

用户后续明确要求提交 Git，保持 main，源码与对应测试按功能分批提交，未推送：

- `cdad214`：封面领域模型、状态库 v3 迁移、持久化与备份兼容及数据回归。
- `5c75c1c`：预设／自定义封面、首页左图、阅读横幅、统一设置面板、四张资源与界面／导入回归。
- `bda901e`：三页紧凑布局、阅读排版缓存换代和前后预览导出。
- 文档、设计参考图及验证交接另作一个文档提交，最终哈希以 Git 日志为准。

提交前核对工作区源码与已验证交付清单一致，分离重叠界面改动时核验上一轮封面版本哈希；源码没有因提交而重写。交付目录中的 manifest 保留打包时的基准提交与未提交状态含义。
