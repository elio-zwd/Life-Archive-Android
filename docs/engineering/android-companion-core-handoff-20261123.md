# 宠物核心与公共 AI 接入交接

更新：用户随后提供了 SDK，原生接入与最新结果见[原生交接](android-companion-native-handoff-20261123.md)。以下保留 02:15 首批文字版交付事实与证据，其中缺少 SDK 的结论已被后续接入替代。

日期：2026-11-23。状态：核心首批代码已接入，尚未完成整个[宠物计划](../plans/android-companion.md)。原生 Live2D 尚缺 Cubism SDK for Java Core，系统悬浮和真实远端模型尚未设备验收；当前文字界面不代表原生模型样板通过。没有修改其他仓库；后续已按用户授权分类提交到本地 main，尚未推送。

## 实际实现

| 部分 | 接入内容 |
| --- | --- |
| 公共 AI | `domain:ai`／`data:ai` 拥有唯一配置、加密 Key 池、冷却与重试规则；见域兼容入口与宠物实际共用同一实现 |
| 宠物领域 | `domain:pet` 提供伙伴档案、会话、消息状态、情感枚举、关系记忆及 Repository 契约 |
| 宠物存储 | `data:pet` 使用独立 `companion.db`，初始 Room Schema v1 已导出；无破坏性重建。app 通过 Hilt 装配唯一实例，页面和服务共享它 |
| 页面 | `feature:pet` 提供陪伴、记忆、设置三页；可修改伙伴名称／人格，手工添加记忆或从对话选入，再更正／删除 |
| 宿主 | 单个可拖动按钮展开 Life／见域／伙伴菜单，各域保留导航和未发输入。宠物按需在后台线程初始化，初始化失败可返回 Life 和重试 |
| 桌面 | app 的前台 Service 提供文字窗口、拖动、展开／收起、发送／停止、打开伙伴和关闭；使用同一宠物 Repository，关闭窗口不清除会话 |

当前共二十一个实际模块，新增五模块均有真实调用方，已纳入架构检查。公共 AI 契约及部分兼容类型保留 `com.elio.jianyu.network` 命名空间，避免无关调用与持久化枚举大范围改名；源码和权威数据已归公共模块。

## 公共配置、凭据和恢复

- 公共配置使用 `shared_ai_*` 设置文件，首次从旧 `jianyu_*` 设置接管。接管标记防止后续旧包恢复或重启重新覆盖公共值；伙伴对话用途独立选模型，Key 始终共用。
- 加密 Key 仍使用既有 Keystore 别名，密文接管到 `noBackupFilesDir/shared-ai`。先原子写入公共位置，再删除旧密文；不解密导出、不写普通设置、不纳入备份。
- `AiManager` 是见域兼容入口，`AiRequestExecutor` 只适配见域预算、占用和诊断。真实重试和 Key 状态写回由公共执行器负责；见域的专用 Interactions 历史编排仍留原域。
- 宠物的通用 `AiGateway` 提供流式文本与完成事件，使用当前配置的 Gemini／DeepSeek。身份带业务域、会话和操作；公共绑定按域隔离，宠物使用稳定负数绑定，避免与见域原正数会话及语音绑定冲突。
- 停止收集取消对应 HTTP Call；取消不惩罚 Key。已经输出部分文字的失败不自动重发整条回应。只有正常终止的完整响应才能形成完成事件，截断和缺少终止标记均失败。
- 见域独立恢复／清理不再清除公共 Key 或重置公共配置。旧 `.jyfull` 的 AI 配置字段继续校验，恢复时跳过并按排除该字段后的快照核对；保留原格式兼容。见域维护先结束本域旧任务，不取消宠物任务。

共享配置的完整备份仍按计划后置。本次只适配已有恢复链路，没有新增宠物备份或联合备份事务。

## 会话、记忆与本地可靠性

`CompanionRepository` 提供初始化、档案与草稿写入、显示设置、发送、重试、取消和记忆增改删。会话及记忆都通过该接口访问，不向 feature 暴露 Room／DAO 或文件路径。

用户消息与待回应消息在同一事务落盘，操作标识用于重复提交去重；页面和悬浮入口同时发送时只接受一条正在生成的回应。重试沿用原消息身份，不重复追加用户消息。任务归 app 装配的实例，切页面不重发；进程重开后将遗留生成状态标为中断，用户显式重试。

记忆保留来源消息和修订号，更正／删除使用修订校验，过期写入不能覆盖新值。下一轮按当前人格、最近对话和现有记忆构造上下文；不读 Life 笔记或见域消息。删除关系记忆不删除原对话，原对话仍可能包含相同事实。上下文最多 24 条消息、正文合计约 32,000 字符，关系记忆最多取最近 40 条、合计 8,000 字符。

AI 输出只解析正文和受限情感枚举，额外动作字段不执行。当前记忆由用户明确添加，不声称已经实现自动反思、向量检索或主动问候。情感状态已存储，因原生模型未接入，暂未驱动实际表情动作。

## 验证入口与证据

在仓库根目录用 PowerShell 7 运行：

```powershell
pwsh.exe -NoProfile -File .\verify-companion.ps1 -Help
pwsh.exe -NoProfile -File .\verify-companion.ps1 -WhatIf
pwsh.exe -NoProfile -File .\verify-companion.ps1 -EvidenceDir D:\Temp\life-archive-companion-20261123
pwsh.exe -NoProfile -File .\tools\tests\Run-CompanionVerificationTests.ps1
```

验收脚本分三步运行数据／领域及 feature 测试、定向 app 宿主回归、架构／Lint／APK。从各任务测试 XML 汇总实际执行数量，生成仓库外 `summary.json` 和三个日志文件；记录 APK 路径与 SHA-256，设备与原生模型状态单独列明。

本轮定向测试覆盖公共配置只接管一次、密文接管、请求身份、流式完成／截断、Key 冷却、取消隔离、准备失败、禁止部分回答重发、宠物重复发送／重试、并发入口、记忆修订与删除、真实 Room 重开中断、原对话保留、三域切换、Activity 重建与未发草稿、原见域完整备份往返。测试只使用临时合成数据与假传输，不调用真实模型。

Robolectric 页面截图位于仓库外 `D:/Temp/life-archive-companion-previews/conversation.png` 和 `memories.png`，已检查文字、输入、记忆操作与底栏可见；属于电脑绘制结果，不代替手机视觉验收。脚本自身使用合成 Gradle／XML／APK 验证门禁，不能与软件业务测试混计。

设备结果见[独立清单](../testing/android-companion-manual-acceptance.md)。

### 本轮最终电脑结果

Win10 x64／PowerShell 7／JDK 17，最终完整脚本结束时间为 2026-11-23T02:15:33.1239457+08:00。架构检查、相关 Debug Lint、Debug APK 构建通过；新增 data:ai、data:pet、feature:pet 的 Lint 均未发现问题。app／见域仍有既有依赖版本及其他警告，未为此升级依赖或扩大改动。

| 检查组 | 实际测试数 | 失败／跳过 |
| --- | --- | --- |
| 公共 AI | 9 | 0／0 |
| 宠物存储 | 5 | 0／0 |
| 宠物 ViewModel | 2 | 0／0 |
| 见域数据与备份回归 | 39 | 0／0 |
| 见域领域回归 | 44 | 0／0 |
| 定向 app 宿主与原完整备份 | 14 | 0／0 |
| 软件测试合计 | 113 | 0／0 |

验收脚本自身另有 13 项合成断言通过；帮助、预演、UTF-8、本地链接及差异空白检查通过。电脑截图回读通过，不据此认定真实手机视觉通过。

证据目录：`D:/Temp/life-archive-companion-20261123-final/`，含 `summary.json`、`data-tests.log`、`host-tests.log`、`build-lint.log`。APK 仍沿用当前源码版本 `0.5.0-dev.2`（versionCode 12），本轮未改版本号；产物为仓库根目录下 `app/build/outputs/apk/debug/app-debug.apk`，SHA-256：`E6B857562CD557C1207EB938337BCC19DD3C53BC5DC13BB9429AB3EB59829DC1`。

本轮没有安装应用、调用 ADB、连接手机或使用真实 Key；原生 Live2D 仍为 `BLOCKED_MISSING_CUBISM_JAVA_CORE`，桌面窗口和真实远端服务等设备项保持 `NOT_VERIFIED`。整个宠物计划尚未完成。

## 剩余工作与接手顺序

1. 用户确认目前没有 Java Core SDK。先由用户从[官方下载页](https://www.live2d.com/en/sdk/download/java/)取得 SDK，再核对许可、固定版本、Core JAR 和目标 ABI；不从非官方镜像补二进制，也不采用 WebView 替代已选原生方案。
2. Hiyori 仅作技术样板，正式形象后选。本轮没有复制或重新分发模型资源。SDK 就绪后才建立有实际调用的 `core:live2d`，完成透明模型、触摸、动作、线程／Surface 生命周期和两种窗口承载。
3. 使用隔离资料在小米 14 Ultra 定向验证三域、模型与桌面权限／通知／后台行为、真实服务返回、功耗与内存；手机操作须在实际授权范围内进行。
4. 语音、主动问候、成长收藏和自律辅助留后续逐项细化。新增备份最后实施；不要因缺少宠物备份重新阻塞已完成的核心开发。
