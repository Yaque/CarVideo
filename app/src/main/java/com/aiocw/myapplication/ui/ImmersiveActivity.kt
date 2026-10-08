package com.aiocw.myapplication.ui

import android.app.Activity
import android.app.AlertDialog
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.animation.DecelerateInterpolator
import android.widget.Button
import android.widget.ImageView
import android.widget.ListView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.media3.common.C
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
 * - 上滑 / 下滑：抖音式跟手切换下一个 / 上一个视频（画面随手指 1:1 位移，
 *   邻近视频封面随动露出；松手按距离/速度决定切换或回弹，切换时无黑屏闪动）；
 * - 右滑：打开播放列表抽屉（当前列表 + 播放范围：全部/收藏/分类）；左滑或轻点关闭；
 * - 右侧悬浮列：收藏（抖音式直接在页面上）+ 菜单（播放设置弹窗：画面适配/解码/播放模式/删除当前视频/更多）；
 * - 启动进入（无指定视频）：全屏随机播放；从管理页进入：按指定范围与条目起播；
 * - 画面默认"适应"（不变形），可在弹窗/设置页切换充满/拉伸。
 */
class ImmersiveActivity : Activity() {

    private lateinit var library: Library
    private lateinit var pages: View
    private lateinit var content: View
    private lateinit var playerView: PlayerView
    private lateinit var previewPrev: ImageView
    private lateinit var previewNext: ImageView
    private lateinit var previewCurrent: ImageView
    private lateinit var textTitle: TextView
    private lateinit var textHint: TextView
    private lateinit var btnFavorite: Button
    private lateinit var seekBar: SeekBar
    private lateinit var textPause: TextView

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
    private var drawerOpen = false
    private var seekDragging = false
    private var restoredAfterRecreate = false   // 重建恢复：不重播起播条目，接着当前进度播

    /** 当前界面应用的字号系数（与设置不一致时重建，全局字号即时生效）。 */
    private var appliedFontScale = 1f

    private val hideHintRunnable = Runnable { textHint.visibility = View.GONE }
    private val hideCurrentPlaceholderRunnable = Runnable { hideCurrentPlaceholder() }
    private val progressHandler = Handler(Looper.getMainLooper())

    /** 进度条周期刷新（秒级粒度，避免 duration 未就绪时的异常值）。 */
    private val progressTick = object : Runnable {
        override fun run() {
            val c = controller
            if (c != null && !seekDragging) {
                val durSec = (c.duration.coerceAtLeast(0L) / 1000).toInt()
                val posSec = (c.currentPosition.coerceAtLeast(0L) / 1000).toInt()
                seekBar.max = durSec
                seekBar.progress = posSec.coerceAtMost(durSec)
            }
            progressHandler.postDelayed(this, 500)
        }
    }

    // 手势判定（抖音式：上下跟手切换 / 右滑抽屉 / 单击暂停继续）
    private var downY = 0f
    private var downX = 0f
    private var downAt = 0L
    private var dragMode = DRAG_NONE
    private var velocityTracker: VelocityTracker? = null
    private var gestureToken = 0      // 手势代次：动画被打断后旧收尾回调作废
    private val touchSlop by lazy { ViewConfiguration.get(this).scaledTouchSlop }
    private val drawerThreshold by lazy { 110f * resources.displayMetrics.density }

    private val playerListener = object : Player.Listener {
        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) = refreshChrome()
        override fun onIsPlayingChanged(isPlaying: Boolean) = refreshChrome()
        override fun onPlaybackStateChanged(playbackState: Int) = refreshChrome()
        override fun onEvents(player: Player, events: Player.Events) {
            // 新视频首帧渲染完成 → 收起切换瞬间的缩略图占位
            if (events.contains(Player.EVENT_RENDERED_FIRST_FRAME)) hideCurrentPlaceholder()
        }
        override fun onPlayerError(error: PlaybackException) {
            // 错误提示短暂显示后同样自动收起
            textHint.text = "播放失败（错误码 ${error.errorCode}），已跳到下一个"
            scheduleHintHide()
            Toast.makeText(this@ImmersiveActivity, "播放失败：${error.message}", Toast.LENGTH_LONG).show()
            controller?.seekToNextMediaItem()   // 坏文件：跳下一个继续
        }
    }

    /** 界面字号统一缩放：创建前套用全局字体缩放系数（所有页面/弹窗/列表一致）。 */
    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(FontScale.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        library = (application as App).library
        appliedFontScale = library.settings.fontScale
        restoredAfterRecreate = savedInstanceState != null   // 字号调整等重建：不跳回原起播条目
        setContentView(R.layout.activity_immersive)
        enterImmersive()

        pages = findViewById(R.id.pages)
        previewPrev = findViewById(R.id.preview_prev)
        previewNext = findViewById(R.id.preview_next)
        previewCurrent = findViewById(R.id.preview_current)
        content = findViewById(R.id.content)
        playerView = findViewById(R.id.player_view)
        textTitle = findViewById(R.id.text_title)
        textHint = findViewById(R.id.text_hint)
        btnFavorite = findViewById(R.id.btn_favorite)
        seekBar = findViewById(R.id.seek_progress)
        textPause = findViewById(R.id.text_pause)
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
        setupSeekBar()
        connectController()
        scheduleHintHide()   // 滑动提示每次打开只显示几秒
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) enterImmersive()
    }

    override fun onStart() {
        super.onStart()
        // 字号在设置页/播放菜单被改过 → 重建界面按新字号渲染（播放不中断，服务继续播）
        if (library.settings.fontScale != appliedFontScale) {
            recreate()
            return
        }
        progressHandler.post(progressTick)   // 可见时才刷进度条
    }

    override fun onStop() {
        progressHandler.removeCallbacks(progressTick)
        super.onStop()
    }

    override fun onDestroy() {
        progressHandler.removeCallbacks(progressTick)
        textHint.removeCallbacks(hideHintRunnable)
        pages.removeCallbacks(hideCurrentPlaceholderRunnable)
        velocityTracker?.recycle()
        velocityTracker = null
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
                    if (!restoredAfterRecreate && startKey != null) jumpOrRebuild(c) else refreshChrome()
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
                    // 随机起播仅限启动/自动播放进入；范围切换从头播
                    index = if (startKey == null && fromLaunch) Random.nextInt(list.size)
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
     * 无目标重入（如点桌面图标返回）则保持当前播放，不重建列表。
     */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (applyIntent(intent)) {
            controller?.let { jumpOrRebuild(it) }
        } else {
            refreshChrome()
        }
    }

    /** 解析起播参数；返回是否携带起播目标（条目或范围）。 */
    private fun applyIntent(intent: Intent?): Boolean {
        if (intent == null) return false
        val hasTarget = intent.getStringExtra(EXTRA_START_KEY) != null || intent.hasExtra(EXTRA_SCOPE_TYPE)
        fromLaunch = intent.getStringExtra(EXTRA_START_KEY) == null
        scopeType = intent.getStringExtra(EXTRA_SCOPE_TYPE) ?: PlaybackService.SCOPE_ALL
        category = intent.getStringExtra(EXTRA_CATEGORY)
        startKey = intent.getStringExtra(EXTRA_START_KEY)
        startIndex = intent.getIntExtra(EXTRA_START_INDEX, 0)
        return hasTarget
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

    /** 刷新文件名/收藏星标/暂停图标/播放列表高亮。 */
    private fun refreshChrome() {
        val c = controller ?: return
        val entry = currentEntry()
        // 文件名显示在底部居中
        textTitle.text = entry?.fileName ?: c.currentMediaItem?.mediaMetadata?.title ?: ""
        btnFavorite.text = if (entry != null && library.isFavorite(entry.key)) "★" else "☆"
        textPause.visibility = if (c.isPlaying) View.GONE else View.VISIBLE
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
        findViewById<Button>(R.id.btn_playlist).setOnClickListener {
            // 开关式：点一下进播放列表，再点一下返回播放
            if (drawerOpen) closeDrawer() else openDrawer()
        }
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
        val textFontScale = view.findViewById<TextView>(R.id.text_font_scale)
        textFontScale.text = "界面字体：${FontScale.label(library.settings.fontScale)}"
        view.findViewById<Button>(R.id.menu_font_down).setOnClickListener {
            FontScale.adjust(this, up = false)
            textFontScale.text = "界面字体：${FontScale.label(library.settings.fontScale)}"
        }
        view.findViewById<Button>(R.id.menu_font_up).setOnClickListener {
            FontScale.adjust(this, up = true)
            textFontScale.text = "界面字体：${FontScale.label(library.settings.fontScale)}"
        }
        view.findViewById<Button>(R.id.menu_close).setOnClickListener { dialog.dismiss() }
        // 弹窗关闭时若字号已变 → 重建界面按新字号渲染（视频由服务继续播）
        dialog.setOnDismissListener {
            if (library.settings.fontScale != appliedFontScale) recreate()
        }
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
        refreshChrome()   // 先同步当前高亮
        drawer.visibility = View.VISIBLE
        drawer.post {
            drawer.translationX = -drawer.width.toFloat()
            drawer.animate().translationX(0f).setDuration(DRAWER_ANIM_MS).start()
            content.animate().translationX(content.width * 0.22f).setDuration(DRAWER_ANIM_MS).start()
            // 列表自动跳转到正在播放的位置
            val idx = controller?.currentMediaItemIndex ?: 0
            playlistList.setSelection(idx)
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

    // ---------- 手势：抖音式跟手上下切换 / 右滑列表 / 单击暂停继续 ----------

    private fun setupGestures() {
        playerView.setOnTouchListener { _, event -> onPlayerTouch(event) }
    }

    /**
     * 上下滑动完全跟手：内容层（当前视频 + 上下邻近封面）随手指 1:1 位移；
     * 松手按滑动距离 / 甩动速度决定切换或回弹，收尾动画速度也跟随手指速度。
     */
    private fun onPlayerTouch(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                gestureToken++                       // 手指一按即接管，旧收尾回调作废
                pages.animate().cancel()
                downX = event.rawX
                downY = event.rawY
                downAt = System.currentTimeMillis()
                dragMode = DRAG_NONE
                velocityTracker?.recycle()
                velocityTracker = VelocityTracker.obtain().apply { addMovement(event) }
            }

            MotionEvent.ACTION_MOVE -> {
                velocityTracker?.addMovement(event)
                if (dragMode == DRAG_NONE) {
                    val dx = event.rawX - downX
                    val dy = event.rawY - downY
                    if (abs(dx) > touchSlop || abs(dy) > touchSlop) {
                        // 先动得多的方向定手势：上下 = 切换视频，左右 = 抽屉
                        dragMode = if (abs(dy) >= abs(dx)) DRAG_VERTICAL else DRAG_HORIZONTAL
                        if (dragMode == DRAG_VERTICAL) bindPreviews()
                    }
                }
                if (dragMode == DRAG_VERTICAL) {
                    val h = pages.height.toFloat().coerceAtLeast(1f)
                    var dy = event.rawY - downY
                    // 无可切换方向（只有一个视频）→ 橡皮筋阻尼，松手回弹
                    if (neighborIndex(next = dy < 0) < 0) dy *= RUBBER_BAND
                    pages.translationY = dy.coerceIn(-h, h)
                }
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                velocityTracker?.addMovement(event)
                velocityTracker?.computeCurrentVelocity(1000)
                val vy = velocityTracker?.yVelocity ?: 0f
                velocityTracker?.recycle()
                velocityTracker = null

                val dx = event.rawX - downX
                val dy = event.rawY - downY
                val dt = System.currentTimeMillis() - downAt
                when (dragMode) {
                    DRAG_VERTICAL -> settleVertical(vy, cancelled = event.actionMasked == MotionEvent.ACTION_CANCEL)
                    DRAG_HORIZONTAL ->
                        if (abs(dx) > drawerThreshold && abs(dx) > abs(dy) * 1.5) {
                            if (dx > 0) openDrawer() else closeDrawer()
                        }
                    else ->
                        if (abs(dx) < touchSlop && abs(dy) < touchSlop && dt < 400) {
                            if (drawerOpen) closeDrawer() else togglePlayPause()
                        }
                }
                dragMode = DRAG_NONE
            }
        }
        return true
    }

    /** 松手收尾：距离/速度任一达标即切换，否则回弹；收尾时长随剩余距离与手指速度。 */
    private fun settleVertical(vy: Float, cancelled: Boolean) {
        val h = pages.height.toFloat().coerceAtLeast(1f)
        val dy = pages.translationY
        val next = dy < 0
        val target = if (cancelled) -1 else neighborIndex(next)
        val velocity = abs(vy)
        val sameDirection = if (next) vy < 0 else vy > 0
        val complete = target >= 0 &&
            (abs(dy) > h * SWITCH_RATIO || (sameDirection && velocity > h * FLING_RATIO))
        val endY = if (complete) (if (next) -h else h) else 0f
        val remaining = abs(endY - dy)
        // 速度取手指速度与基准速度的较大者：快甩收尾短，慢拖也不拖沓
        val speed = maxOf(velocity, h * 1.2f)
        val duration = ((remaining / speed) * 1000f).toLong().coerceIn(120, 320)
        val token = ++gestureToken
        pages.animate()
            .translationY(endY)
            .setDuration(duration)
            .setInterpolator(DecelerateInterpolator())
            .withEndAction {
                if (token != gestureToken) return@withEndAction   // 已被新手势打断
                if (complete) switchToNeighbor(target) else hidePreviews()
            }
            .start()
    }

    /** 切换到目标条目并复位页面；用缩略图盖住首帧前的空窗，滑过去无黑屏。 */
    private fun switchToNeighbor(target: Int) {
        val c = controller
        if (c == null || target < 0) {
            hidePreviews()
            return
        }
        val entry = entryAt(target)
        ThumbLoader.load(this, entry, previewCurrent)
        previewCurrent.visibility = View.VISIBLE
        previewCurrent.alpha = 1f
        c.seekTo(target, 0L)

        val h = pages.height.toFloat().coerceAtLeast(1f)
        pages.translationY = 0f                 // 新视频已在中央
        previewPrev.translationY = -h
        previewNext.translationY = h
        hidePreviews()
        pages.removeCallbacks(hideCurrentPlaceholderRunnable)
        pages.postDelayed(hideCurrentPlaceholderRunnable, PLACEHOLDER_TIMEOUT_MS)
        refreshChrome()
    }

    /** 绑定上下邻近视频封面并放到页面栈两侧（随动露出）。 */
    private fun bindPreviews() {
        val h = pages.height.toFloat().coerceAtLeast(1f)
        previewPrev.translationY = -h
        previewNext.translationY = h
        ThumbLoader.load(this, entryAt(neighborIndex(false)), previewPrev)
        ThumbLoader.load(this, entryAt(neighborIndex(true)), previewNext)
        previewPrev.visibility = View.VISIBLE
        previewNext.visibility = View.VISIBLE
    }

    private fun hidePreviews() {
        previewPrev.visibility = View.GONE
        previewNext.visibility = View.GONE
    }

    private fun hideCurrentPlaceholder() {
        pages.removeCallbacks(hideCurrentPlaceholderRunnable)
        if (previewCurrent.visibility != View.VISIBLE) return
        previewCurrent.animate()
            .alpha(0f)
            .setDuration(160)
            .withEndAction {
                previewCurrent.visibility = View.GONE
                previewCurrent.alpha = 1f
            }
            .start()
    }

    /** 上/下一个条目索引：跟随播放器顺序（随机模式即随机序列），退化时循环接龙。 */
    private fun neighborIndex(next: Boolean): Int {
        val c = controller ?: return -1
        val count = c.mediaItemCount
        if (count <= 1) return -1
        val cur = c.currentMediaItemIndex
        val idx = if (next) c.nextMediaItemIndex else c.previousMediaItemIndex
        if (idx != C.INDEX_UNSET && idx in 0 until count && idx != cur) return idx
        return if (next) (cur + 1) % count else (cur - 1 + count) % count
    }

    private fun entryAt(index: Int): VideoEntry? {
        val c = controller ?: return null
        if (index < 0 || index >= c.mediaItemCount) return null
        val item = c.getMediaItemAt(index) ?: return null
        val key = item.mediaId
        entries.firstOrNull { it.key == key }?.let { return it }
        return keyToEntry(key, item.mediaMetadata?.title?.toString())
    }

    /** 单击：暂停 / 继续（抖音式），中央显示大 ▶ 图标。 */
    private fun togglePlayPause() {
        val c = controller ?: return
        if (c.isPlaying) {
            c.pause()
        } else {
            if (c.playbackState == Player.STATE_IDLE) c.prepare()
            c.play()
        }
    }

    /** 底部进度条：周期刷新 + 拖动定位。 */
    private fun setupSeekBar() {
        seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) = Unit
            override fun onStartTrackingTouch(sb: SeekBar?) {
                seekDragging = true
            }
            override fun onStopTrackingTouch(sb: SeekBar?) {
                seekDragging = false
                val p = sb?.progress ?: return
                controller?.seekTo(p * 1000L)
            }
        })
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

        private const val DRAWER_ANIM_MS = 200L
        private const val HINT_SHOW_MS = 4_000L
        private const val PLACEHOLDER_TIMEOUT_MS = 1_200L   // 缩略图占位兜底隐藏时长

        private const val DRAG_NONE = 0
        private const val DRAG_VERTICAL = 1
        private const val DRAG_HORIZONTAL = 2

        private const val RUBBER_BAND = 0.3f   // 无可切换方向时的阻尼系数
        private const val SWITCH_RATIO = 0.22f // 滑过屏幕该比例即切换
        private const val FLING_RATIO = 0.5f   // 甩动速度超过 半屏/秒 即切换
    }
}
