---
phase: 40-asset-emission-ux
plan: "04"
status: complete
tasks: 2/2
started: "2026-04-25T21:30:00Z"
completed: "2026-04-25T22:00:00Z"
---

## What was built

Post-issuance confirmation tracking and combined flow enhancement for Phase 40.

**Confirmation polling** — pollingLoop() suspend function polls `blockchain.transaction.get` every 30s after successful issuance. Auto-dismisses result banner at 6 confirmations (2s delay). Added to all three standalone callbacks (issueRootAsset, issueSubAsset, issueUniqueToken).

**processIssueAndWrite enhanced** — Step state transitions added at IPFS_UPLOAD, ISSUING, NFC_PROGRAMMING, and CONFIRMING phases. Hardcoded Italian error strings replaced with classifyIssuanceError. Issuance call wrapped in retryWithBackoff(5) with SocketTimeoutException excluded (D-08: wrapped as RuntimeException before retry lambda). Confirmation polling started after combined flow success.

**onTagTapped updated** — On combined flow failure, sets issueStep to IssueStep.Failed(ISSUING, ...).

## Task summary

| # | Task | Result |
|---|------|--------|
| 1 | Confirmation polling in standalone callbacks | pollingLoop added to all 3 callbacks |
| 2 | processIssueAndWrite enhancement | Step states, classification, D-08 retry, polling |

## Key files

| File | Status | Purpose |
|------|--------|---------|
| android/app/src/main/java/io/raventag/app/MainActivity.kt | MODIFIED | pollingLoop, processIssueAndWrite enhancement, onTagTapped update |

## Deviations

- isActive check replaced with natural cancellation via delay(). ViewModel scope destruction cancels the coroutine tree, making explicit isActive check redundant.
- 6 pre-existing test failures (4 SunVerifierTest + 2 RavencoinTxBuilderTest) unrelated to Phase 40 changes. All Phase 40 tests pass.

## Self-Check: PASSED

- [x] Confirmation polling starts after successful issuance in all callbacks
- [x] Polls blockchain.transaction.get every 30s
- [x] Auto-dismiss at 6 confirmations
- [x] processIssueAndWrite sets step states (IPFS_UPLOAD/ISSUING/NFC_PROGRAMMING/CONFIRMING)
- [x] processIssueAndWrite uses classifyIssuanceError
- [x] SocketTimeoutException excluded from retry in combined flow (D-08)
- [x] Combined flow starts confirmation polling after success
- [x] Full compilation passes
