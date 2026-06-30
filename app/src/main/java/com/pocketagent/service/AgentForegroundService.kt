package com.pocketagent.service

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import com.pocketagent.MainActivity
import com.pocketagent.R
import com.pocketagent.util.NotificationChannels
import com.pocketagent.util.NotificationIds
import dagger.hilt.android.AndroidEntryPoint

/**
 * Foreground service that keeps the agent running when the app is backgrounded.
 * Shows a persistent notification while the agent is active.
 *
 * v2.0 changes:
 *  - foregroundServiceType=dataSync declared in manifest (Android 14+ requirement)
 *  - M14 FIX: notification text now comes from the intent's STATUS_TEXT extra
 *    (was hardcoded "Agent working…" — the intent extra was being ignored)
 */
@AndroidEntryPoint
class AgentForegroundService : Service() {

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // M14 FIX: use the status text from the intent (passed by ChatViewModel)
        val statusText = intent?.getStringExtra(EXTRA_STATUS_TEXT) ?: "Agent working…"
        startForeground(NotificationIds.AGENT_FOREGROUND, buildNotification(statusText))
        return START_NOT_STICKY
    }

    /** Update the notification text without restarting the service. */
    fun updateStatus(statusText: String) {
        val nm = getSystemService(android.app.NotificationManager::class.java)
        nm.notify(NotificationIds.AGENT_FOREGROUND, buildNotification(statusText))
    }

    override fun onBind(intent: Intent?): android.os.IBinder? = null

    private fun buildNotification(text: String): Notification {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pendingFlags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }
        val pendingIntent = PendingIntent.getActivity(this, 0, intent, pendingFlags)
        return NotificationCompat.Builder(this, NotificationChannels.AGENT_ACTIVITY)
            .setContentTitle(getString(R.string.notif_running_title))
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_splash_icon)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    companion object {
        const val EXTRA_STATUS_TEXT = "status_text"
    }
}
