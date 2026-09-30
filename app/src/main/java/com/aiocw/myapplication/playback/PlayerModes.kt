package com.aiocw.myapplication.playback

import androidx.media3.common.Player
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
