---
id: 30-04-fee-estimation
phase: 30
plan: 04
type: execute
wave: 1
depends_on:
  - 30-01-wave0-test-scaffolding
  - 30-03-scripthash-subscription
files_modified:
  - android/app/src/main/java/io/raventag/app/wallet/fee/FeeEstimator.kt
  - android/app/src/main/java/io/raventag/app/ui/theme/AppStrings.kt
  - android/app/src/main/java/io/raventag/app/ui/screens/SendRvnScreen.kt
  - android/app/src/main/java/io/raventag/app/ui/screens/TransferScreen.kt
autonomous: true
requirements:
  - WALLET-SEND
threat_refs:
  - T-30-NET
ui_spec_refs:
  - "UI-SPEC §Copywriting Contract, Error states, row: Fee estimate unavailable (EN + IT)"
  - "UI-SPEC §Interaction Contracts, Send flow (step 2-3: Fee row with edit icon + fallback warning)"
  - "UI-SPEC §Color: RavenOrange for fee override focus; RavenOrange bodySmall for fallback warning"

must_haves:
  truths:
    - "Send confirmation dialog shows a dynamic fee from blockchain.estimatefee(6) (D-22)"
    - "User can override the fee inline via an Edit icon that opens an OutlinedTextField"
    - "When estimatefee returns -1 or throws, the fallback 0.01 RVN/kB is used AND the user sees the amber/orange 'Fee estimate unavailable. Using 0.01 RVN/kB fallback.' warning line"
    - "Same behavior applies to TransferScreen (asset transfers) for consistency"
  artifacts:
    - path: "android/app/src/main/java/io/raventag/app/wallet/fee/FeeEstimator.kt"
      provides: "FeeEstimator class with estimateSatPerKb + FALLBACK_SAT_PER_KB constant"
      exports: ["FeeEstimator"]
      contains: "FALLBACK_SAT_PER_KB"
    - path: "android/app/src/main/java/io/raventag/app/ui/theme/AppStrings.kt"
      provides: "new EN + IT keys for fee warning, fee override label, fee-unavailable banner"
      contains: "sendFeeEstimateUnavailable"
  key_links:
    - from: "SendRvnScreen confirm dialog"
      to: "FeeEstimator.estimateSatPerKb(6)"
      via: "ViewModel call before dialog render"
      pattern: "FeeEstimator"
    - from: "TransferScreen confirm dialog"
      to: "FeeEstimator.estimateSatPerKb(6)"
      via: "ViewModel call"
      pattern: "FeeEstimator"
---

<objective>
Implement D-22 dynamic fee estimation end-to-end. Backend layer: a `FeeEstimator` that calls `RavencoinPublicNode.estimateFeeRvnPerKb(6)` (added in plan 30-03) and falls back to 0.01 RVN/kB (= 1_000_000 sat/kB) when the node returns -1 or throws. UI layer: extend the existing Send / Transfer confirm dialogs with a fee row, an edit-icon-triggered override input, and a fallback warning line.

Purpose: eliminate the current hard-coded or relay-floor fee logic and give the user an accurate, editable, visibly-explained fee.

Output: one new class file, one strings update (EN + IT), two Compose screen edits.
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
@android/app/src/main/java/io/raventag/app/wallet/RavencoinPublicNode.kt
@android/app/src/main/java/io/raventag/app/utils/RetryUtils.kt
@android/app/src/main/java/io/raventag/app/ui/theme/AppStrings.kt
@android/app/src/main/java/io/raventag/app/ui/screens/SendRvnScreen.kt
@android/app/src/main/java/io/raventag/app/ui/screens/TransferScreen.kt
@android/app/src/test/java/io/raventag/app/wallet/fee/FeeEstimatorTest.kt

<interfaces>
From plan 30-03 (RavencoinPublicNode.kt):
```kotlin
fun estimateFeeRvnPerKb(targetBlocks: Int): Double
// returns the raw RVN/kB number; -1.0 when server returns null; throws on RPC error
```

Wave 0 expected signature for FeeEstimator (honor exactly):
```kotlin
class FeeEstimator(
    // Primary constructor used in production.
    private val node: io.raventag.app.wallet.RavencoinPublicNode
) {
    // Secondary constructor for unit tests: lambda-injectable estimator.
    internal constructor(estimateProvider: suspend (Int) -> Double)

    suspend fun estimateSatPerKb(targetBlocks: Int = 6): Long

    companion object {
        /** D-22 fallback: 0.01 RVN/kB = 1_000_000 sat/kB. */
        const val FALLBACK_SAT_PER_KB: Long = 1_000_000L
    }
}
```
</interfaces>
</context>

<tasks>

<task type="auto" tdd="true">
  <name>Task 1: Implement FeeEstimator class (test-first) + add EN/IT strings for fee copy</name>
  <files>
    android/app/src/main/java/io/raventag/app/wallet/fee/FeeEstimator.kt,
    android/app/src/main/java/io/raventag/app/ui/theme/AppStrings.kt
  </files>
  <read_first>
    @.planning/phases/30-wallet-reliability/30-RESEARCH.md#L725-L733,
    @.planning/phases/30-wallet-reliability/30-PATTERNS.md#L167-L186,
    @.planning/phases/30-wallet-reliability/30-UI-SPEC.md#L181-L187,
    @android/app/src/test/java/io/raventag/app/wallet/fee/FeeEstimatorTest.kt,
    @android/app/src/main/java/io/raventag/app/utils/RetryUtils.kt,
    @android/app/src/main/java/io/raventag/app/ui/theme/AppStrings.kt
  </read_first>
  <behavior>
    - `estimateSatPerKb(targetBlocks)` returns `Long` in sat/kB.
    - Source-of-truth unit conversion: `rvnPerKb * 1e8 = satPerKb`; `0.01 RVN/kB = 1_000_000 sat/kB`.
    - If node returns `< 0` OR `== 0.0` OR throws any exception (inc. SocketTimeout, IOException, IllegalStateException, UnknownHostException): return `FALLBACK_SAT_PER_KB = 1_000_000`.
    - Pass through non-fallback values honestly: `0.002 RVN/kB → 200_000 sat/kB`.
    - Passes `targetBlocks` verbatim to the injected provider.
    - Wrap the node call in `retryWithBackoff(maxAttempts = 3, initialDelayMs = 500L, backoffMultiplier = 2.0)` so a single transient failure does not collapse to fallback. If the retry eventually exhausts, CATCH the exception and return fallback.
  </behavior>
  <action>
    **FeeEstimator.kt** (new file):
    ```kotlin
    package io.raventag.app.wallet.fee

    import io.raventag.app.utils.retryWithBackoff
    import io.raventag.app.wallet.RavencoinPublicNode
    import kotlinx.coroutines.Dispatchers
    import kotlinx.coroutines.withContext

    class FeeEstimator private constructor(
        private val estimateProvider: suspend (Int) -> Double
    ) {
        /** Production constructor: uses the live ElectrumX node. */
        constructor(node: RavencoinPublicNode) : this(estimateProvider = { target ->
            withContext(Dispatchers.IO) { node.estimateFeeRvnPerKb(target) }
        })

        /** Test-only constructor taking a lambda. */
        internal constructor(estimateProviderLambda: (suspend (Int) -> Double)) : this(estimateProviderLambda as suspend (Int) -> Double)

        /**
         * Returns a sat/kB fee rate for the requested block target.
         * Falls back to [FALLBACK_SAT_PER_KB] (0.01 RVN/kB) on any failure
         * or when the server indicates insufficient data (<= 0).
         */
        suspend fun estimateSatPerKb(targetBlocks: Int = 6): Long {
            val rvnPerKb: Double = try {
                retryWithBackoff(maxAttempts = 3, initialDelayMs = 500L, backoffMultiplier = 2.0) {
                    estimateProvider(targetBlocks)
                }
            } catch (_: Exception) { -1.0 }
            if (rvnPerKb <= 0.0) return FALLBACK_SAT_PER_KB
            val satPerKb = (rvnPerKb * 100_000_000.0).toLong()
            return if (satPerKb <= 0L) FALLBACK_SAT_PER_KB else satPerKb
        }

        /**
         * Same signature but surfaces WHETHER the fallback was used.
         * UI (SendRvnScreen / TransferScreen) uses this to decide whether
         * to show the amber "estimate unavailable" warning (UI-SPEC).
         */
        suspend fun estimateSatPerKbWithSource(targetBlocks: Int = 6): Result {
            val rvnPerKb: Double = try {
                retryWithBackoff(maxAttempts = 3, initialDelayMs = 500L, backoffMultiplier = 2.0) {
                    estimateProvider(targetBlocks)
                }
            } catch (_: Exception) { return Result(FALLBACK_SAT_PER_KB, usedFallback = true) }
            if (rvnPerKb <= 0.0) return Result(FALLBACK_SAT_PER_KB, usedFallback = true)
            val satPerKb = (rvnPerKb * 100_000_000.0).toLong()
            return if (satPerKb <= 0L) Result(FALLBACK_SAT_PER_KB, usedFallback = true)
                   else Result(satPerKb, usedFallback = false)
        }

        data class Result(val satPerKb: Long, val usedFallback: Boolean)

        companion object {
            const val FALLBACK_SAT_PER_KB: Long = 1_000_000L
        }
    }
    ```

    Reconcile the two constructors: Kotlin does not allow two constructors with the same erased signature. The cleanest design — drop the dual-constructor idea and instead use:
    ```kotlin
    class FeeEstimator(private val estimateProvider: suspend (Int) -> Double) {
        constructor(node: RavencoinPublicNode) : this(estimateProvider = { t ->
            withContext(Dispatchers.IO) { node.estimateFeeRvnPerKb(t) }
        })
        // ... rest as above ...
    }
    ```
    That's one primary constructor (the lambda) + one secondary (taking the node). Update the Wave 0 test file IF needed so it instantiates via the lambda constructor; per 30-01 it already does. Verify by reading `FeeEstimatorTest.kt` and adjust the constructor mode to match.

    **AppStrings.kt** — append new keys to both `stringsEn` and `stringsIt`. Use Grep to locate the existing map declarations and add keys in alphabetical / logical order:
    Keys to add (EN):
    - `sendFeeLabel = "Fee"` (used in: `Fee: %1$s RVN · ~6 blocks`)
    - `sendFeeTarget = "~6 blocks"`
    - `sendFeeEditLabel = "Edit fee"`
    - `sendFeeOverrideHint = "Custom fee (RVN/kB)"`
    - `sendFeeEstimateUnavailable = "Fee estimate unavailable. Using 0.01 RVN/kB fallback."`

    Keys to add (IT):
    - `sendFeeLabel = "Commissione"`
    - `sendFeeTarget = "~6 blocchi"`
    - `sendFeeEditLabel = "Modifica commissione"`
    - `sendFeeOverrideHint = "Commissione custom (RVN/kB)"`
    - `sendFeeEstimateUnavailable = "Stima commissione non disponibile. Uso il valore minimo 0.01 RVN/kB."`

    **No em dashes** (MEMORY.md rule): verify every new string. Use middle dot `·` where the UI-SPEC calls for it (`Fee: X RVN · ~6 blocks`).

    Em-dash audit: `! grep -P '\u2014' android/app/src/main/java/io/raventag/app/wallet/fee/FeeEstimator.kt android/app/src/main/java/io/raventag/app/ui/theme/AppStrings.kt`
  </action>
  <verify>
    <automated>cd android && ./gradlew :app:testConsumerDebugUnitTest --tests "io.raventag.app.wallet.fee.FeeEstimatorTest" -i 2>&1 | tail -30</automated>
  </verify>
  <acceptance_criteria>
    - `test -f android/app/src/main/java/io/raventag/app/wallet/fee/FeeEstimator.kt`
    - `grep -q "class FeeEstimator" android/app/src/main/java/io/raventag/app/wallet/fee/FeeEstimator.kt`
    - `grep -q "FALLBACK_SAT_PER_KB.*1_000_000L" android/app/src/main/java/io/raventag/app/wallet/fee/FeeEstimator.kt`
    - `grep -q "suspend fun estimateSatPerKb" android/app/src/main/java/io/raventag/app/wallet/fee/FeeEstimator.kt`
    - `grep -q "retryWithBackoff" android/app/src/main/java/io/raventag/app/wallet/fee/FeeEstimator.kt`
    - `grep -q "data class Result" android/app/src/main/java/io/raventag/app/wallet/fee/FeeEstimator.kt`
    - `grep -q "sendFeeEstimateUnavailable" android/app/src/main/java/io/raventag/app/ui/theme/AppStrings.kt`
    - `grep -q "Fee estimate unavailable. Using 0.01 RVN/kB fallback." android/app/src/main/java/io/raventag/app/ui/theme/AppStrings.kt`
    - `grep -q "Stima commissione non disponibile. Uso il valore minimo 0.01 RVN/kB." android/app/src/main/java/io/raventag/app/ui/theme/AppStrings.kt`
    - `grep -q "sendFeeLabel" android/app/src/main/java/io/raventag/app/ui/theme/AppStrings.kt`
    - `grep -q "sendFeeEditLabel" android/app/src/main/java/io/raventag/app/ui/theme/AppStrings.kt`
    - `! grep -P '\u2014' android/app/src/main/java/io/raventag/app/wallet/fee/FeeEstimator.kt android/app/src/main/java/io/raventag/app/ui/theme/AppStrings.kt`
    - `cd android && ./gradlew :app:testConsumerDebugUnitTest --tests "*FeeEstimatorTest*"` exits 0 (all five Wave 0 tests GREEN).
  </acceptance_criteria>
  <done>FeeEstimator class passes all Wave 0 tests GREEN. EN + IT strings in place. No em dashes.</done>
</task>

<task type="auto" tdd="true">
  <name>Task 2: Wire FeeEstimator into SendRvnScreen + TransferScreen confirm dialogs with fee row + editable override + fallback warning</name>
  <files>
    android/app/src/main/java/io/raventag/app/ui/screens/SendRvnScreen.kt,
    android/app/src/main/java/io/raventag/app/ui/screens/TransferScreen.kt
  </files>
  <read_first>
    @.planning/phases/30-wallet-reliability/30-UI-SPEC.md#L103-L112,
    @.planning/phases/30-wallet-reliability/30-UI-SPEC.md#L359-L365,
    @.planning/phases/30-wallet-reliability/30-UI-SPEC.md#L152-L168,
    @android/app/src/main/java/io/raventag/app/ui/screens/SendRvnScreen.kt,
    @android/app/src/main/java/io/raventag/app/ui/screens/TransferScreen.kt,
    @android/app/src/main/java/io/raventag/app/ui/theme/AppStrings.kt,
    @android/app/src/main/java/io/raventag/app/wallet/fee/FeeEstimator.kt
  </read_first>
  <behavior>
    Confirm dialog before a send (for both screens):
    - A fee row is added under the amount/address summary in the dialog body.
    - Layout: `Row { Text("Fee: %s RVN · ~6 blocks"); IconButton(Icons.Default.Edit, tint=RavenOrange) }`.
    - Tapping the edit icon reveals an inline `OutlinedTextField` accepting numeric RVN/kB (keyboardType Decimal). Typing updates the held `satPerKbOverride` state.
    - Above the fee row: if `usedFallback == true`, show a text `"Fee estimate unavailable. Using 0.01 RVN/kB fallback."` (EN) / IT equivalent in `RavenOrange bodySmall`.
    - On Send button press, the ViewModel uses the override value if any, else the estimator result, to compute the fee, pass to `sendRvnLocal` (or asset equivalent).
    - When the user cancels the dialog, any override is discarded.
    - The estimator call is made LAZILY on dialog open (not on screen open), so it reflects fresh network state.
  </behavior>
  <action>
    Strategy: these screens already exist and already show a send confirm flow (Phase 20 D-07). The change is additive. For both files:

    1. Read the existing file top-to-bottom. Locate the confirmation `AlertDialog` composable (Phase 20 pattern).
    2. Add a `fun buildFeeSection(...)` private composable in the same file that renders:
       - conditional warning line (bodySmall, RavenOrange) when `usedFallback == true`
       - a `Row` with the fee label (bodySmall) and an `IconButton` (Edit icon, RavenOrange)
       - when expanded: an `OutlinedTextField` with singleLine=true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal)
    3. In the dialog body, insert this section above the existing confirm/cancel buttons.
    4. Hoist the fee state into a `rememberSaveable` at the screen top: `var feeSatPerKb by remember { mutableStateOf<Long?>(null) }`; `var usedFallback by remember { mutableStateOf(false) }`; `var feeOverride by remember { mutableStateOf<String?>(null) }`.
    5. On confirm-dialog opening (e.g., `LaunchedEffect(showConfirmDialog) { if (showConfirmDialog) { ... }}`): call `FeeEstimator(node).estimateSatPerKbWithSource(6)` from a CoroutineScope tied to the dialog, update `feeSatPerKb` and `usedFallback`.
    6. Pass the effective fee (override if set, else estimate, else FALLBACK) to the existing send handler.

    Do NOT touch the send-builder logic itself in this plan — pass the new fee rate into the existing transaction-building call (matching whatever argument `sendRvnLocal` / asset-transfer function accepts). If that function currently expects `sat/byte` and we have `sat/kB`: divide by 1000 at the call site. Document which unit the existing send code uses in the plan summary so plan 30-05 can align.

    Concrete snippet to insert (shape, adapt imports):
    ```kotlin
    @Composable
    private fun FeeSection(
        feeSatPerKb: Long?,
        usedFallback: Boolean,
        overrideText: String,
        onOverrideChange: (String) -> Unit,
        onEditToggle: () -> Unit,
        editOpen: Boolean
    ) {
        val strings = LocalStrings.current
        Column {
            if (usedFallback) {
                Text(
                    text = strings.sendFeeEstimateUnavailable,
                    style = MaterialTheme.typography.bodySmall,
                    color = RavenOrange,
                    modifier = Modifier.padding(bottom = 4.dp)
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                val feeRvn = (feeSatPerKb ?: FeeEstimator.FALLBACK_SAT_PER_KB) / 1e8
                Text(
                    text = "${strings.sendFeeLabel}: %.8f RVN · ${strings.sendFeeTarget}".format(feeRvn),
                    style = MaterialTheme.typography.bodySmall,
                    color = RavenMuted,
                    modifier = Modifier.weight(1f)
                )
                IconButton(onClick = onEditToggle, modifier = Modifier.size(36.dp)) {
                    Icon(Icons.Default.Edit, contentDescription = strings.sendFeeEditLabel, tint = RavenOrange)
                }
            }
            if (editOpen) {
                OutlinedTextField(
                    value = overrideText,
                    onValueChange = onOverrideChange,
                    label = { Text(strings.sendFeeOverrideHint) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }
    ```

    Transfer screen: same snippet pattern, same strings, same FeeEstimator call. The asset transfer builder path already computes fees internally — for v1, still surface the estimate to the user and pass the override back down to the builder call.

    Em-dash audit: `! grep -P '\u2014' android/app/src/main/java/io/raventag/app/ui/screens/SendRvnScreen.kt android/app/src/main/java/io/raventag/app/ui/screens/TransferScreen.kt`.
  </action>
  <verify>
    <automated>cd android && ./gradlew :app:assembleConsumerDebug -q 2>&1 | tail -20</automated>
  </verify>
  <acceptance_criteria>
    - `grep -q "FeeEstimator" android/app/src/main/java/io/raventag/app/ui/screens/SendRvnScreen.kt`
    - `grep -q "FeeEstimator" android/app/src/main/java/io/raventag/app/ui/screens/TransferScreen.kt`
    - `grep -q "sendFeeEstimateUnavailable" android/app/src/main/java/io/raventag/app/ui/screens/SendRvnScreen.kt`
    - `grep -q "Icons.Default.Edit" android/app/src/main/java/io/raventag/app/ui/screens/SendRvnScreen.kt`
    - `grep -q "sendFeeEditLabel\|sendFeeOverrideHint" android/app/src/main/java/io/raventag/app/ui/screens/SendRvnScreen.kt`
    - `grep -q "FeeEstimator" android/app/src/main/java/io/raventag/app/ui/screens/TransferScreen.kt`
    - `! grep -P '\u2014' android/app/src/main/java/io/raventag/app/ui/screens/SendRvnScreen.kt android/app/src/main/java/io/raventag/app/ui/screens/TransferScreen.kt`
    - `cd android && ./gradlew :app:assembleConsumerDebug` exits 0.
  </acceptance_criteria>
  <done>Both send flows call FeeEstimator, display the fee and fallback warning using UI-SPEC copy, and accept override input. No em dashes. App compiles.</done>
</task>

</tasks>

<threat_model>
## Trust Boundaries

| Boundary | Description |
|----------|-------------|
| user input → fee override | untrusted numeric input; must be parsed defensively and clamped to a sane range |

## STRIDE Threat Register

| Threat ID | Category | Component | Disposition | Mitigation Plan |
|-----------|----------|-----------|-------------|-----------------|
| T-30-NET-04 | Tampering | Malicious ElectrumX node returns absurdly high fee | mitigate | Warn the user via the visible fee line; the user may override. Additional clamp: reject fee rates > 1.0 RVN/kB (sanity cap) before use. Add clamp in `FeeEstimator.estimateSatPerKbWithSource`: `if (satPerKb > 100_000_000L) return Result(FALLBACK_SAT_PER_KB, usedFallback = true)`. |
| T-30-NET-05 | Tampering | Malicious node returns 0 fee → user sends tx that never confirms | mitigate | `rvnPerKb <= 0.0` → fallback. Users still see the fallback warning. |
| T-30-NET-06 | Input validation | User enters non-numeric override | mitigate | `keyboardType = KeyboardType.Decimal` + try/catch parse; reject bad input by keeping previous value. |

ASVS V5.1 input validation on override field; V9.2 TLS for RPC call (inherited from RavencoinPublicNode TLS/TOFU path).
</threat_model>

<verification>
- `cd android && ./gradlew :app:assembleConsumerDebug` exits 0.
- `cd android && ./gradlew :app:testConsumerDebugUnitTest --tests "*FeeEstimatorTest*"` all GREEN.
- `! grep -r '\u2014' android/app/src/main/java/io/raventag/app/ui/theme/AppStrings.kt android/app/src/main/java/io/raventag/app/wallet/fee/ android/app/src/main/java/io/raventag/app/ui/screens/SendRvnScreen.kt android/app/src/main/java/io/raventag/app/ui/screens/TransferScreen.kt`
</verification>

<success_criteria>
- FeeEstimator passes all Wave 0 unit tests.
- Both send screens show the fee with an Edit override and the fallback warning when applicable.
- EN + IT strings present; no em dashes anywhere.
</success_criteria>

<output>
Create `.planning/phases/30-wallet-reliability/30-04-SUMMARY.md`:
- Final constructor signature of FeeEstimator.
- Exact unit used by the existing send-builder path (sat/B or sat/kB), noting the conversion at the call site.
- Screenshot-ready description of the new fee section (for manual-verify in plan 30-10).
- Note for plan 30-05: consolidation txs built inside `sendRvnLocal` should consume `FeeEstimator.estimateSatPerKb(6)` on the same code path.
</output>
