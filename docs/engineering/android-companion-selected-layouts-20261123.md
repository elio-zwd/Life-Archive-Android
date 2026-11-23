# 伙伴选定布局实施与验收

日期：2026-11-23。以用户后续选型为准，沿用既有记忆 B 卡片，补齐陪伴、设置、两类悬浮及记忆／人格编辑。设计比较板见[方案目录](../design/companion-candidates-20261123/README.md)。

## 实际行为

| 页面／状态 | 当前实现 |
| --- | --- |
| 陪伴 C | 实际 `CompanionAvatar`／`Live2DView` 连续渲染，占内容区约 68% 高度；下层对话面板约 48%，覆盖形象下缘。保留触摸反馈、动作及同一回复驱动的情绪提示。收起、键盘打开或可用高度不足时让出输入空间；模型按既有生命周期释放／暂停。 |
| 伙伴设置 B | 身份卡片显示真实名称、人格摘要和原生形象；名称／人格可修改。App 内显示、桌面陪伴分别放入卡片，公共 AI 保留唯一配置入口。长人格可在编辑表单完整查看。 |
| 记忆／人格编辑 B | Material 3 底部弹层，内容可滚动，保留取消与保存。未保存输入可随 Activity 重建恢复，记忆还保留 ID、来源与预期修订。保存成功后关闭，失败留在表单重试；记忆提交由 ViewModel 确认，同次提交不会因旋转后再次等待而重复新增。 |
| 悬浮正常 A | 轻量透明模型、短气泡、拖动与对话／收起。App 内收起隐藏小窗；系统正常状态的收起停止桌面服务，通知仍提供停止桌面陪伴入口。 |
| 悬浮对话 B | 原窗口展开为贴边形象与输入面板，保留同一会话与草稿。可以发送、停止、结束对话或回到伙伴；系统窗口还可直接打开 App／关闭。展开与拖动结束时靠边，窗口变化时约束位置。 |

结束对话／收起输入面板返回轻量状态，隐藏／关闭窗口不主动取消已提交的回复；取消生成使用“停止”动作。记忆删除确认继续采用原确认对话框，已选底部弹层用于记忆新增／更正与名称／人格编辑。

大形象使用与悬浮相同的 Cubism 承载能力，不使用角色静态截图替代动画。Hiyori 仍为技术样板，未选定正式角色；本次不引入比较板中的生成角色和场景图片。App 内与系统窗口有各自模型／Surface，继续由共用绘制线程／EGLContext 协调。

App 内小窗和伙伴页共用 Activity 的伙伴 ViewModel、Repository 与草稿。系统窗口从同一 Repository 观察状态，草稿串行保存，程序回填不触发另一次写入；较早回执不覆盖正在输入的本地草稿。发送与取消仍经原任务协调，不另建聊天或 AI Key。系统窗口未完成资料初始化时禁用输入；主动关闭／通知停止先等待已排队的草稿写入。系统杀进程后仍以已持久化状态为准，不承诺未提交表单或进行中的业务持续运行。

从小窗或系统入口“回到伙伴”会明确打开陪伴标签，而不是只切换到上次停留的设置页；普通三域切换仍保留各域页面。保留现有可拖动三域菜单、Life／见域底栏、记忆 B 卡片、唯一公共 AI 配置和全 App 三套配色。公共 AI 页面的布局尚未另选，本次保留现有对话框。未新增模块、数据库版本、语音、主动互动、成长、自律或备份能力。

## 软件验证与预览

Windows 10 x64、PowerShell 7、JDK 17、固定本机 SDK 5 R5。完整入口：

```powershell
pwsh.exe -NoProfile -File .\verify-companion.ps1 -EvidenceDir D:/Temp/life-archive-companion-selected-layouts-20261123-accepted
pwsh.exe -NoProfile -File .\tools\tests\Run-CompanionVerificationTests.ps1
```

最终完整检查于 **2026-11-23 09:37:56（Asia/Shanghai）通过**：**142 项软件测试，零失败、零跳过**。架构、相关模块 Debug Lint、Debug APK 与原生设备测试 APK 编译通过；后者未上设备运行。宠物 feature Lint 无问题，app Lint 为 0 错误／49 条既有提示，原生 Framework 保留 1 条上游注释提示。未为清除旧提示升级依赖或修改外部 SDK。

| 实际测试模块 | 数量 |
| --- | --- |
| core/live2d | 9 |
| data/ai | 9 |
| data/pet | 5 |
| feature/pet | 13 |
| data/jianyu | 39 |
| domain/jianyu | 44 |
| app（定向宿主、窗口及恢复回归） | 23 |

验收脚本另通过 **18 项合成断言**，证据目录 `D:/Temp/life-archive-companion-script-tests-9b1fe9817e574deb90c55d98598cb3d1/`。完整实际运行证据为 `D:/Temp/life-archive-companion-selected-layouts-20261123-accepted/summary.json` 及同目录三份 Gradle 日志，控制台日志为 `D:/Temp/life-archive-companion-selected-layouts-accepted-run.log`。

最终 APK：`app/build/outputs/apk/debug/app-debug.apk`；SHA-256：`79778AE1571EC2B9542A7883C3E10887A6CACE53912C66A0BF74D4914F48C895`，已与报告核对。

页面测试覆盖大形象区域、叠放面板、短窗口／大字体输入、长人格卡片、弹层空值与取消、保存失败保留、未保存表单恢复、记忆修订冲突及确认任务重建；宿主覆盖真实落盘、三域切换、Activity 重建、贴边输入接续与回到陪伴标签。系统窗口 View 测试覆盖模式切换、草稿程序回填不重写、本地输入保护、尺寸／位置约束及三套全局配色。

实际 Compose 和 Android View 桌面渲染位于仓库外 `D:/Temp/life-archive-companion-previews/`，已逐张回读暖纸／浅灰蓝／深石墨页面及两种悬浮状态。每套主题包括：

- `conversation-{blue,warm,graphite}.png`：陪伴 C。
- `settings-{blue,warm,graphite}.png`：设置 B。
- `memory-sheet-{blue,warm,graphite}.png`、`profile-sheet-{blue,warm,graphite}.png`：底部弹层 B，叠加实际宿主窗口与弹层窗口绘制。
- `in-app-idle.png`、`in-app-dialogue.png`：App 内轻量 A／对话 B。
- `system-idle-{blue,warm,graphite}.png`、`system-dialogue-{blue,warm,graphite}.png`：系统窗口视图的离屏绘制，不是已授权悬浮服务截图。

快速查看：[陪伴 C](D:/Temp/life-archive-companion-previews/conversation-graphite.png)、[设置 B](D:/Temp/life-archive-companion-previews/settings-warm.png)、[记忆弹层](D:/Temp/life-archive-companion-previews/memory-sheet-warm.png)、[人格弹层](D:/Temp/life-archive-companion-previews/profile-sheet-warm.png)、[App 内轻量](D:/Temp/life-archive-companion-previews/in-app-idle.png)、[App 内对话](D:/Temp/life-archive-companion-previews/in-app-dialogue.png)、[系统轻量](D:/Temp/life-archive-companion-previews/system-idle-warm.png)、[系统对话](D:/Temp/life-archive-companion-previews/system-dialogue-warm.png)。

Robolectric 不能在 Windows 加载 Android Cubism JNI 并渲染模型，预览中的模型区域透明留空，没有合成假角色充当运行证据。旧手记卡片交接中记录的 432dp 原生图形弹窗 `AppNotIdleException`，在本次新底部弹层的上述重建与实际预览检查中未复现；这仍不能替代手机输入法及弹层行为验收。

## 设备边界

本轮不安装 App、不调用 ADB、不操作真实资料、不使用真实 Key 发起生成。大形象与两类悬浮的实际动画／透明合成、触摸动作、锁屏与 Surface 重建、键盘避让、横竖屏／分屏、系统悬浮权限与通知停止、帧时间／内存／功耗均为 **NOT_VERIFIED**。应按[设备清单](../testing/android-companion-manual-acceptance.md)在小米 14 Ultra 定向验收；APK 构建和 View 测试不代表这些行为通过。
