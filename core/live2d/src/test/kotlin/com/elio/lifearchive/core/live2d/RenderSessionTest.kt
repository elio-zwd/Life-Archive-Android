package com.elio.lifearchive.core.live2d

import org.junit.Assert.*
import org.junit.Test

class RenderSessionTest {
    @Test fun hiddenSurfaceDoesNotCreateModel() {
        var creates = 0
        val session = RenderSession({ creates++ }, { error("隐藏 Surface 无需释放模型") })
        session.visible(false); session.close()
        assertEquals(0, creates)
    }
    @Test fun repeatedResumeDoesNotDuplicateModelAndPauseReleasesOnce() {
        var creates = 0; var releases = 0
        val session = RenderSession({ creates++ }, { releases++ })
        repeat(3) { session.visible(true) }; repeat(3) { session.visible(false) }
        assertEquals(1, creates); assertEquals(1, releases); assertFalse(session.opened)
    }
    @Test fun resumingRebuildsAfterResourcesReleased() {
        val calls = mutableListOf<String>()
        val session = RenderSession({ calls += "create" }, { calls += "release" })
        session.visible(true); session.visible(false); session.visible(true); session.close()
        assertEquals(listOf("create", "release", "create", "release"), calls)
    }
    @Test fun destroyedSurfaceCannotBeResurrectedByLateLifecycleEvent() {
        var creates = 0; var releases = 0
        val session = RenderSession({ creates++ }, { releases++ })
        session.visible(true); session.close(); session.close(); session.visible(true)
        assertEquals(1, creates); assertEquals(1, releases)
    }
    @Test fun failedLoadDoesNotBecomeOpenedAndCanBeClosed() {
        var releases = 0
        val session = RenderSession({ throw IllegalStateException("加载失败") }, { releases++ })
        assertThrows(IllegalStateException::class.java) { session.visible(true) }
        session.close(); assertFalse(session.opened); assertEquals(0, releases)
    }
    @Test fun oneWindowStoppingDoesNotChangeAnotherWindow() {
        var live = 0
        val app = RenderSession({ live++ }, { live-- })
        val desktop = RenderSession({ live++ }, { live-- })
        app.visible(true); desktop.visible(true); app.close()
        assertTrue(desktop.opened); assertEquals(1, live)
        desktop.close(); assertEquals(0, live)
    }
    @Test fun sameReplyDoesNotRepeatActionButNextReplyWithSameMoodDoes() {
        val cues = CueTracker()
        assertTrue(cues.accept("reply-1:COMPLETE:HAPPY"))
        assertFalse(cues.accept("reply-1:COMPLETE:HAPPY"))
        assertTrue(cues.accept("reply-2:GENERATING:THINKING"))
        assertTrue(cues.accept("reply-2:COMPLETE:HAPPY"))
    }
}
