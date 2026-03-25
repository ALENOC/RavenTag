/**
 * HTTP request logger middleware (logger.ts)
 *
 * Provides three exports:
 *   - requestLogger: Express middleware that logs each request to console (with ANSI
 *     color coding by status code) and persists it to the SQLite request_logs table.
 *   - logRateLimitEvent: Persists a rate-limit hit to the rate_limit_events table.
 *   - getRequestStats: Aggregates request metrics for the last N hours (used by
 *     the /api/metrics endpoint).
 *
 * Persistence is best-effort: if the DB is not yet initialised, log writes are silently
 * skipped so the logger never causes a request to fail.
 *
 * The /health and /favicon.ico paths are intentionally excluded to avoid log spam.
 */
import { Request, Response, NextFunction } from 'express'
import { getDb } from './cache.js'

// Paths that should not generate log entries (health probe and browser favicon requests)
const SKIP_PATHS = new Set(['/health', '/favicon.ico'])

/**
 * Express middleware that logs HTTP requests.
 *
 * Logging is performed in the 'finish' event (after the response headers are sent)
 * so that the final status code is available. This avoids logging before the
 * response is complete.
 *
 * Console format: [ISO-timestamp] METHOD /path STATUS duration_ms IP
 * Colors: green for 2xx, yellow for 4xx, red for 5xx.
 */
export function requestLogger(req: Request, res: Response, next: NextFunction): void {
  if (SKIP_PATHS.has(req.path)) return next()

  const start = Date.now()
  // Prefer X-Forwarded-For header (set by reverse proxies) over the raw socket address
  const ip = (req.headers['x-forwarded-for'] as string)?.split(',')[0].trim()
    ?? req.socket.remoteAddress
    ?? 'unknown'

  res.on('finish', () => {
    const duration = Date.now() - start
    const method = req.method
    const path = req.path
    const status = res.statusCode

    // Console log: color-coded by status
    const statusColor = status >= 500 ? '\x1b[31m' : status >= 400 ? '\x1b[33m' : '\x1b[32m'
    const reset = '\x1b[0m'
    console.log(
      `[${new Date().toISOString()}] ${method} ${path} ${statusColor}${status}${reset} ${duration}ms ${ip}`
    )

    // Persist to DB for metrics (non-blocking, best-effort)
    // Wrapped in try/catch so a DB failure never propagates to the request lifecycle
    try {
      getDb()
        .prepare('INSERT INTO request_logs (method, path, status, duration_ms, ip) VALUES (?, ?, ?, ?, ?)')
        .run(method, path, status, duration, ip)
    } catch {
      // DB not yet initialized or table missing , ignore
    }
  })

  next()
}

/**
 * Persist a rate-limit event to the DB for monitoring.
 * Called by the rate-limit handler in index.ts when a request is rejected.
 * Non-fatal: errors are silently ignored.
 *
 * @param ip    Client IP address (from X-Forwarded-For or socket).
 * @param path  Request path that was rate-limited.
 */
export function logRateLimitEvent(ip: string, path: string): void {
  try {
    getDb()
      .prepare('INSERT INTO rate_limit_events (ip, path) VALUES (?, ?)')
      .run(ip, path)
  } catch {
    // ignore
  }
}

/**
 * Aggregate request statistics for the last N hours.
 * Used by the /api/metrics admin endpoint.
 *
 * Queries the request_logs and rate_limit_events tables using a Unix-epoch
 * threshold (created_at is stored as an integer by SQLite's unixepoch()).
 *
 * @param hours  Look-back window in hours (default: 24).
 * @returns Object with total_requests, error_requests, rate_limited, avg_duration_ms,
 *          top_paths (top 10 by hit count), and period_hours.
 */
export function getRequestStats(hours = 24): object {
  const db = getDb()
  // Calculate the Unix timestamp threshold for the look-back window
  const since = Math.floor(Date.now() / 1000) - hours * 3600

  const total = (db.prepare('SELECT COUNT(*) as n FROM request_logs WHERE created_at > ?').get(since) as { n: number }).n
  const errors = (db.prepare('SELECT COUNT(*) as n FROM request_logs WHERE created_at > ? AND status >= 400').get(since) as { n: number }).n
  const rateLimited = (db.prepare('SELECT COUNT(*) as n FROM rate_limit_events WHERE created_at > ?').get(since) as { n: number }).n

  // Top 10 most-requested paths, useful for identifying abuse or high-traffic endpoints
  const topPaths = db.prepare(`
    SELECT path, COUNT(*) as n FROM request_logs
    WHERE created_at > ? GROUP BY path ORDER BY n DESC LIMIT 10
  `).all(since) as { path: string; n: number }[]

  const avgDuration = (db.prepare(
    'SELECT AVG(duration_ms) as avg FROM request_logs WHERE created_at > ?'
  ).get(since) as { avg: number | null }).avg ?? 0

  return {
    period_hours: hours,
    total_requests: total,
    error_requests: errors,
    rate_limited: rateLimited,
    avg_duration_ms: Math.round(avgDuration),
    top_paths: topPaths
  }
}
