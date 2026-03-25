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
            // Respect the user's notification preference
            val appPrefs = applicationContext.getSharedPreferences("raventag_app", Context.MODE_PRIVATE)
            if (!appPrefs.getBoolean("notifications_enabled", true)) return@withContext Result.success()

            val walletManager = WalletManager(applicationContext)
            // getAddress() requires Keystore; returns null if wallet is not set up or device locked
            val address = walletManager.getAddress() ?: return@withContext Result.success()

            val node = RavencoinPublicNode()

            // ── RVN balance check ──────────────────────────────────────────────
            val balance = node.getBalance(address)
            val newRvnSat = balance.confirmed + balance.unconfirmed
            val lastRvnSat = prefs.getLong("poll_rvn_sat", -1L)

            if (lastRvnSat >= 0 && newRvnSat > lastRvnSat) {
                val receivedRvn = (newRvnSat - lastRvnSat) / 1e8
                NotificationHelper.notify(
                    applicationContext,
                    id = 1001,
                    title = "RVN ricevuto",
                    body = "+${formatAmount(receivedRvn)} RVN"
                )
            }
            prefs.edit().putLong("poll_rvn_sat", newRvnSat).apply()

            // ── Asset balance check ────────────────────────────────────────────
            val assets = node.getAssetBalances(address)
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
