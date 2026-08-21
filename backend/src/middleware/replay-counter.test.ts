/**
 * RT108-SEC-201 regression: the NFC replay counter must be usable as the FIRST
 * database-touching operation of the process. Previously it dereferenced the
 * lazily-assigned module-level `db` binding directly, so a public verify
 * request right after a restart crashed with TypeError → unhandledRejection →
 * process exit (unauthenticated remote DoS).
 *
 * DB_PATH is set before the dynamic import so the module under test opens a
 * fresh temp database, and checkAndUpdateCounter is the first call made.
 */
import { test } from 'node:test'
import assert from 'node:assert'
import { mkdtempSync, rmSync } from 'node:fs'
import { tmpdir } from 'node:os'
import path from 'node:path'

process.env.DB_PATH = path.join(mkdtempSync(path.join(tmpdir(), 'raventag-sec201-')), 'test.db')

type CacheModule = typeof import('./cache.js')
let cache: CacheModule | undefined
async function loadCache(): Promise<CacheModule> {
  cache ??= await import('./cache.js')
  return cache
}

test('checkAndUpdateCounter works as the first DB operation of the process', async () => {
  const { checkAndUpdateCounter } = await loadCache()
  // Must not throw TypeError on a not-yet-initialized module-level binding.
  assert.equal(checkAndUpdateCounter('a'.repeat(64), 1), true)
  assert.equal(checkAndUpdateCounter('a'.repeat(64), 1), false, 'replay of same counter rejected')
  assert.equal(checkAndUpdateCounter('a'.repeat(64), 2), true, 'higher counter accepted')
  assert.equal(checkAndUpdateCounter('a'.repeat(64), 1), false, 'lower counter rejected')
  assert.equal(checkAndUpdateCounter('b'.repeat(64), 5), true, 'independent chip state')
})

test('invalid counters are rejected without touching the DB', async () => {
  const { checkAndUpdateCounter } = await loadCache()
  assert.equal(checkAndUpdateCounter('c'.repeat(64), -1), false)
  assert.equal(checkAndUpdateCounter('c'.repeat(64), 1.5), false)
  assert.equal(checkAndUpdateCounter('c'.repeat(64), Number.MAX_SAFE_INTEGER + 1), false)
})

test('migration 8 provisions the persistent ElectrumX TOFU pin table', async () => {
  const { getDb } = await loadCache()
  const db = getDb()
  const tables = db
    .prepare("SELECT name FROM sqlite_master WHERE type='table' AND name='electrum_tofu_pins'")
    .all() as { name: string }[]
  assert.equal(tables.length, 1)
  db.prepare('INSERT OR REPLACE INTO electrum_tofu_pins (host, fingerprint) VALUES (?, ?)')
    .run('example.test', 'f'.repeat(64))
  const row = db.prepare('SELECT fingerprint FROM electrum_tofu_pins WHERE host = ?')
    .get('example.test') as { fingerprint: string }
  assert.equal(row.fingerprint, 'f'.repeat(64))
})

// Cleanup temp DB directory (WAL files included) on process exit.
process.on('exit', () => {
  try { rmSync(path.dirname(process.env.DB_PATH as string), { recursive: true, force: true }) } catch { /* best effort */ }
})
