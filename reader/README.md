# 本地阅读运行资源与浏览器检查

0.2.2 使用首屏优先及前后一屏按需渲染，复杂库仍离线打包；缓存只保存派生投影/SVG/尺寸，接口不向笔记或离线网页开放私有路径。兼容检查通过 readerDrain 主动遍历，另测屏外延后、缓存命中不加载复杂库、字号/宽度失效、折叠加载和嵌入目标更新。

打开性能脚本为 test/open-performance.mjs，依赖 app 本地测试导出的实际样例和配图。冻结 baseline、参数及测量边界见[验证说明](../docs/testing/README.md)，结果见[提速交接](../docs/engineering/phase-1-reader-performance-handoff.md)。

此目录负责固定、组装阅读器资源，不保存笔记或用户缓存。现有 Node.js 24、npm、PowerShell 7 与 Windows Chrome/Edge 用于电脑检查；Android 工具链仍为 JDK 17、SDK 36。

`package-lock.json` 固定 MathJax 4.1.3、Mermaid 12.2.0、PDF.js 6.4.299、DOMPurify 3.4.17，以及测试用 Playwright 1.58.2。Gradle 在首次构建时执行 `npm.cmd ci --ignore-scripts`，再把需要的资源、字体及许可证复制到 feature:editor 的被忽略构建目录。APK 内不引用 CDN。不要手工修改 node_modules 或生成资源。

```powershell
.\gradlew.bat testReader --console=plain
.\gradlew.bat verifyPhaseOne --console=plain
```

在仓库根目录运行。浏览器检查读取 core:document 测试生成的同一份 HTML 投影，启动仅监听 127.0.0.1 的临时服务器，并在结束时关闭服务器和无头浏览器。默认查找 Windows Chrome/Edge；其他位置可用 `LIFE_ARCHIVE_BROWSER` 指定。截图默认写入系统 TEMP 下 `life-archive-phase1-browser`；`LIFE_ARCHIVE_EVIDENCE` 可改输出目录。

包含浅深色公式、Mermaid、表格及 Callout、章节嵌入及循环处理、脚注、HTML 脚本清理、默认不访问远程资源、50 MiB 级合成 PDF 翻页，以及合成 WAV/MP4 的浏览器解码与定位检查。MP4 由本机 FFmpeg 生成蓝色画面，无外部素材；WAV 与大 PDF 在测试进程内生成。FFmpeg 不是运行测试的依赖。

稳定性检查另外采样浅深首帧，延迟图表库验证原子展示，检查嵌入链接的来源笔记、旧渲染回执、刷新不清空整页、配置及单资源更新保留其他媒体/Callout 会话，以及普通正文不加载复杂渲染库。

浏览器检查不能代替 Android WebView、目标机媒体编码、系统文件选择器或中文输入验收。最新结果见[稳定性修复交接](../docs/engineering/phase-1-stability-handoff.md)，完整支持状态见[功能交接](../docs/engineering/phase-1-function-handoff.md)。

HTML 视觉样板可用 `node reader/capture-design.mjs --out D:/新的空目录` 在仓库根目录导出小米 14 Ultra UI 交接包。脚本复用这里的 Playwright，固定 432 × 960 逻辑画布，直接渲染 1440 × 3200 PNG，不连接手机；详情见 [UI 实施交接](../docs/design/xiaomi14ultra-ui-handoff.md)。这与 Android 完整阅读器资源、真机验收分别记录。
