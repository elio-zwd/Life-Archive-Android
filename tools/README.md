# 辅助工具

**状态：从见域原样复制，通用能力可直接复用，无需先改代码。** 启动 Life Archive 时通过现有参数指定包名，使用 Profile 时核对设备参数。工程底座验收使用了显式包名及 Serial 的 SDK ADB 命令，未改动复制工具；该验收不代表工具本身已完成设备集成验证。

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

- 截图、点击、UI 查找、设备诊断和本地日志/JUnit 汇总不依赖 Life Archive 业务实现，可以直接复用。
- 统一 CLI 的 `launch` 默认包名仍为 `com.elio.jianyu`。启动 Life Archive 时传入 `--package com.elio.lifearchive` 即可，无需修改默认值；包名优先级为命令行参数、显式加载的 Profile、工具默认值。
- Profile 是可选配置，不会自动加载示例文件。若使用，须在仓库外建立个人配置并核对包名、Serial、分辨率、DPI、API 和方向，不直接套用上游示例。
- `jianyu` 临时目录前缀、测试样例及来源说明保留上游命名，无需仅为项目改名而替换；实际出现并发冲突或行为不兼容时再针对问题调整。
- 设备必须显式指定 Serial，或仅有一台在线设备可唯一选择；多个候选不能默认选第一台。
- 点击等动作应验证预期状态；只有 ADB 命令返回成功不能宣称页面操作完成。
- 截图、UI XML、完整日志与证据放在仓库外。旧辅助入口的限制不一定与统一 CLI 相同，调用者仍需遵守此约定。
- 中文输入不以旧版 `adb shell input text` 作为可靠契约。
- 原样复制不意味着已在本项目真机验收，也不意味着本次会执行启动、强停、安装或清库。

完整复制清单、来源版本及使用配置约定见 [UPSTREAM.md](UPSTREAM.md)。本次不复制构建脚本、旧图标模板和 AI 业务工具；不建立对见域本地目录的运行依赖。
