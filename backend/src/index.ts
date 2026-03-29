/**
 * RavenTag API - Entry point (index.ts)
 *
 * Bootstraps the Express application for the RavenTag backend.
 * Responsibilities:
 *   - Load secrets from Docker secret files (_FILE convention)
 *   - Enforce mandatory env-var safety checks in production
 *   - Configure security middleware (Helmet, CORS, rate limiting)
 *   - Mount all API route groups under /api/*
 *   - Mount all API route groups under /api/*
 *
 * Protocol: RTP-1 (RavenTag Protocol v1)
 */
import 'dotenv/config'
import { readFileSync } from 'fs'
import express from 'express'
import helmet from 'helmet'
import cors from 'cors'
import { rateLimit } from 'express-rate-limit'
import assetsRouter from './routes/assets.js'
import verifyRouter from './routes/verify.js'
import adminRouter from './routes/admin.js'
import brandRouter from './routes/brand.js'
import registryRouter from './routes/registry.js'
import { requestLogger, logRateLimitEvent, getRequestStats } from './middleware/logger.js'
import { requireAdminKey } from './middleware/auth.js'

// ── CRIT-2: Docker secrets support (_FILE convention) ────────────────────────
// If ADMIN_KEY_FILE (etc.) is set, read the secret from that file.
// This allows Docker secrets to be mounted as files instead of env vars.
for (const key of ['ADMIN_KEY', 'OPERATOR_KEY', 'BRAND_MASTER_KEY', 'BRAND_SALT']) {
  const filePath = process.env[`${key}_FILE`]
  if (filePath && !process.env[key]) {
    try {
      process.env[key] = readFileSync(filePath, 'utf-8').trim()
    } catch {
      console.error(`[Startup] Could not read secret file for ${key}: ${filePath}`)
    }
  }
}

// ── Startup safety checks ────────────────────────────────────────────────────
// Prevent the server from starting in production with an insecure default key
// or without specifying allowed CORS origins.
const adminKey = process.env.ADMIN_KEY ?? process.env.ADMIN_API_KEY
const DEFAULT_KEY = 'change_me_generate_with_openssl_rand_hex_32'

if (process.env.NODE_ENV === 'production') {
  if (!adminKey || adminKey === DEFAULT_KEY) {
    console.error('[FATAL] ADMIN_KEY must be set to a secure random value in production.')
    console.error('[FATAL] Generate one with: openssl rand -hex 32')
    process.exit(1)
  }
  if (!process.env.ALLOWED_ORIGINS) {
    console.error('[FATAL] ALLOWED_ORIGINS must be set in production (e.g. https://raventag.com).')
    process.exit(1)
  }
}

const app = express()
// Trust the first proxy hop so that req.ip and rate-limit headers reflect the real client IP
// when the backend runs behind nginx or any other reverse proxy.
app.set('trust proxy', 1)

const PORT = Number(process.env.PORT ?? 3001)

const logoSvg = readFileSync(`${__dirname}/../public/logo.svg`, 'utf-8')
  .replace(/<svg /, '<svg style="width:96px;height:96px;display:block;margin:0 auto 20px" ')

// ── Security middleware ──────────────────────────────────────────────────────
// Helmet sets secure HTTP headers (X-Content-Type-Options, X-Frame-Options, etc.)
app.use(helmet())
// HIGH-1: never use wildcard origins; in dev default to localhost only
app.use(cors({
  origin: (process.env.ALLOWED_ORIGINS
    ?? (process.env.NODE_ENV === 'production' ? '' : 'http://localhost:3000,http://localhost:3001'))
    .split(',').filter(Boolean)
}))
// Limit request body size to prevent memory exhaustion via large JSON payloads
app.use(express.json({ limit: '100kb' }))

// ── Request logger ───────────────────────────────────────────────────────────
// Logs every request (method, path, status, duration, IP) to console and SQLite.
app.use(requestLogger)

// ── Rate limiting ────────────────────────────────────────────────────────────
/**
 * Create an express-rate-limit middleware with a 1-minute window.
 * Uses standard RateLimit-* headers (RFC 6585 draft).
 * On rate-limit hit, logs the event to the DB for monitoring.
 */
function makeLimiter(max: number) {
  return rateLimit({
    windowMs: 60_000,
    max,
    standardHeaders: true,
    legacyHeaders: false,
    message: { error: 'Too many requests', code: 'RATE_LIMITED' },
    handler: (req, res, _next, options) => {
      // Prefer X-Forwarded-For (set by reverse proxies) over direct socket IP
      const ip = (req.headers['x-forwarded-for'] as string)?.split(',')[0].trim() ?? req.ip ?? 'unknown'
      logRateLimitEvent(ip, req.path)
      res.status(options.statusCode).json(options.message)
    }
  })
}

const verifyLimiter = makeLimiter(30)  // public verify: 30 req/min
const apiLimiter    = makeLimiter(60)  // public read: 60 req/min
// CRIT-4: admin/brand write routes get a strict 5 req/min to limit brute-force
const adminLimiter  = makeLimiter(5)

// Health check (no version info exposed publicly)
app.get('/health', (_req, res) => {
  res.json({ status: 'ok', protocol: 'RTP-1' })
})

// Android App Links verification file.
// Android OS fetches this to confirm the app is authorized to handle URLs on this domain.
// Set ANDROID_APP_FINGERPRINT env var to SHA-256 certificate fingerprint(s) of the release APK(s).
// RTSL-1.0 LICENSE REQUIREMENT: RavenTag Verify fingerprint MUST be included in all deployments.
// Additional brand fingerprints can be added (comma-separated) for co-branding.
// Generate with: keytool -list -v -keystore your.keystore -alias your_alias
app.get('/.well-known/assetlinks.json', (_req, res) => {
  const fingerprintsEnv = process.env.ANDROID_APP_FINGERPRINT
  if (!fingerprintsEnv) {
    res.status(404).json({ error: 'Not configured' })
    return
  }
  // Support single or multiple fingerprints (comma-separated)
  const fingerprints = fingerprintsEnv.split(',').map(f => f.trim()).filter(Boolean)
  if (fingerprints.length === 0) {
    res.status(404).json({ error: 'No valid fingerprints configured' })
    return
  }
  
  // RTSL-1.0 Compliance: RavenTag Verify fingerprint must always be included
  const raventagFingerprint = '3EA5B9F375631A4E1DE95DE1DA9C2245141E4AD8FA7A63787D6AB98196B4A3BE'
  if (!fingerprints.includes(raventagFingerprint)) {
    console.error('[RTSL-1.0 VIOLATION] RavenTag Verify fingerprint must be included in ANDROID_APP_FINGERPRINT')
    console.error('[RTSL-1.0] See LICENSE for attribution requirements')
    res.status(403).json({ 
      error: 'RTSL-1.0 license violation: RavenTag Verify fingerprint must be included',
      required: raventagFingerprint 
    })
    return
  }
  
  res.json([{
    relation: ['delegate_permission/common.handle_all_urls'],
    target: {
      namespace: 'android_app',
      package_name: 'io.raventag.app',
      sha256_cert_fingerprints: fingerprints
    }
  }])
})

// Metrics endpoint (admin only)
// Returns request totals, error rate, top paths, and average latency for the last N hours.
app.get('/api/metrics', requireAdminKey, (_req, res) => {
  const hours = 24
  res.json(getRequestStats(hours))
})


// Browser-facing page shown when an NFC tag is tapped outside the app.
// Invites the user to install RavenTag to verify the tag.
app.get('/verify', verifyLimiter, (_req, res) => {
  res.send(installHtml())
})

function installHtml(): string {
  const releaseUrl = 'https://github.com/ALENOC/RavenTag/releases/latest'
  const playStoreUrl = process.env.PLAY_STORE_URL
  const apkUrl = process.env.VERIFY_APK_URL || releaseUrl

  const downloadBtn = playStoreUrl
    ? `<a href="${playStoreUrl}" target="_blank" rel="noopener noreferrer" style="display:inline-block">
    <svg xmlns="http://www.w3.org/2000/svg" width="180" height="54" viewBox="0 0 180 54">
      <rect width="180" height="54" rx="8" fill="#000"/>
      <rect x=".5" y=".5" width="179" height="53" rx="7.5" fill="none" stroke="#a6a6a6" stroke-width="1"/>
      <text x="62" y="18" fill="#fff" font-family="sans-serif" font-size="9" letter-spacing=".5">GET IT ON</text>
      <text x="62" y="37" fill="#fff" font-family="sans-serif" font-size="18" font-weight="600">Google Play</text>
      <g transform="translate(16,10)"><path d="M0 1.2C0 .5.4 0 .8.2L20.5 16 .8 31.8C.4 32 0 31.5 0 30.8Z" fill="#4285f4"/><path d="M.8.2 20.5 16l5.5-5.6L3.6.2C2.8-.2 1.6-.1.8.2Z" fill="#34a853"/><path d="M.8 31.8l2.8-.1L26 21.6 20.5 16Z" fill="#ea4335"/><path d="M26 10.4 3.6.2c-.4-.2-.8 0-.8.2L20.5 16Z" fill="#fbbc05"/><path d="M3.6 31.7 26 21.6 20.5 16 .8 31.8c.4.2 1.2.2 2.8-.1Z" fill="#ea4335"/></g>
    </svg>
  </a>`
    : `<a class="btn" href="${apkUrl}">Download for Android</a>`

  return `<!DOCTYPE html><html lang="en"><head><meta charset="UTF-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>RavenTag</title>
<style>
body{background:#0a0a0a;color:#fff;font-family:sans-serif;display:flex;align-items:center;justify-content:center;min-height:100vh;margin:0;padding:16px;box-sizing:border-box}
.card{background:#0a0a0a;border:1px solid #333;border-radius:16px;padding:32px;max-width:400px;width:100%;text-align:center}
h1{color:#f97316;margin:0 0 8px;font-size:22px}
p{color:#aaa;line-height:1.6;margin:0 0 24px;font-size:15px}
.btn{display:inline-block;background:#f97316;color:#fff;text-decoration:none;border-radius:10px;padding:12px 28px;font-size:15px;font-weight:600;letter-spacing:.3px}
.btn:hover{background:#ea6c0a}
.sub{display:block;margin-top:12px;font-size:12px;color:#555}
</style></head>
<body><div class="card">
  ${logoSvg}
  <h1>RavenTag Verify</h1>
  <p>Install the RavenTag Verify app to authenticate this NFC tag.</p>
  ${downloadBtn}
  <a class="sub" href="${releaseUrl}">View all releases</a>
</div></body></html>`
}

// API routes
app.use('/api/assets', apiLimiter, assetsRouter)
app.use('/api/verify', verifyLimiter, verifyRouter)
// CRIT-4: admin and brand routes use stricter rate limit (5 req/min)
app.use('/api/admin', adminLimiter, adminRouter)
app.use('/api/brand', adminLimiter, brandRouter)
app.use('/api/registry', apiLimiter, registryRouter)

// 404 handler - catches all unmatched routes
app.use((_req, res) => {
  res.status(404).json({ error: 'Not found', code: 'NOT_FOUND' })
})

// Global error handler - last-resort catch for unhandled errors in route handlers
app.use((err: Error, _req: express.Request, res: express.Response, _next: express.NextFunction) => {
  console.error('[Error]', err.message)
  res.status(500).json({ error: 'Internal server error', code: 'INTERNAL_ERROR' })
})

app.listen(PORT, () => {
  console.log(`RavenTag API running on http://localhost:${PORT}`)
  console.log(`Protocol: RTP-1 | Env: ${process.env.NODE_ENV ?? 'development'}`)
})

export default app
