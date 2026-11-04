package com.elio.lifearchive

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.elio.lifearchive.core.designsystem.DocumentPageTheme
import com.elio.lifearchive.core.designsystem.DocumentPalette
import com.elio.lifearchive.core.preferences.DocumentTheme
import com.elio.lifearchive.core.preferences.resolvedDocumentTheme
import com.elio.lifearchive.core.preferences.ThemeMode
import com.elio.lifearchive.core.preferences.PreferencesRepository
import com.elio.lifearchive.domain.notes.NotesRepository
import com.elio.lifearchive.domain.notes.LibraryRepository
import com.elio.lifearchive.domain.notes.RemoteCacheRepository
import com.elio.lifearchive.domain.notes.ReaderCacheRepository
import com.elio.lifearchive.core.backup.BackupEngine
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    @Inject internal lateinit var variant: VariantRuntime
    @Inject internal lateinit var notes: NotesRepository
    @Inject internal lateinit var preferences: PreferencesRepository
    @Inject internal lateinit var library: LibraryRepository
    @Inject internal lateinit var remote: RemoteCacheRepository
    @Inject internal lateinit var readerCache: ReaderCacheRepository
    @Inject internal lateinit var backup: BackupEngine
    @Inject internal lateinit var sync: com.elio.lifearchive.domain.sync.SyncRepository

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val model = ViewModelProvider(this)[AppViewModel::class.java]
        setContent {
            val state by model.state.collectAsStateWithLifecycle()
            val dark = when (state.preferences.theme) {
                ThemeMode.SYSTEM -> isSystemInDarkTheme()
                ThemeMode.DARK -> true
                ThemeMode.LIGHT -> false
            }
            val palette = when (state.preferences.resolvedDocumentTheme(dark)) {
                DocumentTheme.BLUE -> DocumentPalette.BLUE
                DocumentTheme.WARM -> DocumentPalette.WARM
                DocumentTheme.GRAPHITE -> DocumentPalette.GRAPHITE
            }
            DocumentPageTheme(palette) {
                when {
                    state.error != null -> Scaffold { Text("初始化失败：${state.error}", modifier = androidx.compose.ui.Modifier.padding(it)) }
                    !state.ready -> Scaffold { Text("正在恢复本地状态…", modifier = androidx.compose.ui.Modifier.padding(it)) }
                    else -> ArchiveApp(notes, preferences, state.preferences, variant.debugControls, library, remote, backup, readerCache, sync)
                }
            }
        }
    }
}
