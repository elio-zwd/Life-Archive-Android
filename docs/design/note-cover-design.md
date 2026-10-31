# 笔记封面设计与交互

日期：2026-10-31。用户已确认功能方案；生成图是实施参考，最终视觉认可与设备验收单独记录。

## 布局与使用

- 首页封面移到标题与摘要左侧，沿用 60 × 66 dp、8 dp 圆角；文字区底部保留真实日期、笔记本与标签，管理入口位于行右侧。未设置的新旧笔记统一显示山野预设，不再自动使用正文首图。上下间距按后续用户要求采用[紧凑布局](../engineering/phase-1-compact-layout-handoff-20261031.md)。
- 阅读页依次展示开头 H1（原文存在时）、元信息、2.4:1 圆角封面和正文；封面随正文滚动，无 H1 不合成标题。封面不写进 Markdown 或正文投影，不改变目录原文偏移。
- 首页管理、阅读／编辑更多菜单共用“设置封面”面板：预设／自定义切换、效果预览、选择列表、保存／取消。切换选项只改变待保存选择，保存成功才刷新正式封面。
- 四张内置预设为山野、湖泊、林间、纸页，使用同一套低饱和纸感插画。图片裁切居中，兼顾首页小图与阅读横幅；不提供裁剪编辑器。
- 自定义可选笔记中已保存引用的本地图片，或从手机系统选择器选图。手机图片复制到私有库 `_covers/`，不依赖原文件和临时 URI；目前支持系统能够解码且编码大小不超过 8 MiB 的图片。
- 选用正文图片不隐藏原正文图。图片缺失／损坏时显示默认山野，持久选择保留，可重新设置；封面失败不阻塞正文。
- 封面是需要备份的应用状态，不写 YAML；普通 Markdown／ZIP 导出不承诺恢复封面选择，完整备份恢复保留。

## 生图来源与资源

使用内置 `image_gen` 工具，未使用 API／CLI。参考旧首页与阅读截图生成[三屏布局参考](cover-reference/ui-design.png)，然后按图实现原生 UI。生成图中的日期、正文与控件细节不作为业务事实或设备证据。

正式图片位于 `core/designsystem/src/main/assets/covers/`：`mountain.png`、`lake.png`、`forest.png`、`paper.png`。原图完整保留，Android 按需采样，阅读器加载合并后的同源 assets；不是运行时联网生成。

### 布局参考完整提示词

```text
Use case: ui-mockup. Create a polished Android UI design board with THREE tall screen panels side by side for Life Archive Chinese local notes app. Reference images are existing homepage and reader: preserve their simple grey-blue palette, typography hierarchy, compact controls, flat list separators. Panel 1 homepage: Life Archive heading, search, folder pills, ONLY two note rows titled 山野慢行 and 窗边阅读. Each row has a SMALL rounded landscape illustration thumbnail ON THE LEFT of title and excerpt, with date/folder/tags below. Bottom navigation 笔记 / 设置. Panel 2 reader: compact toolbar, then H1 山野慢行, small metadata, a rounded wide 2.4:1 muted layered mountain illustration cover, paragraph, checklist, small edit FAB bottom right. Panel 3 cover selection dialog on soft dim page: title 设置封面, segmented tabs 预设 and 自定义, wide preview, 2x2 image grid captions 山野 湖泊 林间 纸页 with selected blue outline/check, 取消 / 保存. Four covers soft desaturated editorial gouache illustration with subtle paper grain, no text inside illustrations. Background #F4F6F8, ink #20242A, muted #667085, blue #2F67B2. Practical implementable Android Material UI, generous white space, NO decorative extra controls, no gradient background, no glassmorphism. This is a design reference board, not a phone photograph. Render Chinese labels clearly.
```

### 四张封面完整提示词

**mountain**

```text
Use case: illustration-story. Asset: built-in note cover for Life Archive Android, one single continuous image, landscape aspect about 2.4:1. Restrained desaturated blue-grey, sage green and warm cream palette, editorial gouache with fine matte paper grain, simple elegant layered shapes, soft diffuse daylight, quiet contemplative mood. Important subject centered, composition also readable in a small square central crop. Full bleed, no frame, no panels, no labels, no text, no logos, no watermark. Match a cohesive set for a minimal grey-blue notes app. Layered blue-grey mountains, sage grassy foothills, pale sky and a winding walking trail. Mountains are the focus, not lake.
```

**lake**

```text
Use case: illustration-story. Asset: built-in note cover for Life Archive Android, one single continuous image, landscape aspect about 2.4:1. Restrained desaturated blue-grey, sage green and warm cream palette, editorial gouache with fine matte paper grain, simple elegant layered shapes, soft diffuse daylight, quiet contemplative mood. Important subject centered, composition also readable in a small square central crop. Full bleed, no frame, no panels, no labels, no text, no logos, no watermark. Match a cohesive set for a minimal grey-blue notes app. A quiet sage-blue lake with distant misty mountains and sparse reeds, soft reflections and calm open water.
```

**forest**

```text
Use case: illustration-story. Asset: built-in note cover for Life Archive Android, one single continuous image, landscape aspect about 2.4:1. Restrained desaturated blue-grey, sage green and warm cream palette, editorial gouache with fine matte paper grain, simple elegant layered shapes, soft diffuse daylight, quiet contemplative mood. Important subject centered, composition also readable in a small square central crop. Full bleed, no frame, no panels, no labels, no text, no logos, no watermark. Match a cohesive set for a minimal grey-blue notes app. A peaceful woodland footpath framed by muted green trees and softly filtered daylight, warm grey earth.
```

**paper**

```text
Use case: illustration-story. Asset: built-in note cover for Life Archive Android, one single continuous image, landscape aspect about 2.4:1. Restrained desaturated blue-grey, sage green and warm cream palette, editorial gouache with fine matte paper grain, simple elegant layered shapes, soft diffuse daylight, quiet contemplative mood. Important subject centered, composition also readable in a small square central crop. Full bleed, no frame, no panels, no labels, no text, no logos, no watermark. Match a cohesive set for a minimal grey-blue notes app. An open blank notebook on a warm wooden windowsill, a small plant and ceramic mug, soft window light, page texture, subdued sage and grey-blue accents. No writing on pages.
```

## 实现预览

通过真实 Repository 创建“山野慢行”（预设）和“窗边阅读”（复制入库的纸页图片作为自定义附件），关闭重开后查询并读取资源，再交给正式 Compose 与阅读资源生成预览。例图用于合成测试，不访问用户资料库。

运行与实际结果见[封面功能交接](../engineering/phase-1-note-cover-handoff-20261031.md)。首页／面板截图来自 Robolectric；阅读截图来自正式 reader.js／reader.css 的电脑浏览器渲染，不包含 Android 顶栏及系统栏，均不代替设备验收。
