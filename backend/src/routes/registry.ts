/**
 * Registry routes (routes/registry.ts)
 *
 * Public registry for RTP-1 brands and on-chain asset emissions.
 * Allows the community to discover brands using the RavenTag protocol and to
 * verify that asset issuance transactions exist on the Ravencoin blockchain.
 *
 * All GET endpoints are public (no auth required).
 * POST /register requires admin key to prevent spam registrations.
 * POST /notify is public but strictly rate-limited (5 req/min per IP) and
 *   requires the txid to be verified on the Ravencoin blockchain via ElectrumX
 *   before the notification is stored.
 *
 * Routes:
 *   GET  /api/registry/brands    List all registered RTP-1 brands
 *   POST /api/registry/register  Register a brand (admin key required)
 *   POST /api/registry/notify    Notify a new on-chain asset emission (public, rate-limited)
 *   GET  /api/registry/emissions List recorded on-chain emissions (last 200)
 */
import { Router, Request, Response } from 'express'
import { rateLimit } from 'express-rate-limit'
import { getDb } from '../middleware/cache.js'
import { requireAdminKey } from '../middleware/auth.js'
import { electrumXService } from '../services/electrumx.js'

const router = Router()

// Strict limiter for the public notify endpoint: 5 req/min per IP
// Prevents spamming the registry with invalid or fictitious emission notifications
const notifyLimiter = rateLimit({
  windowMs: 60_000,
  max: 5,
  standardHeaders: true,
  legacyHeaders: false,
  message: { error: 'Too many requests', code: 'RATE_LIMITED' }
})

/**
 * Ensure the brand_registry table exists.
 * Called as a guard before any brand_registry query because the table may not
 * have been created yet if the migration has not run (e.g. in tests).
 */
function ensureTable() {
  getDb().prepare(`
    CREATE TABLE IF NOT EXISTS brand_registry (
      brand_name TEXT PRIMARY KEY,
      registered_at TEXT NOT NULL,
      protocol_version TEXT DEFAULT 'RTP-1'
    )
  `).run()
}

// Valid asset type values for the asset_emissions table constraint
const VALID_ASSET_TYPES = ['root', 'sub', 'unique'] as const
type AssetType = typeof VALID_ASSET_TYPES[number]

/**
 * GET /api/registry/brands
 * Public list of brands using RavenTag protocol, ordered by registration date ascending.
 * Returns brand name, registration date, and protocol version for each entry.
 */
router.get('/brands', (_req: Request, res: Response) => {
  ensureTable()
  const brands = getDb().prepare('SELECT brand_name as name, registered_at FROM brand_registry ORDER BY registered_at ASC').all()
  res.json({ brands, count: brands.length, protocol: 'RTP-1' })
})

/**
 * POST /api/registry/register
 * Registers the brand in the public RavenTag registry.
 * Requires admin key to prevent spam.
 *
 * Brand names are trimmed, must be 2-64 characters, and may only contain
 * alphanumeric characters, spaces, underscores, dots, and hyphens.
 * Duplicate registrations are silently ignored (INSERT OR IGNORE).
 */
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

/**
 * POST /api/registry/notify
 * Silent notification sent by RavenTag apps after on-chain asset emission.
 * Public endpoint, no auth required. Rate-limited to 5 req/min per IP.
 * Requires a valid txid that is verified to exist on the Ravencoin blockchain.
 *
 * This endpoint enables the public emissions registry to track asset issuance
 * without requiring the brand to run an admin call. The txid verification via
 * ElectrumX ensures only real, confirmed (or mempool) transactions are recorded,
 * preventing fabricated emission records.
 *
 * When a root asset is notified, it is also auto-registered in brand_registry
 * so the brand appears in the public brand list.
 *
 * Duplicate txid submissions are silently ignored (INSERT OR IGNORE on UNIQUE txid).
 */
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
  // Validate txid format: must be a 64-character hex string (SHA-256 reversed)
  if (!txid || typeof txid !== 'string' || !/^[0-9a-fA-F]{64}$/.test(txid)) {
    res.status(400).json({ error: 'txid must be a valid 64-char hex transaction id' })
    return
  }

  // Verify the txid actually exists on the Ravencoin blockchain before recording it.
  // This prevents the registry from being polluted with fictitious txids.
  const exists = await electrumXService.txExists(txid)
  if (!exists) {
    res.status(422).json({ error: 'txid not found on blockchain', code: 'TX_NOT_FOUND' })
    return
  }

  const name = asset_name.trim().toUpperCase()
  const now = issued_at || new Date().toISOString()
  const version = protocol_version || 'RTP-1'

  const db = getDb()
  // INSERT OR IGNORE: duplicate txid (same emission sent twice) is silently skipped
  db.prepare(`
    INSERT OR IGNORE INTO asset_emissions (asset_name, asset_type, txid, issued_at, protocol_version)
    VALUES (?, ?, ?, ?, ?)
  `).run(name, asset_type, txid, now, version)

  // Also register the brand (root part of the asset name) if not already present
  if (asset_type === 'root') {
    ensureTable()
    db.prepare(`
      INSERT OR IGNORE INTO brand_registry (brand_name, registered_at, protocol_version)
      VALUES (?, ?, ?)
    `).run(name, now, version)
  }

  res.json({ success: true })
})

/**
 * DELETE /api/registry/brands/:brandName
 * Remove a brand from the public registry.
 * Requires admin key. Returns 404 if the brand does not exist.
 */
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

/**
 * GET /api/registry/emissions
 * Public list of recorded on-chain emissions in reverse-chronological order.
 * Limited to the 200 most recent to prevent excessively large responses.
 * Returns asset_name, asset_type, txid, issued_at, and protocol_version for each entry.
 */
router.get('/emissions', (_req: Request, res: Response) => {
  const emissions = getDb()
    .prepare('SELECT asset_name, asset_type, txid, issued_at, protocol_version FROM asset_emissions ORDER BY issued_at DESC LIMIT 200')
    .all()
  res.json({ emissions, count: emissions.length })
})

export default router
