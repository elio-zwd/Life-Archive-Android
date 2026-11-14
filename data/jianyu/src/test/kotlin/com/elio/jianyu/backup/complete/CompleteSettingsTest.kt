package com.elio.jianyu.backup.complete

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import java.util.UUID
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CompleteSettingsTest {
    @Test fun settingsRoundTripPreservesTypesAndExcludesLifeAndCredentials() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val key = UUID.randomUUID().toString()
        val source = { name: String -> context.getSharedPreferences("source-$key-$name", Context.MODE_PRIVATE) }
        val target = { name: String -> context.getSharedPreferences("target-$key-$name", Context.MODE_PRIVATE) }
        source("roundtable_settings").edit().putBoolean("is_auto_next_enabled", false).commit()
        source("conversation_session_preferences").edit().putStringSet("archived_session_ids", setOf("7", "42")).commit()
        source("dialog_reading_state").edit().putInt("session_7_scroll_offset", 23).putFloat("session_7_scroll_progress", .37f).commit()
        source("jianyu_app_preferences").edit().putString("theme_mode", "DARK").commit()
        val life = context.getSharedPreferences("Life-$key", 0).apply { edit().putString("keep", "Life 值").commit() }
        val keys = context.getSharedPreferences("keys-$key", 0).apply { edit().putString("secret", "synthetic-do-not-export").commit() }
        val raw = CompleteSettings.capture(source)
        assertFalse(raw.contains("synthetic-do-not-export"))
        CompleteSettings.apply(raw, target)
        assertEquals(raw, CompleteSettings.capture(target))
        assertEquals("Life 值", life.getString("keep", null))
        assertEquals("synthetic-do-not-export", keys.getString("secret", null))
    }

    @Test fun unexpectedSettingsAndCredentialKeysStopImportBeforeAnyWrite() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val prefix = UUID.randomUUID().toString()
        val preferences = { name: String -> context.getSharedPreferences("reject-$prefix-$name", 0) }
        val original = CompleteSettings.capture(preferences)
        val hostile = JSONObject(original).apply { getJSONObject("roundtable_settings").put("api_key", JSONObject().put("type", "string").put("value", "synthetic")) }.toString()
        try { CompleteSettings.apply(hostile, preferences); fail("凭据键必须被拒绝") } catch (_: IllegalArgumentException) {}
        assertEquals(original, CompleteSettings.capture(preferences))
    }

    @Test fun wrongValueTypeIsRejectedDuringPreviewWithoutWriting() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val prefix = UUID.randomUUID().toString()
        val preferences = { name: String -> context.getSharedPreferences("typed-$prefix-$name", 0) }
        preferences("jianyu_app_preferences").edit().putString("theme_mode", "DARK").commit()
        val original = CompleteSettings.capture(preferences)
        for (value in listOf(JSONObject().put("type", "int").put("value", 1), JSONObject().put("type", "string").put("value", true))) {
            val hostile = JSONObject(original).apply { getJSONObject("jianyu_app_preferences").put("theme_mode", value) }.toString()
            try { CompleteSettings.apply(hostile, preferences); fail("设置类型错误必须在写入前拒绝") } catch (_: IllegalArgumentException) {}
            assertEquals(original, CompleteSettings.capture(preferences))
        }
    }
}
