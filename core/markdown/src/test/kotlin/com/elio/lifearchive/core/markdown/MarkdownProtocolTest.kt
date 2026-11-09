package com.elio.lifearchive.core.markdown

import android.net.Uri
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class MarkdownProtocolTest {
    @Test fun actionMustUseExactLocalOriginAndCurrentRevision() {
        fun action(prefix: String, revision: String = "current") = Uri.parse("$prefix/reader/action?type=link&revision=$revision")
        assertTrue(MarkdownProtocol.acceptsAction(action(MarkdownProtocol.ORIGIN), "current"))
        for (origin in listOf("http://appassets.androidplatform.net", "https://appassets.androidplatform.net:444", "https://other.example", "file://appassets.androidplatform.net")) {
            assertFalse(origin, MarkdownProtocol.acceptsAction(action(origin), "current"))
        }
        assertFalse(MarkdownProtocol.acceptsAction(action(MarkdownProtocol.ORIGIN, "old"), "current"))
    }

    @Test fun framesAndStaleMessagesCannotSubmitCacheOrProgress() {
        val message = JSONObject().put("revision", "current")
        assertTrue(MarkdownProtocol.acceptsMessage(true, MarkdownProtocol.ORIGIN, "current", message))
        assertFalse(MarkdownProtocol.acceptsMessage(false, MarkdownProtocol.ORIGIN, "current", message))
        assertFalse(MarkdownProtocol.acceptsMessage(true, "https://other.example", "current", message))
        assertFalse(MarkdownProtocol.acceptsMessage(true, MarkdownProtocol.ORIGIN, "new", message))
    }

    @Test fun staticAssetAccessDoesNotExposeOtherBusinessResources() {
        fun asset(path: String) = MarkdownProtocol.isStaticAsset(Uri.parse(MarkdownProtocol.ORIGIN + path))
        assertTrue(asset("/reader/index.html"))
        assertTrue(asset("/reader/mathjax/tex-svg.js"))
        assertTrue(asset("/covers/mountain.png"))
        for (path in listOf("/skills/role.md", "/skill_knowledge/private.md", "/avatars/role.png", "/reader/../skills/role.md", "/reader/%2e%2e/skills/role.md", "/reader/%5cskills.md", "/covers/other.png")) {
            assertFalse(path, asset(path))
        }
    }
}
