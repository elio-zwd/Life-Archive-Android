package com.elio.lifearchive

import android.content.Context
import com.elio.lifearchive.core.backup.BackupEngine
import com.elio.lifearchive.core.backup.StorageGate
import com.elio.lifearchive.core.preferences.PreferencesRepository
import com.elio.lifearchive.core.preferences.PreferencesStore
import com.elio.lifearchive.data.notes.NotesStorage
import com.elio.lifearchive.domain.notes.NotesRepository
import com.elio.lifearchive.domain.notes.LibraryRepository
import com.elio.lifearchive.domain.notes.RemoteCacheRepository
import com.elio.lifearchive.data.notes.RemoteCacheStore
import com.elio.lifearchive.data.notes.ReaderCacheStore
import com.elio.lifearchive.domain.notes.ReaderCacheRepository
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.io.File
import javax.inject.Singleton

/** 先装配恢复协调器，再向调用方公开存储，确保首次访问受恢复门锁保护。 */
internal class FoundationRuntime(context: Context, root: File) : AutoCloseable {
    private val gate = StorageGate()
    private val notesStore = NotesStorage.open(context, File(root, "notes"), gate)
    private val preferencesStore = PreferencesStore(File(root, "settings.preferences_pb"), gate)
    val backup = BackupEngine(File(root, "backup-work"), gate, listOf(notesStore.backupParticipant, preferencesStore.backupParticipant))
    val notes: NotesRepository = notesStore.repository
    val library: LibraryRepository = notesStore.library
    val remote: RemoteCacheRepository = RemoteCacheStore(File(context.cacheDir, "remote-resources"))
    val readerCache: ReaderCacheRepository = ReaderCacheStore(File(context.cacheDir, "reader-results"))
    val preferences: PreferencesRepository = preferencesStore.repository
    override fun close() { notesStore.close(); preferencesStore.close() }
    suspend fun closeAndJoin() { notesStore.close(); preferencesStore.closeAndJoin() }
}

@Module
@InstallIn(SingletonComponent::class)
internal object FoundationModule {
    @Provides @Singleton
    fun runtime(@ApplicationContext context: Context): FoundationRuntime = FoundationRuntime(context, File(context.filesDir, "library"))
    @Provides fun notes(runtime: FoundationRuntime): NotesRepository = runtime.notes
    @Provides fun library(runtime: FoundationRuntime): LibraryRepository = runtime.library
    @Provides fun remote(runtime: FoundationRuntime): RemoteCacheRepository = runtime.remote
    @Provides fun readerCache(runtime: FoundationRuntime): ReaderCacheRepository = runtime.readerCache
    @Provides fun preferences(runtime: FoundationRuntime): PreferencesRepository = runtime.preferences
    @Provides fun backup(runtime: FoundationRuntime): BackupEngine = runtime.backup
}
