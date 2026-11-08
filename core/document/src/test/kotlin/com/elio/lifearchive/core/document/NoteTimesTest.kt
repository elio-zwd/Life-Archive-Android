package com.elio.lifearchive.core.document

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class NoteTimesTest {
    @Test fun sharedContractPreservesEverythingOutsideTargetFields() {
        val fixture = Json.parseToJsonElement(javaClass.getResource("/note-organization-v1.json")!!.readText()).jsonObject
        fixture.getValue("cases").jsonArray.forEach { case ->
            val item = case.jsonObject
            val times = ContentTimes(item["created"]!!.jsonPrimitive.contentOrNull, item["updated"]!!.jsonPrimitive.contentOrNull)
            val expected = item["expected"]!!.jsonPrimitive.content
            assertEquals(expected, NoteTimes.withTimes(item["raw"]!!.jsonPrimitive.content, times))
            assertEquals(expected, NoteTimes.withTimes(expected, times))
        }
        fixture["invalid"]!!.jsonArray.forEach { assertTrue(it.toString(), runCatching { NoteTimes.withTimes(it.jsonPrimitive.content, ContentTimes(updated = "2026-11-09")) }.isFailure) }
        fixture["invalidTimes"]!!.jsonArray.forEach { assertFalse(NoteTimes.valid(it.jsonPrimitive.content)) }
    }
    @Test fun maintenanceNeverBecomesAContentEditAndPrecisionIsRetained() {
        val original = "\uFEFF正文\r\n"
        val identity = NoteIdentity.ensure(original)
        val tagged = DocumentAnalyzer.withTags(identity, listOf("职业发展/面试/自我介绍"))
        val dated = NoteTimes.withTimes(tagged, ContentTimes("2026-11-09", "2026-11-09T12:00:00+08:00"))
        assertEquals(NoteTimes.content(original), NoteTimes.content(dated))
        assertNotEquals(NoteTimes.content(dated), NoteTimes.content(dated + "新增"))
        assertEquals("2026-11-09", NoteTimes.read(dated).created)
        assertTrue(runCatching { NoteTimes.withTimes(dated, ContentTimes(updated = "2026-11-08")) }.isFailure)
        assertEquals(ContentTimes(), NoteTimes.read("---\ndate: 2020-01-01\n---\n正文"))
    }
    @Test fun confirmedTagMappingDoesNotTouchCodeAndKeepsDiscardedWords() {
        val raw = "---\ntags: [面试, 新标签]\nunknown: keep\n---\n#面试 和 #新标签\n`#面试`\n```\n#新标签\n```"
        val mapped = mapDocumentTags(raw, mapOf("面试" to "职业发展/面试", "新标签" to null))
        assertTrue(mapped.contains("tags: [\"职业发展/面试\"]"))
        assertTrue(mapped.contains("#职业发展/面试 和 新标签"))
        assertTrue(mapped.contains("`#面试`")); assertTrue(mapped.contains("```\n#新标签\n```"))
    }
}
