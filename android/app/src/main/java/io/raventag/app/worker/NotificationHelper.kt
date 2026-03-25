package io.raventag.app.worker

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import io.raventag.app.R

/**
 * Utility object for wallet incoming-transfer notifications.
 *
 * Usage:
 *   1. Call createChannel(context) once at app start (safe to call repeatedly).
 *   2. Call notify(context, id, title, body) from WalletPollingWorker when a transfer is detected.
 */
object NotificationHelper {

    private const val CHANNEL_ID = "raventag_wallet"

    /**
     * Create the notification channel for wallet events.
     * Must be called before any notification is posted (Android 8+).
     * Safe to call on every app start; the system ignores duplicate channel creation.
     */
    fun createChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Wallet",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "Incoming RVN and asset transfers"
            }
            context.getSystemService(NotificationManager::class.java)
                .createNotificationChannel(channel)
        }
    }

    /**
     * Post a notification. On Android 13+ silently skips if POST_NOTIFICATIONS is not granted.
     *
     * @param context Application context.
     * @param id      Unique notification ID (prevents duplicate notifications for different assets).
     * @param title   Notification title (e.g. "RVN ricevuto").
     * @param body    Notification body (e.g. "+1,5 RVN").
     */
    fun notify(context: Context, id: Int, title: String, body: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) return
        }

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(title)
            .setContentText(body)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .build()

        NotificationManagerCompat.from(context).notify(id, notification)
    }
}
