package com.elio.jianyu.data
import com.elio.jianyu.storage.jianyuPreferences
import com.elio.jianyu.domain.*
import android.content.Context
import com.elio.jianyu.conversation.*

class ConversationReadingStateRepository(context: Context) : ConversationReadingRepository {
    private val preferences = context.applicationContext.jianyuPreferences(
        "dialog_reading_state",
        Context.MODE_PRIVATE,
    )

    override fun load(sessionId: Long): ConversationReadingState {
        val prefix = prefix(sessionId)
        val keys = preferences.all.keys
        fun <T> readEntries(suffix: String, readValue: (String) -> T): Map<String, T> {
            val keyPrefix = "$prefix$suffix"
            return keys.asSequence()
                .filter { it.startsWith(keyPrefix) }
                .associate { key -> key.removePrefix(keyPrefix) to readValue(key) }
        }
        return ConversationReadingState(
            roleStripExpanded = preferences.getBoolean("${prefix}roles", true),
            selectedRoleIds = readEntries("page_") { preferences.getString(it, "").orEmpty() },
            expandedAnswerIds = preferences.getStringSet("${prefix}expanded", emptySet()).orEmpty().toSet(),
            readAnswerIds = preferences.getStringSet("${prefix}read", emptySet()).orEmpty().toSet(),
            answerScrollOffsets = readEntries("offset_") { preferences.getInt(it, 0) },
            answerScrollProgress = readEntries("progress_") { preferences.getFloat(it, 0f) },
            conversationScrollKey = preferences.getString("${prefix}scroll_key", null),
            conversationScrollOffset = preferences.getInt("${prefix}scroll_offset", 0),
            conversationScrollProgress = if (preferences.contains("${prefix}scroll_progress")) {
                preferences.getFloat("${prefix}scroll_progress", 0f)
            } else null,
        )
    }

    override fun saveRoleStripExpanded(sessionId: Long, expanded: Boolean) {
        preferences.edit().putBoolean("${prefix(sessionId)}roles", expanded).apply()
    }

    override fun saveSelectedRole(sessionId: Long, questionId: String, roleId: String, readAnswerIds: Set<String>) {
        preferences.edit()
            .putString("${prefix(sessionId)}page_$questionId", roleId)
            .putStringSet("${prefix(sessionId)}read", readAnswerIds.toSet())
            .apply()
    }

    override fun saveExpandedAnswers(sessionId: Long, expandedAnswerIds: Set<String>, collapsedAnswerId: String?) {
        preferences.edit()
            .putStringSet("${prefix(sessionId)}expanded", expandedAnswerIds.toSet())
            .apply {
                if (collapsedAnswerId != null) {
                    putInt("${prefix(sessionId)}offset_$collapsedAnswerId", 0)
                    putFloat("${prefix(sessionId)}progress_$collapsedAnswerId", 0f)
                }
            }
            .apply()
    }

    override fun saveAnswerOffset(sessionId: Long, answerId: String, offset: Int, progress: Float) {
        preferences.edit()
            .putInt("${prefix(sessionId)}offset_$answerId", offset)
            .putFloat("${prefix(sessionId)}progress_$answerId", progress)
            .apply()
    }

    override fun saveConversationOffset(sessionId: Long, itemId: String, offset: Int, progress: Float) {
        preferences.edit()
            .putString("${prefix(sessionId)}scroll_key", itemId)
            .putInt("${prefix(sessionId)}scroll_offset", offset)
            .putFloat("${prefix(sessionId)}scroll_progress", progress)
            .apply()
    }

    override fun deleteSession(sessionId: Long) {
        val prefix = prefix(sessionId)
        preferences.edit().apply {
            preferences.all.keys.filter { it.startsWith(prefix) }.forEach(::remove)
        }.apply()
    }

    private fun prefix(sessionId: Long): String = "session_${sessionId}_"
}
