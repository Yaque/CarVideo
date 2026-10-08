package com.aiocw.carvideo.playback

import android.content.Context
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.RenderersFactory
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import com.aiocw.carvideo.shared.model.DecodeMode

/**
 * 解码器选择策略（见鉴权分析 §8）。
 *
 * - 自动：硬解优先，硬解失败/不可用时由系统软解或扩展渲染器降级；
 *   这里使用 EXTENSION_RENDERER_MODE_ON（扩展渲染器排在硬解之后）——
 *   注意不要用 EXTENSION_RENDERER_MODE_PREFER，那是"软解优先"，与需求相反。
 * - 强制硬解 / 强制软解：按 codec 名称前缀过滤（c2.android./OMX.google. 为平台软解）。
 *
 * 说明：Media3 的 FFmpeg 扩展只提供音频软解；视频"全格式软解"需引入
 * libVLC / ijkplayer / libmpv 等内核（见鉴权分析 §8.4），此处按设置预留切换点。
 */
object DecoderSelector {

    private val SOFTWARE_PREFIXES = listOf("c2.android.", "OMX.google.", "OMX.ffmpeg.")

    fun create(context: Context, mode: DecodeMode): RenderersFactory {
        val factory = DefaultRenderersFactory(context).apply {
            setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON)
        }
        when (mode) {
            DecodeMode.AUTO -> Unit
            DecodeMode.HARDWARE -> factory.setMediaCodecSelector(HARDWARE_ONLY)
            DecodeMode.SOFTWARE -> factory.setMediaCodecSelector(SOFTWARE_ONLY)
        }
        return factory
    }

    private fun isSoftware(name: String): Boolean =
        SOFTWARE_PREFIXES.any { name.startsWith(it, ignoreCase = true) }

    private val HARDWARE_ONLY = MediaCodecSelector { mimeType, requiresSecure, requiresTunneling ->
        val all = MediaCodecSelector.DEFAULT.getDecoderInfos(mimeType, requiresSecure, requiresTunneling)
        val hw = all.filterNot { isSoftware(it.name) }
        if (hw.isEmpty()) all else hw
    }

    private val SOFTWARE_ONLY = MediaCodecSelector { mimeType, requiresSecure, requiresTunneling ->
        val all = MediaCodecSelector.DEFAULT.getDecoderInfos(mimeType, requiresSecure, requiresTunneling)
        val sw = all.filter { isSoftware(it.name) }
        if (sw.isEmpty()) all else sw
    }
}
