# Plan 50-01 Summary: Process-Level Error Handlers

**Status:** Complete
**Commit:** feat(50-01): add unhandledRejection and uncaughtException handlers

## What was built

Added `process.on('unhandledRejection')` and `process.on('uncaughtException')` handlers to `backend/src/index.ts`. The unhandled rejection handler closes the HTTP server then SQLite gracefully (with 5s forced exit fallback). The uncaught exception handler logs the stack trace and exits immediately since the process state is undefined. Server instance captured via `const server = app.listen(...)` to enable graceful shutdown. Imports `getDb` from the cache module for SQLite close.

## must_haves verification

- `unhandledRejection` handler calls `server.close()` then `process.exit(1)` ✓
- `uncaughtException` handler logs stack trace then `process.exit(1)` ✓
- Server instance captured in `const server = app.listen(...)` ✓
- Import `getDb` from cache module ✓

## Key files created/modified

- `backend/src/index.ts` — Added import, two process-level handlers, server variable capture

## Self-Check: PASSED
