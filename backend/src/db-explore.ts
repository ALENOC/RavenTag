/**
 * Database Explorer (db-explore.ts)
 *
 * Read-only REPL for exploring the RavenTag SQLite database.
 * Launched via `npm run db:explore`.
 *
 * SECURITY: Opens the database in read-only mode. No write operations
 * are exposed. The database is permanent and must never be altered
 * by tooling (C-01).
 */
import Database from 'better-sqlite3'
import * as readline from 'readline'
import * as path from 'path'

const DB_PATH = process.env.DB_PATH ?? path.join(process.cwd(), 'raventag.db')

console.log(`Opening database (read-only): ${DB_PATH}`)
const db = new Database(DB_PATH, { readonly: true })

const commands: Record<string, () => void> = {
  '.assets': () => {
    const rows = db.prepare(
      'SELECT asset_name, tag_uid, nfc_pub_id, datetime(registered_at, \'unixepoch\') as registered FROM chip_registry ORDER BY registered_at DESC'
    ).all()
    if (rows.length === 0) { console.log('No registered chips.'); return }
    console.table(rows)
  },
  '.brands': () => {
    const rows = db.prepare(
      'SELECT brand_name, registered_at, protocol_version FROM brand_registry ORDER BY registered_at DESC'
    ).all()
    if (rows.length === 0) { console.log('No registered brands.'); return }
    console.table(rows)
  },
  '.revoked': () => {
    const rows = db.prepare(
      'SELECT asset_name, reason, datetime(revoked_at, \'unixepoch\') as revoked FROM revoked_assets ORDER BY revoked_at DESC'
    ).all()
    if (rows.length === 0) { console.log('No revoked assets.'); return }
    console.table(rows)
  },
  '.stats': () => {
    const tables = [
      'cache', 'chip_registry', 'revoked_assets', 'nfc_counters',
      'request_logs', 'rate_limit_events', 'brand_registry', 'asset_emissions'
    ]
    console.log('Table row counts:')
    for (const t of tables) {
      try {
        const row = db.prepare(`SELECT COUNT(*) as n FROM ${t}`).get() as { n: number }
        console.log(`  ${t}: ${row.n}`)
      } catch {
        console.log(`  ${t}: (table not found)`)
      }
    }
  },
  '.help': () => {
    console.log('')
    console.log('Available commands:')
    console.log('  .assets   List registered chips (chip_registry)')
    console.log('  .brands   List registered brands (brand_registry)')
    console.log('  .revoked  List revoked assets (revoked_assets)')
    console.log('  .stats    Show row counts for all tables')
    console.log('  .help     Show this help')
    console.log('  .exit     Close database and exit')
    console.log('')
  }
}

console.log('RavenTag Database Explorer (read-only)')
console.log('Type .help for available commands.')

const rl = readline.createInterface({
  input: process.stdin,
  output: process.stdout
})
rl.setPrompt('db> ')
rl.prompt()

rl.on('line', (line: string) => {
  const cmd = line.trim()
  if (cmd === '.exit' || cmd === '.quit') {
    rl.close()
    return
  }
  if (commands[cmd]) {
    commands[cmd]()
  } else if (cmd) {
    console.log(`Unknown command: ${cmd}`)
    console.log('Type .help for available commands.')
  }
  rl.prompt()
}).on('close', () => {
  db.close()
  console.log('Database closed.')
  process.exit(0)
})
