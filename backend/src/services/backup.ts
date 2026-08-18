/**
 * Optional in-process SQLite backup scheduler.
 *
 * The default Docker deployment uses the dedicated backup sidecar. If this
 * scheduler is explicitly enabled, callers must supply a dedicated backup
 * encryption-key file; the admin authentication key is never reused.
 */
import { execFileSync } from 'child_process'
import { unlinkSync, readdirSync } from 'fs'
import { getDb } from '../middleware/cache.js'

const BACKUP_INTERVAL_MS = 24 * 60 * 60 * 1000
const MAX_BACKUPS = 7
const BACKUP_DIR = process.env.BACKUP_DIR ?? '/backups'

export function startBackupScheduler(backupEncryptionKeyPath: string): NodeJS.Timeout {
  if (!backupEncryptionKeyPath) throw new Error('backup encryption key path is required')

  const runBackup = () => {
    try {
      const now = new Date()
      const timestamp = `${now.getFullYear()}-${String(now.getMonth() + 1).padStart(2, '0')}-${String(now.getDate()).padStart(2, '0')}_${String(now.getHours()).padStart(2, '0')}-${String(now.getMinutes()).padStart(2, '0')}`
      const tmpFile = `${BACKUP_DIR}/raventag_${timestamp}.db.tmp`
      const encFile = `${BACKUP_DIR}/raventag_${timestamp}.db.enc`

      const source = getDb()
      source.backup(tmpFile).then(() => {
        try {
          execFileSync('openssl', [
            'enc', '-aes-256-cbc', '-pbkdf2', '-iter', '100000',
            '-pass', `file:${backupEncryptionKeyPath}`,
            '-in', tmpFile,
            '-out', encFile
          ], { timeout: 60000, stdio: 'ignore' })

          unlinkSync(tmpFile)
          const files = readdirSync(BACKUP_DIR)
            .filter(f => f.startsWith('raventag_') && f.endsWith('.db.enc'))
            .sort()
          while (files.length > MAX_BACKUPS) {
            unlinkSync(`${BACKUP_DIR}/${files.shift()!}`)
          }
          console.log(`[Backup] Created: ${encFile}`)
        } catch (err) {
          try { unlinkSync(tmpFile) } catch { /* already removed */ }
          console.error('[Backup] Encrypt/prune failed:', err)
        }
      }).catch((err: unknown) => console.error('[Backup] .backup() failed:', err))
    } catch (err) {
      console.error('[Backup] Failed:', err)
    }
  }

  setTimeout(runBackup, 30000)
  return setInterval(runBackup, BACKUP_INTERVAL_MS)
}
