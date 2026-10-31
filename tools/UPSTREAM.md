# 辅助工具复制来源

## 来源与状态

- 来源仓库：见域 / AI-Skill-Roundtable。
- 来源本地路径：`D:\My_Elio\AI-Skill-Roundtable`（仅为来源记录，不是运行依赖）。
- 来源提交：`e72b2839a5d4e828a37ebd86632263f955ec4544`。
- 复制日期：2026-10-18。
- 复制方式：仅复制下列 Git 跟踪文件；复制前已确认这些路径没有未提交改动。
- 本次保留脚本、测试、示例及目录级 AGENTS 原文，未调整实现。缓存、运行产物和源仓库其他未提交工作不纳入复制。

这是一次独立快照，不会自动从见域更新。通用工具可通过现有参数直接用于 Life Archive，无需先修改代码。目前暂不抽取共用工具；实际遇到行为不兼容或新增需求时再按需维护。

## 文件清单（23 个）

以下路径均相对于本仓库根目录：

```text
tools/adb_verbose_diagnose.py
tools/click.py
tools/device/AGENTS.md
tools/device/__init__.py
tools/device/adb_client.py
tools/device/cli.py
tools/device/evidence.py
tools/device/models.py
tools/device/observer.py
tools/device/profiles/ldplayer-main.example.json
tools/device/selectors.py
tools/device/tests/test_adb_fallbacks.py
tools/device/tests/test_cli_contracts.py
tools/device/tests/test_device_control.py
tools/device/tests/test_tap_verification_safety.py
tools/device/ui_selectors.py
tools/local-verification/AGENTS.md
tools/local-verification/Invoke-LocalVerification.ps1
tools/local-verification/LocalVerification.psm1
tools/local-verification/tests/Run-EmptyLineRegressionTest.ps1
tools/local-verification/tests/Run-LocalVerificationToolTests.ps1
tools/screencap.py
tools/uidump.py
```

本目录 README 与本文为 Life Archive 新编说明，不属于原样复制文件。

## 使用配置与后续维护

- 截图、点击、UI 查找、设备诊断和本地日志/JUnit 汇总是通用能力，不要求按项目改写。
- 统一 CLI 的 `launch` 保留默认包名 `com.elio.jianyu`；使用 Life Archive 时显式传入 `--package com.elio.lifearchive`，或通过显式加载的仓库外 Profile 提供包名。命令行包名优先于 Profile，二者都未提供时才使用默认值。
- Profile 可选，示例不会自动加载。使用个人 Profile 时按实际设备核对 Serial、分辨率、DPI、API 和方向，并指定 Life Archive 包名；不需要修改或复制提交上游示例。
- `jianyu` 本地临时目录前缀、手机端 UI dump 文件名及上游测试样例可保留，无需仅为名称而批量替换；只有实际出现并发冲突或行为不兼容时才针对问题修改。
- 原目录级 AGENTS 中涉及 PR、Windows/Ubuntu CI 的表述属于上游规则。本仓库尚无对应 PR 或 CI，不能声称已验证；启用相应流程时再更新。
- 使用前可按[验证说明](../docs/testing/README.md)运行已有无设备检查；工具实现变更后须运行相关帮助入口和测试。设备验证仍按用户任务授权执行，无设备测试通过不等于设备集成通过。
- 后续修改时在本文记录偏离来源的内容；共用维护方式留待实际需要时讨论，不在本次初始化中建设。

2026-10-18 首次复制未引入上游 APK 构建、应用身份检查、图标模板、AI 路由、专属扫描或 GitHub Actions 配置。

## 2026-11-01：根目录构建脚本适配

- 用户明确指定来源：`D:/My_Elio/AI-Skill-Roundtable/build.ps1`，读取时 SHA-256 为 `b4f09ee7750bdb78d5faef293ca305c938c3b1c619f29d5ad4e42df7f9f140c7`；仅只读来源文件，没有修改见域仓库。
- 新入口为根目录 [build.ps1](../build.ps1)，沿用上游 JDK 17 检查、Debug／Release 构建、APK 导出与退出前暂停能力。默认导出到系统“图片”目录，默认版本保持本项目 `app/build.gradle.kts` 中的 0.2.6／8，脚本不依赖来源仓库运行。
- 本项目适配：Life Archive 名称与包名、版本化 APK 命名；参数／交互修改版本、UTF-8 BOM／换行及其他改动保留；帮助、无暂停和预演；以 AGP 元数据校验包名／版本并定位未签名 Release 产物。上游错误处理与 JDK 实际路径识别按本项目需要修正。
- 按用户补充支持 `-Install`：Gradle 增量检查后复用最新产物，过期则先构建；唯一在线设备或显式序列号选择、保留数据覆盖安装、安装后核对包版本。未签名 APK 不安装，不自动卸载、清库或启动应用。
- 同日新增本项目专用的根目录 [install.ps1](../install.ps1)，作为 `build.ps1 -Install` 的独立入口；转发显式参数及退出码，不复制另一套构建或设备判断逻辑。
- 回归入口：[Run-BuildScriptTests.ps1](tests/Run-BuildScriptTests.ps1)。测试使用仓库外临时工程及模拟 Gradle／ADB，不连接手机；实际项目只验证默认构建导出和已有最新产物复用，没有执行实际安装。
