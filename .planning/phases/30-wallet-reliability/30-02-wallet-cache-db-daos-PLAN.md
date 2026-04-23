---
id: 30-02-wallet-cache-db-daos
phase: 30
plan: 02
type: execute
wave: 1
depends_on:
  - 30-01-wave0-test-scaffolding
files_modified:
  - android/app/src/main/java/io/raventag/app/wallet/cache/WalletReliabilityDb.kt
  - android/app/src/main/java/io/raventag/app/wallet/cache/WalletCacheDao.kt
  - android/app/src/main/java/io/raventag/app/wallet/cache/ReservedUtxoDao.kt
  - android/app/src/main/java/io/raventag/app/wallet/cache/TxHistoryDao.kt
  - android/app/src/main/java/io/raventag/app/wallet/cache/PendingConsolidationDao.kt
  - android/app/src/main/java/io/raventag/app/wallet/health/QuarantineDao.kt
  - android/app/src/main/java/io/raventag/app/MainActivity.kt
autonomous: true
requirements:
  - WALLET-BAL
  - WALLET-UTXO
threat_refs:
  - T-30-UTXO
  - T-30-NET

must_haves:
  truths:
    - "Opening WalletScreen reads last-known balance + UTXOs + tx history from SQLite instantly (D-04)"
    - "Reserved UTXOs are persisted in SQLite (D-20); sum is derivable"
    - "Pending-consolidation flag survives app kill and restart (D-21)"
    - "TOFU quarantine records survive app kill (D-11) for 1h enforcement"
    - "All five tables live in one `wallet_reliability.db` opened with PRAGMA synchronous=FULL + journal_mode=WAL (Pitfall 6)"
  artifacts:
    - path: "android/app/src/main/java/io/raventag/app/wallet/cache/WalletReliabilityDb.kt"
      provides: "single SQLiteOpenHelper opening wallet_reliability.db with all five CREATE TABLE statements"
      contains: "CREATE TABLE wallet_state_cache"
    - path: "android/app/src/main/java/io/raventag/app/wallet/cache/WalletCacheDao.kt"
      provides: "D-04 cache object; pure `computeSpendableBalanceSat` static helper"
      exports: ["WalletCacheDao", "CachedWalletState"]
    - path: "android/app/src/main/java/io/raventag/app/wallet/cache/ReservedUtxoDao.kt"
      provides: "D-20 reservation CRUD + stale-prune + sum-reserved"
      exports: ["ReservedUtxoDao", "ReservedUtxo"]
    - path: "android/app/src/main/java/io/raventag/app/wallet/cache/TxHistoryDao.kt"
      provides: "D-23 paged tx history + three-value columns (sent/cycled/fee)"
      exports: ["TxHistoryDao", "TxHistoryRow"]
    - path: "android/app/src/main/java/io/raventag/app/wallet/cache/PendingConsolidationDao.kt"
      provides: "D-21 pending-consolidation flag persistence"
      exports: ["PendingConsolidationDao", "PendingConsolidation"]
    - path: "android/app/src/main/java/io/raventag/app/wallet/health/QuarantineDao.kt"
      provides: "D-11 TOFU quarantine table"
      exports: ["QuarantineDao"]
  key_links:
    - from: "MainActivity.onCreate"
      to: "WalletReliabilityDb.init(this)"
      via: "one call per process"
      pattern: "WalletReliabilityDb\\.init"
    - from: "all five DAOs"
      to: "WalletReliabilityDb.writableDatabase"
      via: "shared singleton DB handle"
      pattern: "WalletReliabilityDb\\.getDatabase"
---

<objective>
Create the persistence layer for Phase 30: one SQLite database `wallet_reliability.db` hosting five tables (`wallet_state_cache`, `tx_history`, `reserved_utxos`, `pending_consolidations`, `quarantined_nodes`), and five singleton-object DAOs wrapping them. Wire DB init into `MainActivity.onCreate`. No business logic yet — pure CRUD + a pure `computeSpendableBalanceSat` helper on `WalletCacheDao`.

Purpose: every subsequent plan depends on these DAOs existing. Centralizing in one file + one DB allows transactional cross-table queries (e.g. Pattern 3 Example 2 from RESEARCH.md: `SELECT SUM(...) FROM reserved_utxos WHERE NOT EXISTS (SELECT 1 FROM tx_history WHERE confirms > 0)`).

Output: six new production files + one MainActivity edit. Pure-function unit tests from plan 30-01 pass GREEN after this plan (at least `WalletCacheDaoTest.balance_subtracts_reserved_*`).
</objective>

<execution_context>
@$HOME/.claude/get-shit-done/workflows/execute-plan.md
@$HOME/.claude/get-shit-done/templates/summary.md
</execution_context>

<context>
@.planning/STATE.md
@.planning/phases/30-wallet-reliability/30-CONTEXT.md
@.planning/phases/30-wallet-reliability/30-RESEARCH.md
@.planning/phases/30-wallet-reliability/30-PATTERNS.md
@.planning/phases/30-wallet-reliability/30-VALIDATION.md
@android/app/src/main/java/io/raventag/app/security/TofuFingerprintDao.kt
@android/app/src/main/java/io/raventag/app/wallet/RavencoinPublicNode.kt

<interfaces>
Types already in the codebase (do NOT redefine):

From `io.raventag.app.wallet.RavencoinPublicNode`:
```kotlin
data class Utxo(
    val txid: String,
    val vout: Int,
    val value: Long,     // satoshis (1 RVN = 100_000_000 sat)
    val height: Int
)
data class AssetUtxo(
    val txid: String,
    val vout: Int,
    val assetName: String,
    val amount: Long,    // in asset base units
    val height: Int
)
data class TxHistoryEntry(
    val txid: String,
    val height: Int,
    val confirmations: Int,
    val timestamp: Long
    // additional fields may exist — consult file at execution time
)
```

Test stubs from 30-01 expect these signatures — honor them exactly:

```kotlin
object WalletCacheDao {
    fun init(context: android.content.Context)
    fun writeState(utxos: List<Utxo>, assetUtxos: Map<String, List<AssetUtxo>>, blockHeight: Int)
    fun readState(): CachedWalletState?
    fun getLastRefreshedAt(): Long
    @JvmStatic fun computeSpendableBalanceSat(utxos: List<Utxo>, reservedSat: Long): Long
    data class CachedWalletState(
        val walletId: String,
        val balanceSat: Long,
        val utxos: List<Utxo>,
        val assetUtxos: Map<String, List<AssetUtxo>>,
        val blockHeight: Int,
        val lastRefreshedAt: Long
    )
}

object ReservedUtxoDao {
    fun init(context: android.content.Context)
    data class ReservedUtxo(val txidIn: String, val vout: Int, val valueSat: Long, val submittedTxid: String, val submittedAt: Long)
    fun reserve(entries: List<ReservedUtxo>)
    fun releaseFor(submittedTxid: String)
    fun sumReservedSat(): Long
    fun pruneOlderThan(thresholdMillis: Long)
    fun all(): List<ReservedUtxo>
}
```
</interfaces>
</context>

<tasks>

<task type="auto" tdd="true">
  <name>Task 1: Create WalletReliabilityDb + WalletCacheDao + ReservedUtxoDao with full schema, PRAGMAs, and pure balance helper</name>
  <files>
    android/app/src/main/java/io/raventag/app/wallet/cache/WalletReliabilityDb.kt,
    android/app/src/main/java/io/raventag/app/wallet/cache/WalletCacheDao.kt,
    android/app/src/main/java/io/raventag/app/wallet/cache/ReservedUtxoDao.kt
  </files>
  <read_first>
    @.planning/phases/30-wallet-reliability/30-RESEARCH.md#L346-L405,
    @.planning/phases/30-wallet-reliability/30-RESEARCH.md#L515-L521,
    @.planning/phases/30-wallet-reliability/30-RESEARCH.md#L593-L621,
    @.planning/phases/30-wallet-reliability/30-PATTERNS.md#L34-L112,
    @android/app/src/main/java/io/raventag/app/security/TofuFingerprintDao.kt,
    @android/app/src/test/java/io/raventag/app/wallet/cache/WalletCacheDaoTest.kt,
    @android/app/src/test/java/io/raventag/app/wallet/cache/ReservedUtxoDaoTest.kt
  </read_first>
  <action>
    **WalletReliabilityDb.kt** — single `SQLiteOpenHelper` owning the DB file. Structure:
    ```kotlin
    package io.raventag.app.wallet.cache

    import android.content.Context
    import android.database.sqlite.SQLiteDatabase
    import android.database.sqlite.SQLiteOpenHelper

    internal object WalletReliabilityDb {
        private const val DB_NAME = "wallet_reliability.db"
        private const val DB_VERSION = 1

        private class Helper(context: Context) : SQLiteOpenHelper(context, DB_NAME, null, DB_VERSION) {
            override fun onConfigure(db: SQLiteDatabase) {
                db.execSQL("PRAGMA synchronous=FULL;")
                db.execSQL("PRAGMA journal_mode=WAL;")
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
                        txid        TEXT PRIMARY KEY,
                        height      INTEGER NOT NULL,
                        confirms    INTEGER NOT NULL,
                        amount_sat  INTEGER NOT NULL,
                        sent_sat    INTEGER NOT NULL,
                        cycled_sat  INTEGER NOT NULL,
                        fee_sat     INTEGER NOT NULL,
                        is_incoming INTEGER NOT NULL,
                        is_self     INTEGER NOT NULL,
                        timestamp   INTEGER NOT NULL,
                        cached_at   INTEGER NOT NULL
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
            override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) { /* v1 only */ }
        }

        @Volatile private var helper: Helper? = null
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
            helper?.writableDatabase ?: error("WalletReliabilityDb not initialized (call init() from MainActivity.onCreate)")
    }
    ```
    Set column `value_sat` on `reserved_utxos` (deviating slightly from the RESEARCH.md schema which lacked it; required so Example 2 joined-sum can compute without a separate tx_history lookup for the reservation's own inputs). Do NOT remove the `submitted_txid` column.

    **WalletCacheDao.kt** — uses `Gson` (already a dependency, see RavencoinPublicNode.kt line 5-8 imports). Mirror the TofuFingerprintDao object-helper pattern exactly (thread-safe init, `ContentValues`, `insertWithOnConflict(..., CONFLICT_REPLACE)`).
    ```kotlin
    package io.raventag.app.wallet.cache

    import android.content.ContentValues
    import android.content.Context
    import android.database.sqlite.SQLiteDatabase
    import com.google.gson.Gson
    import com.google.gson.reflect.TypeToken
    import io.raventag.app.wallet.AssetUtxo
    import io.raventag.app.wallet.Utxo

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
            db.query(TABLE, arrayOf(
                "wallet_id", "balance_sat", "utxos_json", "asset_utxos_json", "block_height", "last_refreshed_at"
            ), "wallet_id = ?", arrayOf(WALLET_ID), null, null, null).use { c ->
                if (!c.moveToFirst()) return null
                val utxosType = object : TypeToken<List<Utxo>>() {}.type
                val assetsType = object : TypeToken<Map<String, List<AssetUtxo>>>() {}.type
                return CachedWalletState(
                    walletId = c.getString(0),
                    balanceSat = c.getLong(1),
                    utxos = gson.fromJson(c.getString(2), utxosType) ?: emptyList(),
                    assetUtxos = gson.fromJson(c.getString(3), assetsType) ?: emptyMap(),
                    blockHeight = c.getInt(4),
                    lastRefreshedAt = c.getLong(5)
                )
            }
        }

        fun getLastRefreshedAt(): Long = readState()?.lastRefreshedAt ?: 0L

        @JvmStatic
        fun computeSpendableBalanceSat(utxos: List<Utxo>, reservedSat: Long): Long {
            val confirmedSat = utxos.sumOf { it.value }
            return (confirmedSat - reservedSat).coerceAtLeast(0L)
        }
    }
    ```

    **ReservedUtxoDao.kt** — same pattern:
    ```kotlin
    package io.raventag.app.wallet.cache

    import android.content.ContentValues
    import android.content.Context
    import android.database.sqlite.SQLiteDatabase

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

        fun reserve(entries: List<ReservedUtxo>) {
            if (entries.isEmpty()) return
            val db = WalletReliabilityDb.getDatabase()
            db.beginTransaction()
            try {
                for (e in entries) {
                    val cv = ContentValues().apply {
                        put("txid_in", e.txidIn)
                        put("vout", e.vout)
                        put("value_sat", e.valueSat)
                        put("submitted_txid", e.submittedTxid)
                        put("submitted_at", e.submittedAt)
                    }
                    db.insertWithOnConflict(TABLE, null, cv, SQLiteDatabase.CONFLICT_REPLACE)
                }
                db.setTransactionSuccessful()
            } finally { db.endTransaction() }
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
            db.query(TABLE, arrayOf("txid_in","vout","value_sat","submitted_txid","submitted_at"),
                null, null, null, null, "submitted_at DESC").use { c ->
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
    ```

    Em-dash audit: `! grep -P '\u2014' android/app/src/main/java/io/raventag/app/wallet/cache/WalletReliabilityDb.kt android/app/src/main/java/io/raventag/app/wallet/cache/WalletCacheDao.kt android/app/src/main/java/io/raventag/app/wallet/cache/ReservedUtxoDao.kt`.
  </action>
  <verify>
    <automated>cd android && ./gradlew :app:testConsumerDebugUnitTest --tests "io.raventag.app.wallet.cache.WalletCacheDaoTest" --tests "io.raventag.app.wallet.cache.ReservedUtxoDaoTest" -i 2>&1 | tail -30</automated>
  </verify>
  <acceptance_criteria>
    - `test -f android/app/src/main/java/io/raventag/app/wallet/cache/WalletReliabilityDb.kt`
    - `test -f android/app/src/main/java/io/raventag/app/wallet/cache/WalletCacheDao.kt`
    - `test -f android/app/src/main/java/io/raventag/app/wallet/cache/ReservedUtxoDao.kt`
    - `grep -q "CREATE TABLE IF NOT EXISTS wallet_state_cache" android/app/src/main/java/io/raventag/app/wallet/cache/WalletReliabilityDb.kt`
    - `grep -q "CREATE TABLE IF NOT EXISTS tx_history" android/app/src/main/java/io/raventag/app/wallet/cache/WalletReliabilityDb.kt`
    - `grep -q "CREATE TABLE IF NOT EXISTS reserved_utxos" android/app/src/main/java/io/raventag/app/wallet/cache/WalletReliabilityDb.kt`
    - `grep -q "CREATE TABLE IF NOT EXISTS pending_consolidations" android/app/src/main/java/io/raventag/app/wallet/cache/WalletReliabilityDb.kt`
    - `grep -q "CREATE TABLE IF NOT EXISTS quarantined_nodes" android/app/src/main/java/io/raventag/app/wallet/cache/WalletReliabilityDb.kt`
    - `grep -q "PRAGMA synchronous=FULL" android/app/src/main/java/io/raventag/app/wallet/cache/WalletReliabilityDb.kt`
    - `grep -q "PRAGMA journal_mode=WAL" android/app/src/main/java/io/raventag/app/wallet/cache/WalletReliabilityDb.kt`
    - `grep -q "object WalletCacheDao" android/app/src/main/java/io/raventag/app/wallet/cache/WalletCacheDao.kt`
    - `grep -q "fun computeSpendableBalanceSat" android/app/src/main/java/io/raventag/app/wallet/cache/WalletCacheDao.kt`
    - `grep -q "coerceAtLeast" android/app/src/main/java/io/raventag/app/wallet/cache/WalletCacheDao.kt`
    - `grep -q "object ReservedUtxoDao" android/app/src/main/java/io/raventag/app/wallet/cache/ReservedUtxoDao.kt`
    - `grep -q "pruneOlderThan" android/app/src/main/java/io/raventag/app/wallet/cache/ReservedUtxoDao.kt`
    - `! grep -P '\u2014' android/app/src/main/java/io/raventag/app/wallet/cache/WalletReliabilityDb.kt android/app/src/main/java/io/raventag/app/wallet/cache/WalletCacheDao.kt android/app/src/main/java/io/raventag/app/wallet/cache/ReservedUtxoDao.kt`
    - Pure-function tests in `WalletCacheDaoTest.balance_subtracts_reserved_*` exit GREEN. Context-dependent tests (roundtrip, insert_on_broadcast, cleanup_on_confirm, prune_stale) either GREEN (if Robolectric is on classpath and works) or remain @Ignore'd with a reason; both are acceptable.
  </acceptance_criteria>
  <done>DB helper + first two DAOs exist, schema creates with correct PRAGMAs, pure-function unit tests pass GREEN. No em dashes.</done>
</task>

<task type="auto" tdd="true">
  <name>Task 2: Create TxHistoryDao + PendingConsolidationDao + QuarantineDao, wire DB init in MainActivity</name>
  <files>
    android/app/src/main/java/io/raventag/app/wallet/cache/TxHistoryDao.kt,
    android/app/src/main/java/io/raventag/app/wallet/cache/PendingConsolidationDao.kt,
    android/app/src/main/java/io/raventag/app/wallet/health/QuarantineDao.kt,
    android/app/src/main/java/io/raventag/app/MainActivity.kt
  </files>
  <read_first>
    @.planning/phases/30-wallet-reliability/30-RESEARCH.md#L363-L403,
    @.planning/phases/30-wallet-reliability/30-PATTERNS.md#L440-L448,
    @android/app/src/main/java/io/raventag/app/security/TofuFingerprintDao.kt,
    @android/app/src/main/java/io/raventag/app/wallet/cache/WalletReliabilityDb.kt,
    @android/app/src/main/java/io/raventag/app/MainActivity.kt
  </read_first>
  <action>
    **TxHistoryDao.kt** (package `io.raventag.app.wallet.cache`):
    ```kotlin
    package io.raventag.app.wallet.cache

    import android.content.ContentValues
    import android.content.Context
    import android.database.sqlite.SQLiteDatabase

    object TxHistoryDao {
        private const val TABLE = "tx_history"

        data class TxHistoryRow(
            val txid: String,
            val height: Int,          // 0 = mempool
            val confirms: Int,
            val amountSat: Long,      // positive = incoming, negative = net outgoing
            val sentSat: Long,        // D-19 "Sent" — amount to external address
            val cycledSat: Long,      // D-19 "Cycled" — amount to currentIndex+1
            val feeSat: Long,         // D-19 "Fee"
            val isIncoming: Boolean,
            val isSelf: Boolean,      // true for pure consolidation / self-transfer
            val timestamp: Long,      // block header unix seconds, 0 if mempool
            val cachedAt: Long
        )

        fun init(context: Context) = WalletReliabilityDb.init(context)

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
            } finally { db.endTransaction() }
        }

        /** Paged list ordered by height DESC (mempool=0 rows sort last). */
        fun page(limit: Int, offset: Int): List<TxHistoryRow> {
            val db = WalletReliabilityDb.getDatabase()
            val out = mutableListOf<TxHistoryRow>()
            // height=0 mempool first, then confirmed DESC
            val orderBy = "CASE WHEN height = 0 THEN 1 ELSE 0 END DESC, height DESC, timestamp DESC"
            db.query(TABLE,
                arrayOf("txid","height","confirms","amount_sat","sent_sat","cycled_sat","fee_sat","is_incoming","is_self","timestamp","cached_at"),
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

        fun findByTxid(txid: String): TxHistoryRow? = page(limit = 1, offset = 0).firstOrNull { it.txid == txid }
            ?: run {
                val db = WalletReliabilityDb.getDatabase()
                db.query(TABLE,
                    arrayOf("txid","height","confirms","amount_sat","sent_sat","cycled_sat","fee_sat","is_incoming","is_self","timestamp","cached_at"),
                    "txid = ?", arrayOf(txid), null, null, null).use { c ->
                    if (!c.moveToFirst()) null
                    else TxHistoryRow(
                        txid = c.getString(0), height = c.getInt(1), confirms = c.getInt(2),
                        amountSat = c.getLong(3), sentSat = c.getLong(4), cycledSat = c.getLong(5),
                        feeSat = c.getLong(6), isIncoming = c.getInt(7) == 1, isSelf = c.getInt(8) == 1,
                        timestamp = c.getLong(9), cachedAt = c.getLong(10)
                    )
                }
            }

        fun count(): Int {
            val db = WalletReliabilityDb.getDatabase()
            db.rawQuery("SELECT COUNT(*) FROM $TABLE", null).use { c ->
                return if (c.moveToFirst()) c.getInt(0) else 0
            }
        }
    }
    ```

    **PendingConsolidationDao.kt**:
    ```kotlin
    package io.raventag.app.wallet.cache

    import android.content.ContentValues
    import android.content.Context
    import android.database.sqlite.SQLiteDatabase

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
            db.query(TABLE,
                arrayOf("submitted_txid","submitted_at","last_retry_at","retry_count","last_error"),
                null, null, null, null, "submitted_at ASC").use { c ->
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
    ```

    **QuarantineDao.kt** (package `io.raventag.app.wallet.health`):
    ```kotlin
    package io.raventag.app.wallet.health

    import android.content.ContentValues
    import android.content.Context
    import android.database.sqlite.SQLiteDatabase
    import io.raventag.app.wallet.cache.WalletReliabilityDb

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
            db.query(TABLE, arrayOf("host","quarantined_until","reason"), null, null, null, null, null).use { c ->
                while (c.moveToNext()) out += Quarantine(c.getString(0), c.getLong(1), c.getString(2))
            }
            return out
        }
    }
    ```

    **MainActivity.kt edit** — add `WalletReliabilityDb.init(this)` in `onCreate`, adjacent to the existing `TofuFingerprintDao.init(...)` or notification-channel creation block (lines ~2447-2461). Use Grep to find the exact line right after `super.onCreate(savedInstanceState)` or right before the existing WorkManager scheduling call. Insert:
    ```kotlin
    io.raventag.app.wallet.cache.WalletReliabilityDb.init(this)
    ```
    Exactly one call. Do NOT duplicate. If `TofuFingerprintDao.init(this)` is present, place our init immediately after it.

    Em-dash audit: `! grep -P '\u2014' android/app/src/main/java/io/raventag/app/wallet/cache/TxHistoryDao.kt android/app/src/main/java/io/raventag/app/wallet/cache/PendingConsolidationDao.kt android/app/src/main/java/io/raventag/app/wallet/health/QuarantineDao.kt`. (MainActivity.kt is existing — audit only the touched lines by reading the diff hunk to verify no em dash.)
  </action>
  <verify>
    <automated>cd android && ./gradlew :app:assembleConsumerDebug -q 2>&1 | tail -15</automated>
  </verify>
  <acceptance_criteria>
    - `test -f android/app/src/main/java/io/raventag/app/wallet/cache/TxHistoryDao.kt`
    - `test -f android/app/src/main/java/io/raventag/app/wallet/cache/PendingConsolidationDao.kt`
    - `test -f android/app/src/main/java/io/raventag/app/wallet/health/QuarantineDao.kt`
    - `grep -q "object TxHistoryDao" android/app/src/main/java/io/raventag/app/wallet/cache/TxHistoryDao.kt`
    - `grep -q "fun page(limit: Int, offset: Int)" android/app/src/main/java/io/raventag/app/wallet/cache/TxHistoryDao.kt`
    - `grep -q "data class TxHistoryRow" android/app/src/main/java/io/raventag/app/wallet/cache/TxHistoryDao.kt`
    - `grep -q "sent_sat\|sentSat" android/app/src/main/java/io/raventag/app/wallet/cache/TxHistoryDao.kt`
    - `grep -q "cycled_sat\|cycledSat" android/app/src/main/java/io/raventag/app/wallet/cache/TxHistoryDao.kt`
    - `grep -q "object PendingConsolidationDao" android/app/src/main/java/io/raventag/app/wallet/cache/PendingConsolidationDao.kt`
    - `grep -q "object QuarantineDao" android/app/src/main/java/io/raventag/app/wallet/health/QuarantineDao.kt`
    - `grep -q "REASON_TOFU_MISMATCH" android/app/src/main/java/io/raventag/app/wallet/health/QuarantineDao.kt`
    - `grep -q "WalletReliabilityDb.init(this)" android/app/src/main/java/io/raventag/app/MainActivity.kt`
    - `! grep -P '\u2014' android/app/src/main/java/io/raventag/app/wallet/cache/TxHistoryDao.kt android/app/src/main/java/io/raventag/app/wallet/cache/PendingConsolidationDao.kt android/app/src/main/java/io/raventag/app/wallet/health/QuarantineDao.kt`
    - `cd android && ./gradlew :app:assembleConsumerDebug` exits 0.
  </acceptance_criteria>
  <done>All five DAOs compile and integrate. MainActivity initializes the DB once. Build succeeds.</done>
</task>

</tasks>

<threat_model>
## Trust Boundaries

| Boundary | Description |
|----------|-------------|
| app process → SQLite file | `wallet_reliability.db` is app-private (internal storage). Untrusted input (ElectrumX responses) will be stored here by later plans. |

## STRIDE Threat Register

| Threat ID | Category | Component | Disposition | Mitigation Plan |
|-----------|----------|-----------|-------------|-----------------|
| T-30-UTXO-01 | Tampering | Reserved-UTXO row persists after crash | mitigate | `PRAGMA synchronous=FULL` + `PRAGMA journal_mode=WAL` on DB open (Pitfall 6). Startup prune of rows older than 48h (implemented in plan 30-05). |
| T-30-UTXO-02 | Tampering | Reserved-UTXO subtraction causes negative balance | mitigate | `computeSpendableBalanceSat` uses `coerceAtLeast(0L)` (A6 in RESEARCH.md). Unit test `WalletCacheDaoTest.balance_subtracts_reserved_never_negative` enforces this. |
| T-30-NET-01 | Information Disclosure | Wallet state JSON leaked if device is rooted | accept | SQLite file is app-private; root = full trust boundary already breached. StrongBox-bound keys (Phase 10) still protect the mnemonic. Balance is public blockchain data (derivable from any address) — no secret leaked. ASVS V7.1. |
| T-30-UTXO-03 | Denial of Service | Unbounded tx_history growth | mitigate | `TxHistoryDao.page(limit, offset)` caps UI reads; a future housekeeping plan can add row-count trimming. v1 acceptable: users with many txs are rare. |

ASVS L1 controls: V6.2 (no custom crypto in this layer), V7.4 (PRAGMA WAL for durability).
</threat_model>

<verification>
- `cd android && ./gradlew :app:assembleConsumerDebug` exits 0.
- `cd android && ./gradlew :app:testConsumerDebugUnitTest --tests "io.raventag.app.wallet.cache.*" -i` — pure-function tests green; SQLite-requiring tests GREEN if Robolectric available, else remain @Ignore'd with reason.
- `grep -r "PRAGMA synchronous=FULL" android/app/src/main/java/io/raventag/app/wallet/cache/` returns a hit (Pitfall 6 enforcement).
- No em dashes in any new file.
</verification>

<success_criteria>
- All five DAOs exist, share one DB, follow the TofuFingerprintDao structural pattern.
- Every CREATE TABLE statement matches RESEARCH.md §Pattern 3 schema (with the documented `value_sat` addition to `reserved_utxos`).
- WAL + synchronous FULL PRAGMAs set in `onConfigure`.
- Pure-function unit tests from plan 30-01 are GREEN after this plan.
- MainActivity initializes the DB exactly once.
</success_criteria>

<output>
Create `.planning/phases/30-wallet-reliability/30-02-SUMMARY.md` listing:
- File paths + line counts for all six new files and the MainActivity diff.
- Exact schema (paste CREATE TABLE statements) so downstream plans can reference.
- Which pure tests flipped RED→GREEN, which remain RED / @Ignore and why.
- Note for plan 30-05: startup prune call should be `ReservedUtxoDao.pruneOlderThan(System.currentTimeMillis() - 48L*3600_000L)`.
</output>
