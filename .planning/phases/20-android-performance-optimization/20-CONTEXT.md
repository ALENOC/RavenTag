# Phase 20: Android Performance Optimization - Context

**Gathered:** 2026-04-13
**Status:** Ready for planning

<domain>
## Phase Boundary

Eliminate UI blocking in the Android app by converting synchronous network/IO operations (OkHttp execute(), enrichWithIpfsData) to async suspend functions with withContext(Dispatchers.IO). Optimize wallet restore performance and ensure send operations (RVN/assets) do not block the UI thread. No ANRs during normal operations.

</domain>

<decisions>
## Implementation Decisions

### Wallet Restore Optimization
- **D-01:** Parallel loading for wallet restore. Load UTXOs, balances, and transaction history simultaneously using Kotlin coroutines (async/awaitAll). This provides ~3x speedup over sequential loading.
- **D-02:** Auto-retry failed parts of parallel restore before showing error. 5 retries with exponential backoff. After exhausting retries, show error notification with option to retry manually.

### Send Operation UX
- **D-03:** Background execution with Android notification system for send operations. User can dismiss the app while transaction broadcasts. Notification shows progress (broadcasting, confirming, completed/failed).
- **D-04:** Tapping send notification opens to transaction details screen (not main wallet).
- **D-05:** Multiple progress notifications during send operation lifecycle: "Broadcasting...", "Confirming (1/N)", "Completed" or "Failed". Use notification ID to update the same notification slot.
- **D-06:** Auto-retry failed sends before showing error. 5 retries with exponential backoff (consistent with wallet restore policy). After exhausting retries, show failure notification with "Retry" action.
- **D-07:** Always show confirmation dialog before sending. Dialog displays: amount, recipient address, and network fee. User must explicitly confirm before broadcast begins.

### Claude's Discretion
- Loading UI pattern for non-send/non-restore async operations (spinners, progress indicators on buttons)
- Async error handling for general operations (snackbar for transient errors, dialog for critical failures)
- Cancellation policy for in-progress operations (e.g., user navigates away during IPFS upload)
- IPFS upload async conversion details (KuboUploader, PinataUploader execute() migration)
- Exact notification channel configuration and styling

</decisions>

<canonical_refs>
## Canonical References

**Downstream agents MUST read these before planning or implementing.**

### Android App Structure
- `android/app/src/main/java/io/raventag/app/MainActivity.kt` — Main activity with wallet loading, send operations, and withContext patterns
- `android/app/src/main/java/io/raventag/app/wallet/WalletManager.kt` — HD wallet management, restore logic, balance loading
- `android/app/src/main/java/io/raventag/app/wallet/AssetManager.kt` — Asset/sub-asset issuance, admin key, RPC calls
- `android/app/src/main/java/io/raventag/app/ravencoin/RpcClient.kt` — Ravencoin RPC client (OkHttp-based)
- `android/app/src/main/java/io/raventag/app/worker/WalletPollingWorker.kt` — Background wallet polling

### IPFS Upload (Blocking)
- `android/app/src/main/java/io/raventag/app/ipfs/KuboUploader.kt` — IPFS Kubo upload (OkHttp execute(), blocking)
- `android/app/src/main/java/io/raventag/app/ipfs/PinataUploader.kt` — Pinata IPFS upload (OkHttp execute(), blocking)

### UI Screens
- `android/app/src/main/java/io/raventag/app/ui/screens/WalletScreen.kt` — Wallet UI with send flow
- `android/app/src/main/java/io/raventag/app/ui/screens/IssueAssetScreen.kt` — Asset issuance UI

### Project Context
- `.planning/PROJECT.md` — Project vision, requirements, constraints
- `.planning/phases/10-android-security-hardening/10-01-SUMMARY.md` — Admin key migration (affects AssetManager patterns)
- `.planning/phases/10-android-security-hardening/10-02-SUMMARY.md` — TOFU fingerprint persistence (affects RavencoinPublicNode)

</canonical_refs>

<code_context>
## Existing Code Insights

### Reusable Assets
- `withContext(Dispatchers.IO)` pattern already used in WalletScreen and MainActivity (partial adoption exists)
- `rememberCoroutineScope()` already used in MnemonicBackupScreen
- `OkHttpClient` singleton pattern already established in KuboUploader and PinataUploader

### Established Patterns
- OkHttp `execute()` is the blocking call pattern found in KuboUploader and PinataUploader
- `withContext(Dispatchers.Main)` used for UI updates after background work in MainActivity
- RavencoinPublicNode uses async WebSocket for ElectrumX connections (already non-blocking)
- RpcClient uses synchronous OkHttp calls (needs migration to suspend functions)

### Integration Points
- MainActivity.loadWalletBalance() — current wallet restore entry point
- AssetManager.deriveChipKeys() — IPFS enrichment with blocking calls
- WalletManager — wallet restore with sequential UTXO/balance loading
- Send flow in WalletScreen — currently blocks UI during broadcast

</code_context>

<specifics>
## Specific Ideas

- Parallel wallet restore using `coroutineScope { async { ... } }` pattern for UTXOs, balances, and transactions
- Android notification system for send operations with `NotificationCompat.Builder` and `NotificationManager`
- Notification channel: "transaction_progress" for send operation notifications
- Confirmation dialog: Compose `AlertDialog` showing amount, address, and fee before send
- Retry with exponential backoff: base delay 1s, multiplier 2x, max 5 retries

</specifics>

<deferred>
## Deferred Ideas

None — discussion stayed within phase scope

</deferred>

---

*Phase: 20-android-performance-optimization*
*Context gathered: 2026-04-13*