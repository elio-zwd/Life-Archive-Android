package com.elio.jianyu.skill.catalog

import kotlinx.coroutines.flow.StateFlow

interface OfficialSkillPreferences {
    val favoriteIds: StateFlow<Set<String>>
    val recentUses: StateFlow<List<RecentOfficialSkillUse>>

    suspend fun setFavorite(skillId: String, favorite: Boolean): Boolean

    suspend fun recordSkillUsed(skillId: String, usedAt: Long): Boolean

    /** 清空最近使用记录；不影响收藏、会话历史与当前参与者。 */
    suspend fun clearRecentUses(): Boolean

    /** 删除全部本地数据时同时清空收藏与最近使用，并同步当前进程状态。 */
    suspend fun clearAll(): Boolean

    /** 查看详情不等于真正进入使用流程，故该事件不得写入最近使用。 */
    suspend fun onSkillDetailViewed(skillId: String) = Unit
}
