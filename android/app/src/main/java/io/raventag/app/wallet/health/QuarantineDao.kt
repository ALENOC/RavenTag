package io.raventag.app.wallet.health

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import io.raventag.app.wallet.cache.WalletReliabilityDb

/**
 * DAO for the quarantined_nodes table (D-11).
 *
 * Tracks ElectrumX nodes that have been temporarily quarantined due to
 * TOFU certificate mismatch, RPC failures, or timeouts. A quarantined
 * node is skipped for the duration of its quarantine period (default 1 hour).
 */
object QuarantineDao {
    private const val TABLE = "quarantined_nodes"
    const val REASON_TOFU_MISMATCH = "TOFU_MISMATCH"
    const val REASON_RPC_FAILED = "RPC_FAILED"
    const val REASON_TIMEOUT = "TIMEOUT"

    data class Quarantine(val host: String, val quarantinedUntil: Long, val reason: String)

    fun init(context: Context) = WalletReliabilityDb.init(context)

    fun quarantine(host: String, durationMillis: Long, reason: String) {
        val db = WalletReliabilityDb.getDatabase()
        val cv = ContentValues().apply {
            put("host", host)
            put("quarantined_until", System.currentTimeMillis() + durationMillis)
            put("reason", reason)
        }
        db.insertWithOnConflict(TABLE, null, cv, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun isQuarantined(host: String): Boolean {
        val db = WalletReliabilityDb.getDatabase()
        db.query(TABLE, arrayOf("quarantined_until"), "host = ?", arrayOf(host), null, null, null).use { c ->
            if (!c.moveToFirst()) return false
            val until = c.getLong(0)
            return until > System.currentTimeMillis()
        }
    }

    fun clear(host: String) {
        WalletReliabilityDb.getDatabase().delete(TABLE, "host = ?", arrayOf(host))
    }

    fun all(): List<Quarantine> {
        val db = WalletReliabilityDb.getDatabase()
        val out = mutableListOf<Quarantine>()
        db.query(TABLE, arrayOf("host", "quarantined_until", "reason"), null, null, null, null, null).use { c ->
            while (c.moveToNext()) out += Quarantine(c.getString(0), c.getLong(1), c.getString(2))
        }
        return out
    }
}
