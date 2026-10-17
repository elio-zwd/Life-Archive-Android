# Android 工程底座交接

日期：2026-10-18。工程底座已建立，业务架构尚未实现。完整架构方向继续遵循 [ADR-001](../architecture/adr-001-android-architecture.md)；本次用户明确只搭底座，将 Hilt/KSP、导航、业务模块及数据层交给后续实施者。

## 当前代码与版本

依赖图只有 `app → core:designsystem`，不创建其他占位模块：

| 模块 | 当前职责与接入位置 |
| --- | --- |
| `app` | `MainActivity`、初始化页、Manifest、应用资源和启动冒烟测试；后续在此替换初始化页并组装真实功能入口 |
| `core:designsystem` | `com.elio.lifearchive.core.designsystem.LifeArchiveTheme(darkTheme, content)`；使用系统浅深主题，固定参考配色，无动态配色 |

| 项目 | 固定值 |
| --- | --- |
| 应用标识 / 显示名 | `com.elio.lifearchive` / Life Archive |
| versionCode / versionName | `1` / `0.1.0` |
| JDK / JVM 目标 | 17 |
| Gradle Wrapper | 8.13，bin 发行包，配置 SHA-256 校验 |
| AGP | 8.13.2 |
| Kotlin / Compose Compiler 插件 | 2.3.21 |
| Compose BOM | 2026.03.00（UI 1.10.5、Material 3 1.4.0） |
| Activity Compose | 1.12.4 |
| JUnit / AndroidX Test JUnit / Runner | 4.13.2 / 1.3.0 / 1.7.0 |
| min / compile / target SDK | 26 / 36 / 36 |

版本目录为 `gradle/libs.versions.toml`，SDK 级别在两个模块的构建脚本中设置。Wrapper 脚本和 JAR 已纳入交付；本机 `local.properties`、缓存、APK、签名材料不纳入 Git。Windows 的 SDK 路径在 properties 文件中须转义盘符冒号，避免 `PropertyEscape` 错误。`gradle.properties` 限制最多 2 个工作线程，Gradle 堆上限 2 GiB、Kotlin 编译守护进程堆上限 512 MiB，减少与模拟器争用内存。

当前不申请网络、外部存储或其他业务权限；Manifest 和数据提取规则暂关闭系统自动备份及设备迁移，后续业务备份按 ADR-001 实施。这些规则只做底座配置，未进行 Android 12+ 的实际备份/迁移验证。

## 构建与测试入口

Windows 10 x64、PowerShell 7，使用 JDK 17、SDK Platform 36 和 Build Tools 35.0.0：

```powershell
.\gradlew.bat --version
.\gradlew.bat :app:assembleDebug :app:lintDebug :core:designsystem:lintDebug :app:assembleDebugAndroidTest --console=plain
```

详细环境配置、APK/报告位置和显式 Serial 的安装、仪器测试命令见[验证说明](../testing/README.md)。`gradlew --version` 显示的 Kotlin 2.0.21 是 Gradle 内嵌 Kotlin，不是项目所用的 Kotlin 插件 2.3.21。启动冒烟测试只验证真实 Activity 中的两项文字可见；尚无业务单测，不添加算术占位测试。

## 本次实际验证

验证时使用 `00b4d97` 上的工程底座工作区，代码随后原样提交为 `fb7bc81`。环境：Windows 10 amd64、Temurin JDK 17.0.19+10。最终 APK SHA-256：`fb6c94e48aa07bb3b91c748cf494d844a4be1461c3877e29a86630c45e8b1d5e`。

| 检查 | 实际结果 |
| --- | --- |
| Wrapper / JDK | PASS：Gradle 8.13，Launcher 与 Daemon 使用 JDK 17 |
| Debug App / 仪器测试 APK 构建 | PASS：最终构建成功，两个 APK 均生成 |
| App Lint | PASS：0 错误，8 条版本或 target SDK 更新提示；保留约定版本，未用 baseline 或全局禁用掩盖问题 |
| 主题模块 Lint | PASS：0 错误、0 警告 |
| 雷电启动冒烟测试 | PASS：`BootstrapSmokeTest` 的 1 项测试通过，真实 Activity 中应用名与初始化状态可见 |
| Home 后返回 | PASS：先断言已离开 App，再返回并通过两项页面文字断言 |
| 浅深主题 | PASS：两种主题均通过文字断言并查看实际截图，无截断或遮挡；测试结束恢复原夜间模式 `no` |
| 启动崩溃 | PASS：最终 App 进程正常，运行日志无 `FATAL EXCEPTION`，崩溃缓冲区未发现本应用记录 |

雷电实例 0 由本机 `ldconsole list2` 与对应虚拟机进程核对，Android 9 / API 28，1080 × 2400，实际覆盖 DPI 450。开始时曾显示 `offline`，复查时已恢复在线；未修改模拟器配置或数据。型号字符串虽显示小米标识，以上均为模拟器结果。

完整日志、截图与 UI XML 位于本机仓库外 `%TEMP%\life-archive-bootstrap-20261018`：`build-final.log`、`gradle-version.txt`、`instrumentation-final.log`、`ui-verification-final.json`、`verification-summary.json`、`app-runtime.log`、`crash-buffer.log`，以及 `light`、`dark`、`return-from-home` 对应的 PNG/XML。它们是本机证据，不是运行依赖，也不纳入 Git。

首次 Lint 因新建的本机 SDK 路径转义失败，修正后最终检查通过；Manifest 数据提取配置警告已补齐规则。Debug 打包另有原样保留 `libandroidx.graphics.path.so` 符号的提示，未为消除此提示安装 NDK，实际启动与测试正常。

未验证：业务单测与笔记功能、API 26/36 设备行为、Android 12+ 系统备份配置的实际行为、小米 14 Ultra 真机输入/视觉/性能、Release 签名交付。当前主题检查仅覆盖初始化页，不代表完整产品视觉验收。

## 给后续架构实施者

1. 直接沿用当前 Wrapper、版本目录和主题模块，不重新生成整个工程，不覆盖已有未提交改动。
2. 从 ADR-001 的依赖表与编辑器样板门槛继续，按实际调用建立 `core:document`、`domain:notes`、`data:notes`、`feature:editor`；初始化页可在接入真实入口时移除。
3. Hilt/KSP、Navigation 3、ViewModel/UDF、Room、DataStore、序列化和编辑/渲染库尚未接入；实际引入时集中固定版本并构建验证，不能从底座通过推断这些组合已兼容。
4. 基础主题和临时矢量图标只用于工程启动，不表示最终视觉设计已验收。模拟器不是小米 14 Ultra 真机，笔记、文件、输入法及性能仍需各自验收。

本次保持 `main`，工程底座与交接文档按用户后续授权分类提交，不推送；未改动其他项目和复制工具实现。
