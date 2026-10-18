package com.elio.lifearchive.core.document

import org.junit.Assert.*
import org.junit.Test

class DocumentsTest {
    @Test fun preservesBomNewlinesAndUnknownSyntax() {
        val bytes = "\uFEFF---\r\nunknown: [未识别, 42]\r\n---\r\n![[附件.pdf#page=2]]\n%% untouched %%".toByteArray()
        assertArrayEquals(bytes, RawDocument.fromUtf8(bytes).utf8())
    }

    @Test fun rejectsInvalidUtf8() {
        assertThrows(java.nio.charset.CharacterCodingException::class.java) { RawDocument.fromUtf8(byteArrayOf(0xC0.toByte(), 0xAF.toByte())) }
        assertThrows(java.nio.charset.CharacterCodingException::class.java) { RawDocument("\uD800").utf8() }
    }

    @Test fun validatesWindowsAndAndroidPaths() {
        listOf("../a.md", "/a.md", "a//b.md", "a\\b.md", "CON.md", "folder/LPT1.txt", "a./b.md", "a /b.md", "a:b.md", "NUL", "COM¹.md").forEach {
            assertFalse(it, LibraryPath.isValid(it))
        }
        assertTrue(LibraryPath.isValid("笔记本/中文 Test.md"))
        assertEquals(LibraryPath("Folder/A.md").collisionKey, LibraryPath("folder/a.md").collisionKey)
        assertTrue(LibraryPath.conflicts("folder/b.md", listOf("Folder/a.md")))
        assertTrue(LibraryPath.conflicts("a.md/b.md", listOf("a.md")))
        assertFalse(LibraryPath.conflicts("Folder/b.md", listOf("Folder/a.md")))
    }
}
