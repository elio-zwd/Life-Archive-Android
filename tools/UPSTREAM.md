# 辅助工具复制来源

## 来源与状态

- 来源仓库：见域 / AI-Skill-Roundtable。
- 来源本地路径：`D:\My_Elio\AI-Skill-Roundtable`（仅为来源记录，不是运行依赖）。
- 来源提交：`e72b2839a5d4e828a37ebd86632263f955ec4544`。
- 复制日期：2026-10-18。
- 复制方式：仅复制下列 Git 跟踪文件；复制前已确认这些路径没有未提交改动。
- 本次保留脚本、测试、示例及目录级 AGENTS 原文，未调整实现。缓存、运行产物和源仓库其他未提交工作不纳入复制。

这是一次独立快照，不会自动从见域更新。目前暂不抽取共用工具，实际使用时再按需对照来源适配和优化。

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

## 正式使用前的适配清单

- 将默认启动包名及示例中的见域应用标识与未来 `com.elio.lifearchive` 对齐；同步相关测试，不能仅改说明文字。
- 示例设备 Serial、分辨率、DPI、API 和方向须按实际设备设置，个人 Profile 放在仓库外；不能把示例当作真实环境。
- 核对 `jianyu` 临时文件前缀、手机端 UI dump 路径及测试中的上游仓库字符串；按使用需求调整，避免两套工具并行时混淆证据。
- 原目录级 AGENTS 中涉及 PR、Windows/Ubuntu CI 的表述属于上游规则。本仓库尚无对应 PR 或 CI，不能声称已验证；启用相应流程时再更新。
- 先运行已有无设备测试，再按用户任务授权做设备验证。原样复制和单测通过不等于设备集成通过。
- 后续修改时在本文记录偏离来源的内容；共用维护方式留待实际需要时讨论，不在本次初始化中建设。

本次未引入上游 APK 构建、应用身份检查、图标模板、AI 路由、专属扫描或 GitHub Actions 配置。
