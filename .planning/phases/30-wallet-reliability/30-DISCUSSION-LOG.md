# Phase 30: Wallet Reliability - Discussion Log

> **Audit trail only.** Do not use as input to planning, research, or execution agents.
> Decisions are captured in CONTEXT.md — this log preserves the alternatives considered.

**Date:** 2026-04-17
**Phase:** 30-wallet-reliability
**Areas discussed:** Balance & UTXO sync, Receive detection, Node reliability & failover, Mnemonic export/import, Quantum-resistance consolidation (user-surfaced), Receive address strategy, Fee estimation, Transaction history, Mempool & stuck-tx, Asset-UTXO reservation, Power save, Consolidation failure recovery

---

## Area Selection

| Option | Description | Selected |
|--------|-------------|----------|
| Balance & UTXO sync strategy | Sync cadence, triggers, balance/UTXO drift handling | ✓ |
| Receive detection | Subscription vs poll vs WorkManager | ✓ |
| Node reliability & failover | Single vs fallback list, timeouts, degraded UX | ✓ |
| Mnemonic export/import | Format, import safety, keystore integrity | ✓ |

---

## Balance & UTXO Sync

### Sync triggers
| Option | Description | Selected |
|--------|-------------|----------|
| On app foreground | Refresh when WalletScreen resumes | ✓ |
| Periodic poll while open | Every N seconds in foreground | ✓ |
| After local action | Refresh after send/receive | |
| Manual pull-to-refresh only | No auto-sync | |

### Drift handling (balance vs UTXO sum)
| Option | Description | Selected |
|--------|-------------|----------|
| Trust UTXO sum, log warning | sum(utxo.value) as balance | ✓ |
| Trust balance RPC, flag UI | Show balance with 'syncing' indicator | |
| Refresh both, retry until match | Block actions during reconciliation | |

### Poll interval
| Option | Description | Selected |
|--------|-------------|----------|
| 15 seconds | Very responsive | |
| 30 seconds | Standard wallet UX | ✓ |
| 60 seconds | Battery-friendly | |
| Adaptive | Start 30s, back off when stable | |

### State cache
| Option | Description | Selected |
|--------|-------------|----------|
| In-memory only | Re-fetch on restart | |
| SQLite cache + fetch on open | Persist state, instant render | ✓ |
| EncryptedSharedPreferences cache | Simpler blob storage | |

---

## Receive Detection

### Detection method
| Option | Description | Selected |
|--------|-------------|----------|
| ElectrumX scripthash subscription | Push-based, near-instant | ✓ |
| Poll only (reuse 30s wallet poll) | Simple, up to 30s latency | |
| Background WorkManager when app closed | Offline polling | |
| Subscription + WorkManager hybrid | Best of both | |

**Notes:** User selected subscription-only for primary detection; WorkManager added separately in the follow-up question.

### Receive UX
| Option | Description | Selected |
|--------|-------------|----------|
| In-app banner/snackbar | Top-of-screen notification | ✓ |
| System notification | OS-level, respects settings | ✓ |
| Balance auto-updates silently | No explicit notice | ✓ |
| Transaction appears in history list | New row with confirm progress | ✓ |

### Confirmation threshold
| Option | Description | Selected |
|--------|-------------|----------|
| 1 confirmation | ~1 min, common for consumer wallets | |
| 6 confirmations (Bitcoin-style) | ~6 min, conservative | ✓ |
| Display count, never label 'final' | User decides | |

### Background polling
| Option | Description | Selected |
|--------|-------------|----------|
| Yes, every 15 minutes | WorkManager periodic job | ✓ |
| Yes, charging + WiFi only | Battery-friendly | |
| No, foreground only | Simplest | |

---

## Node Reliability & Failover

### Failover strategy
| Option | Description | Selected |
|--------|-------------|----------|
| Hardcoded fallback list, round-robin | 3-5 nodes, auto | ✓ |
| Primary + secondary, manual switch | User-controlled | |
| User-configurable in Settings | Power-user friendly | |
| Hybrid: defaults + user override | Ship defaults, allow custom | |

### Degraded UX
| Option | Description | Selected |
|--------|-------------|----------|
| Connection status badge | Colored pill indicator | ✓ |
| Balance with 'stale' indicator | Last updated HH:MM | ✓ |
| Block send/receive when fully disconnected | Prevent broadcast to void | ✓ |
| Silent retry, no UI change until all fail | Cleanest, less transparent | |

### Timeouts
| Option | Description | Selected |
|--------|-------------|----------|
| 5s connect / 10s RPC | Quick failover | |
| 10s connect / 20s RPC | Matches current NetworkModule | ✓ |
| 3s connect / 8s RPC | Aggressive | |

### TOFU mismatch handling
| Option | Description | Selected |
|--------|-------------|----------|
| Quarantine for 1 hour, then retry | Auto, prevents churn | ✓ |
| Permanently skip until app restart | Session-scoped | |
| Surface to user | Most transparent | |

---

## Mnemonic Export/Import

### Export format
| Option | Description | Selected |
|--------|-------------|----------|
| 12/24-word phrase display (copyable) | BIP39 standard | ✓ |
| QR code (plain mnemonic) | Inter-device transfer | |
| Encrypted file with passphrase | Safe for cloud | |
| Encrypted QR with passphrase | Safe to photograph | |

### Import verification
| Option | Description | Selected |
|--------|-------------|----------|
| BIP39 checksum validation | Reject invalid input | ✓ |
| Confirmation dialog naming current wallet | Prevent accidental overwrite | ✓ |
| Require current wallet backup first | Force backup before overwrite | ✓ |
| Derive + show first address, confirm match | Verify expected wallet | |

### Keystore safeguards
| Option | Description | Selected |
|--------|-------------|----------|
| Detect keystore key invalidated | KeyPermanentlyInvalidatedException handling | ✓ |
| Require biometric/device credential to reveal mnemonic | BiometricPrompt before view | ✓ |
| Integrity check on wallet load (HMAC of seed) | Detect tampering | ✓ |
| Strongbox-backed key when available | Hardware-backed keystore | |

### Mnemonic caching
| Option | Description | Selected |
|--------|-------------|----------|
| Re-decrypt on every use | Never hold plaintext | ✓ |
| Cache in memory for session, clear on background | Balance UX / safety | |
| Cache for N minutes after unlock | Explicit timeout | |

---

## Quantum-Resistance Consolidation (user-surfaced during area selection)

The user raised this as a critical behavior that must be preserved and optimized, not redesigned.

**User's note (verbatim, Italian):** "Quando si inviano RVN o Asset verso un indirizzo esterno, il wallet deve spostare prima tutti gli asset e poi tutto il saldo rimanente in una transazione atomica in un nuovo indirizzo che non ha mai speso con currentIndex+1 (attualmente lo fa già e va velocizzato il processo di invio), il wallet inoltre deve spostare RVN e asset che dovessero arrivare su indirizzi con currentIndex minore di quello attuale ad un indirizzo che non ha mai speso con currentIndex+1 (il wallet se arrivano asset ad un indirizzo vecchio che non ha RVN, dovrà finanziarlo per trasferire gli asset e per questo l'indirizzo attuale non sarà più un indirizzo che non ha mai speso, per questo va incrementato currentIndex a currentIndex+1), tutte queste funzioni le fa già ma vanno ottimizzate in velocità e affidabilità, devono essere invisibili all'utente finale, servono per rendere il wallet resistente ad attacchi di computer quantistici in quanto l'ultimo indirizzo non avrà mai speso RVN e quindi non avrà la sua chiave pubblica esposta."

Captured in D-17.

---

## Receive Address Strategy

| Option | Description | Selected |
|--------|-------------|----------|
| Always show currentIndex (never-spent) address | Advances after send/consolidation | ✓ |
| Rotate address on each receive (BIP44 gap limit) | More privacy, doesn't fit model | |
| Single fixed address forever | Breaks quantum-resistance | |

---

## Fee Estimation

| Option | Description | Selected |
|--------|-------------|----------|
| Dynamic via estimatefee, user override | Adaptive + rescue path | ✓ |
| Static 0.01 RVN/kB, user override | Simpler, no RPC dep | |
| Dynamic only, no user override | Cleanest, no rescue | |

---

## Transaction History

### Scope & pagination
| Option | Description | Selected |
|--------|-------------|----------|
| Last 20 inline, 'Load more' button | Balanced default | ✓ |
| Last 50 inline, infinite scroll | Smoother UX | |
| All history eagerly, filter client-side | Small wallets only | |
| Last 20, 'See full history' link | Separate view | |

### Outgoing-tx display (user-surfaced)
**User's note (verbatim, Italian):** "Le transazioni transazioni in uscita devono essere elencate nell'apposita sezione in modo corretto (se per esempio ho 250 RVN e invio 5 RVN a un indirizzo esterno, devo vedere nella lista 5 RVN inviati e 245 - la commissione RVN ciclati su intdirizzo nuovo che non ha mai speso, andrebbe visualizzato anche la commissione spesa a quanto ammonta)."

Captured in D-19.

---

## Mempool & Stuck-tx Handling

| Option | Description | Selected |
|--------|-------------|----------|
| Unconfirmed incoming NOT counted as spendable | Separate 'Pending' line | ✓ |
| Unconfirmed incoming counted as spendable once in mempool | Faster availability, risk | |
| Auto-rebroadcast if unconfirmed after N minutes | Silent retry | ✓ |
| Show 'pending' with timestamp; manual rebroadcast after 1h | Explicit user action | |

---

## Asset-UTXO Reservation

| Option | Description | Selected |
|--------|-------------|----------|
| Lock reserved UTXOs in SQLite table | Durable, prevents double-spend attempts | ✓ |
| Disable Send button while consolidation pending | Simple blocking | |
| In-memory reservation, lost on restart | Risky | |

---

## Power Save Behavior

| Option | Description | Selected |
|--------|-------------|----------|
| Respect battery saver: pause poll, keep subscription | Minimal-cost sync | ✓ |
| Respect data saver: fall back to poll-on-open | Skip background jobs | |
| Consolidation broadcasts regardless of power state | Security-critical | ✓ |
| Show reduced-sync-mode indicator | Prevent user confusion | ✓ |

---

## Consolidation Failure Recovery

| Option | Description | Selected |
|--------|-------------|----------|
| Silent retry with backoff, queue for next app open | Invisible, resilient | ✓ |
| Silent retry only, give up after N attempts | Lower noise, higher silent-failure risk | |
| Surface to user immediately on first failure | Intrusive | |
| Block new sends until last consolidation confirms | Strictest, lowest throughput | |

---

## Claude's Discretion

- SQLite schema details for wallet state cache, reserved_utxos, tx history tables.
- Notification channel configuration for incoming-tx channel.
- Exact WorkManager scheduling parameters (backoff, constraints, initial delay).
- Compose UI details for connection status badge, pending-balance line, tx history row format (D-19 display values).
- Coroutine/flow architecture for subscription delivery → UI state updates.
- Exact fallback ElectrumX node list (researcher to gather).
- Retry/backoff constants not fixed by Phase 20 D-02.

## Deferred Ideas

See `<deferred>` section in CONTEXT.md.
