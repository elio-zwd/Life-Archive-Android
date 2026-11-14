package com.elio.jianyu.preferences

enum class ThemeMode {
    SYSTEM,
    LIGHT,
    DARK,
}

enum class FontSizeMode {
    SYSTEM,
    SMALL,
    LARGE,
}

enum class ContentDensityMode {
    COMPACT,
    STANDARD,
    COMFORTABLE,
}

data class AppPreferencesState(
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val fontSizeMode: FontSizeMode = FontSizeMode.SYSTEM,
    val contentDensityMode: ContentDensityMode = ContentDensityMode.STANDARD,
    val reducedMotion: Boolean = false,
    val highContrastText: Boolean = false,
    val showMessageTimestamps: Boolean = true,
    val confirmSensitiveContext: Boolean = true,
)

/** 仅保存用户主动选择的应用偏好；不写入正文、Key 或运行时请求数据。 */
