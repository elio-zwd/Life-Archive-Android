# 原生 Live2D 承载

当前固定 **Cubism SDK for Java 5 R5（2026-05-29）**，版本记录在 `gradle/libs.versions.toml`，164 个实际编译／资源输入的 SHA-256 在 `gradle/cubism-sdk.sha256`。Core AAR 包含 arm64-v8a、x86、x86_64；没有 armeabi-v7a。应用标识仍为 `com.elio.lifearchive`。

## 本机配置

从 [Live2D 官方](https://www.live2d.com/en/sdk/download/java/)取得完整 SDK 并按页面要求处理许可。解压到仓库外，在已忽略的根目录 `local.properties` 增加：

```properties
cubism.sdk.dir=D\:/My_Elio/CubismSdkForJava
```

Properties 中的盘符冒号需写作 `\:`；环境变量使用普通路径，不加这个转义。

也可使用环境变量 `CUBISM_SDK_DIR`（优先）。目录需包含 `cubism-info.yml`、`Core/android/Live2DCubismCore.aar`、`Framework/framework/src/main`、`Sample/src/main/assets/Hiyori`。无需运行 SDK 自己的 Gradle 或安装其他 JDK。

`verifyCubismSdk` 拒绝版本／文件不符或额外未锁定的 Framework／Hiyori 输入。Framework 从本机源码编译，Core 由 app 打包，SDK 二进制及绝对路径不提交 Git。只将 Hiyori 和 Framework 着色器打包，不打包其他模型、样例应用、签名材料。SDK／Core／Framework 许可说明随 APK 的 `assets/live2d/licenses` 分发；Hiyori 仍只作技术样板，正式形象与发布素材另选。

## 生命周期与接口

`Live2DView` 是透明 TextureView，公开 `setActive`、`setCue(token, mood)`、触摸反馈和状态回调，不依赖宠物业务域。情感只接受 CALM／HAPPY／THINKING／CARING；模型不执行 AI 指令。feature 负责把持久化消息映射成 cue，app 负责系统窗口。

SDK 的 shader／offscreen 缓存为静态成员，多个独立 EGLContext 不能直接共用这些缓存。本实现只使用一个绘制线程与 EGLContext，分别切换窗口 EGLSurface；模型、纹理和动作状态按窗口隔离。没有复制或改写 SDK 源码来规避该约束。

- 页面根据宿主 Lifecycle 的 RESUMED 状态启停，切域、离开页面、收起形象、显示键盘时释放对应模型和纹理。
- 系统窗口在锁屏／熄屏后暂停；权限撤销／停止服务时释放窗口及资源。
- TextureView 销毁后由绘制线程先销毁 EGLSurface，再释放 SurfaceTexture，避免 swap 与释放并发。
- 最后一个活动窗口关闭后释放 Framework、EGL 和 GPU 资源，停止帧循环；保留一个空闲 HandlerThread 串行处理后续打开，Life 冷启动不创建它。
- 失败回调保留文字会话；切换页面可重新建立 Surface。原生崩溃不具备同进程隔离保证。

## 检查

根目录 `verify-companion.ps1` 运行有源码单测、架构、Lint、APK 与原生设备冒烟测试 APK 编译，并核对最终 APK 的 JNI／模型／纹理／动作／着色器／许可入口。它不连接设备。

`src/androidTest/.../NativeRenderDeviceTest.kt` 会实际调用 JNI、创建 EGL pbuffer、检查模型像素和透明像素、释放再重建。电脑只编译，不报告其通过；通过授权后可在隔离的 instrumentation 包上运行。该测试也不代替小米 14 Ultra 的实际窗口合成、触摸命中、帧时间、内存或功耗验收。
