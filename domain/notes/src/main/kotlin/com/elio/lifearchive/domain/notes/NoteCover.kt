package com.elio.lifearchive.domain.notes

import com.elio.lifearchive.core.document.LibraryPath

enum class CoverPreset(val key: String, val label: String) {
    MOUNTAIN("mountain", "山野"), LAKE("lake", "湖泊"), FOREST("forest", "林间"), PAPER("paper", "纸页");
}

/** 封面属于应用状态；附件路径相对于库根，不随笔记文件夹改变。 */
sealed interface NoteCover {
    data class Preset(val preset: CoverPreset = CoverPreset.MOUNTAIN) : NoteCover
    data class Attachment(val path: LibraryPath) : NoteCover
}

data class CoverImage(val path: LibraryPath, val label: String)
