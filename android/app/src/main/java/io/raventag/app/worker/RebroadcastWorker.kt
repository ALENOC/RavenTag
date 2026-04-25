package io.raventag.app.worker

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import io.raventag.app.wallet.RavencoinPublicNode
import io.raventag.app.wallet.cache.PendingConsolidationDao
import io.raventag.app.wallet.cache.ReservedUtxoDao
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

/**
 * WorkManager worker that auto-rebroadcasts stuck transactions per D-25.
 *
 * Scheduled as a OneTimeWorkRequest with unique name "rebroadcast-<txid>".
 * Each run checks if the tx is confirmed (release reservations), otherwise
 * attempts a silent rebroadcast and schedules the next attempt on the
 * 30/60/120/240/480 min exponential ladder, capped at 5 attempts.
 *
 * D-27: consolidation ALWAYS broadcasts. The only constraint is
 * NetworkType.CONNECTED so we don't waste cycles offline.
 * No battery/power-save constraints that would defer broadcast.
 */
class RebroadcastWorker(
    ctx: Context,
    params: WorkerParameters
) : CoroutineWorker(ctx, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        // D-11/D-12: background workers may run before MainActivity has a
        // chance to init() the health monitor, so init defensively here.
        io.raventag.app.wallet.health.NodeHealthMonitor.init(applicationContext)

        val txid = inputData.getString(KEY_TXID) ?: return@withContext Result.failure()
        val rawHex = inputData.getString(KEY_RAW_HEX) ?: return@withContext Result.failure()
        val attempt = inputData.getInt(KEY_ATTEMPT, 0)

        if (attempt >= MAX_ATTEMPTS) {
            PendingConsolidationDao.upsert(
                PendingConsolidationDao.PendingConsolidation(
                    submittedTxid = txid,
                    submittedAt = System.currentTimeMillis(),
                    lastRetryAt = System.currentTimeMillis(),
                    retryCount = attempt,
                    lastError = "rebroadcast cap reached"
                )
            )
            return@withContext Result.success()
        }

        val node = RavencoinPublicNode(applicationContext)

        // Confirmation check via blockchain.transaction.get.
        // If the tx is confirmed, release its reserved UTXOs and clear the
        // pending consolidation row. No reschedule needed.
        val confirmed = try {
            val result = node.callElectrumRawOrNull(
                "blockchain.transaction.get", listOf(txid, true)
            )
            val confirms = result?.asJsonObject
                ?.get("confirmations")
                ?.takeIf { !it.isJsonNull }
                ?.asInt ?: 0
            confirms > 0
        } catch (_: Exception) { false }

        if (confirmed) {
            ReservedUtxoDao.releaseFor(txid)
            PendingConsolidationDao.clear(txid)
            return@withContext Result.success()
        }

        // Rebroadcast silently per D-25. Double-spend rejection by ElectrumX
        // is expected and harmless: it means the tx is already in mempool.
        try { node.broadcast(rawHex) } catch (_: Exception) { /* silent */ }

        // Schedule next attempt on the D-25 ladder
        val nextDelayMinutes = DELAY_LADDER_MINUTES.getOrElse(attempt) { 480L }
        schedule(
            context = applicationContext,
            txid = txid,
            rawHex = rawHex,
            attempt = attempt + 1,
            initialDelayMinutes = nextDelayMinutes
        )
        PendingConsolidationDao.upsert(
            PendingConsolidationDao.PendingConsolidation(
                submittedTxid = txid,
                submittedAt = System.currentTimeMillis(),
                lastRetryAt = System.currentTimeMillis(),
                retryCount = attempt + 1,
                lastError = null
            )
        )
        Result.success()
    }

    companion object {
        const val KEY_TXID = "txid"
        const val KEY_RAW_HEX = "raw_hex"
        const val KEY_ATTEMPT = "attempt"
        const val MAX_ATTEMPTS = 5
        // D-25 ladder: delays AFTER attempt N (attempt 0 = first scheduled 30 min later)
        val DELAY_LADDER_MINUTES: List<Long> = listOf(30L, 60L, 120L, 240L, 480L)

        /** Public entry used by WalletManager after a successful broadcast. */
        fun schedule(
            context: Context,
            txid: String,
            rawHex: String,
            attempt: Int,
            initialDelayMinutes: Long
        ) {
            val req = OneTimeWorkRequestBuilder<RebroadcastWorker>()
                .setInitialDelay(initialDelayMinutes, TimeUnit.MINUTES)
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build()
                )
                .setInputData(
                    workDataOf(
                        KEY_TXID to txid,
                        KEY_RAW_HEX to rawHex,
                        KEY_ATTEMPT to attempt
                    )
                )
                .build()
            WorkManager.getInstance(context)
                .enqueueUniqueWork("rebroadcast-$txid", ExistingWorkPolicy.REPLACE, req)
        }
    }
}
