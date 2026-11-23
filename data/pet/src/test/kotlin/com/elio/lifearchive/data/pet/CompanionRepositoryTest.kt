package com.elio.lifearchive.data.pet

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.elio.lifearchive.domain.ai.*
import com.elio.lifearchive.domain.pet.*
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CompanionRepositoryTest {
    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    private suspend fun CompanionRepository.finished() = withTimeout(10_000) { state.first { it.messages.isNotEmpty() && !it.generating } }
    private suspend fun fixture(ai: AiGateway, test: suspend (AndroidCompanionRepository, CompanionDatabase, CoroutineScope) -> Unit) {
        val db = Room.inMemoryDatabaseBuilder(context, CompanionDatabase::class.java).allowMainThreadQueries().build()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val repo = AndroidCompanionRepository({ db }, ai, scope)
        try { repo.initialize(); test(repo, db, scope) }
        finally { scope.coroutineContext[Job]!!.cancelAndJoin(); db.close() }
    }
    @Test fun duplicateSendAndRepeatedRetryKeepOneConversationIdentity() = runBlocking {
        val count = AtomicInteger()
        fixture(AiGateway { flow { if (count.incrementAndGet() == 1) throw AiRequestException(AiFailure.NETWORK); emit(AiEvent.Complete("{\"text\":\"我在听\",\"emotion\":\"CARING\"}")) } }) { repo, _, _ ->
            repo.saveDraft("今天有点累")
            assertTrue(repo.send("今天有点累", "stable-operation"))
            assertFalse(repo.send("今天有点累", "stable-operation"))
            val failed = repo.finished().messages.last()
            assertEquals(ReplyStatus.FAILED, failed.status)
            assertTrue(repo.retry(failed.id))
            val complete = repo.finished()
            assertEquals(2, complete.messages.size)
            assertEquals(failed.id, complete.messages.last().id)
            assertEquals("我在听", complete.messages.last().text)
            assertEquals(CompanionEmotion.CARING, complete.messages.last().emotion)
            assertEquals("", complete.profile.draft)
            assertFalse(repo.send("今天有点累", "stable-operation"))
        }
    }
    @Test fun concurrentEntryPointsHaveOnlyOnePendingReplyAndCancelIsDurable() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        fixture(AiGateway { flow { entered.complete(Unit); awaitCancellation() } }) { repo, _, _ ->
            val accepted = coroutineScope { listOf(async { repo.send("页面", "page") }, async { repo.send("悬浮窗", "overlay") }).awaitAll() }
            assertEquals(1, accepted.count { it })
            entered.await(); repo.cancel()
            assertEquals(ReplyStatus.CANCELLED, repo.state.value.messages.last().status)
            assertEquals(2, repo.state.value.messages.size)
        }
    }
    @Test fun correctedAndDeletedMemoryChangesFutureContextWithoutReadingOtherDomains() = runBlocking {
        val requests = CopyOnWriteArrayList<AiRequest>()
        fixture(AiGateway { request -> requests += request; flowOf(AiEvent.Complete("你好")) }) { repo, _, _ ->
            repo.remember("我喜欢绿茶")
            val memory = repo.state.value.memories.single()
            assertTrue(repo.editMemory(memory.id, memory.revision, "我喜欢红茶"))
            assertFalse(repo.editMemory(memory.id, memory.revision, "过期写入"))
            repo.send("我喜欢什么？", "first"); repo.finished()
            assertTrue(requests.last().instruction.contains("红茶")); assertFalse(requests.last().instruction.contains("绿茶"))
            val revised = repo.state.value.memories.single()
            assertTrue(repo.deleteMemory(revised.id, revised.revision))
            assertFalse(repo.deleteMemory(revised.id, revised.revision))
            repo.send("新的交流", "second"); repo.finished()
            assertFalse(requests.last().instruction.contains("红茶"))
            assertEquals("pet", requests.last().id.business)
            assertEquals(4, repo.state.value.messages.size)
        }
    }
    @Test fun realDatabaseReopenPreservesProfileDraftMemoryAndInterruptsPendingGeneration() = runBlocking {
        val name = "pet-${java.util.UUID.randomUUID()}.db"
        val first = Room.databaseBuilder(context, CompanionDatabase::class.java, name).allowMainThreadQueries().build()
        first.companion().initialize(ProfileRow(name = "小伙伴", personality = "认真倾听", draft = "尚未发送", showInApp = true))
        first.companion().remember(MemoryRow("memory", "周末喜欢散步", null))
        first.companion().insert(MessageRow("pending", "op", "COMPANION", "", "GENERATING", 1))
        first.close()
        val reopened = Room.databaseBuilder(context, CompanionDatabase::class.java, name).allowMainThreadQueries().build()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val repo = AndroidCompanionRepository({ reopened }, AiGateway { error("不能重新发起旧请求") }, scope)
            repo.initialize(); repo.initialize()
            assertEquals("小伙伴", repo.state.value.profile.name)
            assertEquals("尚未发送", repo.state.value.profile.draft)
            assertTrue(repo.state.value.profile.showInApp)
            assertEquals("周末喜欢散步", repo.state.value.memories.single().text)
            assertEquals(ReplyStatus.INTERRUPTED, repo.state.value.messages.single().status)
        } finally { scope.cancel(); reopened.close() }
    }
    @Test fun malformedActionsCannotReachWindowOrStorage() {
        assertEquals("你好", parseReply("```json\n{\"text\":\"你好\",\"emotion\":\"UNKNOWN\",\"action\":\"delete_all\"}\n```").first)
        assertEquals(CompanionEmotion.CALM, parseReply("普通文字").second)
        try { parseReply("{\"action\":\"delete_all\"}"); fail() } catch (_: IllegalArgumentException) {}
    }
    @Test fun selectedModelPersistsAcrossReopenAndRejectsBlankId() = runBlocking {
        val name = "pet-model-${java.util.UUID.randomUUID()}.db"
        val first = Room.databaseBuilder(context, CompanionDatabase::class.java, name)
            .addMigrations(CompanionDatabase.MIGRATION_1_2, CompanionDatabase.MIGRATION_2_3).allowMainThreadQueries().build()
        val scope1 = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val repo = AndroidCompanionRepository({ first }, AiGateway { error("不能联网") }, scope1)
            repo.initialize()
            assertEquals(DEFAULT_COMPANION_MODEL_ID, repo.state.value.profile.modelId)
            repo.selectModel("Mao")
            assertEquals("Mao", repo.state.value.profile.modelId)
            try { repo.selectModel("   "); fail() } catch (_: IllegalArgumentException) {}
        } finally { scope1.cancel(); first.close() }
        val reopened = Room.databaseBuilder(context, CompanionDatabase::class.java, name)
            .addMigrations(CompanionDatabase.MIGRATION_1_2, CompanionDatabase.MIGRATION_2_3).allowMainThreadQueries().build()
        val scope2 = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val repo = AndroidCompanionRepository({ reopened }, AiGateway { error("不能联网") }, scope2)
            repo.initialize()
            assertEquals("Mao", repo.state.value.profile.modelId)
        } finally { scope2.cancel(); reopened.close() }
    }
}
