package com.aiocw.myapplication

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import com.aiocw.myapplication.shared.Library

class App : Application() {

    lateinit var library: Library
        private set

    override fun onCreate() {
        super.onCreate()
        library = Library(this)
        createNotificationChannels()
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
