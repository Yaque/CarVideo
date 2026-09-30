package com.aiocw.myapplication.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.TextView
import com.aiocw.myapplication.R
import com.aiocw.myapplication.shared.model.VideoEntry

/** 播放列表抽屉适配器：卡片式条目，当前播放项高亮 + "正在播放"标识。 */
class PlaylistAdapter : BaseAdapter() {

    private var items: List<VideoEntry> = emptyList()
    private var currentIndex: Int = -1

    fun update(list: List<VideoEntry>, current: Int) {
        items = list
        currentIndex = current
        notifyDataSetChanged()
    }

    override fun getCount(): Int = items.size
    override fun getItem(position: Int): VideoEntry = items[position]
    override fun getItemId(position: Int): Long = position.toLong()

    override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
        val view = convertView
            ?: LayoutInflater.from(parent.context).inflate(R.layout.item_playlist, parent, false)
        val entry = items[position]
        val isNow = position == currentIndex

        view.setBackgroundResource(if (isNow) R.drawable.glass_panel else R.drawable.glass_item)
        view.findViewById<TextView>(R.id.text_index).text = (position + 1).toString()
        view.findViewById<TextView>(R.id.text_title).text = entry.title
        view.findViewById<TextView>(R.id.text_meta).text =
            "${entry.category} · ${VideoGridAdapter.formatSize(entry.sizeBytes)}"
        view.findViewById<TextView>(R.id.text_now).visibility =
            if (isNow) View.VISIBLE else View.GONE
        return view
    }
}
