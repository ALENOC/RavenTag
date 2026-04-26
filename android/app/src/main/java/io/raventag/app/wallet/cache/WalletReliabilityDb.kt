package io.raventag.app.wallet.cache

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/**
 * Single SQLiteOpenHelper owning the wallet_reliability.db database.
 *
 * Hosts five tables used by Phase 30 DAOs:
 *   - wallet_state_cache   (WalletCacheDao)
 *   - tx_history           (TxHistoryDao)
 *   - reserved_utxos       (ReservedUtxoDao)
 *   - pending_consolidations (PendingConsolidationDao)
 *   - quarantined_nodes    (QuarantineDao)
 *
 * PRAGMA synchronous=FULL + journal_mode=WAL guarantee durability of reserved-UTXO
 * rows even if the app crashes mid-write (Pitfall 6 from RESEARCH.md).
 */
internal object WalletReliabilityDb {
    private const val DB_NAME = "wallet_reliability.db"
    private const val DB_VERSION = 2

    private class Helper(context: Context) : SQLiteOpenHelper(context, DB_NAME, null, DB_VERSION) {
        init {
            setWriteAheadLoggingEnabled(true)
        }

        override fun onConfigure(db: SQLiteDatabase) {
            db.execSQL("PRAGMA synchronous=FULL;")
            db.execSQL("PRAGMA foreign_keys=OFF;")
        }

        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL("""
                CREATE TABLE IF NOT EXISTS wallet_state_cache (
                    wallet_id         TEXT PRIMARY KEY,
                    balance_sat       INTEGER NOT NULL,
                    utxos_json        TEXT NOT NULL,
                    asset_utxos_json  TEXT NOT NULL,
                    block_height      INTEGER NOT NULL,
                    last_refreshed_at INTEGER NOT NULL
                )
            """.trimIndent())
            db.execSQL("""
                CREATE TABLE IF NOT EXISTS tx_history (
                    txid              TEXT PRIMARY KEY,
                    height            INTEGER NOT NULL,
                    confirms          INTEGER NOT NULL,
                    amount_sat        INTEGER NOT NULL,
                    sent_sat          INTEGER NOT NULL,
                    cycled_sat        INTEGER NOT NULL,
                    fee_sat           INTEGER NOT NULL,
                    is_incoming       INTEGER NOT NULL,
                    is_self           INTEGER NOT NULL,
                    timestamp         INTEGER NOT NULL,
                    cached_at         INTEGER NOT NULL,
                    is_issuance       INTEGER NOT NULL DEFAULT 0,
                    issuance_burn_sat INTEGER NOT NULL DEFAULT 0
                )
            """.trimIndent())
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_tx_history_height ON tx_history(height DESC)")
            db.execSQL("""
                CREATE TABLE IF NOT EXISTS reserved_utxos (
                    txid_in         TEXT NOT NULL,
                    vout            INTEGER NOT NULL,
                    value_sat       INTEGER NOT NULL,
                    submitted_txid  TEXT NOT NULL,
                    submitted_at    INTEGER NOT NULL,
                    PRIMARY KEY(txid_in, vout)
                )
            """.trimIndent())
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_reserved_submitted_txid ON reserved_utxos(submitted_txid)")
            db.execSQL("""
                CREATE TABLE IF NOT EXISTS pending_consolidations (
                    submitted_txid TEXT PRIMARY KEY,
                    submitted_at   INTEGER NOT NULL,
                    last_retry_at  INTEGER,
                    retry_count    INTEGER NOT NULL DEFAULT 0,
                    last_error     TEXT
                )
            """.trimIndent())
            db.execSQL("""
                CREATE TABLE IF NOT EXISTS quarantined_nodes (
                    host              TEXT PRIMARY KEY,
                    quarantined_until INTEGER NOT NULL,
                    reason            TEXT NOT NULL
                )
            """.trimIndent())
        }

        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
            if (oldVersion <= 1) {
                try { db.execSQL("ALTER TABLE tx_history ADD COLUMN is_issuance INTEGER NOT NULL DEFAULT 0") } catch (_: Exception) {}
                try { db.execSQL("ALTER TABLE tx_history ADD COLUMN issuance_burn_sat INTEGER NOT NULL DEFAULT 0") } catch (_: Exception) {}
            }
        }
    }

    @Volatile
    private var helper: Helper? = null
    private val initLock = Any()

    fun init(context: Context) {
        synchronized(initLock) {
            if (helper != null) return
            helper = Helper(context.applicationContext)
            // Touch writableDatabase to force onConfigure + onCreate
            helper!!.writableDatabase
        }
    }

    fun getDatabase(): SQLiteDatabase =
        helper?.writableDatabase
            ?: error("WalletReliabilityDb not initialized (call init() from MainActivity.onCreate)")
}
