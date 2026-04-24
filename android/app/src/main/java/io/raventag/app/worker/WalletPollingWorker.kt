package io.raventag.app.worker

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import io.raventag.app.wallet.RavencoinPublicNode
import io.raventag.app.wallet.WalletManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * WorkManager worker that polls the ElectrumX network for incoming RVN and asset transfers.
 *
 * Runs every 15 minutes (Android minimum for PeriodicWork). On each execution:
 *   1. Derives the wallet address from WalletManager (requires device to be unlocked).
 *   2. Fetches confirmed + unconfirmed RVN balance via ElectrumX.
 *   3. Fetches all asset balances via ElectrumX.
 *   4. Compares against values stored in SharedPreferences from the last poll.
 *   5. Posts a notification for each RVN or asset increase detected.
 *   6. Updates stored values for the next comparison.
 *
 * On the very first run (no stored baseline), only stores the current balances without
 * notifying, to avoid spurious notifications on app install or first launch.
 *
 * Resilience:
 *   - Wallet not set up or Keystore locked: Result.success() (skip gracefully, no retry needed).
 *   - Network/IO error: Result.retry() so WorkManager retries with backoff before the next period.
 *   - Any other unexpected error: Result.success() to avoid polluting the WorkManager failure log.
 */
class WalletPollingWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    private val prefs get() = applicationContext
        .getSharedPreferences("wallet_poll", Context.MODE_PRIVATE)

    private val gson = Gson()

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        try {
            // D-11/D-12: background workers may run before MainActivity has a
            // chance to init() the health monitor, so init defensively here.
            io.raventag.app.wallet.health.NodeHealthMonitor.init(applicationContext)

            // Respect the user's notification preference
            val appPrefs = applicationContext.getSharedPreferences("raventag_app", Context.MODE_PRIVATE)
            if (!appPrefs.getBoolean("notifications_enabled", true)) return@withContext Result.success()

            val walletManager = WalletManager(applicationContext)
            // getCurrentAddress() requires Keystore; returns null if wallet is not set up or device locked
            walletManager.getCurrentAddress() ?: return@withContext Result.success()
            val currentIndex = walletManager.getCurrentAddressIndex()

            val node = RavencoinPublicNode(applicationContext)

            // Derive all addresses with a single Keystore decrypt
            val addresses = walletManager.getAddressBatch(0, 0..currentIndex).values.toList()

            // ── RVN balance check (single batch TLS call for all addresses) ────
            val newRvnSat = (node.getTotalBalance(addresses) * 1e8).toLong()
            val lastRvnSat = prefs.getLong("poll_rvn_sat", -1L)

            var incomingDetected = false
            if (lastRvnSat >= 0 && newRvnSat > lastRvnSat) {
                incomingDetected = true
                val receivedRvn = (newRvnSat - lastRvnSat) / 1e8
                NotificationHelper.notify(
                    applicationContext,
                    id = 1001,
                    title = "RVN ricevuto",
                    body = "+${formatAmount(receivedRvn)} RVN"
                )
            }
            prefs.edit().putLong("poll_rvn_sat", newRvnSat).apply()

            // ── Asset balance check (single batch TLS call for all addresses) ──
            val assetTotals = node.getTotalAssetBalances(addresses)
            val assets = assetTotals.map { (name, amount) ->
                io.raventag.app.wallet.ElectrumAssetBalance(name, amount)
            }
            val lastAssetsType = object : TypeToken<Map<String, Long>>() {}.type
            val lastAssets: Map<String, Long> = gson.fromJson(
                prefs.getString("poll_assets", "{}"), lastAssetsType
            ) ?: emptyMap()

            var notifId = 1002
            val newAssets = mutableMapOf<String, Long>()
            for (asset in assets) {
                val newSat = (asset.amount * 1e8).toLong()
                newAssets[asset.name] = newSat
                val lastSat = lastAssets[asset.name] ?: -1L
                if (lastSat >= 0 && newSat > lastSat) {
                    incomingDetected = true
                    val diff = (newSat - lastSat) / 1e8
                    NotificationHelper.notify(
                        applicationContext,
                        id = notifId++,
                        title = "Asset ricevuto",
                        body = "+${formatAmount(diff)} ${asset.name}"
                    )
                }
            }
            prefs.edit().putString("poll_assets", gson.toJson(newAssets)).apply()

            // ── Auto-sweep: if any incoming transfer was detected, consolidate funds
            //    from HAS_OUTGOING addresses to the current quantum-safe address.
            //    Addresses that only received funds (RECEIVE_ONLY) are never touched.
            if (incomingDetected) {
                try {
                    walletManager.sweepOldAddresses()
                } catch (_: Exception) {
                    // Sweep failure is non-fatal: funds stay on the old address until
                    // the next polling cycle or the user opens the app.
                }
            }

        } catch (_: java.io.IOException) {
            // Network error: retry with backoff so we don't silently miss a run
            return@withContext Result.retry()
        } catch (_: Exception) {
            // Keystore unavailable or unexpected error: skip this run gracefully
        }
        Result.success()
    }

    /**
     * Format a RVN/asset amount with comma as decimal separator and no trailing zeros.
     * Integers are shown without any decimal part.
     * Examples: 1.5 -> "1,5", 10.0 -> "10", 0.00050000 -> "0,0005"
     */
    private fun formatAmount(amount: Double): String {
        val s = String.format(java.util.Locale.US, "%.8f", amount)
        val dot = s.indexOf('.')
        val intPart = s.substring(0, dot)
        val decPart = s.substring(dot + 1).trimEnd('0')
        return if (decPart.isEmpty()) intPart else "$intPart,$decPart"
    }
}
