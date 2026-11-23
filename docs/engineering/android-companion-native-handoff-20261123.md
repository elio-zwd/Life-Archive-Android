# 宠物原生模型与窗口接入

日期：2026-11-23。用户已提供 `D:/My_Elio/CubismSdkForJava`，继续此前[公共 AI 与宠物核心](android-companion-core-handoff-20261123.md)。本轮只完成核心样板和软件验证，不把手机行为、正式角色、语音等扩展或后置备份写成完成。

## SDK 与模块

新增有实际调用方的 `core:live2d`，当前共二十二个实际模块，依赖已纳入 `checkArchitecture`。模块没有业务域依赖；`feature:pet` 用它显示模型，app 用它承载系统窗口。原有唯一公共 AI 和宠物 Repository 不变。

- SDK 为 Cubism SDK for Java **5 R5**，包内创建标记 2026-05-29；版本集中在 `libs.versions.toml`。
- Core AAR SHA-256：`3F05DA57AB855E803000E6353888DD561C47758598C6C0200DCD0109312705F8`。
- 固定 164 个 Core、Framework 源码／着色器、Hiyori 和许可文件输入，构建拒绝不符版本／哈希及额外未锁定输入。
- 直接编译本机 Framework，app 打包本机 Core；不提交 SDK 原始二进制或本机路径。只打包 Hiyori 样板及必要着色器，保留三份 SDK 许可说明。
- SDK AAR 含 arm64-v8a、x86、x86_64，未包含 armeabi-v7a。固定包与现有 JDK 17／AGP 构建核对，不升级仓库依赖。

配置和更详细生命周期见 [core:live2d 说明](../../core/live2d/README.md)。没有修改 SDK、旧宠物工程或其他仓库，没有迁移旧数据。

## 三种呈现

| 承载 | 实际行为 |
| --- | --- |
| 伙伴页 | 原生透明 TextureView，按可用高度限制模型区，欢迎内容滚动、输入区固定；模型可收起，键盘出现时移除模型；轻触触发素材动作和本地反馈 |
| App 内小窗 | 用户先进入伙伴并开启显示后，在 Life／见域顶层出现；按实际布局位置限制拖动边界，支持对话入口、收起和最近回复气泡；收起直接回传宿主，不依赖后台伙伴页收集状态；阅读／编辑时不承载，只冷启动 Life 不初始化宠物库或模型 |
| 系统悬浮 | 用户授权后开启前台服务，透明模型、气泡、拖动、展开输入、打开 App 和关闭；锁屏／熄屏暂停，通知停止／权限撤销销毁窗口 |

三种呈现从同一宠物消息快照取得回应标识、状态和情感，映射 CALM／HAPPY／THINKING／CARING；相同回应不因重组反复触发动作，下一次回应即使情感相同也可重新触发。触摸只触发局部动作，不另发 AI 请求或追加消息。Hiyori 无独立表情文件时使用已有参数与动作，不凭空声明素材具备额外表情。

当前模型只作 Hiyori 技术样板，不提供旧 v1／v2／Web 技术模式或正式角色切换。人格和名称可改。原生失效时给出明确提示，文字会话／记忆继续可用。

## 资源与线程

Cubism Framework 的 shader 与 offscreen 缓存为静态成员。所有窗口使用一个专用线程和一个 EGLContext，分别切换 EGLSurface、模型、纹理和动作状态，避免多个独立 Context 误用全局 shader 标识。

页面跟随宿主可见生命周期；隐藏时释放模型和纹理。SurfaceTexture 由绘制线程在 EGLSurface 销毁后释放。最后一个活动窗口关闭时释放 Framework、EGL、GPU 资源并停止帧循环；保留一个空闲 HandlerThread 处理未来重新打开，避免旧线程释放与新线程初始化 SDK 竞争。目标帧间隔约 33ms，只是调度设置，不能据此报告设备稳定 30 FPS 或省电。

进程重启仍按已保存消息恢复，不保证生成任务存活。SDK 与其他代码同进程，没有原生崩溃隔离。没有改变凭据、备份格式或数据库 Schema v1；新增宠物／联合备份仍放到最后。

## 验证范围

新增单测核对隐藏不加载、重复恢复不重复创建、暂停释放、再次显示重建、销毁后忽略迟到事件、失败状态、双窗口生命周期隔离、相同回复动作去重、透明视图冷构造与无障碍点击。

`verify-companion.ps1` 增加原生模块单测／Lint、设备测试 APK 编译和成品 APK 的 JNI／模型／纹理／动作／着色器／许可入口检查。脚本合成回归新增缺少原生库必须失败，不能仅凭 APK 文件存在报告原生接入通过。

`NativeRenderDeviceTest` 真正使用 JNI、EGL pbuffer、模型和像素读取，检查透明／非透明像素并释放重建。当前只在电脑编译，不运行；它与真实窗口合成和交互验收也分别记录。

### 最终电脑结果

Windows 10 x64／PowerShell 7／JDK 17。最终完整脚本在 **2026-11-23 05:10:57 +08:00** 结束，架构、相关 Debug Lint、Debug APK、原生设备冒烟测试 APK 编译和 12 个成品原生资源入口检查通过。

| 有源码测试组 | 测试数 | 失败／跳过 |
| --- | --- | --- |
| 原生承载生命周期／视图 | 9 | 0／0 |
| 公共 AI | 9 | 0／0 |
| 宠物存储 | 5 | 0／0 |
| 宠物 ViewModel | 2 | 0／0 |
| 见域数据／备份 | 39 | 0／0 |
| 见域领域 | 44 | 0／0 |
| 定向 app 宿主与备份 | 15 | 0／0 |
| 合计 | **123** | **0／0** |

脚本自身另有 **18 项合成断言**通过，包括缺少原生库、无有效测试、构建失败时不得报告通过，以及拒绝覆盖旧证据目录。首轮发现并修正 Properties 盘符转义；布局修正经过最终完整脚本重跑。新增宿主用例实际检查伙伴页后台时的小窗显示／拖动后手柄可见／收起；原伙伴页用例增加小屏输入框和发送按钮可见断言。Robolectric 截图中没有真实 GPU 模型，不能据此验收 Live2D。

Core Lint 为 0 错误、1 条上游 Framework 注释警告；app／见域还有其他警告，未为此升级依赖或修改 SDK。UTF-8、本地链接与 Git 差异空白检查通过。

证据目录：`D:/Temp/life-archive-companion-native-20261123-delivery/`，包含 `summary.json`、`data-tests.log`、`host-tests.log`、`build-lint.log`。脚本自身证据：`D:/Temp/life-archive-companion-script-tests-7382c13928d247b98daf316d722cbb58/`。

最终 APK：`app/build/outputs/apk/debug/app-debug.apk`，源码版本仍为 `0.5.0-dev.2`（versionCode 12），SHA-256：`79FF788CB3298886DA5872B7474AE565977D9AD43432070CA42831FF5E591E60`。原生设备测试包在 `core/live2d/build/outputs/apk/androidTest/debug/live2d-debug-androidTest.apk`。

本轮没有安装应用、执行 ADB 或读取真实远端 Key。设备、真实远端服务、帧时间／内存／功耗均未验证，状态为 `NOT_VERIFIED`；原生包为 `PACKAGED_NOT_DEVICE_VERIFIED`，JNI／EGL 冒烟为 `COMPILED_NOT_RUN`。详见[设备清单](../testing/android-companion-manual-acceptance.md)。新增备份继续后置。

## 2026-11-23 黑色剪影修复与模拟器验证

用户反馈伙伴页、设置页及悬浮窗口均只显示黑色剪影。原因是 SDK 的 `CubismShaderAndroid.setUpTexture` 会将缩小过滤设为 `GL_LINEAR_MIPMAP_LINEAR`，宿主上传纹理后却未生成 mipmap，导致纹理不完整。现已在上传后生成完整 mipmap，不修改 SDK 或模型素材。

原生像素测试增加彩色像素数量断言，保留透明背景及释放重建检查。在用户授权的雷电模拟器（x86_64、Android 9 / API 28）上，修改前彩色像素为 0，测试失败；修复后 1 项原生 JNI／EGL 测试通过，两次资源创建均通过。9 项本地生命周期／视图测试、架构检查、core:live2d 与 app Debug Lint、Debug APK 和原生测试 APK 构建通过。

已保留数据覆盖更新模拟器应用，实际截图核对伙伴页、设置缩略角色、Life 页 App 内小窗、系统桌面悬浮角色均恢复彩色显示；往返切换及后台返回后仍可显示。本次没有连接真实手机，也未执行清库或卸载；桌面悬浮测试后已关闭，原有 App 内小窗设置保留。模拟器显示结果不替代小米手机实测或性能验收。

证据在仓库外 `D:/Temp/life-archive-role-render-20261123/`：`render-before.log`、`render-after.log`、`build-after.log` 以及 `before.png`、`conversation-settled.png`、`settings-settled.png`、`in-app-settled.png`、`desktop-after/observation.png`。

最新 APK 仍为 `0.5.0-dev.2` / versionCode 12，包含此前切换菜单焦点修复，输出为 `app/build/outputs/apk/debug/app-debug.apk`；交付副本 `D:/Temp/life-archive-role-render-20261123/LifeArchive-0.5.0-dev.2-role-color-fix.apk`。SHA-256：`434CEBC6BDF234CEB847A3CDCA0B48FBF50C2CBADBE312A57FF9B054F978EB7A`。上节哈希及未运行结论为修复前历史记录。

## 后续

1. 用户在小米 14 Ultra 使用隔离资料验证 JNI／EGL 冒烟、三类窗口、透明合成、触摸与动作、权限与通知、资源和性能；需记录实际结果。
2. 本轮新增伙伴相关界面提供[六组 A／B／C 候选图片](../design/companion-candidates-20261123/README.md)，供选择后再移植正式布局；候选图不是运行截图或验收证据。
3. 正式形象之后选择；语音、主动问候、成长收藏、自律辅助逐项确定。宠物与新增整 App 备份放到最后，既有备份继续保留。
