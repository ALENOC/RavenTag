# Plan 50-06 Summary: Read-Only CLI Database Explorer

**Status:** Complete
**Commit:** feat(50-06): add read-only CLI database explorer

## What was built

Created `backend/src/db-explore.ts` — a read-only REPL for exploring the RavenTag SQLite database. Launched via `npm run db:explore`. Opens the database with `readonly: true`. Pre-built commands: `.assets` (chip_registry), `.brands` (brand_registry), `.revoked` (revoked_assets), `.stats` (row counts for all tables), `.help` (command list), `.exit` (close database and exit). Added `"db:explore": "tsx src/db-explore.ts"` to package.json scripts.

## must_haves verification

- Database opened in read-only mode (`readonly: true`) ✓
- Pre-built commands: `.assets`, `.brands`, `.revoked`, `.stats`, `.help` ✓
- No INSERT, UPDATE, DELETE, DROP exposed ✓
- Clean exit via `.exit` (closes DB connection) ✓
- Database never altered or deleted by the CLI ✓

## Key files created/modified

- `backend/src/db-explore.ts` — NEW: read-only database explorer REPL
- `backend/package.json` — Added `db:explore` script

## Self-Check: PASSED
