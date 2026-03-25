/**
 * Admin routes (routes/admin.ts)
 *
 * Provides admin-only endpoints for managing registered NFC tag -> asset mappings.
 * All routes in this file require the admin key (X-Admin-Key or X-Api-Key header).
 *
 * The `registered_tags` table is a legacy store that maps nfc_pub_id directly to
 * an asset name, along with optional brand_info and metadata_ipfs fields.
 * For brand chip programming (NTAG 424 DNA key derivation), see routes/brand.ts
 * and the chip_registry table, which also stores the raw tag UID.
 *
 * Routes:
 *   POST   /api/admin/register-tag     Register an NFC tag -> asset mapping
 *   GET    /api/admin/tags             List all registered tags
 *   DELETE /api/admin/tags/:nfcPubId   Deregister a tag
 */
import { Router, Request, Response } from 'express'
import { requireAdminKey } from '../middleware/auth.js'
import { getDb } from '../middleware/cache.js'
import { adminRegisterTagSchema } from '../utils/validation.js'

const router = Router()

// All admin routes require API key
router.use(requireAdminKey)

/**
 * POST /api/admin/register-tag
 * Register a new NFC tag -> Ravencoin asset mapping.
 *
 * The nfc_pub_id is the SHA-256(uid || salt) identifier from the RTP-1 protocol.
 * The client is responsible for computing nfc_pub_id before calling this endpoint;
 * the salt is never sent to the server here (unlike /api/brand/register-chip which
 * derives the nfc_pub_id server-side from the raw UID and BRAND_SALT env var).
 *
 * Returns 409 Conflict if the nfc_pub_id is already registered.
 */
router.post('/register-tag', (req: Request, res: Response) => {
  const parsed = adminRegisterTagSchema.safeParse(req.body)
  if (!parsed.success) {
    res.status(400).json({ error: 'Invalid request', details: parsed.error.issues, code: 'VALIDATION_ERROR' })
    return
  }

  const { asset_name, nfc_pub_id, brand_info, metadata_ipfs } = parsed.data
  const db = getDb()

  try {
    db.prepare(`
      INSERT INTO registered_tags (nfc_pub_id, asset_name, brand_info, metadata_ipfs)
      VALUES (?, ?, ?, ?)
    `).run(
      nfc_pub_id.toLowerCase(),
      asset_name,
      // brand_info is stored as a JSON string since SQLite has no native JSON column type
      brand_info ? JSON.stringify(brand_info) : null,
      metadata_ipfs ?? null
    )

    res.status(201).json({ success: true, nfc_pub_id, asset_name })
  } catch (err: unknown) {
    // SQLite UNIQUE constraint violation: the nfc_pub_id is already registered
    if (err instanceof Error && err.message.includes('UNIQUE')) {
      res.status(409).json({ error: 'Tag already registered', code: 'DUPLICATE' })
      return
    }
    res.status(500).json({ error: 'Database error', code: 'DB_ERROR' })
  }
})

/**
 * GET /api/admin/tags
 * List all registered tags in reverse-chronological order.
 * Returns the full registered_tags table contents.
 */
router.get('/tags', (req: Request, res: Response) => {
  const db = getDb()
  const tags = db.prepare('SELECT * FROM registered_tags ORDER BY created_at DESC').all()
  res.json({ tags, count: tags.length })
})

/**
 * DELETE /api/admin/tags/:nfcPubId
 * Deregister a tag by removing its entry from the registered_tags table.
 * The nfcPubId parameter is normalised to lowercase before the lookup.
 * Returns 404 if the tag was not found.
 */
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

export default router
