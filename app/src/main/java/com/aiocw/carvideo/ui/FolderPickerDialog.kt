package com.aiocw.carvideo.ui

import android.app.Activity
import android.app.AlertDialog
import android.view.LayoutInflater
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.ListView
import android.widget.TextView
import com.aiocw.carvideo.R
import com.aiocw.carvideo.shared.storage.VolumeRepository
import java.io.File

/**
 * 文件夹选择器（基于 File API，依赖 MANAGE_EXTERNAL_STORAGE）。
 * 顶层可快速跳转到任意存储卷（内置存储 / SD 卡 / U 盘），见鉴权分析 §3、§5。
 */
class FolderPickerDialog(
    private val activity: Activity,
    private val volumes: VolumeRepository,
    private val onPicked: (File) -> Unit,
) {

    private var current: File = volumes.internalStorageRoot()
    private val children = mutableListOf<File>()
    private lateinit var adapter: ArrayAdapter<String>
    private lateinit var textPath: TextView
    private lateinit var dialog: AlertDialog

    fun show() {
        val view = LayoutInflater.from(activity).inflate(R.layout.dialog_folder_picker, null)
        textPath = view.findViewById(R.id.text_path)
        val folderList: ListView = view.findViewById(R.id.folder_list)

        adapter = ArrayAdapter(activity, android.R.layout.simple_list_item_1, mutableListOf())
        folderList.adapter = adapter
        folderList.setOnItemClickListener { _, _, position, _ ->
            children.getOrNull(position)?.let {
                current = it
                refresh()
            }
        }

        view.findViewById<Button>(R.id.btn_up).setOnClickListener {
            current.parentFile?.let {
                current = it
                refresh()
            }
        }
        view.findViewById<Button>(R.id.btn_volumes).setOnClickListener { pickVolume() }
        view.findViewById<Button>(R.id.btn_ok).setOnClickListener {
            onPicked(current)
            dialog.dismiss()
        }
        view.findViewById<Button>(R.id.btn_cancel).setOnClickListener { dialog.dismiss() }

        dialog = AlertDialog.Builder(activity)
            .setTitle("选择视频源文件夹")
            .setView(view)
            .create()
        refresh()
        dialog.show()
    }

    private fun pickVolume() {
        val infos = volumes.listVolumes().filter { it.root != null }
        AlertDialog.Builder(activity)
            .setTitle("选择存储卷")
            .setItems(infos.map { "${it.label}（${it.root!!.absolutePath}）" }.toTypedArray()) { _, which ->
                current = infos[which].root!!
                refresh()
            }
            .show()
    }

    private fun refresh() {
        textPath.text = current.absolutePath
        val dirs = current.listFiles()
            ?.filter { it.isDirectory && !it.name.startsWith(".") }
            ?.sortedBy { it.name.lowercase() }
            ?: emptyList()
        children.clear()
        children += dirs
        adapter.clear()
        adapter.addAll(if (dirs.isEmpty()) listOf("（此目录无子文件夹，可直接选择）") else dirs.map { "[目录] ${it.name}" })
        adapter.notifyDataSetChanged()
    }
}
