# Plan 50-04 Summary: Periodic request_logs Cleanup

**Status:** Complete
**Commit:** feat(50-04): add periodic cleanup of request_logs and rate_limit_events

## What was built

Added `startLogCleanup()` function to logger.ts that deletes rows older than 30 days from `request_logs` and `rate_limit_events` tables. Runs once at startup (to catch accumulated logs since last restart) then every 24h via setInterval. `nfc_counters` table is explicitly and intentionally excluded with a documented security reason (NTAG 424 DNA anti-replay mechanism). Wired into index.ts via import and call inside the listen callback. Renamed Migration 6 to `log_retention_cleanup_one_shot` with a comment referencing the new periodic cleanup.

## must_haves verification

- setInterval every 24h deleting rows older than 30 days ✓
- Runs once at startup before interval begins ✓
- `request_logs` and `rate_limit_events` cleaned; `nfc_counters` NEVER touched ✓
- Comment documents WHY nfc_counters is excluded (anti-replay security) ✓

## Key files created/modified

- `backend/src/middleware/logger.ts` — Added startLogCleanup() export
- `backend/src/index.ts` — Imported and called startLogCleanup() at startup
- `backend/src/middleware/migrations.ts` — Renamed migration 6, added cross-reference comment

## Self-Check: PASSED
