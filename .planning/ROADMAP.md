# RavenTag Roadmap

**Milestone:** v1.0 Security, Performance & Reliability

## Phase Overview

```
Phase 10: Android Security Hardening
Phase 20: Android Performance Optimization
Phase 30: Wallet Reliability
Phase 40: Asset Emission UX
Phase 50: Backend Stability
```

---

## Phase 10: Android Security Hardening

**Goal:** Eliminate security vulnerabilities in Android app

**Requirements:**
- Rimuovere ADMIN_KEY da BuildConfig Android, richiedere sempre da EncryptedSharedPreferences
- Abilitare rejectUnauthorized per ElectrumX TLS o pinning SHA-256 fingerprint
- Persistere fingerprint TOFU in SQLite (sopravvivono a restart)
- Usare column list esplicite nelle SELECT admin (no SELECT *)
- Verificare che nessun proxy/CDN logghi il body di derive-chip-key

**Success Criteria:**
- No hardcoded credentials in APK
- All ElectrumX connections use TLS with certificate validation
- TOFU fingerprints persist across app restarts
- No SQL injection risks from SELECT *
- derive-chip-key payload never logged

**Plans:**
4/4 plans complete
- [x] 10-01-PLAN.md — Admin Key Migration (BuildConfig → EncryptedSharedPreferences)
- [x] 10-02-PLAN.md — Persist TOFU fingerprints in SQLite for MITM protection across restarts
- [x] 10-03-PLAN.md — Replace SELECT * queries with explicit column lists in backend
- [x] 10-04-PLAN.md — Verify and prevent logging of derive-chip-key payloads

---

## Phase 20: Android Performance Optimization

**Goal:** Eliminate UI blocking and improve responsiveness

**Requirements:**
- Convertire chiamate bloccanti Android (enrichWithIpfsData, execute()) in suspend functions con withContext(IO)
- Ottimizzare restore wallet: ridurre blocking I/O e sincronizzazione sequenziale
- Garantire che invio RVN e asset non blocchi la UI

**Success Criteria:**
- All network calls use suspend functions
- Wallet restore completes without UI freeze
- Send operations show loading state, not blocking UI
- No ANRs during normal operations

**Plans:**
6/6 plans complete
- [x] 20-01-PLAN.md — Convert OkHttp execute() calls to suspend functions using suspendCancellableCoroutine
- [x] 20-02-PLAN.md — Create TransactionNotificationHelper for send operation progress notifications
- [x] 20-03-PLAN.md — Create retryWithBackoff utility with exponential backoff for transient failures
- [x] 20-04-PLAN.md — Implement parallel wallet restore with async/awaitAll for ~3x speedup
- [x] 20-05-PLAN.md — Integrate notifications into send operations (RVN and asset transfers) with retry
- [x] 20-06-PLAN.md — Implement loading UI patterns (full-screen spinner, button spinner) and error handling

---

## Phase 30: Wallet Reliability

**Goal:** Robust RVN wallet with accurate balances

**Requirements:**
- Wallet RVN: saldo affidabile, invio/ricezione, sincronizzazione UTXO
- Sicurezza wallet: protezione mnemonic, keystore integrity, export/import

**Success Criteria:**
- RVN balance matches ElectrumX state
- Send RVN transactions broadcast successfully
- Receive RVN detects incoming transactions
- UTXO set accurately reflects blockchain state
- Mnemonic can be safely exported/imported
- Keystore protected from extraction

**Plans:**
5/10 plans executed
- [x] 30-01-PLAN.md — Wave 0 test scaffolding (6 test files, 4 production stubs, behavior contracts for Wave 1-3)
- [x] 30-02-PLAN.md — Wallet Cache DB DAOs (WalletCacheDao, ReservedUtxoDao SQLite implementations)
- [x] 30-03-PLAN.md — Scripthash Subscription (ElectrumX real-time status notifications)
- [x] 30-04-PLAN.md — Fee Estimation (estimatefee with fallback)
- [x] 30-05-PLAN.md — Consolidation Reliability (UTXO reservation, pending consolidation, RebroadcastWorker)
- [ ] 30-06-PLAN.md — Mnemonic Safety (backup gate, HMAC integrity, keystore exception handling)
- [ ] 30-07-PLAN.md — Node Reliability (TOFU quarantine, fallback rotation)
- [ ] 30-08-PLAN.md — WalletScreen Refresh and Receive UX
- [ ] 30-09-PLAN.md — Tx History 3-Value (sent/cycled/fee breakdown)
- [ ] 30-10-PLAN.md — Housekeeping

---

## Phase 40: Asset Emission UX

**Goal:** Reliable asset/sub-asset issuance with clear error handling

**Requirements:**
- Emissione asset (Brand): gestione errori RPC, feedback utente chiaro

**Success Criteria:**
- RPC errors are caught and displayed to user
- Asset issuance failures have actionable error messages
- User feedback for success/failure is clear
- No silent failures during issuance

**Plans:**
Not yet planned

---

## Phase 50: Backend Stability

**Goal:** Robust backend with proper error handling

**Requirements:**
- Aggiungere unhandledRejection handler nel backend
- Sostituire sequential loop in getAssetHierarchy con Promise.all
- Paginazione o limite documentato per listassets (cap 200)
- Cleanup periodico request_logs e nfc_counters
- Backup SQLite sicuro (sostituire raw file copy con .backup API)

**Success Criteria:**
- No unhandled promise rejections crash the server
- Asset hierarchy queries are parallelized
- listassets has enforced pagination
- Database tables don't grow unbounded
- SQLite backups use proper API, not file copies

**Plans:**
Not yet planned

---

## Out of Scope

- Multi-instance backend / horizontal scaling — self-hosted, single-instance acceptable
- Structured logging (pino) — operational improvement, not security-critical
- Frontend web performance — Android focus for this milestone
- Migrating registered_tags to chip_registry — technical debt, not vulnerability
- Testing suite backend — important but separate scope

---

## Milestone Criteria

**v1.0 Complete when:**
- [ ] All security vulnerabilities addressed
- [ ] Android app performs smoothly without UI blocking
- [ ] RVN wallet is reliable and accurate
- [ ] Asset issuance has clear error handling
- [ ] Backend is stable and robust

**Target Release:** TBD

*Created: 2026-04-13*
*Updated: 2026-04-21 — Phase 30 plan 30-05 executed*
