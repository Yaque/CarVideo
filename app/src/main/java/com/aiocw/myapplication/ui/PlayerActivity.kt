package com.aiocw.myapplication.ui

import android.app.Activity
import android.app.AlertDialog
import android.content.ComponentName
import android.os.Bundle
import android.widget.Button
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
import com.aiocw.myapplication.playback.PlayerModes
import com.aiocw.myapplication.playback.PlaybackService
import com.aiocw.myapplication.playback.toMediaItem
import com.aiocw.myapplication.shared.Library
import com.aiocw.myapplication.shared.model.LibraryScope
import com.aiocw.myapplication.shared.model.PlaybackMode
import com.aiocw.myapplication.shared.model.VideoEntry
import com.aiocw.myapplication.ui.glass.GlassBackground
import com.google.common.util.concurrent.ListenableFuture
import java.io.File

/**
 * 播放界面：
 * 收藏 / 删除当前视频 / 单个循环 / 整体随机循环 / 整体顺序循环 / 播放指定分类或全部。
 * 播放内核在 [PlaybackService]（Media3 MediaSession），本页通过 MediaController 控制，
 * 因此外部控制端（方向盘按键/系统媒体中心）与本页状态天然同步。
 */
class PlayerActivity : Activity() {

    private lateinit var library: Library
    private lateinit var playerView: PlayerView
    private lateinit var textTitle: TextView
    private lateinit var textState: TextView
    private lateinit var btnPlay: Button
    private lateinit var btnMode: Button
    private lateinit var btnFavorite: Button

    private var controller: MediaController? = null
    private var controllerFuture: ListenableFuture<MediaController>? = null

    private var entries: List<VideoEntry> = emptyList()
    private var mode: PlaybackMode = PlaybackMode.LOOP_ALL

    private var scopeType: String = PlaybackService.SCOPE_ALL
    private var category: String? = null
    private var startKey: String? = null
    private var startIndex: Int = 0

    private val playerListener = object : Player.Listener {
        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) = refreshNowPlaying()
        override fun onIsPlayingChanged(isPlaying: Boolean) = refreshNowPlaying()
        override fun onPlaybackStateChanged(playbackState: Int) = refreshNowPlaying()
        override fun onPlayerError(error: PlaybackException) {
            // 播放失败要可见（文件缺失/解码失败等），否则表现为“点了没反应”
            textState.text = "播放失败（错误码 ${error.errorCode}）：${error.message}"
            Toast.makeText(this@PlayerActivity, "播放失败：${error.message}", Toast.LENGTH_LONG).show()
            refreshNowPlaying()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        library = (application as App).library
        setContentView(R.layout.activity_player)
        GlassBackground.install(this)   // 半透明状态栏/导航栏（画面区为黑色）

        playerView = findViewById(R.id.player_view)
        textTitle = findViewById(R.id.text_title)
        textState = findViewById(R.id.text_state)
        btnPlay = findViewById(R.id.btn_play)
        btnMode = findViewById(R.id.btn_mode)
        btnFavorite = findViewById(R.id.btn_favorite)

        scopeType = intent.getStringExtra(EXTRA_SCOPE_TYPE) ?: PlaybackService.SCOPE_ALL
        category = intent.getStringExtra(EXTRA_CATEGORY)
        startKey = intent.getStringExtra(EXTRA_START_KEY)
        startIndex = intent.getIntExtra(EXTRA_START_INDEX, 0)
        mode = if (library.settings.rememberPlaybackMode) {
            library.settings.lastPlaybackMode
        } else {
            library.settings.defaultPlaybackMode
        }

        setupButtons()
        connectController()
    }

    override fun onDestroy() {
        controller?.removeListener(playerListener)
        playerView.player = null
        controllerFuture?.let { MediaController.releaseFuture(it) }
        controllerFuture = null
        controller = null
        super.onDestroy()
    }

    // ---------- MediaController 连接（连接即拉起 PlaybackService） ----------

    private fun connectController() {
        val token = SessionToken(this, ComponentName(this, PlaybackService::class.java))
        val future = MediaController.Builder(this, token).buildAsync()
        controllerFuture = future
        future.addListener({
            runCatching { future.get() }.onSuccess { c ->
                controller = c
                playerView.player = c
                c.addListener(playerListener)
                onControllerReady(c)
            }.onFailure {
                Toast.makeText(this, "播放服务连接失败", Toast.LENGTH_LONG).show()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun onControllerReady(c: MediaController) {
        if (c.mediaItemCount == 0) {
            buildAndPlay(c)
        } else {
            // 服务已在播（如 U 盘自动播放）：仅同步到指定条目
            // 注意：Player 接口没有 mediaItems 列表属性，只能用 mediaItemAt(i) 遍历
            var idx = -1
            val targetKey = startKey
            if (targetKey != null) {
                for (i in 0 until c.mediaItemCount) {
                    if (c.getMediaItemAt(i)?.mediaId == targetKey) {
                        idx = i
                        break
                    }
                }
            }
            if (idx >= 0 && idx != c.currentMediaItemIndex) {
                c.seekTo(idx, library.getProgress(c.getMediaItemAt(idx)?.mediaId ?: ""))
            }
            // 关键：列表已存在但播放器可能处于暂停/IDLE（如播完停住），必须确保开始播放
            if (c.playbackState == Player.STATE_IDLE) c.prepare()
            c.play()
            PlayerModes.apply(c, mode)
            refreshNowPlaying()
        }
    }

    private fun buildAndPlay(c: MediaController) {
        Thread {
            if (library.snapshot().isEmpty() && scopeType != PlaybackService.SCOPE_FAVORITES) {
                library.refresh()
            }
            val list = entriesOfScope()
            entries = list
            runOnUiThread {
                if (list.isEmpty()) {
                    Toast.makeText(this, "该范围内没有视频", Toast.LENGTH_LONG).show()
                    refreshNowPlaying()
                    return@runOnUiThread
                }
                var index = list.indexOfFirst { it.key == startKey }
                if (index < 0) index = startIndex.coerceIn(0, list.lastIndex)
                val startPos = if (library.settings.resumePlayback) library.getProgress(list[index].key) else 0L
                c.setMediaItems(list.map { it.toMediaItem() }, index, startPos)
                PlayerModes.apply(c, mode)
                c.prepare()
                c.play()
                refreshNowPlaying()
            }
        }.start()
    }

    private fun entriesOfScope(): List<VideoEntry> = when (scopeType) {
        PlaybackService.SCOPE_FAVORITES -> library.entriesFor(LibraryScope.Favorites)
        PlaybackService.SCOPE_CATEGORY -> library.entriesFor(LibraryScope.Category(category ?: ""))
        else -> library.entriesFor(LibraryScope.All)
    }

    // ---------- 按钮 ----------

    private fun setupButtons() {
        findViewById<Button>(R.id.btn_play).setOnClickListener { withController { c ->
            if (c.isPlaying) c.pause() else c.play()
        } }
        findViewById<Button>(R.id.btn_prev).setOnClickListener { withController { c ->
            c.seekToPreviousMediaItem()
        } }
        findViewById<Button>(R.id.btn_next).setOnClickListener { withController { c ->
            c.seekToNextMediaItem()
        } }
        btnMode.setOnClickListener { withController { c ->
            mode = PlayerModes.next(mode)
            PlayerModes.apply(c, mode)
            library.settings.lastPlaybackMode = mode
            refreshNowPlaying()
        } }
        btnFavorite.setOnClickListener { toggleFavorite() }
        findViewById<Button>(R.id.btn_delete).setOnClickListener { confirmDeleteCurrent() }
        findViewById<Button>(R.id.btn_scope).setOnClickListener { pickScope() }
        findViewById<Button>(R.id.btn_back).setOnClickListener { finish() }
    }

    private inline fun withController(block: (MediaController) -> Unit) {
        val c = controller
        if (c == null) {
            Toast.makeText(this, "播放器连接中…", Toast.LENGTH_SHORT).show()
        } else {
            block(c)
        }
    }

    private fun pickScope() {
        val labels = mutableListOf("全部视频", "我的收藏")
        val scopes = mutableListOf<LibraryScope>(LibraryScope.All, LibraryScope.Favorites)
        library.categories().forEach {
            labels += "分类：$it"
            scopes += LibraryScope.Category(it)
        }
        AlertDialog.Builder(this)
            .setTitle("播放范围")
            .setItems(labels.toTypedArray()) { _, which ->
                val scope = scopes[which]
                scopeType = when (scope) {
                    is LibraryScope.All -> PlaybackService.SCOPE_ALL
                    is LibraryScope.Favorites -> PlaybackService.SCOPE_FAVORITES
                    is LibraryScope.Category -> PlaybackService.SCOPE_CATEGORY
                }
                category = (scope as? LibraryScope.Category)?.name
                startKey = null
                startIndex = 0
                withController { c -> c.stop(); buildAndPlay(c) }
            }
            .show()
    }

    private fun toggleFavorite() {
        val entry = currentEntry() ?: return
        val nowFav = library.toggleFavorite(entry)
        Toast.makeText(this, if (nowFav) "已收藏" else "已取消收藏", Toast.LENGTH_SHORT).show()
        refreshNowPlaying()
    }

    private fun confirmDeleteCurrent() {
        val entry = currentEntry() ?: return
        AlertDialog.Builder(this)
            .setTitle("删除视频")
            .setMessage("确定删除「${entry.fileName}」？此操作不可恢复。")
            .setPositiveButton("删除") { _, _ ->
                withController { c ->
                    val index = c.currentMediaItemIndex
                    if (index >= 0 && index < c.mediaItemCount) c.removeMediaItem(index)
                }
                Thread {
                    library.access.delete(File(entry.path))
                    library.store.removeFavorite(entry.key)
                    library.clearProgress(entry.key)
                    library.refresh()
                    entries = entriesOfScope()
                    runOnUiThread {
                        Toast.makeText(this, "已删除", Toast.LENGTH_SHORT).show()
                        if (entries.isEmpty()) finish()
                    }
                }.start()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun currentEntry(): VideoEntry? {
        val c = controller ?: return null
        val key = c.currentMediaItem?.mediaId ?: return null
        entries.firstOrNull { it.key == key }?.let { return it }
        // 兜底：从 key 还原路径（key = "volumeUuid|path"）
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

    private fun refreshNowPlaying() {
        val c = controller ?: return
        val item = c.currentMediaItem
        val entry = currentEntry()
        textTitle.text = item?.mediaMetadata?.title ?: "未播放"
        btnPlay.text = if (c.isPlaying) "暂停" else "播放"
        btnMode.text = PlayerModes.label(mode)
        btnFavorite.text = if (entry != null && library.isFavorite(entry.key)) "★ 收藏" else "☆ 收藏"
        textState.text = buildString {
            if (c.mediaItemCount > 0) append("第 ${c.currentMediaItemIndex + 1}/${c.mediaItemCount} 个")
            append(" · ").append(PlayerModes.label(mode))
            if (c.shuffleModeEnabled) append(" · 随机")
            entry?.let { append(" · ").append(it.category) }
            append(" · 解码：").append(
                when (library.settings.decodeMode) {
                    com.aiocw.myapplication.shared.model.DecodeMode.AUTO -> "自动（硬解优先）"
                    com.aiocw.myapplication.shared.model.DecodeMode.HARDWARE -> "强制硬解"
                    com.aiocw.myapplication.shared.model.DecodeMode.SOFTWARE -> "强制软解"
                }
            )
        }
    }

    companion object {
        const val EXTRA_SCOPE_TYPE = "scope_type"
        const val EXTRA_CATEGORY = "category"
        const val EXTRA_START_KEY = "start_key"
        const val EXTRA_START_INDEX = "start_index"
    }
}
