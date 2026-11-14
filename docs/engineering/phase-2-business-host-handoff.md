# 二期业务接入与双域宿主

状态：2026-11-14 真实业务、悬浮入口、正式消息渲染和完整迁移／恢复已接入，电脑定向交付检查通过；不是二期真机完成报告。最新数据闭环与交付包见[完整迁移交接](phase-2-complete-backup-handoff.md)，当前仅使用合成资料。

## 已接入的真实能力

- 原见域生产源码基线 `2d474464100d70269ef43d838408e7573461e93d`。后续仅为完整迁出在来源仓库增加导出适配（代码 `1d3b2b98`、文档 `03950f7b`），没有操作手机旧资料。沿用 Life `app` 和应用标识 `com.elio.lifearchive`，新增 `domain:jianyu`、`data:jianyu`、`feature:jianyu`，共十六个模块。
- 纯 Kotlin domain 承载业务模型、协调器和公开能力。38 个 Room 持久化模型留在 data；DAO 通过显式 `entity` 映射领域对象。原 v18 Schema 身份摘要仍为 `3b63f950997a41754eb5aa792a99bd8f`，实际以旧 Schema 建库后由新 Room 打开并读写验证，未破坏性重建。
- 见域页面仅依赖 domain 和共用渲染模块；数据、网络、音频、设置、维护及旧备份实现由 app 经 data 装配。保留对话／角色／资料／我的、原内部导航和各自外观。
- Life 正常启动不创建见域运行实例或数据库。首次切换才在后台初始化；初始化失败有返回 Life 和显式重试入口。
- 数据库 `jianyu_roundtable_database`；文件、缓存、noBackup 和外部私有文件各在对应根目录的 `jianyu` 子目录；偏好增加 `jianyu_` 前缀。凭据留见域专属 noBackup。音频读取／删除先校验所属路径，清理只取消见域标签的 WorkManager 任务。

## 实际入口和状态所有者

从 Life 首页点击显示“见域”的单个悬浮按钮，进入真实对话一级页；角色、资料、我的底栏保持。按钮显示目标业务名称，拖动结束记忆相对位置，按实测底栏高度及系统／键盘占用约束范围。位置属于宿主设备偏好，不进入任何业务内容备份。

用户已确认：对话一级页自身显示聊天，不另建会话列表首页；展开回答、打开弹层或进入详情／设置时隐藏按钮。Life 阅读／编辑时隐藏。

`BusinessHostViewModel` 持有当前业务、见域运行租约和世代 UI ViewModelStore；普通往返只改变可见业务。隐藏树降至 CREATED 并移出显示／无障碍范围，不接收返回或焦点。对话生成属于数据运行会话，页面切换不结束该会话。维护状态要求释放 UI 任务、对话 ViewModel 和租约后才能闭库。

全新启动默认 Life；Android SavedState 重建恢复当前业务。已访问的两域保留 Compose 状态和导航。未发送文字在同进程的 ViewModel 中保留；进程被结束后只保证已持久化消息，未完成回答保留正文并标记 STOPPED，不自动重新请求。

## 已有电脑证据

环境：Windows、PowerShell 7、JDK 17；没有连接或操作手机。日志在仓库外 `D:/Temp/life-phase2-port/`。

- `checkArchitecture`、`:app:compileDebugKotlin` 通过。
- domain／data 底层及宿主状态测试通过，覆盖旧 v18 Schema、领域对象持久化、部分回答恢复、路径／清理隔离、默认 Life、切换复用、初始化失败和维护释放。
- 真实 Hilt `MainActivity` 的 Robolectric 测试通过：Life 冷启动没有见域数据库 → 实际悬浮入口 → 真实见域就绪 → 输入未发送文字 → 切回 Life → Activity 重建 → 回见域后输入保留。现有导航与阅读宿主定向回归通过。
- 消息片段样板复用 `core:document` 投影和同一 `reader.js`；无笔记元信息，默认没有业务存储能力。桌面 Chromium 145、412×892 下选区暂缓更新、末帧、折叠状态、主题和连续 100 帧长回答检查通过。单次观测约 984ms，不作为 Android 帧率或性能承诺。
- 领域层 44 项、数据层 22 项、消息窗口 2 项、App 定向 16 项测试通过。消息窗口测试分别挂载 100／500 项，验证附着 WebView 在可见项附近且滚到末尾后不再挂载首屏实例；Robolectric 不提供手机帧率／内存性能结论。
- `core:markdown`、见域 data／feature 和 app 的 Lint 通过，无错误；保留原有 API 弃用、参数顺序等警告。Life 阅读浏览器 64 组回归与片段 18 项检查通过。日志 `behavior-render-validation-4.log` 和 `host-final-validation.log` 分别记录完整整合检查及最后宿主回归／构建。

## 开发验证包与提交

`0.5.0-dev.1` / versionCode `11` / `com.elio.lifearchive`，Debug 开发验证包，仅用于合成资料和入口检查，不作为完整迁入／恢复交付。构建输出 `app/build/outputs/apk/debug/app-debug.apk`；阶段副本在仓库外 `D:/Temp/life-phase2-port/LifeArchive-0.5.0-dev.1.apk`。SHA-256：`9bae3948a2afe96d71feeeb8e96ef6788d4147a4eedf61ec844fb45e040c0c486`。未安装到手机，也未修改旧见域。

本地分类提交：`cbd20d9` 共用消息渲染、`cfd5aa9` 见域业务模块、`aca5a86` 双域宿主；没有推送。原受限备份和完整恢复的缺口仍见下节。

## 后续实现与剩余验收

- 开发代码的消息、角色说明、成果及执行输出已切换 `SharedMarkdownText`，复用同一解析与 reader 资源。旧 Compose Markdown／JitPack 依赖已删除；打包时发现的 CommonMark 0.13／0.30.1 同名类冲突随之消除。桌面样板、代码复制及 100／500 消息实例窗口测试已通过；仍需与旧见域对照的设备滚动帧率、内存、实际选区／手势和资源交互验收，不能标记 D 最终通过。
- 旧 `.jybak` 导出和设备快照保持原有限范围；新增完整 `.jyfull` 导出、预览、替换与恢复，见[数据闭环交接](phase-2-complete-backup-handoff.md)，不能把旧有限格式当成完整包。
- 数据替换／清理已轮换 content epoch 并使旧回调失效；普通快照不轮换。真实装配的备份／恢复／清理及两域隔离电脑测试通过；维护时重建 UI 的同 epoch 输入保留，换数据后清空旧输入。
- 设备上的业务生成／停止／重试、完整备份系统选择器与真实资料仍待验收；本地电脑定向检查结果以数据闭环交接为准。
- 未安装到手机；没有小米入口、视觉、长列表帧率或真实旧数据迁入结论。电脑测试不替代设备验收。
