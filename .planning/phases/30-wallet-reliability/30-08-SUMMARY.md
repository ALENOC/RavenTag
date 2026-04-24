---
phase: 30
plan: 08
subsystem: android-wallet-ui
tags: [android, compose, ui, notifications, workmanager, subscription, receive, wallet-screen]
requires:
  - wallet-cache-dao
  - scripthash-subscription
  - node-health-monitor
  - consolidation-reliability
provides:
  - incoming-tx-notification-channel
  - wallet-screen-cached-banner
  - wallet-screen-connection-pill-ui
  - wallet-screen-pending-line
  - wallet-screen-battery-saver-chip
  - receive-screen-d18-sublabel
  - receive-screen-cross-fade
affects:
  - android/app/src/main/java/io/raventag/app/ui/screens/WalletScreen.kt
  - android/app/src/main/java/io/raventag/app/ui/screens/ReceiveScreen.kt
  - android/app/src/main/java/io/raventag/app/worker/WalletPollingWorker.kt
  - android/app/src/main/java/io/raventag/app/worker/IncomingTxNotificationHelper.kt
  - android/app/src/main/java/io/raventag/app/MainActivity.kt
  - android/app/src/main/java/io/raventag/app/ui/theme/AppStrings.kt
tech_stack:
  added:
    - Jetpack Compose ModalBottomSheet + AnimatedContent
    - androidx.compose.material3 LinearProgressIndicator
  patterns:
    - StateFlow -> collectAsState for connection health
    - SubscriptionManager.eventsFlow SharedFlow collector
    - SharedPreferences diff (last_status_<addr>) for background-notification baseline
key_files:
  created:
    - android/app/src/main/java/io/raventag/app/worker/IncomingTxNotificationHelper.kt
  modified:
    - android/app/src/main/java/io/raventag/app/ui/screens/WalletScreen.kt
    - android/app/src/main/java/io/raventag/app/ui/screens/ReceiveScreen.kt
    - android/app/src/main/java/io/raventag/app/worker/WalletPollingWorker.kt
    - android/app/src/main/java/io/raventag/app/MainActivity.kt
    - android/app/src/main/java/io/raventag/app/ui/theme/AppStrings.kt
decisions:
  - Reuse pre-existing Phase 20 VIEW_TRANSACTION intent handler instead of adding a second one
  - WalletScreen SubscriptionManager instance is local-scoped via remember { SubscriptionManager(context) }
  - 30s periodic refresh loop is a simple while(true)+delay, gated by PowerManager.isPowerSaveMode inside the loop
  - Power-save detection uses a one-shot remember probe (not a BroadcastReceiver) per D-28 acceptance
metrics:
  duration_minutes: ~90
  tasks_completed: 6
  files_modified: 6
  completed_date: 2026-04-23
---

# Phase 30 Plan 30-08: WalletScreen Refresh and Receive UX Summary

One-liner: delivered the visible surface for Phase 30 reliability (cached-state banner, connection-health pill with quarantine sheet, pending mempool line, battery-saver chip, D-06 background incoming-tx notifications, D-18 receive cross-fade) by wiring the data sources from plans 30-02/03/05/07 into WalletScreen and ReceiveScreen and adding a new `incoming_tx` NotificationChannel.

## What Was Built

### Task 1: IncomingTxNotificationHelper (commit 145ccbc)
New `android/app/src/main/java/io/raventag/app/worker/IncomingTxNotificationHelper.kt`:
- Channel `incoming_tx` with IMPORTANCE_DEFAULT, showBadge=true
- Three text variants (mempool / confirming / confirmed) selected by confirmations count
- EN + IT verbatim from UI-SPEC Copywriting Contract (middle dot U+00B7 separator)
- notificationId = 2100 + (txid.hashCode() and 0x3FF)
- PendingIntent uses FLAG_IMMUTABLE + explicit MainActivity component
- POST_NOTIFICATIONS permission guard on API 33+

### Task 2: MainActivity channel registration (commit 2b9a27a)
- `IncomingTxNotificationHelper.createChannel(applicationContext)` inserted at MainActivity.kt line 2455 alongside the two existing channel registrations.
- VIEW_TRANSACTION handler pre-existed (added by Phase 20 plan 20-05). The existing handler at MainActivity.kt:2844-2851 reads `TransactionNotificationHelper.ACTION_VIEW_TRANSACTION_EXT` / `EXTRA_TXID_EXT` (both constants resolve to the same string values "VIEW_TRANSACTION" / "txid" as the new helper). It routes via `viewModel.handleViewTransactionIntent(txid)`. No new handler was added — Phase 20 already provides the TransactionDetailsScreen route.
- `onNewIntent` already dispatches via `handleIntent(intent)` (pre-existing), which now implicitly handles both notification sources thanks to the shared action string.

### Task 3: WalletPollingWorker D-06 extension (commit 5869d71)
- Added per-address scripthash-status diff pass AFTER the Phase 20 balance-diff logic
- Uses `WalletManager.getCurrentAddressIndex()` + `getAddressBatch(0, 0..currentIndex)` to resolve active addresses
- SharedPreferences key pattern `last_status_<addr>` persists per-address ElectrumX scripthash status hash
- On baseline-established change: re-fetch balance, compute delta, call `IncomingTxNotificationHelper.showIncoming(...)` with txid + confirmations from `getTransactionHistory` first-unseen row
- `NodeHealthMonitor.init(applicationContext)` called at top of doWork() (idempotent singleton)
- IOException -> Result.retry; other exceptions silent (D-06 silent background path)

### Task 4: AppStrings.kt EN + IT strings (commit a56e064)
- 20 new properties added to `class AppStrings` with EN defaults
- EN assignments in stringsEn, IT overrides in stringsIt
- All Copywriting Contract strings verbatim from UI-SPEC: cachedStateBanner, cachedStateReconnecting, pendingBalanceLabel, batterySaverChip, connectionPillOnline/Reconnecting/Offline/SheetTitle/CurrentNode/LastSuccess/FallbackNodes/Quarantined/Close/NoNode, reconnectingToast, offlineAllNodesUnreachable, incomingTxSnackbar, receiveCurrentAddressLabel, receiveCurrentAddressSubLabel, walletOfflineHeading, walletOfflineBody
- No U+2014 em dashes; all separators use U+00B7 middle dot or colon/comma

### Task 5: WalletScreen.kt UX integration (commit 1379196)
- `CachedStateBanner`: Card with RavenBorder, Icons.Default.History, HH:MM timestamp via SimpleDateFormat
- `PendingBalanceLine`: mempool-incoming row (Icons.Default.Schedule, amber 0xFFF59E0B amount, +%.8f RVN)
- `BatterySaverChip`: 25% alpha amber border, Icons.Default.BatterySaver, labelSmall text, power-save-gated
- `ConnectionPillSheet`: ModalBottomSheet with current-node (monospace), last-success HH:MM, fallback-node list with quarantine markers, OutlinedButton Close
- `ConnectionHealthPill`: new composable driven by NodeHealthMonitor.stateFlow (GREEN pulsing dot / YELLOW pulsing dot / RED static dot), taps open the sheet, minHeight 48dp
- 2dp `LinearProgressIndicator` under header while `isRefreshing`
- 30s `while(true) delay(30_000L)` refresh loop gated by `PowerManager.isPowerSaveMode`
- SubscriptionManager.eventsFlow() collector: on StatusChanged, re-fetch balance, read before/after from WalletCacheDao, emit `+X RVN received` Snackbar on positive delta
- SubscriptionManager instance source: `val subscriptionManager = remember { SubscriptionManager(context) }` (screen-local, no DI)
- Disabled Send/Receive when ConnectionHealth.RED: alpha(0.3f), RavenMuted foreground, wrapper Box Modifier.clickable shows `offlineAllNodesUnreachable` Snackbar even when buttons are `enabled = false`
- Existing legacy `ElectrumStatusBadge` preserved alongside the new pill for existing telemetry (YELLOW state is now represented by the new pill)
- TxCard intentionally untouched (plan 30-09 owns the 3-value rewrite)

### Task 6: ReceiveScreen D-18 (commit 244f004)
- Added main label (`receiveCurrentAddressLabel`) + sub-label (`receiveCurrentAddressSubLabel`) below the QR code
- Wrapped address Text in `AnimatedContent(targetState = address, transitionSpec = { fadeIn(tween(200)) togetherWith fadeOut(tween(200)) })`
- Preserved existing clipboard copy handler verbatim
- No rotation button, no multi-address UI (D-18 explicitly excludes these)

### Em-dash cleanup (commit 5bce043)
MainActivity.kt contained two pre-existing em dashes in comments (lines 975, 3260) — replaced per project MEMORY rule before considering this plan closed.

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 2 - Missing functionality] MainActivity em-dash audit required cleanup**
- **Found during:** Task 2 acceptance-criteria audit (`! grep -P '—' MainActivity.kt`)
- **Issue:** Two pre-existing em dashes in MainActivity code comments (not added by this plan) would fail the project-wide em-dash ban enforced by CLAUDE/MEMORY rules
- **Fix:** Replaced `—` with `:` on line 975 and with `,` on line 3260
- **Files modified:** android/app/src/main/java/io/raventag/app/MainActivity.kt
- **Commit:** 5bce043

## Hand-offs

### To plan 30-09 (Tx History 3-value)
- WalletScreen TxCard rendering preserved exactly as-is. The `items(txHistory)` block in the LazyColumn is untouched. Plan 30-09 can freely rewrite `TxCard` without colliding with any 30-08 edits.
- New `SnackbarHost` overlay is attached to the outer `Box` wrapper at WalletScreen.kt bottom; plan 30-09 can share it for tx-detail affordances.

### To plan 30-10 (Housekeeping)
- Em-dash audit sweep should include: IncomingTxNotificationHelper.kt, WalletPollingWorker.kt, MainActivity.kt, WalletScreen.kt, ReceiveScreen.kt, AppStrings.kt. All were audited here and are clean at time of commit.
- Strings added in Task 4 all live in stringsEn + stringsIt (no missing IT overrides).

## Verification

- `cd android && ./gradlew :app:assembleConsumerDebug` exits 0 (last run after all commits)
- `cd android && ./gradlew :app:compileConsumerDebugKotlin` exits 0
- `! grep -P '—'` on every touched file returns no matches
- All acceptance criteria for Tasks 1-6 pass (grep audit performed in-session)

## Self-Check: PASSED

- File `android/app/src/main/java/io/raventag/app/worker/IncomingTxNotificationHelper.kt`: FOUND
- File `android/app/src/main/java/io/raventag/app/ui/screens/WalletScreen.kt`: FOUND
- File `android/app/src/main/java/io/raventag/app/ui/screens/ReceiveScreen.kt`: FOUND
- File `android/app/src/main/java/io/raventag/app/worker/WalletPollingWorker.kt`: FOUND
- File `android/app/src/main/java/io/raventag/app/MainActivity.kt`: FOUND
- File `android/app/src/main/java/io/raventag/app/ui/theme/AppStrings.kt`: FOUND
- Commit 145ccbc: FOUND
- Commit 2b9a27a: FOUND
- Commit 5869d71: FOUND
- Commit a56e064: FOUND
- Commit 1379196: FOUND
- Commit 244f004: FOUND
- Commit 5bce043: FOUND
- Build `./gradlew :app:assembleConsumerDebug`: PASSED
