/**
 * SQLite migration system (migrations.ts)
 *
 * Implements a simple forward-only migration runner for the RavenTag SQLite database.
 * Migrations are plain SQL strings indexed by a monotonically increasing integer ID.
 * They are tracked in a `schema_migrations` table: once applied, a migration is never
 * re-applied or rolled back.
 *
 * Each migration is executed inside a transaction so that a partial failure leaves
 * the schema and the migrations tracking table in a consistent state.
 *
 * To add a new schema change, append a new entry to the MIGRATIONS array with a
 * unique sequential ID. Never modify or delete an existing migration.
 *
 * Tables managed:
 *   - cache               Key-value TTL cache (Ravencoin asset data, IPFS metadata)
 *   - registered_tags     Legacy nfc_pub_id -> asset_name mapping (admin API)
 *   - revoked_assets      Asset revocation records
 *   - nfc_counters        Per-chip read counter for replay-attack detection
 *   - chip_registry       Physical UID -> asset_name mapping (brand chip programming)
 *   - brand_registry      Public brand directory
 *   - request_logs        HTTP request log for metrics
 *   - rate_limit_events   Rate-limit hit log for abuse monitoring
 *   - asset_emissions     On-chain asset emission notifications (registry)
 *   - schema_migrations   Migration tracking table (created here)
 */
import Database from 'better-sqlite3'

interface Migration {
  id: number
  name: string
  sql: string
}

const MIGRATIONS: Migration[] = [
  {
    id: 1,
    name: 'initial_schema',
    sql: `
      CREATE TABLE IF NOT EXISTS cache (
        key     TEXT PRIMARY KEY,
        value   TEXT NOT NULL,
        expires INTEGER NOT NULL
      );

      CREATE TABLE IF NOT EXISTS registered_tags (
        nfc_pub_id    TEXT PRIMARY KEY,
        asset_name    TEXT NOT NULL,
        brand_info    TEXT,
        metadata_ipfs TEXT,
        created_at    INTEGER NOT NULL DEFAULT (unixepoch())
      );

      CREATE TABLE IF NOT EXISTS revoked_assets (
        asset_name      TEXT PRIMARY KEY,
        reason          TEXT,
        burned_on_chain INTEGER NOT NULL DEFAULT 0,
        burn_txid       TEXT,
        revoked_by      TEXT,
        revoked_at      INTEGER NOT NULL DEFAULT (unixepoch())
      );

      CREATE TABLE IF NOT EXISTS nfc_counters (
        nfc_pub_id   TEXT PRIMARY KEY,
        last_counter INTEGER NOT NULL,
        updated_at   INTEGER NOT NULL DEFAULT (unixepoch())
      );

      CREATE TABLE IF NOT EXISTS chip_registry (
        asset_name    TEXT PRIMARY KEY,
        tag_uid       TEXT NOT NULL,
        nfc_pub_id    TEXT NOT NULL,
        registered_at INTEGER NOT NULL DEFAULT (unixepoch())
      );
    `
  },
  {
    id: 2,
    name: 'brand_registry',
    sql: `
      CREATE TABLE IF NOT EXISTS brand_registry (
        brand_name       TEXT PRIMARY KEY,
        registered_at    TEXT NOT NULL,
        protocol_version TEXT DEFAULT 'RTP-1'
      );
    `
  },
  {
    id: 3,
    name: 'request_logs',
    // Indexes on created_at and status enable efficient time-windowed metric queries
    sql: `
      CREATE TABLE IF NOT EXISTS request_logs (
        id          INTEGER PRIMARY KEY AUTOINCREMENT,
        method      TEXT NOT NULL,
        path        TEXT NOT NULL,
        status      INTEGER NOT NULL,
        duration_ms INTEGER NOT NULL,
        ip          TEXT,
        created_at  INTEGER NOT NULL DEFAULT (unixepoch())
      );
      CREATE INDEX IF NOT EXISTS idx_request_logs_created_at ON request_logs(created_at);
      CREATE INDEX IF NOT EXISTS idx_request_logs_status ON request_logs(status);
    `
  },
  {
    id: 4,
    name: 'rate_limit_metrics',
    // Index on created_at enables efficient time-windowed abuse queries
    sql: `
      CREATE TABLE IF NOT EXISTS rate_limit_events (
        id         INTEGER PRIMARY KEY AUTOINCREMENT,
        ip         TEXT NOT NULL,
        path       TEXT NOT NULL,
        created_at INTEGER NOT NULL DEFAULT (unixepoch())
      );
      CREATE INDEX IF NOT EXISTS idx_rate_limit_created_at ON rate_limit_events(created_at);
    `
  },
  {
    id: 5,
    name: 'add_indexes',
    // Secondary indexes to speed up common query patterns used in revocation
    // checks, chip lookups, and counter freshness queries
    sql: `
      CREATE INDEX IF NOT EXISTS idx_revoked_assets_revoked_at ON revoked_assets(revoked_at);
      CREATE INDEX IF NOT EXISTS idx_chip_registry_nfc_pub_id ON chip_registry(nfc_pub_id);
      CREATE INDEX IF NOT EXISTS idx_nfc_counters_updated_at ON nfc_counters(updated_at);
    `
  },
  {
    id: 6,
    name: 'log_retention_cleanup',
    // Delete log entries older than 30 days to prevent unbounded growth.
    // This migration runs once at schema upgrade time; ongoing cleanup would
    // require a periodic job or SQLite triggers.
    sql: `
      DELETE FROM request_logs WHERE created_at < unixepoch() - 30 * 86400;
      DELETE FROM rate_limit_events WHERE created_at < unixepoch() - 30 * 86400;
    `
  },
  {
    id: 7,
    name: 'asset_emissions',
    // Tracks on-chain asset emission notifications sent by RavenTag apps.
    // asset_type is constrained to the three RTP-1 token tiers.
    // txid is UNIQUE to silently deduplicate double-submitted notifications.
    sql: `
      CREATE TABLE IF NOT EXISTS asset_emissions (
        id               INTEGER PRIMARY KEY AUTOINCREMENT,
        asset_name       TEXT NOT NULL,
        asset_type       TEXT NOT NULL CHECK(asset_type IN ('root','sub','unique')),
        txid             TEXT NOT NULL UNIQUE,
        issued_at        TEXT NOT NULL,
        protocol_version TEXT NOT NULL DEFAULT 'RTP-1'
      );
      CREATE INDEX IF NOT EXISTS idx_asset_emissions_asset_name ON asset_emissions(asset_name);
      CREATE INDEX IF NOT EXISTS idx_asset_emissions_issued_at ON asset_emissions(issued_at);
    `
  }
]

/**
 * Run all pending migrations on the given database instance.
 * Creates `schema_migrations` table if it doesn't exist.
 *
 * Each pending migration is executed inside a transaction: the SQL is applied
 * and the migration ID is recorded atomically. If the SQL fails, the transaction
 * is rolled back and the migration is not marked as applied.
 *
 * @param database  An open better-sqlite3 Database instance.
 */
export function runMigrations(database: Database.Database): void {
  // Create migrations tracking table if this is a fresh database
  database.exec(`
    CREATE TABLE IF NOT EXISTS schema_migrations (
      id         INTEGER PRIMARY KEY,
      name       TEXT NOT NULL,
      applied_at INTEGER NOT NULL DEFAULT (unixepoch())
    );
  `)

  // Load the set of already-applied migration IDs for O(1) membership checks
  const applied = new Set<number>(
    (database.prepare('SELECT id FROM schema_migrations').all() as { id: number }[]).map(r => r.id)
  )

  const insertMigration = database.prepare(
    'INSERT INTO schema_migrations (id, name) VALUES (?, ?)'
  )

  let count = 0
  for (const migration of MIGRATIONS) {
    if (applied.has(migration.id)) continue

    // Wrap each migration in a transaction so schema change and tracking record
    // are either both committed or both rolled back
    database.transaction(() => {
      database.exec(migration.sql)
      insertMigration.run(migration.id, migration.name)
    })()

    console.log(`[DB] Migration ${migration.id} applied: ${migration.name}`)
    count++
  }

  if (count === 0) {
    console.log(`[DB] Schema up to date (${MIGRATIONS.length} migrations).`)
  } else {
    console.log(`[DB] Applied ${count} migration(s). Schema version: ${MIGRATIONS[MIGRATIONS.length - 1].id}`)
  }
}

/**
 * Return the current schema version (the ID of the last applied migration).
 * Returns 0 if the schema_migrations table does not exist yet.
 *
 * @param database  An open better-sqlite3 Database instance.
 */
export function getSchemaVersion(database: Database.Database): number {
  try {
    const row = database.prepare('SELECT MAX(id) as version FROM schema_migrations').get() as { version: number | null }
    return row?.version ?? 0
  } catch {
    return 0
  }
}
