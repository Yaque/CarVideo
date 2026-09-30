package com.aiocw.myapplication.ui

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import com.aiocw.myapplication.App
import com.aiocw.myapplication.R
import com.aiocw.myapplication.playback.PlayerModes
import com.aiocw.myapplication.shared.Library
import com.aiocw.myapplication.shared.model.DecodeMode
import com.aiocw.myapplication.shared.model.VideoSource
import com.aiocw.myapplication.ui.glass.GlassBackground

/**
 * 设置页：存储权限 / 视频源（含 U 盘）/ 扫描与自动播放 / 解码模式 / 播放默认值。
 */
class SettingsActivity : Activity() {

    private lateinit var library: Library
    private lateinit var textPermission: TextView
    private lateinit var sourceContainer: LinearLayout
    private lateinit var textDecodeMode: TextView
    private lateinit var textDefaultMode: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        library = (application as App).library
        setContentView(R.layout.activity_settings)

        textPermission = findViewById(R.id.text_permission)
        sourceContainer = findViewById(R.id.source_container)
        textDecodeMode = findViewById(R.id.text_decode_mode)
        textDefaultMode = findViewById(R.id.text_default_mode)

        // 毛玻璃 UI：壁纸 + 各卡片真磨砂
        GlassBackground.install(this)
        GlassBackground.frost(this, findViewById(R.id.card_permission), 22f)
        GlassBackground.frost(this, findViewById(R.id.card_sources), 22f)
        GlassBackground.frost(this, findViewById(R.id.card_decode), 22f)
        GlassBackground.frost(this, findViewById(R.id.card_play), 22f)

        findViewById<Button>(R.id.btn_back).setOnClickListener { finish() }
        findViewById<Button>(R.id.btn_permission).setOnClickListener { requestPermission() }
        findViewById<Button>(R.id.btn_add_source).setOnClickListener { addSource() }
        findViewById<Button>(R.id.btn_decode_mode).setOnClickListener { cycleDecodeMode() }
        findViewById<Button>(R.id.btn_default_mode).setOnClickListener { cycleDefaultMode() }

        bindSwitch(R.id.switch_scan_on_mount, library.settings.scanOnMount) { library.settings.scanOnMount = it }
        bindSwitch(R.id.switch_auto_play, library.settings.autoPlayOnMount) { library.settings.autoPlayOnMount = it }
        bindSwitch(R.id.switch_resume, library.settings.resumePlayback) { library.settings.resumePlayback = it }
        bindSwitch(R.id.switch_remember_mode, library.settings.rememberPlaybackMode) { library.settings.rememberPlaybackMode = it }
    }

    override fun onResume() {
        super.onResume()
        render()
    }

    private fun bindSwitch(id: Int, checked: Boolean, onChange: (Boolean) -> Unit) {
        findViewById<Switch>(id).apply {
            isChecked = checked
            setOnCheckedChangeListener { _, value -> onChange(value) }
        }
    }

    private fun render() {
        textPermission.text = if (library.access.hasAccess()) {
            "状态：已授予「所有文件访问」"
        } else {
            "状态：未授予 —— 无法读取 U 盘/本地视频文件夹"
        }
        textDecodeMode.text = "解码模式：" + when (library.settings.decodeMode) {
            DecodeMode.AUTO -> "自动（硬解优先，失败降级软解）"
            DecodeMode.HARDWARE -> "强制硬解"
            DecodeMode.SOFTWARE -> "强制软解"
        }
        textDefaultMode.text = "默认播放模式：" + PlayerModes.label(library.settings.defaultPlaybackMode)
        renderSources()
    }

    private fun renderSources() {
        sourceContainer.removeAllViews()
        val sources = library.getSources()
        if (sources.isEmpty()) {
            sourceContainer.addView(TextView(this).apply {
                text = "（尚未配置视频源）"
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, 8, 0, 8)
            })
            return
        }
        for (source in sources) {
            sourceContainer.addView(buildSourceRow(source))
        }
    }

    private fun buildSourceRow(source: VideoSource): View {
        val density = resources.displayMetrics.density
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundResource(R.drawable.glass_item)
            setPadding((12 * density).toInt(), (8 * density).toInt(), (8 * density).toInt(), (8 * density).toInt())
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = (8 * density).toInt() }
        }
        val info = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        info.addView(TextView(this).apply {
            text = source.displayName + if (source.autoPlayOnMount) "（插入自动播放）" else ""
            textSize = 15f
            setTextColor(0xFFFFFFFF.toInt())
        })
        info.addView(TextView(this).apply {
            text = source.absolutePath + if (source.volumeUuid != null) "  [卷 ${source.volumeUuid}]" else ""
            textSize = 12f
            setTextColor(0xFFA9B4C9.toInt())
        })
        row.addView(info)
        row.addView(Button(this).apply {
            text = "移除"
            setBackgroundResource(R.drawable.glass_button)
            setTextColor(0xFFFF9E9E.toInt())
            minWidth = (56 * density).toInt()
            setOnClickListener { confirmRemoveSource(source) }
        })
        return row
    }

    private fun confirmRemoveSource(source: VideoSource) {
        AlertDialog.Builder(this)
            .setTitle("移除视频源")
            .setMessage("从列表移除「${source.displayName}」？（不会删除文件本身）")
            .setPositiveButton("移除") { _, _ ->
                library.removeSource(source.id)
                Thread { library.refresh() }.start()
                renderSources()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun requestPermission() {
        val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
            .setData(Uri.parse("package:$packageName"))
        runCatching { startActivity(intent) }
            .onFailure { Toast.makeText(this, "无法打开授权页", Toast.LENGTH_LONG).show() }
    }

    private fun addSource() {
        if (!library.access.hasAccess()) {
            Toast.makeText(this, "请先授予存储权限", Toast.LENGTH_LONG).show()
            requestPermission()
            return
        }
        FolderPickerDialog(this, library.volumes) { dir ->
            val volumeUuid = library.volumes.listVolumes()
                .firstOrNull { v -> v.root != null && dir.absolutePath.startsWith(v.root!!.absolutePath) }
                ?.uuid
            val name = dir.name.ifBlank { dir.absolutePath }
            AlertDialog.Builder(this)
                .setTitle("添加视频源")
                .setMessage("将「${dir.absolutePath}」添加为视频源？其下每个子文件夹将作为一个分类。")
                .setPositiveButton("添加") { _, _ ->
                    library.addSource(volumeUuid, dir.absolutePath, name, autoPlay = false)
                    Thread { library.refresh() }.start()
                    renderSources()
                    Toast.makeText(this, "已添加，正在后台扫描…", Toast.LENGTH_SHORT).show()
                }
                .setNegativeButton("取消", null)
                .show()
        }.show()
    }

    private fun cycleDecodeMode() {
        library.settings.decodeMode = when (library.settings.decodeMode) {
            DecodeMode.AUTO -> DecodeMode.HARDWARE
            DecodeMode.HARDWARE -> DecodeMode.SOFTWARE
            DecodeMode.SOFTWARE -> DecodeMode.AUTO
        }
        render()
        Toast.makeText(this, "解码模式已切换（下次播放生效）", Toast.LENGTH_SHORT).show()
    }

    private fun cycleDefaultMode() {
        library.settings.defaultPlaybackMode = PlayerModes.next(library.settings.defaultPlaybackMode)
        render()
    }
}
