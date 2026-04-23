---
id: 30-01-wave0-test-scaffolding
phase: 30
plan: 01
type: execute
wave: 0
depends_on: []
files_modified:
  - android/app/src/test/java/io/raventag/app/wallet/cache/WalletCacheDaoTest.kt
  - android/app/src/test/java/io/raventag/app/wallet/cache/ReservedUtxoDaoTest.kt
  - android/app/src/test/java/io/raventag/app/wallet/subscription/SubscriptionParserTest.kt
  - android/app/src/test/java/io/raventag/app/wallet/fee/FeeEstimatorTest.kt
  - android/app/src/test/java/io/raventag/app/wallet/WalletManagerMnemonicTest.kt
  - android/app/src/test/java/io/raventag/app/wallet/RavencoinTxBuilderTest.kt
autonomous: true
requirements:
  - WALLET-BAL
  - WALLET-SEND
  - WALLET-RECV
  - WALLET-UTXO
  - WALLET-MNEM
  - WALLET-KEYS
threat_refs:
  - T-30-MNEM
  - T-30-KEYS
  - T-30-RECV
  - T-30-UTXO

must_haves:
  truths:
    - "Every automated command referenced in later plans points at a test file that exists and compiles"
    - "Each failing test encodes a precise behavior contract for the Wave 1/2 implementation"
    - "Every new test file has JUnit 4 `@Test`-annotated methods matching the names in 30-VALIDATION.md per-task map"
  artifacts:
    - path: "android/app/src/test/java/io/raventag/app/wallet/cache/WalletCacheDaoTest.kt"
      provides: "D-04 cache roundtrip + D-20 reservation math tests"
    - path: "android/app/src/test/java/io/raventag/app/wallet/cache/ReservedUtxoDaoTest.kt"
      provides: "D-20 reservation lifecycle + Pitfall 6 crash-prune tests"
    - path: "android/app/src/test/java/io/raventag/app/wallet/subscription/SubscriptionParserTest.kt"
      provides: "D-05 JSON-RPC id-matching vs notification parsing (Pitfall 1)"
    - path: "android/app/src/test/java/io/raventag/app/wallet/fee/FeeEstimatorTest.kt"
      provides: "D-22 fallback to 0.01 RVN/kB contract"
    - path: "android/app/src/test/java/io/raventag/app/wallet/WalletManagerMnemonicTest.kt"
      provides: "D-14/D-15/D-16 + Pitfall 7 behavioral tests"
    - path: "android/app/src/test/java/io/raventag/app/wallet/RavencoinTxBuilderTest.kt"
      provides: "D-19 cycled-amount change-address assertion (extended)"
  key_links:
    - from: "every Wave 1-3 PLAN.md `<automated>` block"
      to: "a `@Test fun ...` method in this plan"
      via: "Gradle `--tests` glob `*WalletCacheDaoTest.roundtrip*` etc."
      pattern: "@Test"
---

<objective>
Create Wave 0 test scaffolding per Nyquist (every `<automated>` verify command in later plans must point at an existing test). Writes six test files (five new, one extended) that compile and **deliberately fail** — they encode the behavior contracts Wave 1-3 will satisfy. No production code is written in this plan.

Purpose: Guarantee fast feedback (<60s) from the very first Wave 1 commit, and prevent "missing test file" verify failures during execution.
Output: six files under `android/app/src/test/java/io/raventag/app/` that `./gradlew :app:testConsumerDebugUnitTest -i` compiles and runs, producing RED results for every new test method.
</objective>

<execution_context>
@$HOME/.claude/get-shit-done/workflows/execute-plan.md
@$HOME/.claude/get-shit-done/templates/summary.md
</execution_context>

<context>
@.planning/STATE.md
@.planning/ROADMAP.md
@.planning/phases/30-wallet-reliability/30-CONTEXT.md
@.planning/phases/30-wallet-reliability/30-RESEARCH.md
@.planning/phases/30-wallet-reliability/30-PATTERNS.md
@.planning/phases/30-wallet-reliability/30-VALIDATION.md
@android/app/src/test/java/io/raventag/app/wallet/RavencoinTxBuilderTest.kt
@android/app/build.gradle.kts

<interfaces>
The tests reference types that will be created in Wave 1/2. Use fully-qualified references and compile against the planned class signatures below. Because these classes do not exist yet, each test file must define a **package-private stub** at the top (or import expected from the `.cache` / `.subscription` / `.fee` / `.security` subpackages with a `@Suppress("unused", "UNUSED_PARAMETER")` comment) so that the test file compiles even while classes are missing. Preferred strategy: declare the expected classes as `expect class` is not an option in pure JVM test, so instead write the test against an inline `object Stub` with a TODO()-ing API that Wave 1 will delete. This gives us RED tests that fail with `NotImplementedError`/`AssertionError`, which is a legitimate RED state per TDD.

Planned Wave 1 signatures (write tests against these):

```kotlin
// wallet/cache/WalletCacheDao.kt  (plan 30-02)
object WalletCacheDao {
    fun init(context: android.content.Context)
    fun writeState(utxos: List<io.raventag.app.wallet.Utxo>, assetUtxos: Map<String, List<io.raventag.app.wallet.AssetUtxo>>, blockHeight: Int)
    fun readState(): CachedWalletState?
    fun getLastRefreshedAt(): Long
    // returns sum(utxo.value) - sum(reserved.value), coerced >= 0
    fun computeSpendableBalanceSat(utxos: List<io.raventag.app.wallet.Utxo>): Long
    data class CachedWalletState(
        val walletId: String,
        val balanceSat: Long,
        val utxos: List<io.raventag.app.wallet.Utxo>,
        val assetUtxos: Map<String, List<io.raventag.app.wallet.AssetUtxo>>,
        val blockHeight: Int,
        val lastRefreshedAt: Long
    )
}

// wallet/cache/ReservedUtxoDao.kt (plan 30-02)
object ReservedUtxoDao {
    fun init(context: android.content.Context)
    data class ReservedUtxo(val txidIn: String, val vout: Int, val valueSat: Long, val submittedTxid: String, val submittedAt: Long)
    fun reserve(entries: List<ReservedUtxo>)
    fun releaseFor(submittedTxid: String)
    fun sumReservedSat(): Long
    fun pruneOlderThan(thresholdMillis: Long)
    fun all(): List<ReservedUtxo>
}

// wallet/subscription/SubscriptionParser.kt  (plan 30-03)
object SubscriptionParser {
    sealed class Parsed {
        data class Response(val id: Int, val result: com.google.gson.JsonElement?) : Parsed()
        data class Notification(val scripthash: String, val status: String?) : Parsed()
        data class Unknown(val raw: String) : Parsed()
    }
    fun parseLine(line: String): Parsed
}

// wallet/fee/FeeEstimator.kt  (plan 30-04)
class FeeEstimator(private val node: io.raventag.app.wallet.RavencoinPublicNode) {
    // Returns sat/kB. Falls back to 1_000_000 sat/kB (= 0.01 RVN/kB) when estimate <= 0 or throws.
    suspend fun estimateSatPerKb(targetBlocks: Int = 6): Long
    companion object { const val FALLBACK_SAT_PER_KB: Long = 1_000_000L }
}

// wallet/WalletManager.kt extensions (plan 30-06)
class BackupRequiredException(msg: String = "backup required before restore") : RuntimeException(msg)
class IntegrityException(msg: String = "seed HMAC mismatch") : RuntimeException(msg)
class KeystoreInvalidatedException(cause: Throwable? = null) : RuntimeException("keystore invalidated", cause)
```

For each test file, include at the top:
```kotlin
// Wave 0 tests. Wave 1-3 implementations will replace the Stub objects below with real classes.
// Until then, tests MUST fail. Do not make them pass by weakening assertions.
```
</interfaces>
</context>

<tasks>

<task type="auto" tdd="true">
  <name>Task 1: Write WalletCacheDao + ReservedUtxoDao + SubscriptionParser + FeeEstimator tests</name>
  <files>
    android/app/src/test/java/io/raventag/app/wallet/cache/WalletCacheDaoTest.kt,
    android/app/src/test/java/io/raventag/app/wallet/cache/ReservedUtxoDaoTest.kt,
    android/app/src/test/java/io/raventag/app/wallet/subscription/SubscriptionParserTest.kt,
    android/app/src/test/java/io/raventag/app/wallet/fee/FeeEstimatorTest.kt
  </files>
  <read_first>
    @.planning/phases/30-wallet-reliability/30-VALIDATION.md#L37-L55,
    @.planning/phases/30-wallet-reliability/30-RESEARCH.md#L346-L403,
    @.planning/phases/30-wallet-reliability/30-RESEARCH.md#L467-L521,
    @.planning/phases/30-wallet-reliability/30-RESEARCH.md#L540-L590,
    @.planning/phases/30-wallet-reliability/30-PATTERNS.md#L34-L112,
    @android/app/src/test/java/io/raventag/app/wallet/RavencoinTxBuilderTest.kt
  </read_first>
  <behavior>
    WalletCacheDaoTest (test class in `io.raventag.app.wallet.cache`):
    - `@Test fun roundtrip_preserves_utxos_and_timestamp()`: write 3 RVN UTXOs + 1 asset UTXO map + blockHeight=42, read back. Assert balance_sat, utxos JSON deserialized to the same list, asset_utxos same, block_height==42, lastRefreshedAt within ±2s of System.currentTimeMillis() at write.
    - `@Test fun balance_subtracts_reserved_never_negative()`: seed reserved_utxos with SUM = 500_000_000 sat, call computeSpendableBalanceSat with UTXOs summing 300_000_000 sat. Assert result == 0 (coerceAtLeast(0) per A6 in RESEARCH.md).
    - `@Test fun balance_subtracts_reserved_positive()`: 3 UTXOs = 1_000_000_000 sat, reserved = 250_000_000. Assert computeSpendableBalanceSat == 750_000_000.

    ReservedUtxoDaoTest (class in `io.raventag.app.wallet.cache`):
    - `@Test fun insert_on_broadcast_records_all_inputs()`: reserve(listOf(ReservedUtxo("txA",0,100,"subX",now), ReservedUtxo("txA",1,200,"subX",now))). Assert all() returns exactly 2 rows with submittedTxid=="subX".
    - `@Test fun cleanup_on_confirm_removes_rows_for_submitted_txid()`: reserve 3 rows for "subY" + 1 row for "subZ". releaseFor("subY"). Assert all().size==1 && first.submittedTxid=="subZ".
    - `@Test fun prune_stale_removes_rows_older_than_48h()`: insert row with submittedAt = now-49h; insert row with submittedAt = now-1h. pruneOlderThan(now - 48L*3600_000). Assert remaining.size==1 && remaining[0].submittedAt > now-2*3600_000.
    - `@Test fun sum_reserved_returns_total_value()`: insert 3 rows with values 100, 250, 999. Assert sumReservedSat() == 1349.

    SubscriptionParserTest (class in `io.raventag.app.wallet.subscription`):
    - `@Test fun parses_response_with_id_as_Response()`: `{"id":42,"result":"abc","jsonrpc":"2.0"}` → `Parsed.Response(id=42, result=JsonPrimitive("abc"))`.
    - `@Test fun parses_scripthash_notification_as_Notification()`: `{"jsonrpc":"2.0","method":"blockchain.scripthash.subscribe","params":["a1b2","statusHash"]}` → `Parsed.Notification(scripthash="a1b2", status="statusHash")`.
    - `@Test fun parses_scripthash_notification_with_null_status()`: params[1] is JsonNull → status == null.
    - `@Test fun parses_response_with_null_result()`: `{"id":3,"result":null}` → `Parsed.Response(id=3, result=JsonNull)` (result MAY be `com.google.gson.JsonNull.INSTANCE` or `null`; accept either — document which in the implementation).
    - `@Test fun unknown_method_falls_through_to_Unknown()`: `{"jsonrpc":"2.0","method":"server.ping"}` → `Parsed.Unknown`.
    - `@Test fun malformed_json_throws_or_returns_Unknown()`: input `"not json"` → accept either `IllegalArgumentException` OR `Parsed.Unknown`. Pin behavior: test with `assertDoesNotThrow` wrapped result type check; update once implementation decides. For Wave 0, assert `runCatching { parseLine("not json") }.let { it.isFailure || (it.getOrNull() is Parsed.Unknown) }`.

    FeeEstimatorTest (class in `io.raventag.app.wallet.fee`):
    Use a **test fake** `FakeNode : RavencoinPublicNode(ctx)` or simpler: accept a functional interface for the estimate call. Since `RavencoinPublicNode` constructor requires Context, the cleanest pattern is to inject a lambda `estimateFeeProvider: suspend (Int) -> Double` into `FeeEstimator` via a secondary constructor or interface. Write tests against that lambda-injectable constructor; Wave 1 plan 30-04 must honor it.
    - `@Test fun fallback_when_estimate_returns_negative_one()`: lambda returns `-1.0`. Assert `estimateSatPerKb(6) == 1_000_000L`.
    - `@Test fun fallback_when_estimate_returns_zero()`: lambda returns `0.0`. Assert `estimateSatPerKb(6) == 1_000_000L`.
    - `@Test fun fallback_when_estimate_throws_IOException()`: lambda throws `java.io.IOException("timeout")`. Assert `estimateSatPerKb(6) == 1_000_000L`.
    - `@Test fun converts_rvn_per_kb_to_sat_per_kb()`: lambda returns `0.002` (= 0.002 RVN/kB = 200_000 sat/kB). Assert `estimateSatPerKb(6) == 200_000L`.
    - `@Test fun passes_target_blocks_to_lambda()`: capture int arg, call `estimateSatPerKb(12)`, assert captured == 12.
  </behavior>
  <action>
    For each of the four test files, create the package directory and write a JUnit 4 test class. Every test uses `org.junit.Assert.*` and `org.junit.Test`, and tests that require `android.content.Context` use `androidx.test.core.app.ApplicationProvider.getApplicationContext<Context>()` (already available via `androidx.test.ext:junit` test dep; run with `org.junit.runner.RunWith(AndroidJUnit4::class)` from `androidx.test.ext.junit.runners.AndroidJUnit4`) OR Robolectric if already on the classpath — `RavencoinTxBuilderTest.kt` already runs pure JVM without Android, so check: if the Context-requiring tests cannot run in the JVM unit test flavor, use `androidx.test.ext.junit.runners.AndroidJUnit4` + `@Config(manifest = Config.NONE)` only if Robolectric is on classpath; otherwise mark the tests `@Ignore("requires Android runtime — Wave 0 scaffolding")` and document in the plan summary. Primary goal: files compile and tests are executable or ignored.

    Preferred approach (simpler): Do NOT require Context in the DAO test files. Instead, introduce an interface shim in each test file like:
    ```kotlin
    private interface WalletCacheStore {
        fun writeState(utxos: List<Utxo>, assetUtxos: Map<String, List<AssetUtxo>>, blockHeight: Int)
        fun readState(): WalletCacheDao.CachedWalletState?
        fun computeSpendableBalanceSat(utxos: List<Utxo>, reservedSat: Long): Long
    }
    ```
    and write an in-test `InMemoryStore` implementing the shim, plus a helper test that exercises `WalletCacheDao.computeSpendableBalanceSat` as a pure function (the only assertion that does NOT require SQLite). Mark the SQLite-roundtrip tests with `@Ignore("requires Android runtime — implementation in plan 30-02")` and leave the balance-subtracts-reserved pure-function tests un-ignored. The pure-function tests must FAIL with `NotImplementedError` because `WalletCacheDao.computeSpendableBalanceSat` does not yet exist — this is the valid RED state.

    Concrete: at top of WalletCacheDaoTest.kt, write:
    ```kotlin
    package io.raventag.app.wallet.cache
    import io.raventag.app.wallet.Utxo
    import io.raventag.app.wallet.AssetUtxo
    import org.junit.Assert.assertEquals
    import org.junit.Ignore
    import org.junit.Test

    class WalletCacheDaoTest {
        @Test fun balance_subtracts_reserved_never_negative() {
            val utxos = listOf(Utxo(txid="a", vout=0, value=300_000_000L, height=100))
            val reserved = 500_000_000L
            // WalletCacheDao.computeSpendableBalanceSat signature: (utxos, reservedSat) -> Long
            val spendable = WalletCacheDao.computeSpendableBalanceSat(utxos, reserved)
            assertEquals(0L, spendable)
        }
        @Test fun balance_subtracts_reserved_positive() { /* as in behavior */ }
        @Ignore("requires Android Context — implemented by plan 30-02")
        @Test fun roundtrip_preserves_utxos_and_timestamp() { /* stub body calling TODO() */ }
    }
    ```
    Note: `WalletCacheDao.computeSpendableBalanceSat` accepts `(List<Utxo>, Long)` per the inline spec — plan 30-02 MUST honor this signature. If Wave 1 decides to compute `reservedSat` internally via SQLite, expose a pure overload `fun computeSpendableBalanceSat(utxos: List<Utxo>, reservedSat: Long): Long` alongside.

    Verify that `android/app/build.gradle.kts` already declares `testImplementation("junit:junit:4.13.2")` or compatible and `testImplementation("com.google.code.gson:gson:2.10.1")` — it does because `RavencoinTxBuilderTest.kt` compiles. If Gson is not already on the test classpath, add `testImplementation` for it in build.gradle.kts. Do NOT add other new test deps.

    Em-dash audit: `! grep -P '\u2014' android/app/src/test/java/io/raventag/app/wallet/cache/WalletCacheDaoTest.kt android/app/src/test/java/io/raventag/app/wallet/cache/ReservedUtxoDaoTest.kt android/app/src/test/java/io/raventag/app/wallet/subscription/SubscriptionParserTest.kt android/app/src/test/java/io/raventag/app/wallet/fee/FeeEstimatorTest.kt`
  </action>
  <verify>
    <automated>cd android && ./gradlew :app:compileConsumerDebugUnitTestKotlin -q 2>&1 | tail -20 ; test $? -eq 0</automated>
  </verify>
  <acceptance_criteria>
    - `test -f android/app/src/test/java/io/raventag/app/wallet/cache/WalletCacheDaoTest.kt`
    - `test -f android/app/src/test/java/io/raventag/app/wallet/cache/ReservedUtxoDaoTest.kt`
    - `test -f android/app/src/test/java/io/raventag/app/wallet/subscription/SubscriptionParserTest.kt`
    - `test -f android/app/src/test/java/io/raventag/app/wallet/fee/FeeEstimatorTest.kt`
    - `grep -q "class WalletCacheDaoTest" android/app/src/test/java/io/raventag/app/wallet/cache/WalletCacheDaoTest.kt`
    - `grep -q "fun balance_subtracts_reserved_never_negative" android/app/src/test/java/io/raventag/app/wallet/cache/WalletCacheDaoTest.kt`
    - `grep -q "fun roundtrip" android/app/src/test/java/io/raventag/app/wallet/cache/WalletCacheDaoTest.kt`
    - `grep -q "fun insert_on_broadcast" android/app/src/test/java/io/raventag/app/wallet/cache/ReservedUtxoDaoTest.kt`
    - `grep -q "fun cleanup_on_confirm" android/app/src/test/java/io/raventag/app/wallet/cache/ReservedUtxoDaoTest.kt`
    - `grep -q "fun prune_stale" android/app/src/test/java/io/raventag/app/wallet/cache/ReservedUtxoDaoTest.kt`
    - `grep -q "class SubscriptionParserTest" android/app/src/test/java/io/raventag/app/wallet/subscription/SubscriptionParserTest.kt`
    - `grep -q "class FeeEstimatorTest" android/app/src/test/java/io/raventag/app/wallet/fee/FeeEstimatorTest.kt`
    - `grep -q "fun fallback" android/app/src/test/java/io/raventag/app/wallet/fee/FeeEstimatorTest.kt`
    - `! grep -P '\u2014' android/app/src/test/java/io/raventag/app/wallet/cache/WalletCacheDaoTest.kt android/app/src/test/java/io/raventag/app/wallet/cache/ReservedUtxoDaoTest.kt android/app/src/test/java/io/raventag/app/wallet/subscription/SubscriptionParserTest.kt android/app/src/test/java/io/raventag/app/wallet/fee/FeeEstimatorTest.kt`
    - `cd android && ./gradlew :app:compileConsumerDebugUnitTestKotlin` exits 0 (compile clean; tests may reference not-yet-existing classes in interface stubs but must be resolved via top-of-file type stubs or real Wave 1 types planned for 30-02/30-03/30-04; the compile step must succeed for the plan to be considered done).
    - Running `./gradlew :app:testConsumerDebugUnitTest --tests "*WalletCacheDaoTest*"` exits **non-zero** with at least one assertion failure (RED), OR all tests `@Ignore`-marked with a clear reason pointing to plan 30-02 (acceptable fallback for Context-dependent cases).
  </acceptance_criteria>
  <done>Four test files compile. Pure-function tests fail RED (correct Nyquist state); Context-dependent tests are explicitly `@Ignore`d with a reason referencing their implementing plan. Every assertion in the behavior block above is represented by a `@Test` function with the exact name listed.</done>
</task>

<task type="auto" tdd="true">
  <name>Task 2: Write WalletManagerMnemonicTest + extend RavencoinTxBuilderTest</name>
  <files>
    android/app/src/test/java/io/raventag/app/wallet/WalletManagerMnemonicTest.kt,
    android/app/src/test/java/io/raventag/app/wallet/RavencoinTxBuilderTest.kt
  </files>
  <read_first>
    @.planning/phases/30-wallet-reliability/30-VALIDATION.md#L50-L55,
    @.planning/phases/30-wallet-reliability/30-RESEARCH.md#L486-L537,
    @.planning/phases/30-wallet-reliability/30-CONTEXT.md#L37-L45,
    @android/app/src/test/java/io/raventag/app/wallet/RavencoinTxBuilderTest.kt,
    @android/app/src/main/java/io/raventag/app/wallet/WalletManager.kt
  </read_first>
  <behavior>
    WalletManagerMnemonicTest:
    - `@Test fun validateMnemonic_rejects_padding()`: a valid 12-word phrase with trailing newline + tabs normalizes correctly and passes; phrase with embedded extra blank word (two spaces in the middle) normalizes correctly and passes. Phrase with 13 real words (one added) throws `IllegalArgumentException`. Use a known-good BIP39 12-word phrase from BIP39 test vectors, e.g. `"abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about"`. Assert `WalletManager.Companion.validateMnemonic(input)` returns normalized 12-word list (the companion `validateMnemonic` must be exposed — if it's `private`, Wave 2 plan 30-06 promotes it to `internal`).
    - `@Test fun restore_forces_backup_when_wallet_non_zero_and_not_backed_up()`: construct scenario: current wallet state reports balance > 0 AND `hasBackedUpCurrentMnemonic == false`. Call the new `WalletManager.checkRestorePreconditions(currentBalanceSat = 100_000_000L, hasBackedUp = false)` method (stub signature; to be added in 30-06). Assert it throws `BackupRequiredException`. Call with `hasBackedUp = true` → returns Unit (no throw). Call with `currentBalanceSat = 0L, hasBackedUp = false` → no throw.
    - `@Test fun hmac_integrity_mismatch_throws()`: call `WalletManager.verifySeedHmac(seed = byteArrayOf(1,2,3), storedTag = byteArrayOf(9,9,9))` (stub signature; to be added in 30-06). Assert throws `IntegrityException`. Same call with a correct HMAC returns true.
    - `@Test fun key_invalidated_routes_to_restore()`: call `WalletManager.wrapKeystoreException { throw android.security.keystore.KeyPermanentlyInvalidatedException() }` (stub helper; 30-06 implements as `internal inline fun <T> wrapKeystoreException(block: () -> T): T`). Assert rethrows as `KeystoreInvalidatedException` with the original as `cause`. Generic `IOException` is NOT wrapped.
    - Because WalletManager requires Context for the real init flow, any test that exercises Keystore must be `@Ignore("requires Android runtime — instrumented test")`. Pure-logic helpers (`validateMnemonic`, `checkRestorePreconditions`, `verifySeedHmac`, `wrapKeystoreException`) must be authored so they do NOT depend on Context and can run in pure JVM unit tests.

    RavencoinTxBuilderTest.kt extension (keep all existing tests):
    - `@Test fun multiAddressSend_change_to_fresh_address()`: construct (or reuse existing fixtures in the file) a multi-address send where the builder is passed `changeAddress = "FRESH_ADDR_0xABC"`. Parse the built raw tx and assert: at least one output has `scriptPubKey` matching `OP_DUP OP_HASH160 <hash160 of FRESH_ADDR_0xABC> OP_EQUALVERIFY OP_CHECKSIG` (i.e. a P2PKH output to that address) AND the sum of outputs to any other external address does NOT include the cycled amount. If the existing test file already has a helper like `buildMultiAddressSend(...)`, call it; otherwise construct the inputs/outputs manually matching the existing test harness. The exact existing helper to reuse MUST be located during execution by reading `RavencoinTxBuilderTest.kt` top-to-bottom — use the same private `ECKey` fixture keys and `TestContext` fake if present.
  </behavior>
  <action>
    WalletManagerMnemonicTest: create at `android/app/src/test/java/io/raventag/app/wallet/WalletManagerMnemonicTest.kt`. Use pure JUnit 4:
    ```kotlin
    package io.raventag.app.wallet
    import org.junit.Assert.*
    import org.junit.Test
    import org.junit.Ignore
    import io.raventag.app.wallet.BackupRequiredException
    import io.raventag.app.wallet.IntegrityException
    import io.raventag.app.wallet.KeystoreInvalidatedException

    class WalletManagerMnemonicTest {
        private val validPhrase = "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about"

        @Test fun validateMnemonic_rejects_padding() {
            val normalized = WalletManager.validateMnemonic("$validPhrase \n\t")
            assertEquals(12, normalized.size)
            assertEquals("about", normalized.last())
            assertEquals(12, WalletManager.validateMnemonic("  $validPhrase  ").size)
            val thirteen = "$validPhrase apple"
            try { WalletManager.validateMnemonic(thirteen); fail("expected throw") } catch (_: IllegalArgumentException) { /* ok */ }
        }

        @Test fun restore_forces_backup_when_wallet_non_zero_and_not_backed_up() {
            try {
                WalletManager.checkRestorePreconditions(currentBalanceSat = 100_000_000L, hasBackedUp = false)
                fail("expected BackupRequiredException")
            } catch (_: BackupRequiredException) { /* ok */ }
            WalletManager.checkRestorePreconditions(currentBalanceSat = 100_000_000L, hasBackedUp = true)
            WalletManager.checkRestorePreconditions(currentBalanceSat = 0L, hasBackedUp = false)
        }

        @Test fun hmac_integrity_mismatch_throws() {
            val seed = byteArrayOf(1, 2, 3)
            val goodTag = WalletManager.computeSeedHmacForTest(seed, keyBytes = ByteArray(32) { it.toByte() })
            WalletManager.verifySeedHmac(seed, goodTag, keyBytes = ByteArray(32) { it.toByte() })
            try {
                WalletManager.verifySeedHmac(seed, byteArrayOf(9, 9, 9), keyBytes = ByteArray(32) { it.toByte() })
                fail("expected IntegrityException")
            } catch (_: IntegrityException) { /* ok */ }
        }

        @Test fun key_invalidated_routes_to_restore() {
            try {
                WalletManager.wrapKeystoreException<Unit> {
                    throw android.security.keystore.KeyPermanentlyInvalidatedException()
                }
                fail("expected KeystoreInvalidatedException")
            } catch (e: KeystoreInvalidatedException) {
                assertTrue(e.cause is android.security.keystore.KeyPermanentlyInvalidatedException)
            }
            // IOException should NOT be wrapped
            try {
                WalletManager.wrapKeystoreException<Unit> { throw java.io.IOException("transient") }
                fail("expected passthrough IOException")
            } catch (e: java.io.IOException) { assertEquals("transient", e.message) }
        }
    }
    ```
    NOTE: `WalletManager.computeSeedHmacForTest` is a test-only helper that plan 30-06 MUST add (it uses the same BouncyCastle `HMac(SHA256Digest())` as the production helper but takes the key as raw bytes instead of fetching from Keystore). Signal this in the plan summary.

    RavencoinTxBuilderTest extension: read the current file fully to understand its fixture style. Append a new `@Test fun multiAddressSend_change_to_fresh_address()` at the bottom of the existing class (do NOT create a second class). Reuse any existing private helper to call `buildAndSignMultiAddressSend` with a known `changeAddress`. Parse outputs; assert the P2PKH output to `changeAddress` exists and carries the expected cycled value. The test MUST compile even if it fails — use explicit imports. If the existing test file uses extension functions or companion helpers to construct fake UTXOs, reuse them.

    Em-dash audit: `! grep -P '\u2014' android/app/src/test/java/io/raventag/app/wallet/WalletManagerMnemonicTest.kt android/app/src/test/java/io/raventag/app/wallet/RavencoinTxBuilderTest.kt`.
  </action>
  <verify>
    <automated>cd android && ./gradlew :app:compileConsumerDebugUnitTestKotlin -q 2>&1 | tail -30 ; test $? -eq 0</automated>
  </verify>
  <acceptance_criteria>
    - `test -f android/app/src/test/java/io/raventag/app/wallet/WalletManagerMnemonicTest.kt`
    - `grep -q "class WalletManagerMnemonicTest" android/app/src/test/java/io/raventag/app/wallet/WalletManagerMnemonicTest.kt`
    - `grep -q "fun validateMnemonic_rejects_padding" android/app/src/test/java/io/raventag/app/wallet/WalletManagerMnemonicTest.kt`
    - `grep -q "fun restore_forces_backup" android/app/src/test/java/io/raventag/app/wallet/WalletManagerMnemonicTest.kt`
    - `grep -q "fun hmac_integrity_mismatch_throws" android/app/src/test/java/io/raventag/app/wallet/WalletManagerMnemonicTest.kt`
    - `grep -q "fun key_invalidated_routes_to_restore" android/app/src/test/java/io/raventag/app/wallet/WalletManagerMnemonicTest.kt`
    - `grep -q "multiAddressSend_change_to_fresh_address" android/app/src/test/java/io/raventag/app/wallet/RavencoinTxBuilderTest.kt`
    - `! grep -P '\u2014' android/app/src/test/java/io/raventag/app/wallet/WalletManagerMnemonicTest.kt android/app/src/test/java/io/raventag/app/wallet/RavencoinTxBuilderTest.kt`
    - `cd android && ./gradlew :app:compileConsumerDebugUnitTestKotlin` exits 0.
    - `cd android && ./gradlew :app:testConsumerDebugUnitTest --tests "*WalletManagerMnemonicTest*"` exits NON-zero (RED state because 30-06 has not implemented the helpers yet) — or tests fail with `NoSuchMethodError`/`Unresolved reference` at compile time only if Wave 0 uses stub type declarations; we require the types (`BackupRequiredException`, `IntegrityException`, `KeystoreInvalidatedException`) to be declared minimally here (see note below) so the file compiles.
  </acceptance_criteria>
  <done>
    Both test files compile. WalletManagerMnemonicTest fails RED (methods not implemented yet). The RavencoinTxBuilderTest extension test either fails RED (no `changeAddress` guarantee yet) or passes GREEN (existing builder already satisfies it — acceptable since D-17 is already implemented per RESEARCH.md L92; the test then serves as a regression guard).

    **Compile-bootstrap note (critical for downstream):** To make `WalletManagerMnemonicTest.kt` compile before plan 30-06 runs, this task MUST also create a minimal stub file `android/app/src/main/java/io/raventag/app/wallet/WalletExceptions.kt` containing ONLY the exception class declarations:
    ```kotlin
    package io.raventag.app.wallet
    class BackupRequiredException(msg: String = "backup required before restore") : RuntimeException(msg)
    class IntegrityException(msg: String = "seed HMAC mismatch") : RuntimeException(msg)
    class KeystoreInvalidatedException(cause: Throwable? = null) : RuntimeException("keystore invalidated", cause)
    ```
    Plan 30-06 will leave this file in place (adding methods to `WalletManager`, not moving the exceptions). Add this file path to `files_modified` acceptance criteria:
    - `test -f android/app/src/main/java/io/raventag/app/wallet/WalletExceptions.kt`
    - `grep -q "class BackupRequiredException" android/app/src/main/java/io/raventag/app/wallet/WalletExceptions.kt`
    - `grep -q "class KeystoreInvalidatedException" android/app/src/main/java/io/raventag/app/wallet/WalletExceptions.kt`

    Additionally, `WalletManager` MUST gain four no-op / throwing helper stubs in this plan (plan 30-06 replaces the bodies with real implementations). Add to `WalletManager.kt` companion object:
    ```kotlin
    companion object {
        @JvmStatic fun validateMnemonic(input: String): List<String> = TODO("30-06")
        @JvmStatic fun checkRestorePreconditions(currentBalanceSat: Long, hasBackedUp: Boolean) { TODO("30-06") }
        @JvmStatic fun computeSeedHmacForTest(seed: ByteArray, keyBytes: ByteArray): ByteArray = TODO("30-06")
        @JvmStatic fun verifySeedHmac(seed: ByteArray, tag: ByteArray, keyBytes: ByteArray) { TODO("30-06") }
        @JvmStatic inline fun <T> wrapKeystoreException(block: () -> T): T = TODO("30-06")
    }
    ```
    Only add stubs that do NOT already exist. If `validateMnemonic` already exists at line ~818 (per RESEARCH.md Pitfall 7), do not shadow it; instead, the test references that existing method — update the signature note in the plan summary. Add acceptance criterion: `grep -q "fun validateMnemonic" android/app/src/main/java/io/raventag/app/wallet/WalletManager.kt`.

    The net effect: the test compiles, fails RED on TODO/assertion, and plans 30-02/30-03/30-04/30-06 turn tests green incrementally.
  </done>
</task>

</tasks>

<threat_model>
## Trust Boundaries

No runtime trust boundaries in this plan — Wave 0 writes tests, not production code.

## STRIDE Threat Register

| Threat ID | Category | Component | Disposition | Mitigation Plan |
|-----------|----------|-----------|-------------|-----------------|
| T-30-W0-01 | Tampering | Test file forged pass | mitigate | Every test starts RED; Wave 1-3 commits must produce GREEN transitions tracked by the per-task verify map in 30-VALIDATION.md. |
| T-30-W0-02 | Information Disclosure | Hard-coded BIP39 phrase in test | accept | The BIP39 test phrase `abandon…about` is a publicly-known zero-value test vector; no secret exposure. |

ASVS V14 Configuration applies: tests MUST NOT contain real credentials or mnemonic phrases belonging to the user.
</threat_model>

<verification>
After both tasks complete:
- `cd android && ./gradlew :app:compileConsumerDebugUnitTestKotlin` exits 0.
- `cd android && ./gradlew :app:testConsumerDebugUnitTest -i` runs all new tests and reports FAILURES for the pure-function ones (RED is correct).
- `! grep -rP '\u2014' android/app/src/test/java/io/raventag/app/wallet/cache android/app/src/test/java/io/raventag/app/wallet/subscription android/app/src/test/java/io/raventag/app/wallet/fee android/app/src/test/java/io/raventag/app/wallet/WalletManagerMnemonicTest.kt android/app/src/main/java/io/raventag/app/wallet/WalletExceptions.kt` returns no matches.
</verification>

<success_criteria>
- Six test files exist and compile.
- Each test method listed in 30-VALIDATION.md per-task map is present by name in the test sources.
- `WalletExceptions.kt` created in production sources with three exception declarations.
- `WalletManager.kt` has `TODO("30-06")` stubs for the five companion helpers referenced by the mnemonic tests.
- No em dashes anywhere in the new files.
- `./gradlew :app:compileConsumerDebugUnitTestKotlin` exits 0.
</success_criteria>

<output>
After completion, create `.planning/phases/30-wallet-reliability/30-01-SUMMARY.md` documenting:
- Files created / extended (with line counts).
- The four `TODO("30-06")` stubs added to `WalletManager.kt` — downstream plan 30-06 MUST replace these bodies.
- The `WalletExceptions.kt` scaffolding file — downstream plans 30-02 / 30-05 / 30-06 will import these types.
- Any `@Ignore`d tests plus the reason and the implementing plan ID.
- Confirmation that `./gradlew :app:compileConsumerDebugUnitTestKotlin` passes and `./gradlew :app:testConsumerDebugUnitTest` fails RED in the expected tests.
</output>
