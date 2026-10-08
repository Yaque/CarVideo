package com.aiocw.carvideo.ui

import android.content.Context
import android.content.res.Configuration
import com.aiocw.carvideo.shared.data.SettingsRepository
import com.aiocw.carvideo.shared.model.ThemeMode

/**
 * 界面外观统一配置：字号缩放 + 昼夜模式。
 *
 * 各 Activity 在 attachBaseContext 调用 [wrap]，一次性套用：
 * - fontScale：所有文字统一缩放（见 [FontScale]）；
 * - uiMode：白天 / 黑夜 / 跟随系统，驱动 values-night 资源与系统控件配色自动切换。
 */
object UiConfig {

    fun wrap(base: Context): Context {
        val settings = SettingsRepository(base)
        val config = Configuration(base.resources.configuration)
        config.fontScale = settings.fontScale
        config.uiMode = (config.uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or nightBits(base)
        return base.createConfigurationContext(config)
    }

    /** 设置项 → uiMode 夜间位（AUTO 沿用系统当前值）。 */
    private fun nightBits(context: Context): Int {
        val systemNight = context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
        return when (SettingsRepository(context).themeMode) {
            ThemeMode.LIGHT -> Configuration.UI_MODE_NIGHT_NO
            ThemeMode.DARK -> Configuration.UI_MODE_NIGHT_YES
            ThemeMode.AUTO -> systemNight
        }
    }

    fun label(mode: ThemeMode): String = when (mode) {
        ThemeMode.LIGHT -> "白天"
        ThemeMode.DARK -> "黑夜"
        ThemeMode.AUTO -> "跟随系统"
    }

    /** 循环切换顺序：白天 → 黑夜 → 跟随系统。 */
    fun next(mode: ThemeMode): ThemeMode = when (mode) {
        ThemeMode.LIGHT -> ThemeMode.DARK
        ThemeMode.DARK -> ThemeMode.AUTO
        ThemeMode.AUTO -> ThemeMode.LIGHT
    }
}
