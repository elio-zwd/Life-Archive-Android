package com.elio.lifearchive.core.document

import org.junit.Assert.*
import org.junit.Test
import kotlinx.serialization.json.*

class NoteIdentityTest {
    private val id = "12345678-1234-1234-1234-123456789abc"
    @Test fun preservesOriginalBytesOutsideInsertedIdentity() {
        for (nl in listOf("\n", "\r\n")) {
            val raw = "\uFEFF---${nl}# 保留注释${nl}unknown: [one, two]${nl}---${nl}正文$nl"
            val updated = NoteIdentity.ensure(raw, id)
            assertEquals(raw.replaceFirst("---$nl", "---${nl}life_archive_id: $id$nl"), updated)
            assertEquals(id, NoteIdentity.read(updated)); assertEquals(updated, NoteIdentity.ensure(updated))
        }
        val raw = "---\n{\"unknown\": [1,2]}\n---\n正文"
        assertEquals(id, NoteIdentity.read(NoteIdentity.ensure(raw, id)))
    }
    @Test fun refusesUnsafeIdentityInsteadOfReformattingYaml() {
        for (raw in listOf("---\nunknown: 1", "---\nlife_archive_id: wrong\n---\n", "---\n- item\n---\n", "---\nlife_archive_id: $id\nlife_archive_id: $id\n---\n")) {
            assertTrue(runCatching { NoteIdentity.ensure(raw, id) }.isFailure)
        }
    }
    @Test fun reservedAndWindowsPathsAreNotTransferred() {
        listOf(".git/a", ".life-archive/a", ".obsidian/a", "AGENTS.md", "../a", "AUX.md", "a\\b").forEach { assertFalse(isSyncPath(it)) }
        assertTrue(isSyncPath("中文/笔记.md"))
    }
    @Test fun sharedProtocolExamplesMatchPcBytes() {
        val fixture = Json.parseToJsonElement(javaClass.getResource("/simple-sync-v1.json")!!.readText()).jsonObject
        fixture.getValue("identityCases").jsonArray.forEach { value ->
            val sample = value.jsonObject
            assertEquals(sample.getValue("expected").jsonPrimitive.content, NoteIdentity.ensure(sample.getValue("raw").jsonPrimitive.content, fixture.getValue("id").jsonPrimitive.content))
        }
    }
}
