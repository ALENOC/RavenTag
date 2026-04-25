# Phase 50: Backend Stability - Context

**Gathered:** 2026-04-25
**Status:** Ready for planning

<domain>
## Phase Boundary

Fix operational reliability issues in the Node.js/Express backend. Five targeted fixes: unhandledRejection handler, parallel asset hierarchy fetching, listassets pagination, request_logs cleanup, and safe SQLite backup via .backup() API. Also add a read-only CLI tool for database exploration.

Hard constraints: the existing SQLite database is permanent and must never be deleted or altered incompatibly. All API changes must be backward-compatible with existing Android app versions (consumer + brand) and the Next.js frontend.

Out of scope: nfc_counters modification (anti-replay, tied to NTAG DNA verification), structured logging migration (pino), test suite creation, horizontal scaling.
</domain>

<decisions>
## Implementation Decisions

### Error Resilience
- **D-01:** Add `process.on('unhandledRejection', ...)` and `process.on('uncaughtException', ...)` handlers. On crash: log error, attempt graceful shutdown (close HTTP server, close SQLite connection), then `process.exit(1)`. Docker restarts cleanly.
- **D-02:** Keep plain-text `console.error` logging. No migration to structured JSON logging.

### Asset Hierarchy Parallelism
- **D-03:** Replace sequential `for` loop in `getAssetHierarchy` with `Promise.allSettled()`. Failed sub-asset branches return a `partial: true` flag and `errors: [...]` array in the response. Successful branches still return data.
- **D-04:** Limit concurrent sub-asset RPC calls to 5-10 via a simple semaphore or chunked batching. Prevents flooding the Ravencoin RPC node.

### listassets Pagination
- **D-05:** Add optional `?limit=N&offset=M` query params. Default limit remains 200 (current behavior). Omitting params preserves existing behavior — fully backward-compatible.
- **D-06:** Response format: envelope with metadata. `{assets: [...], total: N, limit: N, offset: N, hasMore: boolean}`. Existing clients ignore unknown fields.

### Cleanup Strategy
- **D-07:** `request_logs` cleanup via `setInterval` every 24h in the Node.js process. Retention: 30 days. Run once at startup too.
- **D-08:** `nfc_counters` table: NEVER cleaned up. It is the anti-replay mechanism for NTAG 424 DNA tag verification. Removing counters would allow tag replay attacks.

### SQLite Backup
- **D-09:** Replace raw file copy in backup flow with `better-sqlite3` `.backup()` API. This produces a consistent snapshot under WAL mode, safe under concurrent writes.
- **D-10:** Backup frequency: every 6 hours. Retention: keep last 3 backups (18-hour rotating window). Encrypt output with `openssl enc` (preserve existing encryption pattern).
- **D-11:** Update `docker-compose.yml` backup container to use `sqlite3` CLI `.backup` command (the container doesn't have Node.js). The in-process Node.js backup (D-09) handles runtime; the compose backup is an additional safety layer.

### CLI Database Explorer
- **D-12:** Add `npm run db:explore` script. Opens a read-only REPL with pre-built commands: `.assets` (list registered assets), `.brands` (list brands), `.revoked` (list revoked assets), `.stats` (table row counts). Uses `better-sqlite3` in read-only mode to protect the permanent database.
- **D-13:** CLI tool is read-only — no write operations, no schema changes. The database is permanent and must never be altered or deleted by tooling.

### Critical Constraints
- **C-01:** Existing SQLite database is permanent. No migration may delete or alter existing tables in a backward-incompatible way. New columns must be additive (ALTER TABLE ADD COLUMN with DEFAULT).
- **C-02:** All API changes must be backward-compatible with existing Android app versions (consumer + brand flavors) and the Next.js frontend. Response shapes may add fields but never remove or rename existing ones.
- **C-03:** `nfc_counters` table is part of the NTAG DNA verification anti-replay mechanism. It must never be truncated, cleaned, or altered.

### Claude's Discretion
- Exact concurrency limit value (5 vs 10)
- Backup retention pruning implementation
- REPL command implementation details (readline vs repl module)
- Backup container update approach in docker-compose.yml
- Error message wording in partial hierarchy responses
</decisions>

<canonical_refs>
## Canonical References

**Downstream agents MUST read these before planning or implementing.**

### Backend Entry Points
- `backend/src/index.ts` — Express app setup, server start, middleware mounting. Unhandled rejection handler goes here. Trust proxy setting at line 63.
- `backend/src/services/ravencoin.ts` §186-189 — listassets with 200 cap
- `backend/src/services/ravencoin.ts` §220-232 — Sequential getAssetHierarchy loop (N+1 calls)
- `backend/src/services/electrumx.ts` §124-205 — ElectrumX client, TOFU cert cache, idCounter
- `backend/src/middleware/logger.ts` §37-62 — Request logging, IP extraction, log insertion
- `backend/src/middleware/cache.ts` §74-119 — Revocation functions, nfc_counters management
- `backend/src/middleware/migrations.ts` §133-140 — Migration 6: request_logs cleanup (one-shot, needs periodic replacement)

### Deployment
- `docker-compose.yml` §39-54 — Backup container with raw file copy (needs .backup() replacement)

### Database
- `backend/src/middleware/migrations.ts` — All schema migrations. Must remain additive (C-01).

### Project Context
- `.planning/PROJECT.md` — Active requirements list, constraints, key decisions
- `.planning/codebase/ARCHITECTURE.md` — SQLite schema overview, data flows
- `.planning/codebase/CONCERNS.md` — Detailed analysis of all 5 issues being fixed

### Prior Phase Context
- `.planning/phases/40-asset-emission-ux/40-CONTEXT.md` — D-07 retryWithBackoff pattern, D-08 timeout handling (relevant for RPC call patterns)
</canonical_refs>

<code_context>
## Existing Code Insights

### Reusable Assets
- `better-sqlite3` already installed and configured. `.backup()` method available without new dependencies.
- `backend/src/index.ts` middleware pattern: all middleware mounted before routes. Same pattern for error handlers.
- Existing `setInterval`-based patterns: none yet in backend, but standard Node.js.

### Established Patterns
- `console.error('[tag]', err)` logging with prefix tags throughout all route files.
- Express error handler at `index.ts:225` catches synchronous route errors.
- SQLite WAL mode enabled — `.backup()` API is safe under concurrent reads/writes.
- Backend migrations run at startup before routes mount (index.ts pattern).

### Integration Points
- `backend/src/index.ts` — Process-level handlers mount here, before server.listen()
- `backend/src/services/ravencoin.ts` — getAssetHierarchy and listassets modifications
- `backend/src/middleware/logger.ts` — Request logging INSERT (cleanup targets this)
- `docker-compose.yml` — Backup container command update
- `backend/package.json` — Add `db:explore` script

### Concerns
- `nfc_counters` is critical anti-replay infrastructure — any cleanup here breaks NTAG DNA verification security.
- The backup container in docker-compose uses a raw `openssl enc` pipe. While the in-process .backup() is the primary fix, the compose backup should also be updated for defense-in-depth.
- No existing Node.js process-level periodic tasks — setInterval for cleanup will be the first background timer in the app.
</code_context>

<specifics>
## Specific Ideas

- Error handler should log the promise reason / error stack trace before shutdown, so the cause is visible in docker logs.
- Backup files named `raventag_YYYY-MM-DD_HH-MM.db.enc` — same naming convention as current, just safe content.
- CLI REPL inspired by `sqlite3` interactive mode but with domain-specific commands rather than raw SQL.
- Concurrency limit via simple chunked batching: split sub-asset list into chunks of 5, await Promise.allSettled per chunk sequentially.
- Partial hierarchy response includes an `errors` array with `{assetName, error}` so the brand dashboard can display which sub-assets failed to load.
</specifics>

<deferred>
## Deferred Ideas

- Structured logging (pino) migration — discussed and deferred. Keep plain-text for now.
- Test suite for backend — valuable but separate scope.
- Horizontal scaling / multi-instance — out of scope for single-instance self-hosted deployment.
- nfc_counters TTL-based cleanup — explicitly rejected. Anti-replay must be permanent.
- `registered_tags` to `chip_registry` migration — separate technical debt phase.
- `ensureTable()` removal in registry routes — separate cleanup phase.

### Reviewed Todos (not folded)
None — no pending todos matched Phase 50.
</deferred>

---

*Phase: 50-backend-stability*
*Context gathered: 2026-04-25*
