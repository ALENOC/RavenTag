package io.raventag.app.wallet.cache

import io.raventag.app.wallet.Utxo
import io.raventag.app.wallet.AssetUtxo
import android.content.Context

// Wave 0 stub. Plan 30-02 will implement real DAO.
object WalletCacheDao {
    fun init(context: Context): Unit = TODO("30-02")
    fun writeState(utxos: List<Utxo>, assetUtxos: Map<String, List<AssetUtxo>>, blockHeight: Int): Unit = TODO("30-02")
    fun readState(): CachedWalletState? = TODO("30-02")
    fun getLastRefreshedAt(): Long = TODO("30-02")
    fun computeSpendableBalanceSat(utxos: List<Utxo>, reservedSat: Long): Long {
        val sum = utxos.sumOf { it.satoshis }
        return maxOf(0L, sum - reservedSat)
    }

    data class CachedWalletState(
        val walletId: String,
        val balanceSat: Long,
        val utxos: List<Utxo>,
        val assetUtxos: Map<String, List<AssetUtxo>>,
        val blockHeight: Int,
        val lastRefreshedAt: Long
    )
}
