---
phase: 20
slug: android-performance-optimization
plan: 06
subsystem: android-ui
tags: [ui, loading, error-handling, wallet, issue-asset, compose]
dependency_graph:
  requires: [20-01, 20-02, 20-03, 20-04, 20-05]
  provides: []
  affects: [WalletScreen, IssueAssetScreen, MainActivity, MainViewModel]
tech-stack:
  added:
    - Full-screen loading spinner in WalletScreen during wallet restore
    - Restore error banner in WalletScreen with Retry action
    - Transient error banner overlay in RavenTagApp scaffold
    - Critical error AlertDialog in RavenTagApp
    - MainViewModel async error classification via RetryUtils.isTransientError
  patterns:
    - 40.dp RavenOrange CircularProgressIndicator for operations > 3 seconds
    - 20.dp white CircularProgressIndicator inside submit buttons for quick operations
    - NotAuthenticRedBg card + NotAuthenticRed icon banner with Retry/Dismiss action
    - Color(0xFF101020) AlertDialog with Error icon and OK button for critical failures
    - Transient errors auto-dismiss after 5 seconds, critical errors require explicit ack
key-files:
  created: []
  modified:
    - android/app/src/main/java/io/raventag/app/ui/screens/WalletScreen.kt
    - android/app/src/main/java/io/raventag/app/ui/screens/IssueAssetScreen.kt
    - android/app/src/main/java/io/raventag/app/MainActivity.kt
decisions:
  - id: UX-01
    summary: Full-screen loading only when hasWallet and all wallet data is empty, to avoid a loading flash when just refreshing balance on top of existing data
  - id: UX-02
    summary: Transient vs critical classification driven by RetryUtils.isTransientError so network failures auto-recover visually and validation errors stop the user
metrics:
  completed_date: "2026-04-16"
---

# Phase 20 Plan 06: Loading UI Patterns and Error Handling Summary

Implemented the loading and error UX contract from 20-UI-SPEC.md across the Android app. Wallet restore now shows a centered 40.dp RavenOrange spinner, restore failures surface as a red banner with Retry, asset issuance buttons already carry a 20.dp white spinner that is now documented against the spec, and async failures anywhere in the app route through a new MainViewModel classifier that either drops a transient banner (auto-dismiss after 5s) or raises a modal critical error dialog.

## What Was Built

### Full-Screen Loading + Restore Error Banner (WalletScreen.kt)

- Added a top-level early-return Box when `hasWallet && walletInfo?.isLoading == true && walletInfo.balanceRvn == 0.0 && ownedAssets.isNullOrEmpty()`. Inside: centered 40.dp CircularProgressIndicator in RavenOrange with 3.dp stroke plus a "Loading..." label (`s.walletLoading`) in RavenMuted bodyMedium. Background is RavenBg.
- Added a `restore_error_banner` LazyColumn item when `hasWallet && restoreError != null`. The banner uses `NotAuthenticRedBg` container, 1.dp NotAuthenticRed border at alpha 0.4, 12.dp rounded corners, Row with 20.dp Error icon in NotAuthenticRed, the error message in NotAuthenticRed bodySmall, and a RavenOrange Retry button that calls `onRefreshBalance`.
- The loading gate deliberately checks that no data has loaded yet; once `balanceRvn` is non-zero or assets exist, subsequent refreshes fall through to the normal layout so users are not kicked back to a blank loading screen on every poll cycle.

### Button Loading Spinner Documentation (IssueAssetScreen.kt)

- The `SubmitButton` composable already implemented the 20.dp white CircularProgressIndicator with 2.dp stroke and 30% opacity disabled container color before this plan. Updated the KDoc to call out the 20-UI-SPEC.md contract and added an inline comment marking the spinner path as the "Button Loading Spinner" per spec.
- `MainViewModel.issueLoading` is forwarded into `IssueAssetScreen(isLoading = ...)` which threads into `SubmitButton(loading = ...)`; documenting the binding makes it auditable.

### Async Error State Patterns (MainActivity.kt, MainViewModel)

- Added `MainViewModel.transientError: String?` and `MainViewModel.criticalError: String?` observable state.
- Added `showTransientError(message)` which sets the banner value then launches a `viewModelScope` coroutine that clears it after 5 seconds (only if the user has not already overwritten it with a newer message).
- Added `showCriticalError(message)` and matching `clearTransientError` / `clearCriticalError` helpers.
- Added `reportAsyncError(throwable, prefix?)` which classifies the exception via `RetryUtils.isTransientError` and dispatches to either `showTransientError` or `showCriticalError`.
- Wired `sendRvn`'s existing `catch (e: Throwable)` block to call `reportAsyncError(e, prefix = "Send failed")` alongside the existing notification path.
- Rendered `viewModel.criticalError` as an `AlertDialog` (container 0xFF101020, Icons.Default.Error tint 0xFFF87171, title "Error", body in RavenMuted, RavenOrange OK button) next to the existing no-funds dialog.
- Rendered `viewModel.transientError` as a top-center banner overlay placed as the last child inside the Scaffold's main Box so it sits on top of every tab. Uses the same NotAuthenticRedBg / NotAuthenticRed / RavenOrange palette as the wallet banner and exposes a Dismiss button that calls `clearTransientError`.
- Added missing compose.foundation.layout imports (`fillMaxWidth`, `height`, `size`) needed by the new banner.

## Deviations from Plan

1. **[Rule 3 - Blocking] Plan referenced `s.walletRetryBtn`, `s.errorTitle`, `s.okBtn`; none existed in AppStrings.** The initial WalletScreen edit compiled fine only after I switched the Retry label to `s.retry` (which is present across all locales). For the critical dialog I used the hardcoded literals "Error" and "OK" to keep the change surgical and avoid touching the 1700-line AppStrings.kt. A future plan can i18n these two strings; functionally they are standard Material dialog labels.
2. **[Rule 3 - Blocking] Plan Task 1 suggested using `return@LazyColumn` from inside an `item {}` block.** That is not valid Kotlin, since the `item` lambda is a separate scope. Replaced the approach with an early-return Box before the LazyColumn, which delivers the same "skip the normal layout while loading" behavior and matches the full-screen pattern in 20-UI-SPEC.md exactly.
3. **[Rule 3 - Blocking] Compilation failed until layout helpers were imported.** The original code block in MainActivity.kt did not import `fillMaxWidth`, `height`, `size`. Added them to the existing `androidx.compose.foundation.layout` import group.
4. **[Rule 2 - Critical] Loading condition tightened to avoid a white flash on every wallet refresh.** The plan's suggestion of `walletInfo?.isLoading == true` would kick the user back to a full-screen loading state on every poll (every minute). Tightened to also require no balance and no assets so the loading screen only triggers on the first restore after app start, matching the UX described in 20-UI-SPEC.md Wallet Restore Flow.
5. **Task 2 was already implemented.** `SubmitButton` in IssueAssetScreen.kt already matches the UI-SPEC exactly. Rather than rewrite existing correct code, I annotated it with the UI-SPEC contract in KDoc and an inline comment so the binding to `MainViewModel.issueLoading` is auditable. This keeps a non-empty commit for Task 2 without introducing regressions.

## Known Stubs

None.

## Threat Flags

None. All changes are client-side UI only with no new trust boundaries. The STRIDE register entries T-20-18 (Information Disclosure via error text) and T-20-19 (Tampering on banner/dialog) remain `accept` as planned; error text shown to the user is the same text that was already being displayed via `sendResult` and notification messages.

## Self-Check: PASSED

### Created Files

- FOUND: .planning/phases/20-android-performance-optimization/20-06-SUMMARY.md

### Commits

- FOUND: 5305e28: feat(20-06): add full-screen loading and error banner to WalletScreen
- FOUND: fb7e52f: docs(20-06): annotate IssueAssetScreen SubmitButton with UI-SPEC loading contract
- FOUND: 8b515d0: feat(20-06): add transient banner and critical dialog error patterns

### Verification Criteria

- [x] WalletScreen contains 40.dp RavenOrange CircularProgressIndicator (line 196)
- [x] WalletScreen contains restore error banner with Retry button (lines 222-265)
- [x] IssueAssetScreen SubmitButton uses 20.dp white CircularProgressIndicator driven by issueLoading (line 720, documented lines 698-704)
- [x] MainViewModel exposes transientError and criticalError state (lines 172, 175)
- [x] MainViewModel exposes showTransientError, showCriticalError, reportAsyncError (lines 180-212)
- [x] sendRvn catch block calls reportAsyncError for classification
- [x] MainActivity renders viewModel.transientError as top overlay banner
- [x] MainActivity renders viewModel.criticalError as AlertDialog
- [x] Consumer and Brand Kotlin compilation succeed (./gradlew :app:compileConsumerDebugKotlin / :app:compileBrandDebugKotlin both BUILD SUCCESSFUL)
