package io.raventag.app.wallet.cache

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteConstraintException

/**
 * DAO for the reserved_utxos table (D-20).
 *
 * Tracks UTXOs that have been reserved as inputs to a submitted (but unconfirmed)
 * transaction. The reserved sum is subtracted from the displayed balance to prevent
 * double-spending. Rows are released once the submitted tx confirms, or pruned
 * automatically if older than 48 hours (plan 30-05 will add startup prune).
 */
object ReservedUtxoDao {
    private const val TABLE = "reserved_utxos"

    data class ReservedUtxo(
        val txidIn: String,
        val vout: Int,
        val valueSat: Long,
        val submittedTxid: String,
        val submittedAt: Long
    )

    fun init(context: Context) = WalletReliabilityDb.init(context)

    /** Wipe all reserved UTXO rows. Used by deleteWallet. */
    fun clearAll() {
        WalletReliabilityDb.getDatabase().execSQL("DELETE FROM $TABLE")
    }

    /**
     * Atomically reserve every outpoint. Existing reservations are never replaced.
     * Returns false for a conflicting outpoint or any database failure; callers must
     * fail closed and MUST NOT sign when this returns false.
     */
    fun tryReserve(entries: List<ReservedUtxo>): Boolean {
        if (entries.isEmpty()) return false
        val db = WalletReliabilityDb.getDatabase()
        db.beginTransaction()
        return try {
            for (e in entries) {
                val cv = ContentValues().apply {
                    put("txid_in", e.txidIn)
                    put("vout", e.vout)
                    put("value_sat", e.valueSat)
                    put("submitted_txid", e.submittedTxid)
                    put("submitted_at", e.submittedAt)
                }
                val rowId = db.insertWithOnConflict(TABLE, null, cv, SQLiteDatabase.CONFLICT_ABORT)
                if (rowId == -1L) throw SQLiteConstraintException("outpoint already reserved")
            }
            db.setTransactionSuccessful()
            true
        } catch (_: Exception) {
            false
        } finally {
            db.endTransaction()
        }
    }

    /** Backward-compatible strict wrapper used by older call sites/tests. */
    fun reserve(entries: List<ReservedUtxo>) {
        check(tryReserve(entries)) { "UTXO reservation conflict or database failure" }
    }

    /** Associate a pre-sign intent reservation with the locally computed txid. */
    fun retagReservation(fromId: String, submittedTxid: String): Boolean {
        val db = WalletReliabilityDb.getDatabase()
        val cv = ContentValues().apply { put("submitted_txid", submittedTxid) }
        return db.update(TABLE, cv, "submitted_txid = ?", arrayOf(fromId)) > 0
    }

    fun releaseFor(submittedTxid: String) {
        val db = WalletReliabilityDb.getDatabase()
        db.delete(TABLE, "submitted_txid = ?", arrayOf(submittedTxid))
    }

    fun sumReservedSat(): Long {
        val db = WalletReliabilityDb.getDatabase()
        db.rawQuery("SELECT COALESCE(SUM(value_sat), 0) FROM $TABLE", null).use { c ->
            return if (c.moveToFirst()) c.getLong(0) else 0L
        }
    }

    fun pruneOlderThan(thresholdMillis: Long) {
        val db = WalletReliabilityDb.getDatabase()
        db.delete(TABLE, "submitted_at < ?", arrayOf(thresholdMillis.toString()))
    }

    fun all(): List<ReservedUtxo> {
        val db = WalletReliabilityDb.getDatabase()
        val out = mutableListOf<ReservedUtxo>()
        db.query(
            TABLE,
            arrayOf("txid_in", "vout", "value_sat", "submitted_txid", "submitted_at"),
            null, null, null, null, "submitted_at DESC"
        ).use { c ->
            while (c.moveToNext()) {
                out += ReservedUtxo(
                    txidIn = c.getString(0),
                    vout = c.getInt(1),
                    valueSat = c.getLong(2),
                    submittedTxid = c.getString(3),
                    submittedAt = c.getLong(4)
                )
            }
        }
        return out
    }
}
