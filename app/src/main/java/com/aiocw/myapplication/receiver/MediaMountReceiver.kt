package com.aiocw.myapplication.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.aiocw.myapplication.App
import com.aiocw.myapplication.playback.PlaybackService
import com.aiocw.myapplication.shared.model.LibraryScope
import com.aiocw.myapplication.ui.PlayerActivity

/**
 * U 盘/存储卡热插拔（见鉴权分析 §5.3）。
 * - MEDIA_MOUNTED：按设置自动重扫媒体库；开启"插入自动播放"时拉起播放；
 * - MEDIA_EJECT / MEDIA_UNMOUNTED：重扫（来源目录标记为不可用）。
 * 注意：U 盘挂载后走文件路径访问即可，无需 UsbManager 设备权限。
 */
class MediaMountReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
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
                Intent(context, PlayerActivity::class.java)
                    .putExtra(PlayerActivity.EXTRA_SCOPE_TYPE, PlaybackService.SCOPE_ALL)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }.onFailure {
            runCatching {
                val play = PlaybackService.playIntent(context, LibraryScope.All)
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                    context.startForegroundService(play)
                } else {
                    context.startService(play)
                }
            }
        }
    }
}
