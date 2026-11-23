package com.elio.lifearchive.data.pet

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.elio.lifearchive.domain.ai.*
import com.elio.lifearchive.domain.pet.*
import com.elio.jianyu.network.AiUseCase
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DesktopCompanionTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private suspend fun fixture(gateway: AiGateway, block: suspend (AndroidCompanionRepository) -> Unit) {
        val db = Room.inMemoryDatabaseBuilder(context, CompanionDatabase::class.java).allowMainThreadQueries().build()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val repo = AndroidCompanionRepository({ db }, gateway, scope) {
            context.assets.open("companion-lines.json").bufferedReader().use { parseCompanionLines(it.readText()) }
        }
        try { repo.initialize(); block(repo) } finally { scope.cancel(); db.close() }
    }
    @Test fun bundledVocabularyHasUniqueShortLinesForAllTriggersAndAvoidsRecentRepeats() = runBlocking {
        val lines = context.assets.open("companion-lines.json").bufferedReader().use { parseCompanionLines(it.readText()) }
        assertTrue(lines.size >= 120)
        assertEquals(lines.size, lines.map { it.text }.distinct().size)
        CompanionTrigger.entries.forEach { trigger -> assertTrue(lines.count { it.trigger == trigger } >= 24) }
        fixture(AiGateway { error("本地触碰不应联网") }) { repo ->
            val reactions = List(12) { repo.localReaction(CompanionTrigger.HEAD).text }
            assertEquals(12, reactions.distinct().size)
            assertTrue(repo.state.value.messages.isEmpty()); assertTrue(repo.state.value.memories.isEmpty())
        }
    }
    @Test fun eventGreetingsRespectQuietHoursQuotaAndLongAbsenceWithoutCallingAiInLocalMode() = runBlocking {
        fixture(AiGateway { error("本地问候不应联网") }) { repo ->
            val now = 100_000_000L
            assertNull(repo.greeting(CompanionTrigger.START, now, 23, "day"))
            assertNotNull(repo.greeting(CompanionTrigger.START, now, 10, "day"))
            assertNull(repo.greeting(CompanionTrigger.START, now + 1, 10, "day"))
            repo.updateOverlay { it.copy(screenOffAt = now) }
            assertNull(repo.greeting(CompanionTrigger.RETURN, now + 3_600_000, 11, "day"))
            assertNotNull(repo.greeting(CompanionTrigger.RETURN, now + 7_200_000, 12, "day"))
            assertNull(repo.greeting(CompanionTrigger.RETURN, now + 7_200_001, 12, "day"))
            assertNotNull(repo.greeting(CompanionTrigger.START, now + 10_800_000, 13, "day"))
            assertNotNull(repo.greeting(CompanionTrigger.START, now + 14_400_000, 14, "day"))
            assertNull(repo.greeting(CompanionTrigger.START, now + 18_000_000, 15, "day"))
            assertEquals(4, repo.state.value.overlay.greetingsToday)
            assertNotNull(repo.greeting(CompanionTrigger.START, now + 86_400_000, 10, "next"))
            assertTrue(repo.state.value.messages.isEmpty())
        }
    }
    @Test fun mutedAndDisabledModesPreventNetworkAndConcurrentGreetingsReserveOneSlot() = runBlocking {
        var calls = 0
        fixture(AiGateway { calls++; flowOf(AiEvent.Complete("欢迎回来。")) }) { repo ->
            repo.updateOverlay { it.copy(greetingMode = GreetingMode.OFF) }
            assertNull(repo.greeting(CompanionTrigger.START, 100_000_000, 10, "day"))
            repo.updateOverlay { it.copy(greetingMode = GreetingMode.LOCAL_AI, mutedUntil = 200_000_000) }
            assertNull(repo.greeting(CompanionTrigger.START, 100_000_000, 10, "day"))
            assertEquals(0, calls)
            repo.updateOverlay { it.copy(mutedUntil = 0) }
            val greetings = coroutineScope { List(3) { async { repo.greeting(CompanionTrigger.START, 100_000_000, 10, "day") } }.awaitAll() }
            assertEquals(1, greetings.count { it != null }); assertEquals(1, calls)
        }
    }
    @Test fun aiGreetingUsesOwnIdentityAndMemoryAndFallsBackWithoutAddingMessages() = runBlocking {
        val requests = mutableListOf<AiRequest>()
        fixture(AiGateway { requests += it; flow { throw AiRequestException(AiFailure.NETWORK) } }) { repo ->
            repo.remember("我喜欢散步")
            repo.updateOverlay { it.copy(greetingMode = GreetingMode.LOCAL_AI) }
            assertNotNull(repo.greeting(CompanionTrigger.START, 100_000_000, 10, "day"))
            assertEquals("desktop-greeting", requests.single().id.session)
            assertEquals(AiUseCase.PET_GREETING, requests.single().useCase)
            assertTrue(requests.single().instruction.contains("我喜欢散步"))
            assertTrue(repo.state.value.messages.isEmpty()); assertEquals(1, repo.state.value.memories.size)
        }
    }
    @Test fun cancellationOfGreetingCannotCancelAConversation() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        fixture(AiGateway { request -> flow {
            if (request.useCase == AiUseCase.PET_GREETING) { entered.complete(Unit); awaitCancellation() }
            else emit(AiEvent.Complete("我在听。"))
        } }) { repo ->
            repo.updateOverlay { it.copy(greetingMode = GreetingMode.LOCAL_AI) }
            val greeting = launch { repo.greeting(CompanionTrigger.START, 100_000_000, 10, "day") }
            entered.await()
            assertTrue(repo.send("你好", "chat")); greeting.cancelAndJoin()
            withTimeout(5_000) { repo.state.first { !it.generating } }
            assertEquals(ReplyStatus.COMPLETE, repo.state.value.messages.last().status)
        }
    }
    @Test fun timeoutReturnsLocalLineAndDoesNotLeaveARequestRunning() = runBlocking {
        val finished = CompletableDeferred<Unit>()
        fixture(AiGateway { flow { try { awaitCancellation() } finally { finished.complete(Unit) } } }) { repo ->
            repo.updateOverlay { it.copy(greetingMode = GreetingMode.LOCAL_AI) }
            val response = withTimeout(10_000) { repo.greeting(CompanionTrigger.START, 100_000_000, 10, "day") }
            assertNotNull(response); assertTrue(finished.isCompleted)
            assertTrue(repo.state.value.messages.isEmpty())
        }
    }
    @Test fun pendingChatSuppressesGreetingAndSettingsNormalizeWithoutOverwritingEachOther() = runBlocking {
        fixture(AiGateway { flow { awaitCancellation() } }) { repo ->
            coroutineScope {
                awaitAll(async { repo.updateOverlay { it.copy(heightFraction = .38f) } },
                    async { repo.updateOverlay { it.copy(xFraction = .3f) } })
            }
            assertEquals(.38f, repo.state.value.overlay.heightFraction); assertEquals(.3f, repo.state.value.overlay.xFraction)
            repo.updateOverlay { it.copy(heightFraction = Float.NaN, xFraction = -4f, yFraction = 9f) }
            assertEquals(.25f, repo.state.value.overlay.heightFraction); assertEquals(0f, repo.state.value.overlay.xFraction); assertEquals(1f, repo.state.value.overlay.yFraction)
            assertTrue(repo.send("你好", "pending"))
            assertNull(repo.greeting(CompanionTrigger.START, 100_000_000, 10, "day"))
            repo.cancel()
        }
    }
    @Test fun versionOneMigrationPreservesExistingDataAndDesktopSettingsSurviveReopen() = runBlocking {
        val name = "desktop-migration-" + java.util.UUID.randomUUID() + ".db"
        val file = context.getDatabasePath(name); file.parentFile!!.mkdirs()
        SQLiteDatabase.openOrCreateDatabase(file, null).use { db ->
            db.execSQL("CREATE TABLE profile (id INTEGER NOT NULL PRIMARY KEY, name TEXT NOT NULL, personality TEXT NOT NULL, draft TEXT NOT NULL, showInApp INTEGER NOT NULL)")
            db.execSQL("CREATE TABLE messages (id TEXT NOT NULL PRIMARY KEY, operationId TEXT NOT NULL, role TEXT NOT NULL, text TEXT NOT NULL, status TEXT NOT NULL, createdAt INTEGER NOT NULL, emotion TEXT NOT NULL, sourceMessageId TEXT, error TEXT)")
            db.execSQL("CREATE INDEX index_messages_operationId ON messages(operationId)")
            db.execSQL("CREATE INDEX index_messages_createdAt ON messages(createdAt)")
            db.execSQL("CREATE TABLE memories (id TEXT NOT NULL PRIMARY KEY, text TEXT NOT NULL, sourceMessageId TEXT, revision INTEGER NOT NULL)")
            db.execSQL("INSERT INTO profile VALUES (1, '小伙伴', '温和', '待发送草稿', 1)")
            db.execSQL("INSERT INTO messages VALUES ('m', 'op', 'USER', '你好', 'COMPLETE', 1, 'CALM', NULL, NULL)")
            db.execSQL("INSERT INTO memories VALUES ('memory', '喜欢散步', NULL, 1)")
            db.version = 1
        }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        fun open() = Room.databaseBuilder(context, CompanionDatabase::class.java, name).addMigrations(CompanionDatabase.MIGRATION_1_2).allowMainThreadQueries().build()
        var database = open()
        try {
            var repo = AndroidCompanionRepository({ database }, AiGateway { error("不能联网") }, scope)
            repo.initialize()
            assertEquals("待发送草稿", repo.state.value.profile.draft); assertEquals(1, repo.state.value.messages.size)
            assertEquals("喜欢散步", repo.state.value.memories.single().text)
            repo.updateOverlay { it.copy(heightFraction = .35f, xFraction = .2f, yFraction = .4f, greetingMode = GreetingMode.OFF) }
            database.close(); database = open()
            repo = AndroidCompanionRepository({ database }, AiGateway { error("不能联网") }, scope); repo.initialize()
            assertEquals(.35f, repo.state.value.overlay.heightFraction); assertEquals(.2f, repo.state.value.overlay.xFraction)
            assertEquals(GreetingMode.OFF, repo.state.value.overlay.greetingMode)
        } finally { scope.cancel(); database.close() }
    }
}
