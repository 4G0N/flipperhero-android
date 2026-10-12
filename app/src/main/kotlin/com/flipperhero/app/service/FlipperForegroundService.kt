package com.flipperhero.app.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat

/**
 * Local foreground service that keeps the Flipper session alive and shows an ongoing notification
 * with a Stop button. This is the Android analogue of the iOS Live Activity — entirely local, with
 * NO Firebase/FCM. The Stop action broadcasts [ACTION_STOP] locally for the app to disconnect.
 */
class FlipperForegroundService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                sendBroadcast(Intent(ACTION_STOP).setPackage(packageName))
                ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }
            else -> {
                val text = intent?.getStringExtra(EXTRA_TEXT) ?: "Connected to Flipper"
                startAsForeground(text)
            }
        }
        return START_STICKY
    }

    private fun startAsForeground(text: String) {
        ensureChannel(this)
        val stopIntent = PendingIntent.getService(
            this, 1,
            Intent(this, FlipperForegroundService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("FlipperHero")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setOngoing(true)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop", stopIntent)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceCompat.startForeground(
                this, NOTIFICATION_ID, notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE,
            )
        } else {
            ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, 0)
        }
    }

    companion object {
        const val CHANNEL_ID = "flipper_session"
        const val NOTIFICATION_ID = 42
        const val ACTION_STOP = "com.flipperhero.app.STOP"
        const val EXTRA_TEXT = "text"

        fun ensureChannel(context: Context) {
            val manager = context.getSystemService(NotificationManager::class.java)
            if (manager.getNotificationChannel(CHANNEL_ID) == null) {
                manager.createNotificationChannel(
                    NotificationChannel(CHANNEL_ID, "Flipper session", NotificationManager.IMPORTANCE_LOW),
                )
            }
        }
    }
}
