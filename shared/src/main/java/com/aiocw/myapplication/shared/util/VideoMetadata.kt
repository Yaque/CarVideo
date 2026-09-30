package com.aiocw.myapplication.shared.util

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import java.io.File
import java.io.FileOutputStream

/**
 * 从文件直接提取时长/缩略图（无需 MediaStore 索引，见鉴权分析 §6）。
 */
object VideoMetadata {

    fun getDurationMs(path: String): Long {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(path)
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
        } catch (t: Throwable) {
            0L
        } finally {
            runCatching { retriever.release() }
        }
    }

    /**
     * 缩略图（带磁盘缓存，cacheDir/thumbs/<hash>.jpg）。失败返回 null。
     * 注意：需在后台线程调用。
     */
    fun getThumbnail(context: Context, path: String, maxPx: Int = 320): Bitmap? {
        val cache = File(context.cacheDir, "thumbs")
        val cacheFile = File(cache, Integer.toHexString(path.hashCode()) + ".jpg")
        if (cacheFile.isFile && cacheFile.length() > 0) {
            return runCatching { android.graphics.BitmapFactory.decodeFile(cacheFile.absolutePath) }.getOrNull()
        }
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(path)
            val bitmap = retriever.getFrameAtTime(1_000_000) ?: retriever.frameAtTime ?: return null
            val scaled = scaleDown(bitmap, maxPx)
            cache.mkdirs()
            runCatching {
                FileOutputStream(cacheFile).use { out -> scaled.compress(Bitmap.CompressFormat.JPEG, 80, out) }
            }
            scaled
        } catch (t: Throwable) {
            null
        } finally {
            runCatching { retriever.release() }
        }
    }

    private fun scaleDown(bitmap: Bitmap, maxPx: Int): Bitmap {
        val w = bitmap.width
        val h = bitmap.height
        if (w <= 0 || h <= 0 || (w <= maxPx && h <= maxPx)) return bitmap
        val ratio = maxPx.toFloat() / maxOf(w, h)
        return Bitmap.createScaledBitmap(bitmap, (w * ratio).toInt().coerceAtLeast(1), (h * ratio).toInt().coerceAtLeast(1), true)
    }
}
