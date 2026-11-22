package com.elio.lifearchive

import android.content.ContentProvider
import android.content.ContentValues
import android.content.pm.ProviderInfo
import android.database.Cursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.text.AnnotatedString
import com.elio.jianyu.backup.*
import com.elio.jianyu.domain.*
import com.elio.lifearchive.core.document.RawDocument
import com.elio.lifearchive.domain.notes.NoteResult
import kotlinx.coroutines.*
import java.io.ByteArrayOutputStream
import java.io.File
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.Before
import org.junit.After
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowContentResolver
import org.robolectric.shadows.ShadowLog

/** 真实 app 装配、Room、密码格式和两个备份协调器；仅使用 Robolectric 合成目录。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = LifeArchiveApplication::class)
class CompleteBackupIntegrationTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val password = "仅用于合成资料的备份密码123"
    private var lastSession: com.elio.jianyu.runtime.JianyuHostSession? = null

    @Before fun isolateRuntime() { resetJianyuRuntimeForTest(compose.activity.applicationContext) }
    @After fun closeRuntime() {
        val context = compose.activity.applicationContext
        compose.activityRule.scenario.close()
        resetJianyuRuntimeForTest(context)
    }

    // Robolectric 将方法名加入临时路径；保持短名，避免 Windows 原生 SQLite 超过路径限制。
    @Test fun roundTrip() {
        compose.waitUntil(30000) { compose.onAllNodesWithTag("new-note").fetchSemanticsNodes().isNotEmpty() }
        val life = runBlocking {
            (compose.activity.notes.createManaged("两域隔离.md", RawDocument("# Life\n\n必须保留的正文"), false) as NoteResult.Committed).value
        }
        val context = compose.activity.applicationContext
        val files = File(context.cacheDir, "complete-backup-provider").apply { mkdirs() }
        val provider = SyntheticBackupProvider(files)
        provider.attachInfo(context, ProviderInfo().apply { authority = "complete.test"; exported = false })
        ShadowContentResolver.registerProviderInternal("complete.test", provider)
        focusWindow()
        compose.onNodeWithTag("business-switch").performClick()
        compose.onNodeWithTag("business-select-JIANYU").performClick()
        compose.waitUntil(30000) { compose.onAllNodesWithTag("chat_input").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("chat_input").performTextInput("创建完整备份时不能丢的草稿")
        val originalSession = ready().session
        lastSession = originalSession
        val complete = originalSession.services.backup.complete
        val first = Uri.parse("content://complete.test/first")
        complete.startExport(first.toString(), password)
        waitCompleted(complete)
        waitReady()
        assertTrue(ready().session.generation > originalSession.generation)
        assertEquals(originalSession.contentEpoch, ready().session.contentEpoch)
        compose.onNodeWithTag("chat_input").assertTextContains("创建完整备份时不能丢的草稿")
        assertTrue(File(files, "first").length() > 24)
        val sourceBytes = File(files, "first").readBytes()

        val currentSession = ready().session
        val saved = runBlocking(Dispatchers.IO) {
            currentSession.services.repository.saveIssue(SaveIssueCommand("later-issue", "快照后的新议题", "later-stage", "阶段", "目标", 12345))
        }
        assertTrue(saved.toString(), saved is RepositoryResult.Success)

        // Life 自己恢复时，见域运行实例与内容都保留。
        val lifeBackup = ByteArrayOutputStream()
        runBlocking { compose.activity.backup.export(lifeBackup) }
        val preparedLife = runBlocking { compose.activity.backup.prepare(lifeBackup.toByteArray().inputStream()) }
        runBlocking { compose.activity.backup.commit(preparedLife) }
        compose.waitForIdle()
        assertSame(currentSession, ready().session)
        assertEquals(life.document, runBlocking { (compose.activity.notes.read(life.summary.id) as NoteResult.Success).value.document })

        val inspectScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            inspectScope.launch { complete.inspect(first.toString(), password) }
            compose.waitUntil(30000) { pumpUi(); complete.preview.value != null || complete.operation.value is JianyuCompleteOperation.Failed }
            check(complete.preview.value != null) { complete.operation.value.toString() }
            assertEquals(0L, complete.preview.value!!.recordCounts["issues"])
            assertTrue(complete.preview.value!!.targetHasData)
            assertFalse(complete.preview.value!!.targetBackupReady)

            // 替换前先完整备份现在的见域，后端也按当前指纹核对备份覆盖范围。
            complete.startExport("content://complete.test/current", password)
            waitCompleted(complete)
            waitReady()
            assertTrue(complete.preview.value!!.targetBackupReady)
            val beforeRestore = ready().session
            complete.startRestore(complete.preview.value!!.token, replaceExisting = true)
            waitCompleted(complete)
            waitReady()
            assertNotEquals(beforeRestore.contentEpoch, ready().session.contentEpoch)
            compose.onNodeWithTag("chat_input").assert(SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString("")))
            assertNull(complete.preview.value)
            assertArrayEquals(sourceBytes, File(files, "first").readBytes())
            assertEquals(life.document, runBlocking { (compose.activity.notes.read(life.summary.id) as NoteResult.Success).value.document })
            val staleWrite = runBlocking(Dispatchers.IO) {
                beforeRestore.services.repository.saveIssue(SaveIssueCommand("stale-issue", "失效会话不得写入", "stale-stage", "阶段", "目标", 23456))
            }
            assertTrue("旧世代不能写入新库：$staleWrite", staleWrite is RepositoryResult.Failure)
            val restoredSession = ready().session
            val restoredIssues = runBlocking(Dispatchers.IO) { restoredSession.services.repository.listIssueNavigation(IssueLifecycleState.entries.toSet()) }
            assertTrue(restoredIssues.toString(), restoredIssues is RepositoryResult.Success && restoredIssues.value.isEmpty())

            // 清理仅作用于见域；即使硬件 Keystore 在桌面未实现，清库也必须使旧世代失效。
            compose.onNodeWithTag("chat_input").performTextInput("清理后不能复活的输入")
            val beforeClear = ready().session
            val clear = inspectScope.async { beforeClear.services.maintenance.clearAll() }
            compose.waitUntil(30000) { pumpUi(); clear.isCompleted }
            val clearMessage = runBlocking { clear.await() }
            waitReady()
            assertNotEquals(clearMessage, beforeClear.contentEpoch, ready().session.contentEpoch)
            compose.onNodeWithTag("chat_input").assert(SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString("")))
            assertEquals(life.document, runBlocking { (compose.activity.notes.read(life.summary.id) as NoteResult.Success).value.document })
            val staleAfterClear = runBlocking(Dispatchers.IO) {
                beforeClear.services.repository.saveIssue(SaveIssueCommand("stale-clear", "清理前的会话不得写入", "clear-stage", "阶段", "目标", 34567))
            }
            assertTrue("$clearMessage; $staleAfterClear", staleAfterClear is RepositoryResult.Failure)
        } finally { inspectScope.cancel() }
    }

    private fun host() = ViewModelProvider(compose.activity)[BusinessHostViewModel::class.java]
    private fun ready() = host().jianyu.value as JianyuHostState.Ready
    private fun waitReady() {
        compose.waitUntil(30000) { val inputs = compose.onAllNodesWithTag("chat_input").fetchSemanticsNodes(); host().jianyu.value is JianyuHostState.Ready && inputs.isNotEmpty() }
    }
    private fun waitCompleted(complete: JianyuCompleteBackup) {
        try { compose.waitUntil(60000) { pumpUi(); complete.operation.value !is JianyuCompleteOperation.Busy } }
        catch (error: androidx.compose.ui.test.ComposeTimeoutException) {
            val diagnostics = runCatching {
                val providerType = Class.forName("com.elio.jianyu.JianyuAppRuntimeProvider")
                val provider = providerType.getField("INSTANCE").get(null)
                val registry = providerType.getDeclaredField("leaseRegistry").apply { isAccessible = true }.get(provider)
                val leases = registry.javaClass.getDeclaredField("activeByGeneration").apply { isAccessible = true }.get(registry)
                val tasks = lastSession!!.tasks
                val jobs = tasks.javaClass.getDeclaredField("jobs").apply { isAccessible = true }.get(tasks)
                "leases=$leases, tasks=$jobs"
            }.getOrElse { it.javaClass.simpleName }
            throw AssertionError("${complete.operation.value}; host=${host().jianyu.value}; $diagnostics", error)
        }
        val logs = (ShadowLog.getLogsForTag("JianyuHost") + ShadowLog.getLogsForTag("JianyuCompleteBackup") +
            ShadowLog.getLogsForTag("SQLiteDatabase") + ShadowLog.getLogsForTag("SQLiteLog")).joinToString("\n") { "${it.msg} ${it.throwable?.message.orEmpty()}" }
        assertTrue("${complete.operation.value}\n$logs", complete.operation.value is JianyuCompleteOperation.Finished)
    }
    private fun pumpUi() { compose.onAllNodesWithTag("jianyu_app_content_root").fetchSemanticsNodes() }
    private fun focusWindow() { compose.runOnIdle { compose.activity.window.decorView.dispatchWindowFocusChanged(true) } }
}

private class SyntheticBackupProvider(private val directory: File) : ContentProvider() {
    override fun onCreate() = true
    override fun getType(uri: Uri) = "application/vnd.jianyu.full-backup"
    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        val name = uri.lastPathSegment ?: error("缺少合成文件名")
        require(name in setOf("first", "current"))
        return ParcelFileDescriptor.open(File(directory, name), ParcelFileDescriptor.parseMode(mode))
    }
    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = 0
}
