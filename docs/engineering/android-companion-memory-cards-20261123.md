# 共同记忆 B：手记卡片实现交接

日期：2026-11-23。用户选择[共同记忆 B「手记卡片」](../design/companion-candidates-20261123/02-memory-options.png)，本次只落实记忆页面；其余页面方案仍待选。

后续更新：用户同日继续选定陪伴、设置、悬浮及编辑形态，记忆／人格编辑已改为底部弹层，见[选定布局交接](android-companion-selected-layouts-20261123.md)。本记录保留卡片初次交付时的范围和结果。

随后按用户“顶部少了人物图片”的反馈，将记忆页头改为[随包人物插画](../design/companion-memory-portrait-20261123.md)，该页不再只依赖原生模型；下文原生页头及当时截图为空的说明保留历史含义。

## 实际实现

- 页面采用伙伴寄语、共同记忆标题与数量、添加入口、双列高低错落卡片。卡片保留完整正文、真实来源标签、更正及删除入口，点缀胶带、手绘曲线和枝叶，不把生成图片当作整页背景。
- 颜色跟随全 App 的浅灰蓝／暖纸／深石墨主题，不新增宠物私有配色设置。正式形象仍待选择；页首实际使用 Hiyori 原生技术样板，不使用候选图中的生成角色。
- 网格按窗口与字号自适应：列的最小宽度为 150dp，随字体缩放增大；窄窗口或大字体自动单列。单列时添加按钮独占整行，避开现有默认三域切换浮钮的点击位置；标题区可换行，所有内容随网格滚动，长记忆完整显示，不截断正文或把操作放在不可滚动区域。
- `CompanionMemories` 只接收状态和操作回调；页面与卡片按记忆 ID 定位，沿用现有版本检查、持久化、编辑对话框和删除确认。更正及删除不修改原对话，没有改变数据模型、数据库或 AI 访问范围。
- 记忆页不再重复展示原通用页头；保留原三域切换与伙伴底栏。模型仍由既有生命周期承载管理。
- 切换伙伴底栏或打开记忆表单时先释放原输入焦点；记忆输入框限制为最多 240dp 高、3 至 8 行可视区域，长内容在框内滚动，避免无限增高。

## 电脑验证

使用 PowerShell 7、JDK 17、仓库 Gradle Wrapper；不安装应用、不操作设备、不调用远端 AI。

```powershell
.\gradlew.bat :feature:pet:testDebugUnitTest :app:testDebugUnitTest --tests 'com.elio.lifearchive.BusinessHostUiTest' checkArchitecture :feature:pet:lintDebug :app:assembleDebug --console=plain
```

最终上述命令 **PASS**（1 分 21 秒）：宠物模块 5 项测试（页面 3 项、既有 ViewModel 2 项）和宿主 5 项测试，共 **10 项，零失败、零跳过**。`checkArchitecture`、宠物 Debug Lint（无问题）与 Debug APK 构建均通过。完整日志：`D:/Temp/life-archive-memory-b-acceptance.log`；XML 报告分别位于 `feature/pet/build/test-results/testDebugUnitTest/` 和 `app/build/test-results/testDebugUnitTest/`。

新增页面测试检查 360dp 双列、来源与操作身份、200% 字体单列、长文滚动及空状态；宿主原小窗口场景检查真实创建、跨域切换、重建恢复、更正、删除取消与确认。独立 432 × 960dp 场景检查实际文字颜色跟随三种全局主题并导出图片；已逐张回读。尝试把弹窗编辑也移到该原生图形配置时出现过 Robolectric `AppNotIdleException`，尚未确认其设备表现；最终交互测试沿用原宿主窗口，不能将大窗口弹窗行为报告为已验收。

实际 Compose 截图保存在仓库外：

- [暖纸预览](D:/Temp/life-archive-companion-previews/memories-warm.png)
- [浅灰蓝预览](D:/Temp/life-archive-companion-previews/memories-blue.png)
- [深石墨预览](D:/Temp/life-archive-companion-previews/memories-graphite.png)

原生模型在 Robolectric 渲染中留空，不作为模型显示验收。早期小窗口截图发现默认三域浮钮遮挡添加按钮，现已通过单列整行按钮修正，最终小窗口点击与后续编辑回归通过。

目标手机字体、触摸滚动、原生透明叠加及最终视觉仍为 **NOT_VERIFIED**，电脑测试与 APK 构建不代替真机验收。
