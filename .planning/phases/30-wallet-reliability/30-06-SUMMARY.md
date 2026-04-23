---
phase: 30
plan: 06
subsystem: wallet / security (Android)
tags: [mnemonic-safety, biometric, keystore, hmac, flag-secure, restore-gate]
requires:
  - 30-01 (Wave 0 test scaffolding and companion TODOs)
provides:
  - "BiometricGate: CryptoObject-bound BiometricPrompt suspend wrapper"
  - "MnemonicExporter: Result<CharArray> facade, zero-fill discipline"
  - "WalletManager: HMAC-of-seed + HMAC-of-mnemonic integrity, whitespace-normalized validateMnemonic, KeyPermanentlyInvalidatedException routing, backup-gated restore"
  - "MnemonicBackupScreen: FLAG_SECURE + biometric cover card + backup_completed flag"
  - "RestoreWalletConfirmDialog: D-14 destructive-confirm dialog with forced-backup variant"
affects:
  - android/app/src/main/java/io/raventag/app/security/BiometricGate.kt
  - android/app/src/main/java/io/raventag/app/security/MnemonicExporter.kt
  - android/app/src/main/java/io/raventag/app/wallet/WalletManager.kt
  - android/app/src/main/java/io/raventag/app/ui/screens/MnemonicBackupScreen.kt
  - android/app/src/main/java/io/raventag/app/ui/screens/WalletScreen.kt
  - android/app/src/main/java/io/raventag/app/ui/theme/AppStrings.kt
tech-stack:
  added:
    - androidx.biometric:biometric (already in libs.versions.toml; no gradle change)
  patterns:
    - BiometricPrompt + CryptoObject (D-15) binds auth to Keystore decrypt, not a boolean
    - HMAC-SHA256 via BouncyCastle, key material wrapped by Keystore AES-GCM
    - CharArray-only reveal path; caller zero-fills via java.util.Arrays.fill(it, ' ')
    - FLAG_SECURE via DisposableEffect for sensitive screens
key-files:
  created:
    - android/app/src/main/java/io/raventag/app/security/BiometricGate.kt
    - android/app/src/main/java/io/raventag/app/security/MnemonicExporter.kt
  modified:
    - android/app/src/main/java/io/raventag/app/wallet/WalletManager.kt
    - android/app/src/main/java/io/raventag/app/ui/screens/MnemonicBackupScreen.kt
    - android/app/src/main/java/io/raventag/app/ui/screens/WalletScreen.kt
    - android/app/src/main/java/io/raventag/app/ui/theme/AppStrings.kt
decisions:
  - "BIP39 checksum logic implemented inline in companion object (bip39ChecksumValidCompanion); no pre-existing named validator to delegate to"
  - "CharArray zero-fill uses ' ' (space, 0x20) per project convention D-16, not '\\u0000', to avoid raw-null literal issues"
  - "Setup-flow (pendingMnemonic non-null) skips CryptoObject binding because no ciphertext yet exists; cover card acts as tap-through"
  - "hasFunds detection uses ownedAssets?.size ?: 0 + walletBalance > 0.0 at WalletSetupCard onRestore call site"
  - "onNavigateToMnemonicBackup defaults to {} for backward compatibility; MainActivity wiring deferred to plan 30-08/30-10 where settings→restore navigation may surface"
metrics:
  duration: "~1h (resumed from mid-Task-4 deviation recovery)"
  completed: 2026-04-23
---

# Phase 30 Plan 06: Mnemonic Safety Summary

One-liner: HMAC-integrity + BiometricPrompt/CryptoObject reveal + FLAG_SECURE + D-14 restore-confirm dialog close the final mnemonic attack surface on Android.

## What shipped

### Task 1: `BiometricGate.kt` (commit `66afcf0`)
- `class BiometricGate(activity: FragmentActivity)` exposing `suspend fun decryptWithBiometric(cipher, ciphertext, titleRes, subtitleRes): ByteArray`
- Wraps `BiometricPrompt.authenticate(promptInfo, CryptoObject(cipher))` in `suspendCancellableCoroutine`
- `PromptInfo` uses `BIOMETRIC_STRONG or DEVICE_CREDENTIAL`, no negative button (androidx rejects the combination at runtime)
- `BiometricCancelledException(code, message)` surfaced on `onAuthenticationError`
- Stateless: cipher/ciphertext never stored on the gate

### Task 2: `WalletManager.kt` (commit `2124e5b`)
Wave 0 TODO bodies replaced, all four `WalletManagerMnemonicTest` cases GREEN.

| Wave 0 stub | Location in file | Notes |
|---|---|---|
| `validateMnemonic(input: String): List<String>` | companion @ line 271 | Normalizes `input.trim().split(Regex("\\s+"))`, rejects counts not in {12,15,18,21,24}, delegates to `bip39ChecksumValidCompanion` |
| `bip39ChecksumValidCompanion(words)` | companion @ line 284 | Full BIP39 word-to-index + SHA-256 checksum implementation (no pre-existing named validator was present to delegate to; logic implemented inline) |
| `checkRestorePreconditions(currentBalanceSat, hasBackedUp)` | companion (see `git diff 2124e5b`) | Throws `BackupRequiredException` when funds > 0 AND !backed-up |
| `computeSeedHmacForTest(seed, keyBytes)` | companion | BouncyCastle `HMac(SHA256Digest())`, pure function |
| `verifySeedHmac(seed, tag, keyBytes)` | companion | Constant-time `MessageDigest.isEqual`, zero-fills expected before return |
| `wrapKeystoreException<T>(block)` | companion @ line 368 | Inline catches ONLY `KeyPermanentlyInvalidatedException` and rethrows as `KeystoreInvalidatedException` |

Instance-level additions:
- `loadOrCreateHmacKeyBytes()` wraps 32 random bytes with existing Keystore AES-GCM key (prefs keys `KEY_HMAC_MATERIAL_CT` / `_IV`)
- `computeSeedHmac(seed)` and `verifySeedHmacInstance(seed, tag)` fetch + zero-fill the derived key bytes
- `suspend fun revealMnemonicCharsWithBiometric(gate)` @ line 1068: init-only `Cipher` in DECRYPT_MODE wrapped in `wrapKeystoreException`, passes ciphertext + cipher to `gate.decryptWithBiometric`, verifies HMAC on plaintext, returns CharArray and zero-fills intermediate ByteArray
- `storeSeed(seed, mnemonic)` @ 1003 and `storeMnemonic`-equivalent path writes HMAC; `getSeed()` @ 1042 + `getMnemonic()` @ 1022 verify HMAC post-decrypt; every `cipher.doFinal(...)` wrapped in `wrapKeystoreException`
- `restoreFromMnemonic` entry validates via new `validateMnemonic`, reads `backup_completed`, calls `checkRestorePreconditions`

**In-memory mnemonic cache audit:** `grep -nE '(cachedMnemonic|mnemonicCache|decryptedMnemonic|plaintextSeed|seedCache)' android/app/src/main/java/io/raventag/app/wallet/WalletManager.kt` returns **no matches** — none found pre-existing, none introduced.

### Task 3: `MnemonicExporter.kt` (commit `5191bb8`)
- `object MnemonicExporter` with single `suspend fun revealMnemonic(gate, wm): Result<CharArray>`
- Thin `runCatching { wm.revealMnemonicCharsWithBiometric(gate) }` wrapper — keeps UI decoupled from `WalletManager` for future hardening

### Task 4: `MnemonicBackupScreen.kt` + `AppStrings.kt` (commit `a51a991`)
- `DisposableEffect(Unit)` sets `FLAG_SECURE` on enter, clears on dispose
- Cover card visible when `revealed == null`: Fingerprint icon + title + body + "Reveal phrase" CTA
- CTA launches `revealWithBiometric()` helper that:
  - Setup-flow (prefill): uses `prefillMnemonic.toCharArray()` directly
  - Reveal-flow: wraps `MnemonicExporter.revealMnemonic(BiometricGate(activity), wm)`, maps `BiometricCancelledException` / `KeystoreInvalidatedException` / generic to snackbars and `onKeystoreInvalidated` callback
- `DisposableEffect(revealed) { onDispose { Arrays.fill(it, ' ') } }` zero-fills on screen leave and on revealed transition
- "I've saved it" button flips `backup_completed = true` SharedPref, zero-fills, then calls `onConfirmed`
- 20 new EN + IT entries in `AppStrings.kt` per UI-SPEC Copywriting Contract (biometric cover, restore dialog, device-security-changed, auth-canceled, invalid-phrase)

### Task 5: `WalletScreen.kt` + `AppStrings.kt` (commit `bee7a01`)
- Added file-private `RestoreWalletConfirmDialog(hasBackedUp, rvnAmount, assetsCount, onDismiss, onBackupFirst, onReplace)` composable
- `hasBackedUp == true` → destructive body with formatted `(%1$s RVN, %2$s assets)`, `NotAuthenticRed` "Replace wallet" CTA
- `hasBackedUp == false` → "Back up first" body, `RavenOrange` "Back up phrase first" CTA routes to MnemonicBackupScreen; Cancel still available
- Gate wired at `WalletSetupCard.onRestore`: `hasFunds = walletBalance > 0.0 || (ownedAssets?.size ?: 0) > 0` → defer to dialog, otherwise call `onRestoreWallet` directly
- New `onNavigateToMnemonicBackup: () -> Unit = {}` screen parameter (default keeps existing MainActivity call sites compiling unchanged)

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 1 - Bug] Fixed raw null-byte literal in `Arrays.fill(it, '\x00')`**
- **Found during:** Resume inspection of in-progress Task 4
- **Issue:** The on-disk `MnemonicBackupScreen.kt` contained two occurrences of the byte sequence `27 00 27` (`'` NUL `'`) — a raw null byte inside what was intended to be a char literal. Kotlin char literals require `' '`, not a raw NUL byte; this would fail to compile. `git diff` reported the file as binary because of the NUL bytes.
- **Fix:** Replaced both `'\x00'` raw-null literals with `' '` (space, 0x20) via a python bytes replacement, consistent with project convention D-16 on CharArray zero-fill.
- **Files modified:** `android/app/src/main/java/io/raventag/app/ui/screens/MnemonicBackupScreen.kt` (lines 86, 341)
- **Commit:** folded into `a51a991`

**2. [Rule 3 - Blocking] `onNavigateToMnemonicBackup` defaulted, not wired end-to-end**
- **Found during:** Task 5 wiring
- **Issue:** Plan asks to wire `onNavigateToMnemonicBackup` through MainActivity. The only restore entry in the current WalletScreen tree fires on `!hasWallet`, so the backup-first branch is unreachable in practice today.
- **Fix:** Added the parameter with a `{}` default so the dialog composes and compiles; MainActivity wiring is a separate concern for plans 30-08 / 30-10 which will surface a settings-driven restore entry with a non-empty wallet.
- **Rationale:** Avoided touching MainActivity navigation beyond scope; the dialog is ready to receive the callback when that path is added.

## Auth Gates
None triggered — all tasks autonomous.

## Threat Coverage
All T-30-MNEM-* and T-30-KEYS-01/02/04 `mitigate` entries from the plan `<threat_model>` are enforced:

| Threat | Mitigation site |
|---|---|
| T-30-MNEM-01 Info Disclosure (rooted SharedPrefs read) | AES-GCM-Keystore wrap + HMAC tamper-detect in `storeSeed`/`getSeed`/`storeMnemonic`/`getMnemonic` |
| T-30-MNEM-02 Screenshot/screen-recording | `FLAG_SECURE` DisposableEffect on `MnemonicBackupScreen` |
| T-30-MNEM-04 Tampered ciphertext | HMAC verify + `IntegrityException` on mismatch |
| T-30-MNEM-05 Restore overwrites funded wallet | `checkRestorePreconditions` + `RestoreWalletConfirmDialog` forced-backup variant |
| T-30-MNEM-06 Boolean-flag bypass of BiometricPrompt | `CryptoObject(cipher)` binding in `BiometricGate` — no auth, no plaintext |
| T-30-KEYS-01 Silent Keystore-invalidation | `wrapKeystoreException` → typed `KeystoreInvalidatedException` routed to UI |
| T-30-KEYS-02 Rogue fingerprint enrollment | `BIOMETRIC_STRONG` + re-auth on every reveal (fresh CryptoObject per call) |
| T-30-KEYS-04 Mnemonic retained in memory post-reveal | CharArray-only path + `Arrays.fill(it, ' ')` via DisposableEffect(revealed) onDispose |

## Verification Results
- `./gradlew :app:testConsumerDebugUnitTest --tests "*WalletManagerMnemonicTest*"` → BUILD SUCCESSFUL (all four tests green at commit 2124e5b)
- `./gradlew :app:assembleConsumerDebug` → BUILD SUCCESSFUL (final commit bee7a01)
- `grep -rP '—' <touched files>` → no matches

## Hand-offs
- **Plan 30-08:** `WalletScreen.RestoreWalletConfirmDialog` is in place; integrate the connection-pill + cached-state banners without touching the dialog. If plan 30-08 adds a settings→restore entry that's reachable with an existing wallet, wire `onNavigateToMnemonicBackup` in MainActivity at that time.
- **Plan 30-10:** perform the final em-dash audit sweep across all touched files from all Phase 30 plans (this plan is clean).

## MainActivity base class
Was already `FragmentActivity` before this plan (`class MainActivity : FragmentActivity()` @ line 2334). No change required.

## Commits
- `66afcf0` feat(30-06): add BiometricGate with CryptoObject-bound authentication
- `2124e5b` feat(30-06): extend WalletManager with HMAC integrity, validation, backup gate
- `5191bb8` feat(30-06): create MnemonicExporter zero-fill CharArray reveal wrapper
- `a51a991` feat(30-06): extend MnemonicBackupScreen with biometric cover card, FLAG_SECURE, backup gate
- `bee7a01` feat(30-06): add RestoreWalletConfirmDialog + forced-backup gate on WalletScreen

## Self-Check: PASSED
- BiometricGate.kt: FOUND
- MnemonicExporter.kt: FOUND
- WalletManager.kt mutations: FOUND (validateMnemonic @271, wrapKeystoreException @368, revealMnemonicCharsWithBiometric @1068)
- MnemonicBackupScreen.kt: FOUND (FLAG_SECURE, BiometricGate, MnemonicExporter.revealMnemonic, Arrays.fill, backup_completed, Icons.Default.Fingerprint)
- WalletScreen.kt: FOUND (fun RestoreWalletConfirmDialog, Color(0xFF1A0000), hasBackedUp, backup_completed, NotAuthenticRed, RavenOrange)
- AppStrings.kt: FOUND (all 20 EN+IT keys from acceptance criteria)
- Commits 66afcf0, 2124e5b, 5191bb8, a51a991, bee7a01: all present in `git log`.
