package com.example

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import android.widget.Toast
import androidx.core.app.NotificationCompat

/**
 * LauncherService is the single persistent foreground service designed to manage, persist, and orchestrate
 * all modular features across development phases. In Phase 1, it runs empty but acts as the core service anchor.
 * This guarantees survival from OS background processing aggressive cycles using an active persistent notifications channel.
 */
class LauncherService : Service() {

    companion object {
        private const val TAG = "LauncherService"
        private const val CHANNEL_ID = "launcher_service_channel"
        private const val NOTIFICATION_ID = 1001

        const val ACTION_START = "com.example.action.START"
        const val ACTION_PAUSE_SIDEBAR = "com.example.action.PAUSE_SIDEBAR"
        const val ACTION_PAUSE_SPEED = "com.example.action.PAUSE_SPEED"
    }

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "LauncherService Created")
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "LauncherService StartCommand action: ${intent?.action}")

        when (intent?.action) {
            ACTION_PAUSE_SIDEBAR -> {
                Toast.makeText(this, "Sidebar Paused (Placeholder)", Toast.LENGTH_SHORT).show()
                Log.d(TAG, "Notification action PAUSE_SIDEBAR clicked")
            }
            ACTION_PAUSE_SPEED -> {
                Toast.makeText(this, "Speed Indicator Paused (Placeholder)", Toast.LENGTH_SHORT).show()
                Log.d(TAG, "Notification action PAUSE_SPEED clicked")
            }
        }

        // Show a persistent notification to meet Android platform requirement for foreground service
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIFICATION_ID,
                buildNotification(),
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else {
            startForeground(NOTIFICATION_ID, buildNotification())
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun buildNotification(): Notification {
        // Build settings activity pending intent
        val settingsIntent = Intent(this, SettingsActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        val settingsPendingIntent = PendingIntent.getActivity(
            this,
            0,
            settingsIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        // Build pause sidebar intent
        val pauseSidebarIntent = Intent(this, LauncherService::class.java).apply {
            action = ACTION_PAUSE_SIDEBAR
        }
        val pauseSidebarPendingIntent = PendingIntent.getService(
            this,
            1,
            pauseSidebarIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        // Build pause speed indicator intent
        val pauseSpeedIntent = Intent(this, LauncherService::class.java).apply {
            action = ACTION_PAUSE_SPEED
        }
        val pauseSpeedPendingIntent = PendingIntent.getService(
            this,
            2,
            pauseSpeedIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Launcher Active")
            .setContentText("Foreground service hosting modular custom elements")
            .setSmallIcon(android.R.drawable.sym_def_app_icon)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setContentIntent(settingsPendingIntent)
            .addAction(
                android.R.drawable.ic_menu_preferences,
                "Settings",
                settingsPendingIntent
            )
            .addAction(
                android.R.drawable.ic_media_pause,
                "Pause Sidebar",
                pauseSidebarPendingIntent
            )
            .addAction(
                android.R.drawable.ic_media_pause,
                "Pause Speed",
                pauseSpeedPendingIntent
            )

        return builder.build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Launcher Service Channel",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Coordinates execution vectors for launcher overlays and background telemetry"
            }
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "LauncherService Destroyed")
    }
}
