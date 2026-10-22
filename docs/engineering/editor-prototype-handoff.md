# 一期首轮：编辑器交互样板交接

日期：2026-10-18。状态：代码已接入，交付用户手动安装体验；尚未通过小米 14 Ultra 输入、性能或视觉验收，不代表一期完成。

## 1. 入口与模块

启动后进入真实笔记列表，使用既有 `filesDir/library` 运行实例；不会自动创建示例资料。点击“创建验收样例”才通过 Repository 导入合成 PNG 并创建代表性 Markdown。原 Debug `foundation-demo` 示例已退出启动流程，未删除旧资料。

新增 `feature:notes`、`feature:editor`，共九个模块。app 装配 Navigation 3、ViewModel 工厂和系统生命周期；feature 不依赖 data 或其他 feature，复用原 StorageGate、Repository、设置与备份。没有第二套笔记存储，没有改变状态库 Schema 或备份格式。

列表本轮只显示真实名称和路径，不伪造摘要或时间；提供先命名创建、取消与失败反馈。搜索、笔记本/标签、重命名、附件选择、回收站、正式导入导出和备份恢复 UI 在样板验收后继续实现。

## 2. 编辑、保存与接口

- `EditorSession` 持有同一个 Compose `TextFieldState`。源码与实时预览共享原文、选区、组合输入和撤销历史；切换阅读按原文位置定位。
- 实时预览候选使用现有 Foundation 1.10.5 的 OutputTransformation，不升级工具链。标记只在显示层隐藏；激活行及组合输入/选区覆盖的行保留语法上下文。
- 超过 128 Ki 字符的长文只在光标前后各 4096 字符应用实时样式，界面明确提示；完整正文仍可编辑和保存，阅读页仍按块显示全篇。电脑控件测试发现全篇大量样式的排版成本过高，因此采用这个样板默认值，目标机性能仍待验证。
- `MarkdownParser` 使用 CommonMark Java 0.30.1，以及同版本表格、删除线扩展，后台生成投影，不反向序列化正文。库的支持声明不等于 Obsidian 兼容验收。
- `EditorPersistence` 在输入后 300 ms 防抖写草稿，800 ms 防抖提交正文；不取消已开始的提交。版本与草稿修订号随快照提交；旧回执不回填编辑框，较新输入用新版本/修订号保护。
- 返回列表、切换阅读、进入后台触发持久化。离开前核对最后输入；正文与草稿均未落盘时阻止离开。正文失败但草稿安全时，重开可确认恢复。
- 有不同于正文的草稿时，默认阅读已保存正文。相同版本由用户确认恢复；旧版本草稿可另存为新笔记，原笔记与原草稿均保留，不自动覆盖或丢弃。
- 大正文不写入 Activity Bundle。进程重建通过导航 ID、实际文件和独立草稿恢复；只能承诺已落盘内容，不保证强制终止前最后 300 ms 的按键必然恢复。

新增 Repository 的 `readReadingOffset` / `updateReadingOffset`，复用已有 `readingOffset` 字段和共享门锁；负值返回 `InvalidPosition`。位置精度为首个可见阅读块的原文偏移，更新位置不改变正文版本；已有逻辑备份包含该字段。

## 3. 展示支持清单

| 内容 | 本轮状态 |
| --- | --- |
| 标题、段落、强调、删除线、列表、引用、代码 | 有阅读展示及常见编辑样式；代码不执行；实时预览仍为候选 |
| 任务清单 | 阅读显示勾选符号；文本或工具栏编辑，没有阅读态点选切换 |
| 表格 | 阅读横向滚动；编辑使用 Markdown；单元格内样式暂按文本展示 |
| Callout | 基础标题与内容样式；复杂嵌套及折叠交互待完整兼容轮次 |
| YAML | 阅读默认折叠原文；完整源码编辑，未知字段不重写；实时预览中仍可见属性源码 |
| 图片 | 库内图片读取和采样解码；样例 PNG 经 Repository 入库；样板解码输入上限 16 MiB，不获取网络图片 |
| 链接 | 基础样式与明确的尚未接入提示；内部链接解析/跳转、系统打开后续实现 |
| 双链、块/标题引用、复杂嵌入、公式、Mermaid、HTML、脚注等 | 保留原文，部分块标注暂以源码展示；不计为阅读兼容完成 |
| 原文保真 | 不从渲染结果回写；覆盖 BOM、CRLF、未知字段、特殊语法及长文样例 |

文件名、正文 H1 与 YAML title 独立。源码/实时预览的选区与撤销历史保留；阅读位置当前按块恢复，不承诺像素级滚动位置或跨进程撤销历史。

## 4. 电脑验证与交付

Windows 10 x64、PowerShell 7、JDK 17、既有 SDK 36：

```powershell
.\gradlew.bat verifyEditorPrototype --console=plain
```

调用扩展后的 `verifyFoundation`：原底层回归、两个 feature 的测试/Lint、app 输入/导航/视觉测试、Debug APK 与仪器测试 APK 构建、Release Kotlin 编译；不安装 APK、不调用 ADB。

最终工作区复核：**PASS，53 秒，466 个任务中 48 个执行、418 个 up-to-date**。任务数不等于测试数；未变化模块复用有效缓存结果。测试报告合计 **46 项，0 失败、0 错误**：core:document 3、core:backup 6、core:preferences 1、data:notes 9、feature:editor 16、feature:notes 1、app 10。设备仪器测试只构建未执行。

六个 Android 模块 Debug Lint 均 0 错误；app 有 26 条警告（25 条版本/target 更新提示及 1 条 Bitmap KTX 写法建议），其余五个模块无警告。没有添加 baseline 或关闭检查。Debug/仪器测试 APK、Release Kotlin 编译通过；UTF-8、本地文档链接、尾部空白及 `git diff --check` 通过。

测试覆盖保存期间继续输入、失败/重试、草稿确认恢复、版本冲突、禁止不安全离开、撤销回原文后的保存状态、取消命名不创建文件、格式选区/撤销、原文保真、阅读位置重开/备份、Activity 重建，以及不小于 50 KiB/1 MiB UTF-8 体量的文本控件输入与模式切换。中英混排长文按字符数构造，实际 UTF-8 字节量大于标称基线；不把电脑测试耗时当成真机性能结果。使用临时目录及合成资料。

`PrototypeVisualTest` 用 Robolectric Native Graphics 生成浅深色列表、阅读、编辑及阅读详情 PNG，输出至 `%TEMP%/life-archive-editor-preview/`。键盘为明确标注的占位，不能代表真实候选词、选区菜单或小米系统行为。所有页面使用同一份合成笔记。

APK：`app/build/outputs/apk/debug/app-debug.apk`，仅 Debug 体验包；未进行 Release 签名交付。日志和预览保存在仓库外。

交付目录：仓库同级 `Life-Archive-Deliveries/editor-prototype-20261018/`，包含 `LifeArchive-editor-prototype-debug.apk`、`preview.html`、八张浅深色 PNG、验收说明和最终验证日志。包名实际核对为 `com.elio.lifearchive`，显示名 Life Archive，min/target SDK 26/36。

APK SHA-256：`a3c151b3d77157556f736f1cd04d3d1183fd36f30afbe8da160b60a57a34ca9e`。

样板首次交付时保留 main，尚未暂存、提交或推送；未连接、安装或操作手机。当时完整一期、目标机 IME/交互/性能/视觉、Release 签名、完整 Obsidian 阅读兼容均未验收；后续实现与提交状态见[一期功能交接](phase-1-function-handoff.md)。

## 5. 小米 14 Ultra 手动验收

记录 Android/HyperOS、输入法名称与版本；手动保留数据覆盖安装，不卸载、不清数据。只用新建验收笔记测试。

| 操作与预期 | 当前结果 |
| --- | --- |
| 新建后取消不产生文件；非法/保留名/大小写重名不覆盖旧文件，失败保留名称 | NOT_VERIFIED |
| 新建后进入编辑并弹键盘；已有笔记默认阅读不弹键盘 | NOT_VERIFIED |
| 拼音组合、选词、中英文切换、长按选区、复制剪切粘贴，无吞字/重复字/跳光标 | NOT_VERIFIED |
| 工具栏格式、列表、待办、展开面板、撤销/重做，文本与选区合理 | NOT_VERIFIED |
| 长文滚动、键盘收起/弹出、横竖屏变化，当前段落可见 | NOT_VERIFIED |
| 三模式切换，YAML 自定义字段、复杂语法及正文不因切换丢失 | NOT_VERIFIED |
| 等“已保存”后返回、重开、结束应用后重开，内容正确，阅读恢复至对应块 | NOT_VERIFIED |
| “创建验收样例”后查看引用、Callout、表格、代码、图片；重启后图片仍可读 | NOT_VERIFIED |
| 编辑页“更多”启用正文保存失败模拟，再输入并等失败提示；结束应用后重开，确认恢复草稿后能继续保存 | NOT_VERIFIED |
| 检查浅深色主题正文、链接、代码、引用、表格、工具栏；记录难辨认的位置 | NOT_VERIFIED |

Debug 故障模拟仅影响当前会话的正文提交，不破坏系统磁盘或已有文件；关闭开关或重建进程后恢复正常，不能代替真实磁盘失败/断电验证。

收到设备反馈后先修复关键输入与保存问题，再决定是否正式采用实时预览。不达标时采用源码编辑加阅读预览继续一期；完整 Obsidian 阅读兼容要求不减少。
