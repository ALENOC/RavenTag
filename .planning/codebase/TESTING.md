# Testing Patterns

**Analysis Date:** 2026-04-13

## Test Framework

**Runner (Android JVM unit tests):**
- JUnit 4 (`testImplementation(libs.junit)`)
- Run on JVM without an Android device (standard `./gradlew test`)
- Config: `android/app/build.gradle.kts` — `testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"`

**Assertion Library:**
- `org.junit.Assert.*` (JUnit 4 static assertions): `assertEquals`, `assertTrue`, `assertFalse`, `assertNotNull`, `assertNull`

**Instrumented / E2E (Android):**
- `androidTestImplementation(libs.androidx.test.ext.junit)` and `androidTestImplementation(libs.androidx.test.runner)` are declared but no instrumented test files are present

**Backend / Frontend:**
- No test framework configured; no test files exist in `backend/` or `frontend/`

**Run Commands (Android):**
```bash
cd android
./gradlew test                          # Run all JVM unit tests
./gradlew testConsumerDebugUnitTest     # Run unit tests for consumer flavor
./gradlew testBrandDebugUnitTest        # Run unit tests for brand flavor
./gradlew connectedAndroidTest          # Run instrumented tests (device required)
```

## Test File Organization

**Location:** Co-located under `android/app/src/test/` mirroring the `src/main/` package hierarchy

**Structure:**
```
android/app/src/
├── main/java/io/raventag/app/
│   ├── nfc/SunVerifier.kt
│   └── wallet/RavencoinTxBuilder.kt
└── test/java/io/raventag/app/
    ├── nfc/SunVerifierTest.kt
    └── wallet/RavencoinTxBuilderTest.kt
```

**Naming:** `{ClassName}Test.kt` matching the production class name exactly

## Test Structure

**Suite Organization:**
```kotlin
class SunVerifierTest {
    // Private reference implementations (independent from the class under test)
    private fun aesCbcEncrypt(key: ByteArray, plaintext: ByteArray): ByteArray { ... }
    private fun computeCmac(key: ByteArray, message: ByteArray): ByteArray { ... }

    // Private test vector builders
    private fun buildSunVector(...): Pair<String, String> { ... }

    // Tests grouped by topic with backtick method names
    @Test
    fun `verify with valid SUN vector returns true with correct uid and counter`() { ... }

    @Test(expected = IllegalArgumentException::class)
    fun `buildAndSign with corrupted recipient address checksum throws`() { ... }
}
```

**Key patterns:**
- Test method names use backtick syntax for readable English descriptions
- Tests are grouped by behavior topic with inline section comments (`// ── base58Decode checksum fix tests`)
- No `@Before`/`@After` setup; each test is self-contained
- Lazy properties (`by lazy`) used for expensive shared test data that depends on other lazy values

## Mocking

**Framework:** None (no Mockito or MockK dependency detected)

**Patterns:**
- Tests use independently implemented reference functions (not the class under test) to generate expected values and valid test vectors
- Test vectors are computed from first principles (standard Java crypto APIs + BouncyCastle) so the test verifies correctness against an independent implementation, not against itself

```kotlin
// Pattern: independent reference implementation to build test vectors
private fun buildSunVector(
    sdmEncKey: ByteArray,
    sdmMacKey: ByteArray,
    uid: ByteArray,
    counter: Int
): Pair<String, String> {
    // Uses Cipher.getInstance("AES/CBC/NoPadding") directly, not SunVerifier
    val eHex = aesCbcEncrypt(sdmEncKey, plaintext).joinToString("") { "%02x".format(it) }
    ...
    return eHex to mHex
}
```

**What to Mock:**
- Not applicable; tests use real crypto implementations to verify cryptographic correctness

**What NOT to Mock:**
- Crypto primitives: always use real AES/CMAC for test vector generation; mocking would defeat the purpose

## Fixtures and Factories

**Test Data:**
```kotlin
// Fixed scalar-1 private key (always valid on secp256k1)
private val testPrivKey = ByteArray(31) { 0 } + byteArrayOf(1)
private val testPubKey by lazy { pubKeyFromPrivKey(testPrivKey) }
private val senderAddress by lazy { testAddress(testPrivKey) }
private val senderScript by lazy { p2pkhScriptHex(hash160(testPubKey)) }

// Key material: sequential byte patterns for easy identification
val sdmEncKey = ByteArray(16) { it.toByte() }       // 0x00..0x0F
val sdmMacKey = ByteArray(16) { (it + 16).toByte() } // 0x10..0x1F
val uid = byteArrayOf(0x04, 0xE2.toByte(), 0x4F, 0x7A, 0x12, 0xAB.toByte(), 0xC1.toByte())
```

**Location:**
- Fixture data and helper functions are private members of the test class; no shared fixture files

## Coverage

**Requirements:** None enforced; no JaCoCo or coverage threshold configured

**View Coverage:**
```bash
cd android
./gradlew test jacocoTestReport   # Only if JaCoCo plugin is added
```

## Test Types

**Unit Tests:**
- Scope: individual `object` singletons (`SunVerifier`, `RavencoinTxBuilder`) in isolation
- Approach: provide controlled inputs, assert on return values and thrown exceptions
- Location: `android/app/src/test/`

**Integration Tests:**
- Not present

**E2E / Instrumented Tests:**
- Dependencies declared but no test files written; Android device required
- Location would be: `android/app/src/androidTest/`

**Backend Tests:**
- Not present; no Jest/Vitest/Mocha configuration found in `backend/`

**Frontend Tests:**
- Not present; no test configuration found in `frontend/`

## Common Patterns

**Testing a cryptographic happy path:**
```kotlin
@Test
fun `verify with valid SUN vector returns true with correct uid and counter`() {
    val (eHex, mHex) = buildSunVector(sdmEncKey, sdmMacKey, uid, counter)
    val result = SunVerifier.verify(eHex, mHex, sdmEncKey, sdmMacKey)

    assertTrue("SUN MAC verification must succeed for valid vector", result.valid)
    assertNotNull(result.tagUid)
    assertTrue("UID must match", uid.contentEquals(result.tagUid!!))
    assertEquals("Counter must match", counter, result.counter)
    assertNull("No error on success", result.error)
}
```

**Testing expected exceptions:**
```kotlin
@Test(expected = IllegalArgumentException::class)
fun `buildAndSign with corrupted recipient address checksum throws`() {
    // Corrupt one character of the Base58Check address
    val badAddress = validAddress.dropLast(1) + corruptChar
    RavencoinTxBuilder.buildAndSign(utxos, toAddress = badAddress, ...)
}
```

**Testing failure / invalid input:**
```kotlin
@Test
fun `verify with corrupted MAC returns invalid`() {
    val (eHex, mHex) = buildSunVector(...)
    val badMHex = mHex.dropLast(1) + if (mHex.last() == 'f') '0' else 'f'
    val result = SunVerifier.verify(eHex, badMHex, sdmEncKey, sdmMacKey)

    assertFalse("Corrupted MAC must fail verification", result.valid)
    assertNotNull("Error message must be set", result.error)
}
```

**Testing raw transaction bytes (structural tests):**
```kotlin
@Test
fun `buildAndSign transaction has correct version bytes`() {
    val result = RavencoinTxBuilder.buildAndSign(...)
    assertTrue("tx must start with version 2 (02000000)", result.hex.startsWith("02000000"))
}

@Test
fun `txid is double-sha256 of raw tx reversed`() {
    val rawBytes = result.hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    val expectedTxid = doubleSha256(rawBytes).reversedArray()
        .joinToString("") { "%02x".format(it) }
    assertEquals("txid must be reversed double-SHA256 of raw tx", expectedTxid, result.txid)
}
```

## Coverage Gaps

**Untested areas:**
- All backend TypeScript (`backend/src/`): no tests for `crypto.ts`, `ntag424.ts`, `ravencoin.ts`, `cache.ts`, any Express routes
- All frontend TypeScript/TSX (`frontend/src/`): no tests for any component or page
- Android instrumented tests: declared but not written (UI flows, NFC interactions, Keystore operations)
- Android `WalletManager.kt`: BIP44 HD derivation, mnemonic generation/restore, AES-GCM Keystore encrypt/decrypt
- Android `RavencoinPublicNode.kt`: RPC client calls

**Priority:**
- `backend/src/utils/crypto.ts`: High. Core crypto primitives with no test coverage; bugs here break the entire verification chain
- `backend/src/services/ntag424.ts`: High. SUN verification pipeline; backend equivalent of the tested `SunVerifier.kt`
- `backend/src/middleware/cache.ts`: Medium. Replay detection and revocation logic are security-critical
- `android/wallet/WalletManager.kt`: Medium. BIP39/BIP44 derivation and Keystore encryption are hard to debug without tests

---

*Testing analysis: 2026-04-13*
