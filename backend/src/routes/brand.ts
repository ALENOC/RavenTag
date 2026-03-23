/**
 * Brand routes (routes/brand.ts)
 *
 * Authenticated endpoints for brand operators to manage the full lifecycle of
 * RTP-1 physical product authentication.
 *
 * All Ravencoin wallet operations (asset issuance, transfers, RVN sends) are
 * performed exclusively from the Android app via ElectrumX on-device signing.
 * The backend has no Ravencoin wallet and performs no on-chain writes.
 *
 *   Revocation management:
 *     POST   /api/brand/revoke             Mark an asset as revoked (backend soft-revocation)
 *     DELETE /api/brand/revoke/:assetName  Un-revoke an asset
 *     GET    /api/brand/revoked            List all revoked assets
 *     GET    /api/brand/revoked/:assetName Full revocation status for one asset
 *
 *   Chip registry (NTAG 424 DNA programming):
 *     POST   /api/brand/register-chip      Register chip UID against an asset (operator key)
 *     POST   /api/brand/derive-chip-key    Derive per-chip AES keys for programming (admin key)
 *     DELETE /api/brand/chip/:assetName    Remove a chip registration (admin key)
 *     GET    /api/brand/chips              List all chip registrations (operator key)
 *     GET    /api/brand/chip/:assetName    Get chip for a specific asset (operator key)
 *
 * Auth policy:
 *   - Most write routes require admin key (requireAdminKey).
 *   - Operator-key routes (requireOperatorKey): register-chip, chips, chip/:name.
 */
import { Router, Request, Response } from 'express'
import { requireAdminKey, requireOperatorKey } from '../middleware/auth.js'
import { revokeAsset, unrevokeAsset, listRevokedAssets, isAssetRevoked, registerChip, getChipByAsset, deleteChip, listChips } from '../middleware/cache.js'
import {
  brandRevokeSchema,
  brandRegisterChipSchema,
  brandDeriveChipKeySchema,
  assetNameWithUniqueSchema
} from '../utils/validation.js'
import { computeNfcPubId, deriveTagKeys } from '../utils/crypto.js'

const router = Router()

// Auth policy:
// - Admin-only routes: requireAdminKey (applied per-route below)
// - Operator routes:   requireOperatorKey (accepts admin OR operator key)

/**
 * POST /api/brand/revoke
 * Mark an asset as revoked (counterfeiting/compromise detected).
 * Soft revocation: updates the local SQLite database only.
 * All future scans of the asset will return REVOKED status.
 * Revocation is reversible via DELETE /api/brand/revoke/:assetName.
 *
 * The optional X-Admin-Label header is recorded as 'revoked_by' for audit trails.
 */
router.post('/revoke', requireAdminKey, (req: Request, res: Response) => {
  const parsed = brandRevokeSchema.safeParse(req.body)
  if (!parsed.success) {
    res.status(400).json({ error: 'Invalid request', details: parsed.error.issues, code: 'VALIDATION_ERROR' })
    return
  }

  const { asset_name, reason } = parsed.data
  revokeAsset(asset_name, reason, req.headers['x-admin-label'] as string | undefined)

  res.json({
    success: true,
    asset_name,
    revoked: true,
    reason: reason ?? null
  })
})

/**
 * DELETE /api/brand/revoke/:assetName
 * Un-revoke an asset (remove from revocation list).
 * The asset immediately returns to AUTHENTIC status on the next scan.
 */
router.delete('/revoke/:assetName', requireAdminKey, (req: Request, res: Response) => {
  const { assetName } = req.params
  const parsed = assetNameWithUniqueSchema.safeParse(assetName)
  if (!parsed.success) {
    res.status(400).json({ error: 'Invalid asset name', code: 'INVALID_ASSET_NAME' })
    return
  }

  const ok = unrevokeAsset(assetName)
  if (!ok) {
    res.status(404).json({ error: 'Asset not in revocation list', code: 'NOT_FOUND' })
    return
  }
  res.json({ success: true, asset_name: assetName.toUpperCase() })
})

/**
 * GET /api/brand/revoked
 * List all revoked assets in reverse-chronological order.
 * Includes reason and revocation timestamp for each asset.
 */
router.get('/revoked', requireAdminKey, (_req: Request, res: Response) => {
  const list = listRevokedAssets()
  res.json({ revoked: list, count: list.length })
})

/**
 * GET /api/brand/revoked/:assetName
 * Check revocation status of a specific asset (full detail, admin only).
 * Unlike the public /api/assets/:name/revocation endpoint, this returns
 * the reason, revokedAt timestamp, and revocation details.
 */
router.get('/revoked/:assetName', requireAdminKey, (req: Request, res: Response) => {
  const status = isAssetRevoked(req.params.assetName)
  res.json(status)
})



/**
 * POST /api/brand/register-chip
 * Register an NTAG 424 DNA chip UID against a Ravencoin asset.
 * The server derives nfc_pub_id = SHA-256(uid || BRAND_SALT) from env.
 * This mapping is required for server-side SUN verification (POST /api/verify/tag).
 *
 * Security: An operator cannot overwrite an existing registration (would allow
 * hijacking an already-issued asset). To update a registration, an admin must
 * first delete the existing one via DELETE /api/brand/chip/:assetName.
 *
 * Requires operator key or admin key. BRAND_SALT must be configured.
 */
router.post('/register-chip', requireOperatorKey, (req: Request, res: Response) => {
  const parsed = brandRegisterChipSchema.safeParse(req.body)
  if (!parsed.success) {
    res.status(400).json({ error: 'Invalid request', details: parsed.error.issues, code: 'VALIDATION_ERROR' })
    return
  }

  const saltHex = process.env.BRAND_SALT
  if (!saltHex) {
    res.status(503).json({ error: 'BRAND_SALT not configured on this server', code: 'SALT_NOT_CONFIGURED' })
    return
  }

  const { asset_name, tag_uid } = parsed.data
  const assetUpper = asset_name.toUpperCase()

  // Security Check: Do not allow overwriting an existing registration
  // This prevents an operator from hijacking an already issued asset.
  const existing = getChipByAsset(assetUpper)
  if (existing) {
    res.status(409).json({
      error: 'Asset already registered with a chip. Delete the existing registration first (Admin required).',
      code: 'ALREADY_REGISTERED'
    })
    return
  }

  // Derive nfc_pub_id = SHA-256(tag_uid_bytes || salt_bytes)
  // This keeps the raw UID private; only the hash is stored and exposed in metadata
  const tagUidBuf = Buffer.from(tag_uid, 'hex')
  const saltBuf = Buffer.from(saltHex, 'hex')
  const nfcPubId = computeNfcPubId(tagUidBuf, saltBuf)

  registerChip(assetUpper, tag_uid, nfcPubId)

  res.status(201).json({
    success: true,
    asset_name: assetUpper,
    tag_uid: tag_uid.toLowerCase(),
    nfc_pub_id: nfcPubId
  })
})

/**
 * POST /api/brand/derive-chip-key
 * Derive the per-chip AES-128 keys and nfc_pub_id for a given tag UID.
 *
 * The brand's Android app calls this after reading the tag UID (step 1 of programming).
 * The backend derives keys from BRAND_MASTER_KEY using slot-prefixed AES-ECB diversification:
 *   Key_N = AES128_ECB(BRAND_MASTER_KEY, [slot_N, uid_bytes..., 0x00 padding])
 * where slot 0x00=appMasterKey, 0x01=sdmmacInputKey, 0x02=sdmEncKey, 0x03=sdmMacKey.
 *
 * nfc_pub_id = SHA-256(uid || BRAND_SALT) , consistent with /api/brand/register-chip.
 *
 * Keys travel over HTTPS with operator authentication and are never stored.
 * Compromise of one chip's derived keys does not expose BRAND_MASTER_KEY.
 */
router.post('/derive-chip-key', requireAdminKey, (req: Request, res: Response) => {
  const parsed = brandDeriveChipKeySchema.safeParse(req.body)
  if (!parsed.success) {
    res.status(400).json({ error: 'tag_uid must be 14 hex chars (7 bytes)', code: 'VALIDATION_ERROR' })
    return
  }

  const masterKeyHex = process.env.BRAND_MASTER_KEY
  if (!masterKeyHex) {
    res.status(503).json({ error: 'BRAND_MASTER_KEY not configured on this server', code: 'KEY_NOT_CONFIGURED' })
    return
  }

  const saltHex = process.env.BRAND_SALT
  if (!saltHex) {
    res.status(503).json({ error: 'BRAND_SALT not configured on this server', code: 'SALT_NOT_CONFIGURED' })
    return
  }

  const uidBuf = Buffer.from(parsed.data.tag_uid, 'hex')
  const masterKey = Buffer.from(masterKeyHex, 'hex')
  const saltBuf = Buffer.from(saltHex, 'hex')

  // Derive all four per-chip keys in one call; each key is cryptographically independent
  const { appMasterKey, sdmmacInputKey, sdmEncKey, sdmMacKey } = deriveTagKeys(masterKey, uidBuf)
  // Derive nfc_pub_id separately using the salt (same algorithm as register-chip)
  const nfcPubId = computeNfcPubId(uidBuf, saltBuf)

  res.json({
    app_master_key: appMasterKey.toString('hex'),
    sdmmac_input_key: sdmmacInputKey.toString('hex'),
    sdm_enc_key: sdmEncKey.toString('hex'),
    sdm_mac_key: sdmMacKey.toString('hex'),
    nfc_pub_id: nfcPubId
  })
})

/**
 * DELETE /api/brand/chip/:assetName
 * Remove a chip registration from the chip_registry table.
 * Required before a chip can be re-registered (e.g. to fix an incorrect UID entry).
 * Requires admin key (operators cannot delete registrations).
 */
router.delete('/chip/:assetName', requireAdminKey, (req: Request, res: Response) => {
  const parsed = assetNameWithUniqueSchema.safeParse(req.params.assetName)
  if (!parsed.success) {
    res.status(400).json({ error: 'Invalid asset name', code: 'INVALID_ASSET_NAME' })
    return
  }
  const ok = deleteChip(req.params.assetName)
  if (!ok) {
    res.status(404).json({ error: 'Chip registration not found', code: 'NOT_FOUND' })
    return
  }
  res.json({ success: true, asset_name: req.params.assetName.toUpperCase() })
})

/**
 * GET /api/brand/chips
 * List all registered chip-to-asset mappings in reverse-chronological order.
 * Accessible to operators and admins.
 */
router.get('/chips', requireOperatorKey, (_req: Request, res: Response) => {
  const chips = listChips()
  res.json({ chips, count: chips.length })
})

/**
 * GET /api/brand/chip/:assetName
 * Get the chip registration for a specific asset.
 * Returns tag_uid and nfc_pub_id. Returns 404 if the asset has no registered chip.
 * Accessible to operators and admins.
 */
router.get('/chip/:assetName', requireOperatorKey, (req: Request, res: Response) => {
  const chip = getChipByAsset(req.params.assetName)
  if (!chip) {
    res.status(404).json({ error: 'Chip not registered for this asset', code: 'NOT_FOUND' })
    return
  }
  res.json({ asset_name: req.params.assetName.toUpperCase(), ...chip })
})



export default router
