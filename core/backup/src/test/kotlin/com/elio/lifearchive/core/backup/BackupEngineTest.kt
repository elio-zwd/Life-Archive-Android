package com.elio.lifearchive.core.backup

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class BackupEngineTest {
    @get:Rule val temp = TemporaryFolder()
    private class Crash : Error()
    private class Participant(override val id: String, var value: String) : BackupParticipant {
        var fail: (() -> Unit)? = null
        override suspend fun snapshot(directory: File) { File(directory, "value.txt").writeText(value) }
        override suspend fun validate(directory: File) { require(File(directory, "value.txt").isFile) }
        override suspend fun restore(directory: File) { value = File(directory, "value.txt").readText(); fail?.invoke() }
    }

    @Test fun roundTripAndPreparedRestoreDoesNotMutate() = runBlocking {
        val a = Participant("notes", "old")
        val b = Participant("preferences", "dark")
        val engine = BackupEngine(temp.newFolder(), StorageGate(), listOf(a, b))
        val bytes = ByteArrayOutputStream().also { engine.export(it) }.toByteArray()
        a.value = "new"
        val prepared = engine.prepare(ByteArrayInputStream(bytes))
        assertEquals("new", a.value)
        engine.commit(prepared)
        assertEquals("old", a.value)
        assertEquals("dark", b.value)
    }

    @Test fun partialFailureRollsBackEveryParticipant() = runBlocking {
        val a = Participant("notes", "backup-a")
        val b = Participant("preferences", "backup-b")
        val engine = BackupEngine(temp.newFolder(), StorageGate(), listOf(a, b))
        val bytes = ByteArrayOutputStream().also { engine.export(it) }.toByteArray()
        a.value = "current-a"; b.value = "current-b"
        b.fail = { b.fail = null; throw IOException("模拟恢复失败") }
        val prepared = engine.prepare(ByteArrayInputStream(bytes))
        try { engine.commit(prepared); fail("应失败") } catch (_: IOException) { }
        assertEquals("current-a", a.value)
        assertEquals("current-b", b.value)
        engine.initialize()
    }

    @Test fun processInterruptionRecoversBeforeOpeningGate() = runBlocking {
        val root = temp.newFolder()
        val a = Participant("notes", "backup-a")
        val b = Participant("preferences", "backup-b")
        val engine = BackupEngine(root, StorageGate(), listOf(a, b))
        val bytes = ByteArrayOutputStream().also { engine.export(it) }.toByteArray()
        a.value = "current-a"; b.value = "current-b"
        a.fail = { throw Crash() }
        try { engine.commit(engine.prepare(ByteArrayInputStream(bytes))); fail("应中断") } catch (_: Crash) { }
        assertTrue(File(root, "transaction.json").exists())
        a.fail = null
        val gate = StorageGate()
        BackupEngine(root, gate, listOf(a, b))
        gate.access { assertEquals("current-a", a.value); assertEquals("current-b", b.value) }
        assertFalse(File(root, "transaction.json").exists())
    }

    @Test fun interruptionAfterCommitOnlyCleansUpWithoutRollingBack() = runBlocking {
        val root = temp.newFolder()
        val a = Participant("notes", "backup-a")
        val b = Participant("preferences", "backup-b")
        val engine = BackupEngine(root, StorageGate(), listOf(a, b))
        val bytes = ByteArrayOutputStream().also { engine.export(it) }.toByteArray()
        a.value = "current-a"; b.value = "current-b"
        engine.fault = { if (it == "committed") throw Crash() }
        try { engine.commit(engine.prepare(ByteArrayInputStream(bytes))); fail("应中断") } catch (_: Crash) { }
        assertTrue(File(root, "transaction.json").exists())
        val gate = StorageGate()
        BackupEngine(root, gate, listOf(a, b))
        gate.access { assertEquals("backup-a", a.value); assertEquals("backup-b", b.value) }
        assertFalse(File(root, "transaction.json").exists())
        assertFalse(File(root, "rollback").exists())
        assertTrue(root.list().orEmpty().none { it.startsWith("prepared-") })
    }

    @Test fun rejectsTraversalDuplicatesAndMissingParticipants() = runBlocking {
        val engine = BackupEngine(temp.newFolder(), StorageGate(), listOf(Participant("notes", "unchanged")))
        for (names in listOf(listOf("../outside"), listOf("notes/a", "notes/A"), listOf("manifest.json"))) {
            val bytes = ByteArrayOutputStream().also { out -> ZipOutputStream(out).use { zip ->
                names.forEach { zip.putNextEntry(ZipEntry(it)); zip.write("{}".toByteArray()); zip.closeEntry() }
            } }.toByteArray()
            try { engine.prepare(ByteArrayInputStream(bytes)); fail("应拒绝 $names") } catch (_: IllegalArgumentException) { }
        }
    }

    @Test fun failedRollbackKeepsGateClosedUntilRecoveryWorks() = runBlocking {
        val root = temp.newFolder()
        val gate = StorageGate()
        val a = Participant("notes", "backup")
        val engine = BackupEngine(root, gate, listOf(a))
        val bytes = ByteArrayOutputStream().also { engine.export(it) }.toByteArray()
        a.value = "current"
        a.fail = { throw IOException("持续写入失败") }
        try { engine.commit(engine.prepare(ByteArrayInputStream(bytes))); fail("应失败") } catch (_: IOException) { }
        var accessed = false
        try { gate.access { accessed = true }; fail("恢复失败不应开放") } catch (_: IOException) { }
        assertFalse(accessed)
        assertTrue(File(root, "transaction.json").exists())
        a.fail = null
        engine.initialize()
        assertEquals("current", a.value)
    }
}
