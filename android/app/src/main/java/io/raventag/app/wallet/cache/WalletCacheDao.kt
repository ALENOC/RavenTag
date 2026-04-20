package io.raventag.app.wallet.cache

import io.raventag.app.wallet.AssetUtxo
import io.raventag.app.wallet.Utxo

/**
 * Wave 0 stub. Plan 30-02 replaces this with real SQLite DAO implementation.
 */
object WalletCacheDao {

    data class CachedWalletState(
        val walletId: String,
        val balanceSat: Long,
        val utxos: List<Utxo>,
        val assetUtxos: Map<String, List<AssetUtxo>>,
        val blockHeight: Int,
        val lastRefreshedAt: Long
    )

    fun init(context: android.content.Context) {
        TODO("30-02")
    }

    fun writeState(utxos: List<Utxo>, assetUtxos: Map<String, List<AssetUtxo>>, blockHeight: Int) {
        TODO("30-02")
    }

    fun readState(): CachedWalletState? {
        TODO("30-02")
    }

    fun getLastRefreshedAt(): Long {
        TODO("30-02")
    }

    /**
     * Returns sum(utxo.satoshis) - reservedSat, coerced >= 0.
     * Pure function: does NOT require SQLite or Context.
     * Signature MUST be honored by plan 30-02.
     */
    fun computeSpendableBalanceSat(utxos: List<Utxo>, reservedSat: Long): Long {
        TODO("30-02")
    }
}
