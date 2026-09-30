package com.aiocw.myapplication.ui

import android.app.Activity
import android.app.AlertDialog
import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import android.view.MotionEvent
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.widget.Button
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.media3.ui.PlayerView
import com.aiocw.myapplication.App
import com.aiocw.myapplication.R
import com.aiocw.myapplication.playback.DisplayModes
import com.aiocw.myapplication.playback.PlaybackService
import com.aiocw.myapplication.playback.PlayerModes
import com.aiocw.myapplication.playback.PlaylistBuilder
import com.aiocw.myapplication.playback.toMediaItem
import com.aiocw.myapplication.shared.Library
import com.aiocw.myapplication.shared.model.DecodeMode
import com.aiocw.myapplication.shared.model.LibraryScope
import com.aiocw.myapplication.shared.model.PlaybackMode
import com.aiocw.myapplication.shared.model.VideoEntry
import com.google.common.util.concurrent.ListenableFuture
import java.io.File
import kotlin.math.abs
import kotlin.random.Random

/**
 * 统一抖音式播放页（启动页 / 管理页点击进入 共用本页）：
 * - 上滑 / 下滑：带动画切换下一个 / 上一个视频；
 * - 右滑：打开播放列表抽屉（当前列表 + 播放范围：全部/收藏/分类）；左滑或轻点关闭；
 * - 右侧悬浮列：收藏（抖音式直接在页面上）+ 菜单（播放设置弹窗：画面适配/解码/播放模式/删除当前视频/更多）；
 * - 启动进入（无指定视频）：全屏随机播放；从管理页进入：按指定范围与条目起播；
 * - 画面默认"适应"（不变形），可在弹窗/设置页切换充满/拉伸。
 */
class ImmersiveActivity : Activity() {

    private lateinit var library: Library
    private lateinit var content: View
    private lateinit var playerView: PlayerView
    private lateinit var textTitle: TextView
    private lateinit var textHint: TextView
    private lateinit var btnFavorite: Button

    private lateinit var drawer: View
    private lateinit var playlistList: ListView
    private lateinit var textPlaylistInfo: TextView
    private lateinit var playlistAdapter: PlaylistAdapter

    private var controller: MediaController? = null
    private var controllerFuture: ListenableFuture<MediaController>? = null

    private var entries: List<VideoEntry> = emptyList()
    private var scopeType: String = PlaybackService.SCOPE_ALL
    private var category: String? = null
    private var startKey: String? = null
    private var startIndex: Int = 0
    private var fromLaunch = false       // 启动进入 = 随机播放
    private var switching = false
    private var drawerOpen = false

    private val hideHintRunnable = Runnable { textHint.visibility = View.GONE }

    // 手势判定（纯事件计算）
    private var downY = 0f
    private var downX = 0f
    private var downAt = 0L

    private val playerListener = object : Player.Listener {
        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) = refreshChrome()
        override fun onIsPlayingChanged(isPlaying: Boolean) = refreshChrome()
        override fun onPlaybackStateChanged(playbackState: Int) = refreshChrome()
        override fun onPlayerError(error: PlaybackException) {
            textHint.text = "播放失败（错误码 ${error.errorCode}），已跳到下一个"
            Toast.makeText(this@ImmersiveActivity, "播放失败：${error.message}", Toast.LENGTH_LONG).show()
            controller?.seekToNextMediaItem()   // 坏文件：跳下一个继续
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        library = (application as App).library
        setContentView(R.layout.activity_immersive)
        enterImmersive()

        content = findViewById(R.id.content)
        playerView = findViewById(R.id.player_view)
        textTitle = findViewById(R.id.text_title)
        textHint = findViewById(R.id.text_hint)
        btnFavorite = findViewById(R.id.btn_favorite)
        drawer = findViewById(R.id.drawer)
        playlistList = findViewById(R.id.playlist_list)
        textPlaylistInfo = findViewById(R.id.text_playlist_info)

        // 未指定起播条目 = 启动/自动播放进入 → 随机播放风格
        fromLaunch = intent.getStringExtra(EXTRA_START_KEY) == null
        scopeType = intent.getStringExtra(EXTRA_SCOPE_TYPE) ?: PlaybackService.SCOPE_ALL
        category = intent.getStringExtra(EXTRA_CATEGORY)
        startKey = intent.getStringExtra(EXTRA_START_KEY)
        startIndex = intent.getIntExtra(EXTRA_START_INDEX, 0)
        // 注：singleTask 下重复进入走 onNewIntent，那里同样要解析参数（否则会继续播旧内容）

        playerView.resizeMode = DisplayModes.resizeMode(library.settings.displayMode)

        setupActions()
        setupGestures()
        setupDrawer()
        connectController()
        scheduleHintHide()   // 滑动提示每次打开只显示几秒
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) enterImmersive()
    }

    override fun onDestroy() {
        textHint.removeCallbacks(hideHintRunnable)
        controller?.removeListener(playerListener)
        playerView.player = null
        controllerFuture?.let { MediaController.releaseFuture(it) }
        controllerFuture = null
        controller = null
        super.onDestroy()
    }

    /** 全屏沉浸（隐藏状态栏/导航栏，滑动临时浮现）。 */
    private fun enterImmersive() {
        @Suppress("DEPRECATION")
        window.insetsController?.let {
            it.hide(WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars())
            it.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    // ---------- 播放 ----------

    private fun connectController() {
        val token = SessionToken(this, ComponentName(this, PlaybackService::class.java))
        val future = MediaController.Builder(this, token).buildAsync()
        controllerFuture = future
        future.addListener({
            runCatching { future.get() }.onSuccess { c ->
                controller = c
                playerView.player = c
                c.addListener(playerListener)
                if (c.mediaItemCount == 0) {
                    buildAndPlay(c)
                } else {
                    // 服务已有播放列表：确保继续播；若带指定条目（管理页点击）则跳过去
                    if (c.playbackState == Player.STATE_IDLE) c.prepare()
                    if (!c.isPlaying) c.play()
                    if (startKey != null) jumpOrRebuild(c) else refreshChrome()
                }
            }.onFailure {
                textHint.text = "播放服务连接失败"
                Toast.makeText(this, "播放服务连接失败", Toast.LENGTH_LONG).show()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun buildAndPlay(c: MediaController) {
        Thread {
            if (library.snapshot().isEmpty() && scopeType != PlaybackService.SCOPE_FAVORITES) {
                library.refresh()
            }
            val list = entriesOfScope()
            runOnUiThread {
                if (list.isEmpty()) {
                    Toast.makeText(this, "该范围内没有视频", Toast.LENGTH_LONG).show()
                    if (fromLaunch) {   // 启动且库为空 → 主页引导添加视频源
                        gotoHome()
                        finish()
                    }
                    return@runOnUiThread
                }
                entries = list
                var index = list.indexOfFirst { it.key == startKey }
                if (index < 0) {
                    index = if (startKey == null) Random.nextInt(list.size)
                    else startIndex.coerceIn(0, list.lastIndex)
                }
                val startPos = if (library.settings.resumePlayback) library.getProgress(list[index].key) else 0L
                c.setMediaItems(list.map { it.toMediaItem() }, index, startPos)
                // 启动进入：随机循环（抖音式）；指定进入：沿用记忆的播放模式
                val mode = if (fromLaunch) PlaybackMode.LOOP_SHUFFLE else currentMode()
                PlayerModes.apply(c, mode)
                c.prepare()
                c.play()
                refreshChrome()
            }
        }.start()
    }

    private fun entriesOfScope(): List<VideoEntry> = when (scopeType) {
        PlaybackService.SCOPE_FAVORITES -> library.entriesFor(LibraryScope.Favorites)
        PlaybackService.SCOPE_CATEGORY -> library.entriesFor(LibraryScope.Category(category ?: ""))
        else -> library.entriesFor(LibraryScope.All)
    }

    /**
     * singleTask 复用时新 Intent 到达（管理页点击另一个视频）：
     * 必须切到新指定的条目/范围，否则会继续播旧内容。
     */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        applyIntent(intent)
        controller?.let { jumpOrRebuild(it) }
    }

    private fun applyIntent(intent: Intent?) {
        if (intent == null) return
        fromLaunch = intent.getStringExtra(EXTRA_START_KEY) == null
        scopeType = intent.getStringExtra(EXTRA_SCOPE_TYPE) ?: PlaybackService.SCOPE_ALL
        category = intent.getStringExtra(EXTRA_CATEGORY)
        startKey = intent.getStringExtra(EXTRA_START_KEY)
        startIndex = intent.getIntExtra(EXTRA_START_INDEX, 0)
    }

    /** 有指定条目：当前列表里有就跳过去，没有（范围变了）就重建列表。 */
    private fun jumpOrRebuild(c: MediaController) {
        val key = startKey
        if (key == null) {
            buildAndPlay(c)
            return
        }
        val idx = indexOfKey(c, key)
        if (idx >= 0) {
            if (c.playbackState == Player.STATE_IDLE) c.prepare()
            c.seekTo(idx, if (library.settings.resumePlayback) library.getProgress(key) else 0L)
            if (!c.isPlaying) c.play()
            refreshChrome()
        } else {
            buildAndPlay(c)
        }
    }

    private fun indexOfKey(c: MediaController, key: String): Int {
        for (i in 0 until c.mediaItemCount) {
            if (c.getMediaItemAt(i)?.mediaId == key) return i
        }
        return -1
    }

    private fun currentMode(): PlaybackMode =
        if (library.settings.rememberPlaybackMode) library.settings.lastPlaybackMode
        else library.settings.defaultPlaybackMode

    /** 刷新标题/收藏星标/播放列表高亮。 */
    private fun refreshChrome() {
        val c = controller ?: return
        val entry = currentEntry()
        textTitle.text = c.currentMediaItem?.mediaMetadata?.title ?: ""
        btnFavorite.text = if (entry != null && library.isFavorite(entry.key)) "★" else "☆"
        refreshPlaylistUi()
    }

    private fun refreshPlaylistUi() {
        val c = controller ?: return
        // 播放列表与播放器条目对齐（服务复用/删除后重建条目快照）
        if (entries.size != c.mediaItemCount) {
            entries = (0 until c.mediaItemCount).map { i ->
                val key = c.getMediaItemAt(i)?.mediaId ?: ""
                entries.firstOrNull { it.key == key }
                    ?: keyToEntry(key, c.getMediaItemAt(i)?.mediaMetadata?.title?.toString())
            }
        }
        playlistAdapter.update(entries, c.currentMediaItemIndex)
        textPlaylistInfo.text = "共 ${c.mediaItemCount} 个 · 当前范围：${scopeLabel()}"
    }

    private fun keyToEntry(key: String, title: String?): VideoEntry {
        val path = key.substringAfter('|', missingDelimiterValue = "")
        return VideoEntry(
            key = key,
            path = path,
            volumeUuid = key.substringBefore('|').takeIf { it != "local" },
            category = File(path).parentFile?.name ?: "",
            title = title ?: File(path).nameWithoutExtension,
            sizeBytes = if (path.isBlank()) 0 else File(path).length(),
            lastModified = if (path.isBlank()) 0 else File(path).lastModified(),
        )
    }

    private fun scopeLabel(): String = when (scopeType) {
        PlaybackService.SCOPE_FAVORITES -> "我的收藏"
        PlaybackService.SCOPE_CATEGORY -> "分类：$category"
        else -> "全部视频"
    }

    private fun currentEntry(): VideoEntry? {
        val c = controller ?: return null
        val key = c.currentMediaItem?.mediaId ?: return null
        entries.firstOrNull { it.key == key }?.let { return it }
        val path = key.substringAfter('|', missingDelimiterValue = "")
        return if (path.isBlank()) null else VideoEntry(
            key = key,
            path = path,
            volumeUuid = key.substringBefore('|').takeIf { it != "local" },
            category = File(path).parentFile?.name ?: "",
            title = File(path).nameWithoutExtension,
            sizeBytes = File(path).length(),
            lastModified = File(path).lastModified(),
        )
    }

    // ---------- 右侧操作列（抖音式） + 播放设置弹窗 ----------

    private fun setupActions() {
        btnFavorite.setOnClickListener { toggleFavorite() }
        findViewById<Button>(R.id.btn_menu).setOnClickListener { showMenu() }
        findViewById<Button>(R.id.btn_playlist).setOnClickListener { openDrawer() }
    }

    private fun toggleFavorite() {
        val entry = currentEntry() ?: return
        val nowFav = library.toggleFavorite(entry)
        Toast.makeText(this, if (nowFav) "已收藏" else "已取消收藏", Toast.LENGTH_SHORT).show()
        refreshChrome()
    }

    /** 统一玻璃风格播放设置弹窗：画面/解码/模式 + 删除 + 更多入口。 */
    private fun showMenu() {
        val view = layoutInflater.inflate(R.layout.dialog_player_menu, null)
        val dialog = AlertDialog.Builder(this).setView(view).create()
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)

        view.findViewById<Button>(R.id.menu_display).apply {
            text = "画面适配：${DisplayModes.label(library.settings.displayMode)}"
            setOnClickListener {
                library.settings.displayMode = DisplayModes.next(library.settings.displayMode)
                playerView.resizeMode = DisplayModes.resizeMode(library.settings.displayMode)
                text = "画面适配：${DisplayModes.label(library.settings.displayMode)}"
            }
        }
        view.findViewById<Button>(R.id.menu_decode).apply {
            text = "解码模式：${decodeLabel(library.settings.decodeMode)}"
            setOnClickListener {
                library.settings.decodeMode = nextDecode(library.settings.decodeMode)
                text = "解码模式：${decodeLabel(library.settings.decodeMode)}"
                Toast.makeText(this@ImmersiveActivity, "解码模式下次播放生效", Toast.LENGTH_SHORT).show()
            }
        }
        view.findViewById<Button>(R.id.menu_mode).apply {
            text = "播放模式：${PlayerModes.label(currentMode())}"
            setOnClickListener {
                val mode = PlayerModes.next(currentMode())
                controller?.let { PlayerModes.apply(it, mode) }
                library.settings.lastPlaybackMode = mode
                text = "播放模式：${PlayerModes.label(mode)}"
            }
        }
        view.findViewById<Button>(R.id.menu_delete).setOnClickListener {
            dialog.dismiss()
            confirmDeleteCurrent()
        }
        view.findViewById<Button>(R.id.menu_more).setOnClickListener {
            dialog.dismiss()
            gotoHome()
        }
        view.findViewById<Button>(R.id.menu_close).setOnClickListener { dialog.dismiss() }
        dialog.show()
    }

    private fun confirmDeleteCurrent() {
        val entry = currentEntry() ?: return
        AlertDialog.Builder(this)
            .setTitle("删除视频")
            .setMessage("确定删除「${entry.fileName}」？此操作不可恢复。")
            .setPositiveButton("删除") { _, _ ->
                // 先从播放列表移除，再删物理文件
                val c = controller
                val index = c?.currentMediaItemIndex ?: -1
                if (c != null && index >= 0 && index < c.mediaItemCount) c.removeMediaItem(index)
                Thread {
                    library.access.delete(File(entry.path))
                    library.store.removeFavorite(entry.key)
                    library.clearProgress(entry.key)
                    library.refresh()
                    entries = entriesOfScope()
                    runOnUiThread {
                        Toast.makeText(this, "已删除", Toast.LENGTH_SHORT).show()
                        refreshChrome()
                        if ((controller?.mediaItemCount ?: 0) == 0) finish()
                    }
                }.start()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    // ---------- 播放列表抽屉（右滑进入） ----------

    private fun setupDrawer() {
        playlistAdapter = PlaylistAdapter()
        playlistList.adapter = playlistAdapter
        playlistList.setOnItemClickListener { _, _, position, _ ->
            controller?.seekTo(position, 0L)
            refreshChrome()
        }
        findViewById<Button>(R.id.btn_close_drawer).setOnClickListener { closeDrawer() }
        findViewById<Button>(R.id.btn_scope_all).setOnClickListener { switchScope(PlaybackService.SCOPE_ALL, null) }
        findViewById<Button>(R.id.btn_scope_fav).setOnClickListener { switchScope(PlaybackService.SCOPE_FAVORITES, null) }
        findViewById<Button>(R.id.btn_scope_cat).setOnClickListener { pickCategory() }
    }

    private fun pickCategory() {
        val cats = library.categories()
        if (cats.isEmpty()) {
            Toast.makeText(this, "暂无分类", Toast.LENGTH_SHORT).show()
            return
        }
        AlertDialog.Builder(this)
            .setTitle("选择分类")
            .setItems(cats.toTypedArray()) { _, which -> switchScope(PlaybackService.SCOPE_CATEGORY, cats[which]) }
            .show()
    }

    private fun switchScope(newType: String, newCategory: String?) {
        scopeType = newType
        category = newCategory
        startKey = null
        startIndex = 0
        controller?.let { c ->
            c.stop()
            buildAndPlay(c)
        }
    }

    private fun openDrawer() {
        if (drawerOpen) return
        drawerOpen = true
        drawer.visibility = View.VISIBLE
        drawer.post {
            drawer.translationX = -drawer.width.toFloat()
            drawer.animate().translationX(0f).setDuration(DRAWER_ANIM_MS).start()
            content.animate().translationX(content.width * 0.22f).setDuration(DRAWER_ANIM_MS).start()
        }
    }

    private fun closeDrawer() {
        if (!drawerOpen) return
        drawerOpen = false
        drawer.animate().translationX(-drawer.width.toFloat()).setDuration(DRAWER_ANIM_MS)
            .withEndAction { drawer.visibility = View.GONE }
            .start()
        content.animate().translationX(0f).setDuration(DRAWER_ANIM_MS).start()
    }

    // ---------- 手势：上下切换（带动画）/ 右滑列表 / 轻点隐藏界面 ----------

    private fun setupGestures() {
        playerView.setOnTouchListener { _: View, event: MotionEvent ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.rawX
                    downY = event.rawY
                    downAt = System.currentTimeMillis()
                    true
                }
                MotionEvent.ACTION_UP -> {
                    val dx = event.rawX - downX
                    val dy = event.rawY - downY
                    val dt = System.currentTimeMillis() - downAt
                    val threshold = 110 * resources.displayMetrics.density
                    when {
                        abs(dx) > threshold && abs(dx) > abs(dy) * 1.5 ->
                            if (dx > 0) openDrawer() else closeDrawer()
                        abs(dy) > threshold && abs(dy) >= abs(dx) -> animateSwitch(next = dy < 0)
                        abs(dx) < threshold && abs(dy) < threshold && dt < 400 ->
                            if (drawerOpen) closeDrawer() else toggleChrome()
                    }
                    true
                }
                else -> true
            }
        }
    }

    /** 抖音式转场：当前画面滑出 → 切换条目 → 新画面从反方向滑入。 */
    private fun animateSwitch(next: Boolean) {
        val c = controller ?: return
        if (switching || c.mediaItemCount <= 1) return
        switching = true
        val h = content.height.toFloat().coerceAtLeast(1f)
        content.animate().cancel()
        content.animate()
            .translationY(if (next) -h else h)
            .alpha(0f)
            .setDuration(ANIM_MS)
            .withEndAction {
                if (next) c.seekToNextMediaItem() else c.seekToPreviousMediaItem()
                content.translationY = if (next) h else -h
                content.animate()
                    .translationY(0f)
                    .alpha(1f)
                    .setDuration(ANIM_MS)
                    .withEndAction { switching = false }
                    .start()
            }
            .start()
    }

    private fun toggleChrome() {
        val visible = textTitle.visibility == View.VISIBLE
        val v = if (visible) View.GONE else View.VISIBLE
        textTitle.visibility = v
        textHint.visibility = v
        if (!visible) scheduleHintHide()   // 重新显示时同样几秒后自动收起提示
    }

    /** 滑动提示：显示几秒后自动隐藏。 */
    private fun scheduleHintHide() {
        textHint.removeCallbacks(hideHintRunnable)
        textHint.visibility = View.VISIBLE
        textHint.postDelayed(hideHintRunnable, HINT_SHOW_MS)
    }

    // ---------- 工具 ----------

    private fun decodeLabel(mode: DecodeMode): String = when (mode) {
        DecodeMode.AUTO -> "自动（硬解优先）"
        DecodeMode.HARDWARE -> "强制硬解"
        DecodeMode.SOFTWARE -> "强制软解"
    }

    private fun nextDecode(mode: DecodeMode): DecodeMode = when (mode) {
        DecodeMode.AUTO -> DecodeMode.HARDWARE
        DecodeMode.HARDWARE -> DecodeMode.SOFTWARE
        DecodeMode.SOFTWARE -> DecodeMode.AUTO
    }

    private fun gotoHome() {
        startActivity(Intent(this, MainActivity::class.java))
    }

    companion object {
        const val EXTRA_SCOPE_TYPE = "scope_type"     // ALL / FAVORITES / CATEGORY
        const val EXTRA_CATEGORY = "category"
        const val EXTRA_START_KEY = "***"
        const val EXTRA_START_INDEX = "start_index"

        private const val ANIM_MS = 220L
        private const val DRAWER_ANIM_MS = 200L
        private const val HINT_SHOW_MS = 4_000L
    }
}
