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

    const val CHANNEL_ID: String = "incoming_tx_v2"
    const val ACTION_VIEW_TRANSACTION: String = "VIEW_TRANSACTION"
    const val EXTRA_TXID: String = "txid"
    private const val TAG: String = "IncomingTxNotif"

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
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Notifications for received RVN and assets"
                setShowBadge(true)
                enableVibration(true)
            }
            context.getSystemService(NotificationManager::class.java)
                ?.createNotificationChannel(channel)
            android.util.Log.i(TAG, "Notification channel $CHANNEL_ID created with IMPORTANCE_HIGH")
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
            ) {
                android.util.Log.w(TAG, "POST_NOTIFICATIONS permission not granted, skipping notification")
                return
            }
        }

        val amountStr = String.format(Locale.ROOT, "%.8f", rvnAmount).trimEnd('0').let {
            if (it.endsWith('.')) it + "0" else it
        }
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
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setDefaults(NotificationCompat.DEFAULT_ALL)
            .build()

        val id = NOTIFICATION_ID_BASE + (txid.hashCode() and NOTIFICATION_ID_MASK)
        android.util.Log.i(TAG, "Posting incoming RVN notification: id=$id txid=$txid title='$title' text='$text'")
        NotificationManagerCompat.from(context).notify(id, notification)
    }

    fun showIncomingAsset(
        context: Context,
        txid: String,
        assetName: String,
        assetAmount: Double,
        confirmations: Int
    ) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) {
                android.util.Log.w(TAG, "POST_NOTIFICATIONS permission not granted, skipping notification")
                return
            }
        }

        val amountStr = if (assetAmount % 1.0 == 0.0) {
            String.format(Locale.ROOT, "%.0f", assetAmount)
        } else {
            String.format(Locale.ROOT, "%.8f", assetAmount).trimEnd('0').trimEnd('.')
        }
        val italian = isItalian()

        val title: String
        val text: String
        when {
            confirmations <= 0 -> {
                title = if (italian) "Asset in arrivo" else "Incoming asset"
                text = if (italian) "+$amountStr $assetName · In attesa"
                       else         "+$amountStr $assetName · Pending"
            }
            confirmations < 6 -> {
                title = if (italian) "Asset in arrivo" else "Incoming asset"
                text = if (italian) "+$amountStr $assetName · $confirmations/6 conferme"
                       else         "+$amountStr $assetName · $confirmations/6 confirmations"
            }
            else -> {
                title = if (italian) "Asset ricevuto" else "Asset received"
                text = if (italian) "+$amountStr $assetName confermato"
                       else         "+$amountStr $assetName confirmed"
            }
        }

        val intent = Intent(context, MainActivity::class.java).apply {
            action = ACTION_VIEW_TRANSACTION
            putExtra(EXTRA_TXID, txid)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        val requestCode = (txid + assetName).hashCode()
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
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setDefaults(NotificationCompat.DEFAULT_ALL)
            .build()

        val id = NOTIFICATION_ID_BASE + ((txid + assetName).hashCode() and NOTIFICATION_ID_MASK)
        android.util.Log.i(TAG, "Posting incoming asset notification: id=$id txid=$txid title='$title' text='$text'")
        NotificationManagerCompat.from(context).notify(id, notification)
    }
}
