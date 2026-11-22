package com.elio.lifearchive.data.ai

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.elio.jianyu.network.*
import java.io.File
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SharedAiStorageTest {
    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    @Test fun configurationIsAdoptedOnceAndOldRestoreCannotRollbackIt() {
        val legacy = context.getSharedPreferences("jianyu_ai_runtime_configuration", 0)
        legacy.edit().putString("model.ROUNDTABLE_ANSWER", AiModel.DEEPSEEK_V4_PRO.name).commit()
        val first = AiConfigurationRepository(context)
        assertEquals(AiModel.DEEPSEEK_V4_PRO, first.configuration.value.modelFor(AiUseCase.ROUNDTABLE_ANSWER))
        first.selectModel(AiUseCase.PET_REPLY, AiModel.DEEPSEEK_V4_FLASH)
        legacy.edit().putString("model.PET_REPLY", AiModel.GEMINI_25_FLASH.name).commit()
        assertEquals(AiModel.DEEPSEEK_V4_FLASH, AiConfigurationRepository(context).configuration.value.modelFor(AiUseCase.PET_REPLY))
        assertTrue(first.reset())
        assertEquals(AiModel.GEMINI_38_FLASH, AiConfigurationRepository(context).configuration.value.modelFor(AiUseCase.ROUNDTABLE_ANSWER))
    }
    @Test fun encryptedLegacyFileIsMovedWithoutDecryptingOrCopyingIntoBackup() {
        val old = File(context.noBackupFilesDir, "jianyu/synthetic.enc").apply { parentFile!!.mkdirs(); writeText("synthetic-encrypted-envelope") }
        val target = context.sharedAiKeyFile("synthetic.enc")
        assertEquals("synthetic-encrypted-envelope", target.readText())
        assertFalse(old.exists())
        old.writeText("stale-legacy-envelope")
        assertEquals("synthetic-encrypted-envelope", context.sharedAiKeyFile("synthetic.enc").readText())
        assertFalse(old.exists())
        assertEquals(File(context.noBackupFilesDir, "shared-ai").canonicalPath, target.parentFile!!.canonicalPath)
    }
    @Test fun providerStreamParsingExcludesThinkingAndBuildsOnlySuppliedContext() {
        val thought = kotlinx.serialization.json.Json.parseToJsonElement("""{"candidates":[{"content":{"parts":[{"thought":true,"text":"private thought"},{"text":"你好"}]}}]}""")
        assertEquals("你好", textDelta(AiProvider.GEMINI, thought as kotlinx.serialization.json.JsonObject))
        val delta = kotlinx.serialization.json.Json.parseToJsonElement("""{"choices":[{"delta":{"content":"朋友"}}]}""")
        assertEquals("朋友", textDelta(AiProvider.DEEPSEEK, delta as kotlinx.serialization.json.JsonObject))
        val request = createTextRequest(com.elio.lifearchive.domain.ai.AiRequest(
            com.elio.lifearchive.domain.ai.AiRequestId("pet", "1", "op"), AiUseCase.PET_REPLY, "人格",
            listOf(com.elio.lifearchive.domain.ai.AiMessage(com.elio.lifearchive.domain.ai.AiRole.USER, "当前问题"))), AiModel.GEMINI_25_FLASH, "synthetic-secret")
        assertFalse(request.url.toString().contains("synthetic-secret"))
        assertEquals("synthetic-secret", request.header("x-goog-api-key"))
        val buffer = okio.Buffer(); request.body!!.writeTo(buffer)
        val payload = buffer.readUtf8()
        assertTrue(payload.contains("当前问题")); assertTrue(payload.contains("人格"))
        assertFalse(payload.contains("previous_interaction_id")); assertFalse(payload.contains("synthetic-secret"))
    }
}
