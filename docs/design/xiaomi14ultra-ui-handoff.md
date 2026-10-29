# 小米 14 Ultra UI 实施交接

日期：2026-10-30。用途：将当前 HTML 视觉与交互移植到 Life Archive Android；本轮没有修改 Android UI，也没有操作手机。

## 使用顺序与交付内容

先读仓库 `AGENTS.md`、本文件及 [HTML 交互样板](prototypes/home-a-xiaomi14ultra.html)，再按截图检查各页面。HTML 与当前导出的截图共同构成设计参考；交互含义优先查 HTML 与[已确认决策](../plans/phase-1-editor-visual-decisions.md)，不要从静态截图猜测数据或保存行为。

交付目录包含独立可打开的 `source/home-a-xiaomi14ultra.html`、`screenshots/` 原始 PNG、`index.html` 截图索引、`overview.png` 三主题速览、`design-tokens.json` 颜色值及 `manifest.json` 尺寸／区域／文件校验信息。截图全部来自实际 HTML 的同一份源文件；清晰原图是页面实施依据，速览图仅方便比较。

## 设备与单位

| 项目 | 本次约定 |
| --- | --- |
| 官方屏幕 | 6.73 英寸、WQHD+ 3200 × 1440；竖屏截图为 1440 × 3200。来源：[小米官方规格](https://www.mi.com/global/product/xiaomi-14-ultra/specs/)，核对日期 2026-10-30。 |
| 画面比例 | 宽高比 9:20；横向参数通常称 20:9。 |
| HTML 设计画布 | 432 × 960 CSS 逻辑像素；精确满足 9:20。 |
| 截图输出 | 1440 × 3200 PNG；浏览器导出倍率为 10/3，直接高分辨率渲染，未把低分辨率图片放大。 |
| 桌面浏览 | 布局保持 432 × 960，外层整体缩放到窗口内。改变窗口高度不挤压正文或改变键盘比例。 |
| 实际 Android 密度 | 未读取目标机当前显示分辨率、DPI、字体缩放或 HyperOS Insets。432 是设计参考宽度，不能当作目标机实测 dp。 |

在 Compose 中使用 dp／sp 和实际可用窗口尺寸，不能将截图中的 1440 像素写成 1440 dp。可以以 432 逻辑宽度对照布局间距与层次，再在目标机当前密度下验证。不要按每个控件的绝对物理像素定位；字体与内容流式排版，工具栏靠键盘，正文占剩余空间。

状态栏 36、顶部编辑栏最小 64、格式面板 52、工具按钮 44 等数值均为 HTML 的逻辑像素。每张截图中实际区域的逻辑坐标写在 `manifest.json` 的 `regions`；物理截图坐标等于逻辑坐标乘 10/3。系统状态栏、挖孔、手势条和键盘全部是设计示意，实际 App 必须使用系统 Insets 和输入法，不额外绘制第二套系统栏或仿制键盘。

## 页面与交互要求

| 页面／状态 | 主要依据 | 实施要点 |
| --- | --- | --- |
| 首页浅／深色 | `01-home-light`、`04-home-dark` | 保留首页 A 的列表、缩略图、标签、筛选和轻量新建浮钮；首页主题与文档主题分开。 |
| 阅读三主题 | `05-reader-a/b/c` | 文件名居中缩小，正文 H1／元信息／封面／正文顺序不变；无开头 H1 时不合成标题；右下角编辑小浮钮。 |
| 阅读目录 | `06-reader-outline` | 按正文标题定位，保留阅读位置。目录面板样式可按 Android 语义实现，无需仿制浏览器对话框机制。 |
| 编辑三主题 | `07-editor-a/b/c-keyboard` | 文件名与保存状态在同一顶栏；正文连续画布；没有常驻字符数／行列栏。键盘示意只用来检视正文高度。 |
| 编辑格式面板 | `08-editor-a/b/c-format` | 互斥的单行横向面板，不恢复为原来的两行；图标按钮保留触摸面积，窄屏可滑动查看更多。 |
| 列表与插入 | `09-editor-a-list/insert` | 复用现有命令；面板切换不丢正文选区、输入法焦点或撤销历史。 |
| 长按中文提示 | `10-editor-a-longpress` | 约 500 ms 显示名称；松手不执行命令；滑动／取消／换页取消提示。保留中文无障碍名称。 |
| 三主题选择 | `11-document-theme-picker` | 阅读／编辑的更多菜单各有“主题”；选择共享并记住；首页原有跟随系统／浅／深设置独立。 |
| 编辑键盘收起 | `12-editor-a-no-keyboard` | 正文获得释放的高度，点击正文可再次输入；不要把截图内的假键盘做成业务组件。 |
| 设置浅／深色 | `02-settings-light`、`03-settings-dark` | 保留已有分区及功能。完整内容继续滚动，截图没有展示的设置不能因此删除。 |

三主题名称是 A 浅灰蓝、B 暖纸色、C 深石墨。颜色来自 HTML 的 `--page-*` 变量，具体值见 `design-tokens.json`。本次先统一文档主题与紧凑工具布局，不将早期 B／C 图片中的不同信息栏结构混入当前实现。默认新数据以 A 为主；旧 HTML 没有独立文档主题时暂按原浅／深设置回退 A／C，明确选择后独立保存。

标题按钮保留单击加深、双击减浅；本次最新约定将长按统一为中文提示，标题级别选择从编辑页更多菜单进入。Android 旧版本的“长按选择标题级别”需要在正式 UI 移植时按这个明确的新决定调整。

HTML 的清除行内格式只覆盖完整、可安全确认的简单标记，是样板能力边界。Android 应使用现有 Markdown 命令／文档区间机制；不要把浏览器的简单正则或 textarea 编辑历史替换进正式编辑引擎。

## Android 代码入口及保护边界

| 职责 | 当前入口 |
| --- | --- |
| 首页 | `feature/notes/src/main/kotlin/com/elio/lifearchive/feature/notes/NotesScreen.kt` |
| 阅读与编辑 | `feature/editor/src/main/kotlin/com/elio/lifearchive/feature/editor/EditorScreen.kt` |
| 编辑工具 | `feature/editor/src/main/kotlin/com/elio/lifearchive/feature/editor/EditorToolbar.kt` |
| 设置 | `feature/settings/src/main/kotlin/com/elio/lifearchive/feature/settings/SettingsScreen.kt` |
| 主题基础 | `core/designsystem/src/main/kotlin/com/elio/lifearchive/core/designsystem/Theme.kt` |
| 页面装配 | `app/src/main/kotlin/com/elio/lifearchive/ArchiveApp.kt` |

这里只列出入口，正式实现前按仓库规则读取源码及 ADR-001。保留模块依赖方向，不为 UI 移植引入新的编辑器、WebView 页面、数据层或截图组件。

- Markdown 原文、未知 YAML、文件名与标题独立关系均保持。300 ms 草稿、800 ms 保存、失败恢复、版本冲突、读写入口与备份协议不能因视觉调整失效。
- 正式源码／实验实时预览共享现有会话、选区与撤销历史；保留两种模式的高亮和已有性能机制，不能从显示结果反向重建 Markdown。
- 文档主题是需要记忆的偏好，正式接入时按 `core:preferences` 的持久化与备份参与约定扩展；不要把浏览器 localStorage 当作 Android 设置实现，也不要只改显示却漏掉重启／恢复路径。
- HTML 的缓存、示例封面、导入导出和草稿都是浏览器演示。正式 App 沿用已经实现的附件、备份、完整阅读及存储能力；截图没有出现不等于删除功能。
- 系统字体、百度输入法高度、软键盘动画和系统复制菜单以目标机为准；浏览器模拟触屏不等于真机输入、性能或最终视觉验收。

## 复现截图

Windows 10 x64、PowerShell 7、现有 Node.js／Playwright 和 Chrome 或 Edge。在仓库根目录运行：

```powershell
node reader/capture-design.mjs --help
node reader/capture-design.mjs --out D:/My_Elio/Life-Archive-Deliveries/新的空目录
node --test reader/test/design-prototype.test.mjs
git diff --check
```

脚本只启动本机临时 HTTP 服务和隔离的无头浏览器，不访问个人浏览器数据或手机。输出目录必须为空，避免覆盖旧交付。可用 `LIFE_ARCHIVE_BROWSER` 指定已安装浏览器；不自动下载或安装依赖。

HTML 普通桌面预览默认使用固定设备画布；`?capture=1` 使用无外框截图模式，`?responsive=1` 保留通用浏览器适配。末尾 `#editor` 直接打开第一篇示例笔记编辑。

## 可交给另一个 AI 的任务说明

> 请先阅读仓库 AGENTS.md、ADR-001 和本 UI-HANDOFF，再检查交付包的 HTML、design-tokens.json、manifest.json 以及原始截图。将已确认的首页、阅读和编辑视觉移植到现有 Kotlin／Compose 页面，保持既有本地笔记、完整阅读、源码／实验预览、保存恢复、附件、搜索、导入导出和备份能力。阅读／编辑共享 A 浅灰蓝、B 暖纸色、C 深石墨三主题，首页外观独立；编辑展开面板改为单行图标工具，长按只提示中文名称，保留选区和撤销。以 432 × 960 为设计参考、1440 × 3200 为截图规格，实际布局使用 dp／sp 与系统 Insets；不要绘制截图中的系统状态栏、挖孔、手势条或假键盘。只修改移植所需内容，先完成电脑构建与回归，再报告小米 14 Ultra 真机待验收项。不自动提交、推送、清库或操作手机。
