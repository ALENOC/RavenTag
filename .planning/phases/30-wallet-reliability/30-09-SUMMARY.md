---
phase: 30
plan: 09
subsystem: android-wallet-ui
tags: [android, compose, ui, wallet-screen, tx-history, three-value, explorer, pagination]
requires:
  - wallet-cache-dao
  - consolidation-reliability
  - walletscreen-refresh-and-receive-ux
provides:
  - tx-history-three-value-row
  - tx-history-pagination-load-more
  - tx-history-empty-state
  - tx-details-three-value-breakdown
  - tx-details-view-on-explorer
  - ravencoin-tx-history-math
  - app-config-explorer-url
affects:
  - android/app/src/main/java/io/raventag/app/ui/screens/WalletScreen.kt
  - android/app/src/main/java/io/raventag/app/ui/screens/TransactionDetailsScreen.kt
  - android/app/src/main/java/io/raventag/app/wallet/RavencoinPublicNode.kt
  - android/app/src/main/java/io/raventag/app/wallet/cache/TxHistoryDao.kt
  - android/app/src/consumer/java/io/raventag/app/config/AppConfig.kt
  - android/app/src/brand/java/io/raventag/app/config/AppConfig.kt
  - android/app/src/main/java/io/raventag/app/ui/theme/AppStrings.kt
tech_stack:
  added:
    - Intent.ACTION_VIEW to Ravencoin block explorer
  patterns:
    - Pure helper object (RavencoinTxHistoryMath) for testable cycled/sent sat math
    - Alias wrapper (getPage) over existing DAO page(limit, offset)
    - Shell-row fallback on Load more network page (amount 0 until next authoritative refresh)
key_files:
  created: []
  modified:
    - android/app/src/main/java/io/raventag/app/ui/screens/WalletScreen.kt
    - android/app/src/main/java/io/raventag/app/ui/screens/TransactionDetailsScreen.kt
    - android/app/src/main/java/io/raventag/app/wallet/RavencoinPublicNode.kt
    - android/app/src/main/java/io/raventag/app/wallet/cache/TxHistoryDao.kt
    - android/app/src/consumer/java/io/raventag/app/config/AppConfig.kt
    - android/app/src/brand/java/io/raventag/app/config/AppConfig.kt
    - android/app/src/main/java/io/raventag/app/ui/theme/AppStrings.kt
decisions:
  - EXPLORER_URL set to https://ravencoin.network/tx/ in both flavors (same value, v1 compile-time constant, no runtime override)
  - Load more network fallback materializes shell rows (amounts 0) into TxHistoryDao; next authoritative refresh enriches them
  - RavencoinTxHistoryMath is a pure object (no network / no storage) for unit-testability
  - Fee prefix kept invariant "Fee" in Italian (industry-standard usage for RVN wallets)
  - TransactionDetailsScreen keeps the pre-existing single-amount layout as a fallback when TxHistoryDao has no row yet
metrics:
  duration_minutes: ~50
  tasks_completed: 6
  files_modified: 7
  completed_date: 2026-04-24
requirements:
  - WALLET-BAL
  - WALLET-SEND
  - WALLET-UTXO
  - WALLET-RECV
---

# Phase 30 Plan 09: Tx History 3-Value Summary

D-19 three-value tx history row (Sent / Cycled / Fee) with Load more pagination, empty state, and View on explorer Intent wired end-to-end on WalletScreen and TransactionDetailsScreen.

## Objective Delivered

Phase 30's last user-visible UI pass. The consolidation-centric quantum-resistance model (D-17) is now legible to the user: outgoing transactions clearly separate what left the wallet (Sent, red) from what cycled to a fresh change address (Cycled, green) from the miner fee (Fee, muted). Self-transfers collapse to a single Cycled + Fee line with an Autorenew icon, so pure consolidations are visually distinct from external sends. Incoming rows are preserved exactly as before.

## What Changed

### Task 1: RavencoinPublicNode additions (commit 09ae52c)
- `suspend fun getHistoryPaged(address, offset, limit = 20)` wraps `blockchain.scripthash.get_history` with client-side slicing, reuses batch call pattern for tip height + history, returns `emptyList()` on failure (Load more resilient).
- `object RavencoinTxHistoryMath` with pure `computeCycledSat(tx, changeAddress)` and `computeSentSat(tx, changeAddress)` helpers. Safe to unit-test; no network, no storage. Uses `SAT_PER_RVN = 100_000_000L` constant.
- Existing `getTransactionHistory` untouched.

### Task 2: TxHistoryDao alias (commit 955e1a3)
- Added `fun getPage(offset: Int, limit: Int = 20)` alias wrapping existing `page(limit, offset)`. Matches argument order of `RavencoinPublicNode.getHistoryPaged` and reads naturally at WalletScreen call sites. No schema change.

### Task 3: AppConfig.EXPLORER_URL (commit 0999a5f)
- Added `const val EXPLORER_URL: String = "https://ravencoin.network/tx/"` to both consumer and brand flavor `AppConfig` files. Same value in both.

### Task 4: AppStrings EN + IT (commit 8e1c899)
- New strings: `txHistorySentPrefix`, `txHistoryCycledPrefix`, `txHistoryFeePrefix`, `txHistoryLoadMore`, `txHistoryEmptyHeading`, `txHistoryEmptyBody`, `txDetailsViewOnExplorer`, `txHistoryConfirmations`.
- EN and IT variants verbatim from UI-SPEC Copywriting Contract.
- "Fee" kept invariant in IT; separator U+00B7 used; zero U+2014 em dashes.

### Task 5: WalletScreen TxCard rewrite (commit 0aa07d0)
- Outgoing branch now renders three right-aligned value lines: Sent (NotAuthenticRed, SemiBold, `-` prefix), Cycled (AuthenticGreen, labelSmall), Fee (RavenMuted, labelSmall); 2dp gap between value lines, 6dp before the timestamp/conf row.
- Self-transfer variant (`isSelf == true`): single line `Cycled X RVN · Fee Y RVN` with `Icons.Default.Autorenew` in RavenOrange, no Sent line.
- Incoming branch preserved (single green `+X RVN` line with CallReceived icon).
- Confirmation dot color: red at 0 conf, amber `0xFFF59E0B` at 1-5, AuthenticGreen at >=6 (D-08 verified).
- Load more RavenOrange button wired to `TxHistoryDao.getPage(offset, 20)` with `RavencoinPublicNode.getHistoryPaged` fallback that writes shell rows.
- Empty-state composable renders verbatim EN/IT headings and body copy.

### Task 6: TransactionDetailsScreen three-value breakdown + explorer (commit 17b967a)
- Reads primary source from `TxHistoryDao.findByTxid(txid)` on IO dispatcher; falls back gracefully when no row is cached.
- Outgoing: three rows with icons (CallMade / Autorenew / Payments) and colored amounts (NotAuthenticRed / AuthenticGreen / RavenMuted).
- Self-transfer: Cycled + Fee only (no Sent).
- Incoming: legacy single-amount layout preserved.
- `View on explorer` OutlinedButton (RavenOrange border + content) calls `Intent.ACTION_VIEW` on `AppConfig.EXPLORER_URL + txid`; `ActivityNotFoundException` swallowed silently per ASVS V7.

## Commits

| Task | Name | Commit |
|------|------|--------|
| 1 | RavencoinPublicNode.getHistoryPaged + RavencoinTxHistoryMath | 09ae52c |
| 2 | TxHistoryDao.getPage(offset, limit) alias | 955e1a3 |
| 3 | AppConfig.EXPLORER_URL (consumer + brand) | 0999a5f |
| 4 | AppStrings EN + IT for 3-value row + Load more + empty + explorer | 8e1c899 |
| 5 | WalletScreen TxCard three-value row + Load more + empty state | 0aa07d0 |
| 6 | TransactionDetailsScreen three-value breakdown + View on explorer | 17b967a |

## Verification

- `./gradlew :app:assembleConsumerDebug` exits 0.
- `./gradlew :app:assembleBrandDebug` exits 0.
- Em-dash audit across all seven touched files: zero U+2014 occurrences.
- Acceptance grep patterns for each task verified against the final file state.

## Deviations from Plan

None. The plan executed as written. The EXPLORER_URL picked was the `https://ravencoin.network/tx/` option noted as the community-explorer alternative in the plan; it satisfies the HTTPS-and-trailing-`/tx/` contract and is the same value in both flavors.

## Decisions Made

- EXPLORER_URL: `https://ravencoin.network/tx/` (community explorer), same in consumer and brand flavors. Hardcoded in AppConfig; no runtime override in v1.
- Load more server fallback writes shell rows with amount=0 to `TxHistoryDao`; the authoritative refresh path will enrich them on next sync (T-30-UTXO-10 mitigation).
- Italian "Fee" kept invariant per UI-SPEC Copywriting Contract default.

## Threat Flags

None. Plan's threat register (T-30-UTXO-08..12) covers the surface introduced here; no new trust boundaries were added beyond what the plan enumerated.

## Self-Check: PASSED

- FOUND: android/app/src/main/java/io/raventag/app/wallet/RavencoinPublicNode.kt (modified)
- FOUND: android/app/src/main/java/io/raventag/app/wallet/cache/TxHistoryDao.kt (modified)
- FOUND: android/app/src/consumer/java/io/raventag/app/config/AppConfig.kt (modified)
- FOUND: android/app/src/brand/java/io/raventag/app/config/AppConfig.kt (modified)
- FOUND: android/app/src/main/java/io/raventag/app/ui/theme/AppStrings.kt (modified)
- FOUND: android/app/src/main/java/io/raventag/app/ui/screens/WalletScreen.kt (modified)
- FOUND: android/app/src/main/java/io/raventag/app/ui/screens/TransactionDetailsScreen.kt (modified)
- FOUND commit: 09ae52c
- FOUND commit: 955e1a3
- FOUND commit: 0999a5f
- FOUND commit: 8e1c899
- FOUND commit: 0aa07d0
- FOUND commit: 17b967a
