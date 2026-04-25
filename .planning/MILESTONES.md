# Milestones

## v1.0 — Security, Performance & Reliability

**Shipped:** 2026-04-26
**Phases:** 5 (10-50) | **Plans:** 30 | **Tasks:** ~120

### Delivered

Hardening sicurezza Android (ADMIN_KEY rimosso, TLS abilitato, TOFU persistente), ottimizzazione performance (suspend functions, restore parallelo 3x), wallet reliability (UTXO sync, mnemonic safety, node health monitor), asset emission UX (error classification, step progress, confirmation polling), backend stability (error handlers, pagination, backup API, CLI explorer).

### Key Accomplishments

1. Admin key migrated from BuildConfig to EncryptedSharedPreferences (AES-256-GCM via Android Keystore)
2. All blocking OkHttp execute() calls converted to suspend functions — no more ANR
3. TOFU certificate fingerprints persisted in SQLite — survive app restart, close MITM window
4. Full wallet reliability stack: UTXO reservation, scripthash subscription, fee estimation, consolidation
5. Mnemonic safety: BiometricPrompt bound via CryptoObject + HMAC integrity + FLAG_SECURE
6. Backend process-level error handlers with graceful shutdown; SQLite backup via .backup() API

### Git Range

`fc875de` (2026-03-26) → `5f3551f` (2026-04-26) — 31 days

### Known Deferred Items

- RavencoinTxBuilderTest: 2 pre-existing asset issuance test failures
- Em-dash cleanup: `RavencoinTxBuilder.kt:907,908`
- Structured logging (pino) — operational improvement, separate scope
- Testing suite backend — separate scope
- registered_tags → chip_registry migration — existing technical debt

### Archive

- [v1.0 Roadmap Archive](milestones/v1.0-ROADMAP.md)

---
