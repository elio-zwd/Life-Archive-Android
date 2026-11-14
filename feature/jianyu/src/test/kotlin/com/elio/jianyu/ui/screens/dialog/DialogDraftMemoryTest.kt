package com.elio.jianyu.ui.screens.dialog

import org.junit.Assert.*
import org.junit.Test

class DialogDraftMemoryTest {
    @Test fun snapshotGenerationKeepsDraftWhileDatasetReplacementInvalidatesIt() {
        val input = DialogComposerState(inputText = "不能因创建备份丢失的未发送输入", isMultiRoleAnswer = true, isGenerating = true)
        DialogDraftMemory.save("old-content", input)
        assertEquals(input.copy(isGenerating = false), DialogDraftMemory.read("old-content"))
        assertNull(DialogDraftMemory.read("new-content"))
        DialogDraftMemory.save("new-content", DialogComposerState())
        assertNull(DialogDraftMemory.read("old-content"))
    }
}
