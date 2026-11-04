package com.elio.lifearchive.domain.sync

import com.elio.lifearchive.domain.notes.SyncEntry
import org.junit.Assert.*
import org.junit.Test

class SyncPlannerTest {
    private val a = SyncEntry("n:id", "note", "一.md", "a", 1)
    private val b = a.copy(hash = "b")
    @Test fun firstContactNeverInfersDeletion() {
        assertEquals(SyncDirection.TO_COMPUTER, direction(null, a, null))
        assertEquals(SyncDirection.TO_PHONE, direction(null, null, a))
        assertEquals(SyncDirection.SAME, direction(null, a, a))
        assertEquals(SyncDirection.CONFLICT, direction(null, a, b))
    }
    @Test fun changesDeletesMovesAndConflictsUseCommonBaseline() {
        assertEquals(SyncDirection.TO_PHONE, direction(a, a, null))
        assertEquals(SyncDirection.TO_COMPUTER, direction(a, null, a))
        assertEquals(SyncDirection.CONFLICT, direction(a, null, b))
        assertEquals(SyncDirection.TO_PHONE, direction(a, a, a.copy(path = "改名.md")))
        assertEquals(SyncDirection.CONFLICT, direction(a, b, a.copy(path = "改名.md")))
    }
}
