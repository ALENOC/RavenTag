# Phase 50: Backend Stability - Discussion Log

> **Audit trail only.** Do not use as input to planning, research, or execution agents.
> Decisions are captured in CONTEXT.md — this log preserves the alternatives considered.

**Date:** 2026-04-25
**Phase:** 50-backend-stability
**Areas discussed:** Error resilience, Asset hierarchy parallelism, listassets pagination, Cleanup strategy, SQLite backup, CLI database explorer

---

## Error Resilience

| Option | Description | Selected |
|--------|-------------|----------|
| Graceful shutdown | Log error, close server+DB, process.exit(1), Docker restarts | ✓ |
| Immediate exit | Log error, process.exit(1) immediately | |
| Log and continue | Log but keep running (unstable state risk) | |

| Option | Description | Selected |
|--------|-------------|----------|
| Keep plain text | console.error as-is, no dependency changes | ✓ |
| Switch to JSON | pino or similar structured logging | |

**User's choice:** Graceful shutdown + plain-text logging.

---

## Asset Hierarchy Parallelism

| Option | Description | Selected |
|--------|-------------|----------|
| Promise.allSettled (partial) | Failed branches get partial flag + errors, successes return data | ✓ |
| Promise.all (fail-fast) | Any failure fails entire request | |

| Option | Description | Selected |
|--------|-------------|----------|
| Limited concurrency | Cap at 5-10 concurrent RPC calls, chunked batching | ✓ |
| Unlimited parallel | All sub-assets fire at once | |

**User's choice:** allSettled with partial results + limited concurrency.

---

## listassets Pagination

| Option | Description | Selected |
|--------|-------------|----------|
| Additive params | Optional ?limit=N&offset=M, default 200, backward-compatible | ✓ |
| Document only | Keep API as-is, document 200 cap | |

| Option | Description | Selected |
|--------|-------------|----------|
| Envelope with metadata | {assets: [...], total, limit, offset, hasMore} | ✓ |
| Link header pagination | RFC 5988 Link header for next page | |

**User's choice:** Additive params + envelope metadata.

---

## Cleanup Strategy

| Option | Description | Selected |
|--------|-------------|----------|
| setInterval in Node.js | Cleanup on startup + every 24h | ✓ |
| SQLite trigger | DELETE old rows on INSERT | |

| Option | Description | Selected |
|--------|-------------|----------|
| 30d logs / never counters | request_logs: 30 days, nfc_counters: never (anti-replay) | ✓ |
| 90d logs / keep counters | Longer audit trail | |

**User's choice:** setInterval, 30d for request_logs, nfc_counters NEVER cleaned (NTAG DNA anti-replay).

**User note:** "nfc_counters non deve essere resettato sono legati al processo di verifica dei TAG NTAG DNA"

---

## SQLite Backup

| Option | Description | Selected |
|--------|-------------|----------|
| better-sqlite3 .backup() API | Consistent snapshot under WAL, no child process | ✓ |
| sqlite3 CLI command | Shell out to sqlite3, needs binary in container | |

| Option | Description | Selected |
|--------|-------------|----------|
| Every 6h, keep 3 | 18h rotating window, low disk usage | ✓ |
| Every 1h, keep 24 | Better granularity, more disk | |
| Every 24h, keep 7 | Minimal overhead, week of history | |

**User's choice:** better-sqlite3 .backup() API, every 6h, keep 3.

---

## CLI Database Explorer

| Option | Description | Selected |
|--------|-------------|----------|
| Read-only REPL | .assets, .brands, .revoked, .stats commands | ✓ |
| Single-run JSON | npm run db:list -- --table=assets, stdout JSON | |

**User's choice:** Read-only REPL via `npm run db:explore`.

---

## Claude's Discretion

- Exact concurrency limit value (5 vs 10)
- Backup retention pruning implementation
- REPL command implementation (readline vs repl module)
- Backup container update in docker-compose.yml
- Error message wording in partial hierarchy responses

## Deferred Ideas

- Structured logging (pino) migration
- Test suite for backend
- Horizontal scaling / multi-instance
- nfc_counters TTL cleanup — explicitly rejected
- registered_tags → chip_registry migration
- ensureTable() removal in registry routes

---

## User Constraints (Session Notes)

- "l'aggiornamento del backend non deve assolutamente toccare il database esistente"
- "l'aggiornamento deve essere retrocompatibile con le app di versione precedente"
- "il database esistente e' permanente non va assolutamente eliminato"
- "il backend dopo l'aggiornamento rimane completamente compatibile anche con il frontend"
- "nfc_counters non deve essere resettato sono legati al processo di verifica dei TAG NTAG DNA"
