---
phase: 30-wallet-reliability
plan: 05
subsystem: wallet
tags: [workmanager, utxo-reservation, rebroadcast, pending-consolidation, sqlite]

# Dependency graph
requires:
  - phase: 30-02-wallet-cache-db-daos
    provides: ReservedUtxoDao, PendingConsolidationDao, WalletReliabilityDb
  - phase: 30-03-scripthash-subscription
    provides: callElectrumRawOrNull on RavencoinPublicNode
  - phase: 30-04-fee-estimation
    provides: FeeEstimator (used in send paths but not modified here)
provides:
  - UTXO reservation after broadcast in sendRvnLocal and transferAssetLocal
  - Pending consolidation tracking on broadcast failure
  - RebroadcastWorker with 30/60/120/240/480 min exponential ladder, 5-attempt cap
  - reconcileReservations helper for refresh-based cleanup
  - Startup prune of stale reservations older than 48h
affects: [30-08-walletscreen-refresh-and-receive-ux, 30-09-tx-history-3value]

# Tech tracking
tech-stack:
  added: []
  patterns: [post-broadcast-reservation, workmanager-onetime-chained-rebroadcast, 48h-stale-prune]

key-files:
  created:
    - android/app/src/main/java/io/raventag/app/worker/RebroadcastWorker.kt
  modified:
    - android/app/src/main/java/io/raventag/app/wallet/WalletManager.kt
    - android/app/src/main/java/io/raventag/app/MainActivity.kt

key-decisions:
  - "issueAssetLocal and consolidateAllFundsToFreshAddress do NOT get reservation wiring (issueAsset emits to self-address, consolidation is internal sweep)"
  - "transferAssetLocal gets full reservation + rebroadcast wiring as it is an external-address send"

patterns-established:
  - "Post-broadcast reservation: every external-address send inserts ReservedUtxoDao rows + PendingConsolidationDao row + schedules RebroadcastWorker BEFORE returning to ViewModel"
  - "Reconciliation on refresh: reconcileReservations(confirmedTxids, mempoolTxids) releases reservations for confirmed or stale-dropped txs"

requirements-completed: [WALLET-SEND, WALLET-UTXO]

# Metrics
duration: 4min
completed: 2026-04-21
---

# Phase 30 Plan 05: Consolidation Reliability Summary

**UTXO reservation after broadcast, pending consolidation tracking, and D-25 RebroadcastWorker with 30/60/120/240/480 min exponential ladder**

## Performance

- **Duration:** 4 min
- **Started:** 2026-04-21T18:42:35Z
- **Completed:** 2026-04-21T18:46:32Z
- **Tasks:** 2
- **Files modified:** 3

## Accomplishments
- sendRvnLocal reserves all consumed UTXOs and records pending consolidation immediately after broadcast, preventing phantom-balance double-spend (Pitfall 4)
- transferAssetLocal wired with identical reservation + rebroadcast logic for asset transfer sends
- reconcileReservations helper enables refresh-based cleanup: releases reservations for confirmed txs or stale-dropped txs older than 48h
- RebroadcastWorker auto-rebroadcasts stuck transactions across the 30/60/120/240/480 min ladder, capped at 5 attempts, with NetworkType.CONNECTED constraint only (D-27)
- Startup prune in MainActivity.onCreate removes reservations older than 48h (Pitfall 6 crash recovery)

## Task Commits

Each task was committed atomically:

1. **Task 2: Create RebroadcastWorker** - `6de86b1` (feat)
2. **Task 1: Extend WalletManager send paths** - `3b94976` (feat)

_Note: Task 2 was committed first (existing commit from prior session), Task 1 changes were uncommitted in the working tree._

## Files Created/Modified
- `android/app/src/main/java/io/raventag/app/worker/RebroadcastWorker.kt` - CoroutineWorker with D-25 ladder, 5-attempt cap, confirmation check, silent rebroadcast, PendingConsolidationDao status updates
- `android/app/src/main/java/io/raventag/app/wallet/WalletManager.kt` - Added consumedUtxos tracking in sendRvnLocal, ReservedUtxoDao.reserve + PendingConsolidationDao.upsert + RebroadcastWorker.schedule in sendRvnLocal and transferAssetLocal, reconcileReservations helper, em-dash cleanup
- `android/app/src/main/java/io/raventag/app/MainActivity.kt` - Added ReservedUtxoDao.pruneOlderThan(48h) at startup after WalletReliabilityDb.init

## Decisions Made
- issueAssetLocal and consolidateAllFundsToFreshAddress were NOT wired with reservation/rebroadcast because issueAsset emits the asset to the wallet's own next address (not external), and consolidation is an internal sweep. These are not external-address sends that risk phantom-balance display.
- transferAssetLocal WAS wired because it sends assets to an external address, creating the same phantom-UTXO risk as sendRvnLocal.
- The 48h stale threshold for both reconciliation and startup prune is a conservative upper bound: no Ravencoin transaction should remain unconfirmed for 48h at 1-minute block times.

## Deviations from Plan

None - plan executed exactly as written. Both tasks' code was already present (Task 2 committed in prior session, Task 1 in working tree). This execution verified acceptance criteria, confirmed the build, and committed the Task 1 changes.

## Issues Encountered
None - all code was already implemented and verified against acceptance criteria.

## User Setup Required
None - no external service configuration required.

## Hand-off to Downstream Plans

### Plan 30-08 (WalletScreen refresh and receive UX)
- WalletScreen ViewModel MUST call `walletManager.reconcileReservations(confirmedTxids, mempoolTxids)` on every successful refresh after fetching transaction history
- Surface a "consolidation confirmed" snackbar for any returned txid from reconcileReservations
- Displayed spendable balance = `sum(confirmed UTXOs) - ReservedUtxoDao.sumReservedSat()`

### Plan 30-09 (Tx history three-value display)
- Tx history must filter `is_self=true + cycled_sat>0 + sent_sat=0` as a pure-consolidation row (UI-SPEC self-transfer)
- The `consumedUtxos` variable name is used in sendRvnLocal for tracking which UTXOs were spent (for future audits)

### Variable names for future audits
- `consumedUtxos: List<Utxo>` in sendRvnLocal (both branches: atomic multi-address and simple send)
- `allConsumedUtxos` in transferAssetLocal
- Reservation line: immediately after `setCurrentAddressIndex(currentIndex + 1)` in sendRvnLocal, line ~1227
- Reconciliation line: lines 1264-1283 of WalletManager.kt
- Startup prune: MainActivity.kt line 2458

---
*Phase: 30-wallet-reliability*
*Completed: 2026-04-21*
