# Phase 30: Wallet Reliability - Research

**Researched:** 2026-04-18
**Domain:** Android Ravencoin HD wallet — balance sync, UTXO reconciliation, ElectrumX subscriptions, mnemonic safety, Keystore integrity
**Confidence:** MEDIUM-HIGH (ElectrumX protocol + Android Keystore = HIGH; Ravencoin-specific mobile patterns = MEDIUM; public node health in 2026 = LOW)

<user_constraints>
## User Constraints (from CONTEXT.md)

### Locked Decisions

**Balance & UTXO Sync**
- **D-01:** Sync triggers are both (a) on app foreground / WalletScreen resume and (b) periodic poll while WalletScreen is visible. No manual-only mode.
- **D-02:** Periodic poll interval is 30 seconds while WalletScreen is in foreground.
- **D-03:** On balance vs UTXO-sum mismatch, trust `sum(utxo.value)` as displayed spendable balance. Log the discrepancy (structured log, no user-visible error).
- **D-04:** Persist last-known wallet state (balance, UTXOs, recent tx history) in SQLite. On WalletScreen open, render cached state instantly with a "Last updated HH:MM" indicator, then refresh in background.

**Receive Detection**
- **D-05:** Primary detection is ElectrumX `blockchain.scripthash.subscribe` per wallet address while app is foreground. Subscription delivers near-instant mempool + confirmation notifications.
- **D-06:** Background detection runs via Android WorkManager periodic job every 15 minutes when app is closed. Fires system notification on new tx.
- **D-07:** On new incoming tx, user sees ALL of: in-app banner/snackbar, system notification, balance auto-update, and new entry in transaction history list with confirmation progress.
- **D-08:** A received transaction is considered final in UI at 6 confirmations. Until then show `N/6 confirmations` progress. Unconfirmed (mempool) shows as "Pending".

**Node Reliability & Failover**
- **D-09:** Hardcoded fallback list of ~3-5 known public ElectrumX nodes. Round-robin on connection/RPC failure. No user-configurable list in this phase.
- **D-10:** Per-node TLS timeouts: 10s connect / 20s RPC.
- **D-11:** TOFU fingerprint mismatch → quarantine node for 1 hour, then retry. If still mismatched, keep quarantined. Logged but not surfaced to user.
- **D-12:** Degraded-state UX: connection status badge (green / yellow / red), stale-balance indicator when fetch fails, Send/Receive disabled when ALL fallback nodes have failed.

**Mnemonic Export / Import**
- **D-13:** Export format for v1: 12/24-word phrase display with copy-to-clipboard only. QR and encrypted-file formats deferred.
- **D-14:** Import verification requires: BIP39 checksum validation, confirmation dialog naming current wallet's balance/assets, and a forced current-wallet backup step before import when current wallet has non-zero balance.
- **D-15:** Keystore integrity: detect `KeyPermanentlyInvalidatedException` on decrypt and route user to re-auth or mnemonic restore; require BiometricPrompt before revealing mnemonic words; store HMAC of seed alongside ciphertext and verify on load.
- **D-16:** Never cache decrypted mnemonic in memory after use. Re-decrypt from Android Keystore on every operation. Clear char arrays after use.

**Quantum-Resistance Consolidation**
- **D-17:** Preserve existing consolidation pattern (atomic send + sweep to `currentIndex+1`). This phase optimizes speed/reliability only — does NOT redesign.
- **D-18:** ReceiveScreen always displays `currentIndex` never-spent address. No per-receive rotation.
- **D-19:** Outgoing tx history displays three values: sent amount, cycled amount, fee.
- **D-20:** Reserved UTXOs locked in SQLite `reserved_utxos(txid_in, vout, tx_submitted_at)` on tx submit. Spendable balance = confirmed - reserved.
- **D-21:** Consolidation failure recovery: silent retry with Phase 20 backoff (5× exp). Persist pending-consolidation flag. Never block new sends on pending consolidation.

**Fee Estimation**
- **D-22:** Dynamic fee via ElectrumX `blockchain.estimatefee` (target ~6 blocks), editable in confirm dialog. Fallback to 0.01 RVN/kB static when unavailable.

**Transaction History**
- **D-23:** WalletScreen shows last 20 tx inline with "Load more". Paged via `blockchain.scripthash.get_history`. Cached in SQLite.

**Mempool & Stuck Tx**
- **D-24:** Unconfirmed incoming not counted as spendable (separate "Pending" line).
- **D-25:** Outgoing unconfirmed auto-rebroadcast after N minutes (suggested 30 min) to all fallback nodes. Silent.

**Power Save**
- **D-26:** `PowerManager.isPowerSaveMode()` true → pause 30s poll, keep scripthash subscription (push = minimal cost).
- **D-27:** Consolidation broadcasts regardless of power-save / data-saver.
- **D-28:** Reduced-sync-mode indicator on WalletScreen when power-save active.

### Claude's Discretion
- Exact SQLite schema for wallet state cache, reserved UTXOs, tx history tables
- Notification channel configuration for incoming-tx channel
- Exact WorkManager scheduling parameters
- Compose UI details for connection status badge, pending-balance line, tx history row
- Coroutine/flow architecture for subscription delivery → UI state updates
- Exact fallback ElectrumX node list
- Retry/backoff constants not fixed by Phase 20 D-02

### Deferred Ideas (OUT OF SCOPE)
- QR / encrypted-file mnemonic export
- User-configurable ElectrumX node list
- RBF (Replace-By-Fee)
- PSBT / hardware wallet support
- Structured logging / log aggregation
- BIP44 gap-limit receive rotation
- Multi-device mnemonic sync / cloud backup
</user_constraints>

<phase_requirements>
## Phase Requirements

| ID | Description | Research Support |
|----|-------------|------------------|
| WALLET-BAL | RVN balance matches ElectrumX state | D-01/D-02/D-03 polling + D-04 SQLite cache; `blockchain.scripthash.get_balance` with `asset=true` returns `{confirmed, unconfirmed}` per address |
| WALLET-SEND | Send RVN transactions broadcast successfully | Existing `sendRvnLocal` + D-22 fee estimation via `blockchain.estimatefee`, D-25 auto-rebroadcast |
| WALLET-RECV | Receive RVN detects incoming transactions | D-05 `blockchain.scripthash.subscribe` (foreground) + D-06 WorkManager 15 min (background) |
| WALLET-UTXO | UTXO set accurately reflects blockchain state | D-03 trust UTXO sum; D-04 cache; D-20 reserved_utxos; D-21 pending consolidation resilience |
| WALLET-MNEM | Mnemonic can be safely exported/imported | D-13 (export), D-14 (import gate), D-16 (no memory caching) |
| WALLET-KEYS | Keystore protected from extraction | D-15 `KeyPermanentlyInvalidatedException` + BiometricPrompt + HMAC; existing StrongBox + `setUnlockedDeviceRequired(true)` |
</phase_requirements>

## Summary

Phase 30 is NOT a greenfield wallet build — the existing codebase already has a working BIP44 HD wallet (`WalletManager.kt`, 2102 lines), a Ravencoin ElectrumX client (`RavencoinPublicNode.kt`, 1653 lines), a transaction builder with the full quantum-resistance consolidation pattern (`RavencoinTxBuilder.kt`, 1627 lines), Phase 10 TOFU SQLite persistence (`TofuFingerprintDao.kt`), and Phase 20 suspend-function conversion + `retryWithBackoff`. The phase is a reliability hardening pass: add ElectrumX scripthash subscription, persist wallet state cache, wire biometric gate for mnemonic reveal, handle Keystore invalidation, reserve UTXOs during pending consolidations, and display the three-value outgoing tx breakdown (D-19).

The Kotlin/Android Ravencoin ecosystem is thin. No stable Kotlin ElectrumX client library exists; the existing hand-rolled raw-socket TLS client in `RavencoinPublicNode.kt` is the standard approach. Vanilla Bitcoin mobile-wallet reliability patterns (BlueWallet style) apply directly: time-based + event-triggered polling, conservative confirmation depth (6 for Bitcoin; already chosen for this phase), and SQLite-backed state cache with `last_updated_at`. Ravencoin's 1-minute block time means "6 confirmations" = ~6 minutes (vs ~60 min for Bitcoin) — acceptable UX.

**Primary recommendation:** Treat the existing raw-socket `RavencoinPublicNode` as the mandatory client (no library migration). Add a second persistent socket per ElectrumX session for subscription push notifications (cannot share the request/response socket because subscription notifications arrive asynchronously and would interleave with RPC responses). Use Kotlin `Flow<ScripthashEvent>` to deliver events upward, re-emitted from a singleton `SubscriptionManager`. Cache wallet state in a dedicated SQLite DB (same pattern as Phase 10 TOFU). Keep D-17 consolidation semantics identical — this phase only speeds it up and adds D-20 reservation.

## Architectural Responsibility Map

| Capability | Primary Tier | Secondary Tier | Rationale |
|------------|-------------|----------------|-----------|
| Balance query + UTXO fetch | Android ElectrumX client (raw socket) | — | Already lives in `RavencoinPublicNode`; ElectrumX is the ground truth for Ravencoin chain state |
| Wallet state cache | Android SQLite (app-local) | — | D-04 persistence; no server component trustless design prohibits server-side wallet state |
| Scripthash subscription | Android long-lived TCP socket | — | Push notifications require a dedicated socket; backend is not involved |
| Mnemonic encryption at rest | Android Keystore + EncryptedSharedPreferences | — | Security-critical; already Phase 10 pattern |
| Biometric gate for reveal | Android `BiometricPrompt` + `CryptoObject` | Keystore | D-15; must bind biometric auth to the actual decrypt op, not a boolean flag |
| Fee estimation | Android ElectrumX client (`blockchain.estimatefee`) | Static fallback 0.01 RVN/kB | D-22; backend never proxies fee queries — trustless model |
| Background receive polling | Android WorkManager | ElectrumX client | D-06; system-managed periodic job, cannot depend on app lifecycle |
| Transaction broadcast | Android ElectrumX client (`blockchain.transaction.broadcast`) | Fallback to remaining nodes | Send flow already implements failover in `broadcast()` |
| Reserved UTXO tracking | Android SQLite | — | D-20 local bookkeeping; ElectrumX has no concept of reserved UTXOs |
| Connection status badge | Android UI state (Compose) | `SubscriptionManager` + `ElectrumHealthMonitor` | Derived state from client; not persisted |

## Standard Stack

### Core — already shipped, keep as-is

| Library | Version | Purpose | Why Standard |
|---------|---------|---------|--------------|
| BouncyCastle `bcprov-jdk15to18` | 1.77 | AES-CMAC, HMAC-SHA512 (BIP32), ECDSA (secp256k1), RIPEMD160 | [VERIFIED: STACK.md line 126] Already wired; no external BIP32/39/44 deps, mnemonic wordlist embedded |
| Android Keystore AES-GCM | API 26+ | Mnemonic encryption at rest, StrongBox when available | [VERIFIED: WalletManager.kt:254-289] Existing impl; includes `setUnlockedDeviceRequired(true)` on API 28+ |
| EncryptedSharedPreferences | 1.1.0-alpha06 | Secondary secret storage (admin key, flags) | [VERIFIED: Phase 10 pattern] Already used for AdminKeyStorage |
| SQLiteOpenHelper | Android platform | Persistent caches (TOFU, wallet state, reserved UTXOs, tx history) | [VERIFIED: TofuFingerprintDao.kt] Same pattern reused |
| kotlinx-coroutines-android | 1.7.3 | Suspend functions, `async`/`awaitAll`, `Flow` | [VERIFIED: Phase 20] `retryWithBackoff` + `withContext(Dispatchers.IO)` pattern established |
| WorkManager `work-runtime-ktx` | 2.9.1 | Background periodic receive detection (D-06) | [VERIFIED: WalletPollingWorker.kt] Already in place; extend for scripthash-status comparison |
| Gson | 2.10.1 | JSON-RPC request/response serialization | [VERIFIED: RavencoinPublicNode.kt:5-8] |
| androidx.biometric | 1.1.0 | `BiometricPrompt` + `CryptoObject` for D-15 | [VERIFIED: STACK.md line 142] Declared but not yet consumed in wallet flow |

### Supporting — new in Phase 30

| Library | Version | Purpose | When to Use |
|---------|---------|---------|-------------|
| `androidx.work:work-runtime-ktx` | 2.9.1 | Extend existing worker for scripthash-status comparison | D-06 background incoming detection |
| **No new libraries required** | — | — | All Phase 30 work uses the established stack |

### Alternatives Considered (and rejected)

| Instead of | Could Use | Why Rejected |
|------------|-----------|--------------|
| Hand-rolled raw-socket ElectrumX client | `bitcoin-kit-android` (Horizontal Systems) | [CITED: github.com/horizontalsystems/bitcoin-kit-android] No Ravencoin support; asset layer (OP_RVN_ASSET) not compatible with their script engine. Forking would be larger than maintaining the existing client. |
| Hand-rolled BIP32/39 | `bitcoinj` | [CITED: bitcoinj.org] BitcoinJ has no Ravencoin coin-type-175 support; pulling it in would duplicate what BouncyCastle already provides via `HMac`/`ECNamedCurveTable`. |
| Raw-socket subscription | OkHttp WebSocket | [VERIFIED: ElectrumX protocol uses newline-delimited JSON-RPC over raw TCP + TLS, not WebSocket] Wrong protocol. |
| SQLite cache | EncryptedSharedPreferences blob | [ASSUMED] SQLite is better for structured queries (last-N transactions, reserved UTXOs). Balance blob in prefs is fine for simple values, but D-20 + D-23 need queries. |
| Reorg detection via `blockchain.headers.subscribe` | Confirmation-threshold only | Confirmation-threshold (6 confs per D-08) is sufficient for a consumer wallet at Ravencoin's 1-minute block time. Active reorg detection via header subscription is unnecessary complexity for this phase. |

**Installation:** Nothing new to add. All libraries already declared in `android/gradle/libs.versions.toml`.

**Version verification note:**
- `androidx.work` 2.9.1 — confirmed in STACK.md (2026-04-13 baseline)
- `androidx.biometric` 1.1.0 — stable, released 2020, still the latest stable `1.x`. A `1.2.0-alpha05` exists but is alpha; keep 1.1.0 for stability. [ASSUMED: no breaking need for newer version]
- `androidx.security:security-crypto` 1.1.0-alpha06 — already in tree. MasterKey / EncryptedSharedPreferences API is stable despite alpha suffix.

## Architecture Patterns

### System Architecture Diagram

```
                   ┌───────────────────────────────────────────────────────┐
                   │                    WalletScreen (Compose)             │
                   │   ┌──────────┐  ┌─────────┐  ┌──────────┐  ┌───────┐  │
                   │   │ Balance  │  │ ConnPill│  │ TxList   │  │ Btns  │  │
                   │   └────▲─────┘  └────▲────┘  └────▲─────┘  └───▲───┘  │
                   └────────┼─────────────┼────────────┼────────────┼──────┘
                            │             │            │            │
                            └───── StateFlow<WalletUiState> ────────┘
                                           ▲
                                           │ collectAsState()
                            ┌──────────────┴───────────────────────────┐
                            │            WalletViewModel               │
                            │  - refresh()   (D-01, D-02)              │
                            │  - subscribe() (D-05)                    │
                            │  - send()      (D-17, D-21, D-25)        │
                            │  - reveal()    (D-15, biometric)         │
                            └─┬────┬──────────────┬────────────┬───────┘
                              │    │              │            │
                              ▼    │              ▼            ▼
                  ┌─────────────┐  │  ┌──────────────────┐  ┌──────────────┐
                  │ WalletState │  │  │ SubscriptionMgr  │  │ WalletMgr    │
                  │  Cache DAO  │  │  │ (persistent TLS  │  │ (existing,   │
                  │ (SQLite)    │  │  │  socket per      │  │  extended    │
                  │             │  │  │  scripthash)     │  │  with D-15)  │
                  └──────┬──────┘  │  └────────┬─────────┘  └──────┬───────┘
                         │         │           │                    │
                         │         ▼           ▼                    ▼
                         │   ┌──────────────────────────────────────┐
                         │   │        RavencoinPublicNode           │
                         │   │   (existing raw-socket TLS client,   │
                         │   │    extended with subscription &      │
                         │   │    estimatefee)                      │
                         │   └──────────────────┬───────────────────┘
                         │                      │
                         │                      ▼
                         │            ┌──────────────────┐
                         │            │  ElectrumX       │
                         │            │  node pool       │
                         │            │  (D-09 fallback) │
                         │            └──────────────────┘
                         │
                         └─► ReservedUtxoDao (SQLite, D-20)
                         └─► TxHistoryDao   (SQLite, D-23)
                         └─► PendingConsolidationStore (SQLite, D-21)

         Background path (app closed):
         ┌──────────────────┐      ┌──────────────────┐     ┌─────────────────┐
         │ WorkManager      │──15m─│WalletPollingWkr  │────►│RavencoinPublic  │
         │ (D-06)           │      │ (extend existing)│     │Node (one-shot)  │
         └──────────────────┘      └────────┬─────────┘     └─────────────────┘
                                            │
                                            └─► NotificationHelper (incoming_tx channel)
```

**Key data flow invariants:**
- SubscriptionManager owns the long-lived TLS socket. RavencoinPublicNode continues to own short-lived request/response sockets for RPC calls. These are SEPARATE sockets by design: ElectrumX subscription notifications arrive asynchronously on the subscription socket; interleaving them with one-shot RPC responses on the same socket requires a real framing layer this codebase does not have.
- WalletStateCache is the single source for displayed spendable balance. It is refreshed by (a) successful RPC fetch, (b) subscription event triggering a re-fetch. Never written from the subscription event directly — subscription only says "status changed," not "this is the new value." [CITED: electrumx.readthedocs.io/protocol-methods.html]
- `sum(utxo.value) - sum(reserved.value)` is the displayed spendable balance (D-03 + D-20).

### Recommended Project Structure

Additions to `android/app/src/main/java/io/raventag/app/`:

```
wallet/
├── WalletManager.kt               # existing, extend with biometric gate (D-15)
├── RavencoinPublicNode.kt         # existing, extend with:
│                                  #   - blockchain.estimatefee (D-22)
│                                  #   - subscription entry point (D-05)
├── RavencoinTxBuilder.kt          # existing, no changes (D-17 already implemented)
├── AssetManager.kt                # existing
├── cache/
│   ├── WalletCacheDao.kt          # NEW  D-04 state cache SQLite DAO
│   ├── ReservedUtxoDao.kt         # NEW  D-20 reserved UTXO table
│   ├── TxHistoryDao.kt            # NEW  D-23 paged tx history
│   └── PendingConsolidationDao.kt # NEW  D-21 pending-tx flag
├── subscription/
│   ├── SubscriptionManager.kt     # NEW  D-05 long-lived socket + Flow<ScripthashEvent>
│   └── ScripthashEvent.kt         # NEW  sealed class: StatusChanged, ConnectionLost, etc.
├── health/
│   ├── NodeHealthMonitor.kt       # NEW  D-11/D-12 quarantine & status pill
│   └── QuarantineDao.kt           # NEW  persists quarantine-until timestamps
├── fee/
│   └── FeeEstimator.kt            # NEW  D-22 wrapper around estimatefee + static fallback
security/
├── BiometricGate.kt               # NEW  D-15 BiometricPrompt + CryptoObject helper
├── MnemonicExporter.kt            # NEW  D-13/D-14/D-16 reveal/import flow (no memory caching)
worker/
├── WalletPollingWorker.kt         # existing, extend for D-06 scripthash-status comparison
├── RebroadcastWorker.kt           # NEW  D-25 stuck-tx auto-rebroadcast (one-shot, chained)
ui/
└── screens/
    ├── WalletScreen.kt            # extend: cached banner, connection pill (yellow), pending line, battery chip, restore dialog, three-value row
    ├── SendRvnScreen.kt           # extend: dynamic fee override row
    ├── MnemonicBackupScreen.kt    # extend: biometric cover card (D-15)
    └── TransactionDetailsScreen.kt# extend: three-value breakdown (D-19)
```

### Pattern 1: Persistent Scripthash Subscription Socket

**What:** A dedicated, long-lived TLS socket per ElectrumX session. After the `server.version` handshake and N subscribe calls, the socket stays open. Incoming lines from the server are parsed into either request/response responses or `blockchain.scripthash.subscribe` notifications, and the notifications are emitted into a Kotlin `Flow`.

**When to use:** Exactly once per foreground WalletScreen session (D-05). Torn down on screen-leave or app-background.

**Why a separate socket:** ElectrumX subscription notifications are pushed asynchronously (no request ID). If they arrive while a one-shot RPC is in-flight on the same socket, the current client (synchronous `reader.readLine()`) would receive the wrong line. Separate sockets avoid this entire class of bug.

**Example (reference pattern to port, not copy verbatim):**
```kotlin
// Source: ElectrumX protocol docs (https://electrumx.readthedocs.io/en/latest/protocol-methods.html)
// pattern adapted to existing RavencoinPublicNode.TofuTrustManager
class SubscriptionManager(private val context: Context) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var session: Session? = null
    private val events = MutableSharedFlow<ScripthashEvent>(extraBufferCapacity = 64)

    fun eventsFlow(): SharedFlow<ScripthashEvent> = events.asSharedFlow()

    suspend fun start(addresses: List<String>) = withContext(Dispatchers.IO) {
        stop()
        for (server in SERVERS) {
            try {
                session = openSession(server)
                // handshake + one subscribe call per address
                for (addr in addresses) {
                    session!!.subscribe(scriptHashOf(addr))
                }
                // launch reader loop
                scope.launch { session!!.readLoop(events) }
                return@withContext
            } catch (_: Exception) { /* try next server */ }
        }
        events.emit(ScripthashEvent.AllNodesDown)
    }

    suspend fun stop() { session?.close(); session = null }
}

sealed class ScripthashEvent {
    data class StatusChanged(val scripthash: String, val newStatus: String?) : ScripthashEvent()
    data object ConnectionLost : ScripthashEvent()
    data object AllNodesDown : ScripthashEvent()
}
```

Keep the `TofuTrustManager` from `RavencoinPublicNode` — same TOFU rules apply to the subscription socket.

### Pattern 2: BiometricPrompt Bound to the Decrypt Operation (D-15)

**What:** `BiometricPrompt.authenticate(promptInfo, CryptoObject(cipher))` where `cipher` is `Cipher.DECRYPT_MODE`-initialized with the Keystore AES-GCM key and the stored IV. Only after successful auth does the OS return a usable `CryptoObject` from which `doFinal(ciphertext)` returns the plaintext mnemonic.

**When to use:** D-15 mnemonic reveal in MnemonicBackupScreen. NOT for ordinary send operations (those just use the existing `getMnemonic()` which reads Keystore via `setUnlockedDeviceRequired(true)`).

**Why CryptoObject (not just a boolean gate):** A boolean "user authenticated" flag can be tampered with by a rooted device or a modified APK. CryptoObject binds the auth to the actual decrypt: no auth, no plaintext. [CITED: developer.android.com/training/sign-in/biometric-auth]

**Example pattern (to be written for the phase):**
```kotlin
// Source: https://medium.com/androiddevelopers/using-biometricprompt-with-cryptoobject-how-and-why-aace500ccdb7
fun revealMnemonic(activity: FragmentActivity, onResult: (Result<String>) -> Unit) {
    val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
        try {
            init(Cipher.DECRYPT_MODE, getOrCreateAndroidKey(), GCMParameterSpec(128, loadIv()))
        } catch (e: KeyPermanentlyInvalidatedException) {
            onResult(Result.failure(KeyInvalidatedException())) // route user to restore (D-15)
            return
        }
    }
    val prompt = BiometricPrompt(activity, ContextCompat.getMainExecutor(activity),
        object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                val plaintext = result.cryptoObject!!.cipher!!.doFinal(loadCiphertext())
                onResult(Result.success(String(plaintext, Charsets.UTF_8)))
                // caller MUST overwrite `plaintext` with zeros after display (D-16)
            }
            override fun onAuthenticationError(code: Int, msg: CharSequence) {
                onResult(Result.failure(BiometricCancelledException()))
            }
        })
    prompt.authenticate(
        BiometricPrompt.PromptInfo.Builder()
            .setTitle("Authenticate")
            .setSubtitle("Reveal recovery phrase")
            .setAllowedAuthenticators(BIOMETRIC_STRONG or DEVICE_CREDENTIAL)
            .build(),
        BiometricPrompt.CryptoObject(cipher)
    )
}
```

### Pattern 3: Wallet State Cache (D-04)

**What:** Single-row SQLite table keyed by wallet ID (the root xpub's hash160 is fine, or just `"default"` since this is a single-wallet app). Columns: serialized balance, UTXO JSON, last-refreshed timestamp. Tx history lives in a separate paginated table (D-23).

**Why SQLite over EncryptedSharedPreferences:** Balance is not secret (derivable from any address). UTXO JSON can grow. Tx history needs pagination. Reserved UTXOs need compound-key queries. SharedPrefs serializes entire file on every write — unacceptable for 500-row tx tables.

**Schema (recommended, Claude's discretion per CONTEXT.md):**
```sql
-- 1) wallet_state_cache: single row for fast "open WalletScreen" render
CREATE TABLE wallet_state_cache (
    wallet_id        TEXT PRIMARY KEY,
    balance_sat      INTEGER NOT NULL,     -- sum(utxo.value) - sum(reserved.value) at write time
    utxos_json       TEXT NOT NULL,        -- JSON array of Utxo
    asset_utxos_json TEXT NOT NULL,        -- JSON map {asset_name -> [AssetUtxo]}
    block_height     INTEGER NOT NULL,     -- tip height at time of cache write
    last_refreshed_at INTEGER NOT NULL     -- unix millis
);

-- 2) tx_history: paginated per D-23
CREATE TABLE tx_history (
    txid        TEXT PRIMARY KEY,
    height      INTEGER NOT NULL,          -- 0 = mempool
    confirms    INTEGER NOT NULL,
    amount_sat  INTEGER NOT NULL,          -- positive = incoming
    sent_sat    INTEGER NOT NULL,          -- positive = amount sent externally (D-19 "sent")
    cycled_sat  INTEGER NOT NULL,          -- positive = amount consolidated to currentIndex+1 (D-19 "cycled")
    fee_sat     INTEGER NOT NULL,          -- D-19 "fee"
    is_incoming INTEGER NOT NULL,          -- 0/1
    is_self     INTEGER NOT NULL,          -- consolidation self-transfer
    timestamp   INTEGER NOT NULL,          -- block header unix seconds
    cached_at   INTEGER NOT NULL
);
CREATE INDEX idx_tx_history_height ON tx_history(height DESC);

-- 3) reserved_utxos: D-20
CREATE TABLE reserved_utxos (
    txid_in          TEXT NOT NULL,         -- input txid
    vout             INTEGER NOT NULL,
    tx_submitted_at  INTEGER NOT NULL,      -- used by D-25 auto-rebroadcast timer
    submitted_txid   TEXT NOT NULL,         -- the consolidation tx we submitted
    PRIMARY KEY(txid_in, vout)
);
CREATE INDEX idx_reserved_submitted_txid ON reserved_utxos(submitted_txid);

-- 4) pending_consolidations: D-21 recovery flag
CREATE TABLE pending_consolidations (
    submitted_txid  TEXT PRIMARY KEY,
    submitted_at    INTEGER NOT NULL,
    last_retry_at   INTEGER,
    retry_count     INTEGER NOT NULL DEFAULT 0,
    last_error      TEXT
);

-- 5) quarantined_nodes: D-11
CREATE TABLE quarantined_nodes (
    host            TEXT PRIMARY KEY,
    quarantined_until INTEGER NOT NULL,    -- unix millis, node skipped while now < this
    reason          TEXT NOT NULL          -- TOFU_MISMATCH | RPC_FAILED | TIMEOUT
);
```

### Pattern 4: Confirmation Depth as the Reorg Defense (D-08)

**What:** Do NOT implement active reorg detection on mobile. Use `confirmations >= 6` as the "final" threshold and re-fetch tx confirmations on every WalletScreen open. If a reorg invalidates a tx, ElectrumX will return the updated status (or drop it from the history response) and the local cache will be overwritten on next refresh.

**When to use:** Always for a consumer wallet at this scale.

**Why:** [VERIFIED via bitcoin.stackexchange.com/questions/114985] 6 confirmations is the industry-standard threshold where reorg probability is negligible (<0.00001% under 30% attacker hashrate). Active reorg detection via `blockchain.headers.subscribe` is justified for exchanges and hot wallets, not consumer wallets. Complexity cost > benefit.

**Ravencoin-specific note:** 1-minute block time means 6 confirmations = ~6 minutes. At 1% difficulty disruption (KAWPOW is ASIC-resistant but not attack-proof), 6 confirmations is still comfortably safe.

### Pattern 5: Reserved UTXO Bookkeeping (D-20)

**What:** When `sendRvnLocal` broadcasts a tx, INSERT each input `(txid_in, vout)` into `reserved_utxos`. On subsequent UTXO fetches, subtract reserved rows from the UTXO set before computing spendable balance. On tx confirmation (via subscription event or next poll that finds the tx confirmed in history), DELETE the matching rows.

**Why needed:** ElectrumX returns the raw chain UTXO set. Between submitting a consolidation tx and its first confirmation, the old UTXOs still appear as "unspent" but will become invalid the moment the consolidation confirms. Without reservation, the user could try to send twice — and while ElectrumX would reject the second broadcast (double-spend), the UI would show a confusing "insufficient funds" error.

**Cleanup trigger:** `ReservedUtxoDao.deleteForTxid(submittedTxid)` on observing the submitted tx in the confirmed history OR on detecting it was dropped from mempool (D-25).

### Anti-Patterns to Avoid

- **Subscribing on the request socket.** Do not call `blockchain.scripthash.subscribe` on the one-shot RPC socket used by `RavencoinPublicNode.call()`. Subscription notifications are push-based and will interleave with synchronous response reads. Use a separate socket.
- **Caching decrypted mnemonic in memory (violates D-16).** Even for the duration of one send, decrypt, use, zero-fill. Do not stash it in a ViewModel property "for convenience."
- **Trusting `blockchain.scripthash.subscribe` status hash as wallet state.** The status hash is a fingerprint (SHA-256 of a concatenated history string). It signals "something changed" — not what. Always re-fetch balance/utxo/history after a status-change notification.
- **Using a single blocking polling loop that also handles subscriptions.** The 30s poll (D-02) and subscription events (D-05) are orthogonal. Poll drives catch-up; subscription drives real-time. Collapsing them causes missed events during slow polls.
- **Broadcasting stuck-tx rebroadcasts without a backoff ceiling.** D-25 auto-rebroadcast must cap retries (recommended: 5 rebroadcasts total over 24h, exponential intervals 30m/1h/2h/4h/8h). Unbounded rebroadcast is a node DoS and won't help if the tx is actually double-spent.
- **Persisting the Keystore SecretKey.** The AES-GCM key is generated in the Keystore and never leaves it. Only the ciphertext and IV are persisted. Regenerating the key means the old ciphertext is permanently unreadable — which is actually what D-15 wants on `KeyPermanentlyInvalidatedException`.
- **Treating `blockchain.relayfee` as a fee estimate.** Relayfee is the minimum to enter mempool, not a confirmation-target estimate. Phase 20's existing `getMinRelayFeeRateSatPerByte` applies a 2× safety margin but is still a floor, not a target. D-22 explicitly uses `blockchain.estimatefee(6)` for normal sends; relayfee is only a fallback floor.

## Don't Hand-Roll

| Problem | Don't Build | Use Instead | Why |
|---------|-------------|-------------|-----|
| AES-GCM encryption for mnemonic | Custom `javax.crypto.Cipher` wrapper | Existing `WalletManager.encrypt()/decrypt()` with Android Keystore key | Already implemented with StrongBox fallback and `setUnlockedDeviceRequired(true)`. |
| BIP39 wordlist | Custom wordlist | Embedded `WORD_LIST` in WalletManager.kt (English BIP39) | Already 2048-word list in place. |
| BIP32 HMAC-SHA512 derivation | Custom derivation | Existing `derivePrivateKey()` using BouncyCastle `HMac` + `SHA512Digest` | Already implemented and unit-tested indirectly via `RavencoinTxBuilderTest`. |
| Base58Check encode/decode | Custom implementation | Existing `base58Encode`/`base58Decode` in WalletManager + RavencoinPublicNode | Already implemented. Note: 2 copies exist (one in each file) — consolidate in passing. |
| ECDSA signature (secp256k1) | Custom ECDSA loop | Existing `signEcdsa()` in RavencoinTxBuilder via BouncyCastle `ECNamedCurveTable` | Already implemented with DER encoding and low-s normalization. |
| Coin selection algorithm | Custom Knapsack/BnB | **Hand-rolled greedy (existing)** — documented exception | [ASSUMED] The existing `sendRvnLocal` uses all available RVN UTXOs + old-address sweep because the D-17 quantum-resistance model requires consolidating to a single fresh address per tx. This is incompatible with Bitcoin Core's Branch-and-Bound (which optimizes for "leave small change"). The existing "use everything and cycle" is correct for this wallet model. Do NOT introduce a standard coin selector in this phase. |
| Subscription framing / reconnect | Custom socket manager | Standard "reader coroutine per socket" pattern (see Pattern 1) | The ElectrumX protocol is newline-delimited JSON — simple enough to hand-roll, and no library provides a Kotlin/Android client for Ravencoin ElectrumX. |
| Tx rebroadcast scheduler | Custom `ScheduledExecutorService` | WorkManager `OneTimeWorkRequest` with `setInitialDelay` | WorkManager survives process death, respects Doze, and already in the dependency tree. |
| Background periodic polling | AlarmManager + BroadcastReceiver | WorkManager `PeriodicWorkRequest` (already implemented in WalletPollingWorker) | Existing pattern. 15-min minimum is the system floor for periodic work. [CITED: developer.android.com/reference/kotlin/androidx/work/PeriodicWorkRequest] |
| BIP39 seed → HMAC integrity tag | Custom HMAC wrapper | BouncyCastle `HMac(SHA256Digest())` with a second Keystore-wrapped key | Standard "encrypt-then-MAC"; D-15 requires HMAC of seed alongside ciphertext. |
| Fee estimation target logic | Custom ratio math | `blockchain.estimatefee(6)` with fallback to `blockchain.relayfee` × 2 | ElectrumX already implements Bitcoin-Core-style fee estimation; we just consume it. |
| Ravencoin asset transfer tx bytes | Custom script builder | Existing `RavencoinTxBuilder.buildAndSignMultiAssetTransfer` + `buildAndSignMultiAddressSend` | 1627 lines of tested tx-building logic. Do not touch in this phase. |

**Key insight:** The dangerous temptations in this phase are (a) "let me just add a second PeriodicWorkRequest with 5-min interval" — it won't run, the OS enforces 15-min minimum, and (b) "let me cache the mnemonic briefly in memory to avoid re-auth" — violates D-16 and the StrongBox threat model. Both are hard-rules from the OS and the user decisions, not preferences.

## Runtime State Inventory

> This is not a rename/refactor phase, but Phase 30 DOES introduce new persistent state (SQLite tables, notification channel, WorkManager jobs) that must be considered. Including this section for completeness.

| Category | Items Found | Action Required |
|----------|-------------|------------------|
| Stored data | NEW: `wallet_state_cache`, `tx_history`, `reserved_utxos`, `pending_consolidations`, `quarantined_nodes` in a new `wallet_reliability.db`. Existing: `electrum_certificates.db` (TOFU from Phase 10), SharedPrefs `wallet_poll` (Phase 20 poll baseline), SharedPrefs `raventag_wallet` (mnemonic ciphertext). | CREATE TABLE IF NOT EXISTS on first open. No migration needed — all new. |
| Live service config | Phase 10 added `electrum_certificates.db` with pinned TOFU fingerprints. Phase 30 adds new tables, no migration. Existing Keystore alias `raventag_wallet_key` is unchanged. | None |
| OS-registered state | **NEW notification channel `incoming_tx`** (D-07), separate from existing `transaction_progress` (Phase 20). **NEW WorkManager work `raventag_rebroadcast`** (D-25, OneTime) and **extended `wallet_polling`** (D-06, existing periodic). | Channel must be created in Application.onCreate (API 26+ requirement). WorkManager name strings must avoid collision with Phase 20's `wallet_polling_worker`. |
| Secrets/env vars | None changed. Existing: `raventag_wallet.seed_enc`, `raventag_wallet.mnemonic_enc` in SharedPrefs. D-15 adds HMAC column but under the same SharedPrefs file; no new secret. | None — add new SharedPrefs keys `KEY_SEED_HMAC`, `KEY_MNEMONIC_HMAC` (still ciphertext, still Keystore-wrapped). |
| Build artifacts | None. All new code is pure Kotlin; no build-time artifacts (no proto files, no generated DB). | None |

## Common Pitfalls

### Pitfall 1: Scripthash Subscription Status Arrives BEFORE Subscribe Response

**What goes wrong:** You call `blockchain.scripthash.subscribe` expecting a single RPC response, but ElectrumX may emit a notification on that same scripthash asynchronously before your response. The reader consumes the notification first and your `subscribe()` await times out.

**Why it happens:** The protocol is inherently async on a shared socket. [CITED: electrumx.readthedocs.io/protocol-basics.html] The server doesn't distinguish outbound notifications from inbound responses — both are JSON objects on the same socket.

**How to avoid:** Match responses by `id` (the integer you sent in the request), not by arrival order. Notifications do NOT have `id`; responses always do. Route `{id: ...}` lines to a response-table and `{method: "blockchain.scripthash.subscribe", params: [hash, status]}` lines to the event flow.

**Warning signs:** Subscribe "timing out" sporadically; unit tests that pass but integration is flaky; `reader.readLine()` returning a notification when you expected a response.

### Pitfall 2: TCP Connection Silently Dies on Mobile Networks

**What goes wrong:** App foregrounded, subscription socket "open," but handset switched from WiFi to LTE 20 minutes ago. The socket is a zombie — writes fail, reads block forever. User sees "connected" but never receives push events.

**Why it happens:** Mobile network transitions don't always send FIN; the old socket becomes undeliverable. Without TCP keepalive or app-level ping, the client doesn't know. [CITED: github.com/square/okhttp/issues/3042]

**How to avoid:** (a) Set `sslSocket.keepAlive = true` AND (b) send a periodic `server.ping` (returns null) every 60s from the subscription coroutine. Reconnect on ping failure. (c) Consider registering a `ConnectivityManager.NetworkCallback` to proactively reset the subscription socket when the network changes.

**Warning signs:** User reports "app says connected but doesn't see incoming tx" after leaving it open an hour.

### Pitfall 3: `KeyPermanentlyInvalidatedException` Disguised as a Generic Crypto Error

**What goes wrong:** Catching a broad `Exception` around `cipher.doFinal()` hides that the Keystore key was invalidated by biometric enrollment change. User sees a generic "failed to unlock wallet" message and assumes the app is broken.

**Why it happens:** `KeyPermanentlyInvalidatedException` extends `InvalidKeyException`; a catch-all `Exception` handler absorbs it with no recovery path. [CITED: developer.android.com/reference/android/security/keystore/KeyPermanentlyInvalidatedException]

**How to avoid:** Catch `KeyPermanentlyInvalidatedException` specifically. Route the user to the "Device security changed" dialog (D-15 copywriting contract, UI-SPEC.md line 186) with a single action: restore from recovery phrase. Do NOT silently regenerate the key — the existing ciphertext becomes garbage and funds are irrecoverable without the phrase.

**Warning signs:** User enrolls a new fingerprint, opens the app, gets "something went wrong" with no path forward.

### Pitfall 4: Stale Mempool Cache Masks a Successful Broadcast

**What goes wrong:** User sends RVN, broadcast returns a txid, but the app's UI still shows the old balance for 2 minutes until the next 30s poll catches up. User thinks the send failed, retries, and now has two pending consolidations.

**Why it happens:** The broadcast path writes the raw tx to the network but does not update the local cache. The subscription socket will eventually notify, but "eventually" is server-dependent.

**How to avoid:** After successful broadcast, synchronously: (a) INSERT into `reserved_utxos` (D-20), (b) add an optimistic row to `tx_history` with height=0, (c) emit a state update so WalletScreen reflects the pending tx immediately. On next poll/subscription event, reconcile.

**Warning signs:** User reports "I had to refresh 3 times before my transaction showed up."

### Pitfall 5: Batched `getUtxosAndAllAssetUtxosBatch` Misses Asset UTXOs After Consolidation

**What goes wrong:** Existing `getUtxosAndAllAssetUtxosBatch` filters asset UTXOs by `getAllAssetOutpoints`. If the set is stale (cached too long), a recent consolidation's new asset outputs are missed.

**Why it happens:** [VERIFIED: RavencoinPublicNode.kt:658-740] The batch fetches RVN UTXOs, asset UTXOs, and the outpoint set from ElectrumX in parallel. If the outpoint set query fails, it falls back to empty — causing asset UTXOs to look like plain RVN UTXOs and vice versa.

**How to avoid:** On any failure of the batch, invalidate the cache and force a full refetch on the next cycle. Do NOT display cached state after a batch failure — that's precisely the "stale indicator" path from D-12.

### Pitfall 6: Reserved UTXO Leak on Crash

**What goes wrong:** App submits a consolidation, INSERTs into `reserved_utxos`, then crashes before the SQLite WAL syncs. Reserved row persists, user thinks balance is permanently reduced.

**Why it happens:** SQLite WAL is async-durable by default; a crash mid-write can leave partial state.

**How to avoid:** (a) Open `reserved_utxos.db` with `PRAGMA synchronous=FULL` AND `PRAGMA journal_mode=WAL` — durability without too much perf hit. (b) On app startup, prune rows older than 48h — no consolidation takes longer than that to confirm. (c) Always reconcile: on WalletScreen open, fetch current mempool+confirmed history for the submitted txid; if found, delete the reservation.

### Pitfall 7: BIP39 Checksum Validator Accepts Trailing Whitespace

**What goes wrong:** User pastes a mnemonic from a password manager with a trailing newline. The existing `validateMnemonic()` splits on space, producing a blank 13th word. Some implementations silently drop blanks and accept the input — with a wrong derivation key.

**Why it happens:** BIP39 is strict about word count (12/15/18/21/24) and checksum, but not every implementation normalizes input.

**How to avoid:** `input.trim().split(Regex("\\s+"))` — collapse any whitespace. Reject anything not in {12, 15, 18, 21, 24}. Run the checksum on the normalized list. The existing `validateMnemonic()` at line 818 should be audited for this.

### Pitfall 8: TLS Cert Rotation Breaks All Fallbacks Simultaneously

**What goes wrong:** The admin of a public ElectrumX node rotates their TLS cert. Every app user hits a TOFU mismatch at once. Per D-11 they all quarantine for 1 hour, but if other nodes are also down, users see "Offline" and cannot send.

**Why it happens:** TOFU security model is intentional: cert rotation IS a protocol-level notification that something changed. The app cannot distinguish rotation from MITM.

**How to avoid:** (a) Hardcode enough fallbacks that a single rotation leaves others working (the current 3-node list from `RavencoinPublicNode.kt:172-177` is marginal; adding 2 more from `rvn4lyfe.com` server.json is recommended). (b) Surface the quarantine state in the connection-pill bottom-sheet (UI-SPEC §Connection pill) so power users can debug. (c) Document in release notes: "If multiple nodes are quarantined, clear TOFU pins in Settings → Advanced." (Deferred for a future phase; in Phase 30, quarantine is silent per D-11.)

## Code Examples

### Example 1: Persistent subscription reader with id-matched responses

```kotlin
// Source: ElectrumX protocol docs (https://electrumx.readthedocs.io/en/latest/protocol-basics.html)
private class SubscriptionSession(
    val host: String,
    val socket: SSLSocket,
) {
    private val writer = PrintWriter(socket.outputStream, true)
    private val reader = BufferedReader(InputStreamReader(socket.inputStream))
    private val pending = ConcurrentHashMap<Int, CompletableDeferred<JsonElement>>()

    suspend fun readLoop(events: MutableSharedFlow<ScripthashEvent>) {
        while (coroutineContext.isActive) {
            val line = withContext(Dispatchers.IO) { reader.readLine() }
                ?: throw IOException("subscription socket closed by $host")
            val obj = JsonParser.parseString(line).asJsonObject
            if (obj.has("id")) {
                val id = obj.get("id").asInt
                pending.remove(id)?.complete(obj.get("result") ?: JsonNull.INSTANCE)
            } else {
                // Server-sent notification
                val method = obj.get("method").asString
                val params = obj.getAsJsonArray("params")
                when (method) {
                    "blockchain.scripthash.subscribe" -> {
                        val scripthash = params.get(0).asString
                        val status = params.get(1).takeUnless { it.isJsonNull }?.asString
                        events.emit(ScripthashEvent.StatusChanged(scripthash, status))
                    }
                    // blockchain.headers.subscribe, if we add it later
                }
            }
        }
    }

    suspend fun subscribe(scripthash: String): String? {
        val id = idCounter.getAndIncrement()
        val deferred = CompletableDeferred<JsonElement>()
        pending[id] = deferred
        writer.println(gson.toJson(mapOf(
            "id" to id, "method" to "blockchain.scripthash.subscribe",
            "params" to listOf(scripthash)
        )))
        val result = withTimeout(20_000) { deferred.await() }
        return result.takeUnless { it.isJsonNull }?.asString
    }
}
```

### Example 2: Wallet state cache write with reservation-aware balance

```kotlin
// New: cache/WalletCacheDao.kt
object WalletCacheDao {
    fun writeState(
        db: SQLiteDatabase,
        utxos: List<Utxo>,
        assetUtxos: Map<String, List<AssetUtxo>>,
        blockHeight: Int
    ) {
        val reservedSat = db.rawQuery(
            "SELECT COALESCE(SUM(r.value), 0) FROM reserved_utxos r WHERE NOT EXISTS (" +
            " SELECT 1 FROM tx_history h WHERE h.txid = r.submitted_txid AND h.confirms > 0)",
            null
        ).use { if (it.moveToFirst()) it.getLong(0) else 0L }

        val confirmedSat = utxos.sumOf { it.satoshis }
        val displaySat = (confirmedSat - reservedSat).coerceAtLeast(0)

        db.insertWithOnConflict("wallet_state_cache", null, ContentValues().apply {
            put("wallet_id", "default")
            put("balance_sat", displaySat)
            put("utxos_json", gson.toJson(utxos))
            put("asset_utxos_json", gson.toJson(assetUtxos))
            put("block_height", blockHeight)
            put("last_refreshed_at", System.currentTimeMillis())
        }, SQLiteDatabase.CONFLICT_REPLACE)
    }
}
```

### Example 3: Biometric-gated mnemonic reveal

```kotlin
// New: security/BiometricGate.kt
// Source: https://developer.android.com/training/sign-in/biometric-auth
//         + https://medium.com/androiddevelopers/using-biometricprompt-with-cryptoobject-how-and-why-aace500ccdb7
class BiometricGate(private val activity: FragmentActivity) {
    suspend fun decryptWithBiometric(
        cipher: Cipher,
        ciphertext: ByteArray,
        titleRes: Int,
        subtitleRes: Int,
    ): ByteArray = suspendCancellableCoroutine { cont ->
        val prompt = BiometricPrompt(
            activity,
            ContextCompat.getMainExecutor(activity),
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    try {
                        val c = result.cryptoObject?.cipher
                            ?: return cont.resumeWithException(IllegalStateException("no cipher"))
                        cont.resume(c.doFinal(ciphertext))
                    } catch (e: Exception) { cont.resumeWithException(e) }
                }
                override fun onAuthenticationError(code: Int, msg: CharSequence) {
                    cont.resumeWithException(BiometricCancelledException(code, msg.toString()))
                }
            })
        prompt.authenticate(
            BiometricPrompt.PromptInfo.Builder()
                .setTitle(activity.getString(titleRes))
                .setSubtitle(activity.getString(subtitleRes))
                .setAllowedAuthenticators(
                    BiometricManager.Authenticators.BIOMETRIC_STRONG
                        or BiometricManager.Authenticators.DEVICE_CREDENTIAL
                )
                .build(),
            BiometricPrompt.CryptoObject(cipher)
        )
        cont.invokeOnCancellation { prompt.cancelAuthentication() }
    }
}
```

### Example 4: Rebroadcast worker for stuck outgoing tx (D-25)

```kotlin
// New: worker/RebroadcastWorker.kt
class RebroadcastWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val txid = inputData.getString("txid") ?: return@withContext Result.failure()
        val rawHex = inputData.getString("raw_hex") ?: return@withContext Result.failure()
        val attempt = inputData.getInt("attempt", 0)
        if (attempt >= 5) return@withContext Result.success() // D-25 cap

        val node = RavencoinPublicNode(applicationContext)
        // Check if already confirmed; if so, stop
        try {
            val confirms = node.getTransactionHistory(/* address derived from reserved_utxos */, 1, 0)
                .firstOrNull { it.txid == txid }?.confirmations ?: 0
            if (confirms > 0) return@withContext Result.success()
        } catch (_: Exception) { /* fall through to rebroadcast */ }

        try {
            node.broadcast(rawHex)
        } catch (_: Exception) { /* silent per D-25 */ }

        // Schedule next attempt with exp backoff
        val nextDelayMinutes = listOf(30L, 60L, 120L, 240L, 480L).getOrElse(attempt) { 480L }
        val next = OneTimeWorkRequestBuilder<RebroadcastWorker>()
            .setInitialDelay(nextDelayMinutes, TimeUnit.MINUTES)
            .setInputData(workDataOf(
                "txid" to txid, "raw_hex" to rawHex, "attempt" to attempt + 1
            ))
            .build()
        WorkManager.getInstance(applicationContext)
            .enqueueUniqueWork("rebroadcast-$txid", ExistingWorkPolicy.REPLACE, next)
        Result.success()
    }
}
```

## State of the Art

| Old Approach | Current Approach | When Changed | Impact |
|--------------|------------------|--------------|--------|
| `blockchain.address.get_balance` | `blockchain.scripthash.get_balance` | ElectrumX 1.4 (~2018) | Existing code uses scripthash (correct). [VERIFIED: RavencoinPublicNode.kt:247] |
| Polling-only wallets | Scripthash subscription + poll catch-up | Electrum v3+ | Phase 30 D-05 aligns with modern approach. |
| Single hardcoded node | Public-node pool + TOFU | ElectrumX 2015-2020 | Existing TOFU implementation (Phase 10 L2 SQLite) is current best practice. |
| `Cipher`-protected keys without biometric binding | `BiometricPrompt.CryptoObject` binding auth to the decrypt op | Android 9 (BiometricPrompt) — default pattern by Android 11 | D-15 updates to current pattern. |
| `JobScheduler` for background polling | WorkManager | AndroidX WorkManager 1.0 (2019) | Existing `WalletPollingWorker` uses WorkManager (current). |
| In-memory TOFU cache | SQLite-persisted TOFU | Best practice since ~2020 | Phase 10 already implemented (L1+L2). |

**Deprecated/outdated:**
- `blockchain.address.*` RPC family — removed in ElectrumX 1.3+. Use scripthash-based methods. Existing code is correct.
- `setUserAuthenticationValidityDurationSeconds(N)` on Keystore spec — superseded by `setUserAuthenticationParameters()` on API 30+, but the duration-based API still works on API 26-29. For D-15 phase minimum (API 26), continue with the existing key spec without adding time-bounded auth. Biometric is invoked explicitly per reveal, not via timeout.
- `FingerprintManager` API — deprecated in API 28 in favor of `BiometricPrompt`. Existing code does not use `FingerprintManager`. Good.

## Assumptions Log

| # | Claim | Section | Risk if Wrong |
|---|-------|---------|---------------|
| A1 | `blockchain.estimatefee` returns RVN/kB float, not sat/vB | Fee Estimation / Pattern 5 | If the unit is different, fees will be off by ~100×. Mitigation: sanity-check the returned value against `blockchain.relayfee` — if estimatefee < relayfee, fall back to relayfee × 6 (target-6-block heuristic). |
| A2 | Ravencoin ElectrumX servers implement the subscription protocol identically to upstream kyuupichan ElectrumX | Architecture Pattern 1 | If asset-aware scripthash status computes differently, we may miss asset-transfer-only notifications. Mitigation: on any status change, refetch both RVN and asset UTXOs. |
| A3 | Public ElectrumX nodes (`rvn4lyfe.com`, `rvn-dashboard.com`, `162.19.153.65`, `51.222.139.25`) are still reachable in 2026 | Standard Stack / Node list | If all 4 are down, the wallet is unusable. Mitigation: add 1-2 more from community Discord or rvn4lyfe.com at plan time; also surface node status to UI (already D-12). |
| A4 | Android Keystore AES-GCM key created with `setInvalidatedByBiometricEnrollment` default behavior matches user expectations for D-15 | Pattern 2 | Existing `getOrCreateAndroidKey()` does NOT set `setInvalidatedByBiometricEnrollment` explicitly. Default is `true` when `setUserAuthenticationRequired(true)` is set, otherwise N/A. Since current spec does NOT set `setUserAuthenticationRequired(true)`, the key is NOT auto-invalidated on biometric change. This means `KeyPermanentlyInvalidatedException` will NOT fire on fingerprint enrollment — D-15's "detect keystore invalidation" path is only triggered by explicit key deletion (factory reset). Planner MUST decide: accept this (simpler, still secure for on-device only) OR add `setUserAuthenticationRequired(true) + setInvalidatedByBiometricEnrollment(true)` to the key spec (stronger, but regenerating the key permanently locks out existing wallets). Recommend documenting in plan-check. |
| A5 | 30-minute auto-rebroadcast interval (D-25) is not configurable by user | Pattern 4 / Rebroadcast Worker | User decision to keep "silent, no user-facing action" — implement with the fixed 30/60/120/240/480 min backoff documented. |
| A6 | `sum(utxo.value) - sum(reserved.value)` is always ≥ 0 | Pattern 3 / Example 2 | If a reservation outlives its underlying UTXO (e.g., reorg drops a tx), the subtraction can go negative. Example 2 uses `coerceAtLeast(0)`; plan should also cleanup stale reservations on startup. |
| A7 | WorkManager 15-min minimum is acceptable for D-06 background detection | D-06 | User explicitly chose 15 min in discussion-log. System enforces this minimum [CITED: developer.android.com]. No work-around that complies with Doze/power-save. |
| A8 | Ravencoin ElectrumX servers accept `blockchain.estimatefee(6)` (target = 6 blocks) | Fee Estimation | [VERIFIED: github.com/Electrum-RVN-SIG/electrumx-ravencoin/docs/protocol-methods.rst returns -1 if insufficient data]. Fallback to 0.01 RVN/kB handles this. |
| A9 | Bouncy Castle HMAC-SHA256 is sufficient for D-15 seed integrity check | D-15 | HMAC-SHA256 is standard; no reason to use anything else for this purpose. Key = second Keystore-wrapped AES-GCM key (or derived from the main key via HKDF for simplicity). |
| A10 | The existing `consolidate_fix.kt` scratch file at repo root (per STATE.md blockers) is NOT relevant to Phase 30 and can be deleted independently | Misc | STATE.md flags this as a blocker/concern but it's a repo-hygiene issue, not functionality. Plan should note: delete this file in a housekeeping task, separate from wallet-reliability scope. |

## Open Questions

1. **Should we harden `getOrCreateAndroidKey()` with `setUserAuthenticationRequired(true)` + `setInvalidatedByBiometricEnrollment(true)`? (A4 above)**
   - What we know: Current spec does NOT require per-use authentication. All sends succeed silently if device is unlocked.
   - What's unclear: CONTEXT.md D-15 says "require BiometricPrompt before revealing mnemonic words" — this is about the REVEAL flow, not every send. But also "detect KeyPermanentlyInvalidatedException on decrypt" — which would never fire with the current spec.
   - Recommendation: **Use BiometricPrompt as a UI-level gate only for mnemonic reveal. Add HMAC-of-seed for integrity (D-15 third point). Do NOT change the Keystore key spec**; regenerating the key permanently breaks existing wallets, and the current spec's `setUnlockedDeviceRequired(true)` is already a reasonable bar. The KeyPermanentlyInvalidatedException handler becomes dead code only for a narrow reason (factory reset), and that's acceptable.
   - Action for planner: confirm this interpretation. If user wants stricter (auth on every send), that's a larger migration and should be deferred.

2. **What exactly goes in `tx_history.cycled_sat`?**
   - What we know: D-19 says display "Sent 5 RVN · Cycled 244.9988 RVN · Fee 0.0012 RVN". The `cycled_sat` is the amount sent to the `changeAddress` (currentIndex+1) in the atomic tx. `RavencoinTxBuilder.buildAndSignMultiAddressSend` already emits this as an output.
   - What's unclear: For pure RVN sends without asset sweep, this is just `totalIn - amountSat - feeSat`. For sends with asset sweep, RVN and assets both go to the same new address.
   - Recommendation: `cycled_sat = sum(outputs where output.address == changeAddress)` regardless of whether it's RVN or asset value; assets reported separately in tx details. Planner to decide whether history row shows only RVN cycled or also counts asset outputs.

3. **Should the subscription socket reconnect automatically on scripthash-status mismatch?**
   - What we know: On network change, the old socket may die. D-12 says yellow pill = reconnecting, red = all nodes down.
   - What's unclear: What triggers a reconnect? (Timeout? Explicit failure? Ping failure?) And what does "reconnect" mean for already-pinned TOFU fingerprints?
   - Recommendation: Reconnect on (a) ping-timeout (60s without response), (b) read error, (c) connectivity change. TOFU pins persist (Phase 10 SQLite). If TOFU mismatch on reconnect → D-11 quarantine.

4. **Current node list is 4 servers, but `rvn-dashboard.com` may not be SSL-enabled anymore.**
   - What we know: Existing code has 4 servers. The rvn4lyfe.com servers.json only lists 3 (rvn4lyfe.com, an onion, 162.19.153.65).
   - What's unclear: Which servers are actually healthy in 2026?
   - Recommendation: In a plan-check step, runtime-verify each hardcoded server with a `server.version` call before shipping. If any fail, remove or replace. This is a "health check in CI" concern — the plan should add a one-shot connectivity test script.

## Environment Availability

Skipping this section — Phase 30 is pure Android code/config changes. No new external tools or services required. All dependencies already in `libs.versions.toml`:
- Gradle + AGP 8.7.3
- Kotlin 1.9.22
- JDK 17
- Android SDK 35 (target), 26 (min)

No Node/npm/Python/Docker additions needed for the Android side of this phase.

## Validation Architecture

### Test Framework
| Property | Value |
|----------|-------|
| Framework | JUnit 4 |
| Config file | `android/app/build.gradle.kts` — `testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"` |
| Quick run command | `./gradlew testConsumerDebugUnitTest -i` |
| Full suite command | `./gradlew test` |

### Phase Requirements → Test Map

| Req ID | Behavior | Test Type | Automated Command | File Exists? |
|--------|----------|-----------|-------------------|-------------|
| WALLET-BAL | `sum(utxo.value) - sum(reserved.value)` equals displayed spendable (D-03 + D-20) | unit | `./gradlew testConsumerDebugUnitTest --tests "*WalletCacheDaoTest.balance_subtracts_reserved*"` | ❌ Wave 0 |
| WALLET-BAL | Cache write-and-read roundtrip preserves UTXO JSON + timestamp | unit | `./gradlew testConsumerDebugUnitTest --tests "*WalletCacheDaoTest.roundtrip*"` | ❌ Wave 0 |
| WALLET-SEND | `sendRvnLocal` inserts reservation rows for all consumed UTXOs | unit (Robolectric-free, SQLite in-memory) | `./gradlew testConsumerDebugUnitTest --tests "*ReservedUtxoDaoTest.insert_on_broadcast*"` | ❌ Wave 0 |
| WALLET-SEND | Fee estimator falls back to 0.01 RVN/kB when estimatefee returns -1 | unit (stub RavencoinPublicNode) | `./gradlew testConsumerDebugUnitTest --tests "*FeeEstimatorTest.fallback*"` | ❌ Wave 0 |
| WALLET-RECV | Scripthash subscription parses notification frames correctly | unit (mock socket line reader) | `./gradlew testConsumerDebugUnitTest --tests "*SubscriptionParserTest*"` | ❌ Wave 0 |
| WALLET-RECV | WorkManager worker detects balance increase and fires notification | instrumented | `./gradlew connectedAndroidTest --tests "*WalletPollingWorkerTest*"` | ❌ deferred to manual-verify in Phase 30 plan-check (instrumented tests are not wired in CI) |
| WALLET-UTXO | Reserved UTXOs cleaned up when submitted tx confirms | unit | `./gradlew testConsumerDebugUnitTest --tests "*ReservedUtxoDaoTest.cleanup_on_confirm*"` | ❌ Wave 0 |
| WALLET-UTXO | Startup prunes reservations older than 48h | unit | `./gradlew testConsumerDebugUnitTest --tests "*ReservedUtxoDaoTest.prune_stale*"` | ❌ Wave 0 |
| WALLET-MNEM | BIP39 validator rejects trailing whitespace (Pitfall 7) | unit | `./gradlew testConsumerDebugUnitTest --tests "*WalletManagerTest.validateMnemonic_rejects_padding*"` | ❌ Wave 0 |
| WALLET-MNEM | Restore over non-zero wallet without backup throws `BackupRequiredException` | unit | `./gradlew testConsumerDebugUnitTest --tests "*WalletManagerTest.restore_forces_backup*"` | ❌ Wave 0 |
| WALLET-KEYS | HMAC of seed validated on every getMnemonic(); mismatch throws | unit | `./gradlew testConsumerDebugUnitTest --tests "*WalletManagerTest.hmac_integrity*"` | ❌ Wave 0 |
| WALLET-KEYS | `KeyPermanentlyInvalidatedException` catch surfaces a specific restore path | unit (mock Cipher) | `./gradlew testConsumerDebugUnitTest --tests "*WalletManagerTest.key_invalidated_routes_to_restore*"` | ❌ Wave 0 |
| Tx history | `cycled_sat` correctly calculated for multi-address send | unit | `./gradlew testConsumerDebugUnitTest --tests "*RavencoinTxBuilderTest.multiAddressSend_change_to_fresh_address*"` | ✅ partial (RavencoinTxBuilderTest exists; extend) |

### Sampling Rate
- **Per task commit:** `./gradlew testConsumerDebugUnitTest -i` (runs only the consumer-flavor unit tests — fast)
- **Per wave merge:** `./gradlew test` (runs both flavors)
- **Phase gate:** Full suite green before `/gsd-verify-work`. Instrumented tests (WorkManager, Biometric) are manually verified on a physical device and documented in the plan's verification section.

### Wave 0 Gaps
- [ ] `android/app/src/test/java/io/raventag/app/wallet/cache/WalletCacheDaoTest.kt` — in-memory SQLite tests for D-04 / D-20
- [ ] `android/app/src/test/java/io/raventag/app/wallet/cache/ReservedUtxoDaoTest.kt` — reservation lifecycle
- [ ] `android/app/src/test/java/io/raventag/app/wallet/subscription/SubscriptionParserTest.kt` — JSON-RPC frame routing (response vs notification)
- [ ] `android/app/src/test/java/io/raventag/app/wallet/fee/FeeEstimatorTest.kt` — fallback + unit-conversion sanity
- [ ] `android/app/src/test/java/io/raventag/app/wallet/WalletManagerMnemonicTest.kt` — extend existing WalletManager tests for D-14/D-15/D-16 (if none exists, create new file; WalletManager tests are currently absent per TESTING.md line 215)
- [ ] Extend `android/app/src/test/java/io/raventag/app/wallet/RavencoinTxBuilderTest.kt` — assert outgoing-tx change-address equals `changeAddress` parameter (backs D-19)
- [ ] `android/app/src/androidTest/java/io/raventag/app/worker/WalletPollingWorkerTest.kt` — WorkManager instrumented test (deferred to manual CI)

Framework install: none needed — JUnit 4 already wired, Android `androidx.test.ext:junit` and `androidx.test:runner` already declared.

## Security Domain

Security enforcement is active (no `security_enforcement: false` in config).

### Applicable ASVS Categories

| ASVS Category | Applies | Standard Control |
|---------------|---------|-----------------|
| V2 Authentication | yes | BiometricPrompt (BIOMETRIC_STRONG + DEVICE_CREDENTIAL) for mnemonic reveal (D-15) |
| V3 Session Management | no | No server session; all state local |
| V4 Access Control | yes | Keystore `setUnlockedDeviceRequired(true)` (existing); CryptoObject binding for reveal (new) |
| V5 Input Validation | yes | BIP39 checksum + whitespace normalization (Pitfall 7); Ravencoin address Base58Check validation on paste (existing in TxBuilder) |
| V6 Cryptography | yes | AES-GCM 256 (Android Keystore — hardware where StrongBox), HMAC-SHA256 for seed integrity (new), HMAC-SHA512 for BIP32 (existing BouncyCastle), ECDSA secp256k1 (existing BouncyCastle). Never hand-roll any of these. |
| V7 Error Handling and Logging | yes | Do NOT log decrypted seed, private keys, or mnemonic words. Existing `android.util.Log` calls use address/txid only — audit in passing. |
| V9 Communications | yes | TLS to ElectrumX with TOFU (Phase 10, SQLite-persisted). Subscription socket uses same TofuTrustManager. |
| V10 Malicious Code | no | App-level scope only |
| V14 Configuration | yes | BuildConfig MUST NOT contain mnemonic or Keystore key alias (none currently). |

### Known Threat Patterns for Ravencoin HD Wallet on Android

| Pattern | STRIDE | Standard Mitigation |
|---------|--------|---------------------|
| Mnemonic extracted from a rooted device | Information Disclosure | StrongBox-backed AES-GCM key (existing); HMAC integrity check (new D-15); never cache decrypted in memory (D-16) |
| MITM on ElectrumX TLS (first connection window) | Spoofing | TOFU pin in SQLite (Phase 10); quarantine on mismatch (D-11); pinning on subscription socket too (new) |
| Replay of old raw tx after reorg | Tampering | Reservation cleanup by block confirmation (Pattern 5); 6-confirmation UI threshold (D-08) |
| Stale balance causes double-send attempt | Tampering | Reserved UTXO table (D-20); ElectrumX node-level double-spend rejection (external) |
| Fingerprint enrolled by attacker with physical device access | Spoofing | BiometricPrompt `BIOMETRIC_STRONG` excludes Class 1 sensors; user education: set a strong lock-screen PIN |
| Fake incoming-tx notification spoofed by malicious app | Spoofing | `incoming_tx` channel only written by this app's own process; system prevents cross-app notification posting. No extra mitigation needed. |
| Screenshot of revealed mnemonic | Information Disclosure | `FLAG_SECURE` on MnemonicBackupScreen (recommended addition; not in CONTEXT.md but consistent with D-13 copy-only and industry norm for crypto wallets) |
| Key invalidated by factory reset causes permanent lockout | Denial of Service | Mandatory backup flow before major settings changes (D-14 forced-backup gate); user education via restore-dialog copy (UI-SPEC §Copywriting Contract) |
| Reserved UTXO desync (local says reserved, chain confirmed) | Tampering / logic error | Startup prune (Pitfall 6); on every refresh, reconcile `reserved_utxos` against `tx_history` confirmations |

**`FLAG_SECURE` recommendation (not in CONTEXT.md):** MnemonicBackupScreen should set `window.setFlags(FLAG_SECURE, FLAG_SECURE)` in its `DisposableEffect`. This prevents screenshots and screen-recording. Outside the scope of D-13 but strongly recommended; plan-phase should surface this as a proposed addition to UI-SPEC Implementation Notes.

## Sources

### Primary (HIGH confidence)
- `github.com/Electrum-RVN-SIG/electrumx-ravencoin/blob/master/docs/protocol-methods.rst` — confirmed RPC method signatures including asset parameter
- `electrumx.readthedocs.io/en/latest/protocol-methods.html` — upstream ElectrumX protocol (Ravencoin fork inherits these)
- `electrumx.readthedocs.io/en/latest/protocol-basics.html` — newline-delimited JSON-RPC framing, subscription semantics
- `raw.githubusercontent.com/RavenProject/Ravencoin/master/src/consensus/consensus.h` — `COINBASE_MATURITY = 100`
- `raw.githubusercontent.com/RavenProject/Ravencoin/master/src/chainparams.cpp` — `nPowTargetSpacing = 1 * 60` (1-minute blocks), `nSubsidyHalvingInterval = 2100000`
- `developer.android.com/reference/android/security/keystore/KeyPermanentlyInvalidatedException` — exception semantics
- `developer.android.com/reference/kotlin/androidx/work/PeriodicWorkRequest` — 15-min minimum confirmed
- Existing codebase: `WalletManager.kt`, `RavencoinPublicNode.kt`, `RavencoinTxBuilder.kt`, `TofuFingerprintDao.kt`, `WalletPollingWorker.kt`, `NetworkModule.kt`

### Secondary (MEDIUM confidence)
- `medium.com/androiddevelopers/using-biometricprompt-with-cryptoobject-how-and-why-aace500ccdb7` — CryptoObject rationale
- `github.com/BlueWallet/BlueWallet/wiki/Wallets-refresh-strategy` — reference mobile-wallet polling pattern (BlueWallet uses poll-only, not subscription)
- `github.com/Electrum-RVN-SIG/electrum-ravencoin/blob/master/electrum/servers.json` — public server inventory (3 servers; existing code has 4 — one extra is 51.222.139.25)
- `bitcoin.stackexchange.com/questions/114985` — 6-confirmation reorg threshold justification

### Tertiary (LOW confidence — flagged for runtime verification)
- Public ElectrumX server liveness in 2026 (A3) — requires a plan-check connectivity test
- Behavior of `blockchain.estimatefee(6)` under low-volume Ravencoin mempool (A1, A8) — must handle -1 gracefully; unit test the fallback path
- Android `BiometricPrompt.CryptoObject` behavior when no biometric is enrolled but device credential is set — needs instrumented test

## Metadata

**Confidence breakdown:**
- Standard stack: HIGH — all libraries already in tree; versions verified against STACK.md (2026-04-13 baseline) and no breaking releases between then and today (2026-04-18)
- Architecture: HIGH — patterns align with existing Phase 10/20 patterns and ElectrumX protocol docs
- Pitfalls: MEDIUM-HIGH — Android Keystore pitfalls and TCP zombie pitfalls are industry-known; Ravencoin-specific pitfalls (asset UTXO accounting) are derived from codebase reading, not independent authoritative source
- Public node list: LOW — needs runtime connectivity verification at plan-check time
- Fee estimation behavior on Ravencoin: MEDIUM — unit confirmed, behavior at extreme mempool states not independently verified

**Research date:** 2026-04-18
**Valid until:** 2026-05-18 (30 days — ElectrumX protocol is stable; Android Keystore API is stable; public node liveness is the only volatile factor)

---

*Phase: 30-wallet-reliability*
*Research complete: 2026-04-18*
*Downstream consumer: `/gsd-plan-phase` (planner) and task executors*
