/**
 * @file utils/crypto.ts
 *
 * Low-level cryptographic primitives for RavenTag.
 *
 * All functions use Node.js built-in `crypto` module (no external dependencies).
 * This file is the single source of truth for:
 *
 *   - SHA-256 hashing
 *   - nfc_pub_id derivation (SHA-256 of UID || salt)
 *   - AES-128-CBC encrypt / decrypt (used for SUN PICCData and CMAC internals)
 *   - AES-128-ECB encrypt (used for key diversification)
 *   - AES-CMAC per RFC 4493 (used for SUN MAC verification and session key derivation)
 *   - Per-chip AES-128 key derivation (deriveTagKey, deriveTagKeys)
 *   - AES-CMAC verification with constant-time comparison
 *
 * Security notes:
 *   - All byte comparisons that could leak timing information are performed with
 *     a constant-time XOR-accumulate loop (diff |= a[i] ^ b[i]).
 *   - AES-128-CBC is used with auto-padding disabled; callers are responsible for
 *     supplying correctly padded (or single-block) input.
 */

import { createHash, createCipheriv, createDecipheriv } from 'crypto'

/**
 * Compute the SHA-256 digest of a Buffer.
 *
 * @param data - Input bytes
 * @returns 32-byte SHA-256 digest
 */
export function sha256(data: Buffer): Buffer {
  return createHash('sha256').update(data).digest()
}

/**
 * Compute the nfc_pub_id privacy-preserving public identifier for an NFC chip.
 *
 * nfc_pub_id = SHA-256(tag_uid_bytes || salt_bytes)
 *
 * The salt is generated once at chip registration time and stored server-side
 * (never on-chain). This means the raw UID cannot be recovered from the
 * nfc_pub_id even by an observer with access to the blockchain, preserving
 * the privacy of the physical chip's hardware identifier.
 *
 * @param tagUid - 7-byte chip UID as read from the decrypted SUN PICCData
 * @param salt   - 16-byte random salt assigned at registration
 * @returns 64-character lowercase hex string (32 bytes)
 */
export function computeNfcPubId(tagUid: Buffer, salt: Buffer): string {
  // Concatenate UID and salt before hashing to bind both inputs together
  const combined = Buffer.concat([tagUid, salt])
  return sha256(combined).toString('hex')
}

/**
 * Decrypt a single AES-128-CBC ciphertext block (or multiple blocks).
 *
 * Auto-padding is disabled: the ciphertext length must be an exact multiple
 * of 16 bytes. Used for NTAG 424 DNA SUN PICCData decryption where the chip
 * always encrypts exactly one 16-byte block with a zero IV.
 *
 * @param key        - 16-byte AES-128 key
 * @param iv         - 16-byte initialisation vector (zero for SUN)
 * @param ciphertext - Input bytes (must be a multiple of 16)
 * @returns Plaintext bytes (same length as ciphertext)
 */
export function aes128CbcDecrypt(key: Buffer, iv: Buffer, ciphertext: Buffer): Buffer {
  const decipher = createDecipheriv('aes-128-cbc', key, iv)
  decipher.setAutoPadding(false)
  return Buffer.concat([decipher.update(ciphertext), decipher.final()])
}

/**
 * Encrypt plaintext with AES-128-CBC.
 *
 * Auto-padding is disabled; the plaintext length must be an exact multiple
 * of 16 bytes. This function is used internally by aesCmac to implement
 * the CBC-MAC step of RFC 4493.
 *
 * @param key       - 16-byte AES-128 key
 * @param iv        - 16-byte initialisation vector
 * @param plaintext - Input bytes (must be a multiple of 16)
 * @returns Ciphertext bytes (same length as plaintext)
 */
function aes128CbcEncrypt(key: Buffer, iv: Buffer, plaintext: Buffer): Buffer {
  const cipher = createCipheriv('aes-128-cbc', key, iv)
  cipher.setAutoPadding(false)
  return Buffer.concat([cipher.update(plaintext), cipher.final()])
}

/**
 * Compute a 16-byte AES-CMAC tag over a message using a 128-bit key.
 *
 * Implements RFC 4493 "The AES-CMAC Algorithm" exactly:
 *   1. Generate subkeys K1 and K2 from L = AES(key, 0^128):
 *      - K1 = L << 1;         if MSB(L) == 0
 *      - K1 = (L << 1) XOR Rb; if MSB(L) == 1   (Rb = 0x87 in the last byte)
 *      - K2 derived from K1 the same way.
 *   2. Divide the message into 16-byte blocks. The last block is XOR-ed with
 *      K1 (if complete) or padded with 0x80... and XOR-ed with K2.
 *   3. Chain-encrypt all blocks with AES-128-CBC starting from a zero IV.
 *      The result is the final ciphertext block (16 bytes).
 *
 * Used for:
 *   - Session MAC key derivation (SV2 input)
 *   - SDMMAC verification (MAC over empty or non-empty input)
 *
 * @param key     - 16-byte AES-128 key
 * @param message - Arbitrary-length input (including empty)
 * @returns 16-byte CMAC tag
 */
export function aesCmac(key: Buffer, message: Buffer): Buffer {
  const BLOCK_SIZE = 16
  const ZERO_BLOCK = Buffer.alloc(BLOCK_SIZE, 0)
  // Rb constant from RFC 4493: 0x00...00 0x87 (irreducible polynomial for GF(2^128))
  const Rb = Buffer.from('00000000000000000000000000000087', 'hex')

  // Step 1: Derive subkeys K1 and K2
  // L = AES(key, 0^128)
  const L = aes128CbcEncrypt(key, ZERO_BLOCK, ZERO_BLOCK)

  /**
   * Left-shift a 16-byte buffer by one bit (big-endian, MSB first).
   * Each byte gains the MSB of the next byte as its new LSB.
   */
  function shift1(b: Buffer): Buffer {
    const result = Buffer.alloc(BLOCK_SIZE)
    for (let i = 0; i < BLOCK_SIZE - 1; i++) {
      result[i] = ((b[i] << 1) | (b[i + 1] >> 7)) & 0xff
    }
    result[BLOCK_SIZE - 1] = (b[BLOCK_SIZE - 1] << 1) & 0xff
    return result
  }

  /** XOR two 16-byte buffers element-wise. */
  function xor(a: Buffer, b: Buffer): Buffer {
    const result = Buffer.alloc(BLOCK_SIZE)
    for (let i = 0; i < BLOCK_SIZE; i++) {
      result[i] = a[i] ^ b[i]
    }
    return result
  }

  // K1 = (MSB(L) == 0) ? L<<1 : (L<<1) XOR Rb
  const K1 = (L[0] & 0x80) === 0 ? shift1(L) : xor(shift1(L), Rb)
  // K2 = (MSB(K1) == 0) ? K1<<1 : (K1<<1) XOR Rb
  const K2 = (K1[0] & 0x80) === 0 ? shift1(K1) : xor(shift1(K1), Rb)

  // Step 2: Compute number of 16-byte blocks needed
  const msgLen = message.length
  let n: number
  let lastBlockComplete: boolean

  if (msgLen === 0) {
    // Special case: empty message uses a single incomplete (padded) block
    n = 1
    lastBlockComplete = false
  } else {
    n = Math.ceil(msgLen / BLOCK_SIZE)
    // The last block is "complete" only when the message length is an exact multiple of 16
    lastBlockComplete = msgLen % BLOCK_SIZE === 0
  }

  // Step 3: Prepare the final block
  // Complete last block: XOR with K1 (marks it as a full block per RFC 4493)
  // Incomplete last block: pad with 0x80 followed by zeros, then XOR with K2
  let lastBlock: Buffer
  if (lastBlockComplete) {
    const raw = message.subarray((n - 1) * BLOCK_SIZE, n * BLOCK_SIZE)
    lastBlock = xor(Buffer.from(raw), K1)
  } else {
    const padded = Buffer.alloc(BLOCK_SIZE, 0)
    const partial = message.subarray((n - 1) * BLOCK_SIZE)
    partial.copy(padded)
    // 0x80 padding byte immediately after the last data byte (ISO/IEC 7816-4)
    padded[partial.length] = 0x80
    lastBlock = xor(padded, K2)
  }

  // Step 4: CBC-MAC over all blocks except the last, then encrypt the last block
  // X is the running CBC state; it starts as all zeros.
  let X: Buffer = ZERO_BLOCK
  for (let i = 0; i < n - 1; i++) {
    const block = message.subarray(i * BLOCK_SIZE, (i + 1) * BLOCK_SIZE)
    // Each block is XOR-ed with the previous ciphertext (CBC chaining) before encryption
    X = aes128CbcEncrypt(key, ZERO_BLOCK, xor(X, block as Buffer)) as Buffer
  }
  // Encrypt the prepared final block; the result is the CMAC tag
  return aes128CbcEncrypt(key, ZERO_BLOCK, xor(X, lastBlock))
}

/**
 * Encrypt a single 16-byte block with AES-128-ECB (no padding, no IV).
 *
 * ECB mode is appropriate here because we always encrypt exactly one block,
 * so the lack of chaining in ECB does not introduce the block-level pattern
 * leakage that makes ECB unsafe for multi-block messages.
 *
 * Used exclusively for NXP-style key diversification (deriveTagKey, deriveTagKeys).
 *
 * @param key   - 16-byte AES-128 master key
 * @param block - Exactly 16 bytes to encrypt
 * @returns 16-byte ciphertext (the derived key)
 */
export function aes128EcbEncrypt(key: Buffer, block: Buffer): Buffer {
  const cipher = createCipheriv('aes-128-ecb', key, null)
  cipher.setAutoPadding(false)
  return Buffer.concat([cipher.update(block), cipher.final()])
}

/**
 * Derive a single per-chip AES-128 key from a brand master key and the tag UID.
 *
 * Derivation formula:
 *   Derived_Key = AES128_ECB(masterKey, UID || 0x00...0x00)
 *
 * The UID (7 bytes) is left-aligned in a 16-byte block; unused bytes are zero.
 * This scheme lets the brand regenerate any chip's key from the master key and
 * the chip's physical UID, so keys never need to be stored individually.
 *
 * Security property: knowledge of one chip's derived key does NOT reveal the
 * master key or any other chip's key (AES is a one-way function without the key).
 *
 * @param masterKey - 16-byte AES-128 brand master key (kept server-side)
 * @param uid       - 7-byte chip UID (read from the SUN PICCData or pre-registered)
 * @returns 16-byte derived AES-128 key for this chip
 */
export function deriveTagKey(masterKey: Buffer, uid: Buffer): Buffer {
  const padded = Buffer.alloc(16, 0)
  // Copy at most 16 bytes of the UID (in practice always 7 bytes for NTAG 424 DNA)
  uid.copy(padded, 0, 0, Math.min(uid.length, 16))
  return aes128EcbEncrypt(masterKey, padded)
}

/**
 * Derive all four per-chip AES-128 keys from a brand master key and the tag UID.
 *
 * Each key uses a distinct 1-byte "slot" value prepended to the UID:
 *   block = [slot, uid[0..6], 0x00...0x00]  (16 bytes)
 *   Key_N = AES128_ECB(masterKey, block)
 *
 * Slot assignments:
 *   0x00 -> appMasterKey    (NTAG 424 DNA Application Master Key, Key 0)
 *   0x01 -> sdmmacInputKey  (SDM MAC Input Key, used for authenticated key operations)
 *   0x02 -> sdmEncKey       (K_SDMMetaRead, Key 2: encrypts UID + counter in SUN)
 *   0x03 -> sdmMacKey       (K_SDMFileRead, Key 3: base for session MAC key derivation)
 *
 * Using a different slot byte per role ensures each key is cryptographically
 * independent: compromise of one key (e.g., from a captured APDU trace) does
 * not expose the others. The single BRAND_MASTER_KEY can regenerate all keys
 * for any chip given its UID.
 *
 * @param masterKey - 16-byte AES-128 brand master key
 * @param uid       - 7-byte chip UID
 * @returns Object containing all four 16-byte derived keys
 */
export function deriveTagKeys(masterKey: Buffer, uid: Buffer): {
  appMasterKey: Buffer
  sdmmacInputKey: Buffer
  sdmEncKey: Buffer
  sdmMacKey: Buffer
} {
  /**
   * Build the 16-byte input block for a given slot.
   * Layout: [slot (1 byte)] [uid (up to 15 bytes)] [0x00 padding to fill 16 bytes]
   */
  const makeBlock = (slot: number): Buffer => {
    const block = Buffer.alloc(16, 0)
    block[0] = slot
    // Copy UID starting at byte 1; limit to 15 bytes to stay within the 16-byte block
    uid.copy(block, 1, 0, Math.min(uid.length, 15))
    return block
  }
  return {
    appMasterKey:   aes128EcbEncrypt(masterKey, makeBlock(0x00)),
    sdmmacInputKey: aes128EcbEncrypt(masterKey, makeBlock(0x01)),
    sdmEncKey:      aes128EcbEncrypt(masterKey, makeBlock(0x02)),
    sdmMacKey:      aes128EcbEncrypt(masterKey, makeBlock(0x03)),
  }
}

/**
 * Verify an AES-CMAC tag using a constant-time comparison.
 *
 * Recomputes the expected CMAC with aesCmac and compares it byte-by-byte
 * with the provided MAC using a constant-time XOR-accumulate loop.
 * The loop always runs for the full length regardless of where the first
 * mismatch occurs, preventing timing side-channels.
 *
 * @param key     - 16-byte AES-128 key
 * @param message - Message bytes that were authenticated
 * @param mac     - MAC bytes to verify (must be 16 bytes for AES-CMAC)
 * @returns true if the MAC is valid, false otherwise
 */
export function verifyAesCmac(key: Buffer, message: Buffer, mac: Buffer): boolean {
  const expected = aesCmac(key, message)
  if (expected.length !== mac.length) return false
  // Constant-time comparison: accumulate all bit differences with OR.
  // Returns true only when diff is zero (all bytes matched).
  let diff = 0
  for (let i = 0; i < expected.length; i++) {
    diff |= expected[i] ^ mac[i]
  }
  return diff === 0
}
