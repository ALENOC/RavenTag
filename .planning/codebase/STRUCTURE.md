# Codebase Structure
> Generated: 2026-04-13 | Focus: arch | Repo: RavenTag

## Root Layout

```
RavenTag/
├── backend/             Node.js + TypeScript + Express API server
├── frontend/            Next.js 14 web app
├── android/             Kotlin + Jetpack Compose Android app
├── docs/                Protocol and architecture documentation
├── docker-compose.yml   Production orchestration
├── .github/workflows/   CI/CD (ci.yml)
└── .env.example         Environment variable documentation
```

## Backend (`backend/`)

```
backend/
├── src/
│   ├── index.ts              Entry point, Express app setup
│   ├── routes/
│   │   ├── assets.ts         GET /api/assets, /api/assets/:name/revocation
│   │   ├── verify.ts         POST /api/verify/sun, /api/verify/full, GET /api/verify/tag/:uid
│   │   ├── brand.ts          POST /api/brand/issue, issue-sub, revoke, GET /api/brand/wallet, revoked
│   │   ├── admin.ts          Admin-only operations
│   │   └── registry.ts       Chip and brand registry endpoints
│   ├── services/
│   │   ├── ntag424.ts        SUN message decrypt + SDMMAC verification
│   │   ├── ravencoin.ts      Ravencoin RPC client (issue, issuesubasset, transfer, burn)
│   │   ├── electrumx.ts      ElectrumX client for UTXO queries + tx broadcast
│   │   └── ipfs.ts           IPFS metadata upload/retrieval
│   ├── middleware/
│   │   ├── auth.ts           API key authentication (ADMIN_KEY, OPERATOR_KEY)
│   │   ├── cache.ts          SQLite cache + revocation functions (isAssetRevoked, revokeAsset)
│   │   ├── logger.ts         Request logging middleware
│   │   └── migrations.ts     SQLite schema migrations
│   └── utils/
│       ├── crypto.ts         AES-CMAC, SHA-256, AES-CBC, key derivation
│       └── validation.ts     Zod schemas for request validation
├── package.json
├── tsconfig.json
└── Dockerfile
```

## Frontend (`frontend/`)

```
frontend/
├── src/
│   ├── app/                  Next.js App Router
│   │   ├── page.tsx          Home page (scan entry point)
│   │   ├── verify/           Verification result page
│   │   ├── assets/           Asset browser
│   │   ├── brand/            Brand dashboard
│   │   │   ├── page.tsx      Brand dashboard
│   │   │   ├── issue/        Asset issuance form
│   │   │   └── revoke/       Revocation management
│   │   └── api/              Thin proxy routes to backend
│   ├── components/
│   │   ├── NFCScanner.tsx    Web NFC API (NDEFReader), scan UI
│   │   ├── VerifyResult.tsx  Verification result display with REVOKED banner
│   │   ├── ClientLayout.tsx  Client-side layout wrapper
│   │   └── CookieBanner.tsx  Cookie consent
│   └── lib/
│       ├── ntag424.ts        SUN verification via Web Crypto API (trustless client-side)
│       ├── ravencoin.ts      RPC client + checkAssetRevocation, revokeAsset, issueAsset
│       ├── types.ts          Shared TypeScript types (VerificationResult, RevocationStatus)
│       └── i18n/             Translation strings
├── package.json
├── next.config.js
└── Dockerfile
```

## Android (`android/`)

```
android/
├── src/
│   ├── main/                 Shared code (both flavors)
│   │   ├── nfc/
│   │   │   ├── NfcReader.kt      NfcAdapter + NDEF URL parsing
│   │   │   └── SunVerifier.kt    AES-CMAC via Bouncy Castle, SUN verification
│   │   ├── ravencoin/
│   │   │   └── RpcClient.kt      OkHttp + Gson Ravencoin RPC client
│   │   ├── wallet/
│   │   │   ├── WalletManager.kt  BIP44 HD wallet, BIP39 mnemonic, Android Keystore AES-GCM
│   │   │   └── AssetManager.kt   Issue asset/sub-asset, revoke/burn via backend API
│   │   ├── ipfs/                 IPFS upload/retrieval
│   │   ├── worker/               Background workers
│   │   ├── network/              Network utilities
│   │   └── ui/
│   │       └── screens/
│   │           ├── ScanScreen.kt         NFC scan UI with animation
│   │           ├── VerifyScreen.kt       Verification result (REVOKED + reason)
│   │           ├── WalletScreen.kt       Generate/restore wallet, balance, actions
│   │           ├── IssueAssetScreen.kt   Asset issuance and revocation form
│   │           └── BrandDashboardScreen.kt Brand management panel
│   ├── brand/                Brand product flavor (IS_BRAND_APP=true)
│   └── consumer/             Consumer product flavor (IS_BRAND_APP=false)
├── MainActivity.kt           Bottom nav (Scan / Wallet / Brand), full-screen verify overlay
├── build.gradle              BuildConfig fields: RVN_RPC_URL, IPFS_GATEWAY, API_BASE_URL, ADMIN_KEY
└── build.gradle.kts
```

## Documentation (`docs/`)

```
docs/
├── protocol.md       RTP-1 protocol specification
└── architecture.md   System architecture overview
```

## Key Entry Points

| Target | Entry point |
|---|---|
| Backend | `backend/src/index.ts` |
| Frontend | `frontend/src/app/page.tsx` |
| Android | `android/MainActivity.kt` |

## Configuration Files

| File | Purpose |
|---|---|
| `.env.example` | Documents all required environment variables |
| `docker-compose.yml` | Production service orchestration with healthchecks |
| `backend/tsconfig.json` | TypeScript compiler config |
| `frontend/next.config.js` | Next.js build config |
| `android/build.gradle` | Android build config + BuildConfig injection |
| `.github/workflows/ci.yml` | CI: build + test + Docker + APK artifacts |
