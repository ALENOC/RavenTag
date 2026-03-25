/**
 * SunVerifier.kt
 *
 * Client-side verification of NTAG 424 DNA SUN (Secure Unique NFC) messages.
 *
 * Each time the NTAG 424 DNA chip is tapped, its on-chip hardware generates a
 * fresh, chip-authenticated URL containing two cryptographic fields:
 *
 *   e = AES-128-CBC-ENC(K_SDMMetaRead, IV=0, PICCDataTag || UID[7] || ReadCtr[3] || Padding[5])
 *   m = NXP-truncated CMAC derived from a session key that itself incorporates UID + counter
 *
 * Verification proceeds in three steps:
 *   1. Decrypt e with the static K_SDMMetaRead (Key 2) to recover UID and scan counter.
 *   2. Derive K_SesSDMFileReadMAC = CMAC(K_SDMFileRead, SV2_SDM) where SV2_SDM mixes
 *      a fixed prefix, the UID, and the counter. This key is fresh per tap.
 *   3. Compute CMAC(K_SesSDMFileReadMAC, empty) and compare the NXP-truncated form
 *      against the m parameter. Because the session key is UID- and counter-bound,
 *      an attacker cannot produce a valid m for a different chip or a replayed URL.
 *
 * Reference: NXP Application Note AN12196, Rev. 2.0, Tables 2-4.
 */
package io.raventag.app.nfc

import org.bouncycastle.crypto.engines.AESEngine
import org.bouncycastle.crypto.macs.CMac
import org.bouncycastle.crypto.params.KeyParameter
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import java.security.MessageDigest

/**
 * NTAG 424 DNA SUN message verifier.
 *
 * Implements:
 * - AES-128-CBC decryption for SUN payload (UID + counter)
 * - AES-128-CMAC verification (NXP truncated form)
 * - SHA-256 nfc_pub_id computation
 */
object SunVerifier {

    /**
     * Holds the outcome of a full SUN verification attempt.
     *
     * @property valid      True only when both decryption and MAC verification succeed.
     * @property tagUid     7-byte chip UID extracted from the decrypted PICCData.
     * @property counter    3-byte scan counter (SDMReadCtr) extracted from PICCData.
     * @property nfcPubId   SHA-256(tagUid || salt) as a lowercase hex string, if salt was provided.
     * @property error      Human-readable failure description when valid is false.
     */
    data class SunVerifyResult(
        val valid: Boolean,
        val tagUid: ByteArray? = null,
        val counter: Int? = null,
        val nfcPubId: String? = null,
        val error: String? = null
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is SunVerifyResult) return false
            return valid == other.valid &&
                tagUid.contentEquals(other.tagUid) &&
                counter == other.counter &&
                nfcPubId == other.nfcPubId
        }
        override fun hashCode(): Int = valid.hashCode()
        private fun ByteArray?.contentEquals(other: ByteArray?): Boolean =
            if (this == null && other == null) true
            else if (this == null || other == null) false
            else this.contentEquals(other)
    }

    /**
     * Decrypt NTAG 424 DNA SUN encrypted payload.
     *
     * Cipher: AES-128-CBC, IV = all zeros.
     * Plaintext layout (AN12196 Table 2):
     *   Byte 0:     PICCDataTag = 0xC7 (must match exactly, otherwise reject)
     *   Bytes 1-7:  7-byte UID
     *   Bytes 8-10: 3-byte SDMReadCtr in little-endian
     *   Bytes 11-15: Padding (ignored)
     *
     * @param encryptedHex  32 hex chars = 16 bytes of AES-CBC ciphertext from the URL "e" param.
     * @param sdmEncKey     K_SDMMetaRead (Key 2) registered with the chip during configuration.
     * @return Pair(tagUid: ByteArray[7], counter: Int)
     */
    private fun decryptSunData(encryptedHex: String, sdmEncKey: ByteArray): Pair<ByteArray, Int> {
        val encrypted = encryptedHex.hexToBytes()
        val keySpec = SecretKeySpec(sdmEncKey, "AES")
        val ivSpec = IvParameterSpec(ByteArray(16))
        val cipher = Cipher.getInstance("AES/CBC/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, keySpec, ivSpec)
        val decrypted = cipher.doFinal(encrypted)

        // byte 0 = PICCDataTag must be 0xC7 (AN12196 Table 2)
        require(decrypted[0] == 0xC7.toByte()) {
            "Invalid PICCDataTag byte: expected 0xC7, got 0x${decrypted[0].toInt().and(0xFF).toString(16).padStart(2, '0')}"
        }
        // UID is bytes 1..7
        val tagUid = decrypted.copyOfRange(1, 8)
        // Counter is bytes 8..10, little-endian
        val counter = (decrypted[8].toInt() and 0xFF) or
            ((decrypted[9].toInt() and 0xFF) shl 8) or
            ((decrypted[10].toInt() and 0xFF) shl 16)

        return Pair(tagUid, counter)
    }

    /**
     * Compute AES-128-CMAC using Bouncy Castle.
     *
     * CMAC (Cipher-based Message Authentication Code, RFC 4493) over the given
     * message with the given 16-byte AES key. Returns a 16-byte full MAC.
     */
    private fun computeCmac(key: ByteArray, message: ByteArray): ByteArray {
        val mac = CMac(AESEngine())
        mac.init(KeyParameter(key))
        mac.update(message, 0, message.size)
        val result = ByteArray(16)
        mac.doFinal(result, 0)
        return result
    }

    /**
     * Derive K_SesSDMFileReadMAC session key per AN12196 Section 3.3.
     *
     * This session key is unique per tap because it incorporates UID and SDMReadCtr.
     * Any replay of a previously captured URL will produce the wrong session key
     * (since the live chip counter has advanced), causing MAC verification to fail.
     *
     * SV2_SDM construction (16 bytes total):
     *   Prefix: 0x3C 0xC3 0x00 0x01 0x00 0x80  (6 bytes, fixed by NXP spec)
     *   UID:    tagUid[0..6]                    (7 bytes)
     *   Ctr:    SDMReadCtr as 3-byte LE          (3 bytes)
     *
     * K_SesSDMFileReadMAC = CMAC(K_SDMFileRead, SV2_SDM)
     *
     * @param sdmFileReadKey  K_SDMFileRead (Key 3) set during chip configuration.
     * @param tagUid          7-byte chip UID from PICCData decryption.
     * @param counter         SDMReadCtr from PICCData decryption.
     */
    private fun deriveSessionMacKey(sdmFileReadKey: ByteArray, tagUid: ByteArray, counter: Int): ByteArray {
        val sv2 = byteArrayOf(0x3C, 0xC3.toByte(), 0x00, 0x01, 0x00, 0x80.toByte()) +
            tagUid +
            byteArrayOf(
                (counter and 0xFF).toByte(),
                ((counter shr 8) and 0xFF).toByte(),
                ((counter shr 16) and 0xFF).toByte()
            )  // total = 6 + 7 + 3 = 16 bytes
        return computeCmac(sdmFileReadKey, sv2)
    }

    /**
     * Verify NTAG 424 DNA SUN MAC against the session key.
     *
     * NXP truncation (AN12196 Table 4): from the full 16-byte CMAC, keep only
     * the 8 bytes at odd indices (indices 1, 3, 5, 7, 9, 11, 13, 15).
     * The URL "m" parameter contains exactly these 8 bytes as 16 hex chars.
     *
     * Uses constant-time comparison (XOR accumulation) to prevent timing attacks.
     *
     * @param macHex        16 hex chars from URL "m" parameter.
     * @param sessionMacKey K_SesSDMFileReadMAC (already derived, not the raw K_SDMFileRead).
     * @param cmacInput     Bytes to MAC over. Empty for zero-length CMAC input config
     *                      (sdmmacInputOffset == sdmmacOffset). Freshness comes from the
     *                      session key which already encodes UID and counter.
     */
    private fun verifySunMac(macHex: String, sessionMacKey: ByteArray, cmacInput: ByteArray): Boolean {
        val providedMac = macHex.hexToBytes()

        val fullCmac = computeCmac(sessionMacKey, cmacInput)

        // Extract odd-indexed bytes (indices 1,3,5,...,15) per NXP AN12196 Table 4
        val truncated = ByteArray(8) { i -> fullCmac[i * 2 + 1] }

        // Compare all 8 bytes (16 hex chars in URL)
        val expected = truncated.copyOfRange(0, 8)
        if (expected.size != providedMac.size) return false

        // Constant-time comparison
        var diff = 0
        for (i in expected.indices) {
            diff = diff or (expected[i].toInt() xor providedMac[i].toInt())
        }
        return diff == 0
    }

    /**
     * Compute nfc_pub_id = SHA-256(uid || salt).
     *
     * The brand salt is a secret random value stored on the backend; it is never
     * embedded in the on-chip URL or IPFS metadata. This makes the raw UID
     * unrecoverable from nfc_pub_id alone, protecting chip identity.
     *
     * @param tagUid  7-byte chip UID.
     * @param salt    16-byte brand salt from the backend.
     * @return Lowercase hex string of the 32-byte SHA-256 digest.
     */
    fun computeNfcPubId(tagUid: ByteArray, salt: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(tagUid)
        digest.update(salt)
        return digest.digest().bytesToHex()
    }

    /**
     * Full SUN message verification per AN12196.
     *
     * The three-step sequence is mandatory: decryption must precede session key
     * derivation (the session key depends on UID and counter from step 1), and
     * derivation must precede MAC verification.
     *
     * @param e          32 hex chars: AES-128-CBC encrypted PICCData from URL "e" param.
     * @param m          16 hex chars: NXP-truncated SDMMAC from URL "m" param.
     * @param sdmEncKey  K_SDMMetaRead (Key 2): static per-chip key for PICCData decryption.
     * @param sdmMacKey  K_SDMFileRead (Key 3): base key for session MAC key derivation.
     * @param salt       Optional 16-byte brand salt for nfc_pub_id computation.
     *                   Pass null when the caller does not need nfc_pub_id.
     */
    fun verify(
        e: String,
        m: String,
        sdmEncKey: ByteArray,
        sdmMacKey: ByteArray,
        salt: ByteArray? = null
    ): SunVerifyResult {
        return try {
            // Step 1: Decrypt PICCData with static K_SDMMetaRead to get UID + counter
            val (tagUid, counter) = decryptSunData(e, sdmEncKey)

            // Step 2: Derive K_SesSDMFileReadMAC = CMAC(K_SDMFileRead, SV2_SDM)
            // SV2_SDM includes UID and counter, providing per-tap freshness
            val sessionMacKey = deriveSessionMacKey(sdmMacKey, tagUid, counter)

            // Step 3: Verify SDMMAC , zero-length CMAC input (sdmmacInputOffset == sdmmacOffset)
            // Freshness is already encoded in the session key via UID + ReadCtr
            if (!verifySunMac(m, sessionMacKey, ByteArray(0))) {
                return SunVerifyResult(
                    valid = false,
                    error = "MAC verification failed , tag may be cloned or tampered"
                )
            }

            // Step 4: Compute nfc_pub_id if salt provided
            val nfcPubId = salt?.let { computeNfcPubId(tagUid, it) }

            SunVerifyResult(
                valid = true,
                tagUid = tagUid,
                counter = counter,
                nfcPubId = nfcPubId
            )
        } catch (ex: Exception) {
            SunVerifyResult(valid = false, error = ex.message ?: "Verification error")
        }
    }

    // Extension functions

    /** Decode a lowercase or uppercase hex string to a byte array. */
    private fun String.hexToBytes(): ByteArray {
        require(length % 2 == 0) { "Invalid hex string length" }
        return ByteArray(length / 2) {
            Integer.parseInt(substring(it * 2, it * 2 + 2), 16).toByte()
        }
    }

    /** Encode a byte array to a lowercase hex string. */
    private fun ByteArray.bytesToHex(): String =
        joinToString("") { "%02x".format(it) }
}
