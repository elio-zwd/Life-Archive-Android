package com.elio.jianyu.conversation

interface ConversationReadingRepository {
    fun load(sessionId: Long): ConversationReadingState
    fun saveRoleStripExpanded(sessionId: Long, expanded: Boolean)
    fun saveSelectedRole(sessionId: Long, questionId: String, roleId: String, readAnswerIds: Set<String>)
    fun saveExpandedAnswers(sessionId: Long, expandedAnswerIds: Set<String>, collapsedAnswerId: String?)
    fun saveAnswerOffset(sessionId: Long, answerId: String, offset: Int, progress: Float)
    fun saveConversationOffset(sessionId: Long, itemId: String, offset: Int, progress: Float)
    fun deleteSession(sessionId: Long)
}
