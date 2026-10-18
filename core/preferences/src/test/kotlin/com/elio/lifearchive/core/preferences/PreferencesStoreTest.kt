package com.elio.lifearchive.core.preferences

import com.elio.lifearchive.core.backup.StorageGate
import java.io.File
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
        assertEquals(UserPreferences(), first.repository.preferences.first())
        first.repository.setTheme(ThemeMode.DARK)
        first.repository.setFontScale(1.25f)
        first.closeAndJoin()
        val second = PreferencesStore(file, StorageGate())
        try {
            assertEquals(UserPreferences(ThemeMode.DARK, 1.25f), second.repository.preferences.first())
            try { second.repository.setFontScale(Float.NaN); fail("应拒绝非法字号") } catch (_: IllegalArgumentException) { }
        } finally { second.closeAndJoin() }
    }
}
