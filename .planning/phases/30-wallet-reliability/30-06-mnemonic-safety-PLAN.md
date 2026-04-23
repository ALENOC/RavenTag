---
id: 30-06-mnemonic-safety
phase: 30
plan: 06
type: execute
wave: 2
depends_on:
  - 30-01-wave0-test-scaffolding
files_modified:
  - android/app/src/main/java/io/raventag/app/security/BiometricGate.kt
  - android/app/src/main/java/io/raventag/app/security/MnemonicExporter.kt
  - android/app/src/main/java/io/raventag/app/wallet/WalletManager.kt
  - android/app/src/main/java/io/raventag/app/ui/screens/MnemonicBackupScreen.kt
  - android/app/src/main/java/io/raventag/app/ui/screens/WalletScreen.kt
  - android/app/src/main/java/io/raventag/app/ui/theme/AppStrings.kt
autonomous: true
requirements:
  - WALLET-MNEM
  - WALLET-KEYS
threat_refs:
  - T-30-MNEM
  - T-30-KEYS
ui_spec_refs:
  - "UI-SPEC §Mnemonic reveal biometric gate (D-15)"
  - "UI-SPEC §Restore-over-wallet confirm dialog (D-14)"
  - "UI-SPEC §Copywriting Contract, Destructive / irreversible confirmations (Replace current wallet?, Authenticate to reveal phrase)"
  - "UI-SPEC §Copywriting Contract, Error states (Invalid recovery phrase, Device security changed)"
  - "UI-SPEC §Implementation Notes, Em-dash audit"

must_haves:
  truths:
    - "Mnemonic reveal on MnemonicBackupScreen is gated by BiometricPrompt bound to the Keystore decrypt operation via CryptoObject (D-15, not a boolean flag)"
    - "MnemonicBackupScreen sets FLAG_SECURE on enter and clears it on dispose, preventing screenshots of the words grid (RESEARCH Security Domain recommendation)"
    - "WalletManager.validateMnemonic normalizes arbitrary whitespace via input.trim().split(Regex(\"\\\\s+\")) and rejects word counts not in {12,15,18,21,24} (Pitfall 7)"
    - "WalletManager.getMnemonic/getSeed catch KeyPermanentlyInvalidatedException and rethrow as KeystoreInvalidatedException routed to the restore flow (D-15, Pitfall 3)"
    - "HMAC-SHA256 of the seed is stored alongside the ciphertext in raventag_wallet prefs (KEY_SEED_HMAC) and verified on every getSeed; mismatch throws IntegrityException (D-15, A9)"
    - "Restore-over-wallet is blocked with BackupRequiredException when current balance > 0 AND backup_completed flag is false (D-14)"
    - "No decrypted mnemonic is retained in any property / field / ViewModel state after the reveal flow completes; caller zero-fills the CharArray (D-16)"
    - "All new user-facing strings exist in stringsEn AND stringsIt with verbatim UI-SPEC Copywriting Contract text; no U+2014 em-dash anywhere"
  artifacts:
    - path: "android/app/src/main/java/io/raventag/app/security/BiometricGate.kt"
      provides: "BiometricPrompt + CryptoObject wrapper (suspendCancellableCoroutine) — BIOMETRIC_STRONG or DEVICE_CREDENTIAL"
      exports: ["BiometricGate", "BiometricCancelledException"]
    - path: "android/app/src/main/java/io/raventag/app/security/MnemonicExporter.kt"
      provides: "zero-fill-disciplined reveal wrapper returning CharArray (never String)"
      exports: ["MnemonicExporter"]
    - path: "android/app/src/main/java/io/raventag/app/wallet/WalletManager.kt"
      provides: "HMAC-of-seed integrity, whitespace-normalized validateMnemonic, KeyPermanentlyInvalidatedException routing, backup-gated restoreFromMnemonic, no in-memory cache"
    - path: "android/app/src/main/java/io/raventag/app/ui/screens/MnemonicBackupScreen.kt"
      provides: "biometric cover card + FLAG_SECURE window flag + EN/IT copy per UI-SPEC"
    - path: "android/app/src/main/java/io/raventag/app/ui/screens/WalletScreen.kt"
      provides: "RestoreWalletConfirmDialog composable + forced-backup gate wired before onRestoreWallet"
    - path: "android/app/src/main/java/io/raventag/app/ui/theme/AppStrings.kt"
      provides: "EN + IT entries for reveal/restore/error copy (UI-SPEC Copywriting Contract)"
  key_links:
    - from: "MnemonicBackupScreen Reveal button"
      to: "BiometricGate.decryptWithBiometric → WalletManager.getMnemonic"
      via: "MnemonicExporter.revealMnemonic"
      pattern: "BiometricGate"
    - from: "WalletManager.getSeed / getMnemonic"
      to: "HMAC verification + KeyPermanentlyInvalidatedException wrap"
      via: "wrapKeystoreException { ... } + verifySeedHmac"
      pattern: "KeyPermanentlyInvalidatedException"
    - from: "WalletScreen onRestoreWallet click"
      to: "RestoreWalletConfirmDialog (forced-backup variant when backup_completed=false)"
      via: "walletBalance > 0 || assetsCount > 0 gate"
      pattern: "RestoreWalletConfirmDialog"
    - from: "MnemonicBackupScreen composition"
      to: "window.setFlags(FLAG_SECURE, FLAG_SECURE) / clearFlags onDispose"
      via: "DisposableEffect(Unit) { ... onDispose { ... } }"
      pattern: "FLAG_SECURE"
---

<objective>
Deliver the mnemonic-safety hardening required by D-13/D-14/D-15/D-16 plus the RESEARCH Security Domain FLAG_SECURE recommendation and Pitfalls 3 + 7. This plan introduces the Android `BiometricPrompt` + `CryptoObject` gate that binds authentication to the actual Keystore decrypt operation (not a boolean), adds HMAC-of-seed integrity, normalizes BIP39 input, routes `KeyPermanentlyInvalidatedException` to a user-visible restore path, and enforces the D-14 forced-backup gate before restore-over-wallet.

Purpose: close the final security boundary of the Ravencoin HD wallet on Android. WALLET-MNEM + WALLET-KEYS depend entirely on this plan.
Output: two new files under `security/`, surgical edits to `WalletManager.kt`, the biometric cover card on `MnemonicBackupScreen`, a new `RestoreWalletConfirmDialog` composable on `WalletScreen`, and EN+IT strings in `AppStrings.kt` drawn verbatim from UI-SPEC §Copywriting Contract.

Hard constraint (D-17): we do NOT redesign consolidation. This plan does not touch `RavencoinTxBuilder.kt` or the existing send path.
Hard constraint (D-16): no decrypted mnemonic / seed / private key may be retained in any property, ViewModel field, SavedStateHandle, or global cache after the reveal flow completes. CharArrays are zero-filled before return.
</objective>

<execution_context>
@$HOME/.claude/get-shit-done/workflows/execute-plan.md
@$HOME/.claude/get-shit-done/templates/summary.md
</execution_context>

<context>
@.planning/phases/30-wallet-reliability/30-CONTEXT.md
@.planning/phases/30-wallet-reliability/30-RESEARCH.md
@.planning/phases/30-wallet-reliability/30-PATTERNS.md
@.planning/phases/30-wallet-reliability/30-UI-SPEC.md
@.planning/phases/30-wallet-reliability/30-VALIDATION.md
@android/app/src/main/java/io/raventag/app/wallet/WalletManager.kt
@android/app/src/main/java/io/raventag/app/wallet/WalletExceptions.kt
@android/app/src/main/java/io/raventag/app/ui/screens/MnemonicBackupScreen.kt
@android/app/src/main/java/io/raventag/app/ui/screens/WalletScreen.kt
@android/app/src/main/java/io/raventag/app/ui/theme/AppStrings.kt
@android/app/src/main/java/io/raventag/app/MainActivity.kt
@android/app/src/test/java/io/raventag/app/wallet/WalletManagerMnemonicTest.kt

<interfaces>
**Already seeded by plan 30-01 (Wave 0)** — do NOT redeclare:
```kotlin
// android/app/src/main/java/io/raventag/app/wallet/WalletExceptions.kt
package io.raventag.app.wallet
class BackupRequiredException(msg: String = "backup required before restore") : RuntimeException(msg)
class IntegrityException(msg: String = "seed HMAC mismatch") : RuntimeException(msg)
class KeystoreInvalidatedException(cause: Throwable? = null) : RuntimeException("keystore invalidated", cause)
```

**Wave 0 TODO-stubbed helpers** on `WalletManager.Companion` that THIS plan must replace with real bodies (signatures are fixed by the unit tests):
```kotlin
@JvmStatic fun validateMnemonic(input: String): List<String>
@JvmStatic fun checkRestorePreconditions(currentBalanceSat: Long, hasBackedUp: Boolean)
@JvmStatic fun computeSeedHmacForTest(seed: ByteArray, keyBytes: ByteArray): ByteArray
@JvmStatic fun verifySeedHmac(seed: ByteArray, tag: ByteArray, keyBytes: ByteArray)
@JvmStatic inline fun <T> wrapKeystoreException(block: () -> T): T
```

**Signatures introduced by THIS plan (honored by downstream plans 30-08 and 30-10):**
```kotlin
// android/app/src/main/java/io/raventag/app/security/BiometricGate.kt
class BiometricGate(private val activity: androidx.fragment.app.FragmentActivity) {
    suspend fun decryptWithBiometric(
        cipher: javax.crypto.Cipher,
        ciphertext: ByteArray,
        titleRes: Int,
        subtitleRes: Int
    ): ByteArray
}
class BiometricCancelledException(val code: Int, message: String) : RuntimeException(message)

// android/app/src/main/java/io/raventag/app/security/MnemonicExporter.kt
object MnemonicExporter {
    /** Returns plaintext phrase as CharArray. Caller MUST Arrays.fill(result, '\u0000') when done. */
    suspend fun revealMnemonic(gate: BiometricGate, wm: io.raventag.app.wallet.WalletManager): Result<CharArray>
}

// Additions to WalletManager (instance methods):
suspend fun revealMnemonicCharsWithBiometric(gate: BiometricGate): CharArray
```

**Callers (downstream):**
- `MainActivity` already extends `FragmentActivity` (required for BiometricPrompt — verify at execution time; if not, add `FragmentActivity` to MainActivity class hierarchy in a minimal change).
- `MnemonicBackupScreen` obtains the `FragmentActivity` via `LocalContext.current as FragmentActivity` at composition time.

**SharedPreferences keys added to the EXISTING `raventag_wallet` prefs file (no new secrets file)**:
- `KEY_SEED_HMAC` — Base64 of HMAC-SHA256(seedBytes) using a secondary Keystore-wrapped HMAC key. 32 bytes decoded.
- `KEY_MNEMONIC_HMAC` — Base64 of HMAC-SHA256(mnemonic UTF-8 bytes), same key.
- `backup_completed` — Boolean flag, set to true when the user completes the MnemonicBackupScreen "I've saved it" flow.
</interfaces>
</context>

<tasks>

<task type="auto" tdd="true">
  <name>Task 1: Create BiometricGate.kt (suspend wrapper around BiometricPrompt + CryptoObject)</name>
  <files>
    android/app/src/main/java/io/raventag/app/security/BiometricGate.kt
  </files>
  <read_first>
    @.planning/phases/30-wallet-reliability/30-RESEARCH.md#L303-L343,
    @.planning/phases/30-wallet-reliability/30-RESEARCH.md#L623-L665,
    @.planning/phases/30-wallet-reliability/30-PATTERNS.md#L189-L223,
    @android/app/src/main/java/io/raventag/app/MainActivity.kt,
    @android/app/src/main/java/io/raventag/app/wallet/WalletManager.kt
  </read_first>
  <behavior>
    `BiometricGate(activity: FragmentActivity)` exposes a single suspend function `decryptWithBiometric(cipher, ciphertext, titleRes, subtitleRes): ByteArray`:
    - Wraps `BiometricPrompt.authenticate(promptInfo, CryptoObject(cipher))` in `suspendCancellableCoroutine` per RESEARCH Pattern 2 + Example 3.
    - `PromptInfo.Builder`: title from `titleRes`, subtitle from `subtitleRes`, `setAllowedAuthenticators(BIOMETRIC_STRONG or DEVICE_CREDENTIAL)`, negative button `null` (DEVICE_CREDENTIAL replaces it per androidx docs).
    - On `onAuthenticationSucceeded`: call `result.cryptoObject?.cipher!!.doFinal(ciphertext)` and `cont.resume(plaintext)`. If cryptoObject or cipher is null, resume with `IllegalStateException("no cipher bound")`.
    - On `onAuthenticationError(code, msg)`: resume with `BiometricCancelledException(code, msg.toString())`.
    - On cancellation of the coroutine: `cont.invokeOnCancellation { prompt.cancelAuthentication() }`.
    - Executor: `ContextCompat.getMainExecutor(activity)`.

    `BiometricCancelledException(val code: Int, message: String) : RuntimeException(message)` — public, caught by UI to show the snackbar "Authentication canceled".

    The class is stateless. Callers construct a fresh instance per reveal. Do NOT store `cipher` or `ciphertext` inside the class.
  </behavior>
  <action>
    Create `android/app/src/main/java/io/raventag/app/security/BiometricGate.kt` with exactly this content:

    ```kotlin
    package io.raventag.app.security

    import androidx.biometric.BiometricManager
    import androidx.biometric.BiometricPrompt
    import androidx.core.content.ContextCompat
    import androidx.fragment.app.FragmentActivity
    import javax.crypto.Cipher
    import kotlin.coroutines.resume
    import kotlin.coroutines.resumeWithException
    import kotlinx.coroutines.suspendCancellableCoroutine

    /**
     * D-15: binds BiometricPrompt authentication to a Keystore decrypt operation via
     * `BiometricPrompt.CryptoObject`. Authentication is NOT a boolean flag; no auth, no
     * plaintext.
     *
     * Caller constructs a fresh instance per reveal. Not thread-safe on purpose.
     */
    class BiometricGate(private val activity: FragmentActivity) {

        suspend fun decryptWithBiometric(
            cipher: Cipher,
            ciphertext: ByteArray,
            titleRes: Int,
            subtitleRes: Int
        ): ByteArray = suspendCancellableCoroutine { cont ->
            val prompt = BiometricPrompt(
                activity,
                ContextCompat.getMainExecutor(activity),
                object : BiometricPrompt.AuthenticationCallback() {
                    override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                        try {
                            val c = result.cryptoObject?.cipher
                                ?: return cont.resumeWithException(
                                    IllegalStateException("no cipher bound")
                                )
                            cont.resume(c.doFinal(ciphertext))
                        } catch (t: Throwable) {
                            cont.resumeWithException(t)
                        }
                    }

                    override fun onAuthenticationError(code: Int, msg: CharSequence) {
                        cont.resumeWithException(
                            BiometricCancelledException(code, msg.toString())
                        )
                    }
                }
            )
            val info = BiometricPrompt.PromptInfo.Builder()
                .setTitle(activity.getString(titleRes))
                .setSubtitle(activity.getString(subtitleRes))
                .setAllowedAuthenticators(
                    BiometricManager.Authenticators.BIOMETRIC_STRONG or
                        BiometricManager.Authenticators.DEVICE_CREDENTIAL
                )
                .build()
            prompt.authenticate(info, BiometricPrompt.CryptoObject(cipher))
            cont.invokeOnCancellation { prompt.cancelAuthentication() }
        }
    }

    class BiometricCancelledException(
        val code: Int,
        message: String
    ) : RuntimeException(message)
    ```

    Notes:
    - `androidx.biometric:biometric:1.1.0` is already declared in `libs.versions.toml` (RESEARCH Standard Stack line 126). No gradle change required.
    - Do NOT add a `.setNegativeButtonText(...)` call: with `DEVICE_CREDENTIAL` included, the androidx library rejects that combination at runtime (IllegalArgumentException).
    - Em-dash audit: `! grep -P '\u2014' android/app/src/main/java/io/raventag/app/security/BiometricGate.kt`
  </action>
  <verify>
    <automated>cd android && ./gradlew :app:compileConsumerDebugKotlin -q 2>&1 | tail -15</automated>
  </verify>
  <acceptance_criteria>
    - `test -f android/app/src/main/java/io/raventag/app/security/BiometricGate.kt`
    - `grep -q "class BiometricGate" android/app/src/main/java/io/raventag/app/security/BiometricGate.kt`
    - `grep -q "suspend fun decryptWithBiometric" android/app/src/main/java/io/raventag/app/security/BiometricGate.kt`
    - `grep -q "BIOMETRIC_STRONG" android/app/src/main/java/io/raventag/app/security/BiometricGate.kt`
    - `grep -q "DEVICE_CREDENTIAL" android/app/src/main/java/io/raventag/app/security/BiometricGate.kt`
    - `grep -q "CryptoObject(cipher)" android/app/src/main/java/io/raventag/app/security/BiometricGate.kt`
    - `grep -q "class BiometricCancelledException" android/app/src/main/java/io/raventag/app/security/BiometricGate.kt`
    - `! grep -P '\u2014' android/app/src/main/java/io/raventag/app/security/BiometricGate.kt`
    - `cd android && ./gradlew :app:compileConsumerDebugKotlin` exits 0.
  </acceptance_criteria>
  <done>BiometricGate compiles, binds auth to decrypt via CryptoObject, surfaces BIOMETRIC_STRONG or DEVICE_CREDENTIAL, no em dashes.</done>
</task>

<task type="auto" tdd="true">
  <name>Task 2: Extend WalletManager — HMAC-of-seed integrity + whitespace normalization + KeyPermanentlyInvalidatedException routing + backup-gate + remove in-memory mnemonic cache</name>
  <files>
    android/app/src/main/java/io/raventag/app/wallet/WalletManager.kt
  </files>
  <read_first>
    @.planning/phases/30-wallet-reliability/30-CONTEXT.md#L37-L45,
    @.planning/phases/30-wallet-reliability/30-RESEARCH.md#L437-L447,
    @.planning/phases/30-wallet-reliability/30-RESEARCH.md#L486-L537,
    @.planning/phases/30-wallet-reliability/30-RESEARCH.md#L723-L741,
    @.planning/phases/30-wallet-reliability/30-PATTERNS.md#L367-L376,
    @android/app/src/main/java/io/raventag/app/wallet/WalletManager.kt,
    @android/app/src/main/java/io/raventag/app/wallet/WalletExceptions.kt,
    @android/app/src/test/java/io/raventag/app/wallet/WalletManagerMnemonicTest.kt
  </read_first>
  <behavior>
    Wave 0 left five companion TODO stubs (see `<interfaces>`). This task replaces each with the real implementation and extends the existing instance methods that touch Keystore.

    1) `validateMnemonic(input: String): List<String>` — normalize whitespace via `input.trim().split(Regex("\\s+"))`; reject counts not in `setOf(12, 15, 18, 21, 24)` with `IllegalArgumentException("invalid word count: ${words.size}")`; run the existing BIP39 checksum logic on the normalized list (reuse whatever helper currently lives in WalletManager, e.g. `bip39ChecksumValid(words)`; if that helper is private, invoke it via the companion object's internal scope). Return the normalized `List<String>` on success.

    2) `checkRestorePreconditions(currentBalanceSat: Long, hasBackedUp: Boolean)` — if `currentBalanceSat > 0L && !hasBackedUp` throw `BackupRequiredException("Current wallet has $currentBalanceSat sat and has not been backed up")`. Otherwise return Unit.

    3) `computeSeedHmacForTest(seed: ByteArray, keyBytes: ByteArray): ByteArray` — use BouncyCastle `HMac(SHA256Digest())`:
       ```kotlin
       val mac = org.bouncycastle.crypto.macs.HMac(org.bouncycastle.crypto.digests.SHA256Digest())
       mac.init(org.bouncycastle.crypto.params.KeyParameter(keyBytes))
       mac.update(seed, 0, seed.size)
       val out = ByteArray(mac.macSize)
       mac.doFinal(out, 0)
       return out
       ```
       Both `computeSeedHmacForTest` and the production `computeSeedHmac(seed)` (instance method) share this logic. The test-only variant takes the key as bytes for determinism; the production variant fetches the HMAC key from the Keystore (see step 5 below).

    4) `verifySeedHmac(seed: ByteArray, tag: ByteArray, keyBytes: ByteArray)` — compute HMAC and compare with constant-time `java.security.MessageDigest.isEqual(expected, tag)`; on mismatch throw `IntegrityException("seed HMAC mismatch")`. Return Unit on match.

    5) `wrapKeystoreException<T>(block: () -> T): T` — the inline function catches ONLY `android.security.keystore.KeyPermanentlyInvalidatedException` (and nothing else) and rethrows as `KeystoreInvalidatedException(cause = e)`. All other exceptions pass through unchanged. Because it is inline + reified? No — it is `inline fun <T>` only (no reified). Place on `WalletManager.Companion` per Wave 0 contract.

    Instance-level changes:

    6) HMAC key provisioning — introduce a second Keystore AES-GCM key, alias `raventag_wallet_hmac_key` (distinct from the existing alias). Use the same spec as the existing `getOrCreateAndroidKey()` minus any biometric binding (StrongBox when available, `setUnlockedDeviceRequired(true)` on API 28+). This key is used to derive 32 raw key bytes via AES-GCM-encrypting a fixed 32-byte input (or simpler: use HKDF via BouncyCastle — but the RESEARCH A9 / Don't Hand-Roll approach is "use a second Keystore-wrapped AES-GCM key as the HMAC key material"). Implement this way:
       - On first use, generate 32 random bytes, AES-GCM-encrypt them with the existing mnemonic Keystore key, and store the ciphertext + IV in the `raventag_wallet` prefs under key `KEY_HMAC_MATERIAL_CT` / `KEY_HMAC_MATERIAL_IV`.
       - To compute HMAC, decrypt the stored material to get 32 raw bytes, use them as the BouncyCastle HMAC key, then zero-fill the local `ByteArray` after use.
       - Rationale: avoids the trap of exposing a Keystore-bound HMAC key through `javax.crypto.Mac`, which requires a key that can be extracted from the Keystore (AES can't be). BouncyCastle `HMac` takes raw bytes — we bridge through the decrypt step.

    7) Store/verify HMAC in the existing `storeSeed(seed: ByteArray)` / `getSeed()` / `storeMnemonic(mnemonic: String)` / `getMnemonic()` methods:
       - After `encrypt(seed, iv)` produces ciphertext, compute `hmac = computeSeedHmac(seed)` (production variant) and store Base64(hmac) under `KEY_SEED_HMAC`.
       - In `getSeed()`, after decrypt, compute HMAC of the plaintext, verifySeedHmac against the stored tag; on mismatch throw `IntegrityException` (no attacker has a usable wallet).
       - Same for mnemonic under `KEY_MNEMONIC_HMAC`.

    8) Wrap every `cipher.doFinal(...)` call site in `wrapKeystoreException { ... }`. Concretely: `getSeed()`, `getMnemonic()`, `storeSeed()` (doFinal on encrypt), `storeMnemonic()`. The wrap converts `KeyPermanentlyInvalidatedException` into `KeystoreInvalidatedException` which the UI surfaces as the "Device security changed" dialog.

    9) BIP39 whitespace normalization — the existing `validateMnemonic` (if any) at the ~line 818 region per RESEARCH Pitfall 7 must use `input.trim().split(Regex("\\s+"))` before BIP39 processing. The companion shim replaces/wraps the existing instance / companion variant. Concretely: if the existing signature is `fun validateMnemonic(phrase: String): Boolean`, create a new `@JvmStatic fun validateMnemonic(input: String): List<String>` that calls the existing boolean validator on the normalized list and returns the normalized list on success / throws on failure. Retain the boolean variant as a thin shim for any existing callers; update all call sites to use the new list-returning variant where the normalized list is needed.

    10) `restoreFromMnemonic(phrase: String)` — BEFORE any Keystore rewrite:
        - Compute `currentBalanceSat` via `ReservedUtxoDao.sumReservedSat()` + latest cached balance from `WalletCacheDao.readState()?.balanceSat ?: 0L` (or read from SharedPreferences "wallet_poll.poll_rvn_sat" if the reliability DB is not yet initialized). A simple proxy is acceptable: read the last-known balance from the wallet state cache.
        - Read `hasBackedUp = prefs.getBoolean("backup_completed", false)`.
        - Call `checkRestorePreconditions(currentBalanceSat, hasBackedUp)`. On throw, propagate `BackupRequiredException` to the UI.
        - Then validate the phrase via the new `validateMnemonic`; on failure propagate `IllegalArgumentException`.
        - Then proceed with the existing restore logic.

    11) Remove any in-memory mnemonic cache (D-16). AUDIT: search WalletManager.kt for properties of type `String?`, `ByteArray?`, `CharArray?` that hold decrypted mnemonic/seed. Candidates: `private var cachedMnemonic: String? = null`, `private val mnemonicCache: ...`, any `companion object` field that shadows the decrypted value. DELETE them. Ensure every caller re-decrypts via `getMnemonic()` / `getSeed()`. Add acceptance criterion: `! grep -nE '(cachedMnemonic|mnemonicCache|decryptedMnemonic|plaintextSeed|seedCache)' android/app/src/main/java/io/raventag/app/wallet/WalletManager.kt`.

    12) New instance method `suspend fun revealMnemonicCharsWithBiometric(gate: BiometricGate): CharArray`:
        - Build the Cipher in `DECRYPT_MODE` with the Keystore key + stored IV (re-use the existing `decrypt(...)` scaffolding BUT without calling `doFinal` — stop after `cipher.init`).
        - Catch `KeyPermanentlyInvalidatedException` at init time via `wrapKeystoreException`.
        - Call `gate.decryptWithBiometric(cipher, storedMnemonicCiphertext, R.string.biometricRevealTitle, R.string.biometricRevealSubtitle)` → ByteArray.
        - Verify HMAC on the decrypted plaintext.
        - Convert `ByteArray` (UTF-8) to `CharArray` via `String(plaintext, Charsets.UTF_8).toCharArray()`. Immediately zero-fill the intermediate `ByteArray` via `Arrays.fill(plaintext, 0)`.
        - Return the `CharArray`. **Caller** (MnemonicExporter / UI) is responsible for zero-filling the returned CharArray after display.

    **Em-dash audit** on touched file: `! grep -P '\u2014' android/app/src/main/java/io/raventag/app/wallet/WalletManager.kt`.
  </behavior>
  <action>
    Read `WalletManager.kt` fully (~2102 lines per RESEARCH). Identify these landmarks:
    - Existing `encrypt(bytes: ByteArray): Pair<ByteArray, ByteArray>` / `decrypt(enc: ByteArray, iv: ByteArray): ByteArray`
    - Existing `getOrCreateAndroidKey(): SecretKey`
    - Existing `storeSeed`, `getSeed`, `storeMnemonic`, `getMnemonic` methods
    - Existing `validateMnemonic` (if present) at ~line 818
    - Existing `restoreFromMnemonic` entry point
    - Existing SharedPreferences file name (`raventag_wallet`) and key constants

    Make minimal surgical edits per the behavior section. Rules of engagement:
    - Do NOT reorganize existing methods. Add new helpers in a single block at the bottom of the class (and in the companion object block) to minimize diff size.
    - Do NOT change existing method signatures except where the behavior section explicitly requires (the new `validateMnemonic` companion is a NEW signature; retain the old boolean one as a thin shim if it exists).
    - Do NOT touch `RavencoinTxBuilder.kt` (D-17 hard rule).

    Companion block replacement (replace the five Wave 0 TODOs):
    ```kotlin
    companion object {
        // --- Existing helpers, do not delete ---
        // ... (whatever Wave 0 added as TODOs + pre-existing)

        private val VALID_WORD_COUNTS = setOf(12, 15, 18, 21, 24)

        @JvmStatic
        fun validateMnemonic(input: String): List<String> {
            val words = input.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
            require(words.size in VALID_WORD_COUNTS) {
                "invalid word count: ${words.size}"
            }
            // Run the existing BIP39 checksum logic on the normalized list.
            // If the file already has `fun bip39ChecksumValid(words: List<String>): Boolean`,
            // call it directly. Otherwise promote the existing logic from its private scope.
            require(bip39ChecksumValidCompanion(words)) { "BIP39 checksum failed" }
            return words
        }

        // Thin internal shim: if the file already has a BIP39 checksum validator, delegate.
        // If not, the body below must port the existing word-to-index + checksum SHA-256 logic.
        internal fun bip39ChecksumValidCompanion(words: List<String>): Boolean {
            // Call into the existing non-companion validator. If the existing method is named
            // differently, adjust this delegation to match — document the exact method name
            // discovered during execution in the SUMMARY.
            return io.raventag.app.wallet.WalletManager.bip39ChecksumValid(words)
        }

        @JvmStatic
        fun checkRestorePreconditions(currentBalanceSat: Long, hasBackedUp: Boolean) {
            if (currentBalanceSat > 0L && !hasBackedUp) {
                throw BackupRequiredException(
                    "Current wallet has $currentBalanceSat sat and has not been backed up"
                )
            }
        }

        @JvmStatic
        fun computeSeedHmacForTest(seed: ByteArray, keyBytes: ByteArray): ByteArray {
            val mac = org.bouncycastle.crypto.macs.HMac(
                org.bouncycastle.crypto.digests.SHA256Digest()
            )
            mac.init(org.bouncycastle.crypto.params.KeyParameter(keyBytes))
            mac.update(seed, 0, seed.size)
            val out = ByteArray(mac.macSize)
            mac.doFinal(out, 0)
            return out
        }

        @JvmStatic
        fun verifySeedHmac(seed: ByteArray, tag: ByteArray, keyBytes: ByteArray) {
            val expected = computeSeedHmacForTest(seed, keyBytes)
            val ok = java.security.MessageDigest.isEqual(expected, tag)
            // zero-fill local expected before throwing or returning
            java.util.Arrays.fill(expected, 0)
            if (!ok) throw IntegrityException("seed HMAC mismatch")
        }

        @JvmStatic
        inline fun <T> wrapKeystoreException(block: () -> T): T {
            return try {
                block()
            } catch (e: android.security.keystore.KeyPermanentlyInvalidatedException) {
                throw KeystoreInvalidatedException(cause = e)
            }
        }
    }
    ```

    Instance-method additions (at the bottom of the class, before the companion):
    ```kotlin
    // D-15 HMAC key material (32 random bytes) encrypted under the existing Keystore AES key.
    private fun loadOrCreateHmacKeyBytes(): ByteArray {
        val prefs = context.getSharedPreferences("raventag_wallet", android.content.Context.MODE_PRIVATE)
        val existingCt = prefs.getString(KEY_HMAC_MATERIAL_CT, null)
        val existingIv = prefs.getString(KEY_HMAC_MATERIAL_IV, null)
        if (existingCt != null && existingIv != null) {
            val ct = android.util.Base64.decode(existingCt, android.util.Base64.NO_WRAP)
            val iv = android.util.Base64.decode(existingIv, android.util.Base64.NO_WRAP)
            return Companion.wrapKeystoreException { decrypt(ct, iv) }
        }
        val fresh = ByteArray(32).also { java.security.SecureRandom().nextBytes(it) }
        val (ct, iv) = Companion.wrapKeystoreException { encrypt(fresh) }
        prefs.edit()
            .putString(KEY_HMAC_MATERIAL_CT, android.util.Base64.encodeToString(ct, android.util.Base64.NO_WRAP))
            .putString(KEY_HMAC_MATERIAL_IV, android.util.Base64.encodeToString(iv, android.util.Base64.NO_WRAP))
            .apply()
        return fresh
    }

    private fun computeSeedHmac(seed: ByteArray): ByteArray {
        val keyBytes = loadOrCreateHmacKeyBytes()
        return try {
            Companion.computeSeedHmacForTest(seed, keyBytes)
        } finally {
            java.util.Arrays.fill(keyBytes, 0)
        }
    }

    private fun verifySeedHmacInstance(seed: ByteArray, tag: ByteArray) {
        val keyBytes = loadOrCreateHmacKeyBytes()
        try {
            Companion.verifySeedHmac(seed, tag, keyBytes)
        } finally {
            java.util.Arrays.fill(keyBytes, 0)
        }
    }

    suspend fun revealMnemonicCharsWithBiometric(
        gate: io.raventag.app.security.BiometricGate
    ): CharArray = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        val prefs = context.getSharedPreferences("raventag_wallet", android.content.Context.MODE_PRIVATE)
        val ctB64 = prefs.getString(KEY_MNEMONIC_ENC, null)
            ?: throw IllegalStateException("no mnemonic stored")
        val ivB64 = prefs.getString(KEY_MNEMONIC_IV, null)
            ?: throw IllegalStateException("no mnemonic iv stored")
        val ct = android.util.Base64.decode(ctB64, android.util.Base64.NO_WRAP)
        val iv = android.util.Base64.decode(ivB64, android.util.Base64.NO_WRAP)
        val cipher = Companion.wrapKeystoreException {
            javax.crypto.Cipher.getInstance("AES/GCM/NoPadding").apply {
                init(
                    javax.crypto.Cipher.DECRYPT_MODE,
                    getOrCreateAndroidKey(),
                    javax.crypto.spec.GCMParameterSpec(128, iv)
                )
            }
        }
        val plaintext = gate.decryptWithBiometric(
            cipher,
            ct,
            io.raventag.app.R.string.biometricRevealTitle,
            io.raventag.app.R.string.biometricRevealSubtitle
        )
        try {
            val tagB64 = prefs.getString(KEY_MNEMONIC_HMAC, null)
                ?: throw IntegrityException("no mnemonic HMAC stored")
            val tag = android.util.Base64.decode(tagB64, android.util.Base64.NO_WRAP)
            verifySeedHmacInstance(plaintext, tag)
            String(plaintext, Charsets.UTF_8).toCharArray()
        } finally {
            java.util.Arrays.fill(plaintext, 0)
        }
    }
    ```

    Add new companion/top-level pref key constants in the existing constants block:
    ```kotlin
    private const val KEY_SEED_HMAC = "seed_hmac"
    private const val KEY_MNEMONIC_HMAC = "mnemonic_hmac"
    private const val KEY_HMAC_MATERIAL_CT = "hmac_material_ct"
    private const val KEY_HMAC_MATERIAL_IV = "hmac_material_iv"
    ```
    (If `KEY_MNEMONIC_ENC` / `KEY_MNEMONIC_IV` / `KEY_SEED_ENC` / `KEY_SEED_IV` already exist — they do per Phase 10 — reuse their exact names; do NOT introduce duplicates.)

    Extend `storeSeed(seed: ByteArray)` post-encrypt:
    ```kotlin
    val hmac = computeSeedHmac(seed)
    prefs.edit().putString(KEY_SEED_HMAC, android.util.Base64.encodeToString(hmac, android.util.Base64.NO_WRAP)).apply()
    java.util.Arrays.fill(hmac, 0)
    ```
    Extend `getSeed()` post-decrypt (before returning plaintext):
    ```kotlin
    val tagB64 = prefs.getString(KEY_SEED_HMAC, null)
    if (tagB64 != null) {
        val tag = android.util.Base64.decode(tagB64, android.util.Base64.NO_WRAP)
        verifySeedHmacInstance(plaintext, tag)
    }
    ```
    Same pattern for `storeMnemonic` / `getMnemonic` with `KEY_MNEMONIC_HMAC`.

    Wrap the `cipher.doFinal` site inside the existing `decrypt()` in `Companion.wrapKeystoreException { ... }`. Similarly for `encrypt()`.

    Extend `restoreFromMnemonic(phrase: String)` at the top of the method body:
    ```kotlin
    val normalized = validateMnemonic(phrase) // throws IllegalArgumentException on bad BIP39
    val hasBackedUp = context
        .getSharedPreferences("raventag_wallet", android.content.Context.MODE_PRIVATE)
        .getBoolean("backup_completed", false)
    val currentBalanceSat = runCatching {
        io.raventag.app.wallet.cache.WalletCacheDao.readState()?.balanceSat ?: 0L
    }.getOrDefault(0L)
    checkRestorePreconditions(currentBalanceSat, hasBackedUp)
    // ... existing restore logic, using `normalized.joinToString(" ")` as the phrase
    ```

    **Delete any in-memory mnemonic cache.** Search with:
    `grep -nE '(cachedMnemonic|mnemonicCache|decryptedMnemonic|plaintextSeed|seedCache)' android/app/src/main/java/io/raventag/app/wallet/WalletManager.kt`
    Delete matching fields and update call sites to invoke `getMnemonic()` / `getSeed()` fresh each time.

    **Create the R.string resources** referenced by `revealMnemonicCharsWithBiometric`: add two `<string>` entries in `android/app/src/main/res/values/strings.xml` (if strings.xml is the canonical source for biometric titles; otherwise the `AppStrings.kt` approach is used. Inspect MainActivity to determine which path is live):
    ```xml
    <string name="biometricRevealTitle">Authenticate</string>
    <string name="biometricRevealSubtitle">Reveal recovery phrase</string>
    ```
    Italian equivalent in `res/values-it/strings.xml`:
    ```xml
    <string name="biometricRevealTitle">Autentica</string>
    <string name="biometricRevealSubtitle">Mostra frase di recupero</string>
    ```

    Em-dash audit: `! grep -P '\u2014' android/app/src/main/java/io/raventag/app/wallet/WalletManager.kt`.
  </action>
  <verify>
    <automated>cd android && ./gradlew :app:testConsumerDebugUnitTest --tests "*WalletManagerMnemonicTest*" -i 2>&1 | tail -40</automated>
  </verify>
  <acceptance_criteria>
    - `grep -q "@JvmStatic" android/app/src/main/java/io/raventag/app/wallet/WalletManager.kt`
    - `grep -q "input.trim().split(Regex(\"\\\\\\\\s+\"))" android/app/src/main/java/io/raventag/app/wallet/WalletManager.kt`
    - `grep -q "VALID_WORD_COUNTS" android/app/src/main/java/io/raventag/app/wallet/WalletManager.kt`
    - `grep -q "BackupRequiredException" android/app/src/main/java/io/raventag/app/wallet/WalletManager.kt`
    - `grep -q "IntegrityException" android/app/src/main/java/io/raventag/app/wallet/WalletManager.kt`
    - `grep -q "KeystoreInvalidatedException" android/app/src/main/java/io/raventag/app/wallet/WalletManager.kt`
    - `grep -q "inline fun <T> wrapKeystoreException" android/app/src/main/java/io/raventag/app/wallet/WalletManager.kt`
    - `grep -q "KeyPermanentlyInvalidatedException" android/app/src/main/java/io/raventag/app/wallet/WalletManager.kt`
    - `grep -q "HMac(\\s*\\n*\\s*org.bouncycastle.crypto.digests.SHA256Digest()\\|HMac(org.bouncycastle.crypto.digests.SHA256Digest())" android/app/src/main/java/io/raventag/app/wallet/WalletManager.kt`
    - `grep -q "MessageDigest.isEqual" android/app/src/main/java/io/raventag/app/wallet/WalletManager.kt`
    - `grep -q "suspend fun revealMnemonicCharsWithBiometric" android/app/src/main/java/io/raventag/app/wallet/WalletManager.kt`
    - `grep -q "KEY_SEED_HMAC" android/app/src/main/java/io/raventag/app/wallet/WalletManager.kt`
    - `grep -q "KEY_MNEMONIC_HMAC" android/app/src/main/java/io/raventag/app/wallet/WalletManager.kt`
    - `grep -q "backup_completed" android/app/src/main/java/io/raventag/app/wallet/WalletManager.kt`
    - `grep -q "java.util.Arrays.fill" android/app/src/main/java/io/raventag/app/wallet/WalletManager.kt`
    - `! grep -nE '(cachedMnemonic|mnemonicCache|decryptedMnemonic|plaintextSeed|seedCache)\\s*[=:]' android/app/src/main/java/io/raventag/app/wallet/WalletManager.kt`
    - `! grep -P '\u2014' android/app/src/main/java/io/raventag/app/wallet/WalletManager.kt`
    - `./gradlew :app:testConsumerDebugUnitTest --tests "*WalletManagerMnemonicTest.validateMnemonic_rejects_padding*"` exits 0 (GREEN).
    - `./gradlew :app:testConsumerDebugUnitTest --tests "*WalletManagerMnemonicTest.restore_forces_backup*"` exits 0 (GREEN).
    - `./gradlew :app:testConsumerDebugUnitTest --tests "*WalletManagerMnemonicTest.hmac_integrity_mismatch_throws*"` exits 0 (GREEN).
    - `./gradlew :app:testConsumerDebugUnitTest --tests "*WalletManagerMnemonicTest.key_invalidated_routes_to_restore*"` exits 0 (GREEN).
  </acceptance_criteria>
  <done>
    All four Wave 0 mnemonic unit tests flip to GREEN. Keystore doFinal sites are wrapped. Restore-over-wallet is gated. No in-memory mnemonic cache. No em dashes.
  </done>
</task>

<task type="auto" tdd="true">
  <name>Task 3: Create MnemonicExporter.kt (zero-fill CharArray reveal wrapper)</name>
  <files>
    android/app/src/main/java/io/raventag/app/security/MnemonicExporter.kt
  </files>
  <read_first>
    @.planning/phases/30-wallet-reliability/30-CONTEXT.md#L37-L41,
    @.planning/phases/30-wallet-reliability/30-RESEARCH.md#L303-L343,
    @.planning/phases/30-wallet-reliability/30-PATTERNS.md#L451-L455,
    @android/app/src/main/java/io/raventag/app/security/BiometricGate.kt,
    @android/app/src/main/java/io/raventag/app/wallet/WalletManager.kt
  </read_first>
  <behavior>
    `object MnemonicExporter` with a single entry:
    ```kotlin
    suspend fun revealMnemonic(
        gate: BiometricGate,
        wm: WalletManager
    ): Result<CharArray>
    ```
    Semantics:
    - On success: returns `Result.success(CharArray)` where the CharArray contains the plaintext mnemonic.
    - Caller (MnemonicBackupScreen) owns zero-filling: `Arrays.fill(chars, '\u0000')` when the display card is dismissed.
    - Maps `BiometricCancelledException` → `Result.failure(BiometricCancelledException(code, message))` (passthrough).
    - Maps `KeystoreInvalidatedException` → `Result.failure(KeystoreInvalidatedException(cause))` (passthrough — UI detects and shows the "Device security changed" dialog).
    - Maps `IntegrityException` → `Result.failure(IntegrityException("seed HMAC mismatch"))` (HMAC of stored mnemonic ciphertext is stale or tampered).
    - Any other exception is wrapped in `Result.failure(it)`.

    Concretely, the implementation is a thin wrapper around `wm.revealMnemonicCharsWithBiometric(gate)`. Kept as a separate object so UI code does not import WalletManager directly for reveal (single surface area for future hardening like biometric-bound delete, etc.).
  </behavior>
  <action>
    Create `android/app/src/main/java/io/raventag/app/security/MnemonicExporter.kt`:
    ```kotlin
    package io.raventag.app.security

    import io.raventag.app.wallet.WalletManager

    /**
     * D-13 + D-15 + D-16: reveal the mnemonic as a CharArray.
     *
     * Caller is responsible for zero-filling the returned CharArray after display.
     * Typical pattern:
     * ```
     * MnemonicExporter.revealMnemonic(gate, wm).onSuccess { chars ->
     *     try { renderWords(chars) } finally { java.util.Arrays.fill(chars, '\u0000') }
     * }
     * ```
     */
    object MnemonicExporter {
        suspend fun revealMnemonic(
            gate: BiometricGate,
            wm: WalletManager
        ): Result<CharArray> = runCatching { wm.revealMnemonicCharsWithBiometric(gate) }
    }
    ```

    Em-dash audit: `! grep -P '\u2014' android/app/src/main/java/io/raventag/app/security/MnemonicExporter.kt`.
  </action>
  <verify>
    <automated>cd android && ./gradlew :app:compileConsumerDebugKotlin -q 2>&1 | tail -15</automated>
  </verify>
  <acceptance_criteria>
    - `test -f android/app/src/main/java/io/raventag/app/security/MnemonicExporter.kt`
    - `grep -q "object MnemonicExporter" android/app/src/main/java/io/raventag/app/security/MnemonicExporter.kt`
    - `grep -q "suspend fun revealMnemonic" android/app/src/main/java/io/raventag/app/security/MnemonicExporter.kt`
    - `grep -q "Result<CharArray>" android/app/src/main/java/io/raventag/app/security/MnemonicExporter.kt`
    - `! grep -P '\u2014' android/app/src/main/java/io/raventag/app/security/MnemonicExporter.kt`
    - `cd android && ./gradlew :app:compileConsumerDebugKotlin` exits 0.
  </acceptance_criteria>
  <done>MnemonicExporter compiles, returns Result<CharArray>, delegates to WalletManager.revealMnemonicCharsWithBiometric.</done>
</task>

<task type="auto" tdd="true">
  <name>Task 4: Extend MnemonicBackupScreen with biometric cover card + FLAG_SECURE + EN/IT strings</name>
  <files>
    android/app/src/main/java/io/raventag/app/ui/screens/MnemonicBackupScreen.kt,
    android/app/src/main/java/io/raventag/app/ui/theme/AppStrings.kt
  </files>
  <read_first>
    @.planning/phases/30-wallet-reliability/30-UI-SPEC.md#L303-L309,
    @.planning/phases/30-wallet-reliability/30-UI-SPEC.md#L144-L207,
    @.planning/phases/30-wallet-reliability/30-RESEARCH.md#L838-L845,
    @android/app/src/main/java/io/raventag/app/ui/screens/MnemonicBackupScreen.kt,
    @android/app/src/main/java/io/raventag/app/ui/theme/AppStrings.kt,
    @android/app/src/main/java/io/raventag/app/security/BiometricGate.kt,
    @android/app/src/main/java/io/raventag/app/security/MnemonicExporter.kt
  </read_first>
  <behavior>
    Before the current 12/24-word grid becomes visible:
    1. Set `window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, FLAG_SECURE)` on enter; clear on dispose. Implemented via `DisposableEffect(Unit)`.
    2. Show a covering card ("RavenCard", 16dp padding, `RoundedCornerShape(12.dp)`, `1dp RavenBorder`) containing:
       - `Icons.Default.Fingerprint` 24dp `RavenOrange`
       - Heading `strings.mnemonicBiometricCoverTitle` (EN "Authenticate to reveal phrase" / IT "Autenticati per mostrare la frase") in `titleSmall` SemiBold white
       - Body `strings.mnemonicBiometricCoverBody` (EN "Use your fingerprint, face, or PIN to display the recovery phrase. Anyone who sees it can steal your funds." / IT "Usa impronta, volto o PIN per visualizzare la frase di recupero. Chi la vede può rubare i tuoi fondi.") in `bodyMedium` RavenMuted
       - Primary CTA button `Button(... containerColor=RavenOrange)` label `strings.mnemonicRevealCta` (EN "Reveal phrase" / IT "Mostra frase")
    3. On CTA tap, launch in composition coroutine scope:
       - Acquire `activity = LocalContext.current as? FragmentActivity` — if null, show snackbar "Biometric unavailable".
       - `val gate = BiometricGate(activity)`
       - `MnemonicExporter.revealMnemonic(gate, wm)` — use the `wm` WalletManager instance already injected (existing plumbing — inspect current screen to identify).
       - onSuccess: set `revealed = chars` state, UI flips to show the word grid (existing grid reads from `revealed`). Register a cleanup: when the screen leaves composition OR the user taps "Hide", `Arrays.fill(chars, '\u0000')` and `revealed = null`.
       - onFailure(BiometricCancelledException): snackbar `strings.authCanceledSnackbar` (EN "Authentication canceled" / IT "Autenticazione annullata").
       - onFailure(KeystoreInvalidatedException): navigate out of this screen AND surface the top-level "Device security changed" error dialog (WalletScreen handles this via a `oneTimeError` flow — pass it up via `onKeystoreInvalidated` callback parameter to the screen).
       - onFailure(any other): snackbar `strings.mnemonicRevealFailed` (EN "Could not reveal phrase. Try again." / IT "Impossibile mostrare la frase. Riprova.")

    4. After the grid displays, keep the existing "Copy all" (`RavenOrange`) and "I've saved it" (`RavenOrange`) buttons. Existing auto-erase-clipboard-after-60s behavior is preserved.

    5. On tapping "I've saved it" — flip SharedPreferences flag `backup_completed = true` (scoped to the current wallet). This unblocks restore-over-wallet per D-14 + Task 2 `checkRestorePreconditions`.

    AppStrings.kt additions (EN + IT verbatim from UI-SPEC Copywriting Contract):
    ```kotlin
    // stringsEn block
    mnemonicBiometricCoverTitle = "Authenticate to reveal phrase"
    mnemonicBiometricCoverBody = "Use your fingerprint, face, or PIN to display the recovery phrase. Anyone who sees it can steal your funds."
    mnemonicRevealCta = "Reveal phrase"
    mnemonicCopyAll = "Copy all"
    mnemonicSavedIt = "I've saved it"
    authCanceledSnackbar = "Authentication canceled"
    mnemonicRevealFailed = "Could not reveal phrase. Try again."
    deviceSecurityChangedTitle = "Device security changed"
    deviceSecurityChangedBody = "Device security changed. Restore your wallet from the recovery phrase to continue."
    deviceSecurityChangedCta = "Restore from recovery phrase"

    // stringsIt block
    mnemonicBiometricCoverTitle = "Autenticati per mostrare la frase"
    mnemonicBiometricCoverBody = "Usa impronta, volto o PIN per visualizzare la frase di recupero. Chi la vede può rubare i tuoi fondi."
    mnemonicRevealCta = "Mostra frase"
    mnemonicCopyAll = "Copia tutte"
    mnemonicSavedIt = "L'ho salvata"
    authCanceledSnackbar = "Autenticazione annullata"
    mnemonicRevealFailed = "Impossibile mostrare la frase. Riprova."
    deviceSecurityChangedTitle = "La sicurezza del dispositivo è cambiata"
    deviceSecurityChangedBody = "La sicurezza del dispositivo è cambiata. Ripristina il wallet dalla frase di recupero per continuare."
    deviceSecurityChangedCta = "Ripristina dalla frase di recupero"
    ```

    FLAG_SECURE block:
    ```kotlin
    val view = androidx.compose.ui.platform.LocalView.current
    DisposableEffect(Unit) {
        val window = (view.context as? android.app.Activity)?.window
        window?.setFlags(
            android.view.WindowManager.LayoutParams.FLAG_SECURE,
            android.view.WindowManager.LayoutParams.FLAG_SECURE
        )
        onDispose {
            window?.clearFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE)
        }
    }
    ```

    Em-dash audit on AppStrings.kt AND MnemonicBackupScreen.kt.
  </behavior>
  <action>
    1) Open `AppStrings.kt`. Locate `stringsEn = AppStrings().apply { ... }` block (~line 393) and the `stringsIt = AppStrings().apply { ... }` block (~line 608). Add the EN/IT entries listed in `<behavior>`. Ensure the corresponding properties are declared on `class AppStrings` near the top of the file. For each new key, check that no em dash appears.

    2) Open `MnemonicBackupScreen.kt`. Identify:
       - The screen's root `@Composable` (likely `fun MnemonicBackupScreen(...)`).
       - The WalletManager injection path (field, parameter, or `remember { WalletManager(context) }` pattern).
       - The existing words-grid layout.

    3) At the top of the composable, insert the `DisposableEffect(Unit)` FLAG_SECURE block listed in `<behavior>`.

    4) Replace the current words-grid entry condition so that the grid renders only when `revealed != null`. Introduce:
       ```kotlin
       var revealed: CharArray? by rememberSaveable(stateSaver = null as? androidx.compose.runtime.saveable.Saver<CharArray?, Any>)
       { mutableStateOf<CharArray?>(null) }
       ```
       (CharArray is NOT rememberSaveable-friendly by default; use a plain `remember { mutableStateOf<CharArray?>(null) }` to avoid process-death leakage — losing the revealed state on config change is acceptable; user re-authenticates.)

    5) When `revealed == null`, render the biometric cover card composable described in `<behavior>`. When `revealed != null`, render the existing words grid bound to `revealed.joinToString(" ").split(" ")` or `String(revealed).split(" ")`.

    6) On cover-card CTA tap, launch `LaunchedEffect` via `rememberCoroutineScope().launch { ... }`:
       ```kotlin
       val activity = context as? androidx.fragment.app.FragmentActivity
       if (activity == null) {
           snackbarHostState.showSnackbar("Biometric unavailable")
           return@launch
       }
       val gate = io.raventag.app.security.BiometricGate(activity)
       val result = io.raventag.app.security.MnemonicExporter.revealMnemonic(gate, wm)
       result.onSuccess { chars -> revealed = chars }
       result.onFailure { t ->
           when (t) {
               is io.raventag.app.security.BiometricCancelledException ->
                   snackbarHostState.showSnackbar(strings.authCanceledSnackbar)
               is io.raventag.app.wallet.KeystoreInvalidatedException ->
                   onKeystoreInvalidated()
               else -> snackbarHostState.showSnackbar(strings.mnemonicRevealFailed)
           }
       }
       ```

    7) On screen dispose OR "Hide" toggle OR back nav: zero-fill `revealed`:
       ```kotlin
       DisposableEffect(revealed) {
           onDispose {
               revealed?.let { java.util.Arrays.fill(it, '\u0000') }
           }
       }
       ```

    8) On "I've saved it" tap (existing button), BEFORE the existing nav-back call, set the backup flag:
       ```kotlin
       context.getSharedPreferences("raventag_wallet", android.content.Context.MODE_PRIVATE)
           .edit().putBoolean("backup_completed", true).apply()
       ```

    9) Add a new composable parameter `onKeystoreInvalidated: () -> Unit` to the screen signature. Update the single caller (WalletScreen or MainActivity — inspect at execution time) to pass a lambda that shows the Keystore-invalidated dialog and routes to restore. A minimal implementation: show a `oneTimeErrorDialogState = Error.KeystoreInvalidated` and let the calling screen render the dialog using `strings.deviceSecurityChangedTitle` / `Body` / `Cta`.

    10) Ensure that FragmentActivity is the MainActivity base. If it isn't:
        - Inspect `class MainActivity : ComponentActivity()` line. If it extends `ComponentActivity`, change to `androidx.fragment.app.FragmentActivity` (ComponentActivity is a subclass; the reverse is not true, but FragmentActivity extends ComponentActivity — verify). If MainActivity already extends AppCompatActivity or FragmentActivity, no change needed.
        - Rationale: `androidx.biometric.BiometricPrompt(activity, ...)` requires `FragmentActivity`.

    Em-dash audit: `! grep -P '\u2014' android/app/src/main/java/io/raventag/app/ui/screens/MnemonicBackupScreen.kt android/app/src/main/java/io/raventag/app/ui/theme/AppStrings.kt`.
  </action>
  <verify>
    <automated>cd android && ./gradlew :app:assembleConsumerDebug -q 2>&1 | tail -20</automated>
  </verify>
  <acceptance_criteria>
    - `grep -q "FLAG_SECURE" android/app/src/main/java/io/raventag/app/ui/screens/MnemonicBackupScreen.kt`
    - `grep -q "DisposableEffect" android/app/src/main/java/io/raventag/app/ui/screens/MnemonicBackupScreen.kt`
    - `grep -q "clearFlags" android/app/src/main/java/io/raventag/app/ui/screens/MnemonicBackupScreen.kt`
    - `grep -q "BiometricGate" android/app/src/main/java/io/raventag/app/ui/screens/MnemonicBackupScreen.kt`
    - `grep -q "MnemonicExporter.revealMnemonic" android/app/src/main/java/io/raventag/app/ui/screens/MnemonicBackupScreen.kt`
    - `grep -q "Arrays.fill" android/app/src/main/java/io/raventag/app/ui/screens/MnemonicBackupScreen.kt`
    - `grep -q "backup_completed" android/app/src/main/java/io/raventag/app/ui/screens/MnemonicBackupScreen.kt`
    - `grep -q "Icons.Default.Fingerprint" android/app/src/main/java/io/raventag/app/ui/screens/MnemonicBackupScreen.kt`
    - `grep -q "mnemonicBiometricCoverTitle" android/app/src/main/java/io/raventag/app/ui/theme/AppStrings.kt`
    - `grep -q "Authenticate to reveal phrase" android/app/src/main/java/io/raventag/app/ui/theme/AppStrings.kt`
    - `grep -q "Autenticati per mostrare la frase" android/app/src/main/java/io/raventag/app/ui/theme/AppStrings.kt`
    - `grep -q "Reveal phrase" android/app/src/main/java/io/raventag/app/ui/theme/AppStrings.kt`
    - `grep -q "Mostra frase" android/app/src/main/java/io/raventag/app/ui/theme/AppStrings.kt`
    - `grep -q "I've saved it" android/app/src/main/java/io/raventag/app/ui/theme/AppStrings.kt`
    - `grep -q "L'ho salvata" android/app/src/main/java/io/raventag/app/ui/theme/AppStrings.kt`
    - `grep -q "Copy all" android/app/src/main/java/io/raventag/app/ui/theme/AppStrings.kt`
    - `grep -q "Copia tutte" android/app/src/main/java/io/raventag/app/ui/theme/AppStrings.kt`
    - `grep -q "Device security changed" android/app/src/main/java/io/raventag/app/ui/theme/AppStrings.kt`
    - `grep -q "La sicurezza del dispositivo è cambiata" android/app/src/main/java/io/raventag/app/ui/theme/AppStrings.kt`
    - `grep -q "Authentication canceled" android/app/src/main/java/io/raventag/app/ui/theme/AppStrings.kt`
    - `grep -q "Autenticazione annullata" android/app/src/main/java/io/raventag/app/ui/theme/AppStrings.kt`
    - `! grep -P '\u2014' android/app/src/main/java/io/raventag/app/ui/screens/MnemonicBackupScreen.kt`
    - `! grep -P '\u2014' android/app/src/main/java/io/raventag/app/ui/theme/AppStrings.kt`
    - `cd android && ./gradlew :app:assembleConsumerDebug` exits 0.
  </acceptance_criteria>
  <done>Biometric cover card and FLAG_SECURE live. Words grid gated behind CryptoObject auth. CharArray zero-filled on dispose. backup_completed flag set on "I've saved it". EN + IT strings verbatim, zero em dashes.</done>
</task>

<task type="auto" tdd="true">
  <name>Task 5: Add RestoreWalletConfirmDialog + forced-backup gate on WalletScreen</name>
  <files>
    android/app/src/main/java/io/raventag/app/ui/screens/WalletScreen.kt,
    android/app/src/main/java/io/raventag/app/ui/theme/AppStrings.kt
  </files>
  <read_first>
    @.planning/phases/30-wallet-reliability/30-UI-SPEC.md#L311-L317,
    @.planning/phases/30-wallet-reliability/30-UI-SPEC.md#L190-L210,
    @.planning/phases/30-wallet-reliability/30-CONTEXT.md#L38-L39,
    @android/app/src/main/java/io/raventag/app/ui/screens/WalletScreen.kt,
    @android/app/src/main/java/io/raventag/app/ui/theme/AppStrings.kt,
    @android/app/src/main/java/io/raventag/app/wallet/WalletManager.kt
  </read_first>
  <behavior>
    Introduce `@Composable fun RestoreWalletConfirmDialog(...)` inside WalletScreen.kt as a file-private composable, matching the existing destructive-confirm AlertDialog pattern (`WalletScreen.kt:131-173` per PATTERNS.md):
    - Container color: `Color(0xFF1A0000)` (destructive variant per UI-SPEC Destructive row).
    - Title: bold white, 18sp, using `strings.restoreReplaceWalletTitle` (EN "Replace current wallet?" / IT "Sostituire il wallet attuale?").
    - Body variant A (has backed up): `strings.restoreReplaceWalletBody` (EN "This will replace your current wallet (%1 RVN · %2 assets). You must back up the recovery phrase first. This action cannot be undone." / IT "Questa operazione sostituirà il wallet attuale (%1 RVN · %2 asset). Fai prima il backup della frase di recupero. Questa azione non può essere annullata.") with format args `rvnAmount`, `assetsCount`.
    - Body variant B (has NOT backed up): `strings.restoreBackupFirstBody` (EN "Back up your recovery phrase first. You can't undo this." / IT "Fai prima il backup della frase di recupero. Non puoi annullare questa azione.").
    - Buttons:
      - Variant A: Confirm button = NotAuthenticRed, label `strings.restoreReplaceCta` (EN "Replace wallet" / IT "Sostituisci wallet"); Cancel button = OutlinedButton 1dp RavenBorder, label EN "Cancel" / IT "Annulla".
      - Variant B: SINGLE primary button RavenOrange, label `strings.restoreBackupFirstCta` (EN "Back up phrase first" / IT "Fai prima il backup"), tapping routes to MnemonicBackupScreen. Cancel is STILL available (outlined) per UI-SPEC "Cancel stays available."

    WalletScreen wiring:
    ```kotlin
    val prefs = context.getSharedPreferences("raventag_wallet", Context.MODE_PRIVATE)
    val hasBackedUp = prefs.getBoolean("backup_completed", false)
    val hasFunds = walletBalance > 0 || assetsCount > 0
    var showRestoreDialog by remember { mutableStateOf(false) }

    // Replace existing direct call to `onRestoreWallet` with:
    onRestoreClick = {
        if (hasFunds) {
            showRestoreDialog = true
        } else {
            onRestoreWallet()
        }
    }

    if (showRestoreDialog) {
        RestoreWalletConfirmDialog(
            hasBackedUp = hasBackedUp,
            rvnAmount = walletBalance,
            assetsCount = assetsCount,
            onDismiss = { showRestoreDialog = false },
            onBackupFirst = {
                showRestoreDialog = false
                onNavigateToMnemonicBackup()
            },
            onReplace = {
                showRestoreDialog = false
                onRestoreWallet()
            }
        )
    }
    ```

    AppStrings.kt additions (EN + IT):
    ```kotlin
    // EN
    restoreReplaceWalletTitle = "Replace current wallet?"
    restoreReplaceWalletBody = "This will replace your current wallet (%1\$s RVN · %2\$s assets). You must back up the recovery phrase first. This action cannot be undone."
    restoreBackupFirstBody = "Back up your recovery phrase first. You can't undo this."
    restoreReplaceCta = "Replace wallet"
    restoreBackupFirstCta = "Back up phrase first"
    cancel = "Cancel"   // reuse existing if already present

    // IT
    restoreReplaceWalletTitle = "Sostituire il wallet attuale?"
    restoreReplaceWalletBody = "Questa operazione sostituirà il wallet attuale (%1\$s RVN · %2\$s asset). Fai prima il backup della frase di recupero. Questa azione non può essere annullata."
    restoreBackupFirstBody = "Fai prima il backup della frase di recupero. Non puoi annullare questa azione."
    restoreReplaceCta = "Sostituisci wallet"
    restoreBackupFirstCta = "Fai prima il backup"
    cancel = "Annulla"  // reuse if present
    ```
    If `cancel` / `cancelLabel` already exists in AppStrings, reuse the existing property.

    Also surface the "Invalid recovery phrase" error copy consumed during restore:
    ```kotlin
    // EN
    restoreInvalidPhrase = "Invalid recovery phrase. Check spelling and word order."
    // IT
    restoreInvalidPhrase = "Frase di recupero non valida. Controlla ortografia e ordine."
    ```

    Em-dash audit on BOTH files.
  </behavior>
  <action>
    1) AppStrings.kt — add the EN + IT properties (declare on the class if not present; set in `stringsEn` / `stringsIt` blocks). Verify no em dashes.

    2) WalletScreen.kt — read the file; locate the current "Restore wallet" call site (search for `onRestoreWallet`). Identify how the composable receives `walletBalance`, `assetsCount`, and `onRestoreWallet`. If `assetsCount` is not already a parameter, inspect the WalletViewModel / WalletInfo data class for the asset count source; pass through or compute `assetUtxos.size` from the existing `assetUtxos: Map<String, List<AssetUtxo>>` the screen already receives.

    3) Add a file-private composable at the bottom of WalletScreen.kt:
    ```kotlin
    @Composable
    private fun RestoreWalletConfirmDialog(
        hasBackedUp: Boolean,
        rvnAmount: Double,
        assetsCount: Int,
        onDismiss: () -> Unit,
        onBackupFirst: () -> Unit,
        onReplace: () -> Unit
    ) {
        val strings = io.raventag.app.ui.theme.LocalStrings.current
        androidx.compose.material3.AlertDialog(
            onDismissRequest = onDismiss,
            containerColor = androidx.compose.ui.graphics.Color(0xFF1A0000),
            shape = androidx.compose.foundation.shape.RoundedCornerShape(16.dp),
            title = {
                androidx.compose.material3.Text(
                    text = strings.restoreReplaceWalletTitle,
                    style = androidx.compose.material3.MaterialTheme.typography.titleMedium,
                    fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
                    color = androidx.compose.ui.graphics.Color.White
                )
            },
            text = {
                val body = if (hasBackedUp) {
                    String.format(
                        strings.restoreReplaceWalletBody,
                        String.format("%.8f", rvnAmount),
                        assetsCount.toString()
                    )
                } else {
                    strings.restoreBackupFirstBody
                }
                androidx.compose.material3.Text(
                    text = body,
                    style = androidx.compose.material3.MaterialTheme.typography.bodyMedium,
                    color = io.raventag.app.ui.theme.RavenMuted
                )
            },
            confirmButton = {
                if (hasBackedUp) {
                    androidx.compose.material3.Button(
                        onClick = onReplace,
                        colors = androidx.compose.material3.ButtonDefaults.buttonColors(
                            containerColor = io.raventag.app.ui.theme.NotAuthenticRed
                        )
                    ) { androidx.compose.material3.Text(strings.restoreReplaceCta) }
                } else {
                    androidx.compose.material3.Button(
                        onClick = onBackupFirst,
                        colors = androidx.compose.material3.ButtonDefaults.buttonColors(
                            containerColor = io.raventag.app.ui.theme.RavenOrange
                        )
                    ) { androidx.compose.material3.Text(strings.restoreBackupFirstCta) }
                }
            },
            dismissButton = {
                androidx.compose.material3.OutlinedButton(
                    onClick = onDismiss,
                    border = androidx.compose.foundation.BorderStroke(
                        1.dp, io.raventag.app.ui.theme.RavenBorder
                    )
                ) { androidx.compose.material3.Text(strings.cancel) }
            }
        )
    }
    ```

    4) Wire the dialog at the existing restore-button call site. Keep the existing "empty wallet direct restore" path when `!hasFunds`.

    5) Pass an `onNavigateToMnemonicBackup: () -> Unit` parameter to WalletScreen if it's not already there; bind it in MainActivity navigation at the MnemonicBackupScreen route.

    Em-dash audit: `! grep -P '\u2014' android/app/src/main/java/io/raventag/app/ui/screens/WalletScreen.kt android/app/src/main/java/io/raventag/app/ui/theme/AppStrings.kt`.
  </action>
  <verify>
    <automated>cd android && ./gradlew :app:assembleConsumerDebug -q 2>&1 | tail -20</automated>
  </verify>
  <acceptance_criteria>
    - `grep -q "fun RestoreWalletConfirmDialog" android/app/src/main/java/io/raventag/app/ui/screens/WalletScreen.kt`
    - `grep -q "Color(0xFF1A0000)" android/app/src/main/java/io/raventag/app/ui/screens/WalletScreen.kt`
    - `grep -q "hasBackedUp" android/app/src/main/java/io/raventag/app/ui/screens/WalletScreen.kt`
    - `grep -q "backup_completed" android/app/src/main/java/io/raventag/app/ui/screens/WalletScreen.kt`
    - `grep -q "NotAuthenticRed" android/app/src/main/java/io/raventag/app/ui/screens/WalletScreen.kt`
    - `grep -q "RavenOrange" android/app/src/main/java/io/raventag/app/ui/screens/WalletScreen.kt`
    - `grep -q "Replace current wallet?" android/app/src/main/java/io/raventag/app/ui/theme/AppStrings.kt`
    - `grep -q "Sostituire il wallet attuale?" android/app/src/main/java/io/raventag/app/ui/theme/AppStrings.kt`
    - `grep -q "Back up phrase first" android/app/src/main/java/io/raventag/app/ui/theme/AppStrings.kt`
    - `grep -q "Fai prima il backup" android/app/src/main/java/io/raventag/app/ui/theme/AppStrings.kt`
    - `grep -q "Replace wallet" android/app/src/main/java/io/raventag/app/ui/theme/AppStrings.kt`
    - `grep -q "Sostituisci wallet" android/app/src/main/java/io/raventag/app/ui/theme/AppStrings.kt`
    - `grep -q "Invalid recovery phrase" android/app/src/main/java/io/raventag/app/ui/theme/AppStrings.kt`
    - `grep -q "Frase di recupero non valida" android/app/src/main/java/io/raventag/app/ui/theme/AppStrings.kt`
    - `! grep -P '\u2014' android/app/src/main/java/io/raventag/app/ui/screens/WalletScreen.kt`
    - `! grep -P '\u2014' android/app/src/main/java/io/raventag/app/ui/theme/AppStrings.kt`
    - `cd android && ./gradlew :app:assembleConsumerDebug` exits 0.
  </acceptance_criteria>
  <done>RestoreWalletConfirmDialog composable file-private in WalletScreen.kt. Forced-backup variant shown when backup_completed=false. Build passes. EN + IT strings verbatim. No em dashes.</done>
</task>

</tasks>

<threat_model>
## Trust Boundaries

| Boundary | Description |
|----------|-------------|
| user → Keystore (biometric reveal) | BiometricPrompt + CryptoObject binds user-presence to the actual decrypt op; no plaintext without auth. |
| stored mnemonic ciphertext → runtime memory | Only CharArray exposed; caller zero-fills on dispose; no String/property retention (D-16). |
| restored mnemonic → WalletManager | Whitespace-normalized + BIP39 checksum gate; backup-required gate blocks silent overwrite of funded wallet. |
| MnemonicBackupScreen surface → screen recording / screenshot | FLAG_SECURE blocks capture at the OS layer. |

## STRIDE Threat Register

| Threat ID | Category | Component | Disposition | Mitigation Plan |
|-----------|----------|-----------|-------------|-----------------|
| T-30-MNEM-01 | Information Disclosure | Mnemonic extracted via a rooted device reading SharedPreferences | mitigate | AES-GCM-Keystore (StrongBox when available, existing Phase 10 pattern). HMAC-SHA256 detects tamper. No plaintext in property fields (D-16). |
| T-30-MNEM-02 | Information Disclosure | Mnemonic visible via screen recording / screenshot during reveal | mitigate | `FLAG_SECURE` on MnemonicBackupScreen via DisposableEffect (Task 4). |
| T-30-MNEM-03 | Information Disclosure | Clipboard sniffing after Copy all | accept | Existing Phase 10 auto-erase-clipboard-after-60s; user education via Copywriting Contract. |
| T-30-MNEM-04 | Tampering | Tampered ciphertext → wrong derivation key silently loads | mitigate | HMAC-SHA256 over seed + mnemonic verified on every getSeed/getMnemonic (Task 2); mismatch → IntegrityException. |
| T-30-MNEM-05 | Denial of Service | Restore-over-wallet overwrites funded wallet without backup | mitigate | `checkRestorePreconditions` + forced-backup dialog variant (Tasks 2, 5) per D-14. |
| T-30-MNEM-06 | Elevation of Privilege | Boolean "authenticated" flag tamper bypasses BiometricPrompt | mitigate | CryptoObject(cipher) binding (Task 1) — no auth, no plaintext. Flag-based bypass is not applicable. |
| T-30-KEYS-01 | Denial of Service | KeyPermanentlyInvalidatedException silently swallowed → "generic failure" | mitigate | `wrapKeystoreException` rethrows as typed `KeystoreInvalidatedException`; UI routes to explicit restore dialog (Tasks 2, 4) per Pitfall 3. |
| T-30-KEYS-02 | Spoofing | Attacker enrolls fingerprint during physical access | mitigate | `BIOMETRIC_STRONG` excludes Class 1 sensors; `DEVICE_CREDENTIAL` fallback still requires PIN/pattern. User education in reveal body copy. |
| T-30-KEYS-03 | Tampering | HMAC key material compromised | accept | HMAC material is wrapped by the same Keystore AES-GCM key that protects the mnemonic; any attacker with Keystore access already has the mnemonic. |
| T-30-KEYS-04 | Information Disclosure | Mnemonic retained in ViewModel / SavedStateHandle after reveal | mitigate | CharArray-only return from WalletManager; zero-filled via DisposableEffect(revealed) onDispose (Task 4); no in-memory field retention (Task 2 audit). |

ASVS V2 Authentication, V4 Access Control, V5 Input Validation (BIP39 whitespace), V6 Cryptography (HMAC + AES-GCM), V7 Error Handling (typed exceptions). ASVS L1 adequate.
</threat_model>

<verification>
- `cd android && ./gradlew :app:testConsumerDebugUnitTest --tests "*WalletManagerMnemonicTest*"` — all four tests GREEN.
- `cd android && ./gradlew :app:assembleConsumerDebug` — build exits 0.
- `! grep -rP '\u2014' android/app/src/main/java/io/raventag/app/security android/app/src/main/java/io/raventag/app/ui/screens/MnemonicBackupScreen.kt android/app/src/main/java/io/raventag/app/ui/screens/WalletScreen.kt android/app/src/main/java/io/raventag/app/ui/theme/AppStrings.kt android/app/src/main/java/io/raventag/app/wallet/WalletManager.kt` returns no matches.
- Manual device verification (per 30-VALIDATION.md):
  1. Fresh install → open MnemonicBackupScreen → biometric cover card visible → Reveal phrase → BiometricPrompt appears → cancel → no words shown.
  2. Authenticate successfully → 12/24-word grid visible → rotate screen → words re-hidden, cover card returns (CharArray zero-filled).
  3. Attempt screenshot on MnemonicBackupScreen → OS blocks ("Can't take screenshot due to security policy").
  4. Enroll a new fingerprint in system Settings → reopen app → Reveal → "Device security changed" dialog → route to restore.
  5. With a funded wallet + backup_completed=false, tap Restore → forced-backup dialog with single "Back up phrase first" button (Cancel still available).
  6. Paste mnemonic with trailing whitespace → restore succeeds (whitespace normalized).
  7. Paste 13-word mnemonic → rejected with "Invalid recovery phrase" copy.
</verification>

<success_criteria>
- BiometricGate.kt compiles with BIOMETRIC_STRONG or DEVICE_CREDENTIAL and CryptoObject binding.
- MnemonicExporter.kt returns Result<CharArray>, never String.
- WalletManager.kt Wave 0 TODOs replaced with real bodies that make all four WalletManagerMnemonicTest cases GREEN.
- HMAC-SHA256 is computed and verified on every seed/mnemonic read.
- KeyPermanentlyInvalidatedException is caught and surfaced as KeystoreInvalidatedException.
- MnemonicBackupScreen sets FLAG_SECURE and routes reveal through BiometricGate + MnemonicExporter; CharArray zero-fills on dispose.
- RestoreWalletConfirmDialog composable exists on WalletScreen with both "has backed up" and "needs backup" variants wired to D-14 semantics.
- AppStrings.kt has all new EN + IT entries verbatim from UI-SPEC Copywriting Contract.
- `! grep -P '\u2014'` on every touched file returns no matches.
- `./gradlew :app:assembleConsumerDebug` exits 0.
</success_criteria>

<output>
After completion, create `.planning/phases/30-wallet-reliability/30-06-SUMMARY.md`:
- Exact location in `WalletManager.kt` where each Wave 0 TODO body was replaced (line numbers and surrounding function signature).
- Exact method name of the pre-existing BIP39 checksum validator that `validateMnemonic` now delegates to.
- Confirmation that no mnemonic-cache property field remains (list any properties audited and deleted, or "none found").
- Name of the MainActivity base class before and after this plan (ComponentActivity → FragmentActivity, or "already FragmentActivity").
- Hand-off to plan 30-10: final em-dash audit sweep across all plans' touched files.
- Hand-off to plan 30-08: WalletScreen `RestoreWalletConfirmDialog` is in place; plan 30-08 should integrate the connection-pill and cached-state banners without touching the dialog.
</output>
