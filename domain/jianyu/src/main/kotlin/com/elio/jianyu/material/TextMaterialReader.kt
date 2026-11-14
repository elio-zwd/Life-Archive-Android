package com.elio.jianyu.material

fun interface TextMaterialReader {
    suspend fun read(uri: String): MaterialDocumentReadResult
}
