# Phase 30: Wallet Reliability - Pattern Map

**Mapped:** 2026-04-18
**Files analyzed:** 18 new + 6 modified
**Analogs found:** 22 / 24 (2 no-analog, use RESEARCH.md patterns)

## File Classification

| New/Modified File | Role | Data Flow | Closest Analog | Match Quality |
|-------------------|------|-----------|----------------|---------------|
| `wallet/cache/WalletCacheDao.kt` | DAO (new) | CRUD SQLite | `security/TofuFingerprintDao.kt` | exact (role + data flow) |
| `wallet/cache/ReservedUtxoDao.kt` | DAO (new) | CRUD SQLite | `security/TofuFingerprintDao.kt` | exact |
| `wallet/cache/TxHistoryDao.kt` | DAO (new) | CRUD SQLite + pagination | `security/TofuFingerprintDao.kt` | role-match (adds pagination) |
| `wallet/cache/PendingConsolidationDao.kt` | DAO (new) | CRUD SQLite | `security/TofuFingerprintDao.kt` | exact |
| `wallet/health/QuarantineDao.kt` | DAO (new) | CRUD SQLite | `security/TofuFingerprintDao.kt` | exact |
| `wallet/subscription/SubscriptionManager.kt` | long-lived network service (new) | event-driven (socket → Flow) | `wallet/RavencoinPublicNode.kt` (`call()` + `TofuTrustManager`) | role-match (same TLS + TOFU; adds persistent socket) |
| `wallet/subscription/ScripthashEvent.kt` | sealed class (new) | data model | inline `data class` patterns in `RavencoinPublicNode.kt:39-127` | role-match (extend existing style) |
| `wallet/health/NodeHealthMonitor.kt` | service (new) | request-response + state | `wallet/RavencoinPublicNode.kt` (`ping()` + failover) | role-match |
| `wallet/fee/FeeEstimator.kt` | service (new) | request-response + fallback | existing `getMinRelayFeeRateSatPerByte` in `RavencoinPublicNode.kt` | role-match |
| `security/BiometricGate.kt` | security helper (new) | request-response (suspend) | `wallet/WalletManager.kt:302-314` (encrypt/decrypt) + existing BiometricManager check in `MainActivity.kt:2558-2567` | role-match (combines both) |
| `security/MnemonicExporter.kt` | security service (new) | transform (decrypt + zero-fill) | `wallet/WalletManager.kt:302-314` | role-match |
| `worker/RebroadcastWorker.kt` | CoroutineWorker (new) | batch/scheduled | `worker/WalletPollingWorker.kt` | exact |
| `worker/IncomingTxNotificationHelper.kt` (new channel `incoming_tx`) | notification helper (new) | event-driven | `worker/NotificationHelper.kt` + `worker/TransactionNotificationHelper.kt` | exact |
| `wallet/WalletManager.kt` (extend D-15) | existing | CRUD | self (lines 254-314 for crypto) | N/A (self-extension) |
| `wallet/RavencoinPublicNode.kt` (extend estimatefee + subscribe entry) | existing | request-response | self (`call()` method at line 1557) | N/A (self-extension) |
| `worker/WalletPollingWorker.kt` (extend D-06 for scripthash diff) | existing | scheduled | self (entire file) | N/A (self-extension) |
| `ui/screens/WalletScreen.kt` (extend: cache banner, conn pill, battery chip, three-value row) | Compose screen | UI state | self (existing `WalletInfo` + `ElectrumStatus`) | N/A (self-extension) |
| `ui/screens/MnemonicBackupScreen.kt` (extend: biometric cover card) | Compose screen | UI state | self (existing copy/dismiss flow) + new `BiometricGate` | N/A (self-extension) |
| `ui/screens/SendRvnScreen.kt` (extend: fee override row) | Compose screen | UI state | self | N/A |
| `ui/screens/TransactionDetailsScreen.kt` (extend: three-value breakdown D-19) | Compose screen | UI state | self + new `TxHistoryDao` schema | N/A |

## Pattern Assignments

### `wallet/cache/WalletCacheDao.kt` (new, DAO, CRUD SQLite)

**Analog:** `android/app/src/main/java/io/raventag/app/security/TofuFingerprintDao.kt`

Copy the entire structure verbatim (package + imports + object-with-helper pattern) and swap schema, table name, and DB filename.

**Imports pattern** (lines 1-7):
```kotlin
package io.raventag.app.wallet.cache

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
```

**Singleton-object + private helper class pattern** (lines 21-43):
```kotlin
object TofuFingerprintDao {
    private const val CERT_DB_NAME = "electrum_certificates.db"
    private const val CERT_TABLE = "tofu_fingerprints"
    private const val DB_VERSION = 1

    private class CertDbHelper(context: Context) : SQLiteOpenHelper(context, CERT_DB_NAME, null, DB_VERSION) {
        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL("""
                CREATE TABLE IF NOT EXISTS $CERT_TABLE (
                    host TEXT PRIMARY KEY,
                    fingerprint TEXT NOT NULL,
                    pinned_at INTEGER NOT NULL
                )
            """.trimIndent())
        }
        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {}
    }

    private var dbHelper: CertDbHelper? = null
    private var db: SQLiteDatabase? = null
    private var initialized = false
    private val initLock = Any()
```

**Thread-safe init pattern** (lines 57-64):
```kotlin
fun init(context: Context) {
    synchronized(initLock) {
        if (initialized) return
        dbHelper = CertDbHelper(context.applicationContext)
        db = dbHelper!!.writableDatabase
        initialized = true
    }
}
```

**Read pattern** (lines 72-84) and **upsert pattern** (lines 93-106):
```kotlin
fun getFingerprint(host: String): String? {
    db ?: return null
    val cursor = db!!.query(CERT_TABLE, arrayOf("fingerprint"), "host = ?", arrayOf(host), null, null, null)
    return cursor.use { if (it.moveToFirst()) it.getString(0) else null }
}

fun pinFingerprint(host: String, fingerprint: String) {
    db ?: return
    val values = ContentValues().apply {
        put("host", host); put("fingerprint", fingerprint); put("pinned_at", System.currentTimeMillis())
    }
    db!!.insertWithOnConflict(CERT_TABLE, null, values, SQLiteDatabase.CONFLICT_REPLACE)
}
```

**Apply per file:**
- `WalletCacheDao.kt`: DB `wallet_reliability.db`, table `wallet_state_cache`, schema per RESEARCH.md §Pattern 3 lines 354-361. Must also set `PRAGMA synchronous=FULL` + `PRAGMA journal_mode=WAL` per RESEARCH.md Pitfall 6.
- `ReservedUtxoDao.kt`: same DB `wallet_reliability.db`, table `reserved_utxos`, schema per RESEARCH.md lines 379-387.
- `TxHistoryDao.kt`: same DB, table `tx_history`, add pagination helper: `query(...ORDER BY height DESC LIMIT ? OFFSET ?)`.
- `PendingConsolidationDao.kt`: same DB, table `pending_consolidations`.
- `QuarantineDao.kt`: same DB (or `health.db`), table `quarantined_nodes` — planner chooses co-location.

---

### `wallet/subscription/SubscriptionManager.kt` (new, long-lived network service, event-driven)

**Analog:** `android/app/src/main/java/io/raventag/app/wallet/RavencoinPublicNode.kt`

The raw-socket TLS open pattern plus `TofuTrustManager` must be reused. The difference is that the socket is NOT auto-closed after one request — a reader coroutine owns it for the session.

**Raw socket + TOFU TLS open pattern** (RavencoinPublicNode.kt lines 1557-1567):
```kotlin
val sslCtx = SSLContext.getInstance("TLS")
sslCtx.init(null, arrayOf(TofuTrustManager(context, server.host)), SecureRandom())

val rawSocket = java.net.Socket()
rawSocket.connect(InetSocketAddress(server.host, server.port), CONNECT_TIMEOUT_MS)
val sslSocket = sslCtx.socketFactory.createSocket(rawSocket, server.host, server.port, true) as SSLSocket
sslSocket.soTimeout = READ_TIMEOUT_MS
```

**Handshake + request/response protocol** (lines 1568-1589):
```kotlin
sslSocket.use { sock ->
    val writer = PrintWriter(sock.outputStream, true)
    val reader = BufferedReader(InputStreamReader(sock.inputStream))

    if (method != "server.version") {
        val hsId = idCounter.getAndIncrement()
        writer.println("""{"id":$hsId,"method":"server.version","params":["RavenTag/1.0","1.4"]}""")
        reader.readLine() // consume and discard the handshake response
    }

    val id = idCounter.getAndIncrement()
    writer.println(gson.toJson(mapOf("id" to id, "method" to method, "params" to params)))

    val response = reader.readLine() ?: throw Exception("Empty response from ${server.host}")
    val json = JsonParser.parseString(response).asJsonObject
    val err = json.get("error")
    if (err != null && !err.isJsonNull) throw Exception("ElectrumX error: $err")
    return json.get("result") ?: throw Exception("Null result from ${server.host}")
}
```

**TOFU trust manager (REUSE verbatim, do NOT duplicate)** — promote `TofuTrustManager` from `RavencoinPublicNode.kt:1612-1652` to `internal` visibility (or its own file `wallet/TofuTrustManager.kt`) so `SubscriptionManager` can share it.

**Deltas for SubscriptionManager (from RESEARCH.md §Pattern 1 and Example 1):**
- Do NOT use `sslSocket.use { }` (that closes on exit). Store the socket on the `Session` object.
- Launch a reader coroutine: `scope.launch { readLoop() }`.
- Route by presence of `id` field (response → `pending[id]?.complete(...)`) vs absence (push notification → `events.emit(...)`).
- Expose `SharedFlow<ScripthashEvent>` as public API.
- Add `server.ping` every 60s per RESEARCH.md Pitfall 2.

---

### `wallet/fee/FeeEstimator.kt` (new, service, request-response with fallback)

**Analog:** existing fee-related helper `getMinRelayFeeRateSatPerByte` inside `RavencoinPublicNode.kt` plus the `callWithFailover` loop.

**Pattern to copy: method + positional params call via `callWithFailover`** (same pattern as `getBalance` at lines 228-235):
```kotlin
fun getBalance(address: String): AddressBalance {
    val scripthash = addressToScripthash(address)
    val result = callWithFailover("blockchain.scripthash.get_balance", listOf(scripthash)).asJsonObject
    return AddressBalance(
        confirmed = result.get("confirmed")?.asLong ?: 0L,
        unconfirmed = result.get("unconfirmed")?.asLong ?: 0L
    )
}
```

**Deltas for FeeEstimator:**
- Call `blockchain.estimatefee` with `[6]` (6-block target, D-22).
- Sanity-check: if returned value <= 0 (ElectrumX returns `-1` for insufficient data per RESEARCH.md A8), fall back to static 0.01 RVN/kB.
- Wrap the single call in `RetryUtils.retryWithBackoff` for transient failures (see shared pattern below).

---

### `security/BiometricGate.kt` (new, security helper, request-response suspend)

**Analog 1 — crypto primitives:** `wallet/WalletManager.kt:302-314` (encrypt/decrypt). Must be kept identical for the Cipher init pattern.
**Analog 2 — biometric availability check:** `MainActivity.kt:2558-2567`.

**AES-GCM Cipher init pattern** (WalletManager.kt:309-313):
```kotlin
private fun decrypt(enc: ByteArray, iv: ByteArray): ByteArray {
    val key = getOrCreateAndroidKey()
    val cipher = Cipher.getInstance("AES/GCM/NoPadding")
    cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, iv))
    return cipher.doFinal(enc)
}
```

**Biometric availability check pattern** (MainActivity.kt:2558-2567):
```kotlin
val hasLockScreen = remember {
    val authenticators = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
        BiometricManager.Authenticators.BIOMETRIC_STRONG or
        BiometricManager.Authenticators.DEVICE_CREDENTIAL
    } else {
        BiometricManager.Authenticators.BIOMETRIC_WEAK
    }
    BiometricManager.from(this@MainActivity).canAuthenticate(authenticators) ==
        BiometricManager.BIOMETRIC_SUCCESS
}
```

**Deltas for BiometricGate:**
- Combine the two above into a `suspendCancellableCoroutine`-wrapped call per RESEARCH.md §Pattern 2 and Code Example 3 (lines 629-664).
- Catch `KeyPermanentlyInvalidatedException` SEPARATELY on the `cipher.init` call per RESEARCH.md Pitfall 3.
- Construct `BiometricPrompt.CryptoObject(cipher)` and pass to `prompt.authenticate` — binding auth to the decrypt op (not a bool flag).

---

### `worker/RebroadcastWorker.kt` (new, CoroutineWorker, batch/scheduled)

**Analog:** `android/app/src/main/java/io/raventag/app/worker/WalletPollingWorker.kt`

**Class declaration + doWork pattern** (WalletPollingWorker.kt lines 32-42):
```kotlin
class WalletPollingWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    private val prefs get() = applicationContext
        .getSharedPreferences("wallet_poll", Context.MODE_PRIVATE)

    private val gson = Gson()

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        try {
            // ...
```

**Resilience policy** (lines 116-122):
```kotlin
} catch (_: java.io.IOException) {
    // Network error: retry with backoff
    return@withContext Result.retry()
} catch (_: Exception) {
    // Keystore unavailable or unexpected error: skip gracefully
}
Result.success()
```

**Deltas for RebroadcastWorker:**
- Read `txid`, `raw_hex`, `attempt` from `inputData` (per RESEARCH.md Code Example 4 lines 671-702).
- Cap attempt at 5 (D-25).
- On success or confirmation detected: `Result.success()` without scheduling next.
- On failure: schedule next `OneTimeWorkRequest` with `setInitialDelay` from the 30/60/120/240/480 min ladder.
- Use `WorkManager.getInstance(applicationContext).enqueueUniqueWork("rebroadcast-$txid", ExistingWorkPolicy.REPLACE, next)`.

**Extension to existing `WalletPollingWorker.kt` for D-06 scripthash-status comparison:** keep the current balance-diff logic; ADD persistence of the ElectrumX scripthash `status` string (from `blockchain.scripthash.subscribe` — one-shot polling call) and compare against a SharedPrefs key `poll_status_<addr>` on each run. This matches the existing pattern of `prefs.getLong("poll_rvn_sat", -1L)` at line 60.

---

### `worker/IncomingTxNotificationHelper.kt` (new, notification helper, event-driven)

**Analog 1:** `android/app/src/main/java/io/raventag/app/worker/NotificationHelper.kt` (simplest — single channel, single notify method).
**Analog 2:** `android/app/src/main/java/io/raventag/app/worker/TransactionNotificationHelper.kt` (richer — PendingIntent deep-linking into MainActivity).

**Copy verbatim: channel creation pattern** (NotificationHelper.kt lines 28-40):
```kotlin
fun createChannel(context: Context) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Wallet",
            NotificationManager.IMPORTANCE_DEFAULT
        ).apply {
            description = "Incoming RVN and asset transfers"
        }
        context.getSystemService(NotificationManager::class.java)
            .createNotificationChannel(channel)
    }
}
```

**POST_NOTIFICATIONS guard pattern** (NotificationHelper.kt lines 50-55):
```kotlin
fun notify(context: Context, id: Int, title: String, body: String) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        if (context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) return
    }
    // ...build and notify
}
```

**PendingIntent deep-linking** (copy from TransactionNotificationHelper.kt lines 95-120):
```kotlin
val intent = Intent(context, MainActivity::class.java).apply {
    action = ACTION_VIEW_TRANSACTION
    putExtra(EXTRA_TXID, txid)
    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
}
val pendingIntent = PendingIntent.getActivity(
    context, 0, intent,
    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
)
```

**Deltas for IncomingTxNotificationHelper:**
- New `CHANNEL_ID = "incoming_tx"` (distinct from `raventag_wallet` and `transaction_progress` per RESEARCH.md Runtime State Inventory).
- Channel must be created in `MainActivity.onCreate` alongside the existing two calls at lines 2448 and 2451.
- Notification payload must include txid so tap opens `TransactionDetailsScreen`.

---

### `wallet/subscription/ScripthashEvent.kt` (new, data model sealed class)

**Analog:** existing data classes in `RavencoinPublicNode.kt:39-127` (e.g. `AddressBalance`, `Utxo`, `TxHistoryEntry`).

**Style to match — Kotlin data classes with KDoc:**
```kotlin
data class AddressBalance(val confirmed: Long, val unconfirmed: Long) {
    val totalRvn: Double get() = (confirmed + unconfirmed) / 1e8
}
```

**Delta for ScripthashEvent.kt:**
Sealed class with three branches per RESEARCH.md §Pattern 1 lines 294-298:
```kotlin
sealed class ScripthashEvent {
    data class StatusChanged(val scripthash: String, val newStatus: String?) : ScripthashEvent()
    data object ConnectionLost : ScripthashEvent()
    data object AllNodesDown : ScripthashEvent()
}
```

---

### `wallet/health/NodeHealthMonitor.kt` (new, service, request-response + state)

**Analog:** `RavencoinPublicNode.ping()` at lines 208-216:
```kotlin
fun ping(): Boolean {
    for (server in SERVERS) {
        try {
            call(server, "server.version", listOf("RavenTag/1.0", "1.4"))
            return true
        } catch (_: Exception) {}
    }
    return false
}
```

**Deltas:**
- Extend with per-server health state: timestamp of last successful call, last error, quarantine-until (backed by `QuarantineDao`).
- Expose a `StateFlow<NodeHealth>` emitting `green/yellow/red` per D-12.
- Integrate with `TofuTrustManager` — on `Certificate mismatch` exception, set quarantine-until = `now + 1h` per D-11.

---

### Modifications to `wallet/WalletManager.kt` (D-15 biometric gate + HMAC integrity)

**Self-reference:** existing `encrypt()` / `decrypt()` / `getMnemonic()` / `storeSeed()` methods.

**What to add (per RESEARCH.md A9 + D-15):**
- Second Keystore AES key (or HKDF-derived) used as HMAC key; store `seed_hmac` and `mnemonic_hmac` in same SharedPrefs alongside the existing `seed_enc`/`mnemonic_enc` ciphertexts.
- On every `getMnemonic()` / `getSeed()`, compute HMAC over plaintext and compare against stored tag; throw `IntegrityException` on mismatch.
- Wrap `cipher.doFinal()` in a try/catch that distinguishes `KeyPermanentlyInvalidatedException` and surfaces via a typed exception (`KeystoreInvalidatedException`) that routes the user to restore.
- For mnemonic reveal flow only (D-15), route through `security/BiometricGate.kt` first.

---

### Modifications to `ui/screens/WalletScreen.kt` (cache banner, conn pill, battery chip, three-value row)

**Self-reference:** existing `WalletInfo` data class (lines 62-68), existing `electrumStatus: MainViewModel.ElectrumStatus` parameter (line 90), existing `LazyColumn` + `items` over `TxHistoryEntry` (import at line 56).

**What to add:**
- "Last updated HH:MM" banner bound to `WalletCacheDao.getLastRefreshedAt()` per D-04.
- Connection pill (green/yellow/red) bound to `NodeHealthMonitor.StateFlow`, hex colors from CONTEXT.md specifics line 160.
- "Pending" line showing `sum(unconfirmed incoming)` separate from spendable balance per D-24.
- Extended `TxHistoryEntry` row rendering with three fields (sent/cycled/fee) per D-19 — string format example from CONTEXT.md line 53.
- Battery-saver chip when `PowerManager.isPowerSaveMode()` per D-28.

---

## Shared Patterns

### Coroutine + Dispatchers.IO pattern
**Source:** `worker/WalletPollingWorker.kt:42` and dozens of `withContext(Dispatchers.IO)` usages in MainActivity.
**Apply to:** All new DAO calls, network calls, Keystore operations.
```kotlin
override suspend fun doWork(): Result = withContext(Dispatchers.IO) { ... }
```

### retryWithBackoff utility
**Source:** `android/app/src/main/java/io/raventag/app/utils/RetryUtils.kt:37-68`
**Signature:**
```kotlin
suspend fun <T> retryWithBackoff(
    maxAttempts: Int = 5,          // Phase 20 D-02 default
    initialDelayMs: Long = 1000L,  // 1s base delay
    backoffMultiplier: Double = 2.0,
    block: suspend () -> T
): T
```
**Transient error detection** (lines 86-99): `SocketTimeoutException`, `UnknownHostException`, `IOException` with message containing `timeout|connection|network|temporary`.
**Apply to:** D-21 consolidation retries, D-25 rebroadcast retries, `FeeEstimator` network calls, `NodeHealthMonitor` node probes, `SubscriptionManager.start()` per-server failover.

### TOFU TLS trust manager (MUST reuse, do not duplicate)
**Source:** `RavencoinPublicNode.kt:1612-1652` (currently `private class` — planner should promote to `internal class` in a shared location such as `wallet/TofuTrustManager.kt`).
**Apply to:** `SubscriptionManager` (long-lived socket) and all existing one-shot RPC paths.

### SQLite DAO pattern (singleton object + helper + synchronized init)
**Source:** `security/TofuFingerprintDao.kt` (entire file).
**Apply to:** All five new DAOs (`WalletCacheDao`, `ReservedUtxoDao`, `TxHistoryDao`, `PendingConsolidationDao`, `QuarantineDao`). Keep database file co-located at `wallet_reliability.db` to simplify transactional cross-table queries (e.g. Pattern 3 Example 2 rawQuery joining reserved_utxos with tx_history).
**Durability:** apply `PRAGMA synchronous=FULL; PRAGMA journal_mode=WAL;` in `onCreate` per RESEARCH.md Pitfall 6.

### Notification channel + POST_NOTIFICATIONS guard
**Source:** `worker/NotificationHelper.kt` (lines 28-66).
**Apply to:** New `IncomingTxNotificationHelper`. Channel creation must be invoked from `MainActivity.onCreate` exactly like lines 2448/2451.

### EncryptedSharedPreferences / MasterKey (D-15 HMAC key storage)
**Source:** `security/AdminKeyStorage.kt:34-48` and `MainActivity.kt:2471-2484`.
**Apply to:** `WalletManager` extension for seed HMAC column — store tag in the SAME `raventag_wallet` prefs file (per RESEARCH.md Runtime State Inventory line 462) under new keys `KEY_SEED_HMAC` / `KEY_MNEMONIC_HMAC`. The AES-GCM Keystore key used for the HMAC is separate from the mnemonic encryption key.

### BiometricManager availability probe
**Source:** `MainActivity.kt:2558-2567`. Reuse verbatim inside `BiometricGate`.

### WorkManager periodic scheduling
**Source:** `MainActivity.kt:2457-2461`:
```kotlin
WorkManager.getInstance(this).enqueueUniquePeriodicWork(
    "wallet_poll",
    ExistingPeriodicWorkPolicy.UPDATE,
    PeriodicWorkRequestBuilder<WalletPollingWorker>(15, TimeUnit.MINUTES).build()
)
```
**Apply to:** Keep the existing `wallet_poll` name for the extended polling worker. Use **different** unique names for new OneTime workers: `"rebroadcast-<txid>"` per RESEARCH.md Code Example 4 (avoids collision with Phase 20's `wallet_polling_worker` per Runtime State Inventory line 461).

### MainActivity channel-creation wiring block
**Source:** `MainActivity.kt:2447-2461`. The new `IncomingTxNotificationHelper.createChannel(this)` must be appended here.

## No Analog Found

| File | Role | Data Flow | Action |
|------|------|-----------|--------|
| `wallet/subscription/SubscriptionManager.kt` (reader-loop coroutine framing for id-matched responses) | long-lived socket | event-driven | Partially analog (see above); the reader-loop framing with `ConcurrentHashMap<Int, CompletableDeferred>` has NO existing analog. Use RESEARCH.md Code Example 1 (lines 545-589) as the canonical reference. |
| `security/MnemonicExporter.kt` (zero-fill char[] memory discipline, BIP39 re-validation on import, BackupRequiredException gate) | security service | transform | No existing zero-fill pattern in codebase. Follow RESEARCH.md §Pattern 2 + Pitfall 7 normalization (`input.trim().split(Regex("\\s+"))`). Combine with existing `WalletManager.validateMnemonic()` (line ~818) which planner must audit. |

## Metadata

**Analog search scope:**
- `android/app/src/main/java/io/raventag/app/wallet/`
- `android/app/src/main/java/io/raventag/app/security/`
- `android/app/src/main/java/io/raventag/app/worker/`
- `android/app/src/main/java/io/raventag/app/network/`
- `android/app/src/main/java/io/raventag/app/utils/`
- `android/app/src/main/java/io/raventag/app/ui/screens/`
- `android/app/src/main/java/io/raventag/app/MainActivity.kt`

**Files scanned:** 18 Kotlin sources read (full or targeted ranges).
**Pattern extraction date:** 2026-04-18
