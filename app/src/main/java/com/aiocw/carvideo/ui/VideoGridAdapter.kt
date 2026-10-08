package com.aiocw.carvideo.ui

import android.app.Activity
import android.graphics.Bitmap
import android.util.LruCache
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import com.aiocw.carvideo.R
import com.aiocw.carvideo.shared.model.VideoEntry
import com.aiocw.carvideo.shared.util.VideoMetadata
import java.util.concurrent.Executors

/**
 * 视频卡片网格适配器（模块化封面显示）。
 * 封面缩略图异步提取（MediaMetadataRetriever + 磁盘缓存 + 内存 LruCache），滚动不卡顿。
 */
class VideoGridAdapter(
    private val activity: Activity,
    private val isFavorite: (VideoEntry) -> Boolean,
    private val onFavorite: (VideoEntry) -> Unit,
    private val onDelete: (VideoEntry) -> Unit,
) : BaseAdapter() {

    private val items = mutableListOf<VideoEntry>()
    private val executor = Executors.newFixedThreadPool(2)
    private val thumbCache = object : LruCache<String, Bitmap>(48) {}

    fun submit(list: List<VideoEntry>) {
        items.clear()
        items.addAll(list)
        notifyDataSetChanged()
    }

    override fun getCount(): Int = items.size
    override fun getItem(position: Int): VideoEntry = items[position]
    override fun getItemId(position: Int): Long = position.toLong()

    override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
        val view = convertView ?: LayoutInflater.from(activity).inflate(R.layout.item_video_grid, parent, false)
        val entry = items[position]

        view.findViewById<TextView>(R.id.text_title).text = entry.title
        view.findViewById<TextView>(R.id.text_meta).text =
            "${entry.category} · ${formatSize(entry.sizeBytes)}"

        val favButton = view.findViewById<Button>(R.id.btn_favorite)
        favButton.text = if (isFavorite(entry)) "★" else "☆"
        favButton.setOnClickListener { onFavorite(entry) }
        view.findViewById<Button>(R.id.btn_delete).setOnClickListener { onDelete(entry) }

        val img = view.findViewById<ImageView>(R.id.img_thumb)
        val cached = thumbCache.get(entry.key)
        if (cached != null) {
            img.tag = null
            img.setImageBitmap(cached)
        } else {
            img.setImageResource(R.drawable.thumb_placeholder)
            img.tag = entry.key
            executor.execute {
                val bmp = VideoMetadata.getThumbnail(activity, entry.path, 480)
                if (bmp != null) thumbCache.put(entry.key, bmp)
                activity.runOnUiThread {
                    if (img.tag == entry.key) {
                        img.setImageBitmap(bmp)   // bmp 为 null 时保持占位图
                    }
                }
            }
        }
        return view
    }

    companion object {
        fun formatSize(bytes: Long): String = when {
            bytes >= 1L shl 30 -> "%.1f GB".format(bytes / (1024.0 * 1024 * 1024))
            bytes >= 1L shl 20 -> "%.1f MB".format(bytes / (1024.0 * 1024))
            bytes >= 1L shl 10 -> "%.0f KB".format(bytes / 1024.0)
            else -> "$bytes B"
        }
    }
}
