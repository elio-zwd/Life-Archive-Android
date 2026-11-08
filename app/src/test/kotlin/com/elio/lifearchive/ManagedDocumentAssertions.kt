package com.elio.lifearchive

/** UI 回归仍逐字核对用户原文，只忽略保存层新增加的三个受管字段。 */
internal fun withoutManagedFields(raw: String) = raw.replace(Regex("(?m)^(?:life_archive_id|life_archive_created_at|life_archive_updated_at):[^\\r\\n]*\\r?\\n"), "")
