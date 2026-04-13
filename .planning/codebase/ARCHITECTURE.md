# System Architecture
> Generated: 2026-04-13 | Focus: arch | Repo: RavenTag

## Overview

RavenTag is a protocol-first trustless system (RTP-1) for linking NTAG 424 DNA NFC tags to Ravencoin assets. It spans three deployment targets: a Node.js/Express backend, a Next.js frontend, and an Android app (Kotlin/Compose). The core invariant is that all cryptographic verification can run client-side with no trust in the server.

## Components

### Backend (Node.js/Express)
- REST API server serving verification, asset management, brand, admin, and registry endpoints
- SQLite database (via `better-sqlite3`) for caching, revocation, counters, chip/brand registries, and audit logs
- Ravencoin RPC client for on-chain asset operations
- ElectrumX client for UTXO queries and raw transaction broadcasting (on-device signing flow)
- IPFS integration for asset metadata

### Frontend (Next.js 14, App Router)
- Web NFC scanning via NDEFReader (Web NFC API, Chrome Android only)
- Thin API proxy routes forwarding to backend
- Verification result display with revocation status
- Brand management UI (issue, revoke, dashboard)
- Internationalization via i18n translations

### Android (Kotlin/Compose)
- Two product flavors: `brand` (full management, `IS_BRAND_APP=true`) and `consumer` (verify-only, `IS_BRAND_APP=false`)
- NFC reading via `NfcAdapter` + NDEF URL parsing
- On-device BIP44 HD wallet (m/44'/175'/0'/0/0) with BIP39 mnemonic, secured via Android Keystore AES-GCM
- Asset issuance via on-device transaction signing + ElectrumX broadcast
- SUN verification via Bouncy Castle AES-CMAC

## SUN Verification Pipeline (NTAG 424 DNA, NXP AN12196)

Three-step process:
1. **AES-CBC decrypt** of the encrypted UID/counter field using `SUN_ENC_KEY`
2. **Session MAC key derivation**: CMAC-based SV2 derivation from `SUN_MAC_KEY`, decrypted UID, and counter
3. **Truncated SDMMAC verify**: NXP truncation `CMAC(sessionKey, enc_data)[even_bytes][:4]` = 4 bytes = 8 hex chars, constant-time comparison

## Verification Modes

| Endpoint | Mode | Key exposure |
|---|---|---|
| `GET /api/verify/tag/:uid` | Brand-sovereign | No keys sent to client; server performs full verify |
| `POST /api/verify/full` | Trustless | Caller supplies `encKey` + `macKey`; server is stateless verifier |
| `POST /api/verify/sun` | Operator-protected | Operator holds keys; low-level SUN verify |

## Authentication Tiers

- **Public**: `GET /api/assets/:name/revocation`, all verify endpoints
- **Operator** (`OPERATOR_KEY` or `ADMIN_KEY` header `X-Api-Key`): brand routes, asset queries
- **Admin only** (`ADMIN_KEY` header `X-Admin-Key`): admin routes, registry management

## Key Derivation

Per-slot AES-128 ECB key derivation from master key:
```
slotKey = AES128_ECB(masterKey, [slot || uid || padding])
```
Slots 0x00-0x03 for ENC, MAC, and auxiliary keys.

## Privacy Identifier

```
nfc_pub_id = SHA-256(tag_uid || BRAND_SALT)
```
The salt is never stored on-chain, making the on-chain identifier unlinkable without brand cooperation.

## Data Flows

### NFC Tap -> Verification
1. Tag broadcasts NDEF URL with `uid`, `ctr`, `enc` (encrypted UID/counter), `m` (SDMMAC) params
2. App/frontend extracts params, calls verify endpoint
3. Backend decrypts UID, verifies counter freshness (anti-replay via `nfc_counters` table), verifies MAC
4. Backend checks `revoked_assets` table; returns verification result with revocation status

### Asset Issuance (Android brand flavor)
1. Brand user fills issue form (name, quantity, units, IPFS metadata)
2. WalletManager signs raw tx on-device using BIP44 key
3. Raw tx broadcast via ElectrumX
4. `asset_emissions` table updated

### Revocation
- **Soft revocation**: INSERT into `revoked_assets` SQLite table with reason; immediate effect
- **Hard revocation**: optional on-chain burn to `RXBurnXXXXXXXXXXXXXXXXXXXXXXWUo9FV`

## SQLite Schema (key tables)

| Table | Purpose |
|---|---|
| `cache` | Response caching with TTL |
| `revoked_assets` | Soft revocation records |
| `nfc_counters` | Anti-replay counter tracking per UID |
| `chip_registry` | Registered NFC chips |
| `brand_registry` | Registered brands |
| `asset_emissions` | Asset issuance audit log |
| `request_logs` | API request audit trail |
| `rate_limit_events` | Rate limiting state |

## Deployment

- Backend: Docker multi-stage build (node:20-alpine), persistent volume `/data/raventag.db`
- Frontend: Next.js standalone Docker build
- Orchestrated via `docker-compose.yml` with healthchecks
- CI: GitHub Actions building backend, frontend, Android APKs, and Docker images
