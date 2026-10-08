package com.aiocw.carvideo.ui

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.GridView
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import com.aiocw.carvideo.App
import com.aiocw.carvideo.R
import com.aiocw.carvideo.playback.PlaybackService
import com.aiocw.carvideo.shared.Library
import com.aiocw.carvideo.shared.model.LibraryScope
import com.aiocw.carvideo.shared.model.VideoEntry
import com.aiocw.carvideo.ui.glass.GlassBackground
import java.io.File

/**
 * 视频管理页：左侧分类（全部/收藏/各文件夹），右侧视频列表；
 * 支持收藏、删除（鉴权分析 §9）、扫描、进入设置与播放页。
 */
class MainActivity : Activity() {

    private data class CategoryRow(val label: String, val scope: LibraryScope)

    private lateinit var library: Library
    private lateinit var categoryList: ListView
    private lateinit var videoGrid: GridView
    private lateinit var emptyText: TextView
    private lateinit var videoAdapter: VideoGridAdapter

    private val categories = mutableListOf<CategoryRow>()
    private var currentScope: LibraryScope = LibraryScope.All
    private var currentEntries: List<VideoEntry> = emptyList()

    private val libraryListener: () -> Unit = { runOnUiThread { render() } }
    private var lastAutoScanAt = 0L

    /** 当前界面应用的字号系数（与设置不一致时重建，全局字号即时生效）。 */
    private var appliedFontScale = 1f

    /** 界面字号统一缩放：创建前套用全局字体缩放系数。 */
    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(FontScale.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        library = (application as App).library
        appliedFontScale = library.settings.fontScale
        setContentView(R.layout.activity_main)

        categoryList = findViewById(R.id.category_list)
        videoGrid = findViewById(R.id.video_grid)
        emptyText = findViewById(R.id.empty_text)

        // 毛玻璃 UI：壁纸 + 分类面板真磨砂
        GlassBackground.install(this)
        GlassBackground.frost(this, categoryList, 22f)

        videoAdapter = VideoGridAdapter(
            activity = this,
            isFavorite = { library.isFavorite(it.key) },
            onFavorite = { toggleFavorite(it) },
            onDelete = { confirmDelete(listOf(it)) },
        )
        videoGrid.adapter = videoAdapter
        videoGrid.setOnItemClickListener { _, _, position, _ ->
            currentEntries.getOrNull(position)?.let { openPlayer(it, position) }
        }

        categoryList.setOnItemClickListener { _, _, position, _ ->
            categories.getOrNull(position)?.let {
                currentScope = it.scope
                render()
            }
        }

        findViewById<Button>(R.id.btn_settings).setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        findViewById<Button>(R.id.btn_scan).setOnClickListener { doScan() }

        library.addOnLibraryChangedListener(libraryListener)
    }

    override fun onDestroy() {
        library.removeOnLibraryChangedListener(libraryListener)
        super.onDestroy()
    }

    override fun onStart() {
        super.onStart()
        // 字号在设置页/播放菜单被改过 → 重建界面按新字号渲染
        if (library.settings.fontScale != appliedFontScale) {
            recreate()
            return
        }
        ensurePermission()
        render()
        // 兑底：即使热插拔广播被 ROM 吞掉，打开页面也保证数据是新的（5s 节流）
        val now = System.currentTimeMillis()
        if (now - lastAutoScanAt > 5_000) {
            lastAutoScanAt = now
            Thread { library.refresh() }.start()
        }
    }

    // ---------- 权限门（鉴权分析 §3.2） ----------

    private fun ensurePermission() {
        if (library.access.hasAccess()) return
        AlertDialog.Builder(this)
            .setTitle("需要存储权限")
            .setMessage("需要「所有文件访问」权限，用于读取本地/U 盘视频文件夹、执行删除。")
            .setPositiveButton("去授权") { _, _ ->
                val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
                    .setData(Uri.parse("package:$packageName"))
                runCatching { startActivity(intent) }
                    .onFailure { Toast.makeText(this, "无法打开授权页", Toast.LENGTH_LONG).show() }
            }
            .setNegativeButton("退出") { _, _ -> finish() }
            .setCancelable(false)
            .show()
    }

    // ---------- 渲染 ----------

    private fun render() {
        categories.clear()
        categories += CategoryRow("全部视频", LibraryScope.All)
        categories += CategoryRow("我的收藏", LibraryScope.Favorites)
        library.categories().forEach { categories += CategoryRow(it, LibraryScope.Category(it)) }

        categoryList.adapter = ArrayAdapter(this, R.layout.item_category, categories.map { it.label })
        val checked = categories.indexOfFirst { it.scope == currentScope }.coerceAtLeast(0)
        currentScope = categories[checked].scope
        categoryList.setItemChecked(checked, true)

        currentEntries = library.entriesFor(currentScope)
        videoAdapter.submit(currentEntries)
        emptyText.visibility = if (currentEntries.isEmpty()) android.view.View.VISIBLE else android.view.View.GONE
        videoGrid.visibility = if (currentEntries.isEmpty()) android.view.View.GONE else android.view.View.VISIBLE
    }

    // ---------- 操作 ----------

    private fun doScan() {
        Toast.makeText(this, "正在扫描…", Toast.LENGTH_SHORT).show()
        Thread {
            val count = library.refresh().size
            runOnUiThread {
                Toast.makeText(this, "扫描完成：$count 个视频", Toast.LENGTH_SHORT).show()
            }
        }.start()
    }

    private fun toggleFavorite(entry: VideoEntry) {
        val nowFav = library.toggleFavorite(entry)
        Toast.makeText(this, if (nowFav) "已收藏" else "已取消收藏", Toast.LENGTH_SHORT).show()
        render()
    }

    private fun confirmDelete(entries: List<VideoEntry>) {
        val names = entries.joinToString("\n") { it.fileName }
        AlertDialog.Builder(this)
            .setTitle("删除视频")
            .setMessage("确定删除以下文件？此操作不可恢复：\n$names")
            .setPositiveButton("删除") { _, _ -> doDelete(entries) }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun doDelete(entries: List<VideoEntry>) {
        Thread {
            var ok = 0
            for (entry in entries) {
                val deleted = library.access.delete(File(entry.path))
                library.store.removeFavorite(entry.key)
                library.clearProgress(entry.key)
                if (deleted) ok++
            }
            library.refresh()
            runOnUiThread {
                Toast.makeText(this, "已删除 $ok/${entries.size} 个文件", Toast.LENGTH_SHORT).show()
            }
        }.start()
    }

    private fun openPlayer(entry: VideoEntry, index: Int) {
        // 统一抖音式播放页：携带范围与起播条目
        val intent = Intent(this, ImmersiveActivity::class.java)
            .putExtra(ImmersiveActivity.EXTRA_SCOPE_TYPE, scopeTypeName(currentScope))
            .putExtra(ImmersiveActivity.EXTRA_CATEGORY, (currentScope as? LibraryScope.Category)?.name)
            .putExtra(ImmersiveActivity.EXTRA_START_KEY, entry.key)
            .putExtra(ImmersiveActivity.EXTRA_START_INDEX, index)
        startActivity(intent)
    }

    private fun scopeTypeName(scope: LibraryScope): String = when (scope) {
        is LibraryScope.All -> PlaybackService.SCOPE_ALL
        is LibraryScope.Favorites -> PlaybackService.SCOPE_FAVORITES
        is LibraryScope.Category -> PlaybackService.SCOPE_CATEGORY
    }
}
