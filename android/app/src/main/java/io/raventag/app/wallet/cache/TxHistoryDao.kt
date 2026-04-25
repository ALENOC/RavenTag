package io.raventag.app.wallet.cache

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase

/**
 * DAO for the tx_history table (D-23).
 *
 * Stores paginated transaction history with three-value breakdown columns
 * (sent/cycled/fee) for the WalletScreen transaction list. Rows are upserted
 * on each blockchain refresh and queried with LIMIT/OFFSET for pagination.
 */
object TxHistoryDao {
    private const val TABLE = "tx_history"

    data class TxHistoryRow(
        val txid: String,
        val height: Int,
        val confirms: Int,
        val amountSat: Long,
        val sentSat: Long,
        val cycledSat: Long,
        val feeSat: Long,
        val isIncoming: Boolean,
        val isSelf: Boolean,
        val timestamp: Long,
        val cachedAt: Long
    )

    fun init(context: Context) = WalletReliabilityDb.init(context)

    /** Wipe all cached tx history. Used by deleteWallet. */
    fun clearAll() {
        WalletReliabilityDb.getDatabase().execSQL("DELETE FROM $TABLE")
    }

    fun upsert(rows: List<TxHistoryRow>) {
        if (rows.isEmpty()) return
        val db = WalletReliabilityDb.getDatabase()
        db.beginTransaction()
        try {
            for (r in rows) {
                val cv = ContentValues().apply {
                    put("txid", r.txid)
                    put("height", r.height)
                    put("confirms", r.confirms)
                    put("amount_sat", r.amountSat)
                    put("sent_sat", r.sentSat)
                    put("cycled_sat", r.cycledSat)
                    put("fee_sat", r.feeSat)
                    put("is_incoming", if (r.isIncoming) 1 else 0)
                    put("is_self", if (r.isSelf) 1 else 0)
                    put("timestamp", r.timestamp)
                    put("cached_at", r.cachedAt)
                }
                db.insertWithOnConflict(TABLE, null, cv, SQLiteDatabase.CONFLICT_REPLACE)
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    /** Paged list: mempool rows (height=0) sort last, confirmed rows by height DESC. */
    fun page(limit: Int, offset: Int): List<TxHistoryRow> {
        val db = WalletReliabilityDb.getDatabase()
        val out = mutableListOf<TxHistoryRow>()
        val orderBy = "CASE WHEN height = 0 THEN 1 ELSE 0 END DESC, height DESC, timestamp DESC"
        db.query(
            TABLE,
            arrayOf(
                "txid", "height", "confirms", "amount_sat", "sent_sat", "cycled_sat",
                "fee_sat", "is_incoming", "is_self", "timestamp", "cached_at"
            ),
            null, null, null, null, orderBy, "$limit OFFSET $offset"
        ).use { c ->
            while (c.moveToNext()) {
                out += TxHistoryRow(
                    txid = c.getString(0),
                    height = c.getInt(1),
                    confirms = c.getInt(2),
                    amountSat = c.getLong(3),
                    sentSat = c.getLong(4),
                    cycledSat = c.getLong(5),
                    feeSat = c.getLong(6),
                    isIncoming = c.getInt(7) == 1,
                    isSelf = c.getInt(8) == 1,
                    timestamp = c.getLong(9),
                    cachedAt = c.getLong(10)
                )
            }
        }
        return out
    }

    fun findByTxid(txid: String): TxHistoryRow? {
        val db = WalletReliabilityDb.getDatabase()
        db.query(
            TABLE,
            arrayOf(
                "txid", "height", "confirms", "amount_sat", "sent_sat", "cycled_sat",
                "fee_sat", "is_incoming", "is_self", "timestamp", "cached_at"
            ),
            "txid = ?", arrayOf(txid), null, null, null
        ).use { c ->
            if (!c.moveToFirst()) return null
            return TxHistoryRow(
                txid = c.getString(0),
                height = c.getInt(1),
                confirms = c.getInt(2),
                amountSat = c.getLong(3),
                sentSat = c.getLong(4),
                cycledSat = c.getLong(5),
                feeSat = c.getLong(6),
                isIncoming = c.getInt(7) == 1,
                isSelf = c.getInt(8) == 1,
                timestamp = c.getLong(9),
                cachedAt = c.getLong(10)
            )
        }
    }

    /**
     * D-23 paged tx history with argument order `(offset, limit)` matching
     * [io.raventag.app.wallet.RavencoinPublicNode.getHistoryPaged]. Default
     * page size 20 per UI-SPEC Load more.
     */
    fun getPage(offset: Int, limit: Int = 20): List<TxHistoryRow> =
        page(limit = limit, offset = offset)

    fun count(): Int {
        val db = WalletReliabilityDb.getDatabase()
        db.rawQuery("SELECT COUNT(*) FROM $TABLE", null).use { c ->
            return if (c.moveToFirst()) c.getInt(0) else 0
        }
    }
}
