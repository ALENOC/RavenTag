---
phase: 20
slug: android-performance-optimization
plan: 05
subsystem: android-performance
tags: [notifications, retry, coroutines, wallet, send]
dependency_graph:
  requires: [20-01, 20-02, 20-03]
  provides: []
  affects: [MainActivity, SendRvnScreen, AppStrings]
tech-stack:
  added:
    - TransactionNotificationHelper integration (broadcasting, confirming, completed, failed)
    - RetryUtils.retryWithBackoff wrapper for send operations
    - estimatedFee parameter on SendRvnScreen confirmation dialog (D-07)
  patterns:
    - Background send with persistent notification updates via notification ID reuse
    - Retry with exponential backoff around network-bound send operations
    - Confirmation dialog exposes amount, recipient, fee before broadcast
key-files:
  created: []
  modified:
    - android/app/src/main/java/io/raventag/app/MainActivity.kt
    - android/app/src/main/java/io/raventag/app/ui/screens/SendRvnScreen.kt
    - android/app/src/main/java/io/raventag/app/ui/theme/AppStrings.kt
decisions:
  - id: D-03
    summary: Background execution with Android notification system for send operations
  - id: D-05
    summary: Multiple progress notifications during send lifecycle (broadcasting, confirming, completed, failed)
  - id: D-06
    summary: Auto-retry failed sends with exponential backoff before surfacing error
  - id: D-07
    summary: Always show confirmation dialog with amount, address, and network fee before send
metrics:
  completed_date: "2026-04-16"
---

# Phase 20 Plan 05: Notifications and Retry for Send Operations Summary

Integrated Android notification system and retry logic into RVN and asset send flows. Sends now broadcast in the background with persistent notifications, auto-retry transient failures, and always go through a confirmation dialog showing amount, recipient, and estimated network fee.

## What Was Built

### Confirmation Dialog with Fee (SendRvnScreen.kt) — D-07

- Added `estimatedFee: Double = 0.0` parameter to `SendRvnScreen` composable signature
- Confirmation dialog now renders three labeled rows: Amount, To, Network fee
- When `feeUnavailable` is true, fee row shows "Unavailable" in `RavenOrange`
- Irreversibility warning remains the last element, styled in red
- Recipient address truncates to 16 chars with ellipsis when longer

### sendRvn() Notifications and Retry (MainActivity.kt) — D-03, D-05, D-06

- `TransactionNotificationHelper.showBroadcasting(getApplication())` posted before broadcast
- Broadcast wrapped in `RetryUtils.retryWithBackoff { withContext(Dispatchers.IO) { wm.sendRvnLocal(...) } }`
- On success: `showConfirming(..., 1, 1)` → 2s delay → `showCompleted(..., txid)`
- On `FeeUnavailableException`: `showFailed(..., "Fee unavailable: ...")` and UI flag toggle
- On any other `Throwable`: `showFailed(..., "Send failed: ...")` and UI error state using new `walletSendError` string

### transferAssetConsumer() Notifications and Retry (MainActivity.kt) — D-03, D-05, D-06

- Same broadcasting → confirming → completed notification sequence as `sendRvn()`
- Transfer call wrapped in `RetryUtils.retryWithBackoff`
- Failure path posts `showFailed(..., "Transfer failed: ...")` and sets UI error via new `walletTransferError` string
- Reloads balance and owned assets on success

### Error Strings (AppStrings.kt)

- Added `walletSendError` and `walletTransferError` properties with `%1` placeholder for error message
- Translations added for en, it, fr, de, es, ja, ko, ru (and propagated via `cloneStrings` bases)

## Deviations from Plan

- Plan body showed calls written with `applicationContext` (a property only available on the `ComponentActivity` side). Inside `MainViewModel : AndroidViewModel`, this had to be `getApplication()` to compile. All four helper calls in `sendRvn` and all four in `transferAssetConsumer` use `getApplication()` in the final code.
- No other deviations. Task 1, 2, 3 executed as specified.

## Known Stubs

None.

## Threat Flags

Mitigations cover the STRIDE register from the plan:

| Threat ID | Mitigation |
|-----------|------------|
| T-20-14 | Existing `WalletManager.sendRvnLocal()` validation unchanged; no new trust boundary introduced |
| T-20-15 | Accepted: confirmation dialog is client-side UX only |
| T-20-16 | `RetryUtils.retryWithBackoff` caps retries at 5 with exponential backoff, bounding total wait |
| T-20-17 | Notification text shows only truncated txid (20 chars) and error messages, no keys or seed material |

## Self-Check: PASSED

### Created Files

None (modifications only).

### Commits

- FOUND: 1bea5ae — feat(20-05): add estimatedFee parameter to SendRvnScreen confirmation dialog (D-07)
- FOUND: 25810c3 — feat(20-05): integrate notifications and retry for RVN and asset send operations (D-03, D-05, D-06)
- FOUND: 0dbe9cd — fix(20-05): use getApplication() in AndroidViewModel and add send error strings

### Verification Criteria

- [x] sendRvn() calls TransactionNotificationHelper.showBroadcasting() before send
- [x] sendRvn() calls TransactionNotificationHelper.showConfirming() after broadcast
- [x] sendRvn() calls TransactionNotificationHelper.showCompleted() on success with txid
- [x] sendRvn() calls TransactionNotificationHelper.showFailed() on error with message
- [x] sendRvn() wraps sendRvnLocal() in RetryUtils.retryWithBackoff()
- [x] transferAssetConsumer() uses the same notification pattern
- [x] transferAssetConsumer() wraps transferAssetLocal() in RetryUtils.retryWithBackoff()
- [x] SendRvnScreen confirmation dialog shows Amount, To, Network fee (D-07)
- [x] walletSendError / walletTransferError strings populated across all locales
