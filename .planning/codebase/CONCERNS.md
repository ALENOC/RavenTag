# Technical Concerns
> Generated: 2026-04-13 | Focus: concerns | Repo: RavenTag

## Security

**ADMIN_KEY baked into Android release APK (brand flavor):**
- Risk: `BuildConfig.ADMIN_KEY` is set at compile time from `build.gradle.kts` defaultConfig. In the brand flavor, `MainActivity.kt:2113` instantiates `AssetManager(adminKey = BuildConfig.ADMIN_KEY)`. If the default empty string `""` is shipped, all admin calls silently fail; if a real key is compiled in, it is extractable from the APK by decompiling.
- Files: `android/app/build.gradle.kts` (line: `buildConfigField("String", "ADMIN_KEY", "\"\"")`), `android/app/src/main/java/io/raventag/app/MainActivity.kt:2113`
- Current mitigation: The brand app also stores the admin key in EncryptedSharedPreferences (set from Settings UI), and most brand flows use the key entered at runtime. The `BuildConfig.ADMIN_KEY` path is a secondary fallback that is only exercised when no key has been saved to prefs yet.
- Recommendation: Remove the `BuildConfig.ADMIN_KEY` field entirely; always require the key from EncryptedSharedPreferences and surface a clear onboarding error when it is absent.

**ElectrumX TLS: `rejectUnauthorized: false` in production:**
- Risk: The ElectrumX client in `backend/src/services/electrumx.ts:189` disables certificate validation for all TLS connections (`rejectUnauthorized: false`, `checkServerIdentity: () => undefined`). The only protection is in-memory TOFU pinning, which resets on every process restart. A MITM can present any certificate on the first connection after a restart and permanently pin a fraudulent fingerprint.
- Files: `backend/src/services/electrumx.ts:186-205`
- Impact: An attacker controlling the network path after a backend restart could feed false asset data or suppress revocation lookups.
- Fix approach: Pin known SHA-256 fingerprints of the Ravencoin public ElectrumX servers in a config file and validate against them, or use `rejectUnauthorized: true` when the server uses a CA-signed certificate.

**TOFU cert cache is in-process and non-persistent:**
- Risk: `certCache` in `backend/src/services/electrumx.ts:124` is a plain JS `Map`, cleared on every Node.js restart. Any orchestrated restart (container restart, deploy) resets all pinned fingerprints, leaving the first post-restart connection window unprotected.
- Files: `backend/src/services/electrumx.ts:124`
- Fix approach: Persist fingerprints to the SQLite database so they survive restarts.

**Admin key sent over HTTP in development (no TLS enforcement):**
- Risk: `backend/src/index.ts:74-78` allows `http://localhost:*` CORS origins in development. No mechanism prevents brand app operators from pointing the Android app at a plain-HTTP backend URL. Admin key and per-chip AES keys would travel in cleartext.
- Files: `backend/src/index.ts:74-78`, `android/app/src/main/java/io/raventag/app/config/AppConfig.kt:17`
- Severity: Low in production (enforced by reverse proxy), real in misconfigured deployments.

**AES keys from `derive-chip-key` transmitted in HTTP response body:**
- Risk: `POST /api/brand/derive-chip-key` returns all four per-chip AES-128 keys in the response JSON (`backend/src/routes/brand.ts:212-219`). Any logging layer (proxy, WAF, request logging) that captures response bodies would capture active cryptographic keys.
- Files: `backend/src/routes/brand.ts:185-220`
- Mitigation in place: The request logger (`backend/src/middleware/logger.ts`) does not log response bodies. The route is behind adminLimiter (5 req/min) and requireAdminKey.
- Recommendation: Verify that no upstream proxy or CDN logs response bodies for this path.

**`SELECT *` in admin list endpoints leaks schema details:**
- Risk: `backend/src/routes/admin.ts:78`, `backend/src/middleware/cache.ts:129`, `backend/src/middleware/cache.ts:249` use `SELECT *`. If new columns are added to these tables (e.g., internal notes), they are exposed without an explicit choice.
- Files: `backend/src/routes/admin.ts:78`, `backend/src/middleware/cache.ts:129,249`
- Fix: Use explicit column lists in all admin SELECT queries.

---

## Performance

**Sequential N+1 RPC calls in `getAssetHierarchy`:**
- Problem: `backend/src/services/ravencoin.ts:220-232` fetches sub-assets with `listSubAssets(parentAsset)`, then iterates over all results with a `for` loop calling `listSubAssets(sub)` sequentially. A parent with N sub-assets generates N+1 serial RPC/ElectrumX calls.
- Files: `backend/src/services/ravencoin.ts:224-230`
- Impact: For a brand with 50 sub-assets, a single `/api/assets/:name/hierarchy` request makes 51 sequential network calls, each potentially up to 12s (ElectrumX timeout). Response latency scales linearly with the asset tree depth.
- Fix approach: Replace the sequential `for` loop with `Promise.all(subAssets.map(...))`.

**`listassets` cap at 200 sub-assets per call:**
- Problem: `backend/src/services/ravencoin.ts:186-189` limits each `listassets` call to 200 results. Brands with more than 200 sub-assets or unique tokens will silently receive a truncated list with no indication of truncation.
- Files: `backend/src/services/ravencoin.ts:186-189`
- Fix approach: Implement pagination using the offset parameter, or document the 200-item limit explicitly in API responses.

**SQLite `request_logs` table grows unboundedly at runtime:**
- Problem: Migration 6 in `backend/src/middleware/migrations.ts:132-140` deletes logs older than 30 days only once at migration time. There is no periodic cleanup job. Under sustained traffic the table grows indefinitely, eventually degrading all SQLite query performance (the table shares the same WAL file as revocation and counter checks).
- Files: `backend/src/middleware/migrations.ts:133-140`, `backend/src/middleware/logger.ts:55-62`
- Fix approach: Add a SQLite trigger on `request_logs` INSERT that deletes rows older than 30 days, or implement a periodic Worker in the Node.js process using `setInterval`.

**`nfc_counters` table has no retention policy:**
- Problem: Each unique chip that has ever been scanned creates a permanent row in `nfc_counters`. There is no cleanup logic anywhere in the codebase. In a high-volume deployment the table grows indefinitely and every scan performs an `INSERT OR REPLACE` that writes through to WAL.
- Files: `backend/src/middleware/cache.ts:109-119`, `backend/src/middleware/migrations.ts:63-68`
- Fix approach: Delete `nfc_counters` rows for chips whose asset is revoked or when the chip is de-registered. Optionally add a TTL-based sweep for chips not seen in > 1 year.

**`idCounter` in ElectrumX client is not concurrent-safe:**
- Problem: `backend/src/services/electrumx.ts:131` uses a module-level `let idCounter = 1` that is incremented with `idCounter++`. Under concurrent requests this can produce duplicate IDs, causing response misrouting on a shared socket. (In practice each request opens its own socket, so the impact is low, but the pattern is fragile.)
- Files: `backend/src/services/electrumx.ts:131,166-167`
- Fix: Use `Math.random()` or a proper UUID for JSON-RPC IDs, or document that each call uses its own socket and the counter is only for correlation within that call.

**Android `enrichWithIpfsData` is a blocking synchronous call on a worker thread:**
- Problem: `android/app/src/main/java/io/raventag/app/ravencoin/RpcClient.kt:246-308` uses OkHttp's blocking `execute()` and iterates over multiple gateway URLs sequentially. This is called from a coroutine but is not itself a suspend function, so it blocks the thread for up to `N_gateways * 30s` per asset.
- Files: `android/app/src/main/java/io/raventag/app/ravencoin/RpcClient.kt:246-308`
- Fix: Convert to a suspend function with `withContext(Dispatchers.IO)` and try gateway URLs in parallel with `select { }`.

---

## Technical Debt

**`registered_tags` table is labeled "legacy" but still has active endpoints:**
- Problem: `backend/src/routes/admin.ts` comments describe `registered_tags` as a "legacy store" but three active admin endpoints (`POST /api/admin/register-tag`, `GET /api/admin/tags`, `DELETE /api/admin/tags/:nfcPubId`) still read/write it. The newer `chip_registry` table in `backend/src/routes/brand.ts` is the intended replacement. The legacy table creates two parallel registration systems that can diverge.
- Files: `backend/src/routes/admin.ts`, `backend/src/middleware/migrations.ts:46-52`
- Fix approach: Document that `registered_tags` is deprecated and should not be used for new integrations; eventually migrate remaining callers to `chip_registry`.

**`ensureTable()` in registry routes duplicates migrations:**
- Problem: `backend/src/routes/registry.ts:43-51` contains a `CREATE TABLE IF NOT EXISTS brand_registry` statement run on every request to protect against the table not existing yet. This duplicates the same DDL in Migration 2. The pattern indicates uncertainty about whether migrations are always applied before routes are hit.
- Files: `backend/src/routes/registry.ts:43-51`, `backend/src/middleware/migrations.ts:78-85`
- Fix: Remove the `ensureTable()` guard now that migrations run at startup before routes are mounted.

**Duplicate `connectTimeout`/`readTimeout` in Android `NetworkModule`:**
- Problem: `android/app/src/main/java/io/raventag/app/network/NetworkModule.kt:82-84` sets `connectTimeout` and `readTimeout` a second time inside `buildClient`, overriding the values set on lines 69-71. The effective timeouts are 15s connect / 30s read, but the first set (10s/15s) is silently discarded.
- Files: `android/app/src/main/java/io/raventag/app/network/NetworkModule.kt:67-86`
- Fix: Remove the duplicate timeout calls on lines 82-84.

**`consolidate_fix.kt` file in project root is uncommitted scratch code:**
- Problem: A file `consolidate_fix.kt` exists at the repository root and appears in `git status` as untracked. Its purpose is unclear and it should either be committed into a proper location or deleted.
- Files: `/consolidate_fix.kt`

**Comment typo in `index.ts` (duplicated line):**
- Problem: `backend/src/index.ts:10-11` contains the comment "Mount all API route groups under /api/*" duplicated on consecutive lines.
- Files: `backend/src/index.ts:10`
- Impact: Cosmetic only.

---

## Dependency Risks

**`better-sqlite3` v9 requires native compilation:**
- Risk: `better-sqlite3` uses a native Node.js addon. Any Node.js major version upgrade, Alpine Linux base image change, or ARM/x86 cross-compilation will require a native rebuild. A mismatch causes an immediate startup crash. The Dockerfile uses `node:20-alpine`; if the base image is updated to Node 22 without rebuilding, the pre-built addon will refuse to load.
- Files: `backend/package.json` (`"better-sqlite3": "^9.4.3"`), `backend/Dockerfile`
- Mitigation: The multi-stage Dockerfile ensures the addon is built in the same environment it runs in. Keep the `node:20-alpine` pin explicit and bump it intentionally.

**No `package-lock.json` test coverage:**
- Risk: Backend has no test suite (no Jest, Mocha, or similar in `backend/package.json`). Dependency upgrades (e.g., `axios`, `zod`) are not regression-tested. The `^` version pins in `package.json` allow minor/patch upgrades that could introduce breaking changes silently.
- Files: `backend/package.json`

**Bouncy Castle included as a compile-time dependency in Android:**
- Risk: `android/app/build.gradle.kts` depends on `bouncy.castle` for AES-CMAC, ECDSA, and BIP32 operations. Bouncy Castle is a large library and has had historical CVEs (mostly in its TLS stack, not AES). The app uses only the crypto primitives, not the TLS stack. No version is pinned in the concern list without checking `libs.versions.toml`.
- Files: `android/app/build.gradle.kts`

**`axios` v1.6.7 in backend is not the latest patch:**
- Risk: `axios` 1.6.x had a SSRF-related advisory (GHSA-wf5p-g6vw-rhxx) in some configurations. The IPFS fetch code in `backend/src/services/ipfs.ts` uses axios for external network calls. The SSRF mitigation is applied at the application layer (`ipfsUriToHttp`), but upgrading to the latest patch is low-risk.
- Files: `backend/package.json` (`"axios": "^1.6.7"`), `backend/src/services/ipfs.ts`

---

## Operational

**SQLite hot backup may produce a corrupt file under write load:**
- Risk: The backup container in `docker-compose.yml` (lines 39-54) reads `/data/raventag.db` with `openssl enc` (a raw file copy). SQLite WAL mode does not guarantee a consistent copy of a file read this way while writes are in progress. The result is a backup that may not be a valid SQLite database.
- Files: `docker-compose.yml:39-54`
- Fix approach: Replace the raw file copy with `sqlite3 /data/raventag.db ".backup /backups/raventag_${TIMESTAMP}.db"` (SQLite's online backup API), which is safe under concurrent writes, then encrypt the output.

**No structured error logging or log aggregation:**
- Problem: All backend errors are logged to stdout with `console.error('[tag]', err)`. There is no structured JSON logging, no log level filtering, and no integration with an external log aggregator. Debugging production issues requires direct access to container logs.
- Files: `backend/src/middleware/logger.ts`, all route files using `console.error`
- Fix approach: Replace `console.error` with a structured logger (e.g., `pino`) that emits JSON with severity, timestamp, request ID, and stack trace.

**No process-level unhandledRejection handler:**
- Problem: `backend/src/index.ts` does not register a `process.on('unhandledRejection', ...)` handler. An unhandled promise rejection in Node.js 20+ terminates the process. The Express global error handler on line 225 only catches synchronous errors thrown inside route handlers; async errors from outside the request lifecycle (e.g., ElectrumX background operations) are uncaught.
- Files: `backend/src/index.ts`
- Fix: Add `process.on('unhandledRejection', (reason) => console.error('[Fatal]', reason))` at startup.

**Single Docker container = single point of failure:**
- Problem: The entire backend runs as one Node.js process in one container with a single SQLite file. There is no horizontal scaling path, no read replica, and no failover. A backend restart causes brief downtime for all scan verification requests.
- Files: `docker-compose.yml`
- Impact: Acceptable for an open-source self-hosted protocol, but relevant for brands expecting high availability.

**No health check on the frontend container:**
- Problem: `docker-compose.yml` defines a `healthcheck` only for the `backend` service. The `frontend` service (if defined) has no health check. The backup container depends on `backend` being healthy, but if the frontend is down the compose stack still reports healthy.
- Files: `docker-compose.yml`

**`request_logs` IP field stores raw X-Forwarded-For header value:**
- Problem: `backend/src/middleware/logger.ts:37-38` reads the first value from `X-Forwarded-For` to populate the `ip` column. This value is controlled by the client if the server is not behind a trusted reverse proxy. The trust is set globally with `app.set('trust proxy', 1)` (`index.ts:63`), which trusts exactly one proxy hop. If the deployment has zero or more than one proxy hop, the stored IP will be wrong or spoofed.
- Files: `backend/src/middleware/logger.ts:37-38`, `backend/src/index.ts:63`
- Fix: Document the required `trust proxy` setting in `.env.example` and verify against the actual deployment topology.

---

## Data Integrity

**Soft revocation is per-instance: multi-backend deployments produce inconsistent results:**
- Problem: Revocation state lives entirely in the local SQLite database (`revoked_assets` table). If two instances of the backend are deployed behind a load balancer (or even with a blue-green deploy), a revocation applied to instance A is not visible to instance B until the database is shared or replicated. A scanner hitting the unrevoked instance would see an AUTHENTIC result for a revoked asset.
- Files: `backend/src/middleware/cache.ts:74-82`, `backend/src/routes/brand.ts:54-70`
- Fix approach: For multi-instance deployments, mount the same SQLite file via a shared NFS/EFS volume, or migrate to PostgreSQL. Document this single-instance constraint in the deployment guide.

**`issued_at` field in `asset_emissions` is user-supplied:**
- Problem: `backend/src/routes/registry.ts:140` uses `issued_at || new Date().toISOString()`. The client can supply any `issued_at` value, including timestamps in the past or future, without validation. The field is used for ordering in the public emissions list.
- Files: `backend/src/routes/registry.ts:140`
- Fix: Ignore the client-supplied `issued_at` and always use `new Date().toISOString()` server-side, or validate that the value is a well-formed ISO 8601 date within an acceptable window.

**Asset emission notifications auto-register brands without verification:**
- Problem: `backend/src/routes/registry.ts:151-157` auto-registers the root part of a notified asset name as a brand in `brand_registry` without any ownership verification. Any caller who knows a valid `txid` for a root asset can cause that asset name to appear in the public brand directory.
- Files: `backend/src/routes/registry.ts:151-157`
- Impact: The public brand list can be polluted with brand names that are not operated by the notifier.
- Fix approach: Require the brand to be explicitly registered via the admin-protected `POST /api/registry/register` endpoint, and remove the auto-registration from the notify path.

---

*Concerns audit: 2026-04-13*
