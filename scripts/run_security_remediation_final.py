#!/usr/bin/env python3
from pathlib import Path
import re, runpy

ROOT = Path(__file__).resolve().parents[1]
# Apply the already-reviewed wave3 transformations first (dedicated auth-bound
# Keystore key + vector-tested BIP39 production KDF), then strengthen migration
# semantics and add the legacy application-secret migration.
runpy.run_path(str(Path(__file__).with_name('run_security_remediation_wave3_full.py')), run_name='__main__')

wallet = ROOT / 'android/app/src/main/java/io/raventag/app/wallet/WalletManager.kt'
wallet_test = ROOT / 'android/app/src/androidTest/java/io/raventag/app/wallet/WalletManagerTest.kt'
backup_screen = ROOT / 'android/app/src/main/java/io/raventag/app/ui/screens/MnemonicBackupScreen.kt'
main = ROOT / 'android/app/src/main/java/io/raventag/app/MainActivity.kt'

# ---------------------------------------------------------------------------
# RT-SEC-004: the persisted recovery phrase has NO general-key reveal accessor.
# The only production reveal path authenticates first, then uses the dedicated
# userAuthenticationRequired Keystore key. Migration is crash-safe/idempotent.
# ---------------------------------------------------------------------------
s = wallet.read_text(encoding='utf-8')
# Remove the temporary test-only bypass introduced by wave3; production code
# must not expose a general-wallet-key mnemonic decryption API.
s, n = re.subn(
    r'''\n    internal fun getMnemonicForTestOnly\(\): String\? \{.*?\n    \}\n\n    private fun getSeed''',
    '\n\n    private fun getSeed', s, count=1, flags=re.S
)
if n != 1:
    raise SystemExit(f'could not remove mnemonic test-only bypass: {n}')

pat = re.compile(
    r'''    /\*\*\n     \* Reveal the recovery phrase through a dedicated Keystore key.*?\n    fun getAddress\(''',
    re.S
)
replacement = '''    /**
     * Reveal a persisted recovery phrase. Authentication is established by the
     * OS first; the plaintext can then be produced only by an AES key whose
     * Android Keystore policy itself requires recent user authentication.
     *
     * Existing wallets are migrated lazily. The legacy ciphertext is retained
     * until the NEW persisted ciphertext has been read back and successfully
     * decrypted with the auth-bound key. Any interruption is safe to retry.
     */
    suspend fun revealMnemonicCharsWithBiometric(
        gate: io.raventag.app.security.BiometricGate
    ): CharArray {
        // Keep BiometricPrompt on the caller/main context; all failures/cancel
        // paths throw and therefore fail closed before legacy decryption starts.
        gate.authenticate(
            io.raventag.app.R.string.biometricRevealTitle,
            io.raventag.app.R.string.biometricRevealSubtitle
        )

        return withContext(Dispatchers.IO) {
            val p = prefs()
            val revealKey = wrapKeystoreException { getOrCreateMnemonicRevealKey() }
            var plaintext: ByteArray? = null
            try {
                val authCtB64 = p.getString(KEY_MNEMONIC_AUTH_ENC, null)
                val authIvB64 = p.getString(KEY_MNEMONIC_AUTH_IV, null)
                check((authCtB64 == null) == (authIvB64 == null)) {
                    "incomplete auth-bound mnemonic state"
                }

                if (authCtB64 != null && authIvB64 != null) {
                    val ct = android.util.Base64.decode(authCtB64, android.util.Base64.NO_WRAP)
                    val iv = android.util.Base64.decode(authIvB64, android.util.Base64.NO_WRAP)
                    plaintext = wrapKeystoreException {
                        Cipher.getInstance("AES/GCM/NoPadding").run {
                            init(Cipher.DECRYPT_MODE, revealKey, GCMParameterSpec(128, iv))
                            doFinal(ct)
                        }
                    }

                    // Crash/interruption recovery: if a previous migration wrote a
                    // verified new copy but stopped before legacy cleanup, this
                    // successful auth-bound decrypt proves cleanup is now safe.
                    if (p.contains(KEY_MNEMONIC_ENC) || p.contains(KEY_MNEMONIC_IV)) {
                        check(p.edit()
                            .remove(KEY_MNEMONIC_ENC)
                            .remove(KEY_MNEMONIC_IV)
                            .remove(KEY_MNEMONIC_HMAC)
                            .commit()) { "could not remove verified legacy mnemonic copy" }
                    }
                } else {
                    val legacyCtB64 = p.getString(KEY_MNEMONIC_ENC, null)
                        ?: throw IllegalStateException("no mnemonic stored")
                    val legacyIvB64 = p.getString(KEY_MNEMONIC_IV, null)
                        ?: throw IllegalStateException("no mnemonic iv stored")
                    val legacy = decrypt(
                        android.util.Base64.decode(legacyCtB64, android.util.Base64.DEFAULT),
                        android.util.Base64.decode(legacyIvB64, android.util.Base64.DEFAULT)
                    )
                    try {
                        // Authenticate the legacy value before migrating it.
                        p.getString(KEY_MNEMONIC_HMAC, null)?.let { tagB64 ->
                            verifySeedHmacInstance(
                                legacy,
                                android.util.Base64.decode(tagB64, android.util.Base64.NO_WRAP)
                            )
                        }

                        val encCipher = Cipher.getInstance("AES/GCM/NoPadding")
                        encCipher.init(Cipher.ENCRYPT_MODE, revealKey)
                        val newCt = encCipher.doFinal(legacy)
                        val newIv = encCipher.iv

                        // Verify before persistence.
                        val localVerified = Cipher.getInstance("AES/GCM/NoPadding").run {
                            init(Cipher.DECRYPT_MODE, revealKey, GCMParameterSpec(128, newIv))
                            doFinal(newCt)
                        }
                        try {
                            check(MessageDigest.isEqual(legacy, localVerified)) {
                                "mnemonic auth-bound migration verification failed"
                            }
                        } finally {
                            localVerified.fill(0)
                        }

                        check(p.edit()
                            .putString(KEY_MNEMONIC_AUTH_ENC, android.util.Base64.encodeToString(newCt, android.util.Base64.NO_WRAP))
                            .putString(KEY_MNEMONIC_AUTH_IV, android.util.Base64.encodeToString(newIv, android.util.Base64.NO_WRAP))
                            .commit()) { "could not persist authenticated mnemonic representation" }

                        // Mandatory persisted read-back verification. Only after
                        // this succeeds may the legacy representation be deleted.
                        val persistedCt = android.util.Base64.decode(
                            p.getString(KEY_MNEMONIC_AUTH_ENC, null)
                                ?: error("persisted authenticated mnemonic missing"),
                            android.util.Base64.NO_WRAP
                        )
                        val persistedIv = android.util.Base64.decode(
                            p.getString(KEY_MNEMONIC_AUTH_IV, null)
                                ?: error("persisted authenticated mnemonic IV missing"),
                            android.util.Base64.NO_WRAP
                        )
                        val persistedVerified = Cipher.getInstance("AES/GCM/NoPadding").run {
                            init(Cipher.DECRYPT_MODE, revealKey, GCMParameterSpec(128, persistedIv))
                            doFinal(persistedCt)
                        }
                        try {
                            check(MessageDigest.isEqual(legacy, persistedVerified)) {
                                "persisted mnemonic migration verification failed"
                            }
                        } finally {
                            persistedVerified.fill(0)
                        }

                        check(p.edit()
                            .remove(KEY_MNEMONIC_ENC)
                            .remove(KEY_MNEMONIC_IV)
                            .remove(KEY_MNEMONIC_HMAC)
                            .commit()) { "could not remove verified legacy mnemonic copy" }
                        plaintext = legacy.copyOf()
                    } finally {
                        legacy.fill(0)
                    }
                }

                val bytes = checkNotNull(plaintext) { "mnemonic decrypt produced no plaintext" }
                val chars = Charsets.UTF_8.decode(java.nio.ByteBuffer.wrap(bytes))
                CharArray(chars.remaining()).also { chars.get(it) }
            } finally {
                plaintext?.fill(0)
            }
        }
    }

    fun getAddress('''
s, n = pat.subn(lambda _: replacement, s, count=1)
if n != 1:
    raise SystemExit(f'final auth-bound reveal replacement count={n}')
wallet.write_text(s, encoding='utf-8')

# Remove instrumentation test that relied on the prohibited general-key accessor.
t = wallet_test.read_text(encoding='utf-8')
t, n = re.subn(
    r'''\n    @Test\n    fun getMnemonic_afterRestore_returnsOriginalMnemonic\(\) \{.*?\n    \}\n''',
    '\n', t, count=1, flags=re.S
)
if n != 1:
    raise SystemExit(f'legacy getMnemonic instrumentation test removal count={n}')
wallet_test.write_text(t, encoding='utf-8')

# Fresh-wallet phrase display is also fail-closed behind a real OS auth prompt,
# even though it is an in-memory value rather than a stored ciphertext.
b = backup_screen.read_text(encoding='utf-8')
old = '''    if (!prefillMnemonic.isNullOrBlank()) {
        // Setup flow: the wallet has been generated but not yet persisted; the biometric
        // cover card acts as a tap-through confirmation (D-15 CryptoObject cannot bind
        // to a ciphertext that does not yet exist).
        onRevealed(prefillMnemonic.toCharArray())
        return
    }
    if (wm == null) {
'''
new = '''    if (!prefillMnemonic.isNullOrBlank()) {
        val activity = context as? FragmentActivity
        if (activity == null) {
            snackbarHostState.showSnackbar(strings.mnemonicRevealFailed)
            return
        }
        try {
            BiometricGate(activity).authenticate(
                io.raventag.app.R.string.biometricRevealTitle,
                io.raventag.app.R.string.biometricRevealSubtitle
            )
            onRevealed(prefillMnemonic.toCharArray())
        } catch (_: BiometricCancelledException) {
            snackbarHostState.showSnackbar(strings.authCanceledSnackbar)
        } catch (_: Throwable) {
            snackbarHostState.showSnackbar(strings.mnemonicRevealFailed)
        }
        return
    }
    if (wm == null) {
'''
if b.count(old) != 1:
    raise SystemExit('fresh mnemonic reveal anchor mismatch')
b = b.replace(old, new, 1)
b = b.replace(
    'we skip the Keystore round-trip because the mnemonic is already in memory and no\n * ciphertext exists yet. In later-reveal mode we delegate to [MnemonicExporter].',
    'the phrase is not yet encrypted, but a genuine OS authentication is still required\n * before display. Persisted later-reveal mode delegates to [MnemonicExporter] and the\n * auth-required Keystore key.'
)
backup_screen.write_text(b, encoding='utf-8')

# ---------------------------------------------------------------------------
# RT-SEC-005: small pure migration primitive + JVM regression tests.
# ---------------------------------------------------------------------------
migration = ROOT / 'android/app/src/main/java/io/raventag/app/security/LegacySecretMigration.kt'
migration.write_text(r'''package io.raventag.app.security

/**
 * Crash-safe migration of the exact secret-bearing keys historically stored in
 * the legacy `raventag_secure` preference file.
 *
 * The caller supplies storage operations so the state machine is JVM-testable.
 * A source value is removed only after the destination was written and read back
 * byte-for-byte (String equality here). If deletion is interrupted, a repeated
 * invocation sees the already-verified destination and safely finishes cleanup.
 */
object LegacySecretMigration {
    val SENSITIVE_KEYS: Set<String> = setOf(
        "admin_key",
        "operator_key",
        "initial_master_key",
        "pinata_jwt"
    )

    data class Outcome(val migrated: Int, val cleanedUp: Int)

    fun migrate(
        readLegacy: (String) -> String?,
        writeSecure: (String, String) -> Unit,
        readSecure: (String) -> String?,
        removeLegacy: (String) -> Unit
    ): Outcome {
        var migrated = 0
        var cleaned = 0
        for (key in SENSITIVE_KEYS) {
            val legacy = readLegacy(key) ?: continue
            val existing = readSecure(key)
            if (existing != legacy) {
                writeSecure(key, legacy)
                check(readSecure(key) == legacy) {
                    "secure migration read-back mismatch for $key"
                }
                migrated++
            }
            // If a previous run stopped after verified write, equality above lets
            // this run resume at cleanup without overwriting or losing the source.
            removeLegacy(key)
            check(readLegacy(key) == null) {
                "legacy secret cleanup did not persist for $key"
            }
            cleaned++
        }
        return Outcome(migrated, cleaned)
    }
}
''', encoding='utf-8')

migration_test = ROOT / 'android/app/src/test/java/io/raventag/app/security/LegacySecretMigrationTest.kt'
migration_test.parent.mkdir(parents=True, exist_ok=True)
migration_test.write_text(r'''package io.raventag.app.security

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class LegacySecretMigrationTest {
    private class Store(initial: Map<String, String> = emptyMap()) {
        val values = initial.toMutableMap()
        var failWrite = false
        var failRemoveOnce = false
        fun read(k: String): String? = values[k]
        fun write(k: String, v: String) {
            if (failWrite) throw IllegalStateException("secure store unavailable")
            values[k] = v
        }
        fun remove(k: String) {
            if (failRemoveOnce) {
                failRemoveOnce = false
                throw IllegalStateException("interrupted before cleanup")
            }
            values.remove(k)
        }
    }

    private fun run(legacy: Store, secure: Store) = LegacySecretMigration.migrate(
        legacy::read, secure::write, secure::read, legacy::remove
    )

    @Test fun noLegacyValuesIsNoOp() {
        val out = run(Store(), Store())
        assertEquals(0, out.migrated)
        assertEquals(0, out.cleanedUp)
    }

    @Test fun successfulMigrationVerifiesThenDeletesLegacy() {
        val legacy = Store(mapOf("operator_key" to "secret"))
        val secure = Store()
        val out = run(legacy, secure)
        assertEquals("secret", secure.read("operator_key"))
        assertNull(legacy.read("operator_key"))
        assertEquals(1, out.migrated)
        assertEquals(1, out.cleanedUp)
    }

    @Test fun encryptedStoreFailurePreservesLegacy() {
        val legacy = Store(mapOf("pinata_jwt" to "jwt"))
        val secure = Store().also { it.failWrite = true }
        assertThrows(IllegalStateException::class.java) { run(legacy, secure) }
        assertEquals("jwt", legacy.read("pinata_jwt"))
    }

    @Test fun interruptedMigrationIsSafelyResumed() {
        val legacy = Store(mapOf("initial_master_key" to "master"))
        val secure = Store()
        legacy.failRemoveOnce = true
        assertThrows(IllegalStateException::class.java) { run(legacy, secure) }
        assertEquals("master", legacy.read("initial_master_key"))
        assertEquals("master", secure.read("initial_master_key"))
        val second = run(legacy, secure)
        assertNull(legacy.read("initial_master_key"))
        assertEquals(0, second.migrated)
        assertEquals(1, second.cleanedUp)
    }

    @Test fun repeatedMigrationIsIdempotent() {
        val legacy = Store(mapOf("admin_key" to "admin"))
        val secure = Store()
        run(legacy, secure)
        val second = run(legacy, secure)
        assertEquals("admin", secure.read("admin_key"))
        assertEquals(0, second.migrated)
        assertEquals(0, second.cleanedUp)
    }
}
''', encoding='utf-8')

# MainActivity: v2 encrypted store, explicit migration from both historic raw
# fallback and encrypted-v1 representations, Kubo URL kept as non-secret config.
m = main.read_text(encoding='utf-8')
if 'import io.raventag.app.security.AdminKeyStorage\n' not in m:
    raise SystemExit('AdminKeyStorage import anchor missing')
m = m.replace(
    'import io.raventag.app.security.AdminKeyStorage\n',
    'import io.raventag.app.security.AdminKeyStorage\nimport io.raventag.app.security.LegacySecretMigration\n', 1
)
old = '''            val securePrefsDeferred = async {
                val masterKey = MasterKey.Builder(this@MainActivity)
                    .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                    .build()
                EncryptedSharedPreferences.create(
                    this@MainActivity,
                    "raventag_secure",
                    masterKey,
                    EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                    EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
                )
            }
'''
new = '''            val securePrefsDeferred = async {
                val masterKey = MasterKey.Builder(this@MainActivity)
                    .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                    .build()
                val encryptedV2 = EncryptedSharedPreferences.create(
                    this@MainActivity,
                    "raventag_secure_v2",
                    masterKey,
                    EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                    EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
                )

                fun migrateFrom(source: android.content.SharedPreferences) {
                    LegacySecretMigration.migrate(
                        readLegacy = { source.getString(it, null) },
                        writeSecure = { key, value ->
                            check(encryptedV2.edit().putString(key, value).commit()) {
                                "secure preference write failed for $key"
                            }
                        },
                        readSecure = { encryptedV2.getString(it, null) },
                        removeLegacy = { key ->
                            check(source.edit().remove(key).commit()) {
                                "legacy preference cleanup failed for $key"
                            }
                        }
                    )
                }

                // Historical fail-open releases could write the literal key names
                // into an ordinary SharedPreferences file of this name.
                val rawLegacy = getSharedPreferences("raventag_secure", MODE_PRIVATE)
                migrateFrom(rawLegacy)

                // Later releases used EncryptedSharedPreferences under the same v1
                // file name. Migrate those values too; if the old encrypted store
                // cannot be opened, fail closed and leave it untouched.
                val encryptedV1 = EncryptedSharedPreferences.create(
                    this@MainActivity,
                    "raventag_secure",
                    masterKey,
                    EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                    EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
                )
                migrateFrom(encryptedV1)

                // Kubo URL is configuration, not a credential. Preserve it outside
                // the secret migration whitelist.
                if (!prefs.contains("kubo_node_url")) {
                    val oldKubo = rawLegacy.getString("kubo_node_url", null)
                        ?: encryptedV1.getString("kubo_node_url", null)
                    if (!oldKubo.isNullOrBlank()) {
                        check(prefs.edit().putString("kubo_node_url", oldKubo).commit())
                    }
                }
                encryptedV2
            }
'''
if m.count(old) != 1:
    raise SystemExit(f'securePrefsDeferred anchor count={m.count(old)}')
m = m.replace(old, new, 1)

old = '''            val initializedAdmin = adminKeyStorageDeferred.await()
            val initializedAdminKeyStorage = initializedAdmin.first
            val initializedAdminKey = initializedAdmin.second
            val initializedMasterKey = initializedSecurePrefs.getString("initial_master_key", "") ?: ""
'''
new = '''            val initializedAdmin = adminKeyStorageDeferred.await()
            val initializedAdminKeyStorage = initializedAdmin.first
            var initializedAdminKey = initializedAdmin.second
            // Legacy admin_key is migrated through v2 only as a crash-safe bridge,
            // then moved into its dedicated encrypted AdminKeyStorage.
            val migratedAdminKey = initializedSecurePrefs.getString("admin_key", "").orEmpty()
            if (initializedAdminKey.isBlank() && migratedAdminKey.isNotBlank()) {
                initializedAdminKeyStorage.setAdminKey(migratedAdminKey)
                check(initializedAdminKeyStorage.getAdminKey() == migratedAdminKey) {
                    "admin key migration verification failed"
                }
                initializedAdminKey = migratedAdminKey
            }
            if (migratedAdminKey.isNotBlank()) {
                check(initializedSecurePrefs.edit().remove("admin_key").commit())
            }
            val initializedMasterKey = initializedSecurePrefs.getString("initial_master_key", "") ?: ""
'''
if m.count(old) != 1: raise SystemExit('admin migration init anchor mismatch')
m = m.replace(old, new, 1)
m = m.replace(
    '            val initializedKuboNodeUrl = initializedSecurePrefs.getString("kubo_node_url", "") ?: ""\n',
    '            val initializedKuboNodeUrl = prefs.getString("kubo_node_url", "") ?: ""\n', 1
)
old_admin = '''                                            if (role == "admin") {
                                                securePrefs.edit().putString("admin_key", key).putString("operator_key", "").apply()
                                                savedAdminKey = key; savedOperatorKey = ""
                                                viewModel.adminKeyStatus = MainViewModel.AdminKeyStatus.VALID
                                                viewModel.operatorKeyStatus = MainViewModel.AdminKeyStatus.UNKNOWN
                                                try { viewModel.adminKeyStorage?.setAdminKey(key) } catch (_: Throwable) {}
                                            } else {
                                                securePrefs.edit().putString("operator_key", key).putString("admin_key", "").apply()
                                                savedOperatorKey = key; savedAdminKey = ""
                                                viewModel.operatorKeyStatus = MainViewModel.AdminKeyStatus.VALID
                                                viewModel.adminKeyStatus = MainViewModel.AdminKeyStatus.UNKNOWN
                                            }
'''
new_admin = '''                                            if (role == "admin") {
                                                check(securePrefs.edit().remove("operator_key").commit())
                                                viewModel.adminKeyStorage?.setAdminKey(key)
                                                check(viewModel.adminKeyStorage?.getAdminKey() == key)
                                                savedAdminKey = key; savedOperatorKey = ""
                                                viewModel.adminKeyStatus = MainViewModel.AdminKeyStatus.VALID
                                                viewModel.operatorKeyStatus = MainViewModel.AdminKeyStatus.UNKNOWN
                                            } else {
                                                check(securePrefs.edit().putString("operator_key", key).commit())
                                                viewModel.adminKeyStorage?.clearAdminKey()
                                                savedOperatorKey = key; savedAdminKey = ""
                                                viewModel.operatorKeyStatus = MainViewModel.AdminKeyStatus.VALID
                                                viewModel.adminKeyStatus = MainViewModel.AdminKeyStatus.UNKNOWN
                                            }
'''
if m.count(old_admin) != 1: raise SystemExit('wallet role secret save anchor mismatch')
m = m.replace(old_admin, new_admin, 1)
m = m.replace(
    '                                            securePrefs.edit().putString("kubo_node_url", url).apply()\n',
    '                                            prefs.edit().putString("kubo_node_url", url).apply()\n', 1
)
main.write_text(m, encoding='utf-8')

# Deployment documentation: explicitly generate a separate backup key and run
# the backup sidecar in normal compose deployments.
doc = ROOT / 'docs/deploy/en.md'
d = doc.read_text(encoding='utf-8')
anchor = 'openssl rand -hex 16 > secrets/brand_salt\n```'
if anchor not in d: raise SystemExit('deploy secret-generation anchor missing')
d = d.replace(anchor, '''openssl rand -hex 16 > secrets/brand_salt

# Backup encryption key — MUST be independent from admin/operator/brand keys
openssl rand -hex 32 > secrets/backup_encryption_key.txt
```''', 1)
d = d.replace(
    'docker compose up -d backend\ndocker compose logs backend',
    'docker compose up -d\ndocker compose logs backend\ndocker compose logs backup', 1
)
insert_anchor = 'Verify it is running:\n\n```bash\ncurl http://localhost:3001/health'
if insert_anchor not in d: raise SystemExit('deploy verification anchor missing')
d = d.replace(insert_anchor, '''The `backup` sidecar snapshots SQLite every **24 hours**, encrypts each snapshot with
`secrets/backup_encryption_key.txt`, and retains the **7 newest** encrypted backups.
Never reuse `secrets/admin_key` (or any authentication credential) as the backup key.

Verify it is running:

```bash
curl http://localhost:3001/health''', 1)
doc.write_text(d, encoding='utf-8')

env = ROOT / '.env.example'
e = env.read_text(encoding='utf-8')
needle = '# Generate the file yourself, e.g. with: openssl rand -hex 32 > secrets/backup_encryption_key.txt\n'
if needle not in e: raise SystemExit('.env backup comment anchor missing')
e = e.replace(needle, needle + '# NEVER point this setting at admin_key/operator_key/brand key material.\n', 1)
env.write_text(e, encoding='utf-8')

# Static postconditions for the transformation itself.
wf = wallet.read_text(encoding='utf-8')
ma = main.read_text(encoding='utf-8')
bs = backup_screen.read_text(encoding='utf-8')
assert '.setUserAuthenticationRequired(true)' in wf
assert 'MNEMONIC_REVEAL_KEYSTORE_ALIAS' in wf
assert 'persisted mnemonic migration verification failed' in wf
assert '.remove(KEY_MNEMONIC_ENC)' in wf
assert 'getMnemonicForTestOnly' not in wf
assert 'fun getMnemonic()' not in wf
assert 'raventag_secure_v2' in ma
assert 'LegacySecretMigration.migrate' in ma
assert 'securePrefs.edit().putString("admin_key"' not in ma
assert 'securePrefs.edit().putString("kubo_node_url"' not in ma
assert 'BiometricGate(activity).authenticate' in bs
print('final Android secret/Keystore remediation applied successfully')
