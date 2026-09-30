package com.aiocw.myapplication.playback

import androidx.media3.common.Player
import androidx.media3.ui.AspectRatioFrameLayout
import com.aiocw.myapplication.shared.model.DisplayMode
import com.aiocw.myapplication.shared.model.PlaybackMode

/** 需求播放模式 → Media3 repeat/shuffle 映射（见鉴权分析 §7.3）。 */
object PlayerModes {

    fun apply(player: Player, mode: PlaybackMode) {
        when (mode) {
            // 单个循环
            PlaybackMode.SINGLE_LOOP -> {
                player.repeatMode = Player.REPEAT_MODE_ONE
                player.shuffleModeEnabled = false
            }
            // 整体顺序循环
            PlaybackMode.LOOP_ALL -> {
                player.repeatMode = Player.REPEAT_MODE_ALL
                player.shuffleModeEnabled = false
            }
            // 整体随机循环
            PlaybackMode.LOOP_SHUFFLE -> {
                player.repeatMode = Player.REPEAT_MODE_ALL
                player.shuffleModeEnabled = true
            }
            // 关闭循环：顺序播完即停（= 不自动连播）
            PlaybackMode.OFF -> {
                player.repeatMode = Player.REPEAT_MODE_OFF
                player.shuffleModeEnabled = false
            }
        }
    }

    fun label(mode: PlaybackMode): String = when (mode) {
        PlaybackMode.SINGLE_LOOP -> "单个循环"
        PlaybackMode.LOOP_ALL -> "整体顺序循环"
        PlaybackMode.LOOP_SHUFFLE -> "整体随机循环"
        PlaybackMode.OFF -> "顺序播完"
    }

    /** 播放模式循环切换顺序。 */
    fun next(mode: PlaybackMode): PlaybackMode = when (mode) {
        PlaybackMode.LOOP_ALL -> PlaybackMode.LOOP_SHUFFLE
        PlaybackMode.LOOP_SHUFFLE -> PlaybackMode.SINGLE_LOOP
        PlaybackMode.SINGLE_LOOP -> PlaybackMode.OFF
        PlaybackMode.OFF -> PlaybackMode.LOOP_ALL
    }

    /** 从当前 player 状态反推模式（用于 UI 展示同步）。 */
    fun of(player: Player): PlaybackMode = when {
        player.repeatMode == Player.REPEAT_MODE_ONE -> PlaybackMode.SINGLE_LOOP
        player.shuffleModeEnabled -> PlaybackMode.LOOP_SHUFFLE
        player.repeatMode == Player.REPEAT_MODE_ALL -> PlaybackMode.LOOP_ALL
        else -> PlaybackMode.OFF
    }
}

/** 画面适配模式 ↔ Media3 PlayerView resize mode（默认适应：不拉伸不变形）。 */
object DisplayModes {

    fun resizeMode(mode: DisplayMode): Int = when (mode) {
        // RESIZE_MODE_* 常量定义在 AspectRatioFrameLayout（PlayerView.resizeMode 使用同一套值）
        DisplayMode.FIT -> AspectRatioFrameLayout.RESIZE_MODE_FIT      // 适应：完整显示，留黑边，不变形
        DisplayMode.FILL -> AspectRatioFrameLayout.RESIZE_MODE_ZOOM    // 充满：铺满屏幕，裁剪多余部分
        DisplayMode.STRETCH -> AspectRatioFrameLayout.RESIZE_MODE_FILL // 拉伸：铺满但可能变形
    }

    fun label(mode: DisplayMode): String = when (mode) {
        DisplayMode.FIT -> "适应（不变形）"
        DisplayMode.FILL -> "充满（裁剪）"
        DisplayMode.STRETCH -> "拉伸（全屏）"
    }

    fun next(mode: DisplayMode): DisplayMode = when (mode) {
        DisplayMode.FIT -> DisplayMode.FILL
        DisplayMode.FILL -> DisplayMode.STRETCH
        DisplayMode.STRETCH -> DisplayMode.FIT
    }
}
