package com.elio.lifearchive

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.ui.platform.LocalContext
import androidx.compose.material3.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import android.content.Intent
import android.net.Uri
import android.webkit.WebView
import android.webkit.WebViewClient
import com.elio.lifearchive.core.backup.BackupEngine
import com.elio.lifearchive.core.document.LibraryPath
import com.elio.lifearchive.feature.settings.*
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.lifecycle.viewmodel.navigation3.rememberViewModelStoreNavEntryDecorator
import androidx.navigation3.runtime.*
import androidx.navigation3.ui.NavDisplay
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.elio.lifearchive.core.document.RawDocument
import com.elio.lifearchive.core.preferences.*
import com.elio.lifearchive.core.designsystem.DocumentPageTheme
import com.elio.lifearchive.core.designsystem.DocumentPalette
import com.elio.lifearchive.core.designsystem.ThemeStyleDialog
import com.elio.lifearchive.core.designsystem.label
import androidx.core.view.WindowCompat
import com.elio.lifearchive.domain.notes.*
import com.elio.lifearchive.feature.editor.*
import com.elio.lifearchive.feature.notes.*
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable

@Serializable internal data class NotesHome(val generation: String = UUID.randomUUID().toString()) : NavKey
@Serializable internal data class NoteDestination(val id: String, val editing: Boolean = false, val offset: Int? = null, val entry: String = UUID.randomUUID().toString()) : NavKey
@Serializable internal data object SettingsDestination : NavKey
@Serializable internal data class VideoNotesDestination(val folder: String?, val entry: String = UUID.randomUUID().toString(), val captureOnly: Boolean = false) : NavKey
@Serializable internal data class OnlineDestination(val url: String) : NavKey

@Composable
internal fun ArchiveApp(notes: NotesRepository, preferences: PreferencesRepository, values: UserPreferences, debugControls: Boolean, library: LibraryRepository? = null, remote: RemoteCacheRepository? = null, backup: BackupEngine? = null, readerCache: ReaderCacheRepository? = null) {
    val backStack = rememberNavBackStack(remember { NotesHome() })
    val scope = rememberCoroutineScope()
    val pendingTransfers = remember { mutableSetOf<Job>() }
    fun libraryOperation(block: suspend CoroutineScope.() -> Unit) {
        val job = scope.launch(start = CoroutineStart.LAZY, block = block)
        pendingTransfers += job
        job.invokeOnCompletion { pendingTransfers -= job }
        job.start()
    }
    var creatingSample by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var activeEditor by remember { mutableStateOf<EditorViewModel?>(null) }
    val editors = remember { mutableMapOf<String, EditorViewModel>() }
    val homes = remember { mutableMapOf<NotesHome, NotesViewModel>() }
    LaunchedEffect(backStack.toList()) { val keys = backStack.filterIsInstance<NoteDestination>().map { it.entry }.toSet(); editors.keys.retainAll(keys) }
    LaunchedEffect(backStack.toList()) { homes.keys.retainAll(backStack.filterIsInstance<NotesHome>().toSet()) }
    val activity = LocalActivity.current
    val systemDark = isSystemInDarkTheme()
    val homeDark = when (values.theme) { ThemeMode.SYSTEM -> systemDark; ThemeMode.DARK -> true; ThemeMode.LIGHT -> false }
    val documentTheme = values.resolvedDocumentTheme(homeDark)
    val documentPalette = when (documentTheme) { DocumentTheme.BLUE -> DocumentPalette.BLUE; DocumentTheme.WARM -> DocumentPalette.WARM; DocumentTheme.GRAPHITE -> DocumentPalette.GRAPHITE }
    val currentDark = documentPalette == DocumentPalette.GRAPHITE
    var themePicker by rememberSaveable { mutableStateOf(false) }
    var themeError by rememberSaveable { mutableStateOf<String?>(null) }
    fun chooseTheme(chosen: DocumentTheme) {
        libraryOperation {
            try { preferences.setDocumentTheme(chosen) }
            catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (e: Exception) { themeError = e.message ?: "请重试" }
        }
    }
    if (themePicker) ThemeStyleDialog(documentPalette, onDismiss = { themePicker = false }, onChoose = {
        themePicker = false
        chooseTheme(when (it) { DocumentPalette.BLUE -> DocumentTheme.BLUE; DocumentPalette.WARM -> DocumentTheme.WARM; DocumentPalette.GRAPHITE -> DocumentTheme.GRAPHITE })
    })
    themeError?.let { failure -> AlertDialog(
        onDismissRequest = { themeError = null }, title = { Text("主题保存失败") },
        text = { Text(failure) }, confirmButton = { TextButton(onClick = { themeError = null }) { Text("关闭") } },
    ) }
    SideEffect {
        activity?.window?.let { window ->
            WindowCompat.getInsetsController(window, window.decorView).apply {
                isAppearanceLightStatusBars = !currentDark
                isAppearanceLightNavigationBars = !currentDark
            }
        }
    }
    val context = LocalContext.current
    val settings: SettingsViewModel? = if (library != null && remote != null && backup != null) viewModel(factory = remember(library, remote, backup, readerCache) { viewModelFactory { initializer { SettingsViewModel(preferences, backup, notes, library, remote, readerCache) } } }) else null
    if (settings != null) {
        val transferState by settings.state.collectAsStateWithLifecycle()
        LaunchedEffect(transferState.message) { transferState.message?.let { message = it; activeEditor?.message(it) } }
    }
    var managed by remember { mutableStateOf<LibraryNote?>(null) }
    var coverNote by remember { mutableStateOf<LibraryNote?>(null) }
    var coverSession by rememberSaveable { mutableStateOf<String?>(null) }
    var coverPickerRequest by rememberSaveable { mutableStateOf<String?>(null) }
    var importedCover by remember { mutableStateOf<CoverImage?>(null) }
    var coverBusy by remember { mutableStateOf(false) }
    var coverError by remember { mutableStateOf<String?>(null) }
    fun openCover(note: LibraryNote) {
        coverNote = note; coverSession = UUID.randomUUID().toString(); importedCover = null; coverError = null
    }
    LaunchedEffect(backStack.toList()) { coverNote = null; coverSession = null }
    val coverPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val requested = coverPickerRequest
        coverPickerRequest = null
        if (uri != null && requested != null && requested == coverSession && coverNote != null) {
            coverBusy = true; coverError = null
            libraryOperation {
                try {
                    val result = withContext(Dispatchers.IO) {
                        val name = displayName(context, uri)
                        val input = context.contentResolver.openInputStream(uri) ?: error("无法打开图片")
                        importCoverImage(notes, input, name)
                    }
                    if (coverSession == requested) when (result) {
                        is NoteResult.Success -> importedCover = result.value
                        else -> coverError = result.failureMessage()
                    }
                } catch (e: kotlinx.coroutines.CancellationException) { throw e }
                catch (e: Exception) { if (coverSession == requested) coverError = "图片导入失败：${e.message}" }
                finally { coverBusy = false }
            }
        } else if (uri != null) message = "笔记会话已变化，请重新选择封面"
    }
    var folderPrompt by remember { mutableStateOf<String?>(null) }
    var folderText by remember { mutableStateOf("") }
    var deleteFolder by remember { mutableStateOf<String?>(null) }
    var exportType by rememberSaveable { mutableStateOf("backup") }
    var exportId by rememberSaveable { mutableStateOf<String?>(null) }
    var attachmentEntry by rememberSaveable { mutableStateOf<String?>(null) }
    val outputPicker = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        if (uri != null && settings != null) {
            val open = { context.contentResolver.openOutputStream(uri, "wt") }
            when (exportType) { "backup" -> settings.exportBackup(open); "markdown" -> exportId?.let { settings.exportMarkdown(NoteId(it), open) }; else -> settings.exportLibrary(open, exportId?.let { setOf(NoteId(it)) }) }
        }
    }
    val backupPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> if (uri != null) settings?.prepareRestore { context.contentResolver.openInputStream(uri) } }
    val importPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris -> if (uris.isNotEmpty()) libraryOperation { try { settings?.prepareImport(withContext(Dispatchers.IO) { selectedEntries(context, uris) }) } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { message = "所选文件不可读取：${e.message}" } } }
    val directoryPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri -> if (uri != null) libraryOperation { try { settings?.prepareImport(directoryEntries(context, uri)) } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { message = "目录读取失败：${e.message}" } } }
    val attachmentPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val requestedEntry = attachmentEntry
        attachmentEntry = null
        if (uri != null) {
            val origin = backStack.lastOrNull() as? NoteDestination
            val editor = activeEditor?.takeIf { origin != null && origin.entry == requestedEntry && it.id.value == origin.id }
            if (editor == null) { message = "笔记会话已变化，本次未导入附件，请重新选择"; return@rememberLauncherForActivityResult }
            libraryOperation {
                try {
                    val original = displayName(context, uri)
                    val name = original.takeIf { LibraryPath.isValid(it) && '/' !in it } ?: "附件-${UUID.randomUUID()}.${original.substringAfterLast('.', "bin").filter { it.isLetterOrDigit() }}"
                    val path = "_attachments/${UUID.randomUUID().toString().take(8)}-$name"
                    val result = withContext(Dispatchers.IO) { (context.contentResolver.openInputStream(uri) ?: error("无法打开附件")).use { notes.importAttachment(path, it) } }
                    if (backStack.lastOrNull() != origin || activeEditor !== editor) {
                        message = if (result is NoteResult.Success) "附件已入库，会话已变化，未插入正文：$path" else "附件导入失败：$result"
                    } else if (result is NoteResult.Success) editor.insertAttachment(path) else editor.message("附件导入失败：$result")
                } catch (e: kotlinx.coroutines.CancellationException) { throw e }
                catch (e: Exception) { if (activeEditor === editor) editor.message("附件导入失败：${e.message}") else message = "附件导入失败：${e.message}" }
            }
        } else activeEditor?.cancelAttachment()
    }
    fun pickExport(type: String, id: String? = null) {
        exportType = type; exportId = id
        outputPicker.launch(when (type) { "backup" -> "LifeArchive-backup.zip"; "markdown" -> "笔记.md"; else -> "LifeArchive-notes.zip" })
    }
    fun openLink(value: String) {
        val editor = activeEditor ?: return
        val origin = backStack.lastOrNull()
        scope.launch {
            try {
                if (value.startsWith("online:")) { val target = value.removePrefix("online:"); require(Uri.parse(target).scheme in setOf("http", "https")) { "只支持 HTTP(S) 网页" }; editor.leave { if (backStack.lastOrNull() == origin) backStack.add(OnlineDestination(target)) }; return@launch }
                if (Regex("^(https?|mailto|tel):", RegexOption.IGNORE_CASE).containsMatchIn(value)) { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(value))); return@launch }
                val parsed = Uri.parse(value)
                val from = if (parsed.scheme == "archive-link") NoteId(parsed.host ?: editor.id.value) else editor.id
                val target = if (parsed.scheme == "archive-link") parsed.getQueryParameter("target").orEmpty() else value
                val syntax = runCatching { com.elio.lifearchive.core.document.LinkSyntax.valueOf(parsed.getQueryParameter("syntax") ?: "WIKI") }.getOrDefault(com.elio.lifearchive.core.document.LinkSyntax.WIKI)
                val result = library?.resolve(from, target, syntax)
                if (backStack.lastOrNull() != origin) return@launch
                val resolved = (result as? NoteResult.Success)?.value ?: error("链接解析失败：$result")
                when {
                    resolved.ambiguous.isNotEmpty() -> editor.message("引用不明确，请使用完整路径：${resolved.ambiguous.joinToString()}")
                    resolved.missing -> editor.message("目标或定位不存在：$target")
                    resolved.note != null -> editor.leave { if (backStack.lastOrNull() == origin) backStack.add(NoteDestination(resolved.note!!.value, offset = resolved.location.offset)) }
                    resolved.attachment != null && library != null -> openAttachment(context, library, resolved.attachment!!)
                }
            } catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (e: Exception) { editor.message("无法打开：${e.message}") }
        }
    }
    NavDisplay(
        backStack = backStack,
        entryDecorators = listOf(rememberSaveableStateHolderNavEntryDecorator(), rememberViewModelStoreNavEntryDecorator()),
        onBack = { if (backStack.size > 1) { val origin = backStack.lastOrNull(); val editor = activeEditor; if (editor != null) editor.leave { if (backStack.lastOrNull() == origin) backStack.removeLastOrNull() } else backStack.removeLastOrNull() } else activity?.finish() },
        entryProvider = entryProvider {
            entry<NotesHome> { key ->
                val factory = remember(notes, library) { viewModelFactory { initializer { NotesViewModel(notes, library) } } }
                val model: NotesViewModel = viewModel(factory = factory)
                SideEffect { homes[key] = model; if (backStack.lastOrNull() == key) activeEditor = null }
                val state by model.state.collectAsStateWithLifecycle()
                NotesScreen(
                    state = state, onOpen = { id ->
                        val detail = state.listing?.notes?.find { it.summary.id == id }
                        if (detail?.deleted != null) managed = detail else backStack.add(NoteDestination(id.value, offset = if (state.query.text.isNotBlank()) detail?.matchOffset else null))
                    },
                    onCreate = { name, callback -> model.create(name, callback) },
                    onCreated = { if (backStack.lastOrNull() == key) backStack.add(NoteDestination(it.value, true)) },
                    onCreateSample = {
                        if (!creatingSample) {
                            creatingSample = true
                            libraryOperation {
                                try {
                                    val result = createSample(notes)
                                    if (result is NoteResult.Committed) { if (backStack.lastOrNull() == key) backStack.add(NoteDestination(result.value.summary.id.value)) }
                                    else message = "样例创建失败：$result"
                                } finally { creatingSample = false }
                            }
                        }
                    },
                    onTheme = { themePicker = true },
                    themeLabel = documentPalette.label,
                    onClearError = model::clearError, sampleBusy = creatingSample, message = message,
                    onQuery = model::query, onSettings = { if (settings != null) backStack.add(SettingsDestination) },
                    onBackup = { if (settings != null) pickExport("backup") }, loadThumbnail = model::loadThumbnail,
                    onQuick = { model.quick { if (backStack.lastOrNull() == key) backStack.add(NoteDestination(it.value, true)) } }, onManage = { managed = it },
                    onVideoNotes = { if (backStack.lastOrNull() == key) backStack.add(VideoNotesDestination(state.query.folder)) },
                    onPaste = { if (backStack.lastOrNull() == key) backStack.add(VideoNotesDestination(state.query.folder, captureOnly = true)) },
                    onFolder = { folderPrompt = ""; folderText = "" }, onFolderManage = { folderPrompt = state.query.folder; folderText = state.query.folder.orEmpty() }, onRebuild = model::rebuild,
                )
            }
            entry<NoteDestination> { key ->
                val factory = remember(notes, library, key) { viewModelFactory { initializer { EditorViewModel(notes, NoteId(key.id), key.editing, key.offset, library = library) } } }
                val model: EditorViewModel = viewModel(factory = factory)
                SideEffect { editors[key.entry] = model; if (backStack.lastOrNull() == key) activeEditor = model }
                DocumentPageTheme(documentPalette) {
                    EditorRoute(model, values.fontScale, debugControls,
                        onBack = { if (backStack.lastOrNull() == key) backStack.removeLastOrNull() },
                        onOpenCopy = { if (backStack.lastOrNull() == key) backStack.add(NoteDestination(it.value, true)) },
                        services = if (library != null && remote != null) ReaderServices(NoteId(key.id), notes, library, remote, values.remoteLoading == RemoteLoading.AUTOMATIC, model::message, readerCache) { event ->
                            model.readerLoaded(event)
                            if (debugControls) android.util.Log.d("LifeArchiveReader", "phase=${event.phase} note=${key.id} entryMs=${model.openElapsedMs()} pageMs=${event.elapsedMs} htmlHit=${event.cacheHit} artifactHits=${event.artifactHits}")
                        } else null,
                        onLink = ::openLink, onAttach = { if (model.prepareAttachment()) { attachmentEntry = key.entry; attachmentPicker.launch(arrayOf("*/*")) } },
                        onManage = { scope.launch { val detail = library?.metadata(NoteId(key.id)); if (backStack.lastOrNull() == key) managed = (detail as? NoteResult.Success)?.value } },
                        documentTheme = documentTheme,
                        onCover = { scope.launch {
                            val detail = withContext(Dispatchers.IO) { library?.metadata(NoteId(key.id)) }
                            if (backStack.lastOrNull() == key) (detail as? NoteResult.Success)?.value?.let(::openCover)
                        } },
                        onDocumentTheme = ::chooseTheme,
                    )
                }
            }
            entry<VideoNotesDestination> { key ->
                SideEffect { if (backStack.lastOrNull() == key) activeEditor = null }
                val factory = remember(notes, preferences, key) { viewModelFactory { initializer { VideoNotesViewModel(notes, preferences, key.folder, key.captureOnly) } } }
                val model: VideoNotesViewModel = viewModel(factory = factory)
                VideoNotesRoute(model, active = backStack.lastOrNull() == key,
                    onBack = { if (backStack.lastOrNull() == key) backStack.removeLastOrNull() },
                    onCreated = { id -> if (backStack.lastOrNull() == key) {
                        model.noteOpened()
                        backStack.add(NoteDestination(id.value))
                    } },
                )
            }
            entry<SettingsDestination> {
                SideEffect { if (backStack.lastOrNull() == SettingsDestination) activeEditor = null }
                LaunchedEffect(Unit) { settings?.refreshCache() }
                if (settings != null) {
                    val state by settings.state.collectAsStateWithLifecycle()
                    SettingsScreen(state, values, onBack = { backStack.removeLastOrNull() }, onTheme = { themePicker = true }, onFont = settings::font, onRemote = settings::remote,
                        onClearCache = settings::clearCache, onClearReaderCache = settings::clearReaderCache, onImportFiles = { importPicker.launch(arrayOf("text/markdown", "text/plain", "application/octet-stream")) },
                        onImportDirectory = { directoryPicker.launch(null) }, onExport = { pickExport("zip") }, onBackup = { pickExport("backup") },
                        onPickRestore = { backupPicker.launch(arrayOf("application/zip", "application/octet-stream")) }, onRestore = { settings.restore(beforeRestore = {
                            val sessions = editors.values.toList()
                            if (sessions.any { !it.flush() }) false else {
                                // 系统选择器和样例创建也可能仍在复制，提交替换前等待旧任务结束。
                                pendingTransfers.toList().forEach { it.cancelAndJoin() }
                                homes.values.toList().forEach { it.retire() }
                                sessions.all { it.retire() }
                            }
                        }) { editors.clear(); homes.clear(); activeEditor = null; managed = null; folderPrompt = null; deleteFolder = null; attachmentEntry = null; backStack.clear(); backStack.add(NotesHome()) } }, onCancelRestore = settings::cancelRestore,
                        onImport = settings::commitImport, onCancelImport = settings::cancelImport)
                }
            }
            entry<OnlineDestination> { key ->
                SideEffect { if (backStack.lastOrNull() == key) activeEditor = null }
                OnlinePage(key.url) { backStack.removeLastOrNull() }
            }
        },
    )
    managed?.let { note -> NoteActions(note, onDismiss = { managed = null }) { action, text ->
        val origin = backStack.lastOrNull()
        val current = activeEditor?.takeIf { it.id == note.summary.id }
        if (action == NoteAction.COVER) {
            managed = null; openCover(note)
        } else if (action == NoteAction.MARKDOWN || action == NoteAction.ZIP) {
            val choose = { managed = null; pickExport(if (action == NoteAction.MARKDOWN) "markdown" else "zip", note.summary.id.value) }
            if (current != null) libraryOperation {
                if (current.flush() && current.persistence?.fullyCommitted == true) { if (backStack.lastOrNull() == origin) choose() }
                else current.message("正文尚未提交，请先保存或处理草稿后再导出")
            } else choose()
        } else if (action == NoteAction.TAGS && current != null) {
            try { if (current.applyTags(text.split(',', '，').map { it.trim() }.filter { it.isNotEmpty() })) managed = null } catch (e: Exception) { current.message("标签未修改：${e.message}") }
        } else {
            val perform: () -> Unit = { libraryOperation operation@{
                if (library != null) try {
                    val expected = if (current == null) note.summary.version else (notes.read(note.summary.id) as? NoteResult.Success)?.value?.summary?.version ?: note.summary.version
                    val result = when (action) {
                        NoteAction.PIN -> library.setPinned(note.summary.id, !note.pinned)
                        NoteAction.RENAME -> library.relocate(note.summary.id, expected, text)
                        NoteAction.TAGS -> library.setTags(note.summary.id, expected, text.split(',', '，').map { it.trim() }.filter { it.isNotEmpty() })
                        NoteAction.TRASH -> library.trash(note.summary.id, expected)
                        NoteAction.RESTORE -> library.restore(note.summary.id, text)
                        NoteAction.PURGE -> library.purge(note.summary.id)
                        else -> NoteResult.Success(Unit)
                    }
                    if (backStack.lastOrNull() != origin) return@operation
                    if (result is NoteResult.Success) {
                        managed = null; message = "操作完成"
                        if (current != null && action in setOf(NoteAction.RENAME, NoteAction.TRASH)) {
                            backStack.removeLastOrNull()
                            if (action == NoteAction.RENAME) backStack.add(NoteDestination(current.id.value, editing = current.state.value.mode != EditorMode.READ, offset = current.session?.text?.selection?.start))
                        }
                    } else { message = result.failureMessage(); current?.message(message!!) }
                } catch (e: Exception) { message = "操作未完成：${e.message}"; current?.message(message!!) }
            } }
            if (current != null && action != NoteAction.PIN) current.leave(perform) else perform()
        }
    } }
    folderPrompt?.let { old -> AlertDialog(onDismissRequest = { folderPrompt = null }, title = { Text(if (old.isEmpty()) "新建笔记本" else "重命名／移动笔记本") }, text = { Column { OutlinedTextField(folderText, { folderText = it }, label = { Text("库内目录路径") }); if (old.isNotEmpty()) TextButton(onClick = { deleteFolder = old; folderPrompt = null }) { Text("删除此笔记本…") } } }, confirmButton = { TextButton(onClick = { libraryOperation { val result = if (old.isEmpty()) library?.createFolder(folderText) else library?.relocateFolder(old, folderText); message = if (result is NoteResult.Success) "笔记本操作完成" else result?.failureMessage(); if (result is NoteResult.Success) folderPrompt = null } }) { Text("确认") } }, dismissButton = { TextButton(onClick = { folderPrompt = null }) { Text("取消") } }) }
    deleteFolder?.let { folder -> AlertDialog(onDismissRequest = { deleteFolder = null }, title = { Text("删除笔记本？") }, text = { Text("$folder 内的笔记进入回收站，可逐篇恢复；共享附件保留。") }, confirmButton = { TextButton(onClick = { libraryOperation { val result = library?.trashFolder(folder); message = result?.failureMessage(); if (result is NoteResult.Success) deleteFolder = null } }) { Text("移入回收站") } }, dismissButton = { TextButton(onClick = { deleteFolder = null }) { Text("取消") } }) }
    coverNote?.let { note -> if (library != null) {
        val picker: @Composable () -> Unit = {
            NoteCoverPicker(note, library, importedCover, coverBusy, coverError, notes = notes,
                onPickImage = { coverPickerRequest = coverSession; coverPicker.launch(arrayOf("image/*")) },
                onDismiss = { coverNote = null; coverSession = null },
                onSave = { selected ->
                    val requested = coverSession
                    coverBusy = true; coverError = null
                    libraryOperation {
                        try {
                            val result = withContext(Dispatchers.IO) { library.setCover(note.summary.id, selected) }
                            if (coverSession == requested) {
                                if (result is NoteResult.Success) { coverNote = null; coverSession = null; message = "封面已保存" }
                                else coverError = result.failureMessage()
                            }
                        } catch (e: kotlinx.coroutines.CancellationException) { throw e }
                        catch (e: Exception) { if (coverSession == requested) coverError = "封面保存失败：${e.message}" }
                        finally { coverBusy = false }
                    }
                })
        }
        if (backStack.lastOrNull() is NoteDestination) DocumentPageTheme(documentPalette, picker) else picker()
    } }
    if (settings != null && backStack.lastOrNull() != SettingsDestination) {
        val state by settings.state.collectAsStateWithLifecycle()
        state.message?.let { value -> if (state.busy) AlertDialog(onDismissRequest = {}, title = { Text("正在处理资料") }, text = { Text(value) }, confirmButton = {}) }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable private fun OnlinePage(url: String, onBack: () -> Unit) {
    Scaffold(topBar = { TopAppBar(title = { Text("在线网页") }, navigationIcon = { TextButton(onClick = onBack) { Text("返回") } }) }) { padding ->
        AndroidView(modifier = Modifier.fillMaxSize().padding(padding), factory = { context -> WebView(context).apply { settings.javaScriptEnabled = true; settings.allowFileAccess = false; settings.allowContentAccess = false; settings.domStorageEnabled = false; webViewClient = WebViewClient(); loadUrl(url) } }, onRelease = { it.stopLoading(); it.destroy() })
    }
}

private suspend fun createSample(notes: NotesRepository): NoteResult<NoteSnapshot> = withContext(Dispatchers.IO) {
    val suffix = UUID.randomUUID().toString().take(8)
    val image = "验收图片-$suffix.png"
    val imported = notes.importAttachment(image, ByteArrayInputStream(sampleLandscape()))
    if (imported !is NoteResult.Success) return@withContext when (imported) {
        is NoteResult.StorageFailure -> imported
        else -> NoteResult.StorageFailure("样例附件创建失败：$imported")
    }
    notes.create("日常片段-$suffix.md", RawDocument(PrototypeContent.document(image)))
}

/** 合成图片随样例入库，验证阅读器不依赖外部原文件。 */
internal fun sampleLandscape(): ByteArray {
    val bitmap = Bitmap.createBitmap(1000, 500, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    canvas.drawColor(Color.rgb(222, 231, 237))
    val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    paint.color = Color.rgb(241, 218, 174); canvas.drawCircle(760f, 150f, 55f, paint)
    paint.color = Color.rgb(139, 162, 165)
    canvas.drawPath(Path().apply { moveTo(0f, 320f); lineTo(220f, 130f); lineTo(500f, 370f); lineTo(720f, 205f); lineTo(1000f, 340f); lineTo(1000f, 500f); lineTo(0f, 500f); close() }, paint)
    paint.color = Color.rgb(81, 116, 113)
    canvas.drawPath(Path().apply { moveTo(0f, 370f); cubicTo(220f, 250f, 380f, 500f, 650f, 320f); cubicTo(810f, 235f, 910f, 350f, 1000f, 300f); lineTo(1000f, 500f); lineTo(0f, 500f); close() }, paint)
    paint.color = Color.rgb(48, 77, 77)
    canvas.drawPath(Path().apply { moveTo(0f, 440f); cubicTo(250f, 310f, 560f, 510f, 1000f, 410f); lineTo(1000f, 500f); lineTo(0f, 500f); close() }, paint)
    return ByteArrayOutputStream().use { out -> bitmap.compress(Bitmap.CompressFormat.PNG, 100, out); bitmap.recycle(); out.toByteArray() }
}
