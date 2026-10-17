# 辅助工具

**状态：从见域原样复制留存，尚未完成 Life Archive 设备适配。** 本仓库没有可启动的 App；不要直接使用上游默认包名或示例 Profile 操作设备。

| 入口 | 用途 |
| --- | --- |
| [device/cli.py](device/cli.py) | 统一设备检查、观察、语义定位、点击、等待、断言和启动入口 |
| [local-verification/](local-verification/) | 命令日志、JUnit 解析及紧凑证据汇报 |
| [screencap.py](screencap.py) | 截图辅助入口 |
| [click.py](click.py) | 坐标点击、滑动、按键等辅助入口 |
| [uidump.py](uidump.py) | UI XML 读取辅助入口 |
| [adb_verbose_diagnose.py](adb_verbose_diagnose.py) | 详细设备诊断辅助入口 |

## 当前可以执行的检查

在仓库根目录运行 `python tools/device/cli.py --help` 查看帮助。其他帮助命令和已有无设备测试见[验证说明](../docs/testing/README.md)。这些检查不需要 ADB 设备；实际手机操作需要本机 ADB 和已授权的目标设备。

## 使用前必须核对

- 统一 CLI 当前默认包名仍为 `com.elio.jianyu`，Profile 也属于上游示例。未来目标是 `com.elio.lifearchive`，正式使用时再同步适配代码、示例和测试。
- 设备必须显式指定 Serial，或仅有一台在线设备可唯一选择；多个候选不能默认选第一台。
- 点击等动作应验证预期状态；只有 ADB 命令返回成功不能宣称页面操作完成。
- 截图、UI XML、完整日志与证据放在仓库外。旧辅助入口的限制不一定与统一 CLI 相同，调用者仍需遵守此约定。
- 中文输入不以旧版 `adb shell input text` 作为可靠契约。
- 原样复制不意味着已在本项目真机验收，也不意味着本次会执行启动、强停、安装或清库。

完整复制清单、来源版本及后续改造事项见 [UPSTREAM.md](UPSTREAM.md)。本次不复制构建脚本、旧图标模板和 AI 业务工具；不建立对见域本地目录的运行依赖。
