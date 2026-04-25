package io.raventag.app.worker

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import io.raventag.app.MainActivity
import io.raventag.app.R
import java.util.Locale

/**
 * D-06, D-07, D-08: incoming RVN transaction notifications.
 *
 * Channel: `incoming_tx`, distinct from Phase 20 `transaction_progress` and the legacy
 * `raventag_wallet` channel. Tapping the notification opens MainActivity with
 * `action = VIEW_TRANSACTION` and `extra txid = <txid>`; MainActivity routes to
 * TransactionDetailsScreen.
 *
 * Notification ID strategy per UI-SPEC Implementation Notes:
 *   id = 2100 + (txid.hashCode() and 0x3FF)   -> mod-1024, distinct slots per txid.
 */
object IncomingTxNotificationHelper {

    const val CHANNEL_ID: String = "incoming_tx"
    const val ACTION_VIEW_TRANSACTION: String = "VIEW_TRANSACTION"
    const val EXTRA_TXID: String = "txid"

    private const val NOTIFICATION_ID_BASE: Int = 2100
    private const val NOTIFICATION_ID_MASK: Int = 0x3FF

    private fun isItalian(): Boolean =
        Locale.getDefault().language.startsWith("it", ignoreCase = true)

    fun createChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val name = if (isItalian()) "Transazioni in arrivo" else "Incoming transactions"
            val channel = NotificationChannel(
                CHANNEL_ID,
                name,
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "Notifications for received RVN and assets"
                setShowBadge(true)
            }
            context.getSystemService(NotificationManager::class.java)
                .createNotificationChannel(channel)
        }
    }

    fun showIncoming(
        context: Context,
        txid: String,
        rvnAmount: Double,
        confirmations: Int
    ) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) return
        }

        val amountStr = String.format(Locale.ROOT, "%.8f", rvnAmount)
        val italian = isItalian()

        val title: String
        val text: String
        when {
            confirmations <= 0 -> {
                title = if (italian) "Transazione in arrivo" else "Incoming transaction"
                text = if (italian) "+$amountStr RVN · In attesa"
                       else         "+$amountStr RVN · Pending"
            }
            confirmations < 6 -> {
                title = if (italian) "Transazione in arrivo" else "Incoming transaction"
                text = if (italian) "+$amountStr RVN · $confirmations/6 conferme"
                       else         "+$amountStr RVN · $confirmations/6 confirmations"
            }
            else -> {
                title = if (italian) "Ricevuto" else "Received"
                text = if (italian) "+$amountStr RVN confermati"
                       else         "+$amountStr RVN confirmed"
            }
        }

        val intent = Intent(context, MainActivity::class.java).apply {
            action = ACTION_VIEW_TRANSACTION
            putExtra(EXTRA_TXID, txid)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        val requestCode = txid.hashCode()
        val pendingIntent = PendingIntent.getActivity(
            context,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(title)
            .setContentText(text)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()

        val id = NOTIFICATION_ID_BASE + (txid.hashCode() and NOTIFICATION_ID_MASK)
        NotificationManagerCompat.from(context).notify(id, notification)
    }
}
