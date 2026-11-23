package com.elio.lifearchive.domain.pet

enum class GreetingMode { OFF, LOCAL, LOCAL_AI }
enum class CompanionTrigger { HEAD, BODY, STROKE, START, RETURN }

data class OverlaySettings(
    val heightFraction: Float = 0.25f,
    val xFraction: Float = 0.85f,
    val yFraction: Float = 0.65f,
    val greetingMode: GreetingMode = GreetingMode.LOCAL,
    val quietStartHour: Int = 22,
    val quietEndHour: Int = 8,
    val mutedUntil: Long = 0,
    val lastGreetingAt: Long = 0,
    val greetingDay: String = "",
    val greetingsToday: Int = 0,
    val screenOffAt: Long = 0,
) {
    fun normalized() = copy(heightFraction = heightFraction.takeIf { it.isFinite() }?.coerceIn(.15f, .4f) ?: .25f,
        xFraction = xFraction.takeIf { it.isFinite() }?.coerceIn(0f, 1f) ?: .85f,
        yFraction = yFraction.takeIf { it.isFinite() }?.coerceIn(0f, 1f) ?: .65f,
        quietStartHour = quietStartHour.coerceIn(0, 23), quietEndHour = quietEndHour.coerceIn(0, 23))

    fun canGreet(trigger: CompanionTrigger, now: Long, hour: Int, day: String): Boolean {
        val quiet = if (quietStartHour == quietEndHour) false else if (quietStartHour < quietEndHour)
            hour in quietStartHour until quietEndHour else hour >= quietStartHour || hour < quietEndHour
        return trigger in setOf(CompanionTrigger.START, CompanionTrigger.RETURN) && greetingMode != GreetingMode.OFF &&
            !quiet && now >= mutedUntil && (lastGreetingAt == 0L || now - lastGreetingAt >= 3_600_000L) &&
            (greetingDay != day || greetingsToday < 4) &&
            (trigger != CompanionTrigger.RETURN || screenOffAt > 0 && now - screenOffAt >= 7_200_000L)
    }
}

data class CompanionGreeting(val text: String, val emotion: CompanionEmotion = CompanionEmotion.HAPPY)
data class CompanionLine(val id: String, val text: String, val trigger: CompanionTrigger, val emotion: CompanionEmotion)
