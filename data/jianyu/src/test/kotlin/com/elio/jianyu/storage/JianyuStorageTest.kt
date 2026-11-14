package com.elio.jianyu.storage

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class JianyuStorageTest {
    @Test fun settingsWithSameLogicalNameDoNotOverwriteLife() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val life = context.getSharedPreferences("boundary_settings", Context.MODE_PRIVATE)
        val jianyu = context.jianyuPreferences("boundary_settings", Context.MODE_PRIVATE)
        life.edit().putString("theme", "life").commit()
        jianyu.edit().putString("theme", "jianyu").commit()
        assertEquals("life", life.getString("theme", null))
        assertEquals("jianyu", jianyu.getString("theme", null))
        jianyu.edit().clear().commit()
        assertEquals("life", life.getString("theme", null))
    }

    @Test fun filesCachesAndCredentialsHaveSeparateRoots() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        assertEquals(context.filesDir.resolve("jianyu").canonicalFile, context.jianyuFilesDir.canonicalFile)
        assertEquals(context.cacheDir.resolve("jianyu").canonicalFile, context.jianyuCacheDir.canonicalFile)
        assertEquals(context.noBackupFilesDir.resolve("jianyu").canonicalFile, context.jianyuNoBackupFilesDir.canonicalFile)
        assertNotEquals(context.filesDir.canonicalFile, context.jianyuFilesDir.canonicalFile)
    }

    @Test(expected = IllegalArgumentException::class)
    fun settingsCannotEscapeTheirNamespace() {
        ApplicationProvider.getApplicationContext<Context>().jianyuPreferences("../life", Context.MODE_PRIVATE)
    }
}
