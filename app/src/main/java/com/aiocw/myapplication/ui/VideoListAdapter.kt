package com.aiocw.myapplication.ui

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.TextView
import com.aiocw.myapplication.R
import com.aiocw.myapplication.shared.model.VideoEntry

/** 视频列表适配器（分类管理页 / 播放页共用样式）。 */
class VideoListAdapter(
    private val context: Context,
    private val isFavorite: (VideoEntry) -> Boolean,
    private val onFavorite: (VideoEntry) -> Unit,
    private val onDelete: (VideoEntry) -> Unit,
) : BaseAdapter() {

    private val items = mutableListOf<VideoEntry>()

    fun submit(list: List<VideoEntry>) {
        items.clear()
        items.addAll(list)
        notifyDataSetChanged()
    }

    override fun getCount(): Int = items.size
    override fun getItem(position: Int): VideoEntry = items[position]
    override fun getItemId(position: Int): Long = position.toLong()

    override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
        val view = convertView ?: LayoutInflater.from(context).inflate(R.layout.item_video, parent, false)
        val entry = items[position]

        view.findViewById<TextView>(R.id.text_title).text = entry.title
        view.findViewById<TextView>(R.id.text_meta).text =
            "${entry.category} · ${formatSize(entry.sizeBytes)} · ${entry.fileName}"

        val favButton = view.findViewById<Button>(R.id.btn_favorite)
        favButton.text = if (isFavorite(entry)) "★" else "☆"
        favButton.setOnClickListener { onFavorite(entry) }
        view.findViewById<Button>(R.id.btn_delete).setOnClickListener { onDelete(entry) }
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
