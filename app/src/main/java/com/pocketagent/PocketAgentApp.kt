package com.pocketagent

import android.app.Application
import com.pocketagent.storage.prefs.SettingsRepository
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltAndroidApp
class PocketAgentApp : Application() {

    @Inject lateinit var settingsRepository: SettingsRepository

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        // Load persisted settings ONCE at app startup.
        // This prevents race conditions where ViewModels call load() and
        // overwrite in-memory state that was just written by another ViewModel.
        appScope.launch {
            settingsRepository.load()
        }
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
