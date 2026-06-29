package com.pocketagent

import android.app.Application
import dagger.hilt.android.HiltAndroidApp

@HiltAndroidApp
class PocketAgentApp : Application() {

    override fun onCreate() {
        super.onCreate()
        createNotificationChannels()
    }

    private fun createNotificationChannels() {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            val channel = android.app.NotificationChannel(
                com.pocketagent.util.NotificationChannels.AGENT_ACTIVITY,
                getString(R.string.notif_channel_name),
                android.app.NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.notif_channel_desc)
                setShowBadge(false)
            }
            val nm = getSystemService(android.app.NotificationManager::class.java)
            nm.createNotificationChannel(channel)
        }
    }
}
