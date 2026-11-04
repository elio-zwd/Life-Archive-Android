package com.elio.lifearchive.core.backup

import java.io.ByteArrayOutputStream
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class OptionalParticipantTest {
    @get:Rule val temp = TemporaryFolder()
    @Test fun legacyRestoreResetsMissingOptionalDomainAndRejectsUnknownDomains() = runBlocking {
        var body = "backup"; var reset = false
        val notes = object : BackupParticipant {
            override val id = "notes"
            override suspend fun snapshot(directory: File) { File(directory, "body").writeText(body) }
            override suspend fun validate(directory: File) { require(File(directory, "body").isFile) }
            override suspend fun restore(directory: File) { body = File(directory, "body").readText() }
        }
        val legacy = BackupEngine(temp.newFolder(), StorageGate(), listOf(notes))
        val bytes = ByteArrayOutputStream().also { legacy.export(it) }.toByteArray()
        val optional = object : BackupParticipant {
            override val id = "sync"
            override val optionalInLegacy = true
            override suspend fun snapshot(directory: File) { File(directory, "state").writeText("state") }
            override suspend fun validate(directory: File) { require(File(directory, "state").isFile) }
            override suspend fun restore(directory: File) { reset = true }
            override suspend fun restoreMissing() { reset = true }
        }
        val current = BackupEngine(temp.newFolder(), StorageGate(), listOf(notes, optional))
        body = "current"
        assertTrue(current.commit(current.prepare(bytes.inputStream())) is RestoreResult.Committed)
        assertEquals("backup", body); assertTrue(reset)
        val newer = ByteArrayOutputStream().also { current.export(it) }.toByteArray()
        assertTrue(runCatching { legacy.prepare(newer.inputStream()) }.isFailure)
    }
}
