# 共同记忆顶部人物补图

日期：2026-11-23。用户反馈 B「手记卡片」顶部缺少人物图片，本次补齐随包人物插画；正式伙伴形象仍待选择。

## 实际变更

此前记忆页头仅放置原生 Live2D View，在电脑渲染中人物为空，原生失败时也没有人物图片兜底。现改为右侧 168 × 184dp 的随包透明人物插画，顶部对齐展示上半身，左侧保留真实名称、技术样板说明和寄语，适配全 App 三套配色。名称最多两行，人物不覆盖文字；两列手记、来源标签、编辑、删除及底部弹层维持现有行为。

此图片仅用于共同记忆页顶部，离线即可显示；该页头不再创建 GL 模型。陪伴页、设置身份卡片和两类悬浮继续沿用原生 Live2D，动态大形象没有被静态图片替换。未改数据库、AI、SDK 或备份范围。

## 图片来源

- 使用内置 imagegen，根据[共同记忆 B 比较板](companion-candidates-20261123/02-memory-options.png)中间栏的角色风格新生成透明半身插画，并用同一工具清理边缘；没有从纹理图集拼接或宣称是官方模型截图。
- 项目资源：[companion_memory_portrait_v1.png](../../feature/pet/src/main/res/drawable-nodpi/companion_memory_portrait_v1.png)。原图直接复制入仓库，保留透明通道，未用脚本裁剪或重绘。
- SHA-256：`21ACBAD70CC415200CCD0F3BE975EB89EFF59A16E2608EC04606BAE7F9394ED2`；1086 × 1448 RGBA PNG，1,833,281 字节。展示素材不代表正式角色已冻结。

## 本次实际提示词

生成使用比较板为参考，透明背景：

```text
Use case: illustration-story.
Asset type: transparent character portrait asset for the RIGHT SIDE of the existing Android companion memory-page header, not a screen mockup.
Input image 1 is a STYLE AND CHARACTER REFERENCE ONLY. Study specifically the blonde woman at the TOP of the CENTER phone labelled B 手记卡片. Create a clean standalone portrait closely matching that woman's character design and gentle anime illustration style: long pale honey-blonde hair, black ribbon bow behind one side of her hair, soft ivory white blouse/cardigan, friendly closed-eye natural smile, warm and calm everyday-companion expression.
Composition: one adult woman only, head and upper torso / chest-up portrait, three-quarter view slightly toward viewer-left, upright with slight natural head tilt. Keep entire head, hair and black bow visible with breathing room. Shoulders and torso visible down to waist, cropped below the waist. Center the figure so it works when fitted into a narrow portrait area. No extra figures, no extra limbs, no wings, no animal ears.
The memory UI uses a themed background drawn by code, so OUTPUT A TRULY TRANSPARENT BACKGROUND with a crisp soft natural alpha edge around hair. No room, furniture, frame, title, UI, text, phone, watermark, background texture or shadows on a backdrop. Warm softly painted anime art, detailed face and hair, elegant restrained shading. Match the CENTER B reference, not the left or right UI. This is a temporary app illustration; do not add brand marks. Portrait aspect ratio about 3:4, high-quality PNG cutout.
```

边缘清理使用首张透明人物图为编辑目标，保留透明背景：

```text
Edit target: the supplied transparent portrait. Preserve the adult woman's face, closed-eye smile, pose, hair, black ribbons, outfit, framing, colors, proportions and softly painted anime style exactly. Clean only the cutout boundary and transparent background: remove every scattered yellow/red/green dot, tiny stray colored speck, disconnected white fleck and light fringe outside the silhouette. Produce a smooth clean antialiased alpha boundary around the hair and clothing, keeping natural main hair strands, with all exterior background pixels truly transparent. No halo, glow, confetti, backdrop or new elements. This portrait will be displayed on warm cream, pale blue AND dark slate UI backgrounds, so the outline must remain clean on all three. Preserve the transparent background.
```

## 验证

PowerShell 7、JDK 17 下执行：

```powershell
.\gradlew.bat :feature:pet:testDebugUnitTest :app:testDebugUnitTest --tests '*BusinessHostUiTest' checkArchitecture :feature:pet:lintDebug :app:assembleDebug --console=plain
```

**PASS**：21 项定向测试（宠物 feature 14 项、宿主 7 项），零失败、零跳过；架构检查、宠物 Lint（无问题）和 Debug APK 构建通过。验证资源透明通道、人物中心非透明、实际 Image 可见且不与名称重叠，以及卡片、底部弹层与三域既有流程。较短桌面测试窗口须滚动至记忆条目再核对，不假定条目位于首屏。

APK 内已核对 `res/drawable-nodpi-v4/companion_memory_portrait_v1.png` 存在且大小一致。实际日志：`D:/Temp/life-archive-memory-portrait-20261123-accepted.log`；APK 为 `app/build/outputs/apk/debug/app-debug.apk`，SHA-256：`A39A04A340A31907E628EBAF58CD69150E9BB9DD2E34606A8964294D247C0C0F`。

已回读实际 Compose 页面：[暖纸](D:/Temp/life-archive-companion-previews/memories-warm.png)、[浅灰蓝](D:/Temp/life-archive-companion-previews/memories-blue.png)、[深石墨](D:/Temp/life-archive-companion-previews/memories-graphite.png)。人物在三套配色中均可见；这些是桌面运行预览，手机视觉仍待验收。本轮没有重新运行全部公共 AI／见域测试，也没有操作设备或真实资料。
