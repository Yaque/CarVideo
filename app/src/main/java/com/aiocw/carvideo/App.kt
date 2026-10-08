package com.aiocw.carvideo

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import androidx.core.content.ContextCompat
import com.aiocw.carvideo.receiver.MediaMountReceiver
import com.aiocw.carvideo.shared.Library

class App : Application() {

    lateinit var library: Library
        private set

    override fun onCreate() {
        super.onCreate()
        library = Library(this)
        createNotificationChannels()
        registerMediaMountReceiver()
    }

    /**
     * 运行时注册 U 盘热插拔接收器（与清单注册双保险，见 MediaMountReceiver 注释）。
     * NOT_EXPORTED：只收系统广播，不收其他应用伪造的广播。
     */
    private fun registerMediaMountReceiver() {
        ContextCompat.registerReceiver(
            this,
            MediaMountReceiver(),
            MediaMountReceiver.intentFilter(),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NotificationManager::class.java) ?: return
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_PLAYBACK, "播放", NotificationManager.IMPORTANCE_LOW)
            )
        }
    }

    companion object {
        const val CHANNEL_PLAYBACK = "playback"
    }
}
