package com.elio.jianyu.privacy

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.elio.jianyu.storage.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class JianyuCleanupBoundaryTest {
    @Test fun cleaningJianyuAudioAndCacheLeavesLifeFilesUntouched() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val life = context.filesDir.resolve("library/sentinel.md").also { it.parentFile.mkdirs(); it.writeText("Life 原文") }
        val lifeCache = context.cacheDir.resolve("reader-sentinel").also { it.writeText("Life 缓存") }
        val audio = context.jianyuFilesDir.resolve("jianyu-audio/synthetic.wav").also { it.parentFile.mkdirs(); it.writeText("synthetic") }
        val cache = context.jianyuCacheDir.resolve("synthetic.wav").also { it.writeText("synthetic") }
        assertTrue(clearAppOwnedAudioFiles(context))
        assertFalse(audio.exists())
        assertFalse(cache.exists())
        assertEquals("Life 原文", life.readText())
        assertEquals("Life 缓存", lifeCache.readText())
    }

    @Test fun legacyAudioPathsCannotResolveLifeContent() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val life = context.filesDir.resolve("library/sentinel.wav").also { it.parentFile.mkdirs(); it.writeText("Life") }
        val own = context.jianyuCacheDir.resolve("audio.wav").also { it.writeText("见域") }
        assertNull(context.ownedAudioFile(life.absolutePath))
        assertNull(context.ownedAudioFile("../library/sentinel.wav"))
        assertEquals(own, context.ownedAudioFile(own.absolutePath))
        assertEquals("Life", life.readText())
    }
}
