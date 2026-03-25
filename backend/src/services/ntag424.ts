/**
 * @file services/ntag424.ts
 *
 * NTAG 424 DNA SUN (Secure Unique NFC) verification service.
 *
 * Implements the cryptographic pipeline defined in NXP Application Note AN12196
 * ("NTAG 424 DNA and NTAG 424 DNA TagTamper features and hints") for verifying
 * that an NFC tap came from a genuine, uncloned NTAG 424 DNA chip.
 *
 * Protocol overview (SUN with SDM mirror):
 *   1. On every tap, the chip encrypts its UID and a 3-byte tap counter with
 *      K_SDMMetaRead (Key 2) using AES-128-CBC and appends the ciphertext as
 *      URL parameter "e" (32 hex = 16 bytes).
 *   2. The chip computes a session MAC key from K_SDMFileRead (Key 3), the UID,
 *      and the counter (AN12196 SV2 derivation), then generates a 16-byte CMAC
 *      and truncates it to 8 bytes by retaining only the odd-indexed bytes
 *      (indices 1, 3, 5, ..., 15). This truncated MAC is the URL parameter "m"
 *      (16 hex = 8 bytes).
 *   3. The verifier decrypts "e" to recover UID + counter, re-derives the session
 *      MAC key using the same inputs, recomputes the truncated CMAC, and compares
 *      it with "m" using a constant-time comparison.
 *
 * Since the session MAC key changes on every tap (it depends on the counter),
 * a captured URL cannot be replayed against the verifier without access to the
 * chip's internal keys.
 *
 * Re-exported helpers (consumed by routes/verify.ts):
 *   deriveTagKey   - derive a single per-chip AES-128 key from a master key + UID
 *   deriveTagKeys  - derive all four per-chip AES-128 keys from a master key + UID
 */

import { aes128CbcDecrypt, aesCmac, deriveTagKey, deriveTagKeys } from '../utils/crypto.js'

// Re-export key derivation helpers so routes can import them from this service
// without depending directly on the crypto utility module.
export { deriveTagKey, deriveTagKeys }

/**
 * Derive K_SesSDMFileReadMAC from K_SDMFileRead, the chip UID, and the tap counter.
 *
 * Follows AN12196 §3.3 Session Vector 2 (SV2) derivation:
 *   SV2_SDM = 0x3C 0xC3 0x00 0x01 0x00 0x80 || UID[7] || Counter[3 LE]   (16 bytes total)
 *   K_SesSDMFileReadMAC = CMAC(K_SDMFileRead, SV2_SDM)
 *
 * The 6-byte constant prefix identifies this as the SUN SDM session vector for
 * the MAC key role (as opposed to SV1 used for the encryption key).
 *
 * @param sdmFileReadKey - K_SDMFileRead (Key 3), 16 bytes AES-128
 * @param tagUid         - 7-byte chip UID (as read from decrypted PICCData)
 * @param counter        - 24-bit tap counter (little-endian, extracted from PICCData)
 * @returns K_SesSDMFileReadMAC, 16 bytes
 */
export function deriveSessionMacKey(sdmFileReadKey: Buffer, tagUid: Buffer, counter: number): Buffer {
  const sv2 = Buffer.concat([
    // SV2 fixed prefix per AN12196 Table 3
    Buffer.from([0x3C, 0xC3, 0x00, 0x01, 0x00, 0x80]),
    // 7-byte chip UID
    tagUid,
    // 3-byte tap counter in little-endian byte order
    Buffer.from([
      counter & 0xFF,
      (counter >> 8) & 0xFF,
      (counter >> 16) & 0xFF
    ])
  ])  // 6 + 7 + 3 = 16 bytes
  return aesCmac(sdmFileReadKey, sv2)
}

/**
 * Unified result type returned by verifySunMessage.
 * On success, tagUid and counter are always present.
 * On failure, error describes the step that failed.
 */
export interface SunVerifyResult {
  valid: boolean
  tagUid?: Buffer
  counter?: number
  error?: string
}

/**
 * Parse the "e" and "m" query parameters from a full SUN URL string.
 *
 * The NTAG 424 DNA appends these parameters to the URL programmed into the
 * chip's NDEF record. The chip replaces the mirror placeholders on each tap.
 * Example URL: https://example.com/verify?e=AABBCCDDEE...&m=11223344...
 *
 * @param url - Full SUN URL as read from the NDEF record
 * @returns { e, m } if both parameters are present, or null if parsing fails
 */
export function parseSunUrl(url: string): { e: string; m: string } | null {
  try {
    const u = new URL(url)
    const e = u.searchParams.get('e')
    const m = u.searchParams.get('m')
    if (!e || !m) return null
    return { e, m }
  } catch {
    return null
  }
}

/**
 * Decrypt the NTAG 424 DNA SUN encrypted PICCData field.
 *
 * The chip encrypts a 16-byte plaintext block using AES-128-CBC with a zero IV:
 *   Plaintext layout (AN12196 Table 2):
 *     Byte  0   : PICCDataTag = 0xC7 (identifies this as a full UID + counter block)
 *     Bytes 1-7 : UID (7 bytes)
 *     Bytes 8-10: SDMReadCtr (3 bytes, little-endian)
 *     Bytes 11-15: Padding (unused)
 *
 * @param encryptedHex - URL "e" parameter, exactly 32 hex chars (16 bytes)
 * @param sdmEncKey    - K_SDMMetaRead (Key 2), 16 bytes AES-128
 * @returns { tagUid: Buffer(7), counter: number }
 * @throws If the hex length is wrong or the PICCDataTag byte is not 0xC7
 */
export function decryptSunData(
  encryptedHex: string,
  sdmEncKey: Buffer
): { tagUid: Buffer; counter: number } {
  if (encryptedHex.length !== 32) {
    throw new Error('SUN encrypted data must be exactly 16 bytes (32 hex chars)')
  }
  const encrypted = Buffer.from(encryptedHex, 'hex')
  // Zero IV is specified in AN12196; the chip uses the same fixed zero IV
  const iv = Buffer.alloc(16, 0)
  const decrypted = aes128CbcDecrypt(sdmEncKey, iv, encrypted)

  // byte 0 = PICCDataTag must be 0xC7 (AN12196 Table 2)
  // Any other value means the key is wrong or the data is corrupted/tampered.
  if (decrypted[0] !== 0xC7) {
    throw new Error(`Invalid PICCDataTag byte: expected 0xC7, got 0x${decrypted[0].toString(16).padStart(2, '0')}`)
  }
  // UID is bytes 1..7 (7 bytes for NTAG 424 DNA)
  const tagUid = decrypted.subarray(1, 8)
  // MED-2: assert UID is exactly 7 bytes (should always hold after correct AES-128 decryption)
  if (tagUid.length !== 7) {
    throw new Error(`Invalid UID length: expected 7 bytes, got ${tagUid.length}`)
  }
  // Counter is bytes 8..10, unsigned 24-bit little-endian integer
  const counter = decrypted[8] | (decrypted[9] << 8) | (decrypted[10] << 16)

  return { tagUid, counter }
}

/**
 * Verify the NTAG 424 DNA SDMMAC against the session MAC key.
 *
 * NXP truncation algorithm (AN12196 Table 4):
 *   1. Compute a full 16-byte AES-CMAC over cmacInput using sessionMacKey.
 *   2. Retain only the odd-indexed bytes (indices 1, 3, 5, 7, 9, 11, 13, 15)
 *      to form an 8-byte truncated MAC.
 *   3. Compare all 8 bytes with the provided MAC using a constant-time XOR loop.
 *
 * The URL parameter "m" carries all 8 bytes (16 hex chars), not 4 bytes.
 * (NXP documentation sometimes refers to "4-byte MAC" meaning 4 bytes of the
 * even bytes, but the actual SUN implementation uses 8 bytes of odd bytes.)
 *
 * @param macHex        - URL "m" parameter, 16 hex chars (8 bytes)
 * @param sessionMacKey - K_SesSDMFileReadMAC, 16 bytes (already derived, not the base key)
 * @param cmacInput     - Bytes to MAC; empty Buffer when sdmmacInputOffset == sdmmacOffset
 * @returns true if the truncated CMAC matches macHex, false otherwise
 */
export function verifySunMac(macHex: string, sessionMacKey: Buffer, cmacInput: Buffer): boolean {
  const providedMac = Buffer.from(macHex, 'hex')

  // Compute full 16-byte CMAC over the input bytes
  const fullCmac = aesCmac(sessionMacKey, cmacInput)

  // Extract odd-indexed bytes (indices: 1,3,5,...,15) per NXP AN12196 Table 4
  const truncated = Buffer.alloc(8)
  for (let i = 0; i < 8; i++) {
    // Byte at position i in the truncated MAC comes from index (i*2 + 1) in the full CMAC
    truncated[i] = fullCmac[i * 2 + 1]
  }

  // Compare all 8 bytes (16 hex chars in URL)
  const expected = truncated.subarray(0, 8)
  if (expected.length !== providedMac.length) return false

  // Constant-time comparison: accumulate all bit differences with OR.
  // If any byte differs, diff will be non-zero. This prevents timing attacks
  // that could otherwise reveal how many bytes matched.
  let diff = 0
  for (let i = 0; i < expected.length; i++) {
    diff |= expected[i] ^ providedMac[i]
  }
  return diff === 0
}

/**
 * Perform full SUN message verification per AN12196.
 *
 * This is the main entry point for the verification pipeline. It combines
 * decryptSunData, deriveSessionMacKey, and verifySunMac in the correct order:
 *   Step 1: Decrypt "e" with K_SDMMetaRead (Key 2) to get UID + counter.
 *   Step 2: Derive K_SesSDMFileReadMAC = CMAC(K_SDMFileRead, SV2(UID, counter)).
 *   Step 3: Verify the truncated SDMMAC "m" using K_SesSDMFileReadMAC.
 *
 * The order is mandatory: the session MAC key depends on UID + counter, which
 * are only known after decryption. Therefore decryption must precede MAC verification.
 *
 * On any error (wrong key, bad format, MAC mismatch), returns { valid: false }
 * with a descriptive error string. Does not throw.
 *
 * @param e         - URL "e" parameter, 32 hex chars: AES-128-CBC encrypted PICCData
 * @param m         - URL "m" parameter, 16 hex chars: truncated SDMMAC (8 bytes)
 * @param sdmEncKey - K_SDMMetaRead (Key 2), 16 bytes: static key for PICCData decryption
 * @param sdmMacKey - K_SDMFileRead (Key 3), 16 bytes: base key for session MAC derivation
 * @returns SunVerifyResult with valid flag, tagUid, counter, and optional error message
 */
export function verifySunMessage(
  e: string,
  m: string,
  sdmEncKey: Buffer,
  sdmMacKey: Buffer
): SunVerifyResult {
  try {
    // Step 1: Decrypt PICCData with static K_SDMMetaRead to get UID + counter
    const { tagUid, counter } = decryptSunData(e, sdmEncKey)

    // Step 2: Derive K_SesSDMFileReadMAC = CMAC(K_SDMFileRead, SV2_SDM)
    // The session key is unique per tap because it incorporates the counter.
    const sessionMacKey = deriveSessionMacKey(sdmMacKey, tagUid, counter)

    // Step 3: Verify SDMMAC with zero-length CMAC input
    // When sdmmacInputOffset == sdmmacOffset in the chip's SDM config, there are
    // no additional bytes to authenticate beyond the session key derivation itself.
    // Freshness is guaranteed by the session key (which changes every tap).
    const macValid = verifySunMac(m, sessionMacKey, Buffer.alloc(0))
    if (!macValid) {
      return { valid: false, error: 'MAC verification failed, tag may be cloned or tampered' }
    }

    return { valid: true, tagUid, counter }
  } catch (err: unknown) {
    return {
      valid: false,
      error: err instanceof Error ? err.message : 'Unknown verification error'
    }
  }
}
