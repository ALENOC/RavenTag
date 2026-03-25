/**
 * SQLite-backed cache, revocation store, counter store, and chip registry (cache.ts)
 *
 * This module provides a single SQLite database instance (via better-sqlite3)
 * shared by all backend modules. It offers several distinct subsystems:
 *
 *   1. Key-value cache: TTL-based caching for Ravencoin asset data and IPFS metadata,
 *      reducing repeated RPC and network calls.
 *
 *   2. Revocation store: Tracks revoked Ravencoin assets (by name). Supports optional
 *      on-chain burn records (irreversible revocation via the RVN burn address).
 *
 *   3. NFC counter store: Stores the last seen SUN read counter per nfc_pub_id to
 *      detect replay attacks. A counter is valid only if it is strictly greater than
 *      the previously stored value (HIGH-3 in the security review).
 *
 *   4. Chip registry: Maps a Ravencoin asset name to its physical NTAG 424 DNA UID
 *      and derived nfc_pub_id, enabling server-side SUN verification without
 *      exposing AES keys to the client.
 *
 * DB path is controlled by the DB_PATH environment variable (default: raventag.db
 * in the process working directory). WAL mode and foreign keys are enabled at init.
 */
import Database from 'better-sqlite3'
import path from 'path'
import { runMigrations } from './migrations.js'

const DB_PATH = process.env.DB_PATH ?? path.join(process.cwd(), 'raventag.db')

// Singleton database instance, lazily initialised on first call to getDb()
let db: Database.Database

/**
 * Return (or lazily create) the shared SQLite database instance.
 * - WAL mode improves concurrent read performance and crash safety.
 * - Foreign keys are enabled to maintain referential integrity.
 * - Migrations are run on first open to keep the schema up to date.
 */
export function getDb(): Database.Database {
  if (!db) {
    db = new Database(DB_PATH)
    db.pragma('journal_mode = WAL')
    db.pragma('foreign_keys = ON')
    runMigrations(db)
  }
  return db
}

/**
 * Check whether a given Ravencoin asset is currently revoked.
 * Asset names are normalised to uppercase before lookup.
 *
 * @returns An object with `revoked: false` if not revoked, or
 *          `{ revoked: true, reason, revokedAt }` if found in the revocation table.
 */
export function isAssetRevoked(assetName: string): { revoked: boolean; reason?: string; revokedAt?: number } {
  const database = getDb()
  const row = database
    .prepare('SELECT reason, revoked_at FROM revoked_assets WHERE asset_name = ?')
    .get(assetName.toUpperCase()) as { reason: string | null; revoked_at: number } | undefined
  if (!row) return { revoked: false }
  return { revoked: true, reason: row.reason ?? undefined, revokedAt: row.revoked_at }
}

/**
 * Mark a Ravencoin asset as revoked in the local database.
 * Soft revocation only: updates the database record, no on-chain action.
 * Invalidates any cached data for the asset so future requests see the updated state.
 *
 * @param assetName  Ravencoin asset name (automatically uppercased).
 * @param reason     Human-readable revocation reason (optional).
 * @param revokedBy  Label of the admin who performed the revocation (optional, for audit).
 */
export function revokeAsset(assetName: string, reason?: string, revokedBy?: string): void {
  const database = getDb()
  database.prepare(`
    INSERT OR REPLACE INTO revoked_assets (asset_name, reason, burned_on_chain, burn_txid, revoked_by)
    VALUES (?, ?, 0, NULL, ?)
  `).run(assetName.toUpperCase(), reason ?? null, revokedBy ?? null)
  // Invalidate all cache entries for this asset so the next read reflects revocation
  database.prepare('DELETE FROM cache WHERE key LIKE ?').run(`asset:${assetName.toUpperCase()}%`)
}

/**
 * Remove a Ravencoin asset from the revocation list (un-revoke).
 * The asset immediately returns to AUTHENTIC status on the next scan.
 *
 * @returns true if the asset was found and removed, false if it was not in the list.
 */
export function unrevokeAsset(assetName: string): boolean {
  const database = getDb()
  const result = database.prepare('DELETE FROM revoked_assets WHERE asset_name = ?').run(assetName.toUpperCase())
  return result.changes > 0
}

/**
 * Check if an NFC counter is fresh (strictly greater than the stored value).
 * Updates the stored counter if fresh. Returns false on replay (HIGH-3).
 *
 * The NTAG 424 DNA chip increments its read counter with every authenticated NFC tap.
 * By storing the last seen counter and rejecting any value that is not strictly
 * greater, we prevent replay attacks where an attacker re-uses a previously
 * captured SUN URL.
 *
 * @param nfcPubId  SHA-256 identifier of the NFC chip (hex string).
 * @param counter   The SUN read counter decoded from the encrypted payload.
 * @returns true if the counter is fresh and has been stored; false if replayed.
 */
export function checkAndUpdateCounter(nfcPubId: string, counter: number): boolean {
  const database = getDb()
  const row = database
    .prepare('SELECT last_counter FROM nfc_counters WHERE nfc_pub_id = ?')
    .get(nfcPubId) as { last_counter: number } | undefined
  if (row && counter <= row.last_counter) return false // replay detected
  database.prepare(
    'INSERT OR REPLACE INTO nfc_counters (nfc_pub_id, last_counter) VALUES (?, ?)'
  ).run(nfcPubId, counter)
  return true
}

/**
 * Return the full list of revoked assets in reverse-chronological order.
 * Includes reason and the Unix timestamp of revocation.
 */
export function listRevokedAssets(): Array<{
  asset_name: string; reason: string | null; burned_on_chain: number; burn_txid: string | null; revoked_at: number
}> {
  const database = getDb()
  return database.prepare('SELECT * FROM revoked_assets ORDER BY revoked_at DESC').all() as Array<{
    asset_name: string; reason: string | null; burned_on_chain: number; burn_txid: string | null; revoked_at: number
  }>
}

// TTL constants (in seconds) for the two main cache categories.
// Configurable via environment variables so operators can tune without code changes.
const ASSET_TTL = Number(process.env.CACHE_TTL_ASSET ?? 300)   // 5 minutes for Ravencoin asset data
const IPFS_TTL = Number(process.env.CACHE_TTL_IPFS ?? 3600)    // 1 hour for IPFS metadata (content-addressed, immutable)

/**
 * Retrieve a cached value by key.
 * Returns null if the key is not found or if the entry has expired.
 * Expired entries are eagerly deleted on access.
 *
 * @param key  Cache key string.
 * @returns The cached value (parsed from JSON) or null.
 */
export function cacheGet(key: string): unknown | null {
  const database = getDb()
  const row = database
    .prepare('SELECT value, expires FROM cache WHERE key = ?')
    .get(key) as { value: string; expires: number } | undefined

  if (!row) return null
  if (Date.now() > row.expires) {
    // Eagerly evict expired entries on read to keep the cache table lean
    database.prepare('DELETE FROM cache WHERE key = ?').run(key)
    return null
  }
  try {
    return JSON.parse(row.value)
  } catch {
    return null
  }
}

/**
 * Store a value in the cache with a TTL.
 * Uses INSERT OR REPLACE so existing keys are overwritten.
 * The expiry is stored as an absolute Unix-epoch millisecond timestamp.
 *
 * @param key         Cache key string.
 * @param value       Value to cache (will be JSON-serialised).
 * @param ttlSeconds  Time-to-live in seconds.
 */
export function cacheSet(key: string, value: unknown, ttlSeconds: number): void {
  const database = getDb()
  database
    .prepare('INSERT OR REPLACE INTO cache (key, value, expires) VALUES (?, ?, ?)')
    .run(key, JSON.stringify(value), Date.now() + ttlSeconds * 1000)
}

/**
 * Build the standard cache key for a Ravencoin asset query.
 * Format: "asset:<ASSET_NAME>" (uppercase).
 */
export function assetCacheKey(assetName: string): string {
  return `asset:${assetName}`
}

/**
 * Build the standard cache key for an IPFS URI lookup.
 * Format: "ipfs:<URI>".
 */
export function ipfsCacheKey(uri: string): string {
  return `ipfs:${uri}`
}

// ─── Chip Registry ────────────────────────────────────────────────────────────
// Maps Ravencoin asset names to physical NTAG 424 DNA chip UIDs and their
// derived nfc_pub_id values, enabling server-side SUN verification.

/**
 * Register a physical NFC chip UID against a Ravencoin asset.
 * Values are normalised: asset names uppercased, UIDs lowercased.
 *
 * @param assetName  Ravencoin asset name.
 * @param tagUid     7-byte NTAG 424 DNA UID (hex string).
 * @param nfcPubId   SHA-256(uid || BRAND_SALT) hex string.
 */
export function registerChip(assetName: string, tagUid: string, nfcPubId: string): void {
  const database = getDb()
  database.prepare(`
    INSERT OR REPLACE INTO chip_registry (asset_name, tag_uid, nfc_pub_id)
    VALUES (?, ?, ?)
  `).run(assetName.toUpperCase(), tagUid.toLowerCase(), nfcPubId.toLowerCase())
}

/**
 * Look up the registered chip for a given asset name.
 *
 * @param assetName  Ravencoin asset name (case-insensitive).
 * @returns The chip's tag_uid and nfc_pub_id, or null if not registered.
 */
export function getChipByAsset(assetName: string): { tag_uid: string; nfc_pub_id: string } | null {
  const database = getDb()
  const row = database
    .prepare('SELECT tag_uid, nfc_pub_id FROM chip_registry WHERE asset_name = ?')
    .get(assetName.toUpperCase()) as { tag_uid: string; nfc_pub_id: string } | undefined
  return row ?? null
}

/**
 * Remove a chip registration from the registry.
 *
 * @param assetName  Ravencoin asset name.
 * @returns true if a row was deleted, false if the asset was not registered.
 */
export function deleteChip(assetName: string): boolean {
  const database = getDb()
  const result = database.prepare('DELETE FROM chip_registry WHERE asset_name = ?').run(assetName.toUpperCase())
  return result.changes > 0
}

/**
 * Return all chip-to-asset registrations in reverse-chronological order.
 */
export function listChips(): Array<{ asset_name: string; tag_uid: string; nfc_pub_id: string; registered_at: number }> {
  const database = getDb()
  return database.prepare('SELECT * FROM chip_registry ORDER BY registered_at DESC').all() as Array<{
    asset_name: string; tag_uid: string; nfc_pub_id: string; registered_at: number
  }>
}

export { ASSET_TTL, IPFS_TTL }
