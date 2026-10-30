package com.elio.lifearchive.core.preferences

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.elio.lifearchive.core.backup.StorageGate
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class PreferencesStoreTest {
    @get:Rule val temp = TemporaryFolder()

    @Test fun defaultsAndPersistence() = runBlocking {
        val file = File(temp.newFolder(), "settings.preferences_pb")
        val first = PreferencesStore(file, StorageGate())
        val defaults = first.repository.preferences.first()
        assertEquals(UserPreferences(documentTheme = DocumentTheme.BLUE), defaults)
        assertEquals(DocumentTheme.BLUE, defaults.resolvedDocumentTheme(dark = true))
        first.repository.setTheme(ThemeMode.DARK)
        first.repository.setFontScale(1.25f)
        first.repository.setRemoteLoading(RemoteLoading.AUTOMATIC)
        first.closeAndJoin()
        val second = PreferencesStore(file, StorageGate())
        try {
            assertEquals(UserPreferences(ThemeMode.DARK, 1.25f, RemoteLoading.AUTOMATIC, DocumentTheme.BLUE), second.repository.preferences.first())
            try { second.repository.setFontScale(Float.NaN); fail("应拒绝非法字号") } catch (_: IllegalArgumentException) { }
        } finally { second.closeAndJoin() }
    }

    @Test fun legacyPreferencesFollowActualGlobalThemeUntilSelection() = runBlocking {
        val file = File(temp.newFolder(), "settings.preferences_pb")
        writeOldPreferences(file)
        val first = PreferencesStore(file, StorageGate())
        try {
            val legacy = first.repository.preferences.first()
            assertNull(legacy.documentTheme)
            assertEquals(DocumentTheme.BLUE, legacy.resolvedDocumentTheme(dark = false))
            assertEquals(DocumentTheme.GRAPHITE, legacy.resolvedDocumentTheme(dark = true))
            first.repository.setFontScale(1.25f)
        } finally { first.closeAndJoin() }

        val second = PreferencesStore(file, StorageGate())
        try {
            assertNull(second.repository.preferences.first().documentTheme)
            second.repository.setDocumentTheme(DocumentTheme.WARM)
            val selected = second.repository.preferences.first()
            assertEquals(DocumentTheme.WARM, selected.resolvedDocumentTheme(dark = false))
            assertEquals(DocumentTheme.WARM, selected.resolvedDocumentTheme(dark = true))
        } finally { second.closeAndJoin() }

        val third = PreferencesStore(file, StorageGate())
        try { assertEquals(DocumentTheme.WARM, third.repository.preferences.first().documentTheme) }
        finally { third.closeAndJoin() }
    }

    @Test fun allDocumentThemesRoundTripThroughFormatOneBackup() = runBlocking {
        val file = File(temp.newFolder(), "settings.preferences_pb")
        val store = PreferencesStore(file, StorageGate())
        val directory = temp.newFolder()
        try {
            for (theme in DocumentTheme.values()) {
                store.repository.setDocumentTheme(theme)
                store.backupParticipant.snapshot(directory)
                assertTrue(File(directory, "settings.json").readText().contains("\"documentTheme\":\"${theme.name}\""))
                store.backupParticipant.validate(directory)
                store.repository.setDocumentTheme(if (theme == DocumentTheme.BLUE) DocumentTheme.WARM else DocumentTheme.BLUE)
                store.backupParticipant.restore(directory)
                assertEquals(theme, store.repository.preferences.first().documentTheme)
            }
        } finally { store.closeAndJoin() }
    }

    @Test fun legacyBackupRemainsUnselectedAfterRestoreAndRestart() = runBlocking {
        val file = File(temp.newFolder(), "settings.preferences_pb")
        val directory = temp.newFolder()
        File(directory, "settings.json").writeText(
            """{"format":1,"preferences":{"theme":"SYSTEM","fontScale":1.25,"remoteLoading":"AUTOMATIC"}}""",
        )
        val first = PreferencesStore(file, StorageGate())
        try {
            first.backupParticipant.validate(directory)
            first.backupParticipant.restore(directory)
            val restored = first.repository.preferences.first()
            assertNull(restored.documentTheme)
            assertEquals(DocumentTheme.GRAPHITE, restored.resolvedDocumentTheme(dark = true))
            first.repository.setTheme(ThemeMode.LIGHT)
            assertNull(first.repository.preferences.first().documentTheme)
            first.backupParticipant.snapshot(directory)
            first.backupParticipant.validate(directory)
        } finally { first.closeAndJoin() }

        val second = PreferencesStore(file, StorageGate())
        try {
            val restored = second.repository.preferences.first()
            assertNull(restored.documentTheme)
            assertEquals(DocumentTheme.BLUE, restored.resolvedDocumentTheme(dark = false))
            assertEquals(DocumentTheme.GRAPHITE, restored.resolvedDocumentTheme(dark = true))
        } finally { second.closeAndJoin() }
    }

    @Test fun unknownBackupThemeIsRejectedWithoutChangingPreferences() = runBlocking {
        val file = File(temp.newFolder(), "settings.preferences_pb")
        val directory = temp.newFolder()
        val store = PreferencesStore(file, StorageGate())
        try {
            store.repository.setDocumentTheme(DocumentTheme.WARM)
            val original = store.repository.preferences.first()
            File(directory, "settings.json").writeText(
                """{"format":1,"preferences":{"documentTheme":"UNKNOWN"}}""",
            )
            assertTrue(runCatching { store.backupParticipant.validate(directory) }.isFailure)
            assertTrue(runCatching { store.backupParticipant.restore(directory) }.isFailure)
            assertEquals(original, store.repository.preferences.first())
        } finally { store.closeAndJoin() }
    }

    @Test fun unknownStoredThemeIsRejected() = runBlocking {
        val file = File(temp.newFolder(), "settings.preferences_pb")
        writeOldPreferences(file, documentTheme = "UNKNOWN")
        val store = PreferencesStore(file, StorageGate())
        try { assertTrue(runCatching { store.repository.preferences.first() }.isFailure) }
        finally { store.closeAndJoin() }
    }

    @Test fun failedThemeWriteKeepsPreviousPreferences() = runBlocking {
        val directory = temp.newFolder()
        val file = File(directory, "settings.preferences_pb")
        val saved = File(directory, "saved.preferences_pb")
        val store = PreferencesStore(file, StorageGate())
        try {
            store.repository.setDocumentTheme(DocumentTheme.WARM)
            val original = store.repository.preferences.first()
            check(file.renameTo(saved))
            check(file.mkdir())
            File(file, "block").writeText("block")
            try {
                assertTrue(runCatching { store.repository.setDocumentTheme(DocumentTheme.GRAPHITE) }.isFailure)
                assertEquals(original, store.repository.preferences.first())
            } finally {
                check(file.deleteRecursively())
                check(saved.renameTo(file))
            }
        } finally { store.closeAndJoin() }

        val reopened = PreferencesStore(file, StorageGate())
        try { assertEquals(DocumentTheme.WARM, reopened.repository.preferences.first().documentTheme) }
        finally { reopened.closeAndJoin() }
    }

    private suspend fun writeOldPreferences(file: File, documentTheme: String? = null) {
        val job = SupervisorJob()
        val store = PreferenceDataStoreFactory.create(scope = CoroutineScope(job + Dispatchers.IO)) { file }
        try {
            store.edit {
                it[stringPreferencesKey("theme")] = ThemeMode.SYSTEM.name
                if (documentTheme != null) it[stringPreferencesKey("document_theme")] = documentTheme
            }
        } finally { job.cancelAndJoin() }
    }
}
