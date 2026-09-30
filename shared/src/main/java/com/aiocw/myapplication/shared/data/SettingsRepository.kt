package com.aiocw.myapplication.shared.data

import android.content.Context
import android.content.SharedPreferences
import com.aiocw.myapplication.shared.model.DecodeMode
import com.aiocw.myapplication.shared.model.DisplayMode
import com.aiocw.myapplication.shared.model.PlaybackMode

/**
 * 应用配置（SharedPreferences 持久化）。
 * 播放/解码/扫描等设置不属于鉴权范畴，用轻量 KV 存储即可。
 */
class SettingsRepository(context: Context) {

    private val sp: SharedPreferences =
        context.applicationContext.getSharedPreferences("app_settings", Context.MODE_PRIVATE)

    /** 解码模式：自动（硬解优先）/ 强制硬解 / 强制软解 */
    var decodeMode: DecodeMode
        get() = runCatching { DecodeMode.valueOf(sp.getString(KEY_DECODE_MODE, DecodeMode.AUTO.name)!!) }
            .getOrDefault(DecodeMode.AUTO)
        set(value) = sp.edit().putString(KEY_DECODE_MODE, value.name).apply()

    /** 画面适配：适应（默认，不变形）/ 充满（裁剪）/ 拉伸 */
    var displayMode: DisplayMode
        get() = runCatching { DisplayMode.valueOf(sp.getString(KEY_DISPLAY_MODE, DisplayMode.FIT.name)!!) }
            .getOrDefault(DisplayMode.FIT)
        set(value) = sp.edit().putString(KEY_DISPLAY_MODE, value.name).apply()

    /** 默认播放模式（进入播放页时应用） */
    var defaultPlaybackMode: PlaybackMode
        get() = runCatching { PlaybackMode.valueOf(sp.getString(KEY_PLAY_MODE, PlaybackMode.LOOP_ALL.name)!!) }
            .getOrDefault(PlaybackMode.LOOP_ALL)
        set(value) = sp.edit().putString(KEY_PLAY_MODE, value.name).apply()

    /** 是否记住上次播放模式（否则每次用默认值） */
    var rememberPlaybackMode: Boolean
        get() = sp.getBoolean(KEY_REMEMBER_MODE, true)
        set(value) = sp.edit().putBoolean(KEY_REMEMBER_MODE, value).apply()

    /** 最近一次使用的播放模式（配合 rememberPlaybackMode） */
    var lastPlaybackMode: PlaybackMode
        get() = runCatching { PlaybackMode.valueOf(sp.getString(KEY_LAST_MODE, defaultPlaybackMode.name)!!) }
            .getOrDefault(defaultPlaybackMode)
        set(value) = sp.edit().putString(KEY_LAST_MODE, value.name).apply()

    /** U 盘插入时自动扫描 */
    var scanOnMount: Boolean
        get() = sp.getBoolean(KEY_SCAN_ON_MOUNT, true)
        set(value) = sp.edit().putBoolean(KEY_SCAN_ON_MOUNT, value).apply()

    /** U 盘插入时自动播放（对应来源需开启 autoPlayOnMount，或全局开启） */
    var autoPlayOnMount: Boolean
        get() = sp.getBoolean(KEY_AUTO_PLAY_ON_MOUNT, false)
        set(value) = sp.edit().putBoolean(KEY_AUTO_PLAY_ON_MOUNT, value).apply()

    /** 断点续播 */
    var resumePlayback: Boolean
        get() = sp.getBoolean(KEY_RESUME, true)
        set(value) = sp.edit().putBoolean(KEY_RESUME, value).apply()

    /** 扫描递归深度限制（防大目录卡顿） */
    val maxScanDepth: Int get() = sp.getInt(KEY_MAX_DEPTH, 3)

    companion object {
        private const val KEY_DECODE_MODE = "decode_mode"
        private const val KEY_DISPLAY_MODE = "display_mode"
        private const val KEY_PLAY_MODE = "default_play_mode"
        private const val KEY_REMEMBER_MODE = "remember_play_mode"
        private const val KEY_LAST_MODE = "last_play_mode"
        private const val KEY_SCAN_ON_MOUNT = "scan_on_mount"
        private const val KEY_AUTO_PLAY_ON_MOUNT = "auto_play_on_mount"
        private const val KEY_RESUME = "resume_playback"
        private const val KEY_MAX_DEPTH = "max_scan_depth"
    }
}
