# Phase 30: Wallet Reliability - Context

**Gathered:** 2026-04-17
**Status:** Ready for planning

<domain>
## Phase Boundary

Make the Android RVN wallet's state (balance, UTXO set, transaction history, mnemonic) accurate, resilient, and quantum-resistant end-to-end. Scope covers: sync cadence and state caching, receive detection via ElectrumX subscriptions, multi-node failover with TOFU fingerprint quarantine, mnemonic export/import safety, keystore integrity, fee estimation, transaction history display, mempool/stuck-tx handling, asset-UTXO reservation during consolidation, power-save behavior, and the speed/reliability optimization of the existing quantum-resistance consolidation pattern.

Out of scope: backend stability (Phase 50), asset emission UX (Phase 40), security hardening already completed in Phase 10, and the async conversion work already completed in Phase 20.

</domain>

<decisions>
## Implementation Decisions

### Balance & UTXO Sync
- **D-01:** Sync triggers are both (a) on app foreground / WalletScreen resume and (b) periodic poll while WalletScreen is visible. No manual-only mode.
- **D-02:** Periodic poll interval is 30 seconds while WalletScreen is in foreground.
- **D-03:** On balance vs UTXO-sum mismatch, trust `sum(utxo.value)` as displayed spendable balance. Log the discrepancy (structured log, no user-visible error).
- **D-04:** Persist last-known wallet state (balance, UTXOs, recent tx history) in SQLite. On WalletScreen open, render cached state instantly with a "Last updated HH:MM" indicator, then refresh in background.

### Receive Detection
- **D-05:** Primary detection is ElectrumX `blockchain.scripthash.subscribe` per wallet address while app is foreground. Subscription delivers near-instant mempool + confirmation notifications.
- **D-06:** Background detection runs via Android WorkManager periodic job every 15 minutes when app is closed. Fires system notification on new tx.
- **D-07:** On new incoming tx, user sees ALL of: in-app banner/snackbar, system notification, balance auto-update, and new entry in transaction history list with confirmation progress.
- **D-08:** A received transaction is considered final in UI at 6 confirmations. Until then show `N/6 confirmations` progress. Unconfirmed (mempool) shows as "Pending".

### Node Reliability & Failover
- **D-09:** Hardcoded fallback list of ~3-5 known public ElectrumX nodes. Round-robin on connection/RPC failure. No user-configurable list in this phase.
- **D-10:** Per-node TLS timeouts: 10s connect / 20s RPC. Matches current NetworkModule effective timeouts.
- **D-11:** TOFU fingerprint mismatch → quarantine node for 1 hour, then retry. If still mismatched, keep quarantined. Logged but not surfaced to user.
- **D-12:** Degraded-state UX: connection status badge on WalletScreen (green / yellow / red pill), stale-balance indicator ("Last updated HH:MM · reconnecting…") when fetch fails, Send/Receive actions disabled when ALL fallback nodes have failed. Transient flakiness does not change UI.

### Mnemonic Export / Import
- **D-13:** Export format for v1: 12/24-word phrase display with copy-to-clipboard only. QR and encrypted-file formats are deferred to a future phase.
- **D-14:** Import/restore verification requires ALL of: BIP39 checksum validation, confirmation dialog naming the current wallet's balance/assets ("will replace current wallet X RVN, Y assets — cannot be undone"), and a forced current-wallet backup step before import is allowed when current wallet has non-zero balance.
- **D-15:** Keystore integrity: detect `KeyPermanentlyInvalidatedException` on decrypt and route user to re-auth or mnemonic restore; require BiometricPrompt (fingerprint/face/PIN) before revealing mnemonic words; store HMAC of seed alongside ciphertext and verify on load.
- **D-16:** Never cache decrypted mnemonic in memory after use. Re-decrypt from Android Keystore on every operation. Clear char arrays after use.

### Quantum-Resistance Consolidation (CRITICAL)
- **D-17:** The wallet already implements a quantum-resistance consolidation pattern that must be preserved, optimized for speed and reliability, and remain invisible to the end user. Rules:
  - When the user sends RVN or assets to an external address, the wallet constructs an atomic transaction that (a) sends the requested amount to the external address, (b) sweeps all remaining RVN and any assets on the sending address to a new never-spent address at `currentIndex + 1`.
  - When RVN or assets arrive at an old address (derivation index < `currentIndex`), the wallet auto-consolidates those funds to a new never-spent address at `currentIndex + 1`. If an old address receives only assets and has no RVN to fund the consolidation tx, the wallet funds it from `currentIndex` (which therefore becomes spent — `currentIndex` must then advance to `currentIndex + 1`).
  - Rationale: unspent P2PKH addresses do not expose their public key on-chain (only the RIPEMD160-SHA256 hash). Keeping the active balance on a never-spent address protects against hypothetical quantum attackers who could derive the private key from the public key.
  - This phase's job is NOT to add this behavior (already works) but to make it faster and more reliable.
- **D-18:** Receive address strategy: ReceiveScreen always displays the current `currentIndex` never-spent address. After any external send or auto-consolidation, the displayed address advances to the new `currentIndex`. No per-receive rotation — the quantum-resistance model IS the rotation.
- **D-19:** Transaction history must display outgoing transactions with three explicit values visible to the user:
  1. Amount sent to external address (e.g., `-5 RVN to R...`)
  2. Amount cycled to new never-spent address (e.g., `245 - fee RVN → new address`)
  3. Fee paid (e.g., `Fee: 0.0012 RVN`)
  Example: user has 250 RVN, sends 5 to external address. History row shows: `Sent 5 RVN · Cycled 244.9988 RVN · Fee 0.0012 RVN`.
- **D-20:** Asset/RVN UTXOs reserved by a pending consolidation are locked in a SQLite `reserved_utxos(txid_in, vout, tx_submitted_at)` table on tx submit. Rows removed on confirm or detected drop. Displayed spendable balance = `sum(confirmed_utxo) - sum(reserved_utxo)`.
- **D-21:** Consolidation failure recovery: silent retry with Phase 20 backoff policy (5× exp backoff). If still failing, persist a `pending_consolidation` flag and retry on next wallet refresh / app foreground. User is notified only if the pending-consolidation state has persisted across multiple blocks (funds exposed). Never block new sends on a pending consolidation — throughput matters more than strict sequential consolidation.

### Fee Estimation
- **D-22:** Fees are determined dynamically via ElectrumX `blockchain.estimatefee` (target ~6 blocks) and shown in the send confirmation dialog (Phase 20 D-07), with an editable override field. Consolidation txs use the same logic. Fallback to a safe static rate (0.01 RVN/kB) when estimatefee is unavailable.

### Transaction History
- **D-23:** WalletScreen shows the last 20 transactions inline with a "Load more" button. Older history is paged backwards via ElectrumX `blockchain.scripthash.get_history`. Transactions cached in SQLite for offline display.

### Mempool & Stuck Transactions
- **D-24:** Unconfirmed incoming mempool outputs are NOT counted as spendable balance. They appear as a separate "Pending" line on WalletScreen. Spendable = confirmed UTXOs only (minus reserved, per D-20).
- **D-25:** Outgoing transactions that remain unconfirmed are auto-rebroadcast to all fallback nodes after N minutes (suggested 30 min; tunable). Silent — no user-facing action required.

### Power Save Behavior
- **D-26:** When `PowerManager.isPowerSaveMode()` is true, pause the 30s periodic poll; keep the ElectrumX scripthash subscription open (push-based, minimal cost).
- **D-27:** Consolidation transactions always broadcast regardless of power-save / data-saver state. Security-critical — must not be throttled.
- **D-28:** When sync is in a reduced mode, surface a small status indicator on WalletScreen ("Battery saver — manual refresh recommended") to prevent stale-balance confusion.

### Claude's Discretion
- Exact SQLite schema for wallet state cache, reserved UTXOs, and transaction history tables.
- Notification channel configuration for incoming tx (separate from transaction_progress channel introduced in Phase 20).
- Exact WorkManager scheduling parameters (backoff, constraints, initial delay).
- Compose UI details for connection status badge, pending-balance line, and tx history row layout showing the three values from D-19.
- Coroutine/flow architecture for subscription delivery → UI state updates.
- Exact fallback ElectrumX node list (defer to researcher to gather current public nodes).
- Retry/backoff constants not already fixed by Phase 20 D-02.

</decisions>

<canonical_refs>
## Canonical References

**Downstream agents MUST read these before planning or implementing.**

### Wallet Core
- `android/app/src/main/java/io/raventag/app/wallet/WalletManager.kt` — HD wallet, restore, send, consolidation logic (`currentIndex` management lives here)
- `android/app/src/main/java/io/raventag/app/wallet/RavencoinPublicNode.kt` — ElectrumX client (scripthash, balance, UTXO, history, estimatefee, subscribe RPC)
- `android/app/src/main/java/io/raventag/app/wallet/RavencoinTxBuilder.kt` — Tx construction, signing, asset + consolidation outputs
- `android/app/src/main/java/io/raventag/app/wallet/AssetManager.kt` — Asset-aware UTXO handling, admin operations
- `android/app/src/main/java/io/raventag/app/worker/WalletPollingWorker.kt` — Background polling (to be extended for D-06)

### Wallet UI
- `android/app/src/main/java/io/raventag/app/ui/screens/WalletScreen.kt` — Balance display, tx history, send entry
- `android/app/src/main/java/io/raventag/app/ui/screens/SendRvnScreen.kt` — RVN send flow
- `android/app/src/main/java/io/raventag/app/ui/screens/ReceiveScreen.kt` — Receive address display (must always show currentIndex per D-18)
- `android/app/src/main/java/io/raventag/app/ui/screens/TransferScreen.kt` — Asset transfer flow
- `android/app/src/main/java/io/raventag/app/ui/screens/TransactionDetailsScreen.kt` — Individual tx view
- `android/app/src/main/java/io/raventag/app/ui/screens/MnemonicBackupScreen.kt` — Mnemonic export entry point (D-13, D-15)

### Network / Config
- `android/app/src/main/java/io/raventag/app/network/NetworkModule.kt` — OkHttp timeouts (D-10); has duplicate-timeout bug noted in CONCERNS.md to fix
- `android/app/src/main/java/io/raventag/app/config/AppConfig.kt` — Endpoints, node list (to be extended for D-09)

### Prior Phase Context
- `.planning/phases/20-android-performance-optimization/20-CONTEXT.md` — Parallel restore, retry policy, notification channel, confirmation dialog
- `.planning/phases/20-android-performance-optimization/20-01-SUMMARY.md` — Suspend function conversion
- `.planning/phases/20-android-performance-optimization/20-04-SUMMARY.md` — Parallel wallet restore
- `.planning/phases/10-android-security-hardening/10-01-SUMMARY.md` — Admin key migration (EncryptedSharedPreferences pattern applicable to wallet state cache)
- `.planning/phases/10-android-security-hardening/10-02-SUMMARY.md` — TOFU fingerprint persistence (SQLite pattern for D-11 quarantine)

### Project Context
- `.planning/PROJECT.md` — Current milestone focus, constraints, key decisions
- `.planning/ROADMAP.md` — Phase 30 goal and success criteria
- `.planning/codebase/CONCERNS.md` — Duplicate timeout in NetworkModule and ADMIN_KEY / TLS items relevant to wallet connections

### External Protocol References
- ElectrumX protocol spec (for `blockchain.scripthash.subscribe`, `blockchain.estimatefee`, `blockchain.scripthash.get_history`) — researcher to confirm current endpoint signatures for Ravencoin ElectrumX
- BIP39 spec — mnemonic checksum validation (D-14)
- BIP44 / Ravencoin coin type 175 — already validated in existing WalletManager

</canonical_refs>

<code_context>
## Existing Code Insights

### Reusable Assets
- `WalletManager.restoreWallet()` and `sendRvnLocal()` already exist as entry points for the consolidation pattern (D-17).
- `RavencoinPublicNode.getUtxos`, `getBalance`, `getUtxosAndAllAssetUtxosBatch` already batch-fetch UTXO state — adapt for D-03 reconciliation and D-04 caching.
- Phase 20 `retryWithBackoff` utility (5x exp backoff) directly applies to D-21 consolidation retries and D-25 rebroadcast.
- Phase 20 notification channel pattern (`transaction_progress`) is the model for the incoming-tx notification channel (D-07).
- EncryptedSharedPreferences pattern from Phase 10 admin-key migration applies to wallet state cache decisions (D-04).
- SQLite TOFU persistence pattern from Phase 10 directly applies to D-11 quarantine table and D-20 reserved_utxos table.

### Established Patterns
- `withContext(Dispatchers.IO)` for all blocking network/DB operations (Phase 20).
- `suspendCancellableCoroutine` for OkHttp bridging (Phase 20).
- Compose `AlertDialog` for confirmation dialogs (Phase 20 D-07).
- Android Keystore AES-GCM for mnemonic at rest (existing, Phase 10).

### Integration Points
- `WalletPollingWorker` — extend for D-06 background incoming-tx detection.
- `MainActivity.loadWalletBalance()` — primary refresh trigger (D-01).
- `ReceiveScreen` currentIndex binding — confirm against D-18.
- `RavencoinPublicNode` currently has no subscribe code path — D-05 requires a new persistent-socket subscription handler.

### Concerns to Address in Passing
- `NetworkModule.kt:82-84` has duplicate `connectTimeout`/`readTimeout` calls (CONCERNS.md). Fix while touching timeouts for D-10.

</code_context>

<specifics>
## Specific Ideas

- Quantum-resistance consolidation flow (D-17) must be verified against current `WalletManager` + `RavencoinTxBuilder` implementation early in research — the plan must be optimization, not redesign.
- `reserved_utxos` table schema: `(txid_in TEXT, vout INTEGER, tx_submitted_at INTEGER, PRIMARY KEY(txid_in, vout))`.
- Outgoing tx history row format example: `Sent 5 RVN · Cycled 244.9988 RVN · Fee 0.0012 RVN` (D-19).
- Connection status badge: small colored pill top-right of WalletScreen, colors: green `#10B981`, yellow `#F59E0B`, red `#EF4444`. Tap opens a small sheet with current node URL and last successful RPC timestamp.
- Background WorkManager periodic job: 15 min interval, constraints `NetworkType.CONNECTED`. No battery/charging constraint — user confirmed that D-06 should run broadly; D-26 handles the battery-saver throttling via foreground state.
- Consolidation failure "pending" flag persisted in SQLite (`wallet_state(key=pending_consolidation, value=txid)`), cleared on next successful consolidation.

</specifics>

<deferred>
## Deferred Ideas

- QR code mnemonic export (plain and passphrase-encrypted) — rejected from D-13 for v1, revisit in a later UX phase.
- Encrypted-file mnemonic backup with passphrase — rejected from D-13 for v1.
- User-configurable ElectrumX node list in SettingsScreen — rejected from D-09 for v1; possible future "power user" phase.
- RBF (Replace-By-Fee) for stuck sends — out of scope; D-25 auto-rebroadcast is the v1 mechanism.
- PSBT signing / hardware wallet support — out of scope for this milestone.
- Structured logging / log aggregation for wallet events — CONCERNS.md item, belongs to a future operational phase.
- Receive address rotation via BIP44 gap limit — rejected in favor of quantum-resistance model (D-18).
- Multi-device mnemonic sync / cloud backup — out of scope.

</deferred>

---

*Phase: 30-wallet-reliability*
*Context gathered: 2026-04-17*
