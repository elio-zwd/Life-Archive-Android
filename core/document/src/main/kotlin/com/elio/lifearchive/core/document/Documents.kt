package com.elio.lifearchive.core.document

import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CodingErrorAction
import java.util.Locale
import kotlinx.serialization.Serializable

@Serializable
@JvmInline
value class LibraryPath(val value: String) {
    init { require(isValid(value)) { "非法库内路径" } }

    val collisionKey: String get() = value.lowercase(Locale.ROOT)

    companion object {
        /** 同一目录的大小写拼写也保持一致，防止导出到 Windows 后合并目录。 */
        fun conflicts(candidate: String, existing: Collection<String>): Boolean {
            val parts = candidate.split('/')
            return existing.any { path ->
                val other = path.split('/')
                val common = minOf(parts.size, other.size)
                var conflict = false
                for (i in 0 until common) {
                    if (!parts[i].equals(other[i], ignoreCase = true)) break
                    if (parts[i] != other[i] || i == common - 1) { conflict = true; break }
                }
                conflict
            }
        }

        fun isValid(value: String): Boolean = value.isNotBlank() && value.length <= 240 &&
            !value.startsWith('/') && value.split('/').all { part ->
                part.isNotEmpty() && part != "." && part != ".." && part.length <= 120 &&
                    !part.endsWith(' ') && !part.endsWith('.') &&
                    part.none { it.code < 32 || it in "\\:*?\"<>|" } &&
                    !Regex("(?i)^(CON|PRN|AUX|NUL|COM[1-9¹²³]|LPT[1-9¹²³])(?:\\..*)?$").matches(part)
            }
    }
}

@Serializable
@JvmInline
value class DocumentVersion(val value: String)

/** 原始文本包含原有 BOM 与换行，不通过渲染结果反向生成正文。 */
@Serializable
data class RawDocument(val text: String) {
    fun utf8(): ByteArray {
        val encoded = Charsets.UTF_8.newEncoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).encode(CharBuffer.wrap(text))
        return ByteArray(encoded.remaining()).also { encoded.get(it) }
    }

    companion object {
        fun fromUtf8(bytes: ByteArray): RawDocument = RawDocument(
            Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString(),
        )
    }
}

@Serializable
data class DocumentLocation(val heading: String? = null, val block: String? = null, val offset: Int? = null)
