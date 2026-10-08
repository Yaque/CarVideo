package com.aiocw.myapplication.ui

import android.app.Activity
import android.graphics.Bitmap
import android.util.LruCache
import android.widget.ImageView
import com.aiocw.myapplication.shared.model.VideoEntry
import com.aiocw.myapplication.shared.util.VideoMetadata
import java.util.concurrent.Executors

/**
 * 播放页上下滑动时的视频封面加载器。
 * 磁盘缓存复用 VideoMetadata（cacheDir/thumbs），内存 LruCache 只留最近几张邻近封面；
 * 异步提取 + tag 防错位，滑动过程中封面稍后到位也安全。
 */
object ThumbLoader {

    private val executor = Executors.newFixedThreadPool(2)
    private val cache = object : LruCache<String, Bitmap>(4) {}

    /** 把 entry 的封面异步加载到 into；entry 为 null 时清空。 */
    fun load(activity: Activity, entry: VideoEntry?, into: ImageView) {
        into.tag = entry?.key
        if (entry == null) {
            into.setImageDrawable(null)
            return
        }
        cache.get(entry.key)?.let {
            into.setImageBitmap(it)
            return
        }
        into.setImageDrawable(null)
        executor.execute {
            val bmp = VideoMetadata.getThumbnail(activity.applicationContext, entry.path, 720)
            if (bmp != null) cache.put(entry.key, bmp)
            activity.runOnUiThread {
                if (into.tag == entry.key && bmp != null) into.setImageBitmap(bmp)
            }
        }
    }
}
