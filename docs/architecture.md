# RavenTag Architecture

## System Overview

```
┌─────────────────────────────────────────────────────────────┐
│                        PHYSICAL LAYER                        │
│                                                             │
│   ┌─────────────────┐                                       │
│   │  NTAG 424 DNA   │  Hardware AES-128 key (non-extractable)│
│   │  NFC Tag        │  SUN message on every scan            │
│   └────────┬────────┘                                       │
└────────────│────────────────────────────────────────────────┘
             │ NFC (13.56 MHz ISO 14443)
             ▼
┌─────────────────────────────────────────────────────────────┐
│                       CLIENT LAYER                           │
│                                                             │
│   ┌─────────────────┐      ┌─────────────────────────────┐  │
│   │  Android App    │      │      Web App (Next.js)      │  │
│   │  (Kotlin)       │      │      Web NFC API            │  │
│   │                 │      │      (Chrome Android)       │  │
│   │ NfcAdapter      │      │                             │  │
│   │ SUN Verifier    │      │  SUN Verifier (WebAssembly) │  │
│   │ RPC Client      │      │  RPC Client                 │  │
│   └────────┬────────┘      └──────────────┬──────────────┘  │
└────────────│─────────────────────────────│────────────────┘
             │                             │
             ▼                             ▼
┌─────────────────────────────────────────────────────────────┐
│                      BLOCKCHAIN LAYER                        │
│                                                             │
│   ┌─────────────────────────────────────────────────────┐   │
│   │              Ravencoin Network                      │   │
│   │                                                     │   │
│   │  Public RPC Nodes  │  Electrum Servers              │   │
│   │  (Trustless)       │  (Lightweight)                 │   │
│   │                    │                                │   │
│   │  Asset Registry:                                    │   │
│   │  BRAND/PRODUCT/VARIANT → nfc_pub_id in metadata    │   │
│   └─────────────────────────────────────────────────────┘   │
│                                                             │
│   ┌─────────────────────────────────────────────────────┐   │
│   │              IPFS Network                           │   │
│   │  Brand metadata, certificates, product images      │   │
│   └─────────────────────────────────────────────────────┘   │
└─────────────────────────────────────────────────────────────┘
             │ (Optional caching layer)
             ▼
┌─────────────────────────────────────────────────────────────┐
│                      BACKEND LAYER                           │
│                      (Optional)                             │
│                                                             │
│   ┌─────────────────────────────────────────────────────┐   │
│   │              Node.js API                            │   │
│   │                                                     │   │
│   │  - Asset metadata cache                            │   │
│   │  - IPFS metadata fetch (read-only)                 │   │
│   │  - Admin tag registration tool                     │   │
│   │  - Analytics (privacy-preserving)                  │   │
│   └─────────────────────────────────────────────────────┘   │
└─────────────────────────────────────────────────────────────┘
```

## Verification Flow Detail

```
NFC Scan
   │
   ▼
Read SUN URL: https://verify.<brand-domain>/verify?asset=...&e=ENCRYPTED&m=MAC
              (verify.raventag.com is the reference implementation;
               each brand uses their own domain after deployment)
   │
   ├─ Extract encrypted payload (e)
   │  └─ AES-128-CBC decrypt with SDMMAC key
   │     └─ Get: tag_uid (7 bytes) + counter (3 bytes)
   │
   ├─ Verify CMAC (m) with SUN MAC key
   │  └─ FAIL → ❌ Tag tampered or not authentic
   │
   ├─ Check counter > last_seen_counter (anti-replay)
   │  └─ FAIL → ⚠️ Possible replay attack
   │
   ├─ Compute nfc_pub_id = SHA-256(tag_uid || salt)
   │
   ├─ Query Ravencoin node:
   │  GET /assets/search?query=<nfc_pub_id>
   │  └─ Parse metadata JSON
   │     └─ Find asset where metadata.nfc_pub_id == computed_pub_id
   │
   ├─ Validate asset:
   │  ├─ Asset exists? ✓
   │  ├─ raventag_version == "RTP-1"? ✓
   │  └─ crypto_type == "ntag424_sun"? ✓
   │
   └─ ✅ AUTHENTIC: Display brand, product, variant info
```

## Wallet Roles and Access Control

The Brand app uses a role-based access system determined at wallet creation time. When creating or restoring a wallet, the user enters a control key (admin or operator). The app validates the key against the backend and locks the role to the wallet.

| Role | Control Key | Android permissions |
|---|---|---|
| Admin | Admin API key | All operations unlocked: issue root/sub assets, issue unique tokens, revoke/un-revoke, send RVN, transfer all asset types |
| Operator | Operator API key | Issue unique tokens only. Root/sub asset creation, revocation, send RVN, and root/sub transfers are locked. |

This enables a brand to pre-configure multiple operator devices sharing the same wallet that holds the owner assets. Operators can issue unique tokens (serials) with reduced privileges while the owner tokens remain in the shared wallet.

## Data Flow: Tag Registration (Brand Workflow)

```
Brand Admin
   │
   ├─ 1. Create Ravencoin asset hierarchy
   │     BRAND → BRAND/PRODUCT → BRAND/PRODUCT/VARIANT
   │
   ├─ 2. Program NTAG 424 DNA tag
   │     └─ Set SUN keys (Key 1: SDMMAC, Key 2: SUN MAC)
   │     └─ Enable SUN message
   │     └─ Set redirect URL template
   │
   ├─ 3. Generate nfc_pub_id
   │     └─ salt = random_bytes(16)
   │     └─ nfc_pub_id = SHA-256(tag_uid || salt)
   │     └─ Store salt securely (brand side)
   │
   ├─ 4. Create metadata JSON
   │     └─ Upload to Pinata (IPFS pinning API)
   │     └─ Get CIDv0 hash (Qm...)
   │
   └─ 5. Set asset metadata = IPFS hash
         └─ On-chain registration complete
```

## Security Model

### Trust Assumptions

| Component | Trusted? | Why |
|-----------|----------|-----|
| NTAG 424 DNA chip | Yes | Hardware-level AES, non-extractable keys |
| Ravencoin blockchain | Yes | Decentralized consensus |
| IPFS content | Verified | Content-addressed (hash-verified) |
| SUN MAC key | Yes (in app) | Required for verification |
| Brand salt | No | Privacy, not security-critical |
| Backend API | No | Optional cache only |

### Threat Model

| Threat | Mitigation |
|--------|-----------|
| Tag cloning | NTAG 424 DNA hardware prevents key extraction |
| Replay attack | 24-bit monotonic counter, checked by app |
| Man-in-middle | CMAC verification of SUN message |
| Fake asset on chain | App validates full asset metadata |
| UID correlation | SHA-256 salted hash, salt never on-chain |
| Counterfeit metadata | IPFS content-addressed + on-chain hash |

## Technology Stack

### Backend
- **Runtime**: Node.js 20 (LTS)
- **Language**: TypeScript 5
- **Framework**: Express.js 4
- **Database**: SQLite (for caching) / PostgreSQL (production)
- **IPFS**: fetch-only (public gateways, no pinning)
- **Crypto**: Node.js built-in crypto module

### Frontend
- **Framework**: Next.js 14 (App Router)
- **Language**: TypeScript 5
- **Styling**: Tailwind CSS 3
- **NFC**: Web NFC API
- **State**: React hooks + Context
- **Icons**: Lucide React

### Android
- **Language**: Kotlin 1.9
- **UI**: Jetpack Compose
- **NFC**: Android NfcAdapter
- **HTTP**: Retrofit 2 + OkHttp
- **Crypto**: Bouncy Castle (AES-128)
- **Min SDK**: API 26 (Android 8.0)

## Directory Structure

```
RavenTag/
├── README.md
├── LICENSE
├── docs/
│   ├── protocol.md          ← RTP-1 specification
│   └── architecture.md      ← This file
├── backend/
│   ├── src/
│   │   ├── index.ts         ← Entry point
│   │   ├── routes/
│   │   │   ├── assets.ts    ← Asset lookup endpoints
│   │   │   ├── verify.ts    ← SUN verification endpoint
│   │   │   └── admin.ts     ← Tag registration (protected)
│   │   ├── services/
│   │   │   ├── ravencoin.ts ← RPC client
│   │   │   ├── ntag424.ts   ← SUN crypto logic
│   │   │   └── ipfs.ts      ← IPFS operations
│   │   └── utils/
│   │       ├── crypto.ts    ← SHA-256, AES helpers
│   │       └── validation.ts← Input validation
│   ├── package.json
│   └── .env.example
├── frontend/
│   ├── src/
│   │   ├── app/
│   │   │   ├── page.tsx     ← Home/scanner
│   │   │   ├── verify/      ← Verification result page
│   │   │   └── assets/      ← Asset browser
│   │   ├── components/
│   │   │   ├── NFCScanner.tsx
│   │   │   ├── VerifyResult.tsx
│   │   │   └── AssetCard.tsx
│   │   └── lib/
│   │       ├── ntag424.ts   ← SUN verification
│   │       └── ravencoin.ts ← RPC client
│   ├── package.json
│   └── .env.example
└── android/
    ├── app/
    │   ├── src/main/
    │   │   ├── AndroidManifest.xml
    │   │   ├── java/io/raventag/app/
    │   │   │   ├── MainActivity.kt
    │   │   │   ├── nfc/
    │   │   │   │   ├── NfcReader.kt
    │   │   │   │   └── SunVerifier.kt
    │   │   │   ├── ravencoin/
    │   │   │   │   └── RpcClient.kt
    │   │   │   └── ui/
    │   │   │       ├── screens/
    │   │   │       └── theme/
    │   │   └── res/
    │   └── build.gradle.kts
    ├── build.gradle.kts
    └── settings.gradle.kts
```
