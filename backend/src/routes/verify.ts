/**
 * @file routes/verify.ts
 *
 * Express router for NFC tag verification endpoints.
 *
 * Three verification modes are provided:
 *
 * 1. POST /api/verify/sun
 *    Low-level: client supplies AES keys explicitly. Operator-key protected.
 *    Useful for testing, tooling, or advanced integrations.
 *
 * 2. POST /api/verify/full
 *    Trustless full verification: client supplies AES keys and optionally an
 *    expected Ravencoin asset name. The server decrypts the SUN payload,
 *    derives nfc_pub_id, and cross-checks against on-chain IPFS metadata.
 *
 * 3. POST /api/verify/tag
 *    Brand-sovereign server-side verification. Client sends only {asset, e, m}.
 *    The server holds BRAND_MASTER_KEY and BRAND_SALT, derives per-chip keys,
 *    and performs the full verification pipeline without exposing any key to
 *    the client. Suitable for consumer-facing apps.
 *
 * All three endpoints perform counter-replay detection (HIGH-3 mitigation)
 * and revocation checks (DB + metadata flag).
 */

import { Router, Request, Response } from 'express'
import { verifySunMessage, deriveTagKey, deriveTagKeys } from '../services/ntag424.js'
import { ravencoinService } from '../services/ravencoin.js'
import { fetchIpfsMetadata } from '../services/ipfs.js'
import { computeNfcPubId } from '../utils/crypto.js'
import { sunVerifyWithSaltSchema, fullVerifyRequestSchema, serverVerifySchema } from '../utils/validation.js'
import { isAssetRevoked, checkAndUpdateCounter, getChipByAsset } from '../middleware/cache.js'
import { requireOperatorKey } from '../middleware/auth.js'

const router = Router()

/**
 * POST /api/verify/sun
 *
 * Decrypt and verify a SUN (Secure Unique NFC) message from an NTAG 424 DNA chip.
 *
 * The client supplies both AES-128 keys (sdmmac_key and sun_mac_key) in the
 * request body. This endpoint must NOT be exposed publicly because client-side
 * AES key transport is only acceptable in trusted operator contexts.
 *
 * Request body (sunVerifyRequestSchema):
 *   e          - 32 hex chars: AES-128-CBC encrypted PICCData (16 bytes)
 *   m          - 16 hex chars: truncated SDMMAC (8 bytes)
 *   sdmmac_key - 32 hex chars: AES-128 K_SDMMetaRead (Key 2)
 *   sun_mac_key - 32 hex chars: AES-128 K_SDMFileRead (Key 3)
 *   salt       - 32 hex chars (optional): used to compute nfc_pub_id
 *
 * Response:
 *   { valid, tag_uid, counter, nfc_pub_id? }
 *
 * Requires: operator key (X-Admin-Key or X-Api-Key header).
 */
router.post('/sun', requireOperatorKey, async (req: Request, res: Response) => {
  // Validate request body against Zod schema; return 400 with details on failure
  // sunVerifyWithSaltSchema requires salt to enforce counter-replay detection (HIGH-1)
  const parsed = sunVerifyWithSaltSchema.safeParse(req.body)
  if (!parsed.success) {
    res.status(400).json({ error: 'Invalid request', details: parsed.error.issues, code: 'VALIDATION_ERROR' })
    return
  }

  const { e, m, sdmmac_key, sun_mac_key, salt } = parsed.data

  // Convert hex strings to Buffers for the crypto layer
  const sdmmacKeyBuf = Buffer.from(sdmmac_key, 'hex')
  const sunMacKeyBuf = Buffer.from(sun_mac_key, 'hex')

  // Run SUN verification: decrypt PICCData, derive session MAC key, verify MAC
  const result = verifySunMessage(e, m, sdmmacKeyBuf, sunMacKeyBuf)

  if (!result.valid) {
    res.json({ valid: false, error: result.error })
    return
  }

  // Build the base response with UID and tap counter
  const response: Record<string, unknown> = {
    valid: true,
    tag_uid: result.tagUid?.toString('hex'),
    counter: result.counter
  }

  // If a salt was provided, compute nfc_pub_id = SHA-256(uid || salt) and run
  // counter-replay detection (HIGH-3). Replay check requires a stable identifier
  // (nfc_pub_id) to key the counter cache on.
  if (salt && result.tagUid) {
    const saltBuf = Buffer.from(salt, 'hex')
    const nfcPubId = computeNfcPubId(result.tagUid, saltBuf)
    response['nfc_pub_id'] = nfcPubId
    // Counter replay check (HIGH-3), only when we have a pub_id to key on
    if (result.counter !== undefined && !checkAndUpdateCounter(nfcPubId, result.counter)) {
      res.json({ valid: false, error: 'NFC counter replay detected' })
      return
    }
  }

  res.json(response)
})

/**
 * POST /api/verify/full
 *
 * Full trustless verification: SUN decryption, nfc_pub_id derivation, and
 * optional Ravencoin blockchain asset cross-check.
 *
 * This endpoint is designed for wallets and dApps that hold the AES keys
 * themselves (e.g., from a prior /api/brand/derive-chip-keys call). Verification
 * is fully client-driven: the server performs the same math the client could do
 * locally, acting as a neutral relay with revocation and replay state.
 *
 * Request body (fullVerifyRequestSchema extends sunVerifyRequestSchema):
 *   e              - 32 hex chars: encrypted PICCData
 *   m              - 16 hex chars: truncated SDMMAC
 *   sdmmac_key     - 32 hex chars: K_SDMMetaRead
 *   sun_mac_key    - 32 hex chars: K_SDMFileRead
 *   salt           - 32 hex chars (optional): salt for nfc_pub_id
 *   expected_asset - Ravencoin asset name (optional): triggers blockchain check
 *
 * Response when expected_asset is provided:
 *   { authentic, sun_valid, tag_uid, counter, nfc_pub_id, asset, metadata,
 *     revoked, metadata_revoked, revoked_reason?, revoked_at?, step_failed }
 *
 * Response when expected_asset is omitted:
 *   { authentic: null, sun_valid, tag_uid, counter, nfc_pub_id? }
 *   authentic is null because without an asset to compare against, authenticity
 *   cannot be determined.
 *
 * Public endpoint (no auth required). Keys are caller-supplied.
 */
router.post('/full', async (req: Request, res: Response) => {
  // Validate and parse request body
  const parsed = fullVerifyRequestSchema.safeParse(req.body)
  if (!parsed.success) {
    res.status(400).json({ error: 'Invalid request', details: parsed.error.issues, code: 'VALIDATION_ERROR' })
    return
  }

  const { e, m, sdmmac_key, sun_mac_key, salt, expected_asset } = parsed.data
  const sdmmacKeyBuf = Buffer.from(sdmmac_key, 'hex')
  const sunMacKeyBuf = Buffer.from(sun_mac_key, 'hex')

  // Step 1: Verify SUN message (decrypt + MAC check)
  const sunResult = verifySunMessage(e, m, sdmmacKeyBuf, sunMacKeyBuf)
  if (!sunResult.valid) {
    res.json({
      authentic: false,
      step_failed: 'sun_verification',
      error: sunResult.error
    })
    return
  }

  // Step 2: Compute nfc_pub_id = SHA-256(uid || salt) if both are present.
  // This is the privacy-preserving public identifier stored on-chain in IPFS
  // metadata, linking the tag to an asset without revealing the raw UID.
  let nfcPubId: string | undefined
  if (salt && sunResult.tagUid) {
    nfcPubId = computeNfcPubId(sunResult.tagUid, Buffer.from(salt, 'hex'))
  }

  // Step 2b: Counter replay check (HIGH-3)
  // The NTAG 424 DNA increments its internal counter on every tap. If a response
  // is replayed with a previously seen counter value, it is rejected. This defends
  // against relay and replay attacks even when the attacker has captured a URL.
  if (nfcPubId && sunResult.counter !== undefined) {
    if (!checkAndUpdateCounter(nfcPubId, sunResult.counter)) {
      res.json({
        authentic: false,
        step_failed: 'counter_replay',
        error: 'NFC counter replay detected, possible cloning attempt'
      })
      return
    }
  }

  // Step 3: If expected_asset provided, verify nfc_pub_id matches
  // Fetch the asset from Ravencoin (RPC or ElectrumX fallback), then retrieve
  // its IPFS metadata and compare the stored nfc_pub_id field to the one we
  // just derived from the physical chip.
  if (expected_asset && nfcPubId) {
    try {
      // ipfsFetcher wraps fetchIpfsMetadata so ravencoinService stays decoupled from IPFS
      const ipfsFetcher = (uri: string) => fetchIpfsMetadata(uri)
      const assetResult = await ravencoinService.getAssetWithMetadata(expected_asset, ipfsFetcher)

      if (!assetResult) {
        res.json({ authentic: false, step_failed: 'asset_lookup', error: 'Asset not found on Ravencoin' })
        return
      }

      // Case-insensitive comparison: hex strings are conventionally lowercase but
      // the metadata field could be mixed-case depending on the issuer.
      const metadataMatch =
        assetResult.metadata?.nfc_pub_id?.toLowerCase() === nfcPubId.toLowerCase()

      // Note: IPFS metadata is immutable and cannot be used for revocation.
      // The `status` field in metadata reflects the state at issuance time only.
      // All revocations are handled via the backend SQLite revocation table.

      // Check revocation status BEFORE declaring authentic
      const revocationStatus = isAssetRevoked(expected_asset)
      const revoked = revocationStatus.revoked

      // The tag is authentic only if the nfc_pub_id matches AND the asset is not revoked
      const authentic = metadataMatch && !revoked

      res.json({
        authentic,
        sun_valid: true,
        tag_uid: sunResult.tagUid?.toString('hex'),
        counter: sunResult.counter,
        nfc_pub_id: nfcPubId,
        asset: assetResult.asset,
        metadata: assetResult.metadata,
        revoked,
        revoked_reason: revoked ? revocationStatus.reason : undefined,
        revoked_at: revoked ? revocationStatus.revokedAt : undefined,
        step_failed: revoked ? 'asset_revoked' : metadataMatch ? null : 'nfc_pub_id_mismatch'
      })
      return
    } catch (err: unknown) {
      console.error('[verify]', err)
      // Distinguish between a node connectivity failure and other errors
      res.status(502).json({ authentic: false, step_failed: 'blockchain_query', error: 'Service temporarily unavailable' })
      return
    }
  }

  // No asset to verify against, just return SUN result
  // authentic is explicitly null (not false) to signal "undetermined, not failed"
  res.json({
    authentic: null, // Cannot determine without asset check
    sun_valid: true,
    tag_uid: sunResult.tagUid?.toString('hex'),
    counter: sunResult.counter,
    nfc_pub_id: nfcPubId
  })
})

/**
 * POST /api/verify/tag
 *
 * Brand-sovereign server-side verification.
 *
 * This endpoint is designed for consumer apps that should NOT receive any AES
 * keys. The client sends only {asset, e, m}. The server:
 *   1. Looks up the registered chip UID for the given asset in the local DB.
 *   2. Derives the per-chip AES keys from BRAND_MASTER_KEY + chip UID.
 *   3. Decrypts and verifies the SUN payload.
 *   4. Checks that the decrypted UID matches the registered UID (anti-substitution).
 *   5. Runs counter-replay detection.
 *   6. Fetches the Ravencoin asset and its IPFS metadata.
 *   7. Compares nfc_pub_id and checks revocation.
 *
 * Requires environment variables:
 *   BRAND_MASTER_KEY - 32 hex chars: brand AES-128 master key
 *   BRAND_SALT       - 32 hex chars: salt used when computing nfc_pub_id at chip registration
 *
 * Request body (serverVerifySchema):
 *   asset - Ravencoin asset name (supports unique-asset # separator)
 *   e     - 32 hex chars: encrypted PICCData
 *   m     - 16 hex chars: truncated SDMMAC
 *
 * Response:
 *   { authentic, sun_valid, tag_uid, counter, nfc_pub_id, asset, metadata,
 *     revoked, metadata_revoked, revoked_reason?, revoked_at?, step_failed }
 *
 * Public endpoint (no client auth required). Security is provided by the server
 * holding the keys, not by authenticating the caller.
 */
router.post('/tag', async (req: Request, res: Response) => {
  // Validate request body
  const parsed = serverVerifySchema.safeParse(req.body)
  if (!parsed.success) {
    res.status(400).json({ error: 'Invalid request', details: parsed.error.issues, code: 'VALIDATION_ERROR' })
    return
  }

  const { asset, e, m } = parsed.data
  // Normalize asset name to uppercase for consistent DB and blockchain lookups
  const assetUpper = asset.toUpperCase()

  // Require BRAND_MASTER_KEY and BRAND_SALT to be set in env
  // Without these, server-side key derivation is impossible
  const masterKeyHex = process.env.BRAND_MASTER_KEY
  const saltHex = process.env.BRAND_SALT
  if (!masterKeyHex || !saltHex) {
    res.status(503).json({
      error: 'Brand AES keys not configured on this server. Set BRAND_MASTER_KEY and BRAND_SALT.',
      code: 'KEYS_NOT_CONFIGURED'
    })
    return
  }

  // Look up registered chip UID for this asset
  // getChipByAsset reads from the local SQLite DB where brand operators register chips
  const chip = getChipByAsset(assetUpper)
  if (!chip) {
    res.status(404).json({
      authentic: false,
      error: 'This tag has not been registered on this server',
      code: 'TAG_NOT_REGISTERED',
      step_failed: 'chip_lookup'
    })
    return
  }

  // Derive per-chip AES keys: three distinct keys per chip (AN12196 style)
  // deriveTagKeys uses AES-ECB(masterKey, [slot || uid]) for each role to ensure
  // cryptographic independence between sdmEncKey, sdmMacKey, and sdmmacInputKey.
  const masterKey = Buffer.from(masterKeyHex, 'hex')
  const tagUid = Buffer.from(chip.tag_uid, 'hex')
  const { sdmEncKey, sdmMacKey } = deriveTagKeys(masterKey, tagUid)

  // Verify SUN MAC and decrypt e to get UID + counter
  const sunResult = verifySunMessage(e, m, sdmEncKey, sdmMacKey)
  if (!sunResult.valid) {
    res.json({ authentic: false, step_failed: 'sun_verification', error: sunResult.error })
    return
  }

  // Extra security: verify the decrypted UID matches the registered UID
  // This prevents an attacker from substituting a different (unconfigured) chip
  // and tricking the server into using the wrong registered chip record.
  if (sunResult.tagUid && sunResult.tagUid.toString('hex').toLowerCase() !== chip.tag_uid.toLowerCase()) {
    res.json({
      authentic: false,
      step_failed: 'uid_mismatch',
      error: 'Decrypted UID does not match registered UID, possible chip substitution'
    })
    return
  }

  // Counter replay check
  // nfc_pub_id is the stable per-chip identifier used to key the counter cache
  const nfcPubId = chip.nfc_pub_id
  if (sunResult.counter !== undefined && !checkAndUpdateCounter(nfcPubId, sunResult.counter)) {
    res.json({
      authentic: false,
      step_failed: 'counter_replay',
      error: 'NFC counter replay detected, possible cloning attempt'
    })
    return
  }

  // Blockchain + IPFS + revocation check
  // Fetch on-chain asset data and IPFS metadata, then confirm nfc_pub_id match
  // and revocation status before declaring the tag authentic.
  try {
    const ipfsFetcher = (uri: string) => fetchIpfsMetadata(uri)
    const assetResult = await ravencoinService.getAssetWithMetadata(assetUpper, ipfsFetcher)

    if (!assetResult) {
      res.json({ authentic: false, step_failed: 'asset_lookup', error: 'Asset not found on Ravencoin' })
      return
    }

    // Compare on-chain nfc_pub_id with the registered chip's nfc_pub_id
    const metadataMatch = assetResult.metadata?.nfc_pub_id?.toLowerCase() === nfcPubId.toLowerCase()

    // Note: IPFS metadata is immutable and cannot be used for revocation.
    // The `status` field in metadata reflects the state at issuance time only.
    // All revocations are handled via the backend SQLite revocation table.

    // Check DB revocation (via /api/brand/revoke or direct SQLite entry)
    const revocationStatus = isAssetRevoked(assetUpper)
    const revoked = revocationStatus.revoked

    // Authentic only if pub_id matches AND not revoked
    const authentic = metadataMatch && !revoked

    res.json({
      authentic,
      sun_valid: true,
      tag_uid: sunResult.tagUid?.toString('hex'),
      counter: sunResult.counter,
      nfc_pub_id: nfcPubId,
      asset: assetResult.asset,
      metadata: assetResult.metadata,
      revoked,
      revoked_reason: revoked ? revocationStatus.reason : undefined,
      revoked_at: revoked ? revocationStatus.revokedAt : undefined,
      step_failed: revoked ? 'asset_revoked' : metadataMatch ? null : 'nfc_pub_id_mismatch'
    })
  } catch (err: unknown) {
    // Log the real error server-side; never surface internal RPC details to the client (CRIT-2)
    console.error('[verify/tag]', err)
    res.status(502).json({ authentic: false, step_failed: 'blockchain_query', error: 'Service temporarily unavailable' })
  }
})

export default router
