# 职场与生活视频提示词优化

日期：2026-11-01。基于实际点点复制反馈和用户最新确认，更新四类内置提示词；[当前范围与完整规则](../plans/phase-1-video-notes.md#复制提示词)。不改变笔记存储、主题、提示词类型或备份格式，不覆盖已有自定义提示词。

## 本轮调整

- 完整内置文本均在 1000 字符内，含格式示例与本类要求；加入视频 source 后：通用摘记 964、操作技巧 992、知识讲解 994、选择与避坑 999；这不是生成笔记的字数上限。用户自定义文本既有存储上限保留。
- 输出改为单个四反引号 markdown 包装，方便复制源码，YAML 分隔线、title、tags 分行，tags 固定为行内列表。复用现有粘贴拆包装及 YAML 解析逻辑，不修改解析器。
- 删除 API、函数、代码文件等课程专用要求，仅保留加粗、少量高亮、段落、列表及必要表格等通用阅读规则。
- 职场覆盖面试、简历、说话技巧、汇报和工作工具；生活覆盖养生、周末活动、生活技巧和美食做法。四类按概括、步骤、解释、判断选择的职责区分，不新增分类。
- 标签保持 AI 自选 2～4 个大类，优先 2 个，常用标签仅为示例，不是固定清单。保留关键场景、参数及例子，不编造业绩、经历、费用、地点或确定疗效。
- 用户追加视频链接：四类 YAML 示例增加 `source: ""`，有已确认的原视频／分享链接时填完整 URL，无链接留空，不猜测、拼接或重复写到正文。解析器不变，已有未知字段保留机制负责保存。

## 验证与交付

加入 source 前的文案更新执行 `checkArchitecture :feature:notes:testDebugUnitTest :app:testDebugUnitTest --tests '*VideoNotesNavigationTest' :app:assembleDebug`，PASS，耗时 1 分 21 秒，204 个任务中 15 执行、189 复用。notes 36 项与 App 视频导航 5 项，共 41 项，0 失败／错误／跳过；架构检查、Debug APK 构建及导出包 v2 签名验证 PASS。UTF-8、文档链接与 git diff --check PASS。未将 NO-SOURCE 计作单测通过，未重跑全套 Lint、Release 或全部一期验收。

源码文本长度逐类核对，测试同时校验 1000 字符上限、四反引号包装约定、格式示例的文件名与 YAML 标签解析；沿用自定义提示词保留、逐类恢复默认、复制、旋转重建、粘贴确认与真实保存的回归。

可复制文本及更新包导出到 `D:/My_Elio/Life-Archive-Deliveries/phase-1-video-prompts-20261101/`。使用现有 App 时可从四份 txt 复制到对应类型并保存；安装更新包后可逐类“恢复默认”采用新版，已有自定义不会自动替换。

更新包：`Life-Archive-0.2.6-video-prompts-debug.apk`，版本 0.2.6／8，包名 `com.elio.lifearchive`，SHA-256：`54D3CBAB2E380C0C35D5B926D2ED7461F1995F0FC9846F82810A410D9F8324FB`。同目录含四份中文命名 txt、使用说明与本轮构建日志。未提交、未推送。

实际点点输出与手机复制效果未验证；本轮不连接手机，不自动安装。

加入 source 的本轮执行 `:feature:notes:testDebugUnitTest :app:testDebugUnitTest --tests '*VideoNotesNavigationTest' :app:assembleDebug` PASS，58 秒，203 个任务中 14 执行、189 复用。notes 36 与 App 视频导航 5 项均 0 失败／错误／跳过；未重跑与文案修改无关的全仓检查。测试增加空 source 格式及含查询参数的完整链接，在拆除外层包装后保持原文、文件名与标签不变。UTF-8、文档链接、差异检查及 APK v2 签名验证 PASS；点点和设备仍未实测。

最新交付：`D:/My_Elio/Life-Archive-Deliveries/phase-1-video-source-20261101/`，含四份提示词 txt、使用说明、验证日志与 `Life-Archive-0.2.6-video-source-debug.apk`。版本仍为 0.2.6／8，SHA-256：`4EFC93023753BEEBFAF35F2C7CEC37BE7978481AE3122D4D2828FB7845BDA0EF`。
