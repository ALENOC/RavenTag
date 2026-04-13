---
phase: 10-android-security-hardening
reviewed: 2026-04-13T00:00:00Z
depth: standard
files_reviewed: 13
files_reviewed_list:
  - android/app/build.gradle.kts
  - android/app/src/main/java/io/raventag/app/MainActivity.kt
  - android/app/src/main/java/io/raventag/app/ravencoin/RpcClient.kt
  - android/app/src/main/java/io/raventag/app/security/AdminKeyStorage.kt
  - android/app/src/main/java/io/raventag/app/security/TofuFingerprintDao.kt
  - android/app/src/main/java/io/raventag/app/ui/screens/SettingsScreen.kt
  - android/app/src/main/java/io/raventag/app/wallet/AssetManager.kt
  - android/app/src/main/java/io/raventag/app/wallet/RavencoinPublicNode.kt
  - android/app/src/main/java/io/raventag/app/wallet/WalletManager.kt
  - android/app/src/main/java/io/raventag/app/worker/WalletPollingWorker.kt
  - backend/src/middleware/cache.ts
  - backend/src/middleware/logger.ts
  - backend/src/routes/admin.ts
findings:
  critical: 2
  warning: 8
  info: 5
  total: 15
status: issues_found
---

# Phase 10: Code Review Report

**Reviewed:** 2026-04-13T00:00:00Z
**Depth:** standard
**Files Reviewed:** 13
**Status:** issues_found

## Summary

Reviewed 13 source files across Android Kotlin (10 files) and TypeScript backend (3 files) for security hardening phase 10. The review identified 2 critical security issues, 8 warnings, and 5 info-level items. Key concerns include: hardcoded URLs in BuildConfig, unvalidated JSON parsing in network responses, missing error handling in several paths, and potential credential exposure in logs. Overall code quality is good with consistent patterns and thorough documentation, but several security-hardening opportunities remain.

## Critical Issues

### CR-01: Hardcoded Backend URL Exposes Attack Surface

**File:** `android/app/build.gradle.kts:35-41`
**Issue:** Backend API URL is hardcoded in BuildConfig, which is extractable from the compiled APK via static analysis tools (strings, JADX). This exposes the production backend URL and allows attackers to:
1. Directly target the backend without going through the app
2. Potentially discover API endpoints through enumeration
3. Bypass any client-side validation or rate limiting

**Fix:**
```kotlin
// Remove hardcoded URL from BuildConfig
// buildConfigField("String", "API_BASE_URL", "\"https://api.raventag.com\"")

// Instead, load from environment or secure storage at runtime
// In MainActivity.kt or MainViewModel.kt:
private val prefs = context.getSharedPreferences("raventag_app", Context.MODE_PRIVATE)
var currentVerifyUrl by mutableStateOf(
    prefs.getString("api_base_url", "https://api.raventag.com") ?: "https://api.raventag.com"
)
```

**Rationale:** The SettingsScreen already allows users to configure the backend URL. Remove the hardcoded default and require explicit configuration or use a more obfuscated approach (e.g., encrypted storage, runtime assembly).

### CR-02: JSON Parsing Without Validation in AssetManager

**File:** `android/app/src/main/java/io/raventag/app/wallet/AssetManager.kt:249-257`
**Issue:** The `adminRequest()` method parses JSON responses without validating the structure, potentially allowing:
1. Malicious server responses to cause crashes or unexpected behavior
2. Injection of unexpected data types (e.g., arrays instead of objects)
3. Security vulnerabilities if response data is used in sensitive operations

**Fix:**
```kotlin
private fun adminRequest(method: String, path: String, body: Any? = null): JsonObject {
    val rb = body?.let { gson.toJson(it).toRequestBody(json) }
    val request = Request.Builder()
        .url("$apiBaseUrl$path")
        .header("X-Admin-Key", adminKey)
        .apply {
            when (method) {
                "POST" -> post(rb ?: "{}".toRequestBody(json))
                "DELETE" -> delete(rb)
                else -> get()
            }
        }
        .build()

    val response = http.newCall(request).execute()
    val bodyStr = response.body?.string() ?: "{}"

    // Validate response is JSON before parsing
    val obj = try {
        gson.fromJson(bodyStr, JsonObject::class.java)
    } catch (e: Exception) {
        throw IOException("Invalid JSON response from server")
    }

    // Validate expected structure based on endpoint
    if (!obj.has("success") || obj["success"] == null) {
        throw IOException("Missing 'success' field in response")
    }

    if (!response.isSuccessful) {
        val errMsg = obj["error"]?.asString ?: "HTTP ${response.code}"
        throw IOException(errMsg)
    }
    return obj
}
```

**Rationale:** Add structural validation to ensure responses match expected format before processing. This prevents crashes and potential security issues from malformed or malicious server responses.

## Warnings

### WR-01: Missing Null Check in TofuFingerprintDao

**File:** `android/app/src/main/java/io/raventag/app/security/TofuFingerprintDao.kt:72-84`
**Issue:** `getFingerprint()` assumes `db` is non-null due to the early return, but if `db` becomes null after the check (unlikely but possible in multi-threaded scenarios), it could crash.

**Fix:**
```kotlin
fun getFingerprint(host: String): String? {
    val db = db ?: return null
    return try {
        val cursor = db.query(
            CERT_TABLE,
            arrayOf("fingerprint"),
            "host = ?",
            arrayOf(host),
            null, null, null
        )
        cursor.use {
            if (it.moveToFirst()) it.getString(0) else null
        }
    } catch (e: Exception) {
        Log.e(TAG, "Failed to get fingerprint for $host", e)
        null
    }
}
```

### WR-02: Unvalidated User Input in AssetManager Admin Key

**File:** `android/app/src/main/java/io/raventag/app/wallet/AssetManager.kt:190-192`
**Issue:** Admin key is retrieved from encrypted storage without validation that it's non-empty and properly formatted. A null or empty key would cause all subsequent API calls to fail with unclear error messages.

**Fix:**
```kotlin
private val adminKey: String
    get() = adminKeyStorage.getAdminKey()
        ?.takeIf { it.isNotEmpty() && it.length >= 32 }
        ?: throw IllegalStateException(
            "Admin key not configured or invalid. Configure a valid key in Settings."
        )
```

### WR-03: Missing Error Handling in WalletPollingWorker

**File:** `android/app/src/main/java/io/raventag/app/worker/WalletPollingWorker.kt:108-114`
**Issue:** The auto-sweep operation silently catches all exceptions without logging the specific error. This makes debugging difficult and could hide serious issues like insufficient funds, network failures, or transaction broadcast errors.

**Fix:**
```kotlin
if (incomingDetected) {
    try {
        walletManager.sweepOldAddresses()
    } catch (e: Exception) {
        // Log specific error for debugging
        Log.e(TAG, "Auto-sweep failed", e)
        // Sweep failure is non-fatal: funds stay on the old address until
        // the next polling cycle or the user opens the app.
    }
}
```

### WR-04: Potential Memory Leak in RavencoinPublicNode

**File:** `android/app/src/main/java/io/raventag/app/wallet/RavencoinPublicNode.kt:194`
**Issue:** The `certCache` is a static `ConcurrentHashMap` that grows unbounded. Over time, if the app connects to many different ElectrumX servers, this could consume significant memory.

**Fix:**
```kotlin
private val certCache = ConcurrentHashMap<String, String>()
private val MAX_CACHE_SIZE = 50

// In TofuTrustManager.checkServerTrusted(), after storing:
if (certCache.size > MAX_CACHE_SIZE) {
    // Remove oldest entries (simplified LRU)
    certCache.keys.take(certCache.size - MAX_CACHE_SIZE).forEach { certCache.remove(it) }
}
```

### WR-05: Missing Validation in Backend Revocation Check

**File:** `backend/src/routes/admin.ts:38-43`
**Issue:** The `adminRegisterTagSchema` validation is not shown in this file, but assuming it uses zod, there's no validation that `nfc_pub_id` is a valid SHA-256 hex string (64 hex characters).

**Fix:**
```typescript
// In backend/src/utils/validation.ts or similar:
const nfcPubIdSchema = z.string().length(64).regex(/^[0-9a-fA-F]+$/);

const adminRegisterTagSchema = z.object({
  asset_name: z.string().min(1).max(30),
  nfc_pub_id: nfcPubIdSchema,
  brand_info: brandInfoSchema.optional(),
  metadata_ipfs: z.string().optional()
});
```

### WR-06: Unbounded String Concatenation in WalletManager

**File:** `android/app/src/main/java/io/raventag/app/wallet/WalletManager.kt:1505-1517`
**Issue:** The `base58Encode()` function uses string concatenation in a loop (`sb.append()`), which is generally efficient in Kotlin but could be improved with a more direct approach for cryptographic operations.

**Fix:**
```kotlin
private fun base58Encode(data: ByteArray): String {
    var num = BigInteger(1, data)
    val sb = StringBuilder(data.size * 2) // Pre-allocate sufficient capacity
    val base = BigInteger.valueOf(58)
    while (num > BigInteger.ZERO) {
        val (q, r) = num.divideAndRemainder(base)
        sb.append(B58_ALPHABET[r.toInt()])
        num = q
    }
    for (b in data) {
        if (b == 0.toByte()) sb.append(B58_ALPHABET[0]) else break
    }
    return sb.reverse().toString()
}
```

### WR-07: Missing Input Sanitization in Backend Logger

**File:** `backend/src/middleware/logger.ts:45-60`
**Issue:** While the logger explicitly states it doesn't log request bodies, the IP address from `X-Forwarded-For` header is logged without validation. Malformed headers could cause log injection attacks or consume excessive log space.

**Fix:**
```typescript
const ip = (req.headers['x-forwarded-for'] as string)?.split(',')[0].trim()
  // Validate IP address format (basic IPv4/IPv6 validation)
  ?.match(/^[\d\.:a-fA-F]+$/) ?.[0]
  ?? req.socket.remoteAddress
  // Ensure it's a valid IP address
  ?.match(/^[\d\.:a-fA-F]+$/) ?.[0]
  ?? 'unknown'
```

### WR-08: Race Condition in WalletManager Index Management

**File:** `android/app/src/main/java/io/raventag/app/wallet/WalletManager.kt:352-357`
**Issue:** `getCurrentAddressIndex()` and `setCurrentAddressIndex()` are not atomic. If multiple coroutines call these concurrently, the index could become inconsistent.

**Fix:**
```kotlin
private val indexLock = Any()

fun getCurrentAddressIndex(): Int = synchronized(indexLock) {
    prefs().getInt(KEY_ADDRESS_INDEX, 0)
}

private fun setCurrentAddressIndex(index: Int) = synchronized(indexLock) {
    prefs().edit().putInt(KEY_ADDRESS_INDEX, index).apply()
    cachedAddress = null
}
```

## Info

### IN-01: Inconsistent Error Handling in RpcClient

**File:** `android/app/src/main/java/io/raventag/app/ravencoin/RpcClient.kt:135-166`
**Issue:** `getAssetData()` has inconsistent error handling - it returns null on ElectrumX failure but throws on backend proxy failure. Consider standardizing the behavior.

**Fix:**
```kotlin
fun getAssetData(assetName: String): AssetData? {
    // Try ElectrumX first
    val meta = try {
        context?.let { io.raventag.app.wallet.RavencoinPublicNode(it).getAssetMeta(assetName.uppercase()) }
    } catch (e: Exception) {
        Log.w(TAG, "ElectrumX lookup failed for $assetName", e)
        null
    }

    if (meta != null) {
        return AssetData(
            name = meta.name,
            amount = meta.totalSupply,
            units = meta.divisions,
            reissuable = meta.reissuable,
            hasIpfs = meta.hasIpfs,
            ipfsHash = meta.ipfsHash
        )
    }

    // Fallback to backend proxy (also return null on failure)
    return try {
        val request = Request.Builder()
            .url("$rpcUrl/api/assets/${assetName.uppercase()}")
            .get().build()
        val response = http.newCall(request).execute()
        if (!response.isSuccessful) return null
        val obj = gson.fromJson(response.body?.string(), JsonObject::class.java)
        AssetData(
            name = obj["name"]?.asString ?: assetName,
            amount = obj["amount"]?.asLong ?: 0L,
            units = obj["units"]?.asInt ?: 0,
            reissuable = obj["reissuable"]?.asBoolean ?: false,
            hasIpfs = obj["has_ipfs"]?.asBoolean ?: false,
            ipfsHash = obj["ipfs_hash"]?.asString
        )
    } catch (e: Exception) {
        Log.w(TAG, "Backend proxy lookup failed for $assetName", e)
        null
    }
}
```

### IN-02: Magic Numbers in WalletManager

**File:** `android/app/src/main/java/io/raventag/app/wallet/WalletManager.kt:1148-1154`
**Issue:** Hardcoded values like `1148L`, `70L`, `34L` in fee calculation should be named constants for clarity and maintainability.

**Fix:**
```kotlin
companion object {
    // ... existing constants ...

    // Transaction size constants (bytes)
    private const val TX_OVERHEAD = 10L
    private const val INPUT_SIZE = 148L
    private const val OUTPUT_SIZE = 34L
    private const val ASSET_OUTPUT_SIZE = 70L
    private const val DUST_LIMIT = 546L
}

// Then in fee calculation:
val estimatedBytes = TX_OVERHEAD + INPUT_SIZE * totalInputs + OUTPUT_SIZE * 2 + ASSET_OUTPUT_SIZE * totalAssetOutputs
```

### IN-03: Duplicate Code in RavencoinPublicNode

**File:** `android/app/src/main/java/io/raventag/app/wallet/RavencoinPublicNode.kt:1408-1421`
**Issue:** Base58 decoding logic is duplicated in `addressToScripthash()` and `base58Decode()`. Extract to a shared utility function.

**Fix:**
```kotlin
// Move base58Decode() to a top-level function or utility object
object Base58 {
    private const val ALPHABET = "123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz"

    fun decode(input: String): ByteArray {
        var num = BigInteger.ZERO
        val base = BigInteger.valueOf(58)
        for (c in input) {
            val idx = ALPHABET.indexOf(c)
            require(idx >= 0) { "Invalid Base58 character: $c" }
            num = num.multiply(base).add(BigInteger.valueOf(idx.toLong()))
        }
        val bytes = num.toByteArray()
        val trimmed = if (bytes.isNotEmpty() && bytes[0] == 0.toByte()) bytes.drop(1).toByteArray() else bytes
        val leadingZeros = input.takeWhile { it == ALPHABET[0] }.length
        return ByteArray(leadingZeros) + trimmed
    }
}

// Then in addressToScripthash():
private fun addressToScripthash(address: String): String {
    val decoded = Base58.decode(address)
    // ... rest of the function
}
```

### IN-04: Incomplete Type Checking in Backend Cache

**File:** `backend/src/middleware/cache.ts:163-173`
**Issue:** `cacheGet()` catches all exceptions when parsing JSON, including JSON parse errors and type mismatches. It would be better to log specific errors for debugging.

**Fix:**
```typescript
export function cacheGet(key: string): unknown | null {
  const database = getDb()
  const row = database
    .prepare('SELECT value, expires FROM cache WHERE key = ?')
    .get(key) as { value: string; expires: number } | undefined

  if (!row) return null
  if (Date.now() > row.expires) {
    database.prepare('DELETE FROM cache WHERE key = ?').run(key)
    return null
  }
  try {
    return JSON.parse(row.value)
  } catch (err) {
    // Log parse errors for debugging
    console.error(`Failed to parse cached value for key ${key}:`, err)
    database.prepare('DELETE FROM cache WHERE key = ?').run(key)
    return null
  }
}
```

### IN-05: Redundant Null Checks in Backend Admin Routes

**File:** `backend/src/routes/admin.ts:97-109`
**Issue:** The DELETE endpoint checks `result.changes === 0` to return 404, but SQLite's `DELETE` with a WHERE clause always returns `changes` (never null). The null check is redundant.

**Fix:**
```typescript
router.delete('/tags/:nfcPubId', (req: Request, res: Response) => {
  const { nfcPubId } = req.params
  const db = getDb()
  const result = db
    .prepare('DELETE FROM registered_tags WHERE nfc_pub_id = ?')
    .run(nfcPubId.toLowerCase())

  if (result.changes === 0) {
    res.status(404).json({ error: 'Tag not found', code: 'NOT_FOUND' })
    return
  }
  res.json({ success: true })
})
```

---

_Reviewed: 2026-04-13T00:00:00Z_
_Reviewer: Claude (gsd-code-reviewer)_
_Depth: standard_
