# 当前验证方法

当前已有 Android 工程、完整 Gradle Wrapper 和初始化页启动冒烟测试；业务功能、业务测试和 CI 尚未建立。实际结果见[工程交接](../engineering/bootstrap-handoff.md)。构建、测试、设备启动和视觉检查分别记录，不代表笔记功能或小米真机验收通过。

## Android 构建检查

需要 Windows 10 x64、PowerShell 7、JDK 17、Android SDK Platform 36 和 Build Tools 35.0.0。SDK 与 JDK 使用本机现有安装，不把个人路径写入版本控制：设置 `JAVA_HOME`，通过 `ANDROID_HOME` 或根目录被忽略的 `local.properties` 中的 `sdk.dir` 指定 SDK。Java 命令行 PATH 可能指向旧 Java，检查 Wrapper 实际使用的 JVM：

```powershell
& "$env:JAVA_HOME\bin\java.exe" -version
.\gradlew.bat --version
.\gradlew.bat :app:assembleDebug :app:lintDebug :core:designsystem:lintDebug :app:assembleDebugAndroidTest --console=plain
```

首次构建会下载 Wrapper 与 Maven 依赖；已固定版本见 `gradle/libs.versions.toml`。以上命令不安装 APK、不连接设备。输出入口：

- App：`app/build/outputs/apk/debug/app-debug.apk`。
- 仪器测试：`app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk`。
- Lint 报告：`app/build/reports/lint-results-debug.html`，主题模块另有 `core/designsystem/build/reports/lint-results-debug.html`。

JUnit 已配置，尚无业务单测；`testDebugUnitTest` 显示 `NO-SOURCE` 不能报告为单测通过。

## 已授权模拟器上的启动检查

以下命令只在用户已授权安装与启动的模拟器上执行。先用 `adb devices -l` 与模拟器管理程序核对实例，再输入已确认的 Serial；出现多个候选或 `offline` 时先解决目标身份或连接问题。不得因设备型号字符串显示小米就把雷电当作真实手机。

```powershell
$deviceSerial = Read-Host '输入已核对的模拟器 Serial'
adb -s $deviceSerial shell getprop ro.build.version.sdk
adb -s $deviceSerial shell pm path com.elio.lifearchive
adb -s $deviceSerial install -r app/build/outputs/apk/debug/app-debug.apk
adb -s $deviceSerial shell am start -W -n com.elio.lifearchive/.MainActivity
adb -s $deviceSerial install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb -s $deviceSerial shell am instrument -w com.elio.lifearchive.test/androidx.test.runner.AndroidJUnitRunner
```

`install -r` 用于保留数据的安装/覆盖更新，不为解决签名冲突而卸载或清数据。这里直接调用仪器测试，不使用会额外管理安装生命周期的设备测试任务。

冒烟测试断言应用名称与“工程初始化完成”均可见。还需实际检查 Home 后返回、浅深主题和日志中的启动崩溃。主题切换前记录 `adb -s $deviceSerial shell cmd uimode night`，支持时使用 `night yes` / `night no`，结束后恢复原值。截图、UI XML 和日志保存在仓库外；不因命令返回成功就标记 UI 验收通过。

## 文档检查

在仓库根目录使用 PowerShell 7，执行 `git diff --check`；存在暂存改动时补充 `git diff --cached --check`。同时检查新增未跟踪文件的编码、尾部空白、相对链接及文档事实，Git 差异检查不覆盖未跟踪文件。

## 无设备工具检查

需要本机可用的 Python 3 和 PowerShell 7。脚本采用 Python 标准库，测试无需安装第三方依赖或连接手机；具体 Python 版本兼容性以实际测试结果为准。

```powershell
python tools/device/cli.py --help
python tools/screencap.py --help
python tools/click.py --help
python tools/uidump.py --help
python tools/adb_verbose_diagnose.py --help
python -m compileall -q tools/device tools/screencap.py tools/click.py tools/uidump.py tools/adb_verbose_diagnose.py
python -m unittest discover -s tools/device/tests -p 'test_*.py'
pwsh.exe -NoProfile -File tools/local-verification/tests/Run-LocalVerificationToolTests.ps1
pwsh.exe -NoProfile -File tools/local-verification/tests/Run-EmptyLineRegressionTest.ps1
```

Python 编译检查可能产生被忽略的缓存。PowerShell 测试使用系统临时目录并清理自己创建的文件。完整运行日志保存到仓库外，终端只需汇报结果和失败摘要。

## 后续验证

正式使用手机工具前，完成[工具适配清单](../../tools/UPSTREAM.md)，再在授权范围内核对设备、包名和预期状态。设备操作命令执行成功不能替代 UI 状态验证。

后续业务单测随真实实现增加。编辑器和完整笔记功能按[一期验收要求](../plans/phase-1-local-notes.md)验证；当前初始化页与模拟器结果不替代编辑器交互、文件可靠性、性能或小米 14 Ultra 真机验收，也不沿用见域的历史通过记录。
