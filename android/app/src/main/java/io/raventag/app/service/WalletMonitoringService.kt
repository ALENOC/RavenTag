package io.raventag.app.service

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
import io.raventag.app.MainActivity
import io.raventag.app.R
import io.raventag.app.wallet.subscription.WalletSubscriptionCoordinator
import java.util.Locale

class WalletMonitoringService : Service() {

    companion object {
        const val CHANNEL_ID = "wallet_monitoring"
        const val NOTIFICATION_ID = 2201

        fun start(context: Context) {
            val intent = Intent(context, WalletMonitoringService::class.java)
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            } catch (_: Exception) {}
        }

        fun stop(context: Context) {
            val intent = Intent(context, WalletMonitoringService::class.java)
            try {
                context.stopService(intent)
            } catch (_: Exception) {}
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForegroundWithNotification()
        WalletSubscriptionCoordinator.onForegroundServiceStarted(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForegroundWithNotification()
        WalletSubscriptionCoordinator.onForegroundServiceStarted(this)
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        WalletSubscriptionCoordinator.onForegroundServiceStopped(this)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun isItalian(): Boolean =
        Locale.getDefault().language.startsWith("it", ignoreCase = true)

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val name = if (isItalian()) "Monitoraggio RavenTag" else "RavenTag Monitoring"
            val channel = NotificationChannel(
                CHANNEL_ID,
                name,
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Active background connection for instant transaction alerts"
                setShowBadge(false)
            }
            getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
        }
    }

    private fun startForegroundWithNotification() {
        val title = "RavenTag"
        val text = if (isItalian()) "Monitoraggio transazioni attivo" else "Transaction monitoring active"

        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(title)
            .setContentText(text)
            .setOngoing(true)
            .setContentIntent(pendingIntent)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }
}
