# 二期完整迁移与独立备份交接

2026-11-14：完整 `.jyfull` 导出、预览、恢复和维护换代已接入开发代码，电脑定向回归、架构、Lint 与开发包构建通过。二期尚未完成小米 14 Ultra 验收；没有操作旧手机数据。

## 用户入口与覆盖范围

Life 首页点击悬浮“见域” → 我的 → 备份与恢复。默认页面提供“创建完整见域备份”和“迁入或恢复见域”；历史有限 `.jybak` 与设备快照保留在单独管理入口，不能当作完整迁移包。

旧见域 `com.elio.jianyu` 的受控导出入口为“我的 → 备份与恢复 → 完整导出供 Life 迁入”。导出前先保存输入、停止生成；导出仅处理已保存资料，旧 App 的数据库、文件和凭据保留。安装兼容性、实际手机导出及迁入仍待单独验收。

支持冻结的 Room v18 和 38 个业务表、用户音频／头像与六组可迁设置，详见[覆盖契约](phase-2-complete-transfer-contract.md)。来源基线为 `AI-Skill-Roundtable` 的 `2d47446`，完整导出适配单独保存在来源仓库。API Key、设备密钥、设备快照、诊断、派生缓存和宿主位置不迁移。内置 Skill／知识资源沿用本次同源 APK 的资源，不承诺兼容未知未来版本。

Life 设置入口明确标为“Life 笔记备份与恢复”。两域的导出、恢复与清理分别限定自己的目录、数据库和偏好，没有统一备份包。

## 格式与失败保护

- 魔数 `JYFULL01`，格式版本 1，Room v18／固定 Schema 身份；Argon2id 与流式 AEAD 使用固定参数。ZIP 结束后继续读取密文至 EOF，完成末段认证。
- 清单包含逐表数量、来源、文件相对路径、大小与 SHA-256。逐文件流式处理；单文件上限 2 GiB、总量 8 GiB、文件数 20000，不把全部音频读入内存。
- 解密暂存后核对实际表／索引 SQL、完整性、外键、数量、设置类型和附件。越界／重复路径、符号链接、额外触发器、错误密码、截断、缺文件和未知结构停止操作。
- 取得快照前关闭见域消费者、checkpoint 和 Room，复制独立副本；普通备份保留内容 epoch 和未发送输入。暂存库转换旧音频路径并 VACUUM，保留 ID 与自增序列，避免已删除正文随 SQLite 空闲页进入包。
- 已提取正文的外部 URI 保留来源说明并明确授权不迁移；只有 URI 而无正文的有效资料阻止完整导出。原记录已标为 missing／failed 的音频保留状态并列出说明，不冒充文件已恢复。
- 已有目标内容必须先创建并回读验证当前完整备份，再确认替换。后端核对预览、备份与提交时的内容指纹，期间有新增内容则停止，不自动合并或重编号。
- 替换日志持久化旧数据库、设置、用户文件与 epoch。重开验证后先标记提交、清除见域凭据，再标记 finalized；进程恢复不会重复清掉后来重配的 Key。提交前中断回滚旧数据；日志损坏保留文件并停止打开见域。
- 恢复／清理轮换 epoch 并释放旧运行会话，旧 Repository 写入被拒绝。清理发生部分失败时明确报告；不能把数据库已清空等同所有硬件密钥清理成功。

## 已有电脑证据与限制

测试仅使用 Robolectric 合成目录、假 ContentProvider 和合成密码，没有网络请求、ADB 或用户资料。

- 格式／数据库／设置／替换测试覆盖约 10 MiB 合成音频、密码与篡改失败、路径与符号链接、全部 38 表结构、旧式无议题会话、自定义角色、两类音频路径、正文和 ID、自增、设置类型，以及七个替换中断点与提交后补清理。
- 旧见域真实运行实例生成合成完整导出，原会话、音频、用户文件及合成凭据保留。输出作为 Life 数据模块的独立互通夹具，不能用 Life 自己的写入器替代。
- 真实 Life `MainActivity` 装配测试覆盖完整备份、非空目标先备份、恢复与清理：普通备份后未发送输入保留；替换／清理后旧输入清空、旧会话拒绝写入；Life 笔记正文不变。Life 自身备份恢复保持同一个见域运行会话。
- 页面测试检查密码二次确认、替换风险确认、当前备份门槛、操作期间禁用、切换预览后重新确认。
- 真实 Room 候选重开后注入校验失败，见域不公开不可用实例；显式重试先回滚尚未确认的替换日志，再开放旧数据。联合宿主测试显式关闭每个用例的运行实例，避免跨 Robolectric 临时目录复用。

最终检查日志：仓库外 `D:/Temp/life-phase2-port/phase2-complete-final-2.log`，构建成功，用时 4 分 26 秒。`checkArchitecture`、见域 data／feature、Life 设置及 app 的 Lint 和 Debug APK 通过。实际执行或有效复用已通过结果的测试：见域 domain 44、data 38、feature 5；笔记 data 65、同步 data 12、设置 feature 4、app 定向 23，共 **191 项，失败 0、跳过 0**。来源 App 的完整导出定向测试另为 9 项通过；日志 `source-transfer-final.log`，最后页面可用性调整构建 `source-transfer-ui-final.log` 通过。无源码任务未计入测试数量。

```powershell
./gradlew.bat checkArchitecture :domain:jianyu:test :data:jianyu:testDebugUnitTest :feature:jianyu:testDebugUnitTest :data:notes:testDebugUnitTest :data:sync:testDebugUnitTest :feature:settings:testDebugUnitTest :app:testDebugUnitTest --tests '*CompleteBackupIntegrationTest' --tests '*BusinessHost*Test' --tests '*BusinessSwitcherTest' --tests '*VisibilityLifecycleOwnerTest' --tests '*NavigationHostTest' --tests '*OrganizationNavigationTest' --tests '*ReaderHostStabilityTest' --tests '*SharedMarkdownHostTest' --tests '*FoundationRuntimeTest' :data:jianyu:lintDebug :feature:jianyu:lintDebug :feature:settings:lintDebug :app:lintDebug :app:assembleDebug --console=plain --max-workers=1
```

本轮没有修改共用阅读资源；沿用前轮已通过的 Life 阅读浏览器 64 组、消息片段 18 项及窗口 100／500 条检查，不重复全量一期验收。保留既有 API 弃用等 Lint 警告，不把零错误写成零警告。

硬件 Keystore、Android 文件选择器、实际旧资料、网络停止／重试及长列表帧率／内存没有电脑替代结论。见域片段默认拒绝本地路径，远程资源当前提供系统打开入口，内联媒体的旧版显示对照仍待核对；Life 阅读资源能力保持原适配。设备用例见[二期真机清单](../testing/phase-2-manual-acceptance.md)。

## 开发包与本地提交

| 用途 | 包名／版本 | 仓库外文件 | SHA-256 |
| --- | --- | --- | --- |
| Life 二期开发验证 | `com.elio.lifearchive`，`0.5.0-dev.2`／12 | `D:/Temp/life-phase2-port/LifeArchive-0.5.0-dev.2.apk` | `6f79a6136fd03c3587f60b4de64fd6d409ef412c741b4a2b57479a71f7e6b2d2` |
| 旧见域完整导出适配 | `com.elio.jianyu`，`0.1.0-transfer.1`／2 | `D:/Temp/life-phase2-port/Jianyu-0.1.0-transfer.1.apk` | `da0829ea54b0b1f9f109362e3882d6527ba8488b1bb191192eb761fdf16587bf` |

均为 Debug 开发包，使用 `aapt2 dump badging` 核对实际包名、显示名与版本；未安装，不能声称手机入口可见或覆盖安装兼容。先前 `dev.1` 阶段包仍保留，不作为完整迁入交付。

本地分类提交：`4d5c471` 完整格式／恢复底层与故障测试，`cfef4f7` 备份恢复 UI／输入换代／真实装配测试；本交接与计划／架构／验收清单另作文档提交。来源仓库 `1d3b2b98` 导出适配、`03950f7b` 文档；没有推送。
