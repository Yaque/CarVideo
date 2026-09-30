package com.aiocw.myapplication.ui

import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import android.view.MotionEvent
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
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
import com.aiocw.myapplication.playback.PlaybackService
import com.aiocw.myapplication.playback.PlayerModes
import com.aiocw.myapplication.playback.PlaylistBuilder
import com.aiocw.myapplication.playback.toMediaItem
import com.aiocw.myapplication.shared.Library
import com.aiocw.myapplication.shared.model.LibraryScope
import com.aiocw.myapplication.shared.model.PlaybackMode
import com.google.common.util.concurrent.ListenableFuture
import kotlin.math.abs
import kotlin.random.Random

/**
 * 抖音式沉浸启动页：
 * - 打开软件立即全屏随机播放一个视频（整体随机循环）；
 * - 上滑 / 下滑：切换下一个 / 上一个随机视频；
 * - 点击画面：进入管理主页（[MainActivity]）；从主页按返回键回到本页继续播放；
 * - 库为空时直接进入主页引导配置视频源。
 */
class ImmersiveActivity : Activity() {

    private lateinit var library: Library
    private lateinit var playerView: PlayerView
    private lateinit var textTitle: TextView
    private lateinit var textHint: TextView

    private var controller: MediaController? = null
    private var controllerFuture: ListenableFuture<MediaController>? = null

    // 手势判定（避开 GestureDetector 重写签名差异，纯事件计算）
    private var downY = 0f
    private var downX = 0f
    private var downAt = 0L

    private val playerListener = object : Player.Listener {
        override fun onMediaItemTransition(mediaItem: androidx.media3.common.MediaItem?, reason: Int) = updateTitle()
        override fun onPlaybackStateChanged(playbackState: Int) = updateTitle()
        override fun onPlayerError(error: PlaybackException) {
            textHint.text = "播放失败（错误码 ${error.errorCode}），已跳到下一个"
            Toast.makeText(this@ImmersiveActivity, "播放失败：${error.message}", Toast.LENGTH_LONG).show()
            // 损坏/缺失文件：跳到下一个随机视频继续
            controller?.seekToNextMediaItem()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        library = (application as App).library
        setContentView(R.layout.activity_immersive)
        enterImmersive()

        playerView = findViewById(R.id.player_view)
        textTitle = findViewById(R.id.text_title)
        textHint = findViewById(R.id.text_hint)

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
                    // 已有播放列表（从主页返回）：保持随机模式继续
                    PlayerModes.apply(c, PlaybackMode.LOOP_SHUFFLE)
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
                    // 库为空 → 直接进主页引导添加视频源
                    gotoHome()
                    finish()
                    return@runOnUiThread
                }
                val randomIndex = Random.nextInt(items.size)
                c.setMediaItems(items, randomIndex, 0L)
                PlayerModes.apply(c, PlaybackMode.LOOP_SHUFFLE)   // 随机循环
                c.prepare()
                c.play()
                updateTitle()
            }
        }.start()
    }

    private fun updateTitle() {
        val c = controller ?: return
        val title = c.currentMediaItem?.mediaMetadata?.title
        textTitle.text = title ?: ""
    }

    // ---------- 手势：上滑/下滑切换，点击进主页 ----------

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
                        abs(dy) > threshold && abs(dy) > abs(dx) -> {
                            // 上滑下一个 / 下滑上一个（抖音式）
                            if (dy < 0) controller?.seekToNextMediaItem()
                            else controller?.seekToPreviousMediaItem()
                        }
                        abs(dx) < threshold && abs(dy) < threshold && dt < 400 -> {
                            gotoHome()   // 点击 → 进入管理主页
                        }
                    }
                    true
                }
                else -> true
            }
        }
    }

    private fun gotoHome() {
        startActivity(Intent(this, MainActivity::class.java))
    }
}
