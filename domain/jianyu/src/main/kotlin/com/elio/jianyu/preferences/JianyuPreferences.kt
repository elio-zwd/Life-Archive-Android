package com.elio.jianyu.preferences

import kotlinx.coroutines.flow.StateFlow

interface JianyuPreferences {
    val state: StateFlow<AppPreferencesState>
    fun setThemeMode(mode: ThemeMode)
    fun setFontSizeMode(mode: FontSizeMode)
    fun setContentDensityMode(mode: ContentDensityMode)
    fun setReducedMotion(enabled: Boolean)
    fun setHighContrastText(enabled: Boolean)
    fun setShowMessageTimestamps(enabled: Boolean)
    fun setConfirmSensitiveContext(enabled: Boolean)
    fun reset(): Boolean
}
