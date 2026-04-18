---
phase: 30
slug: wallet-reliability
status: draft
nyquist_compliant: false
wave_0_complete: false
created: 2026-04-18
---

# Phase 30 — Validation Strategy

> Per-phase validation contract for feedback sampling during execution.

---

## Test Infrastructure

| Property | Value |
|----------|-------|
| **Framework** | JUnit 4 (Android unit tests), AndroidJUnitRunner (instrumented) |
| **Config file** | `android/app/build.gradle.kts` (testInstrumentationRunner = androidx.test.runner.AndroidJUnitRunner) |
| **Quick run command** | `./gradlew :app:testConsumerDebugUnitTest -i` |
| **Full suite command** | `./gradlew test` |
| **Estimated runtime** | ~60 seconds for consumer flavor unit tests; ~180 seconds for full suite |

---

## Sampling Rate

- **After every task commit:** Run `./gradlew :app:testConsumerDebugUnitTest -i`
- **After every plan wave:** Run `./gradlew test`
- **Before `/gsd-verify-work`:** Full suite must be green
- **Max feedback latency:** 60 seconds

---

## Per-Task Verification Map

> Populated by each PLAN.md `<automated>` verify block. One row per atomic task.

| Task ID | Plan | Wave | Requirement | Threat Ref | Secure Behavior | Test Type | Automated Command | File Exists | Status |
|---------|------|------|-------------|------------|-----------------|-----------|-------------------|-------------|--------|
| 30-W0-01 | Wave 0 | 0 | WALLET-BAL, WALLET-UTXO | — | SQLite wallet_state_cache roundtrip preserves UTXOs + timestamp | unit | `./gradlew :app:testConsumerDebugUnitTest --tests "*WalletCacheDaoTest.roundtrip*"` | ❌ W0 | ⬜ pending |
| 30-W0-02 | Wave 0 | 0 | WALLET-BAL, WALLET-UTXO | — | Displayed balance = sum(utxo) - sum(reserved), never negative | unit | `./gradlew :app:testConsumerDebugUnitTest --tests "*WalletCacheDaoTest.balance_subtracts_reserved*"` | ❌ W0 | ⬜ pending |
| 30-W0-03 | Wave 0 | 0 | WALLET-SEND, WALLET-UTXO | — | Broadcast inserts reservation rows for all consumed UTXOs | unit | `./gradlew :app:testConsumerDebugUnitTest --tests "*ReservedUtxoDaoTest.insert_on_broadcast*"` | ❌ W0 | ⬜ pending |
| 30-W0-04 | Wave 0 | 0 | WALLET-UTXO | — | Reservations cleaned up when submitted tx confirms | unit | `./gradlew :app:testConsumerDebugUnitTest --tests "*ReservedUtxoDaoTest.cleanup_on_confirm*"` | ❌ W0 | ⬜ pending |
| 30-W0-05 | Wave 0 | 0 | WALLET-UTXO | — | Startup prunes reservations older than 48h (crash recovery, Pitfall 6) | unit | `./gradlew :app:testConsumerDebugUnitTest --tests "*ReservedUtxoDaoTest.prune_stale*"` | ❌ W0 | ⬜ pending |
| 30-W0-06 | Wave 0 | 0 | WALLET-RECV | T-30-RECV | Subscription parser routes response vs notification by id presence | unit | `./gradlew :app:testConsumerDebugUnitTest --tests "*SubscriptionParserTest*"` | ❌ W0 | ⬜ pending |
| 30-W0-07 | Wave 0 | 0 | WALLET-SEND | — | FeeEstimator falls back to 0.01 RVN/kB when estimatefee returns -1 or throws | unit | `./gradlew :app:testConsumerDebugUnitTest --tests "*FeeEstimatorTest.fallback*"` | ❌ W0 | ⬜ pending |
| 30-W0-08 | Wave 0 | 0 | WALLET-MNEM | T-30-MNEM | BIP39 validator rejects trailing whitespace / blank words (Pitfall 7) | unit | `./gradlew :app:testConsumerDebugUnitTest --tests "*WalletManagerTest.validateMnemonic_rejects_padding*"` | ❌ W0 | ⬜ pending |
| 30-W0-09 | Wave 0 | 0 | WALLET-MNEM | T-30-MNEM | Restore over non-zero wallet without backup throws BackupRequiredException | unit | `./gradlew :app:testConsumerDebugUnitTest --tests "*WalletManagerTest.restore_forces_backup*"` | ❌ W0 | ⬜ pending |
| 30-W0-10 | Wave 0 | 0 | WALLET-KEYS | T-30-KEYS | HMAC-of-seed verified on every getMnemonic(); mismatch throws | unit | `./gradlew :app:testConsumerDebugUnitTest --tests "*WalletManagerTest.hmac_integrity*"` | ❌ W0 | ⬜ pending |
| 30-W0-11 | Wave 0 | 0 | WALLET-KEYS | T-30-KEYS | KeyPermanentlyInvalidatedException surfaces KeyInvalidatedException (routed to restore) | unit | `./gradlew :app:testConsumerDebugUnitTest --tests "*WalletManagerTest.key_invalidated_routes_to_restore*"` | ❌ W0 | ⬜ pending |
| 30-W0-12 | Wave 0 | 0 | Tx history (D-19) | — | RavencoinTxBuilder outgoing tx produces change output at currentIndex+1 fresh address | unit | `./gradlew :app:testConsumerDebugUnitTest --tests "*RavencoinTxBuilderTest.multiAddressSend_change_to_fresh_address*"` | ✅ extend | ⬜ pending |

*Status: ⬜ pending · ✅ green · ❌ red · ⚠️ flaky*

---

## Wave 0 Requirements

- [ ] `android/app/src/test/java/io/raventag/app/wallet/cache/WalletCacheDaoTest.kt` — in-memory SQLite tests for D-04 cache + D-20 reservation math
- [ ] `android/app/src/test/java/io/raventag/app/wallet/cache/ReservedUtxoDaoTest.kt` — reservation lifecycle (insert, cleanup on confirm, prune stale >48h)
- [ ] `android/app/src/test/java/io/raventag/app/wallet/subscription/SubscriptionParserTest.kt` — JSON-RPC frame routing (response id-match vs scripthash notification)
- [ ] `android/app/src/test/java/io/raventag/app/wallet/fee/FeeEstimatorTest.kt` — fallback to 0.01 RVN/kB on estimatefee=-1, on throw, and unit-conversion sanity
- [ ] `android/app/src/test/java/io/raventag/app/wallet/WalletManagerMnemonicTest.kt` — BIP39 whitespace normalization, forced-backup gate, HMAC integrity, key-invalidation path (new test file; WalletManager tests are currently absent)
- [ ] Extend `android/app/src/test/java/io/raventag/app/wallet/RavencoinTxBuilderTest.kt` — assert outgoing-tx change output address == changeAddress parameter (backs D-19 `cycled_sat`)

*Framework install:* none needed — JUnit 4 already wired via `androidx.test.ext:junit` + `androidx.test:runner`, BouncyCastle + SQLite available on test classpath.

---

## Manual-Only Verifications

| Behavior | Requirement | Why Manual | Test Instructions |
|----------|-------------|------------|-------------------|
| WorkManager `WalletPollingWorker` detects balance increase and fires `incoming_tx` notification after 15 minutes | WALLET-RECV (D-06) | Instrumented test requires a physical device + ElectrumX node + real WorkManager scheduler; connected tests are not wired in CI | 1. Install consumer APK on device. 2. Put app in background. 3. From another wallet, send 0.001 RVN to the current receive address. 4. Wait ≤15 min. 5. Expect system notification "Incoming transaction · +0.001 RVN · Pending". |
| `BiometricPrompt.CryptoObject` binds auth to mnemonic decrypt | WALLET-KEYS (D-15) | Requires biometric hardware (fingerprint or strong face) + `KeyguardManager` real device | 1. On a device with fingerprint enrolled, open MnemonicBackupScreen. 2. Tap "Reveal phrase". 3. Cancel prompt — expect no words shown. 4. Tap again, authenticate — expect 12/24 words visible. 5. Enroll a new fingerprint in system Settings. 6. Re-open app, tap Reveal — expect "Device security changed" dialog routing to restore. |
| TLS TOFU fingerprint quarantine (1h) on mismatch | WALLET-BAL, WALLET-RECV (D-11) | Requires triggering a cert rotation or mocked TLS; cannot fit the quick-run unit path | 1. Connect once to a pinned node (pin saved). 2. Tamper `electrum_certificates.db` entry (swap fingerprint). 3. Restart app — expect quarantine, yellow connection pill if fallbacks exist, red if all fail. 4. Wait 1h (or roll system clock). 5. Expect retry. |
| `FLAG_SECURE` blocks screenshots on MnemonicBackupScreen | WALLET-MNEM (Security Domain) | Screenshot behavior is OS-level; cannot unit-test | 1. Open MnemonicBackupScreen. 2. Attempt screenshot (Power + Volume-Down). 3. Expect OS toast "Can't take screenshot due to security policy". |
| Scripthash subscription reconnects on network change (WiFi → LTE) | WALLET-RECV (D-05, Pitfall 2) | Requires real network transition | 1. Open WalletScreen on WiFi, confirm pill green. 2. Disable WiFi, wait for LTE. 3. Within 60s expect pill yellow → green after reconnect. 4. Send a tiny incoming tx — expect in-app Snackbar within seconds. |
| Battery-saver chip appears when `PowerManager.isPowerSaveMode()` is true and periodic poll pauses | Power Save (D-26, D-28) | System PowerManager state requires a real device + Settings toggle | 1. Open WalletScreen, note green pill + 30s poll. 2. Enable Battery Saver in Settings. 3. Return to WalletScreen. 4. Expect amber "Battery saver · manual refresh" chip; no 30s poll ticks in logs; subscription still open. |

---

## Validation Sign-Off

- [ ] All tasks have `<automated>` verify or Wave 0 dependencies
- [ ] Sampling continuity: no 3 consecutive tasks without automated verify
- [ ] Wave 0 covers all MISSING references
- [ ] No watch-mode flags (no `--watch`, no `testWatch`)
- [ ] Feedback latency < 60s
- [ ] `nyquist_compliant: true` set in frontmatter

**Approval:** pending
