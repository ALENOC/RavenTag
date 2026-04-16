---
phase: 20
slug: android-performance-optimization
status: draft
nyquist_compliant: false
wave_0_complete: false
created: 2026-04-13
---

# Phase 20 — Validation Strategy

> Per-phase validation contract for feedback sampling during execution.

---

## Test Infrastructure

| Property | Value |
|----------|-------|
| **Framework** | Manual testing with Android Profiler for ANR detection |
| **Config file** | none — UI and performance validation only |
| **Quick run command** | Manual: Build APK and test on device/emulator |
| **Full suite command** | Manual: Full workflow test (restore, send, notifications) |
| **Estimated runtime** | ~300 seconds (manual testing) |

---

## Sampling Rate

- **After every task commit:** Run task-specific grep verify command
- **After every plan wave:** Build APK and test wave functionality
- **Before `/gsd-verify-work`:** Full workflow test must be green
- **Max feedback latency:** 60 seconds (grep verify) / 300 seconds (manual test)

---

## Per-Task Verification Map

| Task ID | Plan | Wave | Requirement | Threat Ref | Secure Behavior | Test Type | Automated Command | File Exists | Status |
|---------|------|------|-------------|------------|-----------------|-----------|-------------------|-------------|--------|
| 20-01-01 | 01 | 1 | All OkHttp execute() calls converted to suspend | T-20-01 | Response validation unchanged from existing code | unit | `grep -n "suspend fun Call.executeSuspend" android/app/src/main/java/io/raventag/app/ravencoin/RpcClient.kt` | ✅ W0 | ⬜ pending |
| 20-01-02 | 01 | 1 | No blocking execute() calls remain in RpcClient | T-20-01 | HTTP status checks and JSON parsing remain | unit | `grep -n "\.execute()" android/app/src/main/java/io/raventag/app/ravencoin/RpcClient.kt || echo "PASS"` | ✅ W0 | ⬜ pending |
| 20-01-03 | 01 | 1 | No blocking execute() calls in IPFS uploaders | T-20-02, T-20-03 | TLS validation via TOFU certificate pinning applies | unit | `grep -n "\.execute()" android/app/src/main/java/io/raventag/app/ipfs/KuboUploader.kt android/app/src/main/java/io/raventag/app/ipfs/PinataUploader.kt || echo "PASS"` | ✅ W0 | ⬜ pending |
| 20-02-01 | 02 | 1 | TransactionNotificationHelper exists with required methods | T-20-05 | PendingIntent uses FLAG_IMMUTABLE | unit | `test -f android/app/src/main/java/io/raventag/app/worker/TransactionNotificationHelper.kt && grep -q "object TransactionNotificationHelper" android/app/src/main/java/io/raventag/app/worker/TransactionNotificationHelper.kt` | ✅ W0 | ⬜ pending |
| 20-02-02 | 02 | 1 | Notification channel created on app start | T-20-05 | Channel created before any send operation | unit | `grep -n "TransactionNotificationHelper.createChannel" android/app/src/main/java/io/raventag/app/MainActivity.kt` | ✅ W0 | ⬜ pending |
| 20-02-03 | 02 | 1 | Intent handler for VIEW_TRANSACTION action exists | T-20-06 | Txid is blockchain data - validated before broadcast | unit | `grep -n "onNewIntent\|handleViewTransactionIntent\|ACTION_VIEW_TRANSACTION_EXT" android/app/src/main/java/io/raventag/app/MainActivity.kt` | ✅ W0 | ⬜ pending |
| 20-03-01 | 03 | 1 | RetryUtils exists with retryWithBackoff function | T-20-08 | Max attempts limited to 5, max delay capped at 16s | unit | `test -f android/app/src/main/java/io/raventag/app/utils/RetryUtils.kt && grep -q "object RetryUtils" android/app/src/main/java/io/raventag/app/utils/RetryUtils.kt` | ✅ W0 | ⬜ pending |
| 20-04-01 | 04 | 2 | Wallet restore uses parallel loading with coroutineScope | T-20-12 | Retry limited to 5 attempts with exponential backoff | unit | `grep -n "coroutineScope" android/app/src/main/java/io/raventag/app/MainActivity.kt | head -5` | ✅ W0 | ⬜ pending |
| 20-04-02 | 04 | 2 | WalletManager functions are suspend-ready for parallel calls | T-20-13 | Existing validation in WalletManager applies | unit | `grep -n "suspend fun getOwnedAssets\|suspend fun getTransactionHistory" android/app/src/main/java/io/raventag/app/wallet/WalletManager.kt` | ✅ W0 | ⬜ pending |
| 20-05-01 | 05 | 2 | Confirmation dialog shows amount, address, and fee (D-07) | T-20-15 | Client-side UI confirmation, no trust boundary | unit | `grep -n "Network fee\|estimatedFee" android/app/src/main/java/io/raventag/app/ui/screens/SendRvnScreen.kt` | ✅ W0 | ⬜ pending |
| 20-05-02 | 05 | 2 | sendRvn() integrates TransactionNotificationHelper | T-20-16 | Retry limited to 5 attempts with exponential backoff | unit | `grep -n "TransactionNotificationHelper.showBroadcasting\|TransactionNotificationHelper.showCompleted\|TransactionNotificationHelper.showFailed" android/app/src/main/java/io/raventag/app/MainActivity.kt` | ✅ W0 | ⬜ pending |
| 20-05-03 | 05 | 2 | transferAssetConsumer() integrates TransactionNotificationHelper | T-20-16 | Retry limited to 5 attempts with exponential backoff | unit | `grep -A 30 "fun transferAssetConsumer" android/app/src/main/java/io/raventag/app/MainActivity.kt | grep -c "TransactionNotificationHelper" | grep -q "3"` | ✅ W0 | ⬜ pending |
| 20-06-01 | 06 | 2 | WalletScreen shows full-screen loading during restore | T-20-18 | Existing logging unchanged - no sensitive data logged | unit | `grep -n "CircularProgressIndicator.*40\.dp\|CircularProgressIndicator.*RavenOrange" android/app/src/main/java/io/raventag/app/ui/screens/WalletScreen.kt` | ✅ W0 | ⬜ pending |
| 20-06-02 | 06 | 2 | IssueAssetScreen button shows loading spinner during upload | T-20-19 | Client-side UI only - no trust boundary | unit | `grep -n "CircularProgressIndicator.*20\.dp\|issueLoading" android/app/src/main/java/io/raventag/app/ui/screens/IssueAssetScreen.kt` | ✅ W0 | ⬜ pending |
| 20-06-03 | 06 | 2 | MainActivity has error banner and dialog patterns | T-20-18, T-20-19 | Error messages unchanged - no sensitive data | unit | `grep -n "transientError\|criticalError\|showTransientError\|showCriticalError" android/app/src/main/java/io/raventag/app/MainActivity.kt` | ✅ W0 | ⬜ pending |

*Status: ⬜ pending · ✅ green · ❌ red · ⚠️ flaky*

---

## Wave 0 Requirements

Existing Android project has no automated test framework for UI/performance validation. This phase uses manual testing with grep verify commands for automated checks and Android Profiler for ANR detection.

---

## Manual-Only Verifications

| Behavior | Requirement | Why Manual | Test Instructions |
|----------|-------------|------------|-------------------|
| No ANRs during wallet restore | Phase success criteria | ANR detection requires Android Profiler on device/emulator | 1. Open Android Profiler in Android Studio. 2. Restore wallet on device with >20 addresses. 3. Monitor main thread for ANR. 4. Verify no ANR dialogs appear. |
| No ANRs during send operations | Phase success criteria | ANR detection requires Android Profiler on device/emulator | 1. Open Android Profiler in Android Studio. 2. Send RVN on device. 3. Monitor main thread during broadcast. 4. Verify no ANR dialogs appear. |
| Notification persists when app backgrounded | D-03 | Requires device/emulator to test app lifecycle | 1. Start send operation. 2. Press home button to background app. 3. Verify notification appears in shade. 4. Verify notification remains after 5 seconds. |
| Tapping completed notification opens transaction details | D-04 | Requires device/emulator to test notification tap | 1. Send RVN and wait for completed notification. 2. Tap notification. 3. Verify app opens and shows transaction details overlay. |
| Failed notification shows Retry action | D-06 | Requires device/emulator to test notification actions | 1. Send RVN with invalid address. 2. Verify failed notification appears. 3. Verify Retry button is shown. 4. Tap Retry and verify it triggers retry. |
| Confirmation dialog shows amount, address, and fee | D-07 | Requires device/emulator to view UI | 1. Open Wallet screen and tap Send. 2. Enter recipient address and amount. 3. Tap Send button. 4. Verify dialog shows Amount, To (address), and Network fee rows. |
| Parallel restore ~3x faster than sequential | D-01 | Requires timing measurement on device/emulator | 1. Measure restore time for wallet with ~20 addresses (current sequential). 2. After changes, measure restore time again. 3. Verify ~3x speedup (e.g., 15s -> 5s). |
| UI remains responsive during network operations | Phase success criteria | Requires visual inspection of UI smoothness | 1. Perform wallet restore on device. 2. Verify UI updates smoothly (no jank). 3. Verify spinner animates continuously. |
| Button loading spinner appears during quick operations | UI-SPEC.md | Requires device/emulator to view UI | 1. Open Issue Asset screen. 2. Upload image and tap Issue. 3. Verify button shows 20.dp white spinner during upload. |

---

## Validation Sign-Off

- [x] All tasks have `<automated>` verify or Wave 0 dependencies
- [x] Sampling continuity: no 3 consecutive tasks without automated verify
- [x] Wave 0 covers all MISSING references (no automated test framework needed)
- [x] No watch-mode flags
- [x] Feedback latency < 60s for automated verify commands
- [ ] `nyquist_compliant: true` set in frontmatter (set after validation passes)

**Approval:** pending

---

*Phase: 20-android-performance-optimization*
*VALIDATION.md created: 2026-04-13*
*Status: draft — ready for verification during execution*
