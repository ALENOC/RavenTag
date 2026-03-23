/**
 * Asset routes (routes/assets.ts)
 *
 * Public read-only endpoints for querying Ravencoin asset data and RTP-1 metadata.
 * These routes are rate-limited at 60 req/min (general API limiter).
 *
 * All asset data comes from the Ravencoin RPC node (via ravencoinService) and IPFS
 * (via fetchIpfsMetadata). Results are cached in SQLite to avoid hammering the RPC
 * node or IPFS gateway on repeated lookups:
 *   - Asset data: ASSET_TTL seconds (default 300s / 5 minutes)
 *   - IPFS metadata: IPFS_TTL seconds (default 3600s / 1 hour, content-addressed so immutable)
 *   - Search results: 60 seconds (short to avoid amplification DoS via unique queries)
 *
 * Routes:
 *   GET /api/assets/search?q=<query>            Search assets by name pattern
 *   GET /api/assets/:assetName                  Get asset info + RTP-1 metadata
 *   GET /api/assets/:assetName/verify-nfc       Verify an nfc_pub_id against an asset
 *   GET /api/assets/:assetName/revocation       Public revocation status check
 *   GET /api/assets/:assetName/hierarchy        Full sub-asset tree
 */
import { Router, Request, Response } from 'express'
import { ravencoinService } from '../services/ravencoin.js'
import { fetchIpfsMetadata } from '../services/ipfs.js'
import { cacheGet, cacheSet, assetCacheKey, ipfsCacheKey, ASSET_TTL, IPFS_TTL, isAssetRevoked } from '../middleware/cache.js'
// search cache TTL defined inline (60s) to avoid amplification DoS
import { assetNameSchema, assetNameWithUniqueSchema } from '../utils/validation.js'

const router = Router()

/**
 * GET /api/assets/search?q=<query>
 * Search Ravencoin assets by name pattern.
 *
 * The query is uppercased and wrapped in wildcards before being passed to the
 * Ravencoin 'listassets' RPC. A minimum length of 3 prevents overly broad
 * searches that could return thousands of results or cause node slowdowns.
 *
 * Results are cached for 60 seconds per unique query string.
 */
router.get('/search', async (req: Request, res: Response) => {
  const q = req.query['q']
  if (typeof q !== 'string' || q.length < 3) {
    res.status(400).json({ error: 'Query must be at least 3 characters', code: 'BAD_REQUEST' })
    return
  }

  // Cache key uses the uppercased query for case-insensitive deduplication
  const cacheKey = `search:${q.toUpperCase()}`
  const cached = cacheGet(cacheKey)
  if (cached) {
    res.json({ ...(cached as object), cached: true })
    return
  }

  try {
    const assets = await ravencoinService.searchAssets(q.toUpperCase())
    cacheSet(cacheKey, { assets, count: assets.length }, 60) // 60s TTL for search results
    res.json({ assets, count: assets.length })
  } catch (err: unknown) {
    console.error('[assets/search]', err)
    res.status(502).json({ error: 'Service temporarily unavailable', code: 'NODE_ERROR' })
  }
})

/**
 * GET /api/assets/:assetName
 * Get asset info and RTP-1 metadata.
 *
 * Fetches raw asset data from the Ravencoin node, then attempts to fetch and
 * parse RTP-1 metadata from IPFS if an ipfs_hash is present on the asset.
 * Both asset data and IPFS metadata are individually cached.
 *
 * Returns 404 if the asset does not exist on Ravencoin.
 */
router.get('/:assetName', async (req: Request, res: Response) => {
  const assetName = req.params.assetName.toUpperCase()
  const parsed = assetNameSchema.safeParse(assetName)
  if (!parsed.success) {
    res.status(400).json({ error: 'Invalid asset name', code: 'INVALID_ASSET_NAME' })
    return
  }

  const cacheKey = assetCacheKey(assetName)
  const cached = cacheGet(cacheKey)
  if (cached) {
    res.json({ ...cached as object, cached: true })
    return
  }

  try {
    // Wrap the IPFS fetcher with local caching so repeated lookups for the same
    // IPFS URI don't result in repeated network requests
    const ipfsFetcher = async (uri: string) => {
      const iKey = ipfsCacheKey(uri)
      const ic = cacheGet(iKey)
      if (ic) return ic
      const data = await fetchIpfsMetadata(uri)
      cacheSet(iKey, data, IPFS_TTL)
      return data
    }

    const result = await ravencoinService.getAssetWithMetadata(assetName, ipfsFetcher)
    if (!result) {
      res.status(404).json({ error: 'Asset not found', code: 'NOT_FOUND' })
      return
    }

    cacheSet(cacheKey, result, ASSET_TTL)
    res.json({ ...result, cached: false })
  } catch (err: unknown) {
    console.error('[assets/:name]', err)
    res.status(502).json({ error: 'Service temporarily unavailable', code: 'NODE_ERROR' })
  }
})

/**
 * GET /api/assets/:assetName/verify-nfc?pub_id=<hex>
 * Verify if an nfc_pub_id matches this asset's on-chain metadata.
 *
 * nfc_pub_id is the SHA-256(tag_uid || salt) identifier stored in the RTP-1
 * metadata JSON on IPFS. Comparison is case-insensitive hex.
 *
 * This endpoint does NOT verify the SUN MAC (that requires the AES key).
 * It only checks whether the identifier embedded in the IPFS metadata matches
 * the provided value, confirming the asset was created for this specific chip.
 *
 * The pub_id parameter must be a 64-character hex string (SHA-256 = 32 bytes).
 */
router.get('/:assetName/verify-nfc', async (req: Request, res: Response) => {
  const assetName = req.params.assetName.toUpperCase()
  const pubId = req.query['pub_id']

  if (typeof pubId !== 'string' || !/^[0-9a-fA-F]{64}$/.test(pubId)) {
    res.status(400).json({ error: 'pub_id must be 64-char hex SHA-256', code: 'BAD_REQUEST' })
    return
  }

  try {
    const ipfsFetcher = async (uri: string) => {
      const data = await fetchIpfsMetadata(uri)
      return data
    }

    const result = await ravencoinService.getAssetWithMetadata(assetName, ipfsFetcher)
    if (!result) {
      res.status(404).json({ error: 'Asset not found', code: 'NOT_FOUND' })
      return
    }

    // Case-insensitive comparison: both sides lowercased for safety
    const match = result.metadata?.nfc_pub_id?.toLowerCase() === pubId.toLowerCase()
    res.json({
      match,
      asset: result.asset,
      raventag_version: result.metadata?.raventag_version ?? null
    })
  } catch (err: unknown) {
    console.error('[assets/:name/verify-nfc]', err)
    res.status(502).json({ error: 'Service temporarily unavailable', code: 'NODE_ERROR' })
  }
})

/**
 * GET /api/assets/:assetName/revocation
 * Public endpoint to check the revocation status of an asset.
 *
 * Only exposes the boolean `revoked` flag, not the reason or timestamp.
 * Full revocation details (reason, burned_on_chain, etc.) are accessible
 * to admins via GET /api/brand/revoked/:assetName.
 *
 * This endpoint is intentionally unauthenticated so that anyone (end consumers,
 * third-party verifiers) can check whether an asset has been revoked.
 */
router.get('/:assetName/revocation', (req: Request, res: Response) => {
  const { assetName } = req.params
  const parsed = assetNameWithUniqueSchema.safeParse(assetName)
  if (!parsed.success) {
    res.status(400).json({ error: 'Invalid asset name', code: 'INVALID_ASSET_NAME' })
    return
  }
  const status = isAssetRevoked(assetName)
  // Expose only revoked flag publicly, not reason/revokedAt (admin-only via /api/brand/revoked)
  res.json({
    asset_name: assetName.toUpperCase(),
    revoked: status.revoked
  })
})

/**
 * GET /api/assets/:assetName/hierarchy
 * Get the full sub-asset tree for a root asset.
 *
 * Returns the parent asset name, all direct sub-assets (PARENT/CHILD),
 * and all variants (sub-assets of sub-assets: PARENT/CHILD/VARIANT and
 * PARENT/CHILD#TAG unique tokens).
 *
 * This is useful for brand dashboards to enumerate all tokens under a root.
 */
router.get('/:assetName/hierarchy', async (req: Request, res: Response) => {
  const assetName = req.params.assetName.toUpperCase()
  const parsed = assetNameSchema.safeParse(assetName)
  if (!parsed.success) {
    res.status(400).json({ error: 'Invalid asset name', code: 'INVALID_ASSET_NAME' })
    return
  }

  try {
    const hierarchy = await ravencoinService.getAssetHierarchy(assetName)
    res.json(hierarchy)
  } catch (err: unknown) {
    console.error('[assets/:name/hierarchy]', err)
    res.status(502).json({ error: 'Service temporarily unavailable', code: 'NODE_ERROR' })
  }
})

export default router
