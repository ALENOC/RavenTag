# RavenTag

## Current Milestone: v1.0 Security, Performance & Reliability

**Status:** ✅ SHIPPED 2026-04-26

**What shipped:**
- Sicurezza Android: ADMIN_KEY rimosso da APK, TLS ElectrumX abilitato, TOFU fingerprint persistenti in SQLite, SELECT * fix, derive-chip-key mai loggato
- Performance Android: blocking OkHttp → suspend functions con withContext(IO), restore wallet parallelo (~3x speedup), retry con exponential backoff
- Wallet: saldo RVN affidabile, UTXO sync/reservation, scripthash subscription, fee estimation, consolidation reliability
- Mnemonic safety: BiometricGate + CryptoObject, HMAC integrity, FLAG_SECURE
- Asset emission UX: error classification (8 categorie), multi-step progress indicator, confirmation polling
- Backend: unhandledRejection/process-level handlers, Promise.allSettled chunked per gerarchia asset, paginazione, retention cleanup, backup SQLite via .backup() API, CLI explorer read-only

## What This Is

Framework open-source trustless (RTP-1) che collega tag NFC NTAG 424 DNA ad asset Ravencoin. Tre deployment target: backend Node.js/Express, frontend Next.js 14, e app Android Kotlin/Compose. La verifica crittografica gira interamente client-side, senza fidarsi del server.

## Core Value

La catena crittografica da chip NFC a blockchain deve essere sicura e reattiva. Se la verifica non e' sicura o la GUI si blocca, il protocollo perde credibilita'.

## Requirements

### Validated

- ✓ Verifica SUN NTAG 424 DNA client-side (AES-CMAC Bouncy Castle + Web Crypto API) — v1.0
- ✓ Flusso emissione asset/sub-asset on-device signing + ElectrumX broadcast — v1.0
- ✓ Wallet HD BIP44/BIP39 con mnemonic protetto da Android Keystore — v1.0
- ✓ Revocazione soft (SQLite) + hard (burn on-chain) — v1.0
- ✓ Backend REST API con verify, asset, brand, admin, registry — v1.0
- ✓ Frontend Next.js con Web NFC scanning — v1.0
- ✓ Docker deployment con healthcheck e backup cifrato — v1.0
- ✓ CI/CD GitHub Actions — v1.0
- ✓ ADMIN_KEY rimosso da BuildConfig → EncryptedSharedPreferences AES-256-GCM — v1.0
- ✓ TLS ElectrumX abilitato con rejectUnauthorized + TOFU fingerprint persistito in SQLite — v1.0
- ✓ SELECT * query sostituite con column list esplicite — v1.0
- ✓ derive-chip-key payload mai loggato da proxy/CDN — v1.0
- ✓ Chiamate OkHttp bloccanti → suspend functions con withContext(IO) — v1.0
- ✓ Wallet restore ottimizzato parallelo (~3x speedup) — v1.0
- ✓ Invio RVN e asset non-bloccante con notification progress — v1.0
- ✓ Wallet RVN: saldo affidabile, invio/ricezione, sincronizzazione UTXO — v1.0
- ✓ Emissione asset (Brand): gestione errori RPC, feedback utente — v1.0
- ✓ Sicurezza wallet: protezione mnemonic, keystore integrity, export/import — v1.0
- ✓ unhandledRejection + uncaughtException handler con graceful shutdown — v1.0
- ✓ Asset hierarchy parallelizzato con Promise.allSettled — v1.0
- ✓ Paginazione listassets (default 50, cap 200) — v1.0
- ✓ Cleanup periodico request_logs con retention — v1.0
- ✓ Backup SQLite via .backup() API (non raw file copy) — v1.0
- ✓ CLI database explorer read-only — v1.0

### Active

*Nessuna requirement attiva. Pronto per prossimo milestone.*

### Out of Scope

- Multi-instance backend / horizontal scaling — progetto self-hosted, single-instance accettabile
- Structured logging (pino) — miglioramento operativo, non critico per sicurezza
- Frontend web performance — focus su Android per v1.0
- Migrare registered_tags a chip_registry — technical debt, non vulnerabilita'
- Testing suite backend — importante ma scope separato

## Context

v1.0 shipped con 30 plan, 5 fasi, ~120 task. Android: 28,768 LOC Kotlin/Compose. Backend: Node.js 20 + Express + better-sqlite3. Frontend: Next.js 14 + Tailwind. L'app Android ha ora wallet HD affidabile con restore parallelo, operazioni di invio non-bloccanti, e sicurezza crittografica end-to-end. Il backend ha error handling robusto, paginazione, backup sicuri, e CLI explorer.

## Constraints

- **Tech stack**: Kotlin 1.9 + Jetpack Compose + Bouncy Castle + OkHttp (Android); Node.js 20 + Express + better-sqlite3 (backend)
- **Protocollo**: RTP-1 deve rimanere compatibile, nessuna rottura della verifica SUN
- **Trustless**: tutta la verifica crittografica resta client-side
- **Android min SDK**: 26 (Android 8.0)
- **Self-hosted**: single-instance SQLite, nessun database esterno

## Key Decisions

| Decision | Rationale | Outcome |
|----------|-----------|---------|
| Fix sicurezza prima di performance | Vulnerabilita' attive hanno impatto reale, performance e' degrado | ✓ Good |
| Focus Android su suspend functions | Blocking OkHttp execute() causa ANR e freeze UI | ✓ Good |
| Persistere TOFU fingerprint in SQLite | In-process Map si resetta a ogni restart, lasciando finestra MITM | ✓ Good |
| Rimuovere BuildConfig.ADMIN_KEY | Chiave compilata nell'APK e' estrabile per decompilazione | ✓ Good |
| Lambda-injectable FeeEstimator per testabilita' | Pure function pattern, no DI framework overhead | ✓ Good |
| BiometricPrompt bound via CryptoObject | Authentication bypassabile se basata solo su boolean callback | ✓ Good |
| NodeHealthMonitor singleton | Single source of truth per RPC + subscription quarantine | ✓ Good |
| computeSpendableBalanceSat pure function | Testable senza mock, no side effects | ✓ Good |
| Wallet Cache DB tables co-located in wallet_reliability.db | Single DB connection, simpler migrations | ✓ Good |
| CharArray zero-fill usa space (0x20) non NUL | Coerente con decisione progetto D-16 | ✓ Good |

## Evolution

This document evolves at phase transitions and milestone boundaries.

**After each phase transition** (via `/gsd-transition`):
1. Requirements invalidated? → Move to Out of Scope with reason
2. Requirements validated? → Move to Validated with phase reference
3. New requirements emerged? → Add to Active
4. Decisions to log? → Add to Key Decisions
5. "What This Is" still accurate? → Update if drifted

**After each milestone** (via `/gsd-complete-milestone`):
1. Full review of all sections
2. Core Value check — still the right priority?
3. Audit Out of Scope — reasons still valid?
4. Update Context with current state

---
*Last updated: 2026-04-26 after v1.0 milestone*
