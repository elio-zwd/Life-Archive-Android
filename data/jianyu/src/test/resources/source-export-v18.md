# 旧见域完整导出互通夹具

`source-export-v18.jyfull` 由 `D:/My_Elio/AI-Skill-Roundtable` 的 `CompleteMigrationExportTest.export` 调用真实来源导出器生成；导出实现提交 `1d3b2b98`。生成时来源安装版本为 `0.1.0`，来源 Schema v18。夹具不是 Life 写入器生成的往返文件。

仅包含合成会话、自定义角色、ID 为 101 的已停止半段回答、256 字节音频、文字用户文件与默认角色资源引用；没有真实用户资料或凭据。公开测试密码为 `合成完整迁出的测试密码123`。

夹具 SHA-256：`0230cd3210860751d508e3108f671f75bbc1a3bf95dd1d80c6aa28fef52511cb`。

重新生成时在 PowerShell 7 设置 `JY_TRANSFER_FIXTURE_OUTPUT` 为仓库外 `.jyfull` 绝对路径，运行来源仓库的 `testDebugUnitTest --tests '*CompleteMigrationExportTest'`，再将合成输出复制到本文件旁。Life 的 `SourceExportInteropTest` 独立解密、检查实际数据库、正文、角色、停止状态、标识、设置和附件并转换目标音频路径。
