package io.raventag.app.worker

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import io.raventag.app.R
import io.raventag.app.MainActivity

/**
 * Helper object for transaction progress notifications during send operations.
 *
 * Usage:
 *   1. Call createChannel(context) once at app start (safe to call repeatedly).
 *   2. Call showBroadcasting(context) when transaction starts.
 *   3. Call showConfirming(context, confirmations, total) when waiting for blocks.
 *   4. Call showCompleted(context, txid) when transaction is confirmed.
 *   5. Call showFailed(context, error) on failure.
 *
 * Per D-03, D-04, D-05, D-06 from CONTEXT.md:
 *   - Users can dismiss app while transaction broadcasts
 *   - Tapping notification opens transaction details screen (full implementation, not placeholder)
 *   - Multiple stage notifications update the same notification slot (ID 2001)
 *   - Failed notification includes Retry action
 */
object TransactionNotificationHelper {

    private const val CHANNEL_ID = "transaction_progress"
    private const val NOTIFICATION_ID = 2001
    private const val ACTION_VIEW_TRANSACTION = "VIEW_TRANSACTION"
    private const val EXTRA_TXID = "txid"
    private const val EXTRA_ERROR = "error"

    /**
     * Create notification channel for transaction progress.
     * Must be called before any notification is posted (Android 8+).
     */
    fun createChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Transaction Progress",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Blockchain transaction broadcast and confirmation progress"
                setShowBadge(false)
                enableVibration(false)
                setSound(null, null)
            }
            context.getSystemService(NotificationManager::class.java)
                .createNotificationChannel(channel)
        }
    }

    /**
     * Show broadcasting notification (ongoing, not cancellable).
     */
    fun showBroadcasting(context: Context) {
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("Broadcasting...")
            .setContentText("Transaction is being broadcast to network")
            .setOngoing(true)
            .setAutoCancel(false)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

        NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification)
    }

    /**
     * Show confirming notification (ongoing, not cancellable).
     */
    fun showConfirming(context: Context, confirmations: Int = 1, total: Int = 1) {
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("Confirming ($confirmations/$total)")
            .setContentText("Waiting for block confirmation")
            .setOngoing(true)
            .setAutoCancel(false)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

        NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification)
    }

    /**
     * Show completed notification (tappable, auto-cancellable).
     * Tapping opens MainActivity with VIEW_TRANSACTION action and txid extra (per D-04).
     */
    fun showCompleted(context: Context, txid: String) {
        val intent = Intent(context, MainActivity::class.java).apply {
            action = ACTION_VIEW_TRANSACTION
            putExtra(EXTRA_TXID, txid)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }

        val pendingIntent = PendingIntent.getActivity(
            context,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("Completed")
            .setContentText("Transaction confirmed on blockchain: ${txid.take(20)}...")
            .setOngoing(false)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()

        NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification)
    }

    /**
     * Show failed notification (tappable, auto-cancellable with Retry action).
     * Retry action sends intent to MainActivity with RETRY_TRANSACTION action.
     */
    fun showFailed(context: Context, error: String) {
        val retryIntent = Intent(context, MainActivity::class.java).apply {
            action = "RETRY_TRANSACTION"
            putExtra(EXTRA_ERROR, error)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }

        val retryPendingIntent = PendingIntent.getActivity(
            context,
            0,
            retryIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("Failed")
            .setContentText(error)
            .setOngoing(false)
            .setAutoCancel(true)
            .addAction(
                R.drawable.ic_refresh,
                "Retry",
                retryPendingIntent
            )
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()

        NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification)
    }

    /**
     * Clear the transaction notification (call when user manually cancels).
     */
    fun clear(context: Context) {
        NotificationManagerCompat.from(context).cancel(NOTIFICATION_ID)
    }

    /**
     * Transaction lifecycle stages for type-safe notification updates.
     */
    enum class TransactionStage {
        BROADCASTING,
        CONFIRMING,
        COMPLETED,
        FAILED
    }

    // Public constants for use by MainActivity intent handler
    const val ACTION_VIEW_TRANSACTION_EXT = ACTION_VIEW_TRANSACTION
    const val EXTRA_TXID_EXT = EXTRA_TXID
    const val EXTRA_ERROR_EXT = EXTRA_ERROR
}
