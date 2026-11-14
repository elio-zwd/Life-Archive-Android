package com.elio.jianyu.conversation

data class ConversationReadingState(
    val roleStripExpanded: Boolean = true,
    val selectedRoleIds: Map<String, String> = emptyMap(),
    val expandedAnswerIds: Set<String> = emptySet(),
    val readAnswerIds: Set<String> = emptySet(),
    val answerScrollOffsets: Map<String, Int> = emptyMap(),
    val answerScrollProgress: Map<String, Float> = emptyMap(),
    val conversationScrollKey: String? = null,
    val conversationScrollOffset: Int = 0,
    val conversationScrollProgress: Float? = null,
)
