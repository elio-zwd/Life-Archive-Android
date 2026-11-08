package com.elio.lifearchive.core.document

/** 仅在用户确认后映射标签；正文中的标签词保留，弃用时只去掉 #。 */
fun mapDocumentTags(source: String, decisions: Map<String, String?>): String {
    val tags = DocumentAnalyzer.analyze(source).propertyTags
    val mapped = tags.mapNotNull { if (decisions.containsKey(it)) decisions[it] else it }.distinct()
    var text = if (mapped == tags) source else DocumentAnalyzer.withTags(source, mapped)
    val start = DocumentAnalyzer.yaml(text)?.last?.plus(1) ?: 0
    Regex("(?:^|\\s)#([\\p{L}\\p{N}_/-]+)").findAll(DocumentAnalyzer.masked(text)).toList().asReversed().forEach { match ->
        val tag = match.groups[1]!!
        if (tag.range.first >= start && decisions.containsKey(tag.value)) {
            val value = decisions[tag.value]
            if (value == null) text = text.removeRange(tag.range.first - 1, tag.range.first)
            else text = text.replaceRange(tag.range, value)
        }
    }
    return text
}
