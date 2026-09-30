package com.aiocw.myapplication.playback

import android.app.Notification
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Handler
import android.os.Looper
import androidx.core.app.ServiceCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.aiocw.myapplication.App
import com.aiocw.myapplication.R
import com.aiocw.myapplication.shared.Library
import com.aiocw.myapplication.shared.model.LibraryScope
import com.aiocw.myapplication.shared.model.PlaybackMode

/**
 * 播放内核服务：ExoPlayer + MediaSession。
 * - 外部控制端（方向盘按键 / 系统媒体中心）经 MediaSession 控制，无需额外权限；
 * - 支持按范围（全部/收藏/分类）起播，供播放页、U 盘自动播放复用；
 * - 周期性保存播放进度，支持断点续播。
 */
class PlaybackService : MediaSessionService() {

    private var mediaSession: MediaSession? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private val library: Library get() = (application as App).library

    private var lastKey: String? = null
    private var lastPositionMs: Long = 0L
    private var consecutiveErrors = 0

    private val progressSaver = object : Runnable {
        override fun run() {
            captureProgress()
            mainHandler.postDelayed(this, PROGRESS_INTERVAL_MS)
        }
    }

    override fun onCreate() {
        super.onCreate()
        val player = ExoPlayer.Builder(this)
            .setRenderersFactory(DecoderSelector.create(this, library.settings.decodeMode))
            .setHandleAudioBecomingNoisy(true)
            .build()

        player.addListener(object : Player.Listener {
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                // 切歌：落盘上一条进度（异步，避免主线程 SQLite IO）
                lastKey?.let { saveProgressAsync(it, lastPositionMs) }
                lastKey = mediaItem?.mediaId
                lastPositionMs = 0L
                captureProgress()
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (!isPlaying) captureProgress()
            }

            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_READY) consecutiveErrors = 0
                if (state == Player.STATE_ENDED) {
                    // 顺序播完即停：清除末条进度，下次从头
                    player.currentMediaItem?.mediaId?.let {
                        library.clearProgress(it)
                    }
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                // 后台播放自愈：坏文件/已删文件跳下一个继续；连续失败防死循环
                consecutiveErrors++
                if (consecutiveErrors > 3 || player.mediaItemCount <= 1) {
                    player.currentMediaItem?.mediaId?.let { library.clearProgress(it) }
                    stopSelf()
                } else {
                    player.seekToNextMediaItem()
                }
            }
        })

        mediaSession = MediaSession.Builder(this, player).build()
        mainHandler.post(progressSaver)
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = mediaSession

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_PLAY_SCOPE) {
            // 规则：startForegroundService 后必须尽快进入前台，先上一个占位通知
            ServiceCompat.startForeground(
                this, STARTUP_NOTIFICATION_ID, buildStartupNotification(),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
            )
            val scope = when (intent.getStringExtra(EXTRA_SCOPE)) {
                SCOPE_FAVORITES -> LibraryScope.Favorites
                SCOPE_CATEGORY -> LibraryScope.Category(intent.getStringExtra(EXTRA_CATEGORY) ?: return START_NOT_STICKY)
                else -> LibraryScope.All
            }
            val modeOverride = intent.getStringExtra(EXTRA_MODE)
                ?.let { runCatching { PlaybackMode.valueOf(it) }.getOrNull() }
            playScope(scope, intent.getIntExtra(EXTRA_INDEX, 0), modeOverride)
        }
        return super.onStartCommand(intent, flags, startId)
    }

    private fun playScope(scope: LibraryScope, startIndex: Int, modeOverride: PlaybackMode? = null) {
        Thread {
            val items = PlaylistBuilder.build(library, scope)
            mainHandler.post {
                val session = mediaSession
                if (session == null || items.isEmpty()) {
                    stopPlaybackAndExit()
                    return@post
                }
                val player = session.player
                val index = startIndex.coerceIn(0, items.lastIndex)
                val startPos = if (library.settings.resumePlayback) {
                    library.getProgress(items[index].mediaId).coerceAtLeast(0L)
                } else 0L
                player.setMediaItems(items, index, startPos)
                PlayerModes.apply(player, modeOverride ?: currentMode())
                player.prepare()
                player.play()
                // 播放启动后把前台通知切换为 Media3 的媒体通知
                mainHandler.postDelayed({
                    runCatching {
                        getSystemService(NotificationManager::class.java)
                            ?.cancel(STARTUP_NOTIFICATION_ID)
                    }
                }, 1500)
            }
        }.start()
    }

    private fun currentMode() = if (library.settings.rememberPlaybackMode) {
        library.settings.lastPlaybackMode
    } else {
        library.settings.defaultPlaybackMode
    }

    private fun captureProgress() {
        val session = mediaSession ?: return
        val player = session.player
        val key = player.currentMediaItem?.mediaId ?: return
        // 播完（STATE_ENDED）不留进度，下次从头；其余状态（含暂停）都落盘
        if (player.playbackState != Player.STATE_ENDED) {
            lastKey = key
            lastPositionMs = player.currentPosition
            saveProgressAsync(key, player.currentPosition)
        }
    }

    /** 进度落盘放后台线程：每 5s 一次，避免主线程磁盘 IO（ANR/卡顿风险）。 */
    private fun saveProgressAsync(key: String, positionMs: Long) {
        Thread { library.saveProgress(key, positionMs) }.start()
    }

    private fun stopPlaybackAndExit() {
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun buildStartupNotification(): Notification =
        Notification.Builder(this, App.CHANNEL_PLAYBACK)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("视频播放")
            .setContentText("正在准备播放…")
            .build()

    override fun onTaskRemoved(rootIntent: Intent?) {
        val player = mediaSession?.player
        if (player == null || !player.playWhenReady || player.mediaItemCount == 0) {
            stopSelf()
        }
    }

    override fun onDestroy() {
        mainHandler.removeCallbacks(progressSaver)
        captureProgress()
        // 销毁前同步落盘一次（进程可能被回收，异步来不及）
        lastKey?.let { library.saveProgress(it, lastPositionMs) }
        mediaSession?.run {
            player.release()
            release()
        }
        mediaSession = null
        super.onDestroy()
    }

    companion object {
        const val ACTION_PLAY_SCOPE = "com.aiocw.myapplication.action.PLAY_SCOPE"
        const val EXTRA_SCOPE = "scope"           // ALL / FAVORITES / CATEGORY
        const val EXTRA_CATEGORY = "category"
        const val EXTRA_INDEX = "index"
        const val EXTRA_MODE = "mode"             // PlaybackMode 名称（如自动播放强制随机）

        const val SCOPE_ALL = "ALL"
        const val SCOPE_FAVORITES = "FAVORITES"
        const val SCOPE_CATEGORY = "CATEGORY"

        private const val STARTUP_NOTIFICATION_ID = 42
        private const val PROGRESS_INTERVAL_MS = 5_000L

        /** 构造"按范围播放"的 Intent（供 U 盘自动播放 / 外部入口复用）。 */
        fun playIntent(
            context: Context,
            scope: LibraryScope,
            startIndex: Int = 0,
            mode: PlaybackMode? = null,
        ): Intent {
            val intent = Intent(context, PlaybackService::class.java).setAction(ACTION_PLAY_SCOPE)
            when (scope) {
                is LibraryScope.All -> intent.putExtra(EXTRA_SCOPE, SCOPE_ALL)
                is LibraryScope.Favorites -> intent.putExtra(EXTRA_SCOPE, SCOPE_FAVORITES)
                is LibraryScope.Category -> intent.putExtra(EXTRA_SCOPE, SCOPE_CATEGORY)
                    .putExtra(EXTRA_CATEGORY, scope.name)
            }
            mode?.let { intent.putExtra(EXTRA_MODE, it.name) }
            return intent.putExtra(EXTRA_INDEX, startIndex)
        }
    }
}
