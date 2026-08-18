#!/usr/bin/env python3
from pathlib import Path
import re

ROOT = Path(__file__).resolve().parents[1]

def read(path):
    return (ROOT / path).read_text(encoding='utf-8')

def write(path, text):
    (ROOT / path).write_text(text, encoding='utf-8')

def replace_once(path, old, new):
    text = read(path)
    count = text.count(old)
    if count != 1:
        raise SystemExit(f'{path}: expected exactly one match, got {count}: {old[:100]!r}')
    write(path, text.replace(old, new, 1))

def replace_all_required(path, old, new, minimum=1):
    text = read(path)
    count = text.count(old)
    if count < minimum:
        raise SystemExit(f'{path}: expected >= {minimum} matches, got {count}: {old[:100]!r}')
    write(path, text.replace(old, new))
    return count

def regex_once(path, pattern, replacement, flags=re.S):
    text = read(path)
    new, count = re.subn(pattern, replacement, text, count=1, flags=flags)
    if count != 1:
        raise SystemExit(f'{path}: regex expected one match, got {count}: {pattern[:100]!r}')
    write(path, new)

# ---------------------------------------------------------------------------
# RT-SEC-003 / RT-SEC-005: authentication and secure preference storage fail closed
# ---------------------------------------------------------------------------
main = 'android/app/src/main/java/io/raventag/app/MainActivity.kt'
replace_once(
    main,
    '    private var securePrefsReady by mutableStateOf(false)\n',
    '    private var securePrefsReady by mutableStateOf(false)\n\n'
    '    /** Non-null when encrypted secret storage cannot be initialized. */\n'
    '    private var secureStorageError by mutableStateOf<String?>(null)\n'
)

regex_once(
    main,
    r'    fun requestBiometricAuth\(\n        title: String,\n        subtitle: String,\n        onSuccess: \(\) -> Unit,\n        onError: \(\) -> Unit\n    \) \{.*?\n    \}\n\n    override fun onCreate',
    '''    fun requestBiometricAuth(
        title: String,
        subtitle: String,
        onSuccess: () -> Unit,
        onError: () -> Unit
    ) {
        val authenticators = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            BiometricManager.Authenticators.BIOMETRIC_STRONG or
                BiometricManager.Authenticators.DEVICE_CREDENTIAL
        } else {
            // DEVICE_CREDENTIAL combinations are not consistently supported by
            // BiometricPrompt on API 26-29; require a strong biometric instead.
            BiometricManager.Authenticators.BIOMETRIC_STRONG
        }

        // Authentication unavailable is a failure, never evidence of identity.
        if (BiometricManager.from(this).canAuthenticate(authenticators) != BiometricManager.BIOMETRIC_SUCCESS) {
            onError()
            return
        }

        try {
            val executor = ContextCompat.getMainExecutor(this)
            val callback = object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    onSuccess()
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    // Every error path (cancel, lockout, unavailable hardware,
                    // enrollment problem, negative button, platform failure) stays locked.
                    onError()
                }

                override fun onAuthenticationFailed() {
                    // A failed attempt does not unlock. BiometricPrompt may allow another attempt.
                }
            }
            val builder = BiometricPrompt.PromptInfo.Builder()
                .setTitle(title)
                .setSubtitle(subtitle)
                .setAllowedAuthenticators(authenticators)
            if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.R) {
                builder.setNegativeButtonText("Annulla")
            }
            BiometricPrompt(this, executor, callback).authenticate(builder.build())
        } catch (_: Exception) {
            // Prompt initialization/platform errors fail closed.
            onError()
        }
    }

    override fun onCreate'''
)

replace_once(
    main,
    '''            val securePrefsDeferred = async {
                try {
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
                } catch (_: Throwable) {
                    // Fallback to plain prefs if Keystore unavailable (e.g. work profile restrictions)
                    getSharedPreferences("raventag_secure", MODE_PRIVATE)
                }
            }
''',
    '''            val securePrefsDeferred = async {
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
)

replace_once(
    main,
    '            val initializedSecurePrefs = securePrefsDeferred.await()\n',
    '''            val initializedSecurePrefs = try {
                securePrefsDeferred.await()
            } catch (t: Throwable) {
                Log.e("MainActivity", "Encrypted secret storage unavailable", t)
                withContext(Dispatchers.Main) {
                    secureStorageError = "Secure storage is unavailable. RavenTag will not load secret-bearing functionality until Android Keystore access is restored."
                    securePrefsReady = true
                }
                return@launch
            }
'''
)

replace_once(
    main,
    '''        setContent {
            // Hold content until EncryptedSharedPreferences is ready (avoids reading null keys)
            if (!securePrefsReady) return@setContent

            // Persisted user preferences''',
    '''        setContent {
            // Hold content until EncryptedSharedPreferences is ready (avoids reading null keys).
            if (!securePrefsReady) return@setContent
            secureStorageError?.let { message ->
                MaterialTheme {
                    Surface(modifier = Modifier.fillMaxSize(), color = Color.Black) {
                        Box(modifier = Modifier.fillMaxSize().padding(24.dp)) {
                            Text(message, color = Color.White, style = MaterialTheme.typography.bodyLarge)
                        }
                    }
                }
                return@setContent
            }

            // Persisted user preferences'''
)

# ---------------------------------------------------------------------------
# RT-SEC-009: recovery phrase never enters the Android clipboard
# ---------------------------------------------------------------------------
mnemonic_screen = 'android/app/src/main/java/io/raventag/app/ui/screens/MnemonicBackupScreen.kt'
for unused in [
    'import androidx.compose.ui.platform.LocalClipboardManager\n',
    'import androidx.compose.ui.text.AnnotatedString\n',
    'import kotlinx.coroutines.delay\n',
]:
    replace_once(mnemonic_screen, unused, '')
replace_once(mnemonic_screen, '    val clipboard = LocalClipboardManager.current\n', '')
replace_once(mnemonic_screen, '    var copied by remember { mutableStateOf(false) }\n', '')
regex_once(
    mnemonic_screen,
    r'''\n            // Copy All: briefly copies to clipboard; cleared after 60s\.\n            OutlinedButton\(.*?\n            Spacer\(modifier = Modifier\.height\(24\.dp\)\)''',
    '\n            // Deliberately no clipboard/export action: the full BIP39 phrase remains inside RavenTag.\n\n            Spacer(modifier = Modifier.height(24.dp))'
)

# ---------------------------------------------------------------------------
# RT-SEC-019: BIP39 NFKD normalization
# ---------------------------------------------------------------------------
wallet = 'android/app/src/main/java/io/raventag/app/wallet/WalletManager.kt'
replace_once(
    wallet,
    '            val words = input.trim().split(Regex("\\\\s+")).filter { it.isNotEmpty() }\n',
    '            val normalized = java.text.Normalizer.normalize(input, java.text.Normalizer.Form.NFKD)\n'
    '            val words = normalized.trim().split(Regex("\\\\s+")).filter { it.isNotEmpty() }\n'
)
regex_once(
    wallet,
    r'''    private fun mnemonicToSeed\(mnemonic: String, passphrase: String\): ByteArray \{.*?\n    \}\n\n    suspend fun healAndSweepTarget''',
    '''    private fun mnemonicToSeed(mnemonic: String, passphrase: String): ByteArray {
        // BIP39 requires UTF-8 NFKD normalization of both mnemonic and passphrase.
        val normalizedMnemonic = java.text.Normalizer.normalize(mnemonic, java.text.Normalizer.Form.NFKD)
        val normalizedPassphrase = java.text.Normalizer.normalize(passphrase, java.text.Normalizer.Form.NFKD)
        val saltBytes = ("mnemonic" + normalizedPassphrase).toByteArray(Charsets.UTF_8)
        val password = normalizedMnemonic.toCharArray()
        val spec = javax.crypto.spec.PBEKeySpec(password, saltBytes, 2048, 512)
        return try {
            javax.crypto.SecretKeyFactory.getInstance("PBKDF2WithHmacSHA512")
                .generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
            java.util.Arrays.fill(password, '\\u0000')
            saltBytes.fill(0)
        }
    }

    suspend fun healAndSweepTarget'''
)

# ---------------------------------------------------------------------------
# RT-SEC-006: one canonical Ravencoin-mainnet P2PKH address policy
# ---------------------------------------------------------------------------
txb = 'android/app/src/main/java/io/raventag/app/wallet/RavencoinTxBuilder.kt'
replace_once(
    txb,
    '''    private fun p2pkhScript(address: String): ByteArray {
        val decoded = base58Decode(address)
        val pubKeyHash = decoded.copyOfRange(1, 21) // skip version byte 0x3C
        return byteArrayOf(''',
    '''    internal fun requireRavencoinMainnetP2pkh(address: String): ByteArray {
        require(address.length in 26..50) { "Invalid Ravencoin address length" }
        val decoded = base58Decode(address)
        require(decoded.size == 25) { "Invalid Ravencoin address structure" }
        require(decoded[0] == 0x3c.toByte()) { "Address is not Ravencoin mainnet P2PKH" }
        require(base58Encode(decoded) == address) { "Non-canonical Ravencoin address" }
        return decoded.copyOfRange(1, 21)
    }

    internal fun isValidRavencoinMainnetP2pkh(address: String): Boolean =
        runCatching { requireRavencoinMainnetP2pkh(address); true }.getOrDefault(false)

    internal fun p2pkhScriptHexForAddress(address: String): String =
        "76a914" + requireRavencoinMainnetP2pkh(address).joinToString("") { "%02x".format(it) } + "88ac"

    private fun p2pkhScript(address: String): ByteArray {
        val pubKeyHash = requireRavencoinMainnetP2pkh(address)
        return byteArrayOf('''
)

# All asset-script address decoders must use the same network validator.
replace_all_required(
    txb,
    '        val decoded = base58Decode(address)\n        val hash160 = decoded.copyOfRange(1, 21)\n',
    '        val hash160 = requireRavencoinMainnetP2pkh(address)\n',
    minimum=2
)

replace_once(
    txb,
    '''    private fun base58Decode(input: String): ByteArray {''',
    '''    private fun base58Encode(data: ByteArray): String {
        var num = BigInteger(1, data)
        val base = BigInteger.valueOf(58)
        val out = StringBuilder()
        while (num > BigInteger.ZERO) {
            val qr = num.divideAndRemainder(base)
            out.append(B58[qr[1].toInt()])
            num = qr[0]
        }
        for (b in data) {
            if (b == 0.toByte()) out.append(B58[0]) else break
        }
        return out.reverse().toString()
    }

    private fun base58Decode(input: String): ByteArray {'''
)

# Wallet helper should no longer bypass the shared address validator.
regex_once(
    wallet,
    r'''    private fun addressToP2pkhScript\(address: String\): String \{.*?\n    \}''',
    '''    private fun addressToP2pkhScript(address: String): String =
        RavencoinTxBuilder.p2pkhScriptHexForAddress(address)'''
)

# ElectrumX scripthash conversion also validates the Ravencoin network.
public_node = 'android/app/src/main/java/io/raventag/app/wallet/RavencoinPublicNode.kt'
replace_once(
    public_node,
    '''        val decoded = base58Decode(address)
        require(decoded.size == 25) { "Invalid Ravencoin address (decoded=${decoded.size} bytes)" }
        val hash160 = decoded.copyOfRange(1, 21)''',
    '''        val hash160 = RavencoinTxBuilder.requireRavencoinMainnetP2pkh(address)'''
)
replace_once(
    public_node,
    '''        val decoded = base58Decode(address)
        val hash160 = decoded.copyOfRange(1, 21)
        return "76a914" + hash160.joinToString("") { "%02x".format(it) } + "88ac"''',
    '''        return RavencoinTxBuilder.p2pkhScriptHexForAddress(address)'''
)

# ---------------------------------------------------------------------------
# RT-SEC-002/P2: relay-fee input validation and bounded ElectrumX lines
# ---------------------------------------------------------------------------
regex_once(
    public_node,
    r'''    fun getMinRelayFeeRateSatPerByte\(\): Long \{.*?\n    \}\n\n    /\*\*\n     \* Returns the current Ravencoin chain tip''',
    '''    fun getMinRelayFeeRateSatPerByte(): Long {
        val results = SERVERS.mapNotNull { server ->
            try {
                val rvnPerKb = call(server, "blockchain.relayfee", emptyList()).asDouble
                val safe = FeeSafetyPolicy.sanitizeRelayFeeRvnPerKb(rvnPerKb)
                Log.d(TAG, "relayfee ${server.host}: $rvnPerKb RVN/kB -> $safe sat/byte after local policy")
                safe
            } catch (e: Exception) {
                Log.w(TAG, "relayfee rejected/failed for ${server.host}: ${e.message}")
                null
            }
        }
        if (results.isEmpty()) throw FeeUnavailableException()
        return results.min()
    }

    /**
     * Returns the current Ravencoin chain tip'''
)

# Add a bounded line reader in the companion/object scope immediately before internal helpers marker.
replace_once(
    public_node,
    '    // Internal helpers ────────────────────────────────────────────────────────\n',
    '''    private fun readBoundedLine(reader: BufferedReader, host: String): String? {
        val out = StringBuilder()
        while (true) {
            val ch = reader.read()
            if (ch == -1) return if (out.isEmpty()) null else out.toString()
            if (ch == '\\n'.code) return out.toString()
            if (ch != '\\r'.code) {
                require(out.length < MAX_RESPONSE_LINE_CHARS) { "Oversized ElectrumX response from $host" }
                out.append(ch.toChar())
            }
        }
    }

    // Internal helpers ────────────────────────────────────────────────────────
'''
)
# Define limit beside existing batch limit.
text = read(public_node)
if 'MAX_RESPONSE_LINE_CHARS' not in text.split('private fun readBoundedLine')[0]:
    # Locate BATCH_CHUNK_SIZE declaration without relying on exact numeric value.
    text, n = re.subn(r'(private const val BATCH_CHUNK_SIZE\s*=\s*\d+[^\n]*\n)', r'\1        private const val MAX_RESPONSE_LINE_CHARS = 1_048_576\n', text, count=1)
    if n != 1:
        raise SystemExit('RavencoinPublicNode: could not place MAX_RESPONSE_LINE_CHARS')
    write(public_node, text)
replace_all_required(public_node, 'reader.readLine()', 'readBoundedLine(reader, server.host)', minimum=3)

subscription = 'android/app/src/main/java/io/raventag/app/wallet/subscription/SubscriptionManager.kt'
replace_once(
    subscription,
    '        private const val TAG = "SubscriptionManager"\n',
    '        private const val TAG = "SubscriptionManager"\n        private const val MAX_RESPONSE_LINE_CHARS = 1_048_576\n'
)
replace_once(
    subscription,
    '                val line = withContext(Dispatchers.IO) { s.reader.readLine() }\n',
    '''                val line = withContext(Dispatchers.IO) { readBoundedLine(s.reader, s.host) }
'''
)
replace_once(
    subscription,
    '    private suspend fun heartbeatLoop(s: Session) {\n',
    '''    private fun readBoundedLine(reader: BufferedReader, host: String): String? {
        val out = StringBuilder()
        while (true) {
            val ch = reader.read()
            if (ch == -1) return if (out.isEmpty()) null else out.toString()
            if (ch == '\\n'.code) return out.toString()
            if (ch != '\\r'.code) {
                require(out.length < MAX_RESPONSE_LINE_CHARS) { "Oversized ElectrumX response from $host" }
                out.append(ch.toChar())
            }
        }
    }

    private suspend fun heartbeatLoop(s: Session) {
'''
)

# ---------------------------------------------------------------------------
# RT-SEC-011: backend counter compare+advance is one atomic SQLite statement
# ---------------------------------------------------------------------------
cache = 'backend/src/middleware/cache.ts'
regex_once(
    cache,
    r'''export function checkAndUpdateCounter\(nfcPubId: string, counter: number\): boolean \{.*?\n\}''',
    '''export function checkAndUpdateCounter(nfcPubId: string, counter: number): boolean {
  if (!Number.isSafeInteger(counter) || counter < 0) return false
  const result = db.prepare(`
    INSERT INTO nfc_counters (nfc_pub_id, last_counter)
    VALUES (?, ?)
    ON CONFLICT(nfc_pub_id) DO UPDATE SET last_counter = excluded.last_counter
    WHERE excluded.last_counter > nfc_counters.last_counter
  `).run(nfcPubId, counter)
  // SQLite serializes the conditional write: exactly one concurrent request can
  // advance a given counter value. changes===0 means replay/stale counter.
  return result.changes === 1
}'''
)

# ---------------------------------------------------------------------------
# RT-SEC-015: IPFS reads never follow a redirect outside the allowlisted first hop
# ---------------------------------------------------------------------------
ipfs = 'backend/src/services/ipfs.ts'
replace_all_required(
    ipfs,
    '        maxContentLength: MAX_RESPONSE_BYTES,\n',
    '        maxContentLength: MAX_RESPONSE_BYTES,\n        maxRedirects: 0,\n',
    minimum=1
)

# ---------------------------------------------------------------------------
# P2/backend: bound ElectrumX response buffer as well
# ---------------------------------------------------------------------------
be = 'backend/src/services/electrumx.ts'
replace_once(
    be,
    "let idCounter = 1\n",
    "let idCounter = 1\nconst MAX_ELECTRUM_RESPONSE_BYTES = 1_048_576\n"
)
replace_once(
    be,
    '''      buffer += chunk.toString()
      const lines = buffer.split('\\n')''',
    '''      buffer += chunk.toString()
      if (Buffer.byteLength(buffer, 'utf8') > MAX_ELECTRUM_RESPONSE_BYTES) {
        done(new Error(`Oversized ElectrumX response from ${server.host}`))
        return
      }
      const lines = buffer.split('\\n')'''
)

# Harden backend address helper too (defense in depth).
replace_once(
    be,
    '''  if (decoded.length !== 25) throw new Error(`Invalid address length: ${decoded.length}`)

  // Extract the 20-byte RIPEMD-160 hash (skip version byte, ignore 4-byte checksum at end)
  const hash160 = decoded.slice(1, 21)''',
    '''  if (decoded.length !== 25) throw new Error(`Invalid address length: ${decoded.length}`)
  if (decoded[0] !== 0x3c) throw new Error('Address is not Ravencoin mainnet P2PKH')
  const payload = decoded.subarray(0, 21)
  const expectedChecksum = createHash('sha256').update(
    createHash('sha256').update(payload).digest()
  ).digest().subarray(0, 4)
  if (!decoded.subarray(21).equals(expectedChecksum)) throw new Error('Invalid Ravencoin address checksum')

  // Extract the 20-byte RIPEMD-160 hash
  const hash160 = decoded.slice(1, 21)'''
)

# ---------------------------------------------------------------------------
# RT-SEC-020: backup cadence/retention and credential separation
# ---------------------------------------------------------------------------
compose = 'docker-compose.yml'
text = read(compose)
text = text.replace('/run/secrets/admin_key', '/run/secrets/backup_encryption_key')
text = text.replace('sleep 21600', 'sleep 86400')
text = text.replace('tail -n +4', 'tail -n +8')
if 'backup_encryption_key:' not in text:
    # Add service secret alongside admin_key, then top-level secret definition.
    text = text.replace('      - admin_key\n', '      - admin_key\n      - backup_encryption_key\n')
    marker = '  admin_key:\n'
    idx = text.find(marker)
    if idx == -1:
        raise SystemExit('docker-compose: top-level admin_key secret not found')
    # Insert dedicated secret definition just before admin_key definition.
    text = text[:idx] + '  backup_encryption_key:\n    file: ${BACKUP_ENCRYPTION_KEY_FILE:-./secrets/backup_encryption_key.txt}\n' + text[idx:]
write(compose, text)

# ---------------------------------------------------------------------------
# RT-SEC-010: Qwen action references use the reviewed immutable upstream SHA.
# Container digest is injected by the GitHub runner via MCP_DIGEST.
# ---------------------------------------------------------------------------
qwen_sha = '05f81718976f3b7da657422e6e8e4d372b8621d7'
import os
mcp_digest = os.environ.get('MCP_DIGEST', '').strip()
if not re.fullmatch(r'sha256:[0-9a-f]{64}', mcp_digest):
    raise SystemExit(f'Invalid/unresolved MCP_DIGEST: {mcp_digest!r}')
for wf in (ROOT / '.github/workflows').glob('qwen-*.yml'):
    text = wf.read_text(encoding='utf-8')
    text = text.replace('QwenLM/qwen-code-action@v1', f'QwenLM/qwen-code-action@{qwen_sha}')
    text = text.replace('ghcr.io/github/github-mcp-server:v0.18.0', f'ghcr.io/github/github-mcp-server@{mcp_digest}')
    # Qwen API-key auth does not require GitHub OIDC in these workflows.
    text = re.sub(r'^\s*id-token:\s*write\s*\n', '', text, flags=re.M)
    wf.write_text(text, encoding='utf-8')

print('wave1 remediation patch applied successfully')
