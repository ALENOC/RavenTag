---
phase: 30
plan: 04
subsystem: wallet-fee-estimation
tags: [fee, estimation, electrumx, fallback, ui, send, transfer]
dependency_graph:
  requires: [30-01, 30-03]
  provides: [FeeEstimator, fee-ui-section]
  affects: [SendRvnScreen, TransferScreen, FeeEstimator, AppStrings]
tech_stack:
  added: [kotlinx-coroutines, RetryUtils.retryWithBackoff]
  patterns: [suspend-function-with-fallback, LaunchedEffect-fee-fetch, composable-fee-section]
key_files:
  created:
    - android/app/src/main/java/io/raventag/app/wallet/fee/FeeEstimator.kt
  modified:
    - android/app/src/main/java/io/raventag/app/ui/theme/AppStrings.kt
    - android/app/src/main/java/io/raventag/app/ui/screens/SendRvnScreen.kt
    - android/app/src/main/java/io/raventag/app/ui/screens/TransferScreen.kt
    - android/app/src/main/java/io/raventag/app/MainActivity.kt
    - android/app/src/main/java/io/raventag/app/wallet/subscription/SubscriptionManager.kt
decisions:
  - Two-optional-param constructor (node + lambda) kept for Wave 0 test compatibility
  - FeeSection composable duplicated per screen (not shared file) to keep screens self-contained
  - TransferScreen gained a new confirmation dialog (previously submitted directly)
  - Fee override computed but not yet wired to send-builder (onSend callback unchanged)
metrics:
  duration: 21m
  completed: 2026-04-21
  tasks: 2
  files: 6
---

# Phase 30 Plan 04: Fee Estimation Summary

FeeEstimator with retry + fallback (0.01 RVN/kB), EN/IT strings, and confirm-dialog fee sections for both SendRvnScreen and TransferScreen.

## Constructor Signature

```kotlin
class FeeEstimator(
    private val node: RavencoinPublicNode? = null,
    private val estimateFeeProvider: (suspend (Int) -> Double)? = null
)
```

Primary constructor takes two optional parameters. Production code passes `node`; tests pass the lambda. The `estimateSatPerKb(targetBlocks)` method wraps the call in `RetryUtils.retryWithBackoff(3, 500ms, 2x)` and falls back to `FALLBACK_SAT_PER_KB = 1_000_000L` on any failure or non-positive result. The `estimateSatPerKbWithSource(targetBlocks)` variant returns a `Result(satPerKb, usedFallback)` data class so the UI can display the amber warning.

Sanity cap: fees exceeding 1.0 RVN/kB (100_000_000 sat/kB) from a malicious node are rejected and replaced with the fallback.

## Fee Unit Note for Plan 30-05

The existing `sendRvnLocal` in `WalletManager.kt` uses **sat/byte** from `getMinRelayFeeRateSatPerByte()`. FeeEstimator returns **sat/kB**. Conversion at the call site: `satPerKb / 1000 = satPerByte`. Plan 30-05 (consolidation reliability) should wire `FeeEstimator.estimateSatPerKb(6)` into the send-builder path and apply this division. The current `onSend(toAddress, amount)` callback does not accept a fee parameter; a future plan should extend the callback or pass the fee via the ViewModel.

## UI Description (for manual-verify in plan 30-10)

**SendRvnScreen confirm dialog**: after tapping "Send", a dark AlertDialog shows amount, recipient, and a new fee row. The fee row reads "Fee: 0.01000000 RVN . ~6 blocks" with an orange Edit icon. If the estimate failed, an amber/orange warning line above reads "Fee estimate unavailable. Using 0.01 RVN/kB fallback." Tapping the Edit icon reveals an `OutlinedTextField` accepting a custom RVN/kB value.

**TransferScreen confirm dialog**: new behavior (previously no confirmation step). After tapping the transfer button, a similar dark AlertDialog appears with asset name, recipient, fee section (same layout), and an ownership warning for root/sub transfers.

## Tasks Completed

| Task | Name | Commit | Files |
|------|------|--------|-------|
| 1 | FeeEstimator class + EN/IT strings | 394e320 | FeeEstimator.kt, AppStrings.kt |
| 2 | Wire into SendRvnScreen + TransferScreen | 454f177 | SendRvnScreen.kt, TransferScreen.kt, MainActivity.kt |

Additional blocking fix committed separately: 0ad9de9 (SubscriptionManager coroutineContext import fix from plan 30-03).

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 3 - Blocking] SubscriptionManager.kt compilation error**
- **Found during:** Task 1 setup (FeeEstimator compilation prerequisite)
- **Issue:** `kotlin.coroutines.coroutineContext.isActive` was an unresolved reference in SubscriptionManager.kt (from plan 30-03)
- **Fix:** Added `import kotlin.coroutines.coroutineContext` and `import kotlinx.coroutines.isActive`, replaced fully-qualified reference with short form
- **Files modified:** SubscriptionManager.kt
- **Commit:** 0ad9de9

**2. [Rule 3 - Blocking] Composable calls inside remember lambda**
- **Found during:** Task 2 (MainActivity.kt call sites)
- **Issue:** `LocalContext.current` cannot be used inside `remember {}` (not a composable context)
- **Fix:** Captured `LocalContext.current` into a val before the `remember` block
- **Files modified:** MainActivity.kt
- **Commit:** 454f177 (included in Task 2 commit)

### Design Adjustments

- The plan suggested dropping the dual-constructor in favor of primary(lambda) + secondary(node). However, the Wave 0 test already uses `FeeEstimator(null, estimateFn)` with two optional params. Kept the existing two-param constructor to avoid modifying the Wave 0 test file, which was already committed and passing in RED state.

- The plan's `effectiveFeeSatPerKb` variable in SendRvnScreen is computed but not yet wired to the `onSend` callback because that callback signature is `(String, Double)` and does not accept a fee parameter. This is intentional per plan guidance: "Do NOT touch the send-builder logic itself in this plan."

## TDD Gate Compliance

- RED: 5 Wave 0 tests confirmed failing with `NotImplementedError` from TODO stub (verified before implementation)
- GREEN: All 5 tests pass after FeeEstimator implementation (verified)
- REFACTOR: No separate refactor commit needed; implementation was clean on first pass

## Threat Flags

No new security surface beyond what the threat model covers. The fee override input uses `KeyboardType.Decimal` and `toDoubleOrNull()` parsing, which safely handles non-numeric input by returning null (keeping the estimated fee). The sanity cap at 100_000_000 sat/kB (1.0 RVN/kB) mitigates T-30-NET-04 from the plan's threat model.

## Self-Check: PASSED

All 5 key files exist on disk. All 3 commit hashes found in git log. FeeEstimator unit tests GREEN. `assembleConsumerDebug` succeeds.
