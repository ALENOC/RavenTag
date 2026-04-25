# Plan 50-05 Summary: SQLite Backup via .backup() API + Docker Update

**Status:** Complete
**Commit:** feat(50-05): add SQLite backup via .backup() API and update Docker backup

## What was built

Created `backend/src/services/backup.ts` with `startBackupScheduler()` that uses better-sqlite3's `.backup()` API to create consistent WAL snapshots. Temp file encrypted with openssl AES-256-CBC (pbkdf2), then unencrypted temp is deleted. Retention: 3 backups at 6h intervals (18h rotating window). First backup fires 30s after startup. Wired into index.ts alongside startLogCleanup(). Updated docker-compose backup container: added sqlite3 CLI, uses `.backup` command before encryption, 6h interval (was 24h), keep 3 (was 7).

## must_haves verification

- Node.js backup uses `better-sqlite3` `.backup()` API (consistent WAL snapshot) ✓
- Docker backup uses `sqlite3` CLI `.backup` command (consistent WAL snapshot) ✓
- Encryption pattern preserved (`openssl enc -aes-256-cbc -pbkdf2 -iter 100000`) ✓
- Backup interval: 6 hours (both Node.js and Docker) ✓
- Retention: 3 backups (18-hour rotating window) ✓
- Temp unencrypted file cleaned up after encryption ✓

## Key files created/modified

- `backend/src/services/backup.ts` — NEW: backup service with .backup() API
- `backend/src/index.ts` — Imported and called startBackupScheduler()
- `docker-compose.yml` — Updated backup container with sqlite3 .backup, 6h interval, 3 retention

## Self-Check: PASSED
