# 架构方向与未来代码组织

状态：已确认方向，尚无 Android 工程。此处不锁定依赖版本，也不代表相关组件已实现。

## 技术和数据约定

- Kotlin + Jetpack Compose 原生 Android；Material 3、ViewModel 与页面导航在工程建立时接入。
- 未来 namespace / applicationId 为 `com.elio.lifearchive`；项目与显示名为 Life Archive。
- 笔记正文保存为 UTF-8 `.md` 文件，文件夹对应笔记本，附件独立管理保存。
- Room 用于可重建索引、列表缓存与应用状态；正文始终以 Markdown 文件为准。
- 文件名、正文标题、YAML 属性相互独立；文件兼容与保存保真遵循[一期计划](../plans/phase-1-local-notes.md)。

## 未来工程组织

从单个 `app` 模块开始，按实际代码逐步建立以下职责，不预先创建空目录或接口：

| 未来位置 | 职责 |
| --- | --- |
| `ui/` | 页面、导航、共享组件、主题；不直接读写笔记文件 |
| `viewmodel/` | 页面状态、用户动作、协程和生命周期协调 |
| `data/` | 文件读写、附件、索引与 Repository；集中处理保存结果和错误 |
| `app/src/test/` | 可脱离设备运行的行为测试 |
| `app/src/androidTest/` | Android 环境与界面交互测试 |

上表是未来布局，不是当前存在的文件路径。包内生产代码将位于 `app/src/main/java/com/elio/lifearchive/`。

## 本阶段边界

不选择编辑器或 Markdown 渲染依赖，不创建数据库 Schema、业务接口、网络层或迁移实现。编辑器需要先通过计划中的真实 Android 交互样板验证；共享能力和电脑协作分别留在第二、三期。
