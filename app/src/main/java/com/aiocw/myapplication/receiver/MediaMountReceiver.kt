package com.aiocw.myapplication.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.SystemClock
import com.aiocw.myapplication.App
import com.aiocw.myapplication.playback.PlaybackService
import com.aiocw.myapplication.shared.model.LibraryScope
import com.aiocw.myapplication.shared.model.PlaybackMode
import com.aiocw.myapplication.ui.ImmersiveActivity

/**
 * U 盘/存储卡热插拔（见鉴权分析 §5.3）。
 * - MEDIA_MOUNTED：按设置自动重扫媒体库；开启"插入自动播放"时拉起播放；
 * - MEDIA_EJECT / MEDIA_UNMOUNTED：重扫（来源目录标记为不可用）。
 *
 * 注册方式：清单注册 + App.onCreate 运行时注册（双保险）。
 * Android 8+ 隐式广播限制下，部分 ROM 不再向清单接收器投递 MEDIA_MOUNTED，
 * 运行时注册可保证进程存活时必收；两路都触发时按 (path, 3s) 去重。
 */
class MediaMountReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (isDuplicate(intent.action, intent.data?.path)) return

        val app = context.applicationContext as? App ?: return
        val library = app.library

        when (intent.action) {
            Intent.ACTION_MEDIA_MOUNTED -> {
                Thread { library.refresh() }.start()
                if (library.settings.autoPlayOnMount) {
                    autoPlay(context)
                }
            }
            Intent.ACTION_MEDIA_EJECT, Intent.ACTION_MEDIA_UNMOUNTED -> {
                Thread { library.refresh() }.start()
            }
        }
    }

    /** 自动播放：优先拉起播放页；后台启动 Activity 被系统拦截时退化为服务起播。 */
    private fun autoPlay(context: Context) {
        runCatching {
            context.startActivity(
                Intent(context, ImmersiveActivity::class.java)
                    .putExtra(ImmersiveActivity.EXTRA_SCOPE_TYPE, PlaybackService.SCOPE_ALL)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }.onFailure {
            runCatching {
                // 自动播放（随机）：显式传入 LOOP_SHUFFLE，不依赖用户默认模式
                val play = PlaybackService.playIntent(
                    context, LibraryScope.All, mode = PlaybackMode.LOOP_SHUFFLE
                )
                context.startForegroundService(play)
            }
        }
    }

    companion object {
        private var lastKey: String? = null
        private var lastAtMs: Long = 0L

        /** 去重键必须含 action：EJECT 与 MOUNTED 同路径间隔极短时不能互相吞掉 */
        private fun isDuplicate(action: String?, path: String?): Boolean = synchronized(this) {
            val now = SystemClock.uptimeMillis()
            val key = "$action|$path"
            val dup = key == lastKey && now - lastAtMs < 3_000
            lastKey = key
            lastAtMs = now
            dup
        }

        /** 运行时注册用的 IntentFilter（与清单声明保持一致）。 */
        fun intentFilter(): IntentFilter = IntentFilter().apply {
            addAction(Intent.ACTION_MEDIA_MOUNTED)
            addAction(Intent.ACTION_MEDIA_EJECT)
            addAction(Intent.ACTION_MEDIA_UNMOUNTED)
            addDataScheme("file")
        }
    }
}
