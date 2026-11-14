package com.elio.jianyu.preferences

import android.content.Context

class StoredJianyuPreferences(context: Context) : JianyuPreferences {
    private val context = context.applicationContext
    init { AppPreferences.initialize(this.context) }
    override val state get() = AppPreferences.state
    override fun setThemeMode(mode: ThemeMode) = AppPreferences.setThemeMode(context, mode)
    override fun setFontSizeMode(mode: FontSizeMode) = AppPreferences.setFontSizeMode(context, mode)
    override fun setContentDensityMode(mode: ContentDensityMode) = AppPreferences.setContentDensityMode(context, mode)
    override fun setReducedMotion(enabled: Boolean) = AppPreferences.setReducedMotion(context, enabled)
    override fun setHighContrastText(enabled: Boolean) = AppPreferences.setHighContrastText(context, enabled)
    override fun setShowMessageTimestamps(enabled: Boolean) = AppPreferences.setShowMessageTimestamps(context, enabled)
    override fun setConfirmSensitiveContext(enabled: Boolean) = AppPreferences.setConfirmSensitiveContext(context, enabled)
    override fun reset() = AppPreferences.reset(context)
}
