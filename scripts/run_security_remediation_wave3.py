#!/usr/bin/env python3
from pathlib import Path
import re

ROOT = Path(__file__).resolve().parents[1]
wallet = ROOT / 'android/app/src/main/java/io/raventag/app/wallet/WalletManager.kt'
test = ROOT / 'android/app/src/androidTest/java/io/raventag/app/wallet/WalletManagerTest.kt'

s = wallet.read_text(encoding='utf-8')

def once(old: str, new: str):
    global s
    n = s.count(old)
    if n != 1:
        raise SystemExit(f'WalletManager expected one match, got {n}: {old[:100]!r}')
    s = s.replace(old, new, 1)

# Dedicated auth-bound mnemonic representation. Legacy seed/mnemonic ciphertext is
# retained for wallet compatibility; only the reveal representation uses this key.
once('        private const val KEYSTORE_ALIAS = "raventag_wallet_key"\n', '''        private const val KEYSTORE_ALIAS = "raventag_wallet_key"
        private const val MNEMONIC_REVEAL_KEYSTORE_ALIAS = "raventag_mnemonic_reveal_key_v1"
        private const val KEY_MNEMONIC_AUTH_ENC = "mnemonic_auth_enc_v1"
        private const val KEY_MNEMONIC_AUTH_IV = "mnemonic_auth_iv_v1"
''')

# Add auth-required key factory immediately after the existing wallet-key factory.
anchor = '        return key\n    }\n\n    fun isKeyHardwareBacked(): Boolean {'
addition = '''        return key
    }

    /**
     * Dedicated key for recovery-phrase reveal. Its use is cryptographically
     * conditioned on recent OS user authentication; the normal wallet seed key is
     * intentionally separate so background balance/address work is not disrupted.
     */
    private fun getOrCreateMnemonicRevealKey(): SecretKey {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        if (keyStore.containsAlias(MNEMONIC_REVEAL_KEYSTORE_ALIAS)) {
            return (keyStore.getEntry(MNEMONIC_REVEAL_KEYSTORE_ALIAS, null) as KeyStore.SecretKeyEntry).secretKey
        }

        fun spec(strongBox: Boolean): KeyGenParameterSpec {
            val builder = KeyGenParameterSpec.Builder(
                MNEMONIC_REVEAL_KEYSTORE_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setRandomizedEncryptionRequired(true)
                .setUserAuthenticationRequired(true)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                builder.setUserAuthenticationParameters(
                    60,
                    KeyProperties.AUTH_BIOMETRIC_STRONG or KeyProperties.AUTH_DEVICE_CREDENTIAL
                )
            } else {
                @Suppress("DEPRECATION")
                builder.setUserAuthenticationValidityDurationSeconds(60)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                builder.setUnlockedDeviceRequired(true)
                if (strongBox) builder.setIsStrongBoxBacked(true)
            }
            return builder.build()
        }

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        return try {
            generator.init(spec(strongBox = true))
            generator.generateKey()
        } catch (_: Throwable) {
            generator.init(spec(strongBox = false))
            generator.generateKey()
        }
    }

    fun isKeyHardwareBacked(): Boolean {'''
if s.count(anchor) != 1: raise SystemExit('wallet key factory anchor mismatch')
s = s.replace(anchor, addition, 1)

# Wipe the derived representation and dedicated key on wallet deletion.
once('            .remove(KEY_MNEMONIC_ENC).remove(KEY_MNEMONIC_IV)\n', '            .remove(KEY_MNEMONIC_ENC).remove(KEY_MNEMONIC_IV)\n            .remove(KEY_MNEMONIC_AUTH_ENC).remove(KEY_MNEMONIC_AUTH_IV)\n')
once('            if (ks.containsAlias(KEYSTORE_ALIAS)) ks.deleteEntry(KEYSTORE_ALIAS)\n', '''            if (ks.containsAlias(KEYSTORE_ALIAS)) ks.deleteEntry(KEYSTORE_ALIAS)
            if (ks.containsAlias(MNEMONIC_REVEAL_KEYSTORE_ALIAS)) ks.deleteEntry(MNEMONIC_REVEAL_KEYSTORE_ALIAS)
''')

# Direct plaintext mnemonic accessor exists only for instrumentation compatibility;
# make the intent explicit and non-public production API.
once('    fun getMnemonic(): String? {\n', '    internal fun getMnemonicForTestOnly(): String? {\n')

# Replace reveal with authenticated lazy migration. Persistence happens only after
# the auth-bound representation has been decrypted back and byte-compared.
pattern = re.compile(r'''    /\*\*\n     \* D-15 \+ D-16: reveal the stored mnemonic.*?    fun getAddress\(''', re.S)
replacement = '''    /**
     * Reveal the recovery phrase through a dedicated Keystore key whose use
     * requires recent OS authentication. Existing wallets migrate lazily and
     * non-destructively on their first successful reveal.
     */
    suspend fun revealMnemonicCharsWithBiometric(
        gate: io.raventag.app.security.BiometricGate
    ): CharArray = withContext(Dispatchers.IO) {
        val p = prefs()

        // Authentication MUST succeed before either migration or use of the
        // auth-bound key. Errors/cancellation propagate and leave the phrase hidden.
        gate.authenticate(
            io.raventag.app.R.string.biometricRevealTitle,
            io.raventag.app.R.string.biometricRevealSubtitle
        )

        val revealKey = wrapKeystoreException { getOrCreateMnemonicRevealKey() }
        var plaintext: ByteArray? = null
        try {
            val authCtB64 = p.getString(KEY_MNEMONIC_AUTH_ENC, null)
            val authIvB64 = p.getString(KEY_MNEMONIC_AUTH_IV, null)

            plaintext = if (authCtB64 != null && authIvB64 != null) {
                val ct = android.util.Base64.decode(authCtB64, android.util.Base64.NO_WRAP)
                val iv = android.util.Base64.decode(authIvB64, android.util.Base64.NO_WRAP)
                wrapKeystoreException {
                    Cipher.getInstance("AES/GCM/NoPadding").run {
                        init(Cipher.DECRYPT_MODE, revealKey, GCMParameterSpec(128, iv))
                        doFinal(ct)
                    }
                }
            } else {
                // Legacy wallet migration: decrypt the existing valid copy only
                // after authentication, create auth-bound ciphertext, verify it,
                // then persist the new representation. Legacy data is not removed.
                val legacyCtB64 = p.getString(KEY_MNEMONIC_ENC, null)
                    ?: throw IllegalStateException("no mnemonic stored")
                val legacyIvB64 = p.getString(KEY_MNEMONIC_IV, null)
                    ?: throw IllegalStateException("no mnemonic iv stored")
                val legacy = decrypt(
                    android.util.Base64.decode(legacyCtB64, android.util.Base64.DEFAULT),
                    android.util.Base64.decode(legacyIvB64, android.util.Base64.DEFAULT)
                )
                try {
                    val tagB64 = p.getString(KEY_MNEMONIC_HMAC, null)
                    if (tagB64 != null) {
                        verifySeedHmacInstance(
                            legacy,
                            android.util.Base64.decode(tagB64, android.util.Base64.NO_WRAP)
                        )
                    }
                    val encCipher = Cipher.getInstance("AES/GCM/NoPadding")
                    encCipher.init(Cipher.ENCRYPT_MODE, revealKey)
                    val newCt = encCipher.doFinal(legacy)
                    val newIv = encCipher.iv

                    val verifyCipher = Cipher.getInstance("AES/GCM/NoPadding")
                    verifyCipher.init(Cipher.DECRYPT_MODE, revealKey, GCMParameterSpec(128, newIv))
                    val verified = verifyCipher.doFinal(newCt)
                    try {
                        check(java.security.MessageDigest.isEqual(legacy, verified)) {
                            "mnemonic auth-bound migration verification failed"
                        }
                    } finally {
                        verified.fill(0)
                    }

                    p.edit()
                        .putString(KEY_MNEMONIC_AUTH_ENC, android.util.Base64.encodeToString(newCt, android.util.Base64.NO_WRAP))
                        .putString(KEY_MNEMONIC_AUTH_IV, android.util.Base64.encodeToString(newIv, android.util.Base64.NO_WRAP))
                        .commit().also { check(it) { "could not persist authenticated mnemonic representation" } }
                    legacy.copyOf()
                } finally {
                    legacy.fill(0)
                }
            }

            val tagB64 = p.getString(KEY_MNEMONIC_HMAC, null)
            if (tagB64 != null) {
                verifySeedHmacInstance(
                    plaintext,
                    android.util.Base64.decode(tagB64, android.util.Base64.NO_WRAP)
                )
            }

            // Decode without creating an immutable java.lang.String copy.
            val chars = Charsets.UTF_8.decode(java.nio.ByteBuffer.wrap(plaintext))
            CharArray(chars.remaining()).also { chars.get(it) }
        } finally {
            plaintext?.fill(0)
        }
    }

    fun getAddress('''
out, n = pattern.subn(lambda _: replacement, s, count=1)
if n != 1: raise SystemExit(f'reveal method replacement count={n}')
s = out
wallet.write_text(s, encoding='utf-8')

# Instrumentation test explicitly uses the test-only legacy accessor.
t = test.read_text(encoding='utf-8')
if t.count('walletManager.getMnemonic()') != 1:
    raise SystemExit('instrumentation getMnemonic caller mismatch')
t = t.replace('walletManager.getMnemonic()', 'walletManager.getMnemonicForTestOnly()', 1)
test.write_text(t, encoding='utf-8')

# Static security assertions.
final = wallet.read_text(encoding='utf-8')
assert '.setUserAuthenticationRequired(true)' in final
assert 'MNEMONIC_REVEAL_KEYSTORE_ALIAS' in final
assert 'gate.authenticate(' in final
assert 'fun getMnemonic()' not in final
print('wave3 auth-bound mnemonic remediation applied successfully')
