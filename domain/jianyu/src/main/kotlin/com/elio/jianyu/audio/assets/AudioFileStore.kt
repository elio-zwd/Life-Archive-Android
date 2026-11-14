package com.elio.jianyu.audio.assets



data class AudioOrphanFile(
    val relativePath: String,
    val sizeBytes: Long,
    val lastModifiedAt: Long,
    val reasonCode: String,
)

data class AudioOrphanReport(
    val files: List<AudioOrphanFile>,
)
