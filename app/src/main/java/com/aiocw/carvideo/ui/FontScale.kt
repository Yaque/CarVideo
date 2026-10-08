package com.aiocw.carvideo.ui

import android.content.Context
import android.content.res.Configuration
import com.aiocw.carvideo.shared.data.SettingsRepository
import kotlin.math.roundToInt

/**
 * 界面字体统一缩放（全局唯一字号开关）。
 *
 * 所有布局/代码中的文字统一使用 sp 计量，这里通过覆盖 Configuration.fontScale
 * 让整个界面（主页 / 设置页 / 播放页 / 弹窗 / 列表）一次性按同一系数缩放，
 * 无需逐个控件设置字号。设置页与播放菜单调参后重建 Activity 即可实时预览。
 */
object FontScale {

    /** 生成已应用字号缩放的 Context（各 Activity.attachBaseContext 调用）。 */
    fun wrap(base: Context): Context {
        val config = Configuration(base.resources.configuration)
        config.fontScale = of(base)
        return base.createConfigurationContext(config)
    }

    /** 当前设置的字号缩放系数。 */
    fun of(context: Context): Float = SettingsRepository(context).fontScale

    /** 加/减一档（10% 一档），写回设置并返回新值。 */
    fun adjust(context: Context, up: Boolean): Float {
        val settings = SettingsRepository(context)
        val next = (settings.fontScale + (if (up) SettingsRepository.FONT_SCALE_STEP else -SettingsRepository.FONT_SCALE_STEP))
            .coerceIn(SettingsRepository.FONT_SCALE_MIN, SettingsRepository.FONT_SCALE_MAX)
        settings.fontScale = next
        return next
    }

    /** 恢复标准 100%。 */
    fun reset(context: Context) {
        SettingsRepository(context).fontScale = 1.0f
    }

    /** 百分比展示，如 "120%"。 */
    fun label(scale: Float): String = "${(scale * 100).roundToInt()}%"
}
