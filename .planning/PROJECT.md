# RavenTag

## Current Milestone: v1.0 Security, Performance & Reliability

**Goal:** Hardening sicurezza, ottimizzazione performance Android, e affidabilita' end-to-end del wallet Ravencoin.

**Target features:**
- Sicurezza: ADMIN_KEY rimosso da APK, TLS ElectrumX abilitato, fingerprint TOFU persistenti, SELECT * fix
- Android performance: blocking OkHttp → suspend functions, wallet restore ottimizzato, invio RVN non-bloccante
- Wallet: saldo RVN affidabile, invio/ricezione, sincronizzazione UTXO
- Emissione asset (Brand): gestione errori RPC, feedback utente chiaro
- Backend: unhandledRejection handler, Promise.all per gerarchia, paginazione listassets, cleanup retention, backup SQLite sicuro

## What This Is

Framework open-source trustless (RTP-1) che collega tag NFC NTAG 424 DNA ad asset Ravencoin. Tre deployment target: backend Node.js/Express, frontend Next.js 14, e app Android Kotlin/Compose. La verifica crittografica gira interamente client-side, senza fidarsi del server.

Questo milestone: hardening sicurezza, ottimizzazione performance Android, e affidabilita' end-to-end del wallet Ravencoin.

## Core Value

La catena crittografica da chip NFC a blockchain deve essere sicura e reattiva. Se la verifica non e' sicura o la GUI si blocca, il protocollo perde credibilita'.

## Requirements

### Validated

- ✓ Verifica SUN NTAG 424 DNA client-side (AES-CMAC Bouncy Castle + Web Crypto API) — existing
- ✓ Flusso emissione asset/sub-asset on-device signing + ElectrumX broadcast — existing
- ✓ Wallet HD BIP44/BIP39 con mnemonic protetto da Android Keystore — existing
- ✓ Revocazione soft (SQLite) + hard (burn on-chain) — existing
- ✓ Backend REST API con verify, asset, brand, admin, registry — existing
- ✓ Frontend Next.js con Web NFC scanning — existing
- ✓ Docker deployment con healthcheck e backup cifrato — existing
- ✓ CI/CD GitHub Actions — existing

### Active

- [ ] Rimuovere ADMIN_KEY da BuildConfig Android, richiedere sempre da EncryptedSharedPreferences
- [ ] Abilitare rejectUnauthorized per ElectrumX TLS o pinning SHA-256 fingerprint
- [ ] Persistere fingerprint TOFU in SQLite (sopravvivono a restart)
- [ ] Usare column list esplicite nelle SELECT admin (no SELECT *)
- [ ] Verificare che nessun proxy/CDN logghi il body di derive-chip-key
- [ ] Convertire chiamate bloccanti Android (enrichWithIpfsData, execute()) in suspend functions con withContext(IO)
- [ ] Ottimizzare restore wallet: ridurre blocking I/O e sincronizzazione sequenziale
- [ ] Garantire che invio RVN e asset non blocchi la UI
- [ ] Wallet RVN: saldo affidabile, invio/ricezione, sincronizzazione UTXO
- [ ] Emissione asset (Brand): gestione errori RPC, feedback utente chiaro
- [ ] Sicurezza wallet: protezione mnemonic, keystore integrity, export/import
- [ ] Aggiungere unhandledRejection handler nel backend
- [ ] Sostituire sequential loop in getAssetHierarchy con Promise.all
- [ ] Paginazione o limite documentato per listassets (cap 200)
- [ ] Cleanup periodico request_logs e nfc_counters
- [ ] Backup SQLite sicuro (sostituire raw file copy con .backup API)

### Out of Scope

- Multi-instance backend / horizontal scaling — progetto self-hosted, single-instance accettabile
- Structured logging (pino) — miglioramento operativo, non critico per sicurezza
- Frontend web performance — focus su Android per questo milestone
- Migrare registered_tags a chip_registry — technical debt, non vulnerabilita'
- Testing suite backend — importante ma scope separato

## Context

Il codebase esiste gia' con tre deployment target funzionanti. Il CONCERNS.md identifica vulnerabilita' di sicurezza concrete (ADMIN_KEY nell'APK, TLS disabilitato, fingerprint non persistenti) e problemi di performance (chiamate RPC bloccanti sulla UI Android, N+1 query nel backend, tabelle SQLite senza retention). L'app Android ha un wallet HD con gestione RVN e asset, ma il restore e' lento e le operazioni di invio bloccano la GUI a causa di chiamate OkHttp sincrone su thread worker.

## Constraints

- **Tech stack**: Kotlin 1.9 + Jetpack Compose + Bouncy Castle + OkHttp (Android); Node.js 20 + Express + better-sqlite3 (backend)
- **Protocollo**: RTP-1 deve rimanere compatibile, nessuna rottura della verifica SUN
- **Trustless**: tutta la verifica crittografica resta client-side
- **Android min SDK**: 26 (Android 8.0)
- **Self-hosted**: single-instance SQLite, nessun database esterno

## Key Decisions

| Decision | Rationale | Outcome |
|----------|-----------|---------|
| Fix sicurezza prima di performance | Vulnerabilita' attive hanno impatto reale, performance e' degrado | — Pending |
| Focus Android su suspend functions | Blocking OkHttp execute() causa ANR e freeze UI | — Pending |
| Persistere TOFU fingerprint in SQLite | In-process Map si resetta a ogni restart, lasciando finestra MITM | — Pending |
| Rimuovere BuildConfig.ADMIN_KEY | Chiave compilata nell'APK e' estrabile per decompilazione | — Pending |

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
*Last updated: 2026-04-13 after initialization*