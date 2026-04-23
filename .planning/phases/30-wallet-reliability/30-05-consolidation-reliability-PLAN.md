---
id: 30-05-consolidation-reliability
phase: 30
plan: 05
type: execute
wave: 2
depends_on:
  - 30-02-wallet-cache-db-daos
  - 30-03-scripthash-subscription
  - 30-04-fee-estimation
files_modified:
  - android/app/src/main/java/io/raventag/app/wallet/WalletManager.kt
  - android/app/src/main/java/io/raventag/app/worker/RebroadcastWorker.kt
  - android/app/src/main/java/io/raventag/app/MainActivity.kt
autonomous: true
requirements:
  - WALLET-SEND
  - WALLET-UTXO
threat_refs:
  - T-30-UTXO
  - T-30-NET

must_haves:
  truths:
    - "Every successful broadcast in sendRvnLocal inserts its consumed UTXOs into reserved_utxos BEFORE the ViewModel emits UI state (Pitfall 4)"
    - "Displayed spendable balance = sum(confirmed UTXOs) - sum(reserved UTXOs) (D-03 + D-20)"
    - "On next refresh/foreground, any tx found confirmed in history → ReservedUtxoDao.releaseFor(txid) + PendingConsolidationDao.clear(txid)"
    - "On app startup, stale reservations older than 48h are pruned (Pitfall 6)"
    - "Consolidation failures persist a row in pending_consolidations and retry via retryWithBackoff (D-21) without blocking new sends"
    - "Stuck outgoing txs (N>30min unconfirmed) are auto-rebroadcast via RebroadcastWorker per 30/60/120/240/480 min ladder, capped at 5 attempts (D-25)"
    - "Consolidation-always-broadcasts rule (D-27) is preserved regardless of power-save"
  artifacts:
    - path: "android/app/src/main/java/io/raventag/app/worker/RebroadcastWorker.kt"
      provides: "OneTimeWorkRequest worker with attempt counter + reschedule chain"
      exports: ["RebroadcastWorker"]
    - path: "android/app/src/main/java/io/raventag/app/wallet/WalletManager.kt"
      provides: "extended sendRvnLocal inserting reservations + scheduling rebroadcast; reconcileReservations helper"
  key_links:
    - from: "WalletManager.sendRvnLocal (post-broadcast)"
      to: "ReservedUtxoDao.reserve + PendingConsolidationDao.upsert + RebroadcastWorker schedule"
      via: "in-process sequential calls BEFORE returning to ViewModel"
      pattern: "ReservedUtxoDao\\.reserve"
    - from: "WalletScreen refresh"
      to: "reconcileReservations(confirmedTxids) + pruneOlderThan"
      via: "ViewModel calls helper"
      pattern: "reconcileReservations"
    - from: "MainActivity.onCreate"
      to: "ReservedUtxoDao.pruneOlderThan(now - 48h)"
      via: "single startup call"
      pattern: "pruneOlderThan"
---

<objective>
Wire the new persistence DAOs from plan 30-02 into the existing send/consolidation flow in `WalletManager.sendRvnLocal` (and the asset-transfer equivalents), add a `RebroadcastWorker` for D-25 stuck-tx auto-rebroadcast, and add a reconciliation helper that cleans up reserved UTXOs when a submitted tx confirms.

**Hard constraint (D-17): do NOT redesign consolidation semantics.** The existing `RavencoinTxBuilder.buildAndSignMultiAddressSend` already emits the atomic send+sweep-to-fresh-address tx. This plan ONLY:
- Inserts `reserved_utxos` rows post-broadcast.
- Persists `pending_consolidations` rows on broadcast failure (with retryWithBackoff in-flight for 5 attempts, then DB-flag for next refresh).
- Schedules a `RebroadcastWorker` chain when a submitted tx is still unconfirmed after 30 min.
- Calls `ReservedUtxoDao.pruneOlderThan` at startup (Pitfall 6 crash recovery).
- Calls `ReservedUtxoDao.releaseFor(txid) + PendingConsolidationDao.clear(txid)` whenever a previously-submitted tx is observed confirmed on refresh.

Purpose: reliability for WALLET-SEND + WALLET-UTXO end-to-end. The user never sees a "phantom unspent" UTXO after a send; stuck txs self-heal.
</objective>

<execution_context>
@$HOME/.claude/get-shit-done/workflows/execute-plan.md
@$HOME/.claude/get-shit-done/templates/summary.md
</execution_context>

<context>
@.planning/phases/30-wallet-reliability/30-CONTEXT.md
@.planning/phases/30-wallet-reliability/30-RESEARCH.md
@.planning/phases/30-wallet-reliability/30-PATTERNS.md
@.planning/phases/30-wallet-reliability/30-VALIDATION.md
@android/app/src/main/java/io/raventag/app/wallet/WalletManager.kt
@android/app/src/main/java/io/raventag/app/worker/WalletPollingWorker.kt
@android/app/src/main/java/io/raventag/app/utils/RetryUtils.kt
@android/app/src/main/java/io/raventag/app/wallet/cache/ReservedUtxoDao.kt
@android/app/src/main/java/io/raventag/app/wallet/cache/PendingConsolidationDao.kt

<interfaces>
From plan 30-02:
```kotlin
object ReservedUtxoDao {
    data class ReservedUtxo(val txidIn: String, val vout: Int, val valueSat: Long, val submittedTxid: String, val submittedAt: Long)
    fun reserve(entries: List<ReservedUtxo>)
    fun releaseFor(submittedTxid: String)
    fun sumReservedSat(): Long
    fun pruneOlderThan(thresholdMillis: Long)
    fun all(): List<ReservedUtxo>
}
object PendingConsolidationDao {
    data class PendingConsolidation(val submittedTxid: String, val submittedAt: Long, val lastRetryAt: Long?, val retryCount: Int, val lastError: String?)
    fun upsert(p: PendingConsolidation)
    fun clear(submittedTxid: String)
    fun all(): List<PendingConsolidation>
}
```

From plan 30-04:
```kotlin
class FeeEstimator(node: RavencoinPublicNode) { suspend fun estimateSatPerKb(targetBlocks: Int = 6): Long }
```

**Existing WalletManager.kt structure** (verify at execution time):
- `fun sendRvnLocal(toAddress: String, amountSat: Long, feeRateSatPerByte: Long): String` (returns txid)
- `fun getTransactionBroadcaster(): RavencoinPublicNode` or similar
- Internal helper that accumulates the consumed UTXOs before signing — identify its name during execution so we can capture the list for reservation.

**Existing WalletPollingWorker** already uses `retryWithBackoff`-style resilience (see PATTERNS.md §265). We extend, not rewrite.

**Existing TransactionNotificationHelper** pattern (Phase 20) is used for send-progress; do NOT duplicate.
</interfaces>
</context>

<tasks>

<task type="auto" tdd="true">
  <name>Task 1: Extend WalletManager.sendRvnLocal to reserve UTXOs + persist pending flag; add reconcileReservations helper</name>
  <files>
    android/app/src/main/java/io/raventag/app/wallet/WalletManager.kt,
    android/app/src/main/java/io/raventag/app/MainActivity.kt
  </files>
  <read_first>
    @.planning/phases/30-wallet-reliability/30-CONTEXT.md#L42-L56,
    @.planning/phases/30-wallet-reliability/30-RESEARCH.md#L416-L437,
    @.planning/phases/30-wallet-reliability/30-RESEARCH.md#L497-L521,
    @.planning/phases/30-wallet-reliability/30-PATTERNS.md#L401-L444,
    @android/app/src/main/java/io/raventag/app/wallet/WalletManager.kt,
    @android/app/src/main/java/io/raventag/app/wallet/cache/ReservedUtxoDao.kt,
    @android/app/src/main/java/io/raventag/app/wallet/cache/PendingConsolidationDao.kt
  </read_first>
  <behavior>
    After every successful broadcast inside `sendRvnLocal` (or any external-address send path):
    1. Collect the exact list of consumed UTXOs (txid_in, vout, value_sat) that the builder just spent.
    2. Call `ReservedUtxoDao.reserve(listOf(... for each consumed ...))` with `submittedTxid = <broadcast txid>` and `submittedAt = System.currentTimeMillis()`.
    3. Call `PendingConsolidationDao.upsert(PendingConsolidation(submittedTxid, submittedAt, null, 0, null))`.
    4. Schedule `RebroadcastWorker` with `setInitialDelay(30, MINUTES)` keyed on `"rebroadcast-$txid"` (unique) passing `txid` and `raw_hex` as `inputData`.

    On broadcast FAILURE (post-retryWithBackoff exhaustion):
    1. Do NOT insert into reserved_utxos (nothing was broadcast).
    2. Call `PendingConsolidationDao.upsert(PendingConsolidation(submittedTxid="FAILED-$timestamp", submittedAt=now, lastRetryAt=now, retryCount=5, lastError=throwable.message))`.
    3. Rethrow to caller so UI shows error banner (Phase 20).

    `reconcileReservations(confirmedTxids: Set<String>, mempoolTxids: Set<String>)` helper on WalletManager:
    - For each `submitted_txid` in `ReservedUtxoDao.all()` grouped: if `confirmedTxids.contains(submittedTxid)` → `ReservedUtxoDao.releaseFor(submittedTxid)` + `PendingConsolidationDao.clear(submittedTxid)`.
    - If the submittedTxid is NOT in confirmedTxids AND NOT in mempoolTxids AND its `submittedAt < now - 48h` → also release (it's effectively dropped — Pitfall 6 + 48h stale prune).
    - Returns the list of released txids (UI may emit a consolidation-confirmed banner per UI-SPEC).

    Startup:
    - `ReservedUtxoDao.pruneOlderThan(System.currentTimeMillis() - 48L*3600_000L)` — called from `MainActivity.onCreate` once, after `WalletReliabilityDb.init(this)`.
  </behavior>
  <action>
    **WalletManager.kt edits**:

    1. Read the file fully. Locate `sendRvnLocal` (or the primary RVN-send entry used by `SendRvnScreen`). Identify the exact point after `broadcast(rawHex)` returns the txid.

    2. Immediately AFTER `broadcast` returns, BEFORE returning to the caller, insert:
    ```kotlin
    // Reserved-UTXO + pending-consolidation bookkeeping (D-20, D-21).
    val now = System.currentTimeMillis()
    val reserved = consumedInputs.map {
        io.raventag.app.wallet.cache.ReservedUtxoDao.ReservedUtxo(
            txidIn = it.txid,
            vout = it.vout,
            valueSat = it.value,
            submittedTxid = broadcastTxid,
            submittedAt = now
        )
    }
    io.raventag.app.wallet.cache.ReservedUtxoDao.reserve(reserved)
    io.raventag.app.wallet.cache.PendingConsolidationDao.upsert(
        io.raventag.app.wallet.cache.PendingConsolidationDao.PendingConsolidation(
            submittedTxid = broadcastTxid, submittedAt = now,
            lastRetryAt = null, retryCount = 0, lastError = null
        )
    )
    // D-25 auto-rebroadcast in 30 minutes if still unconfirmed
    io.raventag.app.worker.RebroadcastWorker.schedule(
        context = context,
        txid = broadcastTxid,
        rawHex = rawHex,
        attempt = 0,
        initialDelayMinutes = 30L
    )
    ```

    Where `consumedInputs: List<Utxo>` is the already-tracked input list inside sendRvnLocal. If the code currently doesn't track it explicitly, capture it at the input-selection step. DO NOT attempt a redesign — if the variable is not named `consumedInputs`, rename this snippet to match the actual variable. Use the Read tool at execution time to find the correct variable name.

    3. Wrap the broadcast call itself with `retryWithBackoff(maxAttempts = 5, initialDelayMs = 1000L, backoffMultiplier = 2.0)` — if it is not already wrapped (Phase 20 established the pattern). If already wrapped at a higher call level in the ViewModel, leave as is and do NOT double-wrap.

    4. Add a new top-level suspend function on WalletManager (outside `sendRvnLocal`):
    ```kotlin
    /**
     * D-20/D-21 reconciliation: call from refresh flows after fetching confirmed + mempool
     * history. Returns the submittedTxids whose reservations were just released.
     */
    suspend fun reconcileReservations(
        confirmedTxids: Set<String>,
        mempoolTxids: Set<String>
    ): List<String> = withContext(kotlinx.coroutines.Dispatchers.IO) {
        val allReserved = io.raventag.app.wallet.cache.ReservedUtxoDao.all()
        val bySubmitted = allReserved.groupBy { it.submittedTxid }
        val now = System.currentTimeMillis()
        val released = mutableListOf<String>()
        for ((subTxid, rows) in bySubmitted) {
            val confirmed = confirmedTxids.contains(subTxid)
            val inMempool = mempoolTxids.contains(subTxid)
            val stale = rows.first().submittedAt < (now - 48L*3600_000L)
            if (confirmed || (!inMempool && stale)) {
                io.raventag.app.wallet.cache.ReservedUtxoDao.releaseFor(subTxid)
                io.raventag.app.wallet.cache.PendingConsolidationDao.clear(subTxid)
                released += subTxid
            }
        }
        released
    }
    ```

    **MainActivity.kt edit**:
    Right after the line added in plan 30-02 (`WalletReliabilityDb.init(this)`), add:
    ```kotlin
    io.raventag.app.wallet.cache.ReservedUtxoDao.pruneOlderThan(
        System.currentTimeMillis() - 48L * 3600_000L
    )
    ```

    Em-dash audit: `! grep -P '\u2014' android/app/src/main/java/io/raventag/app/wallet/WalletManager.kt android/app/src/main/java/io/raventag/app/MainActivity.kt` — audit the touched regions specifically by grepping for em dashes after the edit.

    Never block new sends on a pending consolidation (D-21): do NOT add any gate in `sendRvnLocal` that checks `PendingConsolidationDao.all().isNotEmpty()`. Throughput > strict order.
  </action>
  <verify>
    <automated>cd android && ./gradlew :app:testConsumerDebugUnitTest --tests "io.raventag.app.wallet.cache.ReservedUtxoDaoTest" --tests "*WalletManagerMnemonicTest*" -i 2>&1 | tail -30</automated>
  </verify>
  <acceptance_criteria>
    - `grep -n "ReservedUtxoDao.reserve" android/app/src/main/java/io/raventag/app/wallet/WalletManager.kt` returns at least one line inside or right after sendRvnLocal.
    - `grep -n "PendingConsolidationDao.upsert" android/app/src/main/java/io/raventag/app/wallet/WalletManager.kt` returns at least one line.
    - `grep -n "RebroadcastWorker.schedule" android/app/src/main/java/io/raventag/app/wallet/WalletManager.kt` returns at least one line.
    - `grep -q "fun reconcileReservations" android/app/src/main/java/io/raventag/app/wallet/WalletManager.kt`
    - `grep -q "48L\\s*\\*\\s*3600_000L\\|48L\\*3600_000L" android/app/src/main/java/io/raventag/app/wallet/WalletManager.kt`
    - `grep -q "ReservedUtxoDao.pruneOlderThan" android/app/src/main/java/io/raventag/app/MainActivity.kt`
    - `! grep -P '\u2014' android/app/src/main/java/io/raventag/app/wallet/WalletManager.kt android/app/src/main/java/io/raventag/app/MainActivity.kt`
    - Wave 0 test `ReservedUtxoDaoTest.insert_on_broadcast*` remains GREEN (regression guard); reconcile-reservations path covered by existing `cleanup_on_confirm` test semantics.
    - `cd android && ./gradlew :app:assembleConsumerDebug` exits 0.
  </acceptance_criteria>
  <done>sendRvnLocal reserves UTXOs, records pending consolidation, schedules rebroadcast; reconcile helper released txids; startup pruning wired. Build passes.</done>
</task>

<task type="auto" tdd="true">
  <name>Task 2: Create RebroadcastWorker with 30/60/120/240/480 min backoff and 5-attempt cap</name>
  <files>
    android/app/src/main/java/io/raventag/app/worker/RebroadcastWorker.kt
  </files>
  <read_first>
    @.planning/phases/30-wallet-reliability/30-RESEARCH.md#L667-L703,
    @.planning/phases/30-wallet-reliability/30-RESEARCH.md#L428-L431,
    @.planning/phases/30-wallet-reliability/30-CONTEXT.md#L65-L69,
    @.planning/phases/30-wallet-reliability/30-PATTERNS.md#L225-L265,
    @android/app/src/main/java/io/raventag/app/worker/WalletPollingWorker.kt,
    @android/app/src/main/java/io/raventag/app/wallet/cache/PendingConsolidationDao.kt,
    @android/app/src/main/java/io/raventag/app/wallet/cache/ReservedUtxoDao.kt
  </read_first>
  <behavior>
    `RebroadcastWorker` is a `CoroutineWorker` scheduled as a `OneTimeWorkRequest` with unique work name `rebroadcast-<txid>`. Each run:
    1. Read `txid`, `raw_hex`, `attempt` from inputData.
    2. If `attempt >= 5` → `Result.success()` AND mark pending_consolidation lastError="cap reached" (so UI plan 30-08 can surface the persistent-failure warning per D-21 copy `Pending consolidation not confirmed. Funds may be on an older address.`).
    3. Check confirmation: query `RavencoinPublicNode` for the submitted txid's status (via the existing `getTransactionHistory`-style call). If confirmed or in mempool with `confirmations > 0` → `ReservedUtxoDao.releaseFor(txid)` + `PendingConsolidationDao.clear(txid)` + `Result.success()` (no reschedule).
    4. Else: attempt `node.broadcast(rawHex)` wrapped in try/catch. Ignore failure (silent per D-25).
    5. Schedule next `OneTimeWorkRequest` with `setInitialDelay(ladder[attempt], MINUTES)` where ladder = `[30, 60, 120, 240, 480]` — getOrElse(attempt) { 480 }. Use `ExistingWorkPolicy.REPLACE` so the latest schedule wins.
    6. Return `Result.success()` always (not `retry()` — WorkManager retries with its own exp backoff which is opaque; we schedule explicitly to hit the D-25 ladder).

    Static companion helper `schedule(context, txid, rawHex, attempt, initialDelayMinutes)` used by plan 30-05 Task 1 and by the worker itself.

    D-27: consolidation ALWAYS broadcasts. Do NOT add WorkManager Constraints that would defer on power-save. The only constraint is `NetworkType.CONNECTED` so we don't waste cycles offline.
  </behavior>
  <action>
    Create `android/app/src/main/java/io/raventag/app/worker/RebroadcastWorker.kt`:

    ```kotlin
    package io.raventag.app.worker

    import android.content.Context
    import androidx.work.Constraints
    import androidx.work.CoroutineWorker
    import androidx.work.Data
    import androidx.work.ExistingWorkPolicy
    import androidx.work.NetworkType
    import androidx.work.OneTimeWorkRequestBuilder
    import androidx.work.WorkManager
    import androidx.work.WorkerParameters
    import androidx.work.workDataOf
    import io.raventag.app.wallet.RavencoinPublicNode
    import io.raventag.app.wallet.cache.PendingConsolidationDao
    import io.raventag.app.wallet.cache.ReservedUtxoDao
    import kotlinx.coroutines.Dispatchers
    import kotlinx.coroutines.withContext
    import java.util.concurrent.TimeUnit

    class RebroadcastWorker(
        ctx: Context,
        params: WorkerParameters
    ) : CoroutineWorker(ctx, params) {

        override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
            val txid = inputData.getString(KEY_TXID) ?: return@withContext Result.failure()
            val rawHex = inputData.getString(KEY_RAW_HEX) ?: return@withContext Result.failure()
            val attempt = inputData.getInt(KEY_ATTEMPT, 0)

            if (attempt >= MAX_ATTEMPTS) {
                PendingConsolidationDao.upsert(
                    PendingConsolidationDao.PendingConsolidation(
                        submittedTxid = txid,
                        submittedAt = System.currentTimeMillis(),
                        lastRetryAt = System.currentTimeMillis(),
                        retryCount = attempt,
                        lastError = "rebroadcast cap reached"
                    )
                )
                return@withContext Result.success()
            }

            val node = RavencoinPublicNode(applicationContext)

            // Confirmation check: use the minimum viable RPC. A single call to get_history for
            // a wallet-tracked scripthash would require the address; the scripthash subscription
            // has a dedicated entry at `blockchain.transaction.get(txid, verbose=true)` that
            // returns confirmation count if the server supports it. If that's not wired yet,
            // fall back to attempting a second broadcast (idempotent — double-spend is
            // rejected by ElectrumX as expected).
            val confirmed = try {
                val result = node.callElectrumRawOrNull("blockchain.transaction.get", listOf(txid, true))
                val confirms = result?.asJsonObject?.get("confirmations")?.takeIf { !it.isJsonNull }?.asInt ?: 0
                confirms > 0
            } catch (_: Exception) { false }

            if (confirmed) {
                ReservedUtxoDao.releaseFor(txid)
                PendingConsolidationDao.clear(txid)
                return@withContext Result.success()
            }

            // Rebroadcast silently per D-25
            try { node.broadcast(rawHex) } catch (_: Exception) { /* silent */ }

            // Schedule next attempt
            val nextDelayMinutes = DELAY_LADDER_MINUTES.getOrElse(attempt) { 480L }
            schedule(
                context = applicationContext,
                txid = txid,
                rawHex = rawHex,
                attempt = attempt + 1,
                initialDelayMinutes = nextDelayMinutes
            )
            PendingConsolidationDao.upsert(
                PendingConsolidationDao.PendingConsolidation(
                    submittedTxid = txid,
                    submittedAt = System.currentTimeMillis(),
                    lastRetryAt = System.currentTimeMillis(),
                    retryCount = attempt + 1,
                    lastError = null
                )
            )
            Result.success()
        }

        companion object {
            const val KEY_TXID = "txid"
            const val KEY_RAW_HEX = "raw_hex"
            const val KEY_ATTEMPT = "attempt"
            const val MAX_ATTEMPTS = 5
            // D-25 ladder: delays AFTER attempt N (attempt 0 = first scheduled 30 min later)
            val DELAY_LADDER_MINUTES: List<Long> = listOf(30L, 60L, 120L, 240L, 480L)

            /** Public entry used by WalletManager after a successful broadcast. */
            fun schedule(
                context: Context,
                txid: String,
                rawHex: String,
                attempt: Int,
                initialDelayMinutes: Long
            ) {
                val req = OneTimeWorkRequestBuilder<RebroadcastWorker>()
                    .setInitialDelay(initialDelayMinutes, TimeUnit.MINUTES)
                    .setConstraints(
                        Constraints.Builder()
                            .setRequiredNetworkType(NetworkType.CONNECTED)
                            .build()
                    )
                    .setInputData(
                        workDataOf(
                            KEY_TXID to txid,
                            KEY_RAW_HEX to rawHex,
                            KEY_ATTEMPT to attempt
                        )
                    )
                    .build()
                WorkManager.getInstance(context)
                    .enqueueUniqueWork("rebroadcast-$txid", ExistingWorkPolicy.REPLACE, req)
            }
        }
    }
    ```

    **RavencoinPublicNode helper**: the worker calls `node.callElectrumRawOrNull(method, params)` which must be present. If it is not, add a tiny wrapper in RavencoinPublicNode.kt (same style as `subscribeScripthashRpc`):
    ```kotlin
    /** Low-level: attempts the RPC call against the failover pool; returns null on any exception. */
    fun callElectrumRawOrNull(method: String, params: List<Any?>): com.google.gson.JsonElement? = try {
        callWithFailover(method, params)
    } catch (_: Exception) { null }
    ```
    Add this only if not already present.

    Em-dash audit: `! grep -P '\u2014' android/app/src/main/java/io/raventag/app/worker/RebroadcastWorker.kt`.
  </action>
  <verify>
    <automated>cd android && ./gradlew :app:assembleConsumerDebug -q 2>&1 | tail -15</automated>
  </verify>
  <acceptance_criteria>
    - `test -f android/app/src/main/java/io/raventag/app/worker/RebroadcastWorker.kt`
    - `grep -q "class RebroadcastWorker" android/app/src/main/java/io/raventag/app/worker/RebroadcastWorker.kt`
    - `grep -q "MAX_ATTEMPTS = 5" android/app/src/main/java/io/raventag/app/worker/RebroadcastWorker.kt`
    - `grep -q "listOf(30L, 60L, 120L, 240L, 480L)" android/app/src/main/java/io/raventag/app/worker/RebroadcastWorker.kt`
    - `grep -q "fun schedule" android/app/src/main/java/io/raventag/app/worker/RebroadcastWorker.kt`
    - `grep -q "rebroadcast-\\\$txid\\|rebroadcast-" android/app/src/main/java/io/raventag/app/worker/RebroadcastWorker.kt`
    - `grep -q "NetworkType.CONNECTED" android/app/src/main/java/io/raventag/app/worker/RebroadcastWorker.kt`
    - `grep -q "ExistingWorkPolicy.REPLACE" android/app/src/main/java/io/raventag/app/worker/RebroadcastWorker.kt`
    - `! grep -P '\u2014' android/app/src/main/java/io/raventag/app/worker/RebroadcastWorker.kt`
    - `cd android && ./gradlew :app:assembleConsumerDebug` exits 0.
  </acceptance_criteria>
  <done>RebroadcastWorker schedules itself across the 30/60/120/240/480 min ladder, caps at 5, clears reservations on confirmation, is constrained to network-connected only (D-27 — no power-save constraint). No em dashes.</done>
</task>

</tasks>

<threat_model>
## Trust Boundaries

| Boundary | Description |
|----------|-------------|
| user send → UTXO reservation | local DB write post-broadcast; must survive process kill (WAL + FULL sync). |
| worker → ElectrumX | TLS + TOFU (inherited); rebroadcast is silent. |

## STRIDE Threat Register

| Threat ID | Category | Component | Disposition | Mitigation Plan |
|-----------|----------|-----------|-------------|-----------------|
| T-30-UTXO-04 | Tampering | Crash mid-reserve leaves orphan reservation (Pitfall 6) | mitigate | PRAGMA FULL+WAL (plan 30-02); 48h startup prune; reconcileReservations on every refresh. |
| T-30-UTXO-05 | Tampering | User double-sends because UI shows old UTXO before broadcast ACK (Pitfall 4) | mitigate | Reserve BEFORE returning from sendRvnLocal to ViewModel; UI reads post-reservation balance. |
| T-30-NET-07 | Denial of Service | Rebroadcast storm to public nodes | mitigate | 5-attempt cap + exp ladder (D-25); unique work name per txid; `ExistingWorkPolicy.REPLACE` prevents duplicate chains. |
| T-30-UTXO-06 | Tampering | Reorg drops tx, reserved row never cleared | mitigate | 48h stale-prune + reconcile against mempool+confirmed on refresh. Worst case: user sees slightly-low balance for up to 48h — recoverable. |
| T-30-UTXO-07 | Elevation of Privilege | Attacker forces reserve without broadcast | accept | Attacker with app-process access already owns the wallet (StrongBox out of scope here). App-internal state manipulation requires root; not in threat model. |

ASVS V7.4 (durability via WAL), V6.4 (idempotent broadcast retries — double-spend rejection is server-enforced).
</threat_model>

<verification>
- `cd android && ./gradlew :app:assembleConsumerDebug` exits 0.
- Wave 0 reservation tests remain GREEN.
- `grep -rn "ReservedUtxoDao.reserve" android/app/src/main/java/io/raventag/app/wallet/WalletManager.kt` returns at least one call.
- `grep -n "RebroadcastWorker.schedule" android/app/src/main/java/io/raventag/app/wallet/WalletManager.kt` returns at least one call.
- `grep -n "pruneOlderThan" android/app/src/main/java/io/raventag/app/MainActivity.kt` returns one call.
- No em dashes in any touched file.
</verification>

<success_criteria>
- Existing consolidation tx bytes are UNCHANGED (D-17 preservation).
- Every successful send leaves a reservation row and a pending_consolidation row + an enqueued `rebroadcast-<txid>` work item.
- Stale reservations prune at startup.
- Reconciliation helper callable from UI (used in plan 30-08).
- RebroadcastWorker caps at 5 attempts across the documented ladder.
</success_criteria>

<output>
Create `.planning/phases/30-wallet-reliability/30-05-SUMMARY.md`:
- The exact line where reservation logic was inserted into sendRvnLocal (with the surrounding function signature).
- The variable name used for "consumed inputs" in the existing code (for future audits).
- Hand-off to plan 30-08: WalletScreen ViewModel must call `reconcileReservations(confirmedTxids, mempoolTxids)` on every successful refresh and surface the "consolidation confirmed" snackbar for any released txid.
- Hand-off to plan 30-09: TxHistory display must filter `is_self=true + cycled_sat>0 + sent_sat=0` as a pure-consolidation row (UI-SPEC §Tx history row, self-transfer).
</output>
