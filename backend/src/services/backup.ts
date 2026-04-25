/**
 * SQLite backup service (backup.ts)
 *
 * Creates consistent database snapshots using better-sqlite3's .backup() API,
 * which is safe under WAL mode concurrent writes. Encrypts output with openssl
 * (preserving the existing encryption pattern from docker-compose.yml).
 *
 * Retention: keeps last 3 backups (18-hour rotating window at 6h intervals).
 */
import { execSync } from 'child_process'
import { unlinkSync, readdirSync } from 'fs'
import { getDb } from '../middleware/cache.js'

const BACKUP_INTERVAL_MS = 6 * 60 * 60 * 1000 // 6 hours
const MAX_BACKUPS = 3
const BACKUP_DIR = process.env.BACKUP_DIR ?? '/backups'

export function startBackupScheduler(adminKeyPath = '/run/secrets/admin_key'): NodeJS.Timeout {
  const runBackup = () => {
    try {
      const now = new Date()
      const timestamp = `${now.getFullYear()}-${String(now.getMonth() + 1).padStart(2, '0')}-${String(now.getDate()).padStart(2, '0')}_${String(now.getHours()).padStart(2, '0')}-${String(now.getMinutes()).padStart(2, '0')}`
      const tmpFile = `${BACKUP_DIR}/raventag_${timestamp}.db.tmp`
      const encFile = `${BACKUP_DIR}/raventag_${timestamp}.db.enc`

      // Step 1: Use better-sqlite3 .backup() for a consistent WAL snapshot
      const source = getDb()
      source.backup(tmpFile).then(() => {
        try {
          // Step 2: Encrypt with openssl (same pattern as docker-compose backup)
          execSync(
            `openssl enc -aes-256-cbc -pbkdf2 -iter 100000 -pass file:${adminKeyPath} -in ${tmpFile} -out ${encFile}`,
            { timeout: 60000 }
          )

          // Step 3: Remove unencrypted temp file
          unlinkSync(tmpFile)

          // Step 4: Prune old backups (keep last MAX_BACKUPS)
          const files = readdirSync(BACKUP_DIR)
            .filter(f => f.startsWith('raventag_') && f.endsWith('.db.enc'))
            .sort()
          while (files.length > MAX_BACKUPS) {
            const oldFile = files.shift()!
            unlinkSync(`${BACKUP_DIR}/${oldFile}`)
          }

          console.log(`[Backup] Created: ${encFile}`)
        } catch (err) {
          console.error('[Backup] Encrypt/prune failed:', err)
        }
      }).catch((err: unknown) => {
        console.error('[Backup] .backup() failed:', err)
      })
    } catch (err) {
      console.error('[Backup] Failed:', err)
    }
  }

  // First backup 30s after startup (let DB init complete)
  setTimeout(runBackup, 30000)
  return setInterval(runBackup, BACKUP_INTERVAL_MS)
}
