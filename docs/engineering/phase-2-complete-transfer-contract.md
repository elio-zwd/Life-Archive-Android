# 见域完整迁移与独立恢复契约

实现状态：2026-11-14 完整格式 v1、Room v18 同源范围已实现并做合成资料电脑验证，见[交接](phase-2-complete-backup-handoff.md)。真实手机数据迁入尚未验收。仅对见域操作，不变更 Life 备份协议和目录。

## 来源和一致性

旧见域基线为 `com.elio.jianyu`、源码 `2d47446`、Room v18；整合目标为 `com.elio.lifearchive` 中见域专属存储，Schema 身份保持 `3b63f950997a41754eb5aa792a99bd8f`。先完成运行世代维护和消费者释放，再 checkpoint 并关闭数据库；禁止复制仍在写入的 DB／WAL。完整数据库快照保留原始主键、外键、逻辑关联、sqlite_sequence 和未知正文，不采用现有限导出的实体白名单。导出只读原内容，旧 App 和旧资料保留。

完整格式使用独立魔数／版本，与旧 `.jybak`、设备绑定快照和 Life 备份互不混认。密码通过固定 Argon2id 参数派生流式 AEAD 密钥；参数继承现有已固定配置，不允许文件输入任意放大 KDF。附件和数据库流式写出，不将全部音频读入内存。文件清单包含相对位置、大小和 SHA-256；包含来源应用、安装版本、数据库版本／Schema、逐表数量、设置名及显式排除项。

## 数据覆盖矩阵

| 数据组 | v18 表／设置／文件 | 完整迁移与恢复规则 |
| --- | --- | --- |
| 旧式聊天 | characters、chat_sessions、messages、character_groups、conversation_summary_checkpoints | 全部保留，包括未关联 issue 的会话及自定义角色；保留正文、状态、顺序和 ID |
| 议题与执行 | issues、stages、execution_runs、execution_participant_snapshots、execution_participant_states、execution_run_budgets、cross_discussion_sessions、execution_message_usage_snapshots | 保留全量；存在活动生成时不导出未经确认的一致快照 |
| 资料和个人背景 | material_references、material_usage_snapshots、personal_context_entries、personal_context_usage_snapshots、skill_knowledge_usage_snapshots | 保留内容、快照、来源及敏感标记；不把外部 URI 授权当成已迁附件 |
| 草稿与成果 | stage_summary_drafts、stage_summary_draft_revisions、confirmed_artifacts、artifact_message_sources、artifact_run_sources、artifact_draft_sources、artifact_material_sources | 保留全部修订及来源关系 |
| 音频 | audio_assets；messages.audioFilePath；见域自有音频文件 | 正式音频按原相对路径保存；旧聊天绝对路径转换为包内逻辑引用，导入映射至新见域根目录；缺失或越界资源须报告并阻止完整迁移 |
| 官方角色组合 | official_skill_combinations、official_skill_combination_members | 保留自定义组合、启用、顺序和职责 |
| 生命周期 | issue_lifecycle、issue_archive_events、issue_resume_events、issue_relations、issue_purge_operations | 保留记录；未完成清除操作须先完成／明确失败，不能在换库时跨域续写 |
| 阶段推进 | stage_advancements、stage_advancement_measures、stage_advancement_skill_members、stage_advancement_materials、stage_advancement_artifacts | 保留幂等身份、确认与继承关系 |
| 可迁移偏好 | jianyu_app_preferences、roundtable_settings、conversation_session_preferences、dialog_reading_state、official_skill_catalog_preferences_v1、ai_runtime_configuration | 白名单限定到已核对设置，逐项保存原值类型；不导入未知设置或凭据 |
| 用户头像 | user-profile/avatar.jpg | 原规范化文件带校验值保存；不用内置默认图替代用户头像 |
| 内置内容 | roles／Skill／知识资料／avatars assets | 由目标 APK 提供；来源引用指向不存在内容时预览明确报告，不静默删除用户记录 |
| 排除项 | API Key、Keystore、设备令牌、诊断库和日志、导出暂存、派生缓存、旧设备快照、宿主悬浮位置 | 不导出；新安装重新配置 Key。排除原因列入清单，不与附件缺失混为一项 |

上表覆盖 38 个业务表；只读 Room 元表与 sqlite_sequence 用于结构／自增校验，不对业务表重编号。资料正文已抽取到 Room 的外部来源 URI 属来源说明；是否另有依赖外部附件必须逐项审计，未能确认时不能标记完整迁入。

## 预览、提交与恢复

先解密到见域 noBackup 暂存区，验证格式、文件路径、大小／校验、Schema、完整性和外键，再显示来源、逐表数量、附件、设置和排除项。旧有限 `.jybak` 不能当作完整格式自动导入；给出明确的不支持说明及完整导出路径。

默认仅导入空见域库。目标已有用户数据时停止直接写入，产品流程必须先创建并回读验证当前内容的完整备份，再明确选择替换；后端按内容指纹复核，不自动合并或重编号。取消预览只删除暂存，不接触当前数据。

真正提交在独立数据操作作用域完成，避免维护时取消页面任务形成租约自等待。仅见域进入维护，释放运行消费者，使用持久化日志备份旧 DB、用户文件、设置及 content epoch，再替换。新库重开和逐项核对成功后发布新世代、轮换 epoch 并完成日志；失败／中断先回滚旧数据再重开。启动须在公开 Repository 前处理未完成日志。回滚记录必须留至确认成功，不能把数据库事务当成文件替换原子性。

验证至少包含：旧式会话／角色、全部表、ID／关系／自增、头像与两类音频、偏好类型往返；错误密码、截断、篡改、重复／越界路径、未知版本、缺附件、取消、写入失败、提交中断和重开失败；Life 文件、导航和备份始终不变。设备验收另记。
