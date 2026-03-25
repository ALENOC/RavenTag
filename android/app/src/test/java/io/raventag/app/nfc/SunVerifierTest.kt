package io.raventag.app.nfc

import org.bouncycastle.crypto.engines.AESEngine
import org.bouncycastle.crypto.macs.CMac
import org.bouncycastle.crypto.params.KeyParameter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * JVM unit tests for SunVerifier.
 *
 * Test vectors are generated independently using standard Java crypto primitives
 * and BouncyCastle, then verified against SunVerifier's output. This tests
 * correctness against an independently computed reference rather than the
 * implementation verifying itself.
 *
 * These tests run on the JVM without an Android device.
 */
class SunVerifierTest {

    // ── Reference implementations (independent of SunVerifier) ────────────────

    private fun aesCbcEncrypt(key: ByteArray, plaintext: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/CBC/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(ByteArray(16)))
        return cipher.doFinal(plaintext)
    }

    private fun computeCmac(key: ByteArray, message: ByteArray): ByteArray {
        val mac = CMac(AESEngine())
        mac.init(KeyParameter(key))
        mac.update(message, 0, message.size)
        return ByteArray(16).also { mac.doFinal(it, 0) }
    }

    /**
     * Build a valid SUN test vector (e, m) for the given keys, UID, and counter.
     * Follows AN12196 exactly: encrypt PICCData, derive session key, compute truncated CMAC.
     */
    private fun buildSunVector(
        sdmEncKey: ByteArray,
        sdmMacKey: ByteArray,
        uid: ByteArray,
        counter: Int
    ): Pair<String, String> {
        // Plaintext: PICCDataTag(0xC7) + UID(7) + counter_LE(3) + padding(5 zero bytes)
        val plaintext = ByteArray(16)
        plaintext[0] = 0xC7.toByte()
        uid.copyInto(plaintext, 1)
        plaintext[8] = (counter and 0xFF).toByte()
        plaintext[9] = ((counter shr 8) and 0xFF).toByte()
        plaintext[10] = ((counter shr 16) and 0xFF).toByte()

        val eHex = aesCbcEncrypt(sdmEncKey, plaintext).joinToString("") { "%02x".format(it) }

        // SV2 = 3CC3 0001 0080 + UID(7) + counter_LE(3) = 16 bytes
        val sv2 = byteArrayOf(0x3C, 0xC3.toByte(), 0x00, 0x01, 0x00, 0x80.toByte()) +
                uid +
                byteArrayOf(
                    (counter and 0xFF).toByte(),
                    ((counter shr 8) and 0xFF).toByte(),
                    ((counter shr 16) and 0xFF).toByte()
                )
        val sessionKey = computeCmac(sdmMacKey, sv2)

        // CMAC over empty input, then take odd-indexed bytes [1,3,5,...,15]
        val fullCmac = computeCmac(sessionKey, ByteArray(0))
        val truncated = ByteArray(8) { i -> fullCmac[i * 2 + 1] }
        val mHex = truncated.copyOf(4).joinToString("") { "%02x".format(it) }

        return eHex to mHex
    }

    // ── Tests ─────────────────────────────────────────────────────────────────

    @Test
    fun `verify with valid SUN vector returns true with correct uid and counter`() {
        val sdmEncKey = ByteArray(16) { it.toByte() }
        val sdmMacKey = ByteArray(16) { (it + 16).toByte() }
        val uid = byteArrayOf(0x04, 0xE2.toByte(), 0x4F, 0x7A, 0x12, 0xAB.toByte(), 0xC1.toByte())
        val counter = 42

        val (eHex, mHex) = buildSunVector(sdmEncKey, sdmMacKey, uid, counter)

        val result = SunVerifier.verify(eHex, mHex, sdmEncKey, sdmMacKey)

        assertTrue("SUN MAC verification must succeed for valid vector", result.valid)
        assertNotNull(result.tagUid)
        assertTrue("UID must match", uid.contentEquals(result.tagUid!!))
        assertEquals("Counter must match", counter, result.counter)
        assertNull("No salt provided: nfcPubId must be null", result.nfcPubId)
        assertNull("No error on success", result.error)
    }

    @Test
    fun `verify with counter zero succeeds`() {
        val sdmEncKey = ByteArray(16) { 0xAA.toByte() }
        val sdmMacKey = ByteArray(16) { 0xBB.toByte() }
        val uid = byteArrayOf(0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07)
        val counter = 0

        val (eHex, mHex) = buildSunVector(sdmEncKey, sdmMacKey, uid, counter)
        val result = SunVerifier.verify(eHex, mHex, sdmEncKey, sdmMacKey)

        assertTrue(result.valid)
        assertEquals(0, result.counter)
    }

    @Test
    fun `verify with corrupted MAC returns invalid`() {
        val sdmEncKey = ByteArray(16) { it.toByte() }
        val sdmMacKey = ByteArray(16) { (it + 16).toByte() }
        val uid = byteArrayOf(0x04, 0xE2.toByte(), 0x4F, 0x7A, 0x12, 0xAB.toByte(), 0xC1.toByte())

        val (eHex, mHex) = buildSunVector(sdmEncKey, sdmMacKey, uid, 1)
        // Flip one nibble in the MAC
        val badMHex = mHex.dropLast(1) + if (mHex.last() == 'f') '0' else 'f'

        val result = SunVerifier.verify(eHex, badMHex, sdmEncKey, sdmMacKey)

        assertFalse("Corrupted MAC must fail verification", result.valid)
        assertNotNull("Error message must be set", result.error)
    }

    @Test
    fun `verify with wrong sdmMacKey returns invalid`() {
        val sdmEncKey = ByteArray(16) { it.toByte() }
        val sdmMacKey = ByteArray(16) { (it + 16).toByte() }
        val wrongMacKey = ByteArray(16) { (it + 32).toByte() }
        val uid = byteArrayOf(0x04, 0xE2.toByte(), 0x4F, 0x7A, 0x12, 0xAB.toByte(), 0xC1.toByte())

        val (eHex, mHex) = buildSunVector(sdmEncKey, sdmMacKey, uid, 5)
        val result = SunVerifier.verify(eHex, mHex, sdmEncKey, wrongMacKey)

        assertFalse("Wrong MAC key must fail verification", result.valid)
    }

    @Test
    fun `verify with wrong sdmEncKey returns invalid`() {
        val sdmEncKey = ByteArray(16) { it.toByte() }
        val sdmMacKey = ByteArray(16) { (it + 16).toByte() }
        val wrongEncKey = ByteArray(16) { (it + 32).toByte() }
        val uid = byteArrayOf(0x04, 0xE2.toByte(), 0x4F, 0x7A, 0x12, 0xAB.toByte(), 0xC1.toByte())

        val (eHex, mHex) = buildSunVector(sdmEncKey, sdmMacKey, uid, 5)
        // Wrong key decrypts to garbage: PICCDataTag byte != 0xC7 -> error
        val result = SunVerifier.verify(eHex, mHex, wrongEncKey, sdmMacKey)

        assertFalse("Wrong enc key must fail verification", result.valid)
    }

    @Test
    fun `verify computes nfcPubId when salt is provided`() {
        val sdmEncKey = ByteArray(16) { it.toByte() }
        val sdmMacKey = ByteArray(16) { (it + 16).toByte() }
        val uid = byteArrayOf(0x04, 0xE2.toByte(), 0x4F, 0x7A, 0x12, 0xAB.toByte(), 0xC1.toByte())
        val salt = ByteArray(16) { (it + 1).toByte() }

        val (eHex, mHex) = buildSunVector(sdmEncKey, sdmMacKey, uid, 7)
        val result = SunVerifier.verify(eHex, mHex, sdmEncKey, sdmMacKey, salt)

        assertTrue(result.valid)
        assertNotNull(result.nfcPubId)

        // Verify nfcPubId = SHA-256(uid || salt)
        val expected = MessageDigest.getInstance("SHA-256")
            .let { it.update(uid); it.update(salt); it.digest() }
            .joinToString("") { "%02x".format(it) }
        assertEquals("nfcPubId must equal SHA-256(uid || salt)", expected, result.nfcPubId)
    }

    @Test
    fun `computeNfcPubId matches standard SHA256 concatenation`() {
        val uid = byteArrayOf(0x04, 0xAB.toByte(), 0xCD.toByte(), 0xEF.toByte(), 0x01, 0x02, 0x03)
        val salt = ByteArray(16) { (0xF0 + it).toByte() }

        val expected = MessageDigest.getInstance("SHA-256")
            .let { it.update(uid); it.update(salt); it.digest() }
            .joinToString("") { "%02x".format(it) }

        assertEquals(expected, SunVerifier.computeNfcPubId(uid, salt))
    }

    @Test
    fun `computeNfcPubId produces 64-character lowercase hex string`() {
        val uid = byteArrayOf(0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07)
        val salt = ByteArray(16) { it.toByte() }
        val result = SunVerifier.computeNfcPubId(uid, salt)
        assertEquals("SHA-256 output must be 64 hex chars", 64, result.length)
        assertTrue("Must be lowercase hex", result.all { it.isDigit() || it in 'a'..'f' })
    }

    @Test
    fun `verify different counters produce different MACs`() {
        val sdmEncKey = ByteArray(16) { it.toByte() }
        val sdmMacKey = ByteArray(16) { (it + 16).toByte() }
        val uid = byteArrayOf(0x04, 0xE2.toByte(), 0x4F, 0x7A, 0x12, 0xAB.toByte(), 0xC1.toByte())

        val (eHex1, mHex1) = buildSunVector(sdmEncKey, sdmMacKey, uid, 10)
        val (eHex2, mHex2) = buildSunVector(sdmEncKey, sdmMacKey, uid, 11)

        // Replay: e from counter 10, m from counter 11 must fail
        val result = SunVerifier.verify(eHex1, mHex2, sdmEncKey, sdmMacKey)
        assertFalse("MAC from different counter must not verify with old encrypted data", result.valid)

        // Correct pairs must succeed
        assertTrue(SunVerifier.verify(eHex1, mHex1, sdmEncKey, sdmMacKey).valid)
        assertTrue(SunVerifier.verify(eHex2, mHex2, sdmEncKey, sdmMacKey).valid)
    }
}
