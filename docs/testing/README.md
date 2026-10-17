# 当前验证方法

当前没有 Android 工程、Gradle Wrapper、APK 或 CI。以下命令只验证仓库文档和复制的辅助工具，不代表笔记功能、手机控制集成或视觉体验通过。

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

建立 Android 工程时再补充真实构建、单测、设备测试入口。编辑器和完整笔记功能按[一期验收要求](../plans/phase-1-local-notes.md)验证；不沿用见域的历史通过记录。
