package io.raventag.app.wallet.cache

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import io.raventag.app.wallet.AssetUtxo
import io.raventag.app.wallet.Utxo

/**
 * DAO for the wallet_state_cache table (D-04).
 *
 * Stores a single row keyed by wallet_id="default" containing the last-known
 * balance, serialized UTXO list, serialized asset-UTXO map, and block height.
 * Opening WalletScreen reads this row instantly from SQLite.
 *
 * Also exports the pure helper [computeSpendableBalanceSat] which subtracts
 * reserved-UTXO value from the confirmed total, clamped to zero.
 */
object WalletCacheDao {
    private const val TABLE = "wallet_state_cache"
    private const val WALLET_ID = "default"
    private val gson = Gson()

    data class CachedWalletState(
        val walletId: String,
        val balanceSat: Long,
        val utxos: List<Utxo>,
        val assetUtxos: Map<String, List<AssetUtxo>>,
        val blockHeight: Int,
        val lastRefreshedAt: Long
    )

    fun init(context: Context) = WalletReliabilityDb.init(context)

    fun writeState(
        utxos: List<Utxo>,
        assetUtxos: Map<String, List<AssetUtxo>>,
        blockHeight: Int
    ) {
        val db = WalletReliabilityDb.getDatabase()
        val reservedSat = ReservedUtxoDao.sumReservedSat()
        val displaySat = computeSpendableBalanceSat(utxos, reservedSat)
        val cv = ContentValues().apply {
            put("wallet_id", WALLET_ID)
            put("balance_sat", displaySat)
            put("utxos_json", gson.toJson(utxos))
            put("asset_utxos_json", gson.toJson(assetUtxos))
            put("block_height", blockHeight)
            put("last_refreshed_at", System.currentTimeMillis())
        }
        db.insertWithOnConflict(TABLE, null, cv, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun readState(): CachedWalletState? {
        val db = WalletReliabilityDb.getDatabase()
        db.query(
            TABLE, arrayOf(
                "wallet_id", "balance_sat", "utxos_json", "asset_utxos_json",
                "block_height", "last_refreshed_at"
            ), "wallet_id = ?", arrayOf(WALLET_ID), null, null, null
        ).use { c ->
            if (!c.moveToFirst()) return null
            val utxosType = object : TypeToken<List<Utxo>>() {}.type
            val assetsType = object : TypeToken<Map<String, List<AssetUtxo>>>() {}.type
            return CachedWalletState(
                walletId = c.getString(0),
                balanceSat = c.getLong(1),
                utxos = gson.fromJson<List<Utxo>>(c.getString(2), utxosType) ?: emptyList(),
                assetUtxos = gson.fromJson<Map<String, List<AssetUtxo>>>(c.getString(3), assetsType)
                    ?: emptyMap(),
                blockHeight = c.getInt(4),
                lastRefreshedAt = c.getLong(5)
            )
        }
    }

    fun getLastRefreshedAt(): Long = readState()?.lastRefreshedAt ?: 0L

    /**
     * Pure helper: confirmed balance minus reserved-UTXO sum, clamped to zero.
     * Unit-testable without Android context.
     */
    @JvmStatic
    fun computeSpendableBalanceSat(utxos: List<Utxo>, reservedSat: Long): Long {
        val confirmedSat = utxos.sumOf { it.satoshis }
        return (confirmedSat - reservedSat).coerceAtLeast(0L)
    }
}
