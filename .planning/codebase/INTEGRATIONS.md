# External Integrations
> Generated: 2026-04-13 | Focus: tech | Repo: RavenTag

## Blockchain: Ravencoin

**Type:** Self-hosted full node + public fallback

**Primary:** Local Ravencoin Core node (JSON-RPC over HTTP)
- Connection: `http://$RVN_RPC_HOST:$RVN_RPC_PORT`
- Auth: HTTP Basic (`RVN_RPC_USER` / `RVN_RPC_PASS`, optional if local)
- Default port: 8766
- Client: custom axios-based RPC client in `backend/src/services/ravencoin.ts`
- Methods used: `getassetdata`, `listassets`, `listassetbalancesbyaddress`, `issue`, `issuesubasset`, `transfer`
- Asset index required (`assetindex=1`) for address-based asset queries

**Fallback A: Public RPC node**
- URL: env `RVN_PUBLIC_RPC_URL` (default: `https://rvn-rpc.publicnode.com`)
- Used automatically when local node is unreachable
- Same axios client, no auth

**Fallback B: ElectrumX (TLS JSON-RPC)**
- Protocol: Electrum protocol 1.4 over TLS port 50002
- Client: custom TLS socket implementation in `backend/src/services/electrumx.ts`
- Servers (tried in order with failover):
  1. `rvn4lyfe.com:50002`
  2. `rvn-dashboard.com:50002`
  3. `162.19.153.65:50002`
  4. `51.222.139.25:50002`
- Security: TOFU (Trust-On-First-Use) certificate pinning, in-memory per process
- Methods used: `blockchain.scripthash.get_balance`, `blockchain.scripthash.listunspent`, `blockchain.transaction.broadcast`, `blockchain.transaction.get`, `blockchain.asset.get_meta`
- Used when local node lacks `assetindex=1`

**Android client:**
- OkHttp + Gson direct RPC calls via `android/app/src/main/java/io/raventag/app/ravencoin/RpcClient.kt`
- Retrofit REST client to backend API via `android/app/src/main/java/io/raventag/app/wallet/AssetManager.kt`

## IPFS

**Type:** Local Kubo node (writes) + public gateways (reads)

**Writes (pinning):**
- Local Kubo (go-ipfs) HTTP API
- URL: env `IPFS_API_URL` (default: `http://127.0.0.1:5001`)
- Endpoint: `POST /api/v0/add?cid-version=0&pin=true`
- Used by: `backend/src/services/ipfs.ts` - `uploadMetadataToIpfs()`, `uploadImageToIpfs()`
- Produces CIDv0 hashes (Qm...) for compatibility with Ravencoin asset script field

**Reads (metadata fetch):**
- Primary gateway: env `IPFS_GATEWAY` (default: `https://ipfs.io/ipfs/`)
- Allowed fallback hostnames (SSRF allowlist): `ipfs.io`, `cloudflare-ipfs.com`, `dweb.link`, `gateway.pinata.cloud`
- Used by: `backend/src/services/ipfs.ts` - `fetchIpfsMetadata()`

**Android IPFS gateways (BuildConfig):**
- Primary: `IPFS_GATEWAY` (default: `https://ipfs.io/ipfs/`)
- Fallback list: `IPFS_GATEWAYS` (default: ipfs.io, cloudflare-ipfs.com, gateway.pinata.cloud)

## NFC Hardware: NTAG 424 DNA

**Type:** Hardware chip (NXP Semiconductors), no external API

**Protocol:** SUN (Secure Unique NFC) - AES-128 based
- The chip encrypts UID + read counter using AES-128 CMAC
- MAC is 4 bytes (NXP truncation of 8-byte CMAC: even-indexed bytes only)
- MAC appears as `m` URL parameter in scanned NDEF URLs

**Backend verification:**
- Service: `backend/src/services/ntag424.ts`
- SUN decryption + MAC verification using Node.js `crypto` module (no external library)

**Frontend verification:**
- Service: `frontend/src/lib/crypto.ts`
- Uses browser Web Crypto API (`SubtleCrypto.importKey`, `SubtleCrypto.encrypt`)

**Android verification:**
- Service: `android/app/src/main/java/io/raventag/app/nfc/SunVerifier.kt`
- Uses BouncyCastle AES-CMAC (`org.bouncycastle:bcprov-jdk15to18` 1.77)
- NFC reading: Android platform `NfcAdapter` + `NfcReader.kt`

## Data Storage

**SQLite (Backend):**
- Library: `better-sqlite3` ^9.4.3
- File path: env `DB_PATH` (default: `raventag.db`, production: `/data/raventag.db`)
- Mode: WAL journal, foreign keys ON
- Module: `backend/src/middleware/cache.ts`
- Tables:
  - `cache` - TTL-based key/value cache for asset and IPFS data
  - `revoked_assets` - Revocation records (asset name, reason, burn txid, timestamp)
  - `nfc_counters` - Last-seen SUN read counter per `nfc_pub_id` (replay protection)
  - `chip_registry` - Maps asset names to physical tag UIDs and `nfc_pub_id`
- Migrations: `backend/src/services/migrations.ts`
- Persistence: Docker volume `raventag_data` mounted at `/data`

**Encrypted Backups:**
- Runs in `backup` Docker service (`alpine:3.19`)
- Daily snapshots, AES-256-CBC + PBKDF2 via `openssl enc`
- Key: contents of `admin_key` Docker secret
- Retention: last 7 backups
- Volume: `raventag_backups`

**Android Secure Storage:**
- `androidx.security:security-crypto` 1.1.0-alpha06 (EncryptedSharedPreferences)
- Backed by Android Keystore (AES-GCM)
- Used in: `android/app/src/main/java/io/raventag/app/wallet/WalletManager.kt`
- Stores: BIP39 mnemonic (encrypted), wallet state

## Authentication and Authorization

**Backend admin auth:**
- Mechanism: static API key in request header
- Env var: `ADMIN_KEY` (loaded from Docker secret file `ADMIN_KEY_FILE`)
- Accepted headers: `X-Admin-Key` or `X-Api-Key`
- Middleware: `backend/src/middleware/auth.ts`
- Public endpoints (no auth): `GET /api/assets/:name/revocation`

**Backend operator/brand keys:**
- `OPERATOR_KEY_FILE` and `BRAND_MASTER_KEY_FILE` Docker secrets (purpose: brand-level operations)
- `BRAND_SALT_FILE` Docker secret (used in `nfc_pub_id` derivation: SHA-256(uid || salt))

**Android:**
- Admin key stored in BuildConfig `ADMIN_KEY` field (set at build time for brand flavor)
- Biometric unlock via `androidx.biometric:biometric` 1.1.0

## Analytics

**Frontend:**
- `@vercel/analytics` ^2.0.1
- Enabled when `NEXT_PUBLIC_APP_URL` points to a Vercel-hosted deployment
- Import: `frontend/src/` (no config file found; standard `<Analytics />` component pattern)

## CI/CD and Deployment

**Container registry / hosting:**
- Backend: Docker container (exposed on `127.0.0.1:3001`, intended behind reverse proxy)
- Frontend: Next.js standalone, likely Vercel (telemetry disabled: `NEXT_TELEMETRY_DISABLED=1`)
- Android: APKs released via GitHub Releases (`gh release upload`)

**GitHub Actions:**
- Workflow files: `.github/workflows/qwen-*.yml` (5 files)
- Purpose: automated issue triage and PR review via Qwen model invocation
- No CI pipeline for build/test/deploy was found in `.github/workflows/`

**Docker secrets management:**
- Development: plain files in `./secrets/<name>`
- Production: Docker Swarm secrets (`docker secret create <name>`)

## Vercel Analytics Allowlist (CORS)

- `ALLOWED_ORIGINS` env var controls CORS in the Express backend (default: `https://raventag.com`)
- Android APK fingerprint for request validation: `ANDROID_APP_FINGERPRINT` env var

## Webhook and Callback Endpoints

**Incoming:** None detected.

**Outgoing:** None detected.

## Public Network Dependencies Summary

| Service | Role | URL / Config |
|---|---|---|
| Ravencoin public RPC | Blockchain fallback | `RVN_PUBLIC_RPC_URL` (default: `rvn-rpc.publicnode.com`) |
| ElectrumX servers (4) | Blockchain fallback B | TLS port 50002, see `electrumx.ts` |
| ipfs.io | IPFS read gateway | `IPFS_GATEWAY` env / BuildConfig |
| cloudflare-ipfs.com | IPFS read fallback | SSRF allowlist in `ipfs.ts` |
| gateway.pinata.cloud | IPFS read fallback | SSRF allowlist + Android BuildConfig |
| dweb.link | IPFS read fallback | SSRF allowlist in `ipfs.ts` only |
| Local Kubo node | IPFS writes (pinning) | `IPFS_API_URL` (default: `127.0.0.1:5001`) |
| Vercel Analytics | Frontend analytics | `@vercel/analytics` package |
