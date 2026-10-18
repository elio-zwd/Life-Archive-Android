# 架构与核心数据底层交接

日期：2026-10-18。交付对象是后续功能开发 AI。当前交付架构与核心数据能力，正式笔记功能层待接入；不是一期 App 完成记录。

## 1. 接手顺序与责任

先读根目录 AGENTS、本文及 ADR-001，再按任务阅读一期计划。沿用现有七个模块；不要重建工程、绕过 Repository 或把所有业务放进 app。编辑器交互样板、完整 Obsidian 阅读兼容、笔记组织、搜索、链接维护、回收站及正式导入导出仍属后续功能任务。

原 [bootstrap-handoff](bootstrap-handoff.md) 是两个模块阶段的历史记录；其中不接入业务框架的限制属于当时任务。本轮用户授权底层先行，并限定仅电脑验证。

| 模块 | 当前实现与直接项目依赖 |
| --- | --- |
| app | Hilt 装配、启动状态、主题、Debug 导航/存储示例；依赖下面六个模块 |
| core:designsystem | 既有主题，无项目依赖 |
| core:document | 原始 UTF-8、路径/冲突检查、版本和定位类型，无项目依赖 |
| domain:notes | 笔记 ID、快照、草稿、结果与 Repository；依赖 core:document |
| data:notes | 文件存储、Room 状态/索引、保存恢复、附件流、笔记备份参与者；依赖 domain:notes、core:document、core:backup |
| core:preferences | 设置接口、DataStore、设置备份参与者；依赖 core:backup |
| core:backup | 门锁、ZIP、暂存/确认提交/回滚、参与者契约，无项目依赖 |

`checkArchitecture` 检查直接项目依赖、环路和纯 Kotlin 平台隔离。新增模块须同步允许依赖表；该检查约束真实 Gradle 依赖和源码平台 import，不代替代码审查。

## 2. 版本与运行入口

保留 JDK 17、Gradle 8.13、AGP 8.13.2、Kotlin 2.3.21、Compose BOM 2026.03.00、min/compile/target 26/36/36。新增版本统一在 `gradle/libs.versions.toml`。

| 依赖 | 锁定版本 |
| --- | --- |
| Hilt / KSP | 2.58 / 2.3.9 |
| Navigation 3 | 1.1.2 |
| Lifecycle | 2.10.0 |
| Room / Preferences DataStore | 2.8.4 / 1.2.1 |
| Coroutines / Kotlinx Serialization | 1.10.2 / 1.9.0 |
| Robolectric | 4.16.1 |

Navigation 3 1.2.0 实际 AAR 检查要求 SDK 37 与 AGP 9.1，已报告冲突并保留工具链改用 1.1.2。Hilt 2.59 起要求 AGP 9，使用 2.58。没有绕过 AAR 版本检查或升级原工具链。

`LifeArchiveApplication` 启动 Hilt；`FoundationRuntime` 每个库创建一个门锁、一个笔记存储、一个设置存储，并先装配 `BackupEngine` 恢复回调，再公开 Repository。生产库在 `filesDir/library`，Debug 示例在 `filesDir/foundation-demo`；Release 不包含示例实现。

`AppViewModel` 先初始化笔记存储，再收集设置。恢复失败显示错误，不把空库当成功；主题由 DataStore 实际驱动。字号已持久化，正式阅读器接入时应用，当前示例不代替阅读排版。Debug 导航使用可保存返回栈，以及保存状态和按导航条目管理 ViewModel 的装饰器，弹出条目时释放其 ViewModel。

## 3. 公开接口与状态语义

### 笔记

注入 `NotesRepository`，不要注入 `NotesStorage` 到 feature。观察接口只发布摘要，正文按 ID 读取，输入文本和选区保留在局部编辑会话。

```kotlin
val result = repository.save(noteId, loadedVersion, RawDocument(editorText), draftRevision)
when (result) {
    is NoteResult.Committed -> {
        // 记录 result.value.summary.version；不能用回执正文覆盖仍在输入的会话。
        // indexPending=true 表示正文已落盘，派生索引仍待更新。
    }
    is NoteResult.Conflict -> { /* 保留草稿，重新读取并提示冲突。 */ }
    is NoteResult.StorageFailure -> { /* 保留会话，提示失败；必要时重新初始化与读取。 */ }
    else -> { /* 按 NotFound / InvalidPath 等结果反馈。 */ }
}
```

- `create(path, document)` 创建 `.md`；取消命名时不调用。路径支持目录，跨平台名称/大小写冲突不覆盖旧文件。
- `read(id)` 返回原始正文与版本；`save` 要求预期版本，每次成功提交产生新版本。版本是提交令牌，不是时间戳或文件名。
- `writeDraft` 要求正整数修订号递增；恢复已有草稿后从其修订号继续。草稿基于旧正文版本也保留，由 UI 决定冲突处理。
- `save` 仅清理调用方明确确认的同修订号、同正文草稿；较新草稿不会被旧回执删除。没有提供静默丢弃冲突草稿的入口。
- `Success` 用于读取/初始化等；正文创建/保存使用 `Committed`。`StorageFailure(recoveryRequired=true)` 表示存在待恢复操作，不等于正文已经丢失；重新初始化和读取确认最终版本。取消也可能发生在提交之后，不能据此判定未保存。
- `importAttachment` / `copyAttachment` 用流处理，调用者负责输入/输出流生命周期；文件已纳入私有库，不依赖原 URI。正式引用语法和系统查看器后续实现。
- `rebuildIndex` 只重建派生库。当前投影是原文和版本，不是完整中文搜索、标签或引用引擎。
- 文件名、H1、YAML title 独立；`RawDocument` 保留 BOM/换行并拒绝非法 UTF-8，不做 YAML/Markdown 往返格式化。

### 设置

注入 `PreferencesRepository`；`preferences` 为 Flow，`setTheme` / `setFontScale` 在共享门锁下提交。默认 SYSTEM / 1.0，字号比例允许 0.5–3.0。存储损坏抛错，不静默恢复默认值。

### 备份

注入 `BackupEngine`：`export(output)`、`prepare(input)`、`commit(prepared)`、`discard(prepared)`。ZIP 接口接管并关闭所用流，调用方仍建议使用 `use`。

`prepare` 只验证并暂存，返回引擎管理的 token；UI 展示“替换现有资料”确认后才调用 `commit`。异常或取消表示调用未完整结束，不能直接显示成功，也不能推断数据尚未提交；提交标记落盘后的清理也可能失败。先重新初始化并读取最终状态，再反馈结果。底层不弹确认框；本轮没有接入用户文件选择器。

包格式 v1：根 `manifest.json` 声明参与者，`notes/` 保存正文、附件、独立草稿和逻辑状态 JSON，`preferences/settings.json` 保存设置。索引不入包；恢复保留 ID/版本/置顶与阅读位置字段，再重建索引。当前置顶和阅读位置尚无产品入口。

读取限制为 20,000 条目、解压总计 2 GiB，状态清单 16 MiB、总清单和设置各 64 KiB；这是备份解析的保护限制，不是 ADR 测试样例的文件大小承诺。路径越界、重复/大小写冲突、未知参与者、格式版本不符均拒绝。

## 4. 文件可靠性和恢复

```text
library/
  notes/
    documents/          Markdown 权威正文
    attachments/        已入库附件
    drafts/<id>.json    独立草稿
    operations/save.json
    state.db            ID、路径、版本、hash、应用状态
    index.db            可重建投影
  settings.preferences_pb
  backup-work/          暂存包、回滚快照、恢复记录
```

保存流程：串行门锁 → 检查版本/原文 hash → 同目录暂存正文并同步文件 → 持久操作记录 → 原子替换 → 更新状态库 → 有条件清理草稿 → 删除操作记录 → 更新派生索引。进程中断后根据记录和文件 hash 完成提交，再放行访问；索引失败单独保留待更新标志。

正文文件与状态记录不一致时停止写入，不从索引反向恢复正文。状态库不启用破坏性迁移，并覆盖 SQLite 默认损坏删除回调以保留原文件；后续 Schema 升级必须提供迁移。Room v1 Schema 已纳入 `data/notes/schemas`。索引库允许重建，但不能连带清空状态库。

完整备份在同一门锁下取得快照，数据库通过逻辑记录导出；不复制运行中的 DB/WAL。恢复先写旧库快照和持久事务记录，再调用各参与者替换。提交标记落盘前失败或进程中断时全部恢复旧快照；回滚失败继续保留记录，并阻止后续访问。提交标记落盘后的清理中断只继续清理，不再次回滚。

参与者恢复必须幂等，不能在回调中再次获取门锁或调用公开 Repository。观察者等待门锁后读取最终快照，避免发布部分恢复结果。应用只面向单进程；这些保证针对可检测写入失败和进程中断，不把电脑测试等同真机断电耐久性证明。

恢复保护边界：正常完整恢复要求先取得当前库的有效回滚快照。如果现库状态数据库已经损坏或正文校验不一致，当前实现会保留文件并拒绝继续，不能把它当成已实现的损坏库修复工具；专门的灾难修复入口留待后续明确设计。

## 5. 后续 AI 的接入方式

1. 新增真实 `feature:*` 模块，按 ADR 配置依赖，在根检查表中声明边界；Route 收集 ViewModel 状态，Screen 只接收状态/动作，导航回调交由 app。导航键实现 NavKey 并标记 Serializable，顶层采用 internal/public，不用 private 阻断状态序列化反射。
2. 注入领域接口；`AppViewModelTest` 演示在不改状态管理代码的情况下替换 fake。fake 只用于单元测试，不用来证明文件可靠性。
3. 编辑器自动保存携带正文版本与草稿修订号，先接单篇真实读写，再执行编辑器样板。不要把每次按键经数据库 Flow 回写输入框。
4. 新数据域拥有自己的存储和接口；增加 `BackupParticipant`，实现快照、校验与幂等恢复，在 `FoundationRuntime` 显式注册并增加跨参与者失败测试。参与者集合变化要同时设计包版本迁移，不能忽略旧包中未知数据。
5. 后续重命名/移动/链接维护需要扩展持久操作协议；当前保存记录只实现单篇提交，不声称已经支持多文件事务。
6. 新增 UI/功能按一期计划验收，设备验证另行授权；不要把 Robolectric 当成雷电或小米真机结果。

## 6. 验证记录

验证命令见[验证说明](../testing/README.md)。测试使用系统临时目录中的合成数据，不读取用户真实笔记。

验证对应实现提交 `25d90b9` 和测试提交 `e67d23a`，Windows 10 x64、Temurin JDK 17.0.19+10；Robolectric 使用 API 28。已按用户授权复核并按实现、测试、文档分类提交，保持 main，不推送。

| 检查 | 实际结果 |
| --- | --- |
| `verifyFoundation` | PASS，1 分 29 秒；提交前复核强制重新执行所有本地测试，324 个构建任务中 60 个执行、264 个 up-to-date；任务数量不等于测试数量 |
| 模块依赖 | PASS；通过临时 init 脚本注入 domain:notes → data:notes、临时 feature → data:notes，均按预期拒绝；未修改仓库依赖，恢复正常检查后通过 |
| core:document | 3 项测试通过：原文/BOM/换行保留、非法编码拒绝、跨平台路径与冲突 |
| core:backup | 6 项测试通过：完整往返、非法 ZIP、部分恢复失败、重启回滚、提交后中断仅清理、回滚失败阻止访问 |
| data:notes | 8 项测试通过：真实文件重开、并发版本冲突、草稿修订、写入失败、提交中断恢复、索引/状态损坏分离保护、附件重开、500 篇与 1 MiB 文档 |
| core:preferences | 1 项测试通过：默认值、主题/字号持久化与输入校验 |
| app | 7 项测试通过：fake 状态流与失败反馈、真实 Hilt 装配、导航返回/Activity 重建、真实存储备份恢复、恢复中断回滚、50 MiB 附件流式往返 |
| 全部本地测试 | **25 项通过，0 失败，0 错误**；不包含未执行的设备仪器测试 |
| 四个 Android 模块 Lint | 0 错误；app 有 25 条版本/target SDK 更新提示，其他三个模块 0 警告；未添加 baseline 或禁用检查 |
| APK 与 Release 源码 | Debug APK、仪器测试 APK 构建通过；Release Kotlin 编译通过，未签名交付 Release |
| 文档与差异 | 本地链接、UTF-8、尾部空白及 `git diff --check` 通过 |

Debug APK SHA-256：`4ed12d2c54f0ee80ff045075e368217400f049e8fb96317c9f23b4e34418e253`。APK 为 `app/build/outputs/apk/debug/app-debug.apk`；仪器测试 APK 为 `app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk`。未安装到任何设备。

本轮发现并修复：导航键 private 导致运行时序列化失败、SQLite 默认损坏回调可能删除状态库。提交前复核补严 feature 的独立依赖禁令，并新增提交标记落盘后中断的回归测试，明确异常后的状态确认语义。依赖冲突按上文固定兼容组合解决。最初依赖下载曾出现 TLS 握手失败，重试成功，没有修改全局网络配置。构建保留本机既有 `android.overridePathCheck` 实验选项提示与原样打包 native 库符号提示，未为消除提示安装 NDK。

完整最终构建、聚焦测试和架构正反向检查日志保存在仓库外 `%TEMP%/life-archive-foundation-20261018/`。模块级 XML/HTML 测试与 Lint 报告位于各自被忽略的 `build/` 目录。记录属于本次工作区检查，不是历史设备结果的沿用。

未验证范围固定为：模拟器/真机启动与输入、视觉、导航的设备行为、目标机性能、系统备份限制实际行为、Release 签名交付、完整笔记功能与 Obsidian 语法渲染。历史底座的设备通过结果不沿用为本次结果。
