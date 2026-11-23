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
import androidx.compose.runtime.remember
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
    @Inject internal lateinit var switchPositions: SwitchPositionStore
    @Inject internal lateinit var variant: VariantRuntime
    @Inject internal lateinit var notes: NotesRepository
    @Inject internal lateinit var preferences: PreferencesRepository
    @Inject internal lateinit var library: LibraryRepository
    @Inject internal lateinit var remote: RemoteCacheRepository
    @Inject internal lateinit var readerCache: ReaderCacheRepository
    @Inject internal lateinit var backup: BackupEngine
    @Inject internal lateinit var sync: com.elio.lifearchive.domain.sync.SyncRepository
    @Inject internal lateinit var companionProvider: javax.inject.Provider<com.elio.lifearchive.domain.pet.CompanionRepository>
    private val overlayPermission = registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()) {
        if (android.provider.Settings.canDrawOverlays(this)) requestCompanionOverlay()
    }
    private val notificationPermission = registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.RequestPermission()) {
        startCompanionOverlay()
    }

    private fun requestCompanionOverlay() {
        if (android.os.Build.VERSION.SDK_INT >= 33 && checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            notificationPermission.launch(android.Manifest.permission.POST_NOTIFICATIONS)
        } else startCompanionOverlay()
    }

    private fun startCompanionOverlay() {
        try { startForegroundService(android.content.Intent(this, CompanionOverlayService::class.java)) }
        catch (_: RuntimeException) { android.widget.Toast.makeText(this, "暂时无法开启桌面陪伴，请返回前台后重试。", android.widget.Toast.LENGTH_LONG).show() }
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.getBooleanExtra("open-companion-settings", false)) {
            ViewModelProvider(this)[BusinessHostViewModel::class.java].openCompanionSettings()
            intent.removeExtra("open-companion-settings")
        }
        if (intent.getBooleanExtra("open-companion", false)) {
            ViewModelProvider(this)[BusinessHostViewModel::class.java].openCompanionChat()
            intent.removeExtra("open-companion")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val model = ViewModelProvider(this)[AppViewModel::class.java]
        val businessHost = ViewModelProvider(this)[BusinessHostViewModel::class.java]
        if (intent?.getBooleanExtra("open-companion-settings", false) == true) {
            businessHost.openCompanionSettings()
            intent.removeExtra("open-companion-settings")
        }
        if (intent?.getBooleanExtra("open-companion", false) == true) {
            businessHost.openCompanionChat()
            intent.removeExtra("open-companion")
        }
        setContent {
            val state by model.state.collectAsStateWithLifecycle()
            val lifeNote by businessHost.lifeNote.collectAsStateWithLifecycle()
            val companionChatRequest by businessHost.companionChatRequest.collectAsStateWithLifecycle()
            val companionSettingsRequest by businessHost.companionSettingsRequest.collectAsStateWithLifecycle()
            val knowledge = remember { LifeKnowledgeAdapter(library, businessHost::openLifeNote) }
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
              DualBusinessHost(businessHost, switchPositions, palette == DocumentPalette.GRAPHITE, knowledge,
                petContent = { onBar, onVisible ->
                    CompanionHost(companionProvider, onBar, onVisible,
                        onOverlay = {
                            if (android.provider.Settings.canDrawOverlays(this)) {
                                requestCompanionOverlay()
                            } else {
                                overlayPermission.launch(android.content.Intent(android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                    android.net.Uri.parse("package:$packageName")))
                            }
                        },
                        onStopOverlay = {
                            if (CompanionOverlayService.running) {
                                runCatching { startService(CompanionOverlayService.stopIntent(this)) }
                                    .onFailure { stopService(android.content.Intent(this, CompanionOverlayService::class.java)) }
                            } else {
                                stopService(android.content.Intent(this, CompanionOverlayService::class.java))
                            }
                        },
                        onLife = { businessHost.select(Business.LIFE) }, openChatRequest = companionChatRequest, openSettingsRequest = companionSettingsRequest)
                }, inAppCompanion = { visible -> InAppCompanion(companionProvider, visible, businessHost::openCompanionChat) }) { onSwitcher, onBottomBar ->
                when {
                    state.error != null -> Scaffold { Text("初始化失败：${state.error}", modifier = androidx.compose.ui.Modifier.padding(it)) }
                    !state.ready -> Scaffold { Text("正在恢复本地状态…", modifier = androidx.compose.ui.Modifier.padding(it)) }
                    else -> ArchiveApp(notes, preferences, state.preferences, variant.debugControls, library, remote, backup, readerCache, sync,
                        onSwitcherAvailabilityChanged = onSwitcher, onBottomBarMeasured = onBottomBar,
                        lifeNoteRequest = lifeNote, onLifeNoteOpened = businessHost::consumeLifeNote)
                }
              }
            }
        }
    }
}
