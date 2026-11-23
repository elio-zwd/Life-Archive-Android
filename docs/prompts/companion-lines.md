# 桌面伙伴台词库维护 Prompt

本文件供 Codex 等电脑端 AI 预先完善台词库。App 随包读取资源，运行时不需要电脑在线。真人格与记忆由运行时 AI 管理，通用台词不假装记得用户的具体经历。

## 使用方式

在 Life Archive 仓库使用 `$life-companion-lines`，或把以下任务提示词交给电脑端 AI；描述希望补充的场景、语气或条数。未给条数时补充 20 条。维护资源为 `data/pet/src/main/assets/companion-lines.json`。

## 可复制的任务提示词

你在为 Life Archive Android 的桌面伙伴完善本地台词库。先阅读本仓库 AGENTS.md、本文件、现有台词库，以及 `domain/pet` 的 `CompanionTrigger`、`CompanionEmotion` 和 `data/pet` 的词库解析器，以实际枚举和测试为准。

伙伴温和、自然、有主见，能安静陪伴。台词是角色当下的轻微回应，不是客服提示、系统状态或说教。用户可以不回应；不索取关注、不制造亏欠、不催促学习工作、不宣称看到了其他 App 或知道用户行踪。不虚构共同经历，不把单次触摸解释为关系升级。不写拟声词堆叠、连续撒娇、占有欲或惩罚台词。

按用户指定场景和条数增量补充；未指定条数补充 20 条，不顺手替换整库。先看已有文本，避免仅换同义词的重复。保留原条目的稳定 ID；新增 ID 按所属前缀的最大编号继续，不能因排序、删条而复用旧 ID。删除或重写已有条目仅在本轮任务明确要求时进行。

每条格式如下，只允许资源约定的字段：

```json
{"id":"head-025","text":"给你一个小小的笑。","trigger":"HEAD","emotion":"HAPPY"}
```

| trigger | 场景 | 用词要求 |
| --- | --- | --- |
| HEAD | 轻点头部 | 简短招呼、微笑、被轻触的反应 |
| BODY | 轻点身体 | 自然回应，适合小动作，不描述素材没有的复杂动作 |
| STROKE | 全身抚摸 | 舒适、安静、跟随，不催促继续抚摸 |
| START | 主动开启桌面陪伴 | 初见招呼，不声称刚才一直在后台等待 |
| RETURN | 久别后解锁 | 温和欢迎，不猜测用户离开做了什么 |

`emotion` 只能为 `CALM`、`HAPPY`、`THINKING`、`CARING`。普通本地回应优先前三者中的 CALM／HAPPY 或 CARING，THINKING 只用于确实在思考的台词。文字为简体中文，一条一个自然短句，1～24 个字符（标点也计入），不换行，不放 Markdown、URL、占位变量或操作指令。主动台词不连续提问、不要求回复。每个场景至少保留 24 条，全库至少 120 条。

完成后核对 JSON、重复 ID、重复文本、长度、枚举和场景覆盖。使用本仓库 JDK 17 与 PowerShell 7 运行：

```powershell
./gradlew.bat :data:pet:testDebugUnitTest --tests '*DesktopCompanionTest.bundledVocabularyHasUniqueShortLinesForAllTriggersAndAvoidsRecentRepeats' --console=plain
git diff --check
```

如果只能产出文本而不能访问仓库，输出待增补的 JSON 数组并说明尚未合并和运行测试，不声称已经接入。能访问仓库时直接做本次授权的词库编辑；不修改模型、凭据、聊天或记忆，不自动提交、推送、安装应用。

交付时简要写明新增／修改数量、场景覆盖和实际检查结果，可列 3～5 条有代表性的台词供检查。

## 当前资源与边界

- 首批 120 条，五个场景各 24 条；近期 12 条不重复。
- 桌面触碰动作始终本地执行，文字有节制地出现；台词没有自动记忆写入权限。
- “本地＋AI”使用全 App 公共 AI 的独立 `PET_GREETING` 用途；本文件负责随包本地库，不触发运行时服务。
