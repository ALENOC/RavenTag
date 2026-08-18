# RavenTag Security Remediation Report

## Scope and status

This report records implementation evidence for the findings RT-SEC-001 through RT-SEC-020 from the 2026-08-18 adversarial security audit. It is a remediation record, **not** a `SECURITY PASS`, penetration-test certificate, or release approval. Independent dynamic/adversarial verification remains assigned to Codex after merge.

Audit baseline SHA: `96807a39d6e18a13f1412f4425f81df595ed158f`.

The final pre-merge CI gate requires an exact backend install/build/test/audit, Android compilation/JVM tests, and whole-tree static security invariants. Hardware-, network-adversary-, and instrumented-device checks are deferred to `CODEX_DYNAMIC_SECURITY_TEST_PLAN.md`.

## Finding-by-finding evidence

### RT-SEC-001 — ElectrumX TOFU certificate mismatch
**Status:** FIXED IN CODE; dynamic MITM/rotation test deferred.

**Files:** `android/app/src/main/java/io/raventag/app/wallet/TofuTrustManager.kt`, `RavencoinPublicNode.kt`, `SubscriptionManager.kt`.

**Invariant:** a stored certificate pin mismatch is a hard failure. The connection path cannot silently replace/re-pin a previously trusted certificate merely because a new certificate was presented.

**Validation:** whole-tree review/static gate plus Android compile/JVM suite. Codex must exercise real first-use, unchanged cert, legitimate rotation, and active MITM cases.

### RT-SEC-002 — Malicious relay-fee / implicit MAX transaction behavior
**Status:** FIXED AND JVM-TESTED.

**Files:** `FeeSafetyPolicy.kt`, `RavencoinPublicNode.kt`, `RavencoinTxBuilder.kt`, `WalletManager.kt`, `SendRvnScreen.kt`, `MainActivity.kt`, `FeeSafetyPolicyTest.kt`, `RavencoinTxBuilderTest.kt`.

**Invariant:** relay fee input is constrained by local policy; a normal send never reduces the recipient amount to make an otherwise-insolvent transaction fit. MAX is an explicit UI/application intent propagated end-to-end.

**Validation:** fee-policy and builder regression tests; static gate rejects `isMaxSend`, legacy amount-reduction semantics, and loss of the explicit MAX parameter. Malicious live ElectrumX responses remain a dynamic Codex test.

### RT-SEC-003 — Fail-open biometric startup authentication
**Status:** FIXED IN CODE.

**Files:** `MainActivity.kt`, `BiometricGate.kt`.

**Invariant:** unavailable hardware, enrollment failure, cancellation, lockout, prompt exception, and authentication error never become authenticated state. The only `authPassed=true` paths are a real authentication success and the explicitly non-secret no-wallet state.

**Validation:** whole-tree static auth assignment gate and Android compile/JVM suite. Device-specific BiometricPrompt errors remain dynamic.

### RT-SEC-004 — Recovery phrase not cryptographically auth-bound
**Status:** FIXED IN CODE; Android Keystore/Biometric instrumentation deferred.

**Files:** `WalletManager.kt`, `BiometricGate.kt`, `MnemonicBackupScreen.kt`, `MnemonicExporter.kt`, `WalletManagerTest.kt`.

**Invariant:** wallet seed/signing storage remains separate from the recovery-reveal key. Persisted mnemonic reveal uses dedicated alias `raventag_mnemonic_reveal_key_v1`, AES-GCM, and Android Keystore `setUserAuthenticationRequired(true)`. API 30+ uses `AUTH_BIOMETRIC_STRONG | AUTH_DEVICE_CREDENTIAL`; API 26-29 uses the authentication-validity compatibility API. There is no production general-wallet-key `getMnemonic()` bypass.

**Migration invariant:** authenticate first; decrypt and integrity-check the legacy mnemonic; encrypt with the auth-bound key; locally verify; persist; read back and decrypt the persisted copy; only then remove legacy mnemonic ciphertext/IV/integrity tag. If interrupted after verified persistence but before cleanup, the next successful auth-bound reveal verifies the new copy and completes cleanup. Errors fail closed and retain the legacy copy.

**Validation:** compile/static assertions verify the dedicated auth-required key and absence of a bypass. Real Keystore user-auth enforcement, cancellation, key invalidation, reboot/timeout, and migration on physical/emulated Android are explicitly deferred to Codex.

### RT-SEC-005 — Legacy plaintext secret persistence
**Status:** FIXED AND JVM-TESTED.

**Files:** `MainActivity.kt`, `security/LegacySecretMigration.kt`, `LegacySecretMigrationTest.kt`, `AdminKeyStorage.kt`.

**Invariant:** new application secrets are stored in `raventag_secure_v2` (`EncryptedSharedPreferences`) or the dedicated encrypted `AdminKeyStorage`. Only the historically secret-bearing keys `admin_key`, `operator_key`, `initial_master_key`, and `pinata_jwt` are migrated from legacy `raventag_secure`. `kubo_node_url` is non-secret configuration and is moved to normal application preferences rather than included in the secret whitelist.

**Migration invariant:** source is removed only after encrypted destination write and read-back equality. Secure-store failure leaves source untouched. A run interrupted after destination verification but before deletion is safely resumable.

**Validation:** JVM tests cover no legacy values, successful migration, encrypted-store failure, interrupted migration, and repeated/idempotent migration. Final CI statically rejects new duplicate admin/Kubo writes into secret storage. Real upgrade tests remain in the Codex plan.

### RT-SEC-006 — Insufficient Ravencoin address validation
**Status:** FIXED AND JVM-TESTED.

**Files:** `RavencoinTxBuilder.kt`, `RavencoinTxBuilderTest.kt`.

**Invariant:** Base58Check checksum, payload structure, canonical encoding, and Ravencoin mainnet P2PKH version `0x3C` are enforced before transaction construction. Foreign-network Bitcoin-style addresses are rejected.

**Validation:** address regression vector plus final static invariant.

### RT-SEC-007 — Registry poisoning with unrelated valid txid
**Status:** FIXED AND BACKEND-TESTED.

**Files:** `backend/src/routes/registry.ts`, `services/registry-issuance.ts`, `services/registry-issuance.test.ts`, `services/ravencoin.ts`.

**Invariant:** registry claims are accepted only when `getrawtransaction(..., true)` semantics prove the transaction actually creates the claimed Ravencoin asset with matching issuance type/name, required canonical burn, and owner-token semantics where applicable.

**Validation:** backend tests cover canonical root issuance, ordinary/unrelated tx rejection, asset-name mismatch, type mismatch, missing burn, missing owner token, and valid unique issuance. Real mainnet transaction corpus is deferred to Codex.

### RT-SEC-008 — Concurrent UTXO spend race / reservation after signing
**Status:** FIXED IN CODE AND REGRESSION-GATED.

**Files:** `WalletManager.kt`, `cache/ReservedUtxoDao.kt`, transaction tests.

**Invariant:** every on-device financial signing path enters a shared signing mutex and obtains durable `CONFLICT_ABORT` reservations before any transaction builder/signature invocation. The reservation is rebound to the locally computed txid before network I/O; post-sign ambiguous network failure retains the reservation.

**Validation:** Android compile/JVM suite and whole-tree gate reject direct `tx.hex` broadcast bypasses and require reservation-before-build ordering. True simultaneous coroutine/process/device stress is deferred to Codex.

### RT-SEC-009 — Mnemonic clipboard exposure
**Status:** FIXED IN CODE.

**Files:** `MnemonicBackupScreen.kt`, `MnemonicExporter.kt`.

**Invariant:** no Copy All/clipboard operation exists for the recovery phrase; sensitive buffers are held as `CharArray` where practical and cleared on disposal. `FLAG_SECURE` protects the display.

**Validation:** final static gate scans the mnemonic reveal components for clipboard APIs.

### RT-SEC-010 — Mutable CI/action/container supply-chain references
**Status:** FIXED IN REPOSITORY WORKFLOWS.

**Files:** `.github/workflows/qwen-dispatch.yml`, `qwen-invoke.yml`, `qwen-review.yml`, `qwen-scheduled-triage.yml`, `qwen-triage.yml`.

**Invariant:** Qwen code actions are immutable commit-SHA references and security-sensitive Qwen/MCP containers use immutable digests; unnecessary OIDC permission was removed.

**Validation:** final whole-tree workflow scan rejects mutable Qwen action/container references.

### RT-SEC-011 — NFC replay counter race
**Status:** FIXED LATO CODICE; deployment topology must preserve single authoritative replay state or equivalent serialization.

**Files:** `backend/src/middleware/cache.ts` and NFC verification path.

**Invariant:** replay counter compare/update is atomic at the database boundary rather than a separate check-then-write race.

**Validation:** static/backend tests where available. Multi-instance contention and real-tag replay remain dynamic/operational tests.

### RT-SEC-012 — Android global cleartext networking
**Status:** FIXED.

**Files:** `AndroidManifest.xml`, `res/xml/network_security_config.xml`.

**Invariant:** cleartext traffic is denied globally; there is no global cleartext exception.

**Validation:** final static gate.

### RT-SEC-013 — `/verify` deep-link host trust
**Status:** FIXED.

**Files:** `NfcReader.kt`, Android manifest/app-link configuration.

**Invariant:** `/verify` is accepted only from the explicit RavenTag authorized host set, not merely by matching a path on an arbitrary HTTPS host.

**Validation:** static/JVM validation; malicious real intents remain in Codex plan.

### RT-SEC-014 — Unchecked NDEF payload indexing
**Status:** FIXED.

**Files:** `NfcReader.kt`.

**Invariant:** NDEF payload parsing checks length/structure before indexing; no unchecked `payload[0]` path remains.

**Validation:** final static gate and Android JVM suite.

### RT-SEC-015 — IPFS redirect policy bypass
**Status:** FIXED.

**Files:** `backend/src/services/ipfs.ts`, Android `RpcClient.kt`/IPFS client path.

**Invariant:** validation is not bypassed by transparent HTTP/HTTPS redirect following; redirect targets must not silently escape the intended policy.

**Validation:** static gate requires redirect following disabled on the relevant client. Malicious redirect chains remain a Codex test.

### RT-SEC-016 — Backend dependency advisories
**Status:** FIXED BY COMPATIBLE DEPENDENCY REFRESH; final gate requires zero npm audit findings at low-or-higher severity.

**Files:** `backend/package.json`, `backend/package-lock.json`.

**Pre-refresh exact audit:** 9 vulnerabilities: 4 high, 4 moderate, 1 low.

- `form-data` (direct, high, GHSA-hmw2-7cc7-3qxx): multipart CRLF injection; backend IPFS multipart code makes this relevant. Fixed by `4.0.6`.
- `multer` (direct, high): nested-field DoS and aborted-upload cleanup advisories; source import was not found in the current backend, reducing present reachability, but the declared dependency is updated to `2.2.0` rather than relying on non-use.
- `express` (direct, moderate): affected through vulnerable `qs`; request routing/parsing is network reachable. Updated within major 4 to `4.22.2`.
- `express-rate-limit` (direct, moderate): affected through `ip-address`; rate-limit middleware is on public/admin request paths. Updated within major 8 to `8.6.2`.
- `body-parser` (transitive, moderate aggregate): reachable through Express JSON request parsing; fixed by dependency refresh.
- `qs` (transitive, moderate): Express/body-parser dependency; fixed by Express dependency refresh.
- `path-to-regexp` (transitive via Express, high): Express routing dependency; the lock is explicitly refreshed to patched `0.1.13`, which remains compatible with Express 4.22.2, avoiding a breaking Express 5 upgrade.
- `ip-address` (transitive, high aggregate): express-rate-limit dependency; fixed by express-rate-limit dependency refresh.
- `esbuild` (transitive, low): pulled through `tsx`, used for dev/test tooling rather than production server runtime; fixed by updating `tsx` within major 4.

`axios` is also refreshed within major 1. No `npm audit fix --force` is used. The final gate executes `npm ci`, TypeScript build, backend tests, and `npm audit --audit-level=low`; merge is blocked if any advisory remains.

### RT-SEC-017 — Default-branch governance
**Status:** OPERATIONAL REMEDIATION REQUIRED.

**Evidence:** remote `master` branch protection/ruleset is not enabled and the available repository connector does not expose an authorized branch-protection/ruleset mutation in this workflow.

**Required operator action:** enable branch protection/ruleset for `master` (PR requirement, required status checks including the permanent security gate, restrict force-push/deletion, appropriate review policy).

### RT-SEC-018 — Committed Android local SDK path
**Status:** FIXED.

**Files:** `.gitignore`; `android/app/local.properties` removed from the tracked tree.

**Invariant:** machine-local Android SDK paths are not repository content.

**Validation:** final secret/dangerous-pattern gate rejects tracked `local.properties`/signing/environment files.

### RT-SEC-019 — BIP39 normalization/KDF compatibility
**Status:** FIXED AND JVM-TESTED.

**Files:** `Bip39Kdf.kt`, `Bip39KdfTest.kt`, `WalletManager.kt`.

**Invariant:** mnemonic/passphrase inputs use BIP39 NFKD normalization and production seed derivation delegates to the same vector-tested implementation.

**Validation:** official BIP39 vector and Unicode normalization-equivalence JVM tests. Full BIP32/BIP44/transaction vector corpus remains in Codex plan.

### RT-SEC-020 — Backup key separation / retention
**Status:** FIXED IN CODE AND DEPLOYMENT CONFIG.

**Files:** `backend/src/services/backup.ts`, `backend/src/index.ts`, `docker-compose.yml`, `.env.example`, `docs/deploy/en.md`.

**Invariant:** backup interval is 24 hours and retention is 7 encrypted snapshots. Backup encryption material is a dedicated `backup_encryption_key` / `INTERNAL_BACKUP_ENCRYPTION_KEY_FILE`, never `ADMIN_KEY`/`ADMIN_KEY_FILE`. The in-process scheduler is opt-in and fails closed when enabled without its dedicated key path. Compose mounts admin auth and backup encryption as different secrets; documentation explicitly forbids key reuse.

**Validation:** final whole-tree gate asserts interval/retention and distinct paths, then performs global secret-name review.

## Validation executed by the final gate

The merge gate runs on the exact PR HEAD:

```text
backend:
  npm ci --ignore-scripts
  npm run build
  npm test
  npm audit --audit-level=low

android:
  ./gradlew compileBrandDebugKotlin compileConsumerDebugKotlin
  ./gradlew test

repository:
  whole-tree security invariant assertions
  immutable Qwen/MCP reference assertions
  tracked-secret/dangerous-pattern scan
  git diff --check
```

A green gate proves only these automated checks. It does not substitute for the dynamic adversarial program below.
