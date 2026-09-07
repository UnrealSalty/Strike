package com.strike.online

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import com.strike.MainActivity
import com.strike.R
import com.strike.StrikeApp

private const val CHANNEL = "online"
private const val NOTIFICATION = 2

class OnlineService : Service() {
    override fun onCreate() {
        super.onCreate()
        val notifications = getSystemService(NotificationManager::class.java)
        notifications.createNotificationChannel(NotificationChannel(
            CHANNEL, "Remote access", NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        startForeground(NOTIFICATION, Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_online)
            .setContentTitle("Strike remote access")
            .setContentText("Open Strike to manage the connection")
            .setContentIntent(open)
            .setOngoing(true)
            .build())
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val online = (application as StrikeApp).online
        if (!online.enabled) {
            stopSelf()
            return START_NOT_STICKY
        }
        online.start()
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        fun keepAlive(context: Context, enabled: Boolean) {
            val service = Intent(context, OnlineService::class.java)
            if (enabled) context.startForegroundService(service) else context.stopService(service)
        }
    }
}
