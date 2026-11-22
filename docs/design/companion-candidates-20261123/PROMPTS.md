# 伙伴 UI 候选图生成提示词

日期：2026-11-23。使用内置 `image_gen.imagegen`，未使用 CLI 或 App 的公共 AI Key。以下英文为实际工具输入，保留原文供溯源；生成结果并非确定性重现。比较板背景不透明，人物透明合成为图中示意。

## 陪伴页

输出：`01-conversation-options.png`。

```text
Use case: ui-mockup. Create a high-fidelity Android UI design comparison board for Life Archive's new companion domain. Landscape, high resolution, three equally sized flat front-facing 20:9 phone screens, generous gutters and crisp legible simplified Chinese typography. This is a candidate design, not a real app screenshot. Clearly label columns A, B, C. A: light gray-blue, calm Material 3, #F7F9FC with restrained #5277A4 accents. B: warm paper, #F5EFE4, muted olive/amber, intimate but uncluttered. C: deep graphite, #202329, soft white text with muted icy-blue accents. Layouts must differ substantially, not just colors. Consistent 48dp touch targets, readable contrast, safe area, bottom navigation '陪伴 记忆 设置'. Small optional Life/见域/伙伴 domain switch affordance. Do not introduce voice, growth rewards, streaks, scores, note-library access, backups, subscriptions or features outside text companion chat, editable memories/personality, shared AI, and optional overlays. No watermark. Character where needed is a consistent modest blonde anime sample companion in white blouse and dark skirt; label it 'Hiyori · 技术样板', not a final character choice. Keep all UI within screen, no clipped input or bottom actions.

Board title '陪伴页 · 三种布局'. A '分区陪伴': persistent medium-sized character stage in upper third, a tidy scrolling conversation below, anchored text input and Send button above bottom navigation. B '对话优先': compact character portrait beside a speech card at top, mostly conversational text area and generous breathing room. C '沉浸陪伴': large softly lit character stage, translucent but readable dark conversation sheet in bottom half, input always visible. All variants show title '伙伴', shortcut '公共 AI', control '收起形象', a user bubble '刚忙完，想出去走走。', companion bubble '慢慢走一走，换换心情吧。', small action '记住这件事', text input placeholder '和伙伴说说话', button '发送'. No microphone button. Show a functional, quiet everyday companion, not a game dashboard.
```

## 共同记忆

输出：`02-memory-options.png`。

```text
Use case: ui-mockup. Create a high-fidelity Android UI design comparison board for Life Archive's new companion domain. Landscape, high resolution, three equally sized flat front-facing 20:9 phone screens, generous gutters and crisp legible simplified Chinese typography. This is a candidate design, not a real app screenshot. Clearly label columns A, B, C. A: light gray-blue, calm Material 3, #F7F9FC with restrained #5277A4 accents. B: warm paper, #F5EFE4, muted olive/amber, intimate but uncluttered. C: deep graphite, #202329, soft white text with muted icy-blue accents. Layouts must differ substantially, not just colors. Consistent 48dp touch targets, readable contrast, safe area, bottom navigation '陪伴 记忆 设置'. Small optional Life/见域/伙伴 domain switch affordance. Do not introduce voice, growth rewards, streaks, scores, note-library access, backups, subscriptions or features outside text companion chat, editable memories/personality, shared AI, and optional overlays. No watermark. Character where needed is a consistent modest blonde anime sample companion in white blouse and dark skirt; label it 'Hiyori · 技术样板', not a final character choice. Keep all UI within screen, no clipped input or bottom actions.

Board title '共同记忆 · 三种布局'. A '舒展列表': one-column memory cards with comfortable spacing, clear source labels and visible Correct/Delete actions. B '手记卡片': two-column modest paper cards for short memories, a warm personal notebook feel, understated source marks. C '紧凑分组': dense but legible rows grouped only by source ('手动添加' and '来自共同对话'), trailing overflow for corrections/deletion. Exact main title '共同记忆', action '添加记忆'. Sample facts '周末喜欢散步', '喜欢安静的交流方式', '忙的时候，希望先听我说完'. Actions '更正', '删除'. Helper '记忆可以随时更正或删除。' Show no invented mood analytics, chronology timestamps, automatic extraction, search, rewards or metrics. This is manageable relationship memory, not a photo album.
```

定点编辑输入（以首次生成图为参考）：

```text
Edit this UI comparison image precisely. Keep every layout, character, color, memory card, Chinese label and footer unchanged. Fix only the domain selectors: in the board header at top right AND in each of the three phone headers, the selected business must be '伙伴'. Currently '见域' is highlighted incorrectly. Move the filled selection capsule/background from 见域 to 伙伴 in all four selectors; 见域 and Life must be unselected plain text. Do not rename the domains, do not add features, do not otherwise redraw the UI. Relationship memories on these screens belong to the companion domain.
```

## 伙伴设置

输出：`03-settings-options.png`。

```text
Use case: ui-mockup. Create a high-fidelity Android UI design comparison board for Life Archive's new companion domain. Landscape, high resolution, three equally sized flat front-facing 20:9 phone screens, generous gutters and crisp legible simplified Chinese typography. This is a candidate design, not a real app screenshot. Clearly label columns A, B, C. A: light gray-blue, calm Material 3, #F7F9FC with restrained #5277A4 accents. B: warm paper, #F5EFE4, muted olive/amber, intimate but uncluttered. C: deep graphite, #202329, soft white text with muted icy-blue accents. Layouts must differ substantially, not just colors. Consistent 48dp touch targets, readable contrast, safe area, bottom navigation '陪伴 记忆 设置'. Small optional Life/见域/伙伴 domain switch affordance. Do not introduce voice, growth rewards, streaks, scores, note-library access, backups, subscriptions or features outside text companion chat, editable memories/personality, shared AI, and optional overlays. No watermark. Character where needed is a consistent modest blonde anime sample companion in white blouse and dark skirt; label it 'Hiyori · 技术样板', not a final character choice. Keep all UI within screen, no clipped input or bottom actions.
If any Life/见域/伙伴 domain switch is shown, ONLY 伙伴 is selected. Life and 见域 are unselected. All relationship memory belongs to the companion domain, never to Jianyu.

Board title '伙伴设置 · 三种布局'. A '清晰分组': concise full-width grouped settings list with visible profile summary and independent display sections. B '身份卡片': an elegant compact character identity card at top, below it two large practical cards for in-app display and desktop companion. C '分段设定': dark page with horizontal section pills '伙伴 显示', editable persona summary and clear per-surface actions. Maintain all existing capabilities, no speculative feature toggles. Text: heading '伙伴设定'; name '伙伴'; personality '温和、自然、有主见。认真倾听，尊重你的选择。'; action '修改名称与人格'; separate switch '在 App 内显示伙伴'; section '桌面陪伴'; button '开启桌面陪伴'; secondary '关闭'; shortcut '公共 AI'; note '开启桌面陪伴时需要悬浮窗权限。'. Tiny label 'Hiyori · 技术样板'. Keep labels and controls spacious and visible.
```

定点编辑输入（以首次生成图为参考）：

```text
Precisely edit only one helper sentence in this comparison image. In the MIDDLE phone, under the switch label '在 App 内显示伙伴', replace the current helper sentence with: '在 Life／见域页面显示伙伴小窗。' The switch governs mini windows in the Life/Jianyu domains, not the character stage in the companion conversation page. Keep every other word, layout, character artwork, color, control, icon and all three phone frames exactly unchanged. Do not add extra controls or move anything.
```

## 公共AI设置

输出：`04-shared-ai-options.png`。

```text
Use case: ui-mockup. Create a high-fidelity Android UI design comparison board for Life Archive's new companion domain. Landscape, high resolution, three equally sized flat front-facing 20:9 phone screens, generous gutters and crisp legible simplified Chinese typography. This is a candidate design, not a real app screenshot. Clearly label columns A, B, C. A: light gray-blue, calm Material 3, #F7F9FC with restrained #5277A4 accents. B: warm paper, #F5EFE4, muted olive/amber, intimate but uncluttered. C: deep graphite, #202329, soft white text with muted icy-blue accents. Layouts must differ substantially, not just colors. Consistent 48dp touch targets, readable contrast, safe area, bottom navigation '陪伴 记忆 设置'. Small optional Life/见域/伙伴 domain switch affordance. Do not introduce voice, growth rewards, streaks, scores, note-library access, backups, subscriptions or features outside text companion chat, editable memories/personality, shared AI, and optional overlays. No watermark. Character where needed is a consistent modest blonde anime sample companion in white blouse and dark skirt; label it 'Hiyori · 技术样板', not a final character choice. Keep all UI within screen, no clipped input or bottom actions.
If any Life/见域/伙伴 domain switch is shown, ONLY 伙伴 is selected. Life and 见域 are unselected. All relationship memory belongs to the companion domain, never to Jianyu.

Board title '公共 AI · 三种布局'. A '分区对话框': a spacious rounded dialog over a dim companion page, model selection then shared Key section. B '独立设置页': full-screen settings with provider tabs and a focused model selector, a clear masked Key list below. C '底部面板': a tall dark bottom sheet, provider dropdown and model selector, then Key import section. Exact title '公共 AI 设置'. Prominent helper '与见域共用同一份 Key，伙伴对话可独立选择模型。'. Provider choices 'Gemini' and 'DeepSeek'. Label '伙伴对话模型'; list example 'Gemini Flash', 'DeepSeek Chat'. Masked key display '•••• a1b2' and '•••• c3d4', statuses '未校验', '可用', all purely illustrative. Empty masked import field '导入 Key', primary button '导入并校验', finish '完成'. Never show a raw key, actual credential, per-domain Key ownership, billing, usage quota, subscription, or backup. Show both provider support and one shared credential owner.
```

## 悬浮形态

输出：`05-overlay-options.png`。

```text
Use case: ui-mockup. Create a high-fidelity Android UI design comparison board for Life Archive's new companion domain. Landscape, high resolution, three equally sized flat front-facing 20:9 phone screens, generous gutters and crisp legible simplified Chinese typography. This is a candidate design, not a real app screenshot. Clearly label columns A, B, C. A: light gray-blue, calm Material 3, #F7F9FC with restrained #5277A4 accents. B: warm paper, #F5EFE4, muted olive/amber, intimate but uncluttered. C: deep graphite, #202329, soft white text with muted icy-blue accents. Layouts must differ substantially, not just colors. Consistent 48dp touch targets, readable contrast, safe area, bottom navigation '陪伴 记忆 设置'. Small optional Life/见域/伙伴 domain switch affordance. Do not introduce voice, growth rewards, streaks, scores, note-library access, backups, subscriptions or features outside text companion chat, editable memories/personality, shared AI, and optional overlays. No watermark. Character where needed is a consistent modest blonde anime sample companion in white blouse and dark skirt; label it 'Hiyori · 技术样板', not a final character choice. Keep all UI within screen, no clipped input or bottom actions.
If any Life/见域/伙伴 domain switch is shown, ONLY 伙伴 is selected. Life and 见域 are unselected. All relationship memory belongs to the companion domain, never to Jianyu.

Board title '悬浮陪伴 · 三种布局'. Three columns A, B, C and TWO rows of flat small phone screens (six screens total), top row labeled 'App 内', bottom '系统悬浮'. A '轻量小窗': small transparent full-body character, simple readable speech bubble, discreet drag handle and '对话 收起' actions. B '贴边陪伴': smaller portrait anchored to screen edge, a connected speech bubble, expandable tiny input card; gentle warm tone. C '收放自如': compact edge dock that opens into a clear dark companion panel; show the expanded character and input controls in each phone. Top row background is a faint existing notes list, not a redesigned notes app. Bottom row background a neutral Android wallpaper, not another service. Companion is Hiyori technical sample; speech '慢慢来，我在这里。'. System expanded controls '发送 打开 关闭'; input '和伙伴说说话'. Explicit visual distinction between in-app and system window while preserving the same companion identity. No microphone, countdowns, proactive notifications, rewards or accessing background app content. Character artwork outside its body must be transparently composited onto the underlying screen.
For overlay-only screens do NOT add the companion page bottom navigation over an Android homescreen. The in-app sample stays in Life with a tiny companion overlay; the system sample shows Android wallpaper with no app navigation chrome.
```

定点编辑输入（以首次生成图为参考）：

```text
Edit this comparison board while preserving its A/B/C layout concepts, two rows of three phones, character artwork, colors, wallpaper, and overall composition.
Make these precise product-scope corrections:
1. In ALL THREE TOP phones, the underlying app is Life notes. Highlight Life in the top domain selector, with 见域 and 伙伴 unselected. Their bottom navigation must have exactly TWO tabs: 笔记 (selected) and 设置 (unselected). Remove 陪伴/记忆 bottom tabs from the Life background.
2. Remove EVERY occurrence of 叠加层, 镜面, 氛围/外观扩展, and their stacked-layer icons. No such feature exists. Do not add any new feature in its place.
3. Floating controls must not offer inline memory/personality editing. In the middle top phone use a compact action row 对话 / 收起 below the input. In middle bottom phone use 打开 / 关闭 below the input. In right top floating card replace the 记忆/性格/叠加层 icon row with just 对话 / 收起. Keep the existing bottom-right system panel 发送 / 打开 / 关闭.
4. Replace left top explanatory copy with: "在 Life 中以伙伴小窗陪伴，不读取笔记内容。" and four bullets "保留 Life 内容与底栏", "同一伙伴、同一会话", "可对话、拖动与收起", "使用全 App 公共 AI".
5. Replace left bottom explanatory copy with: "在桌面或其他 App 上显示，窗口分别管理。" and four bullets "主动开启与授权", "对话／发送／打开／关闭", "记忆与人格在 App 内管理", "无 App 底栏或域切换".
The system row must keep Android wallpaper with no app business navigation. Retain native transparent character cutouts and simple speech bubbles. All Chinese must be crisp. No voice, rewards, appearance-layer tools or new capabilities.
```

## 编辑弹层

输出：`06-editor-options.png`。

```text
Use case: ui-mockup. Create a high-fidelity Android UI design comparison board for Life Archive's new companion domain. Landscape, high resolution, three equally sized flat front-facing 20:9 phone screens, generous gutters and crisp legible simplified Chinese typography. This is a candidate design, not a real app screenshot. Clearly label columns A, B, C. A: light gray-blue, calm Material 3, #F7F9FC with restrained #5277A4 accents. B: warm paper, #F5EFE4, muted olive/amber, intimate but uncluttered. C: deep graphite, #202329, soft white text with muted icy-blue accents. Layouts must differ substantially, not just colors. Consistent 48dp touch targets, readable contrast, safe area, bottom navigation '陪伴 记忆 设置'. Small optional Life/见域/伙伴 domain switch affordance. Do not introduce voice, growth rewards, streaks, scores, note-library access, backups, subscriptions or features outside text companion chat, editable memories/personality, shared AI, and optional overlays. No watermark. Character where needed is a consistent modest blonde anime sample companion in white blouse and dark skirt; label it 'Hiyori · 技术样板', not a final character choice. Keep all UI within screen, no clipped input or bottom actions.
If any Life/见域/伙伴 domain switch is shown, ONLY 伙伴 is selected. Life and 见域 are unselected. All relationship memory belongs to the companion domain, never to Jianyu.

Board title '编辑弹层 · 三种布局'. Three columns A, B, C and two rows of enlarged phone UI sections (six panels). Top row '更正记忆', bottom row '修改伙伴设定'. A centered responsive dialog, B comfortable bottom sheet, C focused full-screen editor; show clear differences in space usage. Memory editor exact title '更正记忆', multiline text '周末喜欢散步，希望聊天时自然一些。', source label '来自共同对话', helper '只更正这条记忆，原对话仍保留。', actions '取消 保存'. Profile editor exact title '伙伴设定', labeled name field '名字' containing '伙伴', labeled multiline field '人格与相处方式' containing '温和、自然、有主见。认真倾听，尊重我的选择。', actions '取消 保存'. Preserve readable text, visible sticky bottom actions, enough multiline editing space and a restrained native Android appearance. No keyboard rendering needed. Do not add model instructions, code, JSON, rewards, or fictional forms. Show only candidate editing layouts, not functional screenshots.
In these editor screens, the relevant bottom nav is 记忆 for memory editing and 设置 for personality editing. Do not mark 陪伴 selected on these editing examples. Stick to the text fields described; the design board is not an AI chat screen.
```
