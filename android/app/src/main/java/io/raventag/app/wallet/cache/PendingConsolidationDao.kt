package io.raventag.app.wallet.cache

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase

/**
 * DAO for the pending_consolidations table (D-21).
 *
 * Tracks consolidation transactions that have been submitted but not yet confirmed.
 * Survives app kill and restart so the consolidation-retry logic (plan 30-05)
 * can pick up where it left off.
 */
object PendingConsolidationDao {
    private const val TABLE = "pending_consolidations"

    data class PendingConsolidation(
        val submittedTxid: String,
        val submittedAt: Long,
        val lastRetryAt: Long?,
        val retryCount: Int,
        val lastError: String?
    )

    fun init(context: Context) = WalletReliabilityDb.init(context)

    fun upsert(p: PendingConsolidation) {
        val db = WalletReliabilityDb.getDatabase()
        val cv = ContentValues().apply {
            put("submitted_txid", p.submittedTxid)
            put("submitted_at", p.submittedAt)
            if (p.lastRetryAt != null) put("last_retry_at", p.lastRetryAt) else putNull("last_retry_at")
            put("retry_count", p.retryCount)
            if (p.lastError != null) put("last_error", p.lastError) else putNull("last_error")
        }
        db.insertWithOnConflict(TABLE, null, cv, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun clear(submittedTxid: String) {
        WalletReliabilityDb.getDatabase().delete(TABLE, "submitted_txid = ?", arrayOf(submittedTxid))
    }

    fun all(): List<PendingConsolidation> {
        val db = WalletReliabilityDb.getDatabase()
        val out = mutableListOf<PendingConsolidation>()
        db.query(
            TABLE,
            arrayOf("submitted_txid", "submitted_at", "last_retry_at", "retry_count", "last_error"),
            null, null, null, null, "submitted_at ASC"
        ).use { c ->
            while (c.moveToNext()) {
                out += PendingConsolidation(
                    submittedTxid = c.getString(0),
                    submittedAt = c.getLong(1),
                    lastRetryAt = if (c.isNull(2)) null else c.getLong(2),
                    retryCount = c.getInt(3),
                    lastError = if (c.isNull(4)) null else c.getString(4)
                )
            }
        }
        return out
    }
}
