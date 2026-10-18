package com.elio.lifearchive

import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = LifeArchiveApplication::class)
class HiltHostTest {
    @Test fun activityReceivesRealHiltGraph() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        try { assertNotNull(controller.get().variant) } finally { controller.pause().stop().destroy() }
    }
}
