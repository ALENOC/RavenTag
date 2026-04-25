# Phase 50: Backend Stability - Research

**Researched:** 2026-04-25
**Status:** Research complete

## 1. unhandledRejection + uncaughtException Handler

### Current State
- `backend/src/index.ts:225` has Express error middleware — catches sync errors in route handlers only
- No `process.on('unhandledRejection')` or `process.on('uncaughtException')` anywhere in codebase
- Unhandled promise rejections currently crash the process with `UnhandledPromiseRejectionWarning` (Node 20)
- Docker `restart: unless-stopped` brings it back, but without graceful cleanup

### Implementation Approach
Insert handlers in `index.ts` BEFORE `app.listen()` (line 230), after all middleware mounting:

```
process.on('unhandledRejection', (reason, promise) => {
  console.error('[FATAL] Unhandled Rejection:', reason)
  // graceful shutdown: close HTTP server, close SQLite
  server.close(() => process.exit(1))
})

process.on('uncaughtException', (err) => {
  console.error('[FATAL] Uncaught Exception:', err.message)
  console.error(err.stack)
  process.exit(1)
})
```

Key decision: `unhandledRejection` attempts graceful shutdown (close HTTP + SQLite). `uncaughtException` exits immediately — the process is in an undefined state.

### Integration
- Must capture the server instance from `app.listen()` to close it
- Must export or access the SQLite db instance from cache.ts to close it safely
- `getDb()` returns singleton — can call `getDb().close()` after server stops

### Concerns
- Express error handler at line 225 should remain as-is (catches sync route errors)
- The process-level handlers are a safety net, not a replacement

## 2. Parallel Asset Hierarchy Fetching

### Current State
- `ravencoin.ts:220-232` — `getAssetHierarchy` uses sequential `for` loop
- Each iteration calls `listSubAssets(sub)` which makes 2 parallel RPC calls internally
- N sub-assets = N serial iterations = N*2 total RPC calls, sequential per sub-asset
- No error isolation: one failed sub-asset RPC breaks the entire hierarchy response

### Implementation Approach

Replace sequential `for` with chunked `Promise.allSettled`:

```typescript
async getAssetHierarchy(parentAsset: string): Promise<AssetHierarchy> {
  const subAssets = await this.listSubAssets(parentAsset)
  const variants: Record<string, string[]> = {}
  const errors: Array<{assetName: string, error: string}> = []

  // Chunk sub-assets into batches of 5 to limit concurrent RPC calls
  const CONCURRENCY = 5
  for (let i = 0; i < subAssets.length; i += CONCURRENCY) {
    const chunk = subAssets.slice(i, i + CONCURRENCY)
    const results = await Promise.allSettled(
      chunk.map(sub => this.listSubAssets(sub))
    )
    results.forEach((result, idx) => {
      if (result.status === 'fulfilled' && result.value.length > 0) {
        variants[chunk[idx]] = result.value
      } else if (result.status === 'rejected') {
        errors.push({ assetName: chunk[idx], error: (result.reason as Error).message })
      }
    })
  }

  const response: AssetHierarchy & { partial?: boolean; errors?: Array<{assetName: string; error: string}> } = {
    parent: parentAsset,
    subAssets,
    variants
  }
  if (errors.length > 0) {
    response.partial = true
    response.errors = errors
  }
  return response
}
```

### Concurrency Limit
- D-04 says 5-10. Choose 5 (conservative — protects Ravencoin RPC node from overload)
- Chunked batching: split array into chunks of 5, await `Promise.allSettled` per chunk sequentially
- This avoids firing 200 concurrent RPC calls while still parallelizing within each chunk

### Response Shape
- Add `partial: true` and `errors: [{assetName, error}]` when any sub-branch fails
- Existing clients ignore unknown fields (backward-compatible per C-02)
- `AssetHierarchy` interface needs optional `partial` and `errors` fields

## 3. listassets Pagination

### Current State
- `listSubAssets` hardcodes count=200
- Hierarchy endpoint `GET /api/assets/:name/hierarchy` has no pagination params
- Brands with >200 sub-assets get silently truncated results

### Implementation Approach

Add optional `?limit=N&offset=M` to the hierarchy route:

```typescript
// assets.ts hierarchy route
router.get('/:assetName/hierarchy', async (req, res) => {
  const limit = Math.min(Number(req.query.limit) || 200, 1000)
  const offset = Number(req.query.offset) || 0
  // ... pass to service
})
```

Modify `listSubAssets` to accept optional limit/offset:
```typescript
async listSubAssets(parentAsset: string, limit = 200, offset = 0): Promise<string[]> {
  const [subs, uniques] = await Promise.all([
    this.call<string[]>('listassets', [`${parentAsset}/*`, false, limit, offset]),
    this.call<string[]>('listassets', [`${parentAsset}/#*`, false, limit, offset])
  ])
  return [...(subs ?? []), ...(uniques ?? [])]
}
```

Response envelope per D-06:
```json
{
  "parent": "BRAND",
  "subAssets": ["BRAND/SUB1", ...],
  "variants": {...},
  "total": 450,
  "limit": 200,
  "offset": 0,
  "hasMore": true
}
```

### Default Behavior
- Omitting `?limit` and `?offset` defaults to limit=200, offset=0 — same as current
- Full backward compatibility: existing clients see same response shape plus new metadata fields

## 4. request_logs Periodic Cleanup

### Current State
- Migration 6 (`migrations.ts:132-140`) does one-shot DELETE of logs older than 30 days
- No runtime cleanup — `request_logs` grows unboundedly
- Table shares WAL file with revocation and counter tables

### Implementation Approach

Add cleanup function in `logger.ts` (or new `cleanup.ts`):

```typescript
export function startLogCleanup() {
  const CLEANUP_INTERVAL = 24 * 60 * 60 * 1000 // 24h
  const RETENTION_SECONDS = 30 * 24 * 60 * 60  // 30 days

  const cleanup = () => {
    try {
      const db = getDb()
      const threshold = Math.floor(Date.now() / 1000) - RETENTION_SECONDS
      const r1 = db.prepare('DELETE FROM request_logs WHERE created_at < ?').run(threshold)
      const r2 = db.prepare('DELETE FROM rate_limit_events WHERE created_at < ?').run(threshold)
      if (r1.changes > 0 || r2.changes > 0) {
        console.log(`[Cleanup] Removed ${r1.changes} request_logs rows, ${r2.changes} rate_limit_events rows`)
      }
    } catch (err) {
      console.error('[Cleanup] Failed:', err)
    }
  }

  cleanup() // run once at startup
  return setInterval(cleanup, CLEANUP_INTERVAL)
}
```

Call `startLogCleanup()` from `index.ts` after server starts.

### nfc_counters — EXPLICITLY EXCLUDED
- D-08 and C-03 mandate nfc_counters is NEVER cleaned
- This is the NTAG 424 DNA anti-replay mechanism
- Removing counters would allow tag replay attacks
- Comment must be present in cleanup code explaining WHY nfc_counters is excluded

## 5. SQLite Backup via .backup() API

### Current State
- Docker backup container uses `openssl enc` directly on `/data/raventag.db` file
- Raw file copy under WAL mode can produce inconsistent backups (WAL file not included)
- `better-sqlite3` v9.4.3 has `.backup()` method — synchronous, safe under concurrent WAL writes
- No in-process Node.js backup exists

### Implementation Approach

**Part A: In-process Node.js backup** (new file `backend/src/services/backup.ts`):

```typescript
import Database from 'better-sqlite3'
import { execSync } from 'child_process'
import { getDb } from '../middleware/cache.js'

const BACKUP_INTERVAL = 6 * 60 * 60 * 1000 // 6h
const MAX_BACKUPS = 3
const BACKUP_DIR = process.env.BACKUP_DIR ?? '/backups'

export function startBackupScheduler(adminKeyPath = '/run/secrets/admin_key') {
  const runBackup = () => {
    try {
      const timestamp = new Date().toISOString().replace(/[:.]/g, '-').slice(0, 19)
      const tmpFile = `${BACKUP_DIR}/raventag_${timestamp}.db.tmp`
      const encFile = `${BACKUP_DIR}/raventag_${timestamp}.db.enc`

      // Step 1: Use better-sqlite3 .backup() for consistent snapshot
      const source = getDb()
      const backupDb = new Database(tmpFile)
      source.backup(backupDb)
      backupDb.close()

      // Step 2: Encrypt with openssl (preserve existing pattern)
      execSync(`openssl enc -aes-256-cbc -pbkdf2 -iter 100000 -pass file:${adminKeyPath} -in ${tmpFile} -out ${encFile}`, {
        timeout: 60000
      })

      // Step 3: Remove unencrypted temp file
      require('fs').unlinkSync(tmpFile)

      // Step 4: Prune old backups (keep last 3)
      const files = require('fs').readdirSync(BACKUP_DIR)
        .filter((f: string) => f.startsWith('raventag_') && f.endsWith('.db.enc'))
        .sort()
      while (files.length > MAX_BACKUPS) {
        require('fs').unlinkSync(`${BACKUP_DIR}/${files.shift()}`)
      }

      console.log(`[Backup] Created: ${encFile}`)
    } catch (err) {
      console.error('[Backup] Failed:', err)
    }
  }

  runBackup() // first backup at startup
  return setInterval(runBackup, BACKUP_INTERVAL)
}
```

**Part B: Docker backup container update** (docker-compose.yml line 47-67):
Replace raw `openssl enc -in /data/raventag.db` with `sqlite3` CLI `.backup` command:

```yaml
command: >
  sh -c "apk add --no-cache openssl sqlite > /dev/null 2>&1;
    while true; do
      TIMESTAMP=$$(date +%Y%m%d_%H%M%S);
      sqlite3 /data/raventag.db \".backup /tmp/raventag_snap.db\";
      openssl enc -aes-256-cbc -pbkdf2 -iter 100000 \
        -pass file:/run/secrets/admin_key \
        -in /tmp/raventag_snap.db \
        -out /backups/raventag_$${TIMESTAMP}.db.enc 2>/dev/null \
        && echo \"[Backup] raventag_$${TIMESTAMP}.db.enc (encrypted)\";
      rm -f /tmp/raventag_snap.db;
      ls -t /backups/raventag_*.db.enc 2>/dev/null | tail -n +4 | xargs rm -f;
      sleep 21600;
    done"
```

Changes from current:
- Add `sqlite` package (provides `sqlite3` CLI)
- Use `sqlite3 .backup` command before `openssl enc` (consistent WAL snapshot)
- Backup interval: 86400s → 21600s (every 6h per D-10)
- Retention: keep 7 → keep 3 (per D-10, `tail -n +4`)
- Clean up temp snapshot after encryption

### Key Insight
The `better-sqlite3` `.backup()` method is synchronous and blocks the event loop during backup. For typical RavenTag database sizes (a few MB), this takes <100ms — acceptable. If DB grows large, could use `backupDb.backup(source, { progress: true })` for incremental approach, but not needed now.

## 6. CLI Database Explorer

### Current State
- No CLI tools exist for DB exploration
- `package.json` scripts: dev, build, start, lint only

### Implementation Approach

New file `backend/src/db-explore.ts`:

```typescript
import Database from 'better-sqlite3'
import * as readline from 'readline'
import * as path from 'path'

const DB_PATH = process.env.DB_PATH ?? path.join(process.cwd(), 'raventag.db')

// Open read-only — CRITICAL per D-13 and C-01
const db = new Database(DB_PATH, { readonly: true })

const commands: Record<string, () => void> = {
  '.assets': () => {
    const rows = db.prepare('SELECT asset_name, tag_uid, nfc_pub_id, registered_at FROM chip_registry ORDER BY registered_at DESC').all()
    console.table(rows)
  },
  '.brands': () => {
    const rows = db.prepare('SELECT brand_name, registered_at, protocol_version FROM brand_registry ORDER BY registered_at DESC').all()
    console.table(rows)
  },
  '.revoked': () => {
    const rows = db.prepare('SELECT asset_name, reason, revoked_at FROM revoked_assets ORDER BY revoked_at DESC').all()
    console.table(rows)
  },
  '.stats': () => {
    const tables = ['cache', 'chip_registry', 'revoked_assets', 'nfc_counters', 'request_logs', 'rate_limit_events', 'brand_registry', 'asset_emissions']
    console.log('Table row counts:')
    for (const t of tables) {
      const { n } = db.prepare(`SELECT COUNT(*) as n FROM ${t}`).get() as { n: number }
      console.log(`  ${t}: ${n}`)
    }
  },
  '.help': () => {
    console.log('Commands: .assets  .brands  .revoked  .stats  .help  .exit')
  }
}

const rl = readline.createInterface({ input: process.stdin, output: process.stdout })
rl.setPrompt('db> ')
rl.prompt()

rl.on('line', (line) => {
  const cmd = line.trim()
  if (cmd === '.exit') { rl.close(); return }
  if (commands[cmd]) { commands[cmd]() }
  else if (cmd) { console.log(`Unknown: ${cmd}. Type .help`) }
  rl.prompt()
}).on('close', () => {
  db.close()
  process.exit(0)
})
```

Add to `package.json` scripts:
```json
"db:explore": "tsx src/db-explore.ts"
```

### Constraints
- Read-only mode (`readonly: true`) — prevents accidental writes to permanent DB
- Uses `console.table` for readable output
- Pre-built commands avoid raw SQL access (safety)
- `.exit` closes DB connection cleanly

## 7. Validation Architecture

### What Must Be Verified
1. **unhandledRejection**: Process does not crash on unhandled promise rejection — handler logs and exits gracefully
2. **Parallel hierarchy**: Concurrent sub-asset fetches complete faster than sequential. Partial failures return partial data.
3. **Pagination**: Hierarchy endpoint accepts limit/offset. Response includes metadata envelope. Omitting params preserves default behavior.
4. **Cleanup**: request_logs older than 30 days are deleted. nfc_counters is untouched.
5. **Backup**: `.backup()` API produces consistent snapshot. Docker backup container uses `sqlite3` CLI.
6. **CLI**: `npm run db:explore` opens read-only REPL. Pre-built commands work.

### Nyquist Dimensions
- **Dimension 1 (Goal achievement):** Backend is stable — no unhandled rejection crashes, hierarchy responses are fast, logs don't grow unbounded, backups are safe
- **Dimension 2 (Requirement coverage):** All 5 requirements from ROADMAP.md addressed
- **Dimension 3 (Context fidelity):** All 13 D-0x decisions and 3 C-0x constraints honored
- **Dimension 4 (Code correctness):** TypeScript compiles, existing API responses unchanged for default params
- **Dimension 5 (Backward compatibility):** All API changes are additive. Existing Android + frontend clients unaffected.
- **Dimension 6 (Security):** nfc_counters preserved (anti-replay). Backup encrypted. CLI read-only.
- **Dimension 7 (Edge cases):** Empty sub-assets list, RPC failure on partial branches, zero logs to clean, backup dir missing
- **Dimension 8 (Should-not regressions):** Existing search, verify, revocation endpoints unchanged

---

*Research complete. Ready for planning.*
