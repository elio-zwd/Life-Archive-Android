package com.elio.lifearchive.data.pet

import com.elio.lifearchive.domain.pet.*
import kotlinx.serialization.json.*

internal fun decodeOverlay(raw: String?): OverlaySettings {
    val obj = runCatching { Json.parseToJsonElement(raw ?: "{}").jsonObject }.getOrDefault(JsonObject(emptyMap()))
    fun long(key: String) = obj[key]?.jsonPrimitive?.longOrNull ?: 0L
    return OverlaySettings(
        heightFraction = obj["height"]?.jsonPrimitive?.floatOrNull ?: .25f,
        xFraction = obj["x"]?.jsonPrimitive?.floatOrNull ?: .85f, yFraction = obj["y"]?.jsonPrimitive?.floatOrNull ?: .65f,
        greetingMode = runCatching { GreetingMode.valueOf(obj["mode"]!!.jsonPrimitive.content) }.getOrDefault(GreetingMode.LOCAL),
        quietStartHour = obj["quietStart"]?.jsonPrimitive?.intOrNull ?: 22,
        quietEndHour = obj["quietEnd"]?.jsonPrimitive?.intOrNull ?: 8,
        mutedUntil = long("mutedUntil"), lastGreetingAt = long("lastGreetingAt"),
        greetingDay = obj["day"]?.jsonPrimitive?.contentOrNull ?: "", greetingsToday = long("count").toInt().coerceIn(0, 4),
        screenOffAt = long("screenOffAt"),
        autoPassthrough = obj["autoPassthrough"]?.jsonPrimitive?.booleanOrNull ?: true,
        autoWalk = obj["autoWalk"]?.jsonPrimitive?.booleanOrNull ?: true,
    ).normalized()
}

internal fun encodeOverlay(value: OverlaySettings): String = buildJsonObject {
    put("height", value.heightFraction); put("x", value.xFraction); put("y", value.yFraction)
    put("mode", value.greetingMode.name); put("quietStart", value.quietStartHour); put("quietEnd", value.quietEndHour)
    put("mutedUntil", value.mutedUntil); put("lastGreetingAt", value.lastGreetingAt)
    put("day", value.greetingDay); put("count", value.greetingsToday); put("screenOffAt", value.screenOffAt)
    put("autoPassthrough", value.autoPassthrough); put("autoWalk", value.autoWalk)
}.toString()

internal fun parseCompanionLines(raw: String): List<CompanionLine> = Json.parseToJsonElement(raw).jsonArray.map { element ->
    val row = element.jsonObject
    CompanionLine(row.getValue("id").jsonPrimitive.content, row.getValue("text").jsonPrimitive.content,
        CompanionTrigger.valueOf(row.getValue("trigger").jsonPrimitive.content),
        CompanionEmotion.valueOf(row.getValue("emotion").jsonPrimitive.content))
}.also { lines ->
    require(lines.isNotEmpty() && lines.map { it.id }.distinct().size == lines.size)
    require(lines.all { it.text.isNotBlank() && it.text.length <= 24 && '\n' !in it.text })
    require(CompanionTrigger.entries.all { trigger -> lines.any { it.trigger == trigger } })
}
