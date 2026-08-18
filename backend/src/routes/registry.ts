/**
 * Public RTP-1 brand/emission registry.
 *
 * POST /notify is public and rate-limited, but an emission is trusted only after
 * Ravencoin Core has decoded the referenced transaction and the decoded outputs
 * prove the claimed asset name/type, canonical burn, and owner-token semantics.
 */
import { Router, Request, Response } from 'express'
import { rateLimit } from 'express-rate-limit'
import { getDb } from '../middleware/cache.js'
import { requireAdminKey } from '../middleware/auth.js'
import { ravencoinService } from '../services/ravencoin.js'
import { validateRegistryIssuance, type RegistryAssetType } from '../services/registry-issuance.js'

const router = Router()

const notifyLimiter = rateLimit({
  windowMs: 60_000,
  max: 5,
  standardHeaders: true,
  legacyHeaders: false,
  message: { error: 'Too many requests', code: 'RATE_LIMITED' }
})

function ensureTable() {
  getDb().prepare(`
    CREATE TABLE IF NOT EXISTS brand_registry (
      brand_name TEXT PRIMARY KEY,
      registered_at TEXT NOT NULL,
      protocol_version TEXT DEFAULT 'RTP-1'
    )
  `).run()
}

const VALID_ASSET_TYPES = ['root', 'sub', 'unique'] as const
type AssetType = typeof VALID_ASSET_TYPES[number]

router.get('/brands', (_req: Request, res: Response) => {
  ensureTable()
  const brands = getDb().prepare('SELECT brand_name as name, registered_at FROM brand_registry ORDER BY registered_at ASC').all()
  res.json({ brands, count: brands.length, protocol: 'RTP-1' })
})

router.post('/register', requireAdminKey, (req: Request, res: Response) => {
  const { brand_name, protocol_version } = req.body
  if (!brand_name || typeof brand_name !== 'string' || brand_name.trim().length < 2) {
    res.status(400).json({ error: 'brand_name (min 2 chars) required', code: 'INVALID_BRAND_NAME' })
    return
  }
  if (!/^[A-Za-z0-9 _.-]{2,64}$/.test(brand_name.trim())) {
    res.status(400).json({ error: 'brand_name contains invalid characters', code: 'INVALID_BRAND_NAME' })
    return
  }
  ensureTable()
  const name = brand_name.trim()
  const now = new Date().toISOString()
  getDb().prepare(`
    INSERT OR IGNORE INTO brand_registry (brand_name, registered_at, protocol_version)
    VALUES (?, ?, ?)
  `).run(name, now, protocol_version || 'RTP-1')

  res.json({ success: true, brand_name: name, registered_at: now, message: 'Brand registered in RavenTag public registry.' })
})

router.post('/notify', notifyLimiter, async (req: Request, res: Response) => {
  const { asset_name, asset_type, txid, issued_at, protocol_version } = req.body

  if (!asset_name || typeof asset_name !== 'string' || asset_name.trim().length < 2) {
    res.status(400).json({ error: 'asset_name required' })
    return
  }
  if (!VALID_ASSET_TYPES.includes(asset_type as AssetType)) {
    res.status(400).json({ error: 'asset_type must be root, sub, or unique' })
    return
  }
  if (!txid || typeof txid !== 'string' || !/^[0-9a-fA-F]{64}$/.test(txid)) {
    res.status(400).json({ error: 'txid must be a valid 64-char hex transaction id' })
    return
  }

  let proof: ReturnType<typeof validateRegistryIssuance>
  try {
    const rawTx = await ravencoinService.getRawTransactionVerbose(txid)
    proof = validateRegistryIssuance(
      rawTx,
      txid,
      asset_name,
      asset_type as RegistryAssetType
    )
  } catch (err) {
    console.warn(`[Registry] Cannot obtain consensus-decoded tx ${txid}: ${(err as Error).message}`)
    res.status(503).json({ error: 'Unable to verify issuance transaction', code: 'CHAIN_VERIFICATION_UNAVAILABLE' })
    return
  }

  if (!proof.ok) {
    res.status(422).json({ error: proof.reason, code: 'INVALID_ISSUANCE_PROOF' })
    return
  }

  const name = proof.assetName
  const now = typeof issued_at === 'string' && issued_at.length <= 64
    ? issued_at
    : new Date().toISOString()
  const version = typeof protocol_version === 'string' && protocol_version.length <= 32
    ? protocol_version
    : 'RTP-1'

  const db = getDb()
  db.prepare(`
    INSERT OR IGNORE INTO asset_emissions (asset_name, asset_type, txid, issued_at, protocol_version)
    VALUES (?, ?, ?, ?, ?)
  `).run(name, proof.assetType, txid.toLowerCase(), now, version)

  if (proof.assetType === 'root') {
    ensureTable()
    db.prepare(`
      INSERT OR IGNORE INTO brand_registry (brand_name, registered_at, protocol_version)
      VALUES (?, ?, ?)
    `).run(name, now, version)
  }

  res.json({ success: true })
})

router.delete('/brands/:brandName', requireAdminKey, (req: Request, res: Response) => {
  const brandName = req.params.brandName
  ensureTable()
  const result = getDb().prepare('DELETE FROM brand_registry WHERE brand_name = ?').run(brandName)
  if (result.changes === 0) {
    res.status(404).json({ error: 'Brand not found', code: 'NOT_FOUND' })
    return
  }
  res.json({ success: true, brand_name: brandName })
})

router.get('/emissions', (_req: Request, res: Response) => {
  const emissions = getDb()
    .prepare('SELECT asset_name, asset_type, txid, issued_at, protocol_version FROM asset_emissions ORDER BY issued_at DESC LIMIT 200')
    .all()
  res.json({ emissions, count: emissions.length })
})

export default router
