/**
 * Authentication middleware (auth.ts)
 *
 * Provides Express middleware for admin and operator key verification.
 *
 * Security design:
 *   - Keys are read from environment variables (ADMIN_KEY / ADMIN_API_KEY, OPERATOR_KEY).
 *   - Accepted request headers: X-Admin-Key, X-Api-Key, X-Operator-Key.
 *   - Comparisons use timingSafeEqual directly to prevent timing attacks
 *     (where an attacker can detect a correct prefix by measuring response time).
 *     Keys of different lengths are rejected immediately (no timing attack risk
 *     since key length is not a secret: all keys are generated as fixed-length hex strings).
 *
 * Authorization tiers:
 *   - Admin key: full access to all operations (issue, revoke, transfer, derive keys).
 *   - Operator key: limited access (register chips, list chips, check revocation, issue unique tokens).
 */
import { Request, Response, NextFunction } from 'express'
import { timingSafeEqual } from 'crypto'

/**
 * Constant-time string comparison using timingSafeEqual.
 * Returns false immediately if the strings have different byte lengths
 * (key length is not a secret; all RavenTag keys are fixed-length hex strings).
 */
function safeCompare(a: string, b: string): boolean {
  const aBuf = Buffer.from(a)
  const bBuf = Buffer.from(b)
  if (aBuf.byteLength !== bBuf.byteLength) return false
  return timingSafeEqual(aBuf, bBuf)
}

/**
 * Middleware: require admin key in X-Admin-Key or X-Api-Key header.
 * Reads from ADMIN_KEY env (falls back to ADMIN_API_KEY for backwards compat).
 * Uses constant-time comparison to prevent timing attacks.
 */
export function requireAdminKey(req: Request, res: Response, next: NextFunction): void {
  const adminKey = process.env.ADMIN_KEY ?? process.env.ADMIN_API_KEY
  if (!adminKey) {
    // Server misconfiguration: admin API is not usable without a configured key
    res.status(503).json({ error: 'Admin API not configured', code: 'ADMIN_NOT_CONFIGURED' })
    return
  }
  const provided = req.headers['x-admin-key'] ?? req.headers['x-api-key']
  if (!provided || typeof provided !== 'string') {
    res.status(401).json({ error: 'Invalid or missing admin key', code: 'UNAUTHORIZED' })
    return
  }
  if (!safeCompare(adminKey, provided)) {
    res.status(401).json({ error: 'Invalid or missing admin key', code: 'UNAUTHORIZED' })
    return
  }
  next()
}

/**
 * Middleware: require operator key OR admin key.
 *
 * Operators can: register chips, list chips, check revocation status.
 * Operators CANNOT: issue assets, revoke, burn, transfer.
 *
 * Set OPERATOR_KEY env var. If not set, only ADMIN_KEY is accepted.
 *
 * Accepts any of these headers (checked in order):
 *   X-Admin-Key, X-Api-Key, X-Operator-Key
 */
export function requireOperatorKey(req: Request, res: Response, next: NextFunction): void {
  const adminKey = process.env.ADMIN_KEY ?? process.env.ADMIN_API_KEY
  const operatorKey = process.env.OPERATOR_KEY

  const provided = req.headers['x-admin-key'] ?? req.headers['x-api-key'] ?? req.headers['x-operator-key']
  if (!provided || typeof provided !== 'string') {
    res.status(401).json({ error: 'Missing operator or admin key', code: 'UNAUTHORIZED' })
    return
  }

  const matchesAdmin = adminKey && safeCompare(adminKey, provided)
  const matchesOperator = operatorKey && safeCompare(operatorKey, provided)

  if (!matchesAdmin && !matchesOperator) {
    res.status(401).json({ error: 'Invalid operator or admin key', code: 'UNAUTHORIZED' })
    return
  }
  next()
}
