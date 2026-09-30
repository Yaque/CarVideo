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
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
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
import com.aiocw.myapplication.shared.Library
import com.aiocw.myapplication.shared.model.DecodeMode
import com.aiocw.myapplication.shared.model.DisplayMode
import com.aiocw.myapplication.shared.model.LibraryScope
import com.aiocw.myapplication.shared.model.PlaybackMode
import com.google.common.util.concurrent.ListenableFuture
import kotlin.math.abs
import kotlin.random.Random

/**
 * 抖音式沉浸启动页：
 * - 打开软件立即全屏随机播放一个视频；画面默认"适应"（不变形），可在设置中切换充满/拉伸；
 * - 上滑 / 下滑：带动画切换下一个 / 上一个随机视频；
 * - 右上角悬浮按钮：弹出播放设置（画面适配/解码/播放模式），其中"更多"进入管理主页；
 * - 库为空时直接进入主页引导配置视频源。
 */
class ImmersiveActivity : Activity() {

    private lateinit var library: Library
    private lateinit var content: View
    private lateinit var playerView: PlayerView
    private lateinit var textTitle: TextView
    private lateinit var textHint: TextView

    private var controller: MediaController? = null
    private var controllerFuture: ListenableFuture<MediaController>? = null

    private var switching = false

    // 手势判定（纯事件计算，避开 GestureDetector 重写签名差异）
    private var downY = 0f
    private var downX = 0f
    private var downAt = 0L

    private val playerListener = object : Player.Listener {
        override fun onMediaItemTransition(mediaItem: androidx.media3.common.MediaItem?, reason: Int) = updateTitle()
        override fun onPlaybackStateChanged(playbackState: Int) = updateTitle()
        override fun onPlayerError(error: PlaybackException) {
            textHint.text = "播放失败（错误码 ${error.errorCode}），已跳到下一个"
            Toast.makeText(this@ImmersiveActivity, "播放失败：${error.message}", Toast.LENGTH_LONG).show()
            controller?.seekToNextMediaItem()   // 坏文件：跳到下一个随机视频继续
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

        applyDisplayMode()
        findViewById<Button>(R.id.btn_menu).setOnClickListener { showMenu() }
        setupGestures()
        connectController()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) enterImmersive()
    }

    override fun onDestroy() {
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
                    startRandomPlay(c)
                } else {
                    // 已有播放列表（服务未释放）：确保继续播
                    PlayerModes.apply(c, PlaybackMode.LOOP_SHUFFLE)
                    if (c.playbackState == Player.STATE_IDLE) c.prepare()
                    if (!c.isPlaying) c.play()
                    updateTitle()
                }
            }.onFailure {
                textHint.text = "播放服务连接失败"
                Toast.makeText(this, "播放服务连接失败", Toast.LENGTH_LONG).show()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun startRandomPlay(c: MediaController) {
        Thread {
            val items = PlaylistBuilder.build(library, LibraryScope.All)
            runOnUiThread {
                if (items.isEmpty()) {
                    gotoHome()   // 库为空 → 主页引导添加视频源
                    finish()
                    return@runOnUiThread
                }
                c.setMediaItems(items, Random.nextInt(items.size), 0L)
                PlayerModes.apply(c, PlaybackMode.LOOP_SHUFFLE)   // 随机循环
                c.prepare()
                c.play()
                updateTitle()
            }
        }.start()
    }

    private fun updateTitle() {
        textTitle.text = controller?.currentMediaItem?.mediaMetadata?.title ?: ""
    }

    private fun applyDisplayMode() {
        playerView.resizeMode = DisplayModes.resizeMode(library.settings.displayMode)
    }

    // ---------- 手势：上滑/下滑带动画切换；轻点隐藏/显示标题提示 ----------

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
                    val threshold = 120 * resources.displayMetrics.density
                    when {
                        abs(dy) > threshold && abs(dy) > abs(dx) -> animateSwitch(next = dy < 0)
                        abs(dx) < threshold && abs(dy) < threshold && dt < 400 -> toggleChrome()
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
    }

    // ---------- 播放设置弹窗（含"更多"→ 管理主页） ----------

    private fun showMenu() {
        val items = arrayOf(
            "画面适配：${DisplayModes.label(library.settings.displayMode)}",
            "解码模式：${decodeLabel(library.settings.decodeMode)}",
            "播放模式：${PlayerModes.label(currentMode())}",
        )
        AlertDialog.Builder(this)
            .setTitle("视频播放设置")
            .setItems(items) { _, which ->
                when (which) {
                    0 -> {
                        library.settings.displayMode = DisplayModes.next(library.settings.displayMode)
                        applyDisplayMode()
                        Toast.makeText(this, "画面适配：${DisplayModes.label(library.settings.displayMode)}", Toast.LENGTH_SHORT).show()
                    }
                    1 -> {
                        library.settings.decodeMode = nextDecode(library.settings.decodeMode)
                        Toast.makeText(this, "解码模式：${decodeLabel(library.settings.decodeMode)}（下次播放生效）", Toast.LENGTH_SHORT).show()
                    }
                    2 -> {
                        val mode = PlayerModes.next(currentMode())
                        controller?.let { PlayerModes.apply(it, mode) }
                        library.settings.lastPlaybackMode = mode
                        Toast.makeText(this, "播放模式：${PlayerModes.label(mode)}", Toast.LENGTH_SHORT).show()
                    }
                }
            }
            .setNeutralButton("更多：视频管理") { _, _ -> gotoHome() }
            .setNegativeButton("关闭", null)
            .show()
    }

    private fun currentMode(): PlaybackMode =
        controller?.let { PlayerModes.of(it) } ?: library.settings.lastPlaybackMode

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
        private const val ANIM_MS = 220L
    }
}
