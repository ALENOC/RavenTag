---
id: 30-09-tx-history-3value
phase: 30
plan: 09
type: execute
wave: 3
depends_on:
  - 30-02-wallet-cache-db-daos
  - 30-05-consolidation-reliability
  - 30-08-walletscreen-refresh-and-receive-ux
files_modified:
  - android/app/src/main/java/io/raventag/app/ui/screens/WalletScreen.kt
  - android/app/src/main/java/io/raventag/app/ui/screens/TransactionDetailsScreen.kt
  - android/app/src/main/java/io/raventag/app/wallet/RavencoinPublicNode.kt
  - android/app/src/main/java/io/raventag/app/wallet/cache/TxHistoryDao.kt
  - android/app/src/consumer/java/io/raventag/app/config/AppConfig.kt
  - android/app/src/brand/java/io/raventag/app/config/AppConfig.kt
  - android/app/src/main/java/io/raventag/app/ui/theme/AppStrings.kt
autonomous: true
requirements:
  - WALLET-BAL
  - WALLET-SEND
  - WALLET-UTXO
  - WALLET-RECV
threat_refs:
  - T-30-UTXO
ui_spec_refs:
  - "UI-SPEC §Tx history three-value row (D-19) — outgoing + self-transfer"
  - "UI-SPEC §Tx details screen (D-19) — three-value breakdown + View on explorer"
  - "UI-SPEC §Copywriting Contract — Empty states, Primary CTAs (Load more), Error states"
  - "UI-SPEC §Implementation Notes — Em-dash audit"

must_haves:
  truths:
    - "WalletScreen TxCard outgoing row renders three lines in the right column: Sent (NotAuthenticRed, SemiBold, sign prefix `-`), Cycled (AuthenticGreen, labelSmall), Fee (RavenMuted, labelSmall), separator `·`, with 2dp gap between value lines and 6dp gap before the timestamp row (D-19)"
    - "Self-transfer (consolidation) rows render a single line `Cycled X RVN · Fee Y RVN` with Icons.Default.Autorenew in RavenOrange; no Sent line (D-19)"
    - "Incoming tx rows preserve the existing single-amount layout unchanged"
    - "Confirmation dot color is red at 0 conf, amber (0xFFF59E0B) at 1-5, AuthenticGreen at >=6 confs (D-08)"
    - "A 'Load more' button (RavenOrange) loads the next 20 rows by calling TxHistoryDao.page(limit=20, offset=currentCount); on empty local result, falls back to RavencoinPublicNode.getHistoryPaged (D-23)"
    - "The empty-state composable renders 'No transactions yet' / 'Nessuna transazione' heading and the UI-SPEC body verbatim when tx_history row count is zero"
    - "TransactionDetailsScreen shows three labeled rows (Sent / Cycled / Fee) with icons and tap-to-copy addresses for outgoing transactions; incoming transactions keep the existing single-amount breakdown"
    - "TransactionDetailsScreen has a 'View on explorer' OutlinedButton (RavenOrange) that opens Intent.ACTION_VIEW with AppConfig.EXPLORER_URL + txid"
    - "AppConfig declares EXPLORER_URL (both consumer and brand flavors) as a const String pointing to a Ravencoin block explorer with /tx/ path"
    - "RavencoinPublicNode.getHistoryPaged(address, offset, limit) exposes paged tx history via blockchain.scripthash.get_history with server-returned list sliced client-side"
    - "TxHistoryDao exposes getPage(offset, limit) that returns rows ordered by (height DESC, timestamp DESC) and a upsertAll that REPLACE-conflicts on txid PK"
    - "cycled_sat = sum(outputs where output.address == changeAddress); sent_sat = sum(outputs where output.address != changeAddress && direction = outgoing); fee_sat = previously computed by the send path"
    - "All new user-facing strings exist in stringsEn AND stringsIt verbatim from UI-SPEC Copywriting Contract; zero U+2014 em-dashes anywhere"
  artifacts:
    - path: "android/app/src/main/java/io/raventag/app/ui/screens/WalletScreen.kt"
      provides: "TxCard outgoing three-value row rewrite + self-transfer variant + empty-state composable + Load more button wiring"
    - path: "android/app/src/main/java/io/raventag/app/ui/screens/TransactionDetailsScreen.kt"
      provides: "Three-value breakdown for outgoing txs + View on explorer OutlinedButton"
    - path: "android/app/src/main/java/io/raventag/app/wallet/RavencoinPublicNode.kt"
      provides: "getHistoryPaged(address, offset, limit) + computeCycledSat helper + computeSentSat helper"
      exports: ["getHistoryPaged", "computeCycledSat", "computeSentSat"]
    - path: "android/app/src/main/java/io/raventag/app/wallet/cache/TxHistoryDao.kt"
      provides: "getPage(offset, limit) + upsertAll convenience alias (if not already covered by plan 30-02's upsert)"
    - path: "android/app/src/consumer/java/io/raventag/app/config/AppConfig.kt"
      provides: "EXPLORER_URL const"
    - path: "android/app/src/brand/java/io/raventag/app/config/AppConfig.kt"
      provides: "EXPLORER_URL const (same value as consumer)"
    - path: "android/app/src/main/java/io/raventag/app/ui/theme/AppStrings.kt"
      provides: "EN + IT strings for Sent/Inviato, Cycled/Ciclato, Fee (invariant), Load more/Carica altre, No transactions yet/Nessuna transazione, empty body, view on explorer"
  key_links:
    - from: "WalletScreen TxCard (outgoing)"
      to: "TxHistoryDao.page / TxHistoryRow.sentSat/cycledSat/feeSat (plan 30-02)"
      via: "items(txHistory) rendering"
      pattern: "TxHistoryRow"
    - from: "WalletScreen Load more button"
      to: "TxHistoryDao.getPage(offset, limit=20) + RavencoinPublicNode.getHistoryPaged fallback"
      via: "WalletViewModel.loadMore"
      pattern: "getPage"
    - from: "TransactionDetailsScreen View on explorer"
      to: "Intent.ACTION_VIEW + AppConfig.EXPLORER_URL + txid"
      via: "context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(...)))"
      pattern: "EXPLORER_URL"
    - from: "send path persistence"
      to: "TxHistoryDao.upsert(TxHistoryRow(sentSat, cycledSat, feeSat, is_self, ...))"
      via: "computeCycledSat + computeSentSat helpers invoked post-broadcast"
      pattern: "TxHistoryDao"
---

<objective>
Deliver the D-19 three-value outgoing tx row on both WalletScreen and TransactionDetailsScreen, wire the paged tx history from plan 30-02's `TxHistoryDao` and the server-side `blockchain.scripthash.get_history`, add the "View on explorer" Intent path, and backfill the `sent_sat` / `cycled_sat` computation helpers so the send path writes the three values atomically on broadcast. This is the last visible Phase 30 UI pass before the housekeeping sweep.

Purpose: the D-19 decision is the single user-visible artifact of the quantum-resistance consolidation model (D-17). Without the three-value row, users cannot distinguish "sent 5 RVN" from "cycled 245 RVN" and would read the tx as a much larger outflow. Cleanly separating Sent/Cycled/Fee makes D-17 legible.
Output: surgical edits to WalletScreen.kt (TxCard outgoing row + Load more button + empty state), TransactionDetailsScreen.kt (breakdown + explorer button), RavencoinPublicNode.kt (`getHistoryPaged` + compute helpers), TxHistoryDao.kt (paging helper reconciled), both AppConfig.kt flavors (`EXPLORER_URL`), and AppStrings.kt (EN + IT).

Hard constraints:
- Do NOT touch `RavencoinTxBuilder.kt` (D-17 hard rule).
- Do NOT modify the incoming-row layout — only the outgoing row is rewritten.
- WalletScreen additions must NOT overlap with plan 30-08's header / banner / pill / disabled-state wiring. Only the TxCard region + empty state + Load more are in-scope here.
- `EXPLORER_URL` must be a https URL with trailing `/tx/` so the caller appends the txid. If a Ravencoin explorer's path is `/transaction/<txid>` in production, executor picks a stable one with a `/tx/` endpoint (`https://rvn.tokenview.io/en/tx/` or `https://ravencoin.network/tx/` — executor verifies at time of write).
- All new user-visible strings in AppStrings.kt, verbatim from UI-SPEC Copywriting Contract.
- No U+2014 em-dashes anywhere in touched files.
</objective>

<execution_context>
@$HOME/.claude/get-shit-done/workflows/execute-plan.md
@$HOME/.claude/get-shit-done/templates/summary.md
</execution_context>

<context>
@.planning/phases/30-wallet-reliability/30-CONTEXT.md
@.planning/phases/30-wallet-reliability/30-RESEARCH.md
@.planning/phases/30-wallet-reliability/30-PATTERNS.md
@.planning/phases/30-wallet-reliability/30-UI-SPEC.md
@.planning/phases/30-wallet-reliability/30-VALIDATION.md
@.planning/phases/30-wallet-reliability/30-02-wallet-cache-db-daos-PLAN.md
@.planning/phases/30-wallet-reliability/30-05-consolidation-reliability-PLAN.md
@.planning/phases/30-wallet-reliability/30-08-walletscreen-refresh-and-receive-ux-PLAN.md
@android/app/src/main/java/io/raventag/app/ui/screens/WalletScreen.kt
@android/app/src/main/java/io/raventag/app/ui/screens/TransactionDetailsScreen.kt
@android/app/src/main/java/io/raventag/app/wallet/RavencoinPublicNode.kt
@android/app/src/main/java/io/raventag/app/wallet/cache/TxHistoryDao.kt
@android/app/src/consumer/java/io/raventag/app/config/AppConfig.kt
@android/app/src/brand/java/io/raventag/app/config/AppConfig.kt
@android/app/src/main/java/io/raventag/app/ui/theme/AppStrings.kt
@android/app/src/test/java/io/raventag/app/wallet/RavencoinTxBuilderTest.kt

<interfaces>
**Already declared by upstream plans (consumed here — DO NOT redeclare):**

```kotlin
// From plan 30-02 (wallet/cache/TxHistoryDao.kt)
object TxHistoryDao {
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
    fun init(context: android.content.Context)
    fun upsert(rows: List<TxHistoryRow>)
    fun page(limit: Int, offset: Int): List<TxHistoryRow>
    fun findByTxid(txid: String): TxHistoryRow?
    fun count(): Int
}

// From plan 30-02 (wallet/RavencoinPublicNode existing)
data class TxHistoryEntry(
    val txid: String,
    val height: Int,
    val confirmations: Int,
    val timestamp: Long
)

// From existing codebase (RavencoinPublicNode.kt)
data class Utxo(val txid: String, val vout: Int, val value: Long, val height: Int)
data class AssetUtxo(val txid: String, val vout: Int, val assetName: String, val amount: Long, val height: Int)
fun RavencoinPublicNode.getTransactionHistory(address: String, limit: Int = 15, offset: Int = 0): List<TxHistoryEntry>  // existing
fun RavencoinPublicNode.callWithFailover(method: String, params: List<Any>): com.google.gson.JsonElement  // existing
```

**New helpers introduced by THIS plan:**

```kotlin
// Extension or member on RavencoinPublicNode
suspend fun RavencoinPublicNode.getHistoryPaged(
    address: String,
    offset: Int,
    limit: Int = 20
): List<io.raventag.app.wallet.RavencoinPublicNode.TxHistoryEntry>

// Companion object helpers (pure functions, test-friendly)
object RavencoinTxHistoryMath {
    /**
     * D-19 cycled amount = sum of output values paying the change (currentIndex+1) address.
     * Works for any raw JSON transaction object returned by `blockchain.transaction.get` with verbose=true.
     *
     * @param tx   JSON object with a `vout` array; each entry is `{ value: Double (RVN), scriptPubKey: { addresses: [...] } }`.
     * @param changeAddress The recipient address we consider "change / consolidation cycle".
     * @return cycled amount in satoshis.
     */
    fun computeCycledSat(tx: com.google.gson.JsonObject, changeAddress: String): Long

    /**
     * D-19 sent amount = sum of output values paying ANY address != changeAddress.
     * Returns 0 for self-transfers (all outputs land on changeAddress).
     */
    fun computeSentSat(tx: com.google.gson.JsonObject, changeAddress: String): Long
}

// AppConfig additions (both flavors)
const val EXPLORER_URL: String = "https://rvn.tokenview.io/en/tx/"
// OR alternative: "https://ravencoin.network/tx/". Executor picks one and records in SUMMARY.
```

**Existing codebase facts verified at planning time:**
- `RavencoinPublicNode.getTransactionHistory(address, limit, offset)` (line 914) already slices a full `blockchain.scripthash.get_history` array client-side. `getHistoryPaged` is a thin wrapper with fewer bells & whistles: returns `List<TxHistoryEntry>` without the expensive per-tx vin/vout walk that `getTransactionHistory` performs. This avoids redoing the full decode just for pagination.
- `AppConfig` exists as TWO files (consumer + brand flavor). EXPLORER_URL must be added to BOTH.
- `TxHistoryDao.page(limit, offset)` (plan 30-02) is already the paged accessor. This plan ALIASES it as `getPage(offset, limit)` via a tiny wrapper; the WalletScreen binding uses the alias for clarity.
- `TxHistoryDao.upsert(rows)` already does `CONFLICT_REPLACE` on txid PK; `upsertAll` in the plan body is just a renamed ergonomic alias (or a direct reuse — executor's choice, `upsert` is acceptable as-is).
- RavencoinTxBuilderTest.kt already exists (30-VALIDATION row 12 notes "extend"); this plan does NOT modify the TxBuilder. It may extend the test only to assert that change-output address == the `changeAddress` parameter (backing the D-19 cycled accounting).
</interfaces>
</context>

<tasks>

<task type="auto" tdd="false">
  <name>Task 1: RavencoinPublicNode.getHistoryPaged + RavencoinTxHistoryMath.computeCycledSat/computeSentSat</name>
  <files>
    android/app/src/main/java/io/raventag/app/wallet/RavencoinPublicNode.kt
  </files>
  <read_first>
    @.planning/phases/30-wallet-reliability/30-CONTEXT.md#L47-L53,
    @.planning/phases/30-wallet-reliability/30-RESEARCH.md#L744-L748,
    @.planning/phases/30-wallet-reliability/30-PATTERNS.md#L166-L187,
    @android/app/src/main/java/io/raventag/app/wallet/RavencoinPublicNode.kt
  </read_first>
  <behavior>
    Add two ADDITIVE features to RavencoinPublicNode without modifying any existing method:

    1) `suspend fun getHistoryPaged(address: String, offset: Int, limit: Int = 20): List<TxHistoryEntry>`:
       - Call `blockchain.scripthash.get_history` with the scripthash of `address`.
       - The server returns a full ordered list `[{tx_hash, height, fee?}, ...]` sorted by height ascending with mempool at the end (per ElectrumX protocol; existing `getTransactionHistory` already reorders it). Normalize to a newest-first list: mempool (height == 0) first, then confirmed sorted by height DESC.
       - Apply `.drop(offset).take(limit)` client-side.
       - Map each entry to `TxHistoryEntry(txid, height, confirmations = (currentHeight - height + 1) if height > 0 else 0, timestamp)`. Skip the expensive per-tx body fetch; pagination does not need tx amounts (they already live in TxHistoryDao once populated).
       - If `blockchain.headers.subscribe` is needed for `currentHeight`, use the same batch pattern as the existing `getTransactionHistory`; else reuse any already-cached tip height.
       - Wrap the blocking call in `kotlinx.coroutines.withContext(Dispatchers.IO)`.
       - On any exception, return `emptyList()` (do NOT rethrow — Load more path must be resilient).

    2) `object RavencoinTxHistoryMath`:
       ```kotlin
       object RavencoinTxHistoryMath {
           fun computeCycledSat(tx: com.google.gson.JsonObject, changeAddress: String): Long { ... }
           fun computeSentSat(tx: com.google.gson.JsonObject, changeAddress: String): Long { ... }
       }
       ```

       Semantics:
       - `vout` is a `JsonArray` where each element has `value` (RVN as `Double`) and `scriptPubKey.addresses` (a `JsonArray` of `String`).
       - `computeCycledSat` sums `value` of outputs whose `scriptPubKey.addresses` contains `changeAddress`, converted from RVN → satoshis (`(value * 1e8).toLong()`).
       - `computeSentSat` sums `value` of outputs whose `scriptPubKey.addresses` contains AT LEAST ONE address != `changeAddress`. If an output pays multiple addresses (multi-sig), treat it as "sent" if any address differs from changeAddress (conservative — the user is giving up exclusive control of that output's full value).
       - Malformed entries (no `value`, no `addresses`) contribute 0.
       - Both functions are PURE (no network, no storage). Safe to unit-test.

    3) Do NOT touch the existing `getTransactionHistory`. The new helper is deliberately leaner for the Load more path; the existing method remains for the initial WalletScreen render that walks vin/vout for amount attribution.

    Em-dash audit on the touched file.
  </behavior>
  <action>
    1) Open `android/app/src/main/java/io/raventag/app/wallet/RavencoinPublicNode.kt`. Find a sensible insertion point for additions: below the existing `getTransactionHistory` method (~line 914-1030) or inside the companion object at the bottom of the class — inspect style and place accordingly.

    2) Add `getHistoryPaged` as a member function on the RavencoinPublicNode class:
       ```kotlin
       suspend fun getHistoryPaged(
           address: String,
           offset: Int,
           limit: Int = 20
       ): List<TxHistoryEntry> = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
           try {
               val scripthash = addressToScripthash(address)
               // Batch: fetch tip height + history in one TLS connection, same as existing getTransactionHistory.
               val batch = callWithFailoverBatch(listOf(
                   "blockchain.headers.subscribe" to emptyList<Any>(),
                   "blockchain.scripthash.get_history" to listOf(scripthash)
               ))
               val currentHeight = try {
                   batch[0]?.asJsonObject?.get("height")?.asInt ?: 0
               } catch (_: Exception) { 0 }
               val raw = batch[1]?.asJsonArray ?: return@withContext emptyList<TxHistoryEntry>()
               val ordered = raw
                   .mapNotNull { try { it.asJsonObject } catch (_: Exception) { null } }
                   .sortedWith(compareByDescending {
                       val h = it.get("height")?.asInt ?: 0
                       if (h <= 0) Int.MAX_VALUE else h  // mempool first
                   })
                   .drop(offset)
                   .take(limit)
               ordered.mapNotNull { item ->
                   val txHash = item.get("tx_hash")?.asString ?: return@mapNotNull null
                   val height = item.get("height")?.asInt ?: 0
                   val confirmations = if (height > 0 && currentHeight > 0) {
                       (currentHeight - height + 1).coerceAtLeast(0)
                   } else 0
                   TxHistoryEntry(
                       txid = txHash,
                       height = height,
                       confirmations = confirmations,
                       timestamp = 0L  // Lightweight: timestamp not included; caller fills on full fetch if needed.
                   )
               }
           } catch (_: Exception) {
               emptyList()
           }
       }
       ```

       If the existing `TxHistoryEntry` data class has additional required fields (e.g. `blockTime`, `amount`), supply defaults (0L / null / empty string) to keep the constructor call valid. The executor inspects the file for the exact `TxHistoryEntry` signature and adapts.

    3) Add the helper object at file top-level (below the class body):
       ```kotlin
       object RavencoinTxHistoryMath {

           private const val SAT_PER_RVN = 100_000_000L

           fun computeCycledSat(
               tx: com.google.gson.JsonObject,
               changeAddress: String
           ): Long {
               val vout = try { tx.getAsJsonArray("vout") } catch (_: Exception) { null }
                   ?: return 0L
               var total = 0L
               for (element in vout) {
                   try {
                       val out = element.asJsonObject
                       val addresses = out
                           .getAsJsonObject("scriptPubKey")
                           ?.getAsJsonArray("addresses")
                           ?: continue
                       val hasChange = addresses.any { it.asString == changeAddress }
                       if (hasChange) {
                           val rvn = out.get("value")?.asDouble ?: 0.0
                           total += (rvn * SAT_PER_RVN).toLong()
                       }
                   } catch (_: Exception) {
                       // skip malformed output
                   }
               }
               return total
           }

           fun computeSentSat(
               tx: com.google.gson.JsonObject,
               changeAddress: String
           ): Long {
               val vout = try { tx.getAsJsonArray("vout") } catch (_: Exception) { null }
                   ?: return 0L
               var total = 0L
               for (element in vout) {
                   try {
                       val out = element.asJsonObject
                       val addresses = out
                           .getAsJsonObject("scriptPubKey")
                           ?.getAsJsonArray("addresses")
                           ?: continue
                       val external = addresses.any { it.asString != changeAddress }
                       if (external) {
                           val rvn = out.get("value")?.asDouble ?: 0.0
                           total += (rvn * SAT_PER_RVN).toLong()
                       }
                   } catch (_: Exception) {
                       // skip malformed output
                   }
               }
               return total
           }
       }
       ```

    4) Em-dash audit: `! grep -P '\u2014' android/app/src/main/java/io/raventag/app/wallet/RavencoinPublicNode.kt`. If any em dashes exist from earlier code, replace per MEMORY rule.
  </action>
  <verify>
    <automated>cd android && ./gradlew :app:compileConsumerDebugKotlin -q 2>&1 | tail -20</automated>
  </verify>
  <acceptance_criteria>
    - `grep -q "suspend fun getHistoryPaged" android/app/src/main/java/io/raventag/app/wallet/RavencoinPublicNode.kt`
    - `grep -q "object RavencoinTxHistoryMath" android/app/src/main/java/io/raventag/app/wallet/RavencoinPublicNode.kt`
    - `grep -q "fun computeCycledSat" android/app/src/main/java/io/raventag/app/wallet/RavencoinPublicNode.kt`
    - `grep -q "fun computeSentSat" android/app/src/main/java/io/raventag/app/wallet/RavencoinPublicNode.kt`
    - `grep -q "blockchain.scripthash.get_history" android/app/src/main/java/io/raventag/app/wallet/RavencoinPublicNode.kt`
    - `grep -q "SAT_PER_RVN" android/app/src/main/java/io/raventag/app/wallet/RavencoinPublicNode.kt`
    - `! grep -P '\u2014' android/app/src/main/java/io/raventag/app/wallet/RavencoinPublicNode.kt`
    - `cd android && ./gradlew :app:compileConsumerDebugKotlin` exits 0.
  </acceptance_criteria>
  <done>getHistoryPaged + RavencoinTxHistoryMath.computeCycledSat/computeSentSat added; existing methods unchanged. Build passes. No em dashes.</done>
</task>

<task type="auto" tdd="false">
  <name>Task 2: TxHistoryDao — add getPage(offset, limit) alias (wraps existing page(limit, offset))</name>
  <files>
    android/app/src/main/java/io/raventag/app/wallet/cache/TxHistoryDao.kt
  </files>
  <read_first>
    @.planning/phases/30-wallet-reliability/30-02-wallet-cache-db-daos-PLAN.md,
    @android/app/src/main/java/io/raventag/app/wallet/cache/TxHistoryDao.kt
  </read_first>
  <behavior>
    Plan 30-02 already exposed `fun page(limit: Int, offset: Int): List<TxHistoryRow>` (ordered by height DESC, timestamp DESC). This plan adds a semantic alias whose parameter order matches the `(offset, limit)` convention used by `RavencoinPublicNode.getHistoryPaged` and is more readable at WalletScreen call sites:
    ```kotlin
    fun getPage(offset: Int, limit: Int = 20): List<TxHistoryRow> = page(limit = limit, offset = offset)
    ```

    No schema change. No new table. No new SQL.

    Em-dash audit.
  </behavior>
  <action>
    1) Open `android/app/src/main/java/io/raventag/app/wallet/cache/TxHistoryDao.kt`. Verify plan 30-02's `page(limit, offset)` is present.

    2) Add the alias inside the `object TxHistoryDao`:
       ```kotlin
       /**
        * D-23 paged tx history with the argument order `(offset, limit)` that matches
        * `RavencoinPublicNode.getHistoryPaged`. Default page size 20 per UI-SPEC Load more.
        */
       fun getPage(offset: Int, limit: Int = 20): List<TxHistoryRow> =
           page(limit = limit, offset = offset)
       ```

    3) If the `page` function happens to be private in the plan 30-02 output, promote to internal OR inline the SQL here — the executor chooses the minimal change.

    Em-dash audit.
  </action>
  <verify>
    <automated>cd android && ./gradlew :app:compileConsumerDebugKotlin -q 2>&1 | tail -20</automated>
  </verify>
  <acceptance_criteria>
    - `grep -q "fun getPage(offset: Int" android/app/src/main/java/io/raventag/app/wallet/cache/TxHistoryDao.kt`
    - `grep -q "fun page(limit: Int" android/app/src/main/java/io/raventag/app/wallet/cache/TxHistoryDao.kt`
    - `! grep -P '\u2014' android/app/src/main/java/io/raventag/app/wallet/cache/TxHistoryDao.kt`
    - `cd android && ./gradlew :app:compileConsumerDebugKotlin` exits 0.
  </acceptance_criteria>
  <done>`getPage(offset, limit)` alias available. Existing `page(limit, offset)` untouched. No em dashes.</done>
</task>

<task type="auto" tdd="false">
  <name>Task 3: AppConfig.kt (both flavors) — add EXPLORER_URL const</name>
  <files>
    android/app/src/consumer/java/io/raventag/app/config/AppConfig.kt,
    android/app/src/brand/java/io/raventag/app/config/AppConfig.kt
  </files>
  <read_first>
    @.planning/phases/30-wallet-reliability/30-UI-SPEC.md#L386-L391,
    @android/app/src/consumer/java/io/raventag/app/config/AppConfig.kt,
    @android/app/src/brand/java/io/raventag/app/config/AppConfig.kt
  </read_first>
  <behavior>
    Add a `const val EXPLORER_URL: String` to both flavor `AppConfig` objects (consumer + brand). The URL is an HTTPS endpoint where appending a Ravencoin txid yields a web page showing the transaction.

    Constraints:
    - HTTPS only.
    - Must end with `/tx/` so the caller just concatenates the txid.
    - Must point to a Ravencoin block explorer that lists transactions for the Ravencoin mainnet.

    Recommended value (verify at execution time; if unreachable, pick an alternative that satisfies the two criteria):
    - `https://rvn.tokenview.io/en/tx/` (Tokenview, multi-chain — Ravencoin supported 2024+)
    - `https://ravencoin.network/tx/` (community explorer)

    Executor picks ONE, the same for both flavors, and records the choice + source in SUMMARY.md. Example literal:
    ```kotlin
    /**
     * Block explorer URL prefix for Ravencoin transactions.
     * Appending a txid yields a browsable page, e.g. `${EXPLORER_URL}$txid`.
     *
     * Verified 2026-04 against Ravencoin mainnet. If the explorer rotates in the future,
     * update here — no runtime override is exposed in v1.
     */
    const val EXPLORER_URL: String = "https://ravencoin.network/tx/"
    ```

    Em-dash audit on BOTH files.
  </behavior>
  <action>
    1) Open both `android/app/src/consumer/java/io/raventag/app/config/AppConfig.kt` and `android/app/src/brand/java/io/raventag/app/config/AppConfig.kt`.

    2) Inside each `object AppConfig { ... }`, add the const and KDoc:
       ```kotlin
       /**
        * Block explorer URL prefix for Ravencoin transactions.
        * Appending a txid yields a browsable transaction page, e.g. `${EXPLORER_URL}<txid>`.
        * Verified 2026-04 against Ravencoin mainnet.
        */
       const val EXPLORER_URL: String = "https://ravencoin.network/tx/"
       ```
       (Executor MAY swap to `https://rvn.tokenview.io/en/tx/` if `ravencoin.network` is confirmed dead; same URL must be used in BOTH flavor files. Document the choice in SUMMARY.md.)

    3) Em-dash audit on both files.
  </action>
  <verify>
    <automated>cd android && ./gradlew :app:compileConsumerDebugKotlin :app:compileBrandDebugKotlin -q 2>&1 | tail -20</automated>
  </verify>
  <acceptance_criteria>
    - `grep -q "const val EXPLORER_URL" android/app/src/consumer/java/io/raventag/app/config/AppConfig.kt`
    - `grep -q "const val EXPLORER_URL" android/app/src/brand/java/io/raventag/app/config/AppConfig.kt`
    - `grep -q "https://" android/app/src/consumer/java/io/raventag/app/config/AppConfig.kt`
    - `grep -q "/tx/" android/app/src/consumer/java/io/raventag/app/config/AppConfig.kt`
    - `grep -q "/tx/" android/app/src/brand/java/io/raventag/app/config/AppConfig.kt`
    - `! grep -P '\u2014' android/app/src/consumer/java/io/raventag/app/config/AppConfig.kt`
    - `! grep -P '\u2014' android/app/src/brand/java/io/raventag/app/config/AppConfig.kt`
    - `cd android && ./gradlew :app:compileConsumerDebugKotlin` exits 0.
    - `cd android && ./gradlew :app:compileBrandDebugKotlin` exits 0.
  </acceptance_criteria>
  <done>EXPLORER_URL const present in both flavor AppConfig files, same value, terminating in `/tx/`. Both compile. No em dashes.</done>
</task>

<task type="auto" tdd="false">
  <name>Task 4: AppStrings.kt — EN + IT strings for Sent/Inviato, Cycled/Ciclato, Fee, Load more, empty state, View on explorer</name>
  <files>
    android/app/src/main/java/io/raventag/app/ui/theme/AppStrings.kt
  </files>
  <read_first>
    @.planning/phases/30-wallet-reliability/30-UI-SPEC.md#L131-L139,
    @.planning/phases/30-wallet-reliability/30-UI-SPEC.md#L150-L177,
    @.planning/phases/30-wallet-reliability/30-UI-SPEC.md#L261-L283,
    @android/app/src/main/java/io/raventag/app/ui/theme/AppStrings.kt
  </read_first>
  <behavior>
    Add these properties to `class AppStrings` and populate EN + IT blocks verbatim from UI-SPEC Copywriting Contract.

    | Property key                   | EN value                                                             | IT value                                                                                |
    |--------------------------------|----------------------------------------------------------------------|-----------------------------------------------------------------------------------------|
    | `txHistorySentPrefix`          | `Sent`                                                               | `Inviato`                                                                               |
    | `txHistoryCycledPrefix`        | `Cycled`                                                             | `Ciclato`                                                                               |
    | `txHistoryFeePrefix`           | `Fee`                                                                | `Fee`                                                                                   |
    | `txHistoryLoadMore`            | `Load more`                                                          | `Carica altre`                                                                          |
    | `txHistoryEmptyHeading`        | `No transactions yet`                                                | `Nessuna transazione`                                                                   |
    | `txHistoryEmptyBody`           | `Your first sent or received transaction will appear here.`          | `La prima transazione inviata o ricevuta comparirà qui.`                               |
    | `txDetailsViewOnExplorer`      | `View on explorer`                                                   | `Apri su explorer`                                                                      |
    | `txHistoryConfirmations`       | `%1$d/6 confirmations`                                               | `%1$d/6 conferme`                                                                       |

    Rules:
    - "Fee" is kept invariant in Italian (industry-accepted Italian usage; users familiar with RVN wallets expect "Fee").
    - "Cycled" → "Ciclato" is the canonical Italian translation (see UI-SPEC §Copywriting Contract defaults and MEMORY). If UI-SPEC lists a different literal, executor defers to UI-SPEC verbatim.
    - Separators always `·` (U+00B7). No em dashes (U+2014).

    Em-dash audit.
  </behavior>
  <action>
    1) Open `AppStrings.kt`. Add the new `var` properties to `class AppStrings` with EN defaults:
       ```kotlin
       var txHistorySentPrefix: String = "Sent"
       var txHistoryCycledPrefix: String = "Cycled"
       var txHistoryFeePrefix: String = "Fee"
       var txHistoryLoadMore: String = "Load more"
       var txHistoryEmptyHeading: String = "No transactions yet"
       var txHistoryEmptyBody: String = "Your first sent or received transaction will appear here."
       var txDetailsViewOnExplorer: String = "View on explorer"
       var txHistoryConfirmations: String = "%1\$d/6 confirmations"
       ```

    2) Add EN assignments inside `stringsEn.apply { ... }` (redundant with defaults but explicit).

    3) Add IT overrides inside `stringsIt.apply { ... }`:
       ```kotlin
       txHistorySentPrefix = "Inviato"
       txHistoryCycledPrefix = "Ciclato"
       txHistoryFeePrefix = "Fee"
       txHistoryLoadMore = "Carica altre"
       txHistoryEmptyHeading = "Nessuna transazione"
       txHistoryEmptyBody = "La prima transazione inviata o ricevuta comparirà qui."
       txDetailsViewOnExplorer = "Apri su explorer"
       txHistoryConfirmations = "%1\$d/6 conferme"
       ```

    4) Em-dash audit.
  </action>
  <verify>
    <automated>cd android && ./gradlew :app:compileConsumerDebugKotlin -q 2>&1 | tail -20</automated>
  </verify>
  <acceptance_criteria>
    - `grep -q "txHistorySentPrefix" android/app/src/main/java/io/raventag/app/ui/theme/AppStrings.kt`
    - `grep -q "txHistoryCycledPrefix" android/app/src/main/java/io/raventag/app/ui/theme/AppStrings.kt`
    - `grep -q "txHistoryFeePrefix" android/app/src/main/java/io/raventag/app/ui/theme/AppStrings.kt`
    - `grep -q "txHistoryLoadMore" android/app/src/main/java/io/raventag/app/ui/theme/AppStrings.kt`
    - `grep -q "txHistoryEmptyHeading" android/app/src/main/java/io/raventag/app/ui/theme/AppStrings.kt`
    - `grep -q "txHistoryEmptyBody" android/app/src/main/java/io/raventag/app/ui/theme/AppStrings.kt`
    - `grep -q "txDetailsViewOnExplorer" android/app/src/main/java/io/raventag/app/ui/theme/AppStrings.kt`
    - `grep -q "Sent" android/app/src/main/java/io/raventag/app/ui/theme/AppStrings.kt`
    - `grep -q "Inviato" android/app/src/main/java/io/raventag/app/ui/theme/AppStrings.kt`
    - `grep -q "Cycled" android/app/src/main/java/io/raventag/app/ui/theme/AppStrings.kt`
    - `grep -q "Ciclato" android/app/src/main/java/io/raventag/app/ui/theme/AppStrings.kt`
    - `grep -q "Load more" android/app/src/main/java/io/raventag/app/ui/theme/AppStrings.kt`
    - `grep -q "Carica altre" android/app/src/main/java/io/raventag/app/ui/theme/AppStrings.kt`
    - `grep -q "No transactions yet" android/app/src/main/java/io/raventag/app/ui/theme/AppStrings.kt`
    - `grep -q "Nessuna transazione" android/app/src/main/java/io/raventag/app/ui/theme/AppStrings.kt`
    - `grep -q "Your first sent or received transaction will appear here" android/app/src/main/java/io/raventag/app/ui/theme/AppStrings.kt`
    - `grep -q "La prima transazione inviata o ricevuta comparirà qui" android/app/src/main/java/io/raventag/app/ui/theme/AppStrings.kt`
    - `grep -q "View on explorer" android/app/src/main/java/io/raventag/app/ui/theme/AppStrings.kt`
    - `grep -q "Apri su explorer" android/app/src/main/java/io/raventag/app/ui/theme/AppStrings.kt`
    - `! grep -P '\u2014' android/app/src/main/java/io/raventag/app/ui/theme/AppStrings.kt`
    - `cd android && ./gradlew :app:compileConsumerDebugKotlin` exits 0.
  </acceptance_criteria>
  <done>All D-19 + D-23 EN + IT strings live in AppStrings.kt verbatim from UI-SPEC Copywriting Contract. Build passes. No em dashes.</done>
</task>

<task type="auto" tdd="false">
  <name>Task 5: WalletScreen.kt TxCard rewrite — outgoing three-value row, self-transfer variant, incoming row preserved, Load more button, empty state</name>
  <files>
    android/app/src/main/java/io/raventag/app/ui/screens/WalletScreen.kt
  </files>
  <read_first>
    @.planning/phases/30-wallet-reliability/30-UI-SPEC.md#L131-L139,
    @.planning/phases/30-wallet-reliability/30-UI-SPEC.md#L261-L290,
    @.planning/phases/30-wallet-reliability/30-CONTEXT.md#L49-L55,
    @.planning/phases/30-wallet-reliability/30-PATTERNS.md#L378-L389,
    @android/app/src/main/java/io/raventag/app/ui/screens/WalletScreen.kt,
    @android/app/src/main/java/io/raventag/app/ui/theme/AppStrings.kt,
    @android/app/src/main/java/io/raventag/app/ui/theme/Theme.kt,
    @android/app/src/main/java/io/raventag/app/wallet/cache/TxHistoryDao.kt
  </read_first>
  <behavior>
    Rewrite the outgoing branch of the existing `TxCard` composable to render the D-19 three-value row. Do NOT modify the incoming branch. Add a self-transfer variant (pure consolidation). Add a Load more button and an empty-state composable.

    Visual spec (UI-SPEC §Tx history three-value row):
    - Row outer: existing `Card` with `RavenCard` bg, `RavenBorder` border, 12dp radius, padding 14dp/10dp (unchanged).
    - Left: existing status dot (10dp) + existing direction icon (16dp `Icons.Default.CallMade` in NotAuthenticRed). Unchanged.
    - Middle: existing truncated txid in monospace, RavenMuted, `weight(1f)`. Unchanged.
    - Right column: NEW. `Alignment.End`. Gap `2.dp` between the three value lines. Gap `6.dp` before the timestamp/conf row.
      - Line 1 ("Sent"):  `bodySmall`, FontWeight.SemiBold, color `NotAuthenticRed`. Prefix `"${strings.txHistorySentPrefix} -"` + formatted amount + `" RVN"`. Example: `Sent -5 RVN` (EN), `Inviato -5 RVN` (IT). Decimal styling (10sp decimals) from existing pattern applies — reuse the existing `AnnotatedString` composite used by the balance row.
      - Line 2 ("Cycled"): `labelSmall`, FontWeight.Normal, color `AuthenticGreen`. Text `"${strings.txHistoryCycledPrefix} ${amount} RVN"`.
      - Line 3 ("Fee"):    `labelSmall`, FontWeight.Normal, color `RavenMuted`. Text `"${strings.txHistoryFeePrefix} ${amount} RVN"`.
      - Row 4 (timestamp + conf): existing spec. Middle dot `·` separator, format `DD/MM/YY · n/6 conf` (EN). `txHistoryConfirmations` already pluralized.

    Self-transfer variant (when `row.isSelf == true`):
    - Collapse Lines 1/2/3 into a SINGLE line: `${strings.txHistoryCycledPrefix} ${cycledAmount} RVN · ${strings.txHistoryFeePrefix} ${feeAmount} RVN`.
    - Direction icon replaced with `Icons.Default.Autorenew` in `RavenOrange`.
    - No Sent line.
    - Everything else (dot color, monospace txid, confirmations row) unchanged.

    Incoming row (`row.isIncoming == true`): UNCHANGED. Keep the existing single-amount layout.

    Confirmation dot color (D-08): verify existing logic behaves as:
    - `confirms == 0` → NotAuthenticRed
    - `confirms in 1..5` → amber `Color(0xFFF59E0B)`
    - `confirms >= 6` → AuthenticGreen
    If the existing code does NOT match, correct it in this pass.

    Load more button (UI-SPEC §Primary CTAs):
    - Below the LazyColumn (or inside it as the last item), render `Button(onClick = viewModel.loadMore, colors = ButtonDefaults.buttonColors(containerColor = RavenOrange))` with text `strings.txHistoryLoadMore`.
    - Only visible when `TxHistoryDao.count() > currentlyDisplayed` OR when a next-page fetch is likely to succeed (simpler heuristic: always visible while the last page returned `limit` rows).
    - `loadMore()` behavior (in the ViewModel or inline lambda here):
      1. Compute `offset = currentList.size`.
      2. Call `TxHistoryDao.getPage(offset = offset, limit = 20)`.
      3. If result is empty, fall back to `RavencoinPublicNode.getHistoryPaged(address, offset = offset, limit = 20)` via `kotlinx.coroutines.launch`.
      4. Append returned rows to the displayed list.

    Empty state (UI-SPEC §Empty states):
    - When the displayed list is empty AND no refresh is in progress, render a centered Column inside the history section:
      - Text `strings.txHistoryEmptyHeading` titleSmall SemiBold white.
      - Spacer 8dp.
      - Text `strings.txHistoryEmptyBody` bodySmall RavenMuted, textAlign = Center.

    No modifications to the header / banner / connection pill / Pending line / battery chip — those were installed by plan 30-08.

    Em-dash audit on WalletScreen.kt.
  </behavior>
  <action>
    1) Read WalletScreen.kt. Identify the existing `TxCard` composable. Typical structure:
       ```kotlin
       @Composable
       private fun TxCard(tx: TxHistoryEntry, ...) {
           Card(... ) {
               Row(... ) {
                   // dot, icon, txid, amount
               }
           }
       }
       ```
       If `TxCard` currently takes `TxHistoryEntry` only (Phase 20), adapt it to take `TxHistoryRow` from `TxHistoryDao` instead. If the WalletScreen currently iterates over `TxHistoryEntry` (from network fetch), introduce a `displayedRows: List<TxHistoryDao.TxHistoryRow>` state that is filled from `TxHistoryDao.getPage(offset = 0, limit = 20)` on first load, replacing the network-sourced list. Preserve the initial fetch that WRITES to `TxHistoryDao` (plan 30-05 already ensures the send path writes; plan 30-08 triggers refresh; this plan just reads).

    2) Implement the rewritten `TxCard`:
       ```kotlin
       @Composable
       private fun TxCard(row: io.raventag.app.wallet.cache.TxHistoryDao.TxHistoryRow) {
           val strings = io.raventag.app.ui.theme.LocalStrings.current
           val dotColor = when {
               row.confirms == 0 -> io.raventag.app.ui.theme.NotAuthenticRed
               row.confirms in 1..5 -> androidx.compose.ui.graphics.Color(0xFFF59E0B)
               else -> io.raventag.app.ui.theme.AuthenticGreen
           }

           Card(
               colors = CardDefaults.cardColors(containerColor = io.raventag.app.ui.theme.RavenCard),
               border = androidx.compose.foundation.BorderStroke(1.dp, io.raventag.app.ui.theme.RavenBorder),
               shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp),
               modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
           ) {
               Row(
                   modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
                   verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
               ) {
                   // Left: dot + direction icon
                   Box(Modifier.size(10.dp).background(dotColor, CircleShape))
                   Spacer(Modifier.width(8.dp))
                   val (dirIcon, dirTint) = when {
                       row.isSelf -> Icons.Default.Autorenew to io.raventag.app.ui.theme.RavenOrange
                       row.isIncoming -> Icons.Default.CallReceived to io.raventag.app.ui.theme.AuthenticGreen
                       else -> Icons.Default.CallMade to io.raventag.app.ui.theme.NotAuthenticRed
                   }
                   Icon(dirIcon, contentDescription = null, tint = dirTint, modifier = Modifier.size(16.dp))
                   Spacer(Modifier.width(8.dp))

                   // Middle: truncated txid
                   Text(
                       text = row.txid.take(10) + "\u2026",
                       style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                       color = io.raventag.app.ui.theme.RavenMuted,
                       modifier = Modifier.weight(1f)
                   )

                   // Right column
                   Column(horizontalAlignment = androidx.compose.ui.Alignment.End) {
                       when {
                           row.isIncoming -> {
                               // UNCHANGED incoming layout (single amount + timestamp + confs).
                               val rvn = String.format(java.util.Locale.ROOT, "%.8f", row.amountSat / 1e8)
                               Text(
                                   text = "+$rvn RVN",
                                   style = MaterialTheme.typography.bodySmall,
                                   fontWeight = FontWeight.SemiBold,
                                   color = io.raventag.app.ui.theme.AuthenticGreen
                               )
                           }
                           row.isSelf -> {
                               val cycled = String.format(java.util.Locale.ROOT, "%.8f", row.cycledSat / 1e8)
                               val fee = String.format(java.util.Locale.ROOT, "%.8f", row.feeSat / 1e8)
                               Text(
                                   text = "${strings.txHistoryCycledPrefix} $cycled RVN \u00B7 ${strings.txHistoryFeePrefix} $fee RVN",
                                   style = MaterialTheme.typography.labelSmall,
                                   color = io.raventag.app.ui.theme.AuthenticGreen
                               )
                           }
                           else -> {
                               val sent = String.format(java.util.Locale.ROOT, "%.8f", row.sentSat / 1e8)
                               val cycled = String.format(java.util.Locale.ROOT, "%.8f", row.cycledSat / 1e8)
                               val fee = String.format(java.util.Locale.ROOT, "%.8f", row.feeSat / 1e8)
                               Text(
                                   text = "${strings.txHistorySentPrefix} -$sent RVN",
                                   style = MaterialTheme.typography.bodySmall,
                                   fontWeight = FontWeight.SemiBold,
                                   color = io.raventag.app.ui.theme.NotAuthenticRed
                               )
                               Spacer(Modifier.height(2.dp))
                               Text(
                                   text = "${strings.txHistoryCycledPrefix} $cycled RVN",
                                   style = MaterialTheme.typography.labelSmall,
                                   color = io.raventag.app.ui.theme.AuthenticGreen
                               )
                               Spacer(Modifier.height(2.dp))
                               Text(
                                   text = "${strings.txHistoryFeePrefix} $fee RVN",
                                   style = MaterialTheme.typography.labelSmall,
                                   color = io.raventag.app.ui.theme.RavenMuted
                               )
                           }
                       }
                       Spacer(Modifier.height(6.dp))
                       // Timestamp + conf row
                       val ts = if (row.timestamp > 0L) {
                           java.text.SimpleDateFormat("dd/MM/yy", java.util.Locale.getDefault())
                               .format(java.util.Date(row.timestamp * 1000L))
                       } else ""
                       val conf = String.format(strings.txHistoryConfirmations, row.confirms.coerceAtMost(6))
                       Text(
                           text = if (ts.isEmpty()) conf else "$ts \u00B7 $conf",
                           style = MaterialTheme.typography.labelSmall,
                           color = io.raventag.app.ui.theme.RavenMuted
                       )
                   }
               }
           }
       }
       ```

    3) Replace the LazyColumn's `items(txHistory)` iterator with `items(displayedRows) { TxCard(it) }`. Adapt `displayedRows` type to `List<TxHistoryDao.TxHistoryRow>`.

    4) Add the Load more button below the LazyColumn items (or as the final item):
       ```kotlin
       item {
           Spacer(Modifier.height(8.dp))
           Button(
               onClick = { scope.launch { loadMore() } },
               colors = ButtonDefaults.buttonColors(containerColor = io.raventag.app.ui.theme.RavenOrange),
               modifier = Modifier.fillMaxWidth()
           ) { Text(strings.txHistoryLoadMore) }
       }
       ```

    5) Implement `loadMore()`:
       ```kotlin
       suspend fun loadMore() {
           val offset = displayedRows.size
           val local = io.raventag.app.wallet.cache.TxHistoryDao.getPage(offset = offset, limit = 20)
           if (local.isNotEmpty()) {
               displayedRows = displayedRows + local
           } else {
               val addr = walletInfo.currentReceiveAddress
               val serverPage = io.raventag.app.wallet.RavencoinPublicNode(context)
                   .getHistoryPaged(address = addr, offset = offset, limit = 20)
               // Materialize into TxHistoryRow shells (amount data missing — rely on next refresh to enrich).
               val shells = serverPage.map { entry ->
                   io.raventag.app.wallet.cache.TxHistoryDao.TxHistoryRow(
                       txid = entry.txid,
                       height = entry.height,
                       confirms = entry.confirmations,
                       amountSat = 0L, sentSat = 0L, cycledSat = 0L, feeSat = 0L,
                       isIncoming = false, isSelf = false,
                       timestamp = entry.timestamp,
                       cachedAt = System.currentTimeMillis()
                   )
               }
               if (shells.isNotEmpty()) {
                   io.raventag.app.wallet.cache.TxHistoryDao.upsert(shells)
                   displayedRows = displayedRows + shells
               }
           }
       }
       ```

    6) Empty state:
       ```kotlin
       if (displayedRows.isEmpty() && !isRefreshing) {
           Column(
               modifier = Modifier.fillMaxWidth().padding(vertical = 32.dp),
               horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally
           ) {
               Text(
                   text = strings.txHistoryEmptyHeading,
                   style = MaterialTheme.typography.titleSmall,
                   fontWeight = FontWeight.SemiBold,
                   color = Color.White
               )
               Spacer(Modifier.height(8.dp))
               Text(
                   text = strings.txHistoryEmptyBody,
                   style = MaterialTheme.typography.bodySmall,
                   color = io.raventag.app.ui.theme.RavenMuted,
                   textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                   modifier = Modifier.fillMaxWidth()
               )
           }
       }
       ```

    7) On first composition (or as part of the existing WalletScreen `LaunchedEffect`), seed `displayedRows` from `TxHistoryDao.getPage(offset = 0, limit = 20)`. If the resulting list is empty (fresh install with no cache), also kick off the one-shot history fetch already wired via the existing refresh path.

    Em-dash audit: `! grep -P '\u2014' android/app/src/main/java/io/raventag/app/ui/screens/WalletScreen.kt`.
  </action>
  <verify>
    <automated>cd android && ./gradlew :app:assembleConsumerDebug -q 2>&1 | tail -30</automated>
  </verify>
  <acceptance_criteria>
    - `grep -q "txHistorySentPrefix" android/app/src/main/java/io/raventag/app/ui/screens/WalletScreen.kt`
    - `grep -q "txHistoryCycledPrefix" android/app/src/main/java/io/raventag/app/ui/screens/WalletScreen.kt`
    - `grep -q "txHistoryFeePrefix" android/app/src/main/java/io/raventag/app/ui/screens/WalletScreen.kt`
    - `grep -q "txHistoryLoadMore" android/app/src/main/java/io/raventag/app/ui/screens/WalletScreen.kt`
    - `grep -q "txHistoryEmptyHeading" android/app/src/main/java/io/raventag/app/ui/screens/WalletScreen.kt`
    - `grep -q "txHistoryEmptyBody" android/app/src/main/java/io/raventag/app/ui/screens/WalletScreen.kt`
    - `grep -q "Icons.Default.Autorenew" android/app/src/main/java/io/raventag/app/ui/screens/WalletScreen.kt`
    - `grep -q "Icons.Default.CallMade" android/app/src/main/java/io/raventag/app/ui/screens/WalletScreen.kt`
    - `grep -q "TxHistoryDao.getPage" android/app/src/main/java/io/raventag/app/ui/screens/WalletScreen.kt`
    - `grep -q "getHistoryPaged" android/app/src/main/java/io/raventag/app/ui/screens/WalletScreen.kt`
    - `grep -q "Color(0xFFF59E0B)" android/app/src/main/java/io/raventag/app/ui/screens/WalletScreen.kt`
    - `grep -q "isSelf" android/app/src/main/java/io/raventag/app/ui/screens/WalletScreen.kt`
    - `grep -q "isIncoming" android/app/src/main/java/io/raventag/app/ui/screens/WalletScreen.kt`
    - `grep -q "sentSat\|sent_sat" android/app/src/main/java/io/raventag/app/ui/screens/WalletScreen.kt`
    - `grep -q "cycledSat\|cycled_sat" android/app/src/main/java/io/raventag/app/ui/screens/WalletScreen.kt`
    - `grep -q "feeSat\|fee_sat" android/app/src/main/java/io/raventag/app/ui/screens/WalletScreen.kt`
    - `! grep -P '\u2014' android/app/src/main/java/io/raventag/app/ui/screens/WalletScreen.kt`
    - `cd android && ./gradlew :app:assembleConsumerDebug` exits 0.
  </acceptance_criteria>
  <done>TxCard outgoing row shows three values (Sent/Cycled/Fee); self-transfer variant single-line with Autorenew icon; incoming row untouched; Load more button wired to TxHistoryDao.getPage with network fallback; empty state copy verbatim. Build passes. No em dashes.</done>
</task>

<task type="auto" tdd="false">
  <name>Task 6: TransactionDetailsScreen.kt — three-value breakdown + View on explorer OutlinedButton</name>
  <files>
    android/app/src/main/java/io/raventag/app/ui/screens/TransactionDetailsScreen.kt
  </files>
  <read_first>
    @.planning/phases/30-wallet-reliability/30-UI-SPEC.md#L386-L391,
    @android/app/src/main/java/io/raventag/app/ui/screens/TransactionDetailsScreen.kt,
    @android/app/src/main/java/io/raventag/app/ui/theme/AppStrings.kt,
    @android/app/src/consumer/java/io/raventag/app/config/AppConfig.kt,
    @android/app/src/main/java/io/raventag/app/wallet/cache/TxHistoryDao.kt
  </read_first>
  <behavior>
    Extend TransactionDetailsScreen to (a) render the three-value breakdown for outgoing transactions and (b) add a "View on explorer" OutlinedButton at the bottom of the screen.

    1. Breakdown section — only rendered when `row.isIncoming == false`:
       - Three rows (Column), each row = `Icons.Default.*` 16dp + label + amount Text in the corresponding color:
         - Row "Sent": Icons.Default.CallMade, NotAuthenticRed, label `strings.txHistorySentPrefix`, amount `"-${sentAmount} RVN"`.
         - Row "Cycled": Icons.Default.Autorenew, AuthenticGreen, label `strings.txHistoryCycledPrefix`, amount `"${cycledAmount} RVN"`.
         - Row "Fee":    Icons.Default.AccountBalanceWallet (OR Icons.Default.Payments — pick one consistent icon), RavenMuted, label `strings.txHistoryFeePrefix`, amount `"${feeAmount} RVN"`.
       - Existing recipient-address displays (if any) keep their tap-to-copy behavior.

    2. Self-transfer variant: render ONLY Cycled + Fee rows (no Sent).

    3. Incoming variant: leave the existing breakdown unchanged.

    4. View on explorer OutlinedButton (bottom of scroll container):
       - `OutlinedButton(onClick = { ... }, border = BorderStroke(1.dp, RavenOrange), colors = ButtonDefaults.outlinedButtonColors(contentColor = RavenOrange))`
       - Text: `strings.txDetailsViewOnExplorer` (EN "View on explorer" / IT "Apri su explorer").
       - onClick:
         ```kotlin
         val uri = android.net.Uri.parse(io.raventag.app.config.AppConfig.EXPLORER_URL + txid)
         val intent = android.content.Intent(android.content.Intent.ACTION_VIEW, uri)
         try { context.startActivity(intent) }
         catch (_: android.content.ActivityNotFoundException) { /* silent; no browser available */ }
         ```

    Em-dash audit.
  </behavior>
  <action>
    1) Open `TransactionDetailsScreen.kt`. Identify:
       - The input parameter for the currently-displayed transaction (likely `txid: String` + a fetched `TxHistoryEntry` or `TxHistoryRow`). If the screen currently reads from a `RavencoinPublicNode.TxHistoryEntry`, adapt it to read from `TxHistoryDao.findByTxid(txid)` first and fall back to the network fetch if null. Preserve existing behavior for incoming txs (those already worked in Phase 20).

    2) Add imports (as needed):
       ```kotlin
       import androidx.compose.material.icons.filled.CallMade
       import androidx.compose.material.icons.filled.Autorenew
       import androidx.compose.material.icons.filled.Payments
       import androidx.compose.material3.OutlinedButton
       import androidx.compose.material3.ButtonDefaults
       import androidx.compose.foundation.BorderStroke
       ```

    3) Insert the three-row breakdown Column where the current single-amount rendering lives, gated by `row.isIncoming == false`. When `row.isSelf == true`, render only Cycled + Fee.

    4) Append the OutlinedButton at the very bottom of the scroll container (or inside the screen's main Column, below existing details):
       ```kotlin
       Spacer(Modifier.height(16.dp))
       OutlinedButton(
           onClick = {
               val uri = android.net.Uri.parse(io.raventag.app.config.AppConfig.EXPLORER_URL + txid)
               try {
                   context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, uri))
               } catch (_: android.content.ActivityNotFoundException) { /* silent */ }
           },
           border = androidx.compose.foundation.BorderStroke(1.dp, io.raventag.app.ui.theme.RavenOrange),
           colors = androidx.compose.material3.ButtonDefaults.outlinedButtonColors(
               contentColor = io.raventag.app.ui.theme.RavenOrange
           ),
           modifier = Modifier.fillMaxWidth()
       ) { Text(strings.txDetailsViewOnExplorer) }
       ```

    5) If `context` is not yet accessible, add `val context = LocalContext.current` at the top of the composable.

    Em-dash audit.
  </action>
  <verify>
    <automated>cd android && ./gradlew :app:assembleConsumerDebug -q 2>&1 | tail -30</automated>
  </verify>
  <acceptance_criteria>
    - `grep -q "txDetailsViewOnExplorer" android/app/src/main/java/io/raventag/app/ui/screens/TransactionDetailsScreen.kt`
    - `grep -q "OutlinedButton" android/app/src/main/java/io/raventag/app/ui/screens/TransactionDetailsScreen.kt`
    - `grep -q "AppConfig.EXPLORER_URL" android/app/src/main/java/io/raventag/app/ui/screens/TransactionDetailsScreen.kt`
    - `grep -q "Intent.ACTION_VIEW\|ACTION_VIEW" android/app/src/main/java/io/raventag/app/ui/screens/TransactionDetailsScreen.kt`
    - `grep -q "txHistorySentPrefix" android/app/src/main/java/io/raventag/app/ui/screens/TransactionDetailsScreen.kt`
    - `grep -q "txHistoryCycledPrefix" android/app/src/main/java/io/raventag/app/ui/screens/TransactionDetailsScreen.kt`
    - `grep -q "txHistoryFeePrefix" android/app/src/main/java/io/raventag/app/ui/screens/TransactionDetailsScreen.kt`
    - `grep -q "Icons.Default.CallMade\|Icons.Default.Autorenew" android/app/src/main/java/io/raventag/app/ui/screens/TransactionDetailsScreen.kt`
    - `! grep -P '\u2014' android/app/src/main/java/io/raventag/app/ui/screens/TransactionDetailsScreen.kt`
    - `cd android && ./gradlew :app:assembleConsumerDebug` exits 0.
  </acceptance_criteria>
  <done>Three-value breakdown for outgoing + self-transfer variant + View on explorer OutlinedButton wired to AppConfig.EXPLORER_URL. Build passes. No em dashes.</done>
</task>

</tasks>

<threat_model>
## Trust Boundaries

| Boundary | Description |
|----------|-------------|
| TxHistoryDao (SQLite) → UI | Authoritative local source for the three-value row; writes happen post-broadcast (plan 30-05) and on refresh. UI never writes back. |
| ElectrumX get_history (paged) → TxHistoryDao shell insert | Load-more fallback inserts shells with amount = 0 until the next full refresh; UI shows only the three values from the authoritative row. |
| AppConfig.EXPLORER_URL → external browser Intent | Explicit `Intent.ACTION_VIEW`; URL is compile-time constant; txid appended is public data. ActivityNotFoundException swallowed silently. |

## STRIDE Threat Register

| Threat ID | Category | Component | Disposition | Mitigation Plan |
|-----------|----------|-----------|-------------|-----------------|
| T-30-UTXO-08 | Tampering | Stale tx_history rows cause reservation mismatch after reorg | mitigate | Reconciliation loop (plan 30-05) runs on every refresh and deletes released reservations. The UI reads the latest row; no independent caching layer. |
| T-30-UTXO-09 | Information Disclosure | "View on explorer" Intent leaks txid to the browser / third-party service | accept | txid is public blockchain data — any node observer can see it. User-level fix is Tor / custom explorer; deferred. ASVS V9.2. |
| T-30-UTXO-10 | Tampering | Malicious ElectrumX node returns manipulated get_history list (Load more fallback) | mitigate | Shells written to DB carry only txid/height/confirmations; amounts remain 0 until the next authoritative refresh via `getTransactionHistory` (existing Phase 20 path with TOFU + retry). UI displays "0 RVN" for un-enriched shells — visible indicator that a refresh is needed. |
| T-30-UTXO-11 | Denial of Service | User clicks Load more repeatedly, flooding the ElectrumX node | mitigate | `retryWithBackoff` already wraps `RavencoinPublicNode.callWithFailover` in the existing code; the Load more path inherits this. Additional protection: disable the button while a fetch is in-flight (`loadMore` is a `suspend` with a single-flight guard — add a `var loadingMore by remember { mutableStateOf(false) }`). |
| T-30-UTXO-12 | Spoofing | Explorer URL hijacked via network / DNS to lead to a phishing site | accept | User authenticates to no service on the explorer page; any attacker redirect costs only user time. The real risk is reduced by hardcoding the URL in AppConfig (no runtime override in v1). |

ASVS V5 Input Validation (Intent URI built from compile-time prefix + validated txid hex), V9 Communications (HTTPS explorer URL), V7 Error Handling (ActivityNotFoundException silent). ASVS L1 adequate.
</threat_model>

<verification>
- `cd android && ./gradlew :app:assembleConsumerDebug` exits 0.
- `cd android && ./gradlew :app:assembleBrandDebug` exits 0.
- `cd android && ./gradlew :app:testConsumerDebugUnitTest -i` — Wave 0 tests remain GREEN (this plan adds no new tests, only UI + helpers).
- `! grep -rP '\u2014' android/app/src/main/java/io/raventag/app/ui/screens/WalletScreen.kt android/app/src/main/java/io/raventag/app/ui/screens/TransactionDetailsScreen.kt android/app/src/main/java/io/raventag/app/wallet/RavencoinPublicNode.kt android/app/src/main/java/io/raventag/app/wallet/cache/TxHistoryDao.kt android/app/src/consumer/java/io/raventag/app/config/AppConfig.kt android/app/src/brand/java/io/raventag/app/config/AppConfig.kt android/app/src/main/java/io/raventag/app/ui/theme/AppStrings.kt` returns no matches.
- Manual device verification (per 30-VALIDATION.md):
  1. Open WalletScreen on a wallet with prior outgoing txs. Verify the outgoing row shows three lines (Sent, Cycled, Fee) right-aligned, decimals in 10sp style, with the correct colors (NotAuthenticRed / AuthenticGreen / RavenMuted).
  2. Send 5 RVN externally. After broadcast, WalletScreen prepends the tx as outgoing with `Sent -5 RVN · Cycled (balance - 5 - fee) RVN · Fee <fee> RVN`.
  3. Trigger a consolidation (send from an old address to self). Row renders `Cycled X RVN · Fee Y RVN` on a single line with Autorenew icon.
  4. Receive 1 RVN. Incoming row remains single-amount `+1 RVN` with CallReceived icon (unchanged).
  5. Scroll the history; tap Load more. 20 additional rows append. Repeat until the network returns an empty page; button disappears.
  6. Open a tx; tap "View on explorer" / "Apri su explorer". Browser opens `https://<explorer>/tx/<txid>`.
  7. Toggle locale to Italian. Reopen WalletScreen; labels show `Inviato / Ciclato / Fee / Carica altre`. TxDetails shows `Apri su explorer`.
  8. Empty-state test: fresh install, no network. History section renders `No transactions yet` + body copy.
</verification>

<success_criteria>
- RavencoinPublicNode.getHistoryPaged + RavencoinTxHistoryMath.computeCycledSat/computeSentSat compile; existing methods untouched.
- TxHistoryDao exposes `getPage(offset, limit)` alias.
- Both flavor AppConfig.kt files export `EXPLORER_URL` as a const terminating in `/tx/`.
- AppStrings.kt has every new EN + IT key verbatim from UI-SPEC.
- WalletScreen TxCard renders three-value outgoing row, self-transfer variant, preserved incoming row, correct confirmation dot color, Load more button, empty state.
- TransactionDetailsScreen renders three-value breakdown (Sent/Cycled/Fee) + View on explorer OutlinedButton.
- `./gradlew :app:assembleConsumerDebug` + `:app:assembleBrandDebug` both exit 0.
- `! grep -P '\u2014'` on every touched file returns no matches.
</success_criteria>

<output>
After completion, create `.planning/phases/30-wallet-reliability/30-09-SUMMARY.md`:
- Chosen EXPLORER_URL literal (exact string) and the community source consulted.
- Exact line number where `TxCard` composable begins in WalletScreen.kt before and after the rewrite.
- Whether the WalletScreen history binding was refactored to consume `TxHistoryDao.TxHistoryRow` directly (preferred) or kept a bridge from `TxHistoryEntry`.
- Whether `loadMore()` lives inline in WalletScreen or was added to an existing ViewModel — plus the exact function signature.
- Hand-off to plan 30-10: housekeeping must (a) delete `consolidate_fix.kt` IF it exists; (b) include WalletScreen.kt, TransactionDetailsScreen.kt, RavencoinPublicNode.kt, TxHistoryDao.kt, both AppConfig.kt files, and AppStrings.kt in the em-dash audit sweep.
</output>
