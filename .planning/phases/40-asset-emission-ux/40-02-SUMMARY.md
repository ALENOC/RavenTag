---
phase: 40-asset-emission-ux
plan: "02"
status: complete
tasks: 2/2
started: "2026-04-25T20:30:00Z"
completed: "2026-04-25T21:00:00Z"
---

## What was built

ViewModel error handling core for Phase 40 Asset Emission UX.

**IssueStep sealed class** — Multi-step state machine with Idle/InProgress/Success/Failed states and StepName enum (IPFS_UPLOAD, BALANCE_CHECK, NAME_CHECK, ISSUING, CONFIRMING, NFC_PROGRAMMING).

**WarningType enum** — INSUFFICIENT_BALANCE, DUPLICATE_NAME for pre-flight validation warnings.

**classifyIssuanceError** — Private method mapping 9 error categories (insufficient funds, duplicate name, node unreachable, timeout, fee estimation, IPFS auth, IPFS failed, invalid address, no wallet) to localized AppStrings keys, with raw message fallback.

**Enhanced issuance callbacks** — All three callbacks (issueRootAsset, issueSubAsset, issueUniqueToken) now have:
- D-04 pre-flight balance check (walletInfo.balanceRvn vs burn fee per asset type)
- D-04 pre-flight name uniqueness check (ownedAssets scan)
- Connection-level retry via RetryUtils.retryWithBackoff(5), with SocketTimeoutException excluded (D-08: wrapped as RuntimeException before retry lambda, never retried)
- Classified error messages via classifyIssuanceError
- issuedTxid set on success for explorer link

**revokeAsset bug fixed** — Now captures `val result = withContext(Dispatchers.IO) { am.revokeAsset(...) }` and checks `result.success` and `result.error` instead of hardcoded `true`.

**IssueAssetScreen call site** — Wired `currentStep`, `issuedTxid`, `warningType` (parameters added in Plan 40-03).

**State variables** — `issueStep`, `issuedTxid`, `warningType` added to ViewModel; `clearIssueResult()` extended to reset them.

## Task summary

| # | Task | Result |
|---|------|--------|
| 1 | IssueStep, WarningType, classifyIssuanceError, state vars, clearIssueResult | All present |
| 2 | Enhanced callbacks with pre-flight, classification, retry, revoke fix, call site | All three callbacks enhanced |

## Key files

| File | Status | Purpose |
|------|--------|---------|
| android/app/src/main/java/io/raventag/app/MainActivity.kt | MODIFIED | IssueStep, WarningType, classifyIssuanceError, enhanced callbacks, revoke fix |

## Deviations

- SocketTimeoutException wrapping: The plan's getrawtransaction-on-timeout pattern has a circular txid reference (txid unavailable when SocketTimeoutException thrown). Implementation wraps SocketTimeoutException as RuntimeException inside the retry lambda so isTransientError returns false and retryWithBackoff skips it. D-08 honored: SocketTimeoutException never retried.
- Compilation note: currentStep/issuedTxid/warningType parameters added to IssueAssetScreen call site will compile after Plan 40-03 adds corresponding composable parameters.

## Self-Check: PASSED

- [x] classifyIssuanceError maps 8 known error categories
- [x] Pre-flight balance check compares walletInfo.balanceRvn to burn fee constant
- [x] Pre-flight name check scans ownedAssets for duplicates
- [x] All three issuance callbacks use classified error messages
- [x] revokeAsset captures AssetOperationResult (bug fixed)
- [x] Connection-level transient errors auto-retry via retryWithBackoff(5)
- [x] SocketTimeoutException excluded from auto-retry (D-08)
- [x] IssueStep sealed class, WarningType enum, and state variables present
- [x] IssueAssetScreen receives currentStep, issuedTxid, warningType parameters
