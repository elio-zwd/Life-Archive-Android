package com.elio.jianyu.material

data class MaterialDocument(
    val title: String,
    val sourceLocator: String,
    val content: String,
    val mimeType: String,
    val sizeBytes: Long,
)

sealed interface MaterialDocumentReadResult {
    data class Success(val document: MaterialDocument) : MaterialDocumentReadResult
    data class Failure(val code: String) : MaterialDocumentReadResult
}

/**
 * 资料正文唯一文件读取边界。页面和对话附件都走同一套字节、字符和格式限制，
 * 不把二进制/PDF 内容伪装成 UTF-8 文本。
 */
