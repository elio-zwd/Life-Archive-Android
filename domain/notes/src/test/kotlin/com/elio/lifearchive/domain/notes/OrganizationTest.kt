package com.elio.lifearchive.domain.notes

import org.junit.Assert.*
import org.junit.Test

class OrganizationTest {
    @Test fun physicalPlacementHasOneTopicAndPreservesKnownKindInInbox() {
        assertEquals(8, NoteOrganization.initialTopics.size)
        assertEquals(NotePlacement("职业发展", NoteKind.VIDEO), NoteOrganization.placement("职业发展/视频摘记/记录.md"))
        assertEquals(NotePlacement(null, NoteKind.PERSONAL), NoteOrganization.placement("待整理/个人笔记/记录.md"))
        assertEquals(NotePlacement(), NoteOrganization.placement("历史笔记本/记录.md"))
        assertEquals("待整理/个人笔记", NoteOrganization.folder(null, NoteKind.PERSONAL))
        assertEquals("待整理", NoteOrganization.folder("职业发展", null))
    }
    @Test fun tagParentsIncludeChildrenWithoutPrefixCollisionsAndNewTagsHaveThreeLevels() {
        assertTrue(NoteOrganization.matches("职业发展/面试/自我介绍", "职业发展/面试"))
        assertFalse(NoteOrganization.matches("职业发展/面试技巧", "职业发展/面试"))
        assertTrue(NoteOrganization.validTag("职业发展/面试/自我介绍"))
        listOf("甲//乙", "甲/乙/丙/丁", "甲 乙", "#甲", "/甲").forEach { assertFalse(NoteOrganization.validTag(it)) }
        assertEquals(listOf("职业发展", "职业发展/面试", "职业发展/面试/自我介绍"), NoteOrganization.tagTree(listOf("职业发展/面试/自我介绍")))
    }
}
