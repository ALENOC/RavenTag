# Technology Stack
> Generated: 2026-04-13 | Focus: tech | Repo: RavenTag

## Languages

**TypeScript (Backend)**
- Version: ^5.3.3 (compiled to ES2022 / CommonJS)
- Used in: `backend/src/**/*.ts`
- tsconfig target: ES2022, module: commonjs, strict: true

**TypeScript (Frontend)**
- Version: ^5 (Next.js managed)
- Used in: `frontend/src/**/*.ts`, `frontend/src/**/*.tsx`

**Kotlin (Android)**
- Version: 1.9.22
- JVM target: 17
- Used in: `android/app/src/main/java/io/raventag/app/**/*.kt`

## Runtimes

**Backend:**
- Node.js 20 (Alpine-based Docker image: `node:20-alpine`)
- Entry point: `backend/dist/index.js` (compiled from `backend/src/index.ts`)

**Frontend:**
- Node.js 20 (Alpine-based Docker image: `node:20-alpine`)
- Next.js standalone server (`node server.js`)

**Android:**
- Min SDK: 26 (Android 8.0)
- Target/Compile SDK: 35
- JVM: Java 17 compatibility

## Package Managers

**Backend & Frontend:**
- npm (lockfile: `package-lock.json` in both `backend/` and `frontend/`)

**Android:**
- Gradle 8.x (via Gradle Wrapper)
- Version catalog: `android/gradle/libs.versions.toml`
- AGP (Android Gradle Plugin): 8.7.3

## Backend Framework and Libraries

Source: `backend/package.json`

**Core Framework:**
- `express` ^4.18.2 - HTTP server

**Security / Middleware:**
- `helmet` ^7.1.0 - HTTP security headers
- `cors` ^2.8.5 - CORS middleware
- `express-rate-limit` ^8.3.0 - Rate limiting

**Validation:**
- `zod` ^3.22.4 - Runtime schema validation

**Database:**
- `better-sqlite3` ^9.4.3 - Synchronous SQLite driver

**HTTP Client:**
- `axios` ^1.6.7 - HTTP requests (IPFS gateway reads, Ravencoin RPC)

**File Upload:**
- `multer` ^2.1.1 - Multipart form data (IPFS image upload)
- `form-data` ^4.0.5 - FormData for multipart POSTs to Kubo API

**Environment:**
- `dotenv` ^16.4.1 - .env loading

**Dev Tools:**
- `tsx` ^4.7.0 - TypeScript execution for dev (`npm run dev`)
- `typescript` ^5.3.3 - Compiler
- `@types/node` ^20.11.5, `@types/express` ^4.17.21, etc.

## Frontend Framework and Libraries

Source: `frontend/package.json`

**Core Framework:**
- `next` 14.1.0 - Next.js (App Router + standalone output)
- `react` ^18 - UI library
- `react-dom` ^18

**UI:**
- `lucide-react` ^0.323.0 - Icon set
- `clsx` ^2.1.0 - Conditional class names
- `tailwindcss` ^3.3.0 (dev) - Utility CSS

**Analytics:**
- `@vercel/analytics` ^2.0.1 - Vercel analytics integration

**Build / Dev Tools:**
- `autoprefixer` ^10.0.1 - PostCSS plugin
- `postcss` ^8 - CSS processing
- `eslint` ^8 + `eslint-config-next` 14.1.0 - Linting
- `typescript` ^5 - Type checking
- `@types/react` ^18, `@types/react-dom` ^18, `@types/node` ^20

**Browser APIs Used (no npm package):**
- Web NFC API (`NDEFReader`) - NFC scanning in `frontend/src/components/NFCScanner.tsx`
- Web Crypto API (`SubtleCrypto`) - Client-side SUN verification in `frontend/src/lib/crypto.ts`

## Android Libraries

Source: `android/gradle/libs.versions.toml` and `android/app/build.gradle.kts`

**UI:**
- Jetpack Compose BOM 2024.02.00
- `androidx.compose.material3` (Material 3)
- `androidx.compose.material:material-icons-extended`
- `androidx.navigation:navigation-compose` 2.7.7
- `androidx.activity:activity-compose` 1.8.2
- `io.coil-kt:coil-compose` 2.6.0 - Async image loading

**Networking:**
- `com.squareup.retrofit2:retrofit` 2.9.0 - REST client
- `com.squareup.retrofit2:converter-gson` 2.9.0 - JSON converter
- `com.squareup.okhttp3:okhttp` 4.12.0 - HTTP client
- `com.squareup.okhttp3:logging-interceptor` 4.12.0 - HTTP logging
- `com.google.code.gson:gson` 2.10.1 - JSON parsing

**Cryptography:**
- `org.bouncycastle:bcprov-jdk15to18` 1.77 - AES-CMAC for NTAG 424 SUN verification + BIP32/BIP39 HD wallet (no external BIP library)

**NFC:**
- Android platform `NfcAdapter` (no external library)
- `android.nfc.tech.Ndef` for NDEF URL parsing

**QR Code:**
- `com.google.zxing:core` 3.5.3 - QR code decoding

**Camera:**
- `androidx.camera:camera-camera2` 1.3.4
- `androidx.camera:camera-lifecycle` 1.3.4
- `androidx.camera:camera-view` 1.3.4

**Security / Storage:**
- `androidx.security:security-crypto` 1.1.0-alpha06 - EncryptedSharedPreferences (wraps Android Keystore AES-GCM)
- `androidx.biometric:biometric` 1.1.0 - Biometric authentication

**Lifecycle / Async:**
- `androidx.lifecycle:lifecycle-runtime-ktx` 2.7.0
- `androidx.lifecycle:lifecycle-viewmodel-compose` 2.7.0
- `org.jetbrains.kotlinx:kotlinx-coroutines-android` 1.7.3

**Background Work:**
- `androidx.work:work-runtime-ktx` 2.9.1 - WorkManager

**UI Extras:**
- `androidx.core:core-splashscreen` 1.0.1 - Splash screen API

**Testing:**
- `junit:junit` 4.13.2
- `androidx.test.ext:junit` 1.1.5
- `androidx.test:runner` 1.5.2

## Build Tools

**Backend:**
- `tsc` (TypeScript compiler) - `npm run build` outputs to `backend/dist/`
- Multi-stage Dockerfile: builder stage compiles TS, runner stage uses `npm ci --omit=dev`

**Frontend:**
- `next build` - outputs Next.js standalone
- Multi-stage Dockerfile: deps stage installs packages, builder stage runs `next build`, runner copies `.next/standalone`

**Android:**
- Android Gradle Plugin 8.7.3
- Kotlin Gradle Plugin 1.9.22
- Compose Compiler Extension: 1.5.10
- ProGuard enabled for release builds (`proguard-rules.pro`)
- Two product flavors: `brand` (app ID: `io.raventag.app.brand`) and `consumer` (app ID: `io.raventag.app`)
- Release signing via `android/signing/signing.properties` (not committed)

## Configuration

**Backend env vars (from `docker-compose.yml`):**
- `PORT` (default: 3001)
- `DB_PATH` (default: `/data/raventag.db`)
- `RVN_RPC_HOST`, `RVN_RPC_PORT`, `RVN_RPC_USER`, `RVN_RPC_PASS`
- `RVN_PUBLIC_RPC_URL` (fallback public node, default: `https://rvn-rpc.publicnode.com`)
- `IPFS_GATEWAY` (default: `https://ipfs.io/ipfs/`)
- `CACHE_TTL_ASSET` (default: 300s), `CACHE_TTL_IPFS` (default: 3600s)
- `ALLOWED_ORIGINS` (CORS)
- `ANDROID_APP_FINGERPRINT` (SHA-256 of release APK signing cert)
- Secrets via Docker secrets files: `admin_key`, `operator_key`, `brand_master_key`, `brand_salt`

**Frontend env vars (from `frontend/.env.example`):**
- `NEXT_PUBLIC_APP_URL`
- `NEXT_PUBLIC_RVN_RPC_URL`
- `NEXT_PUBLIC_IPFS_GATEWAY`
- `NEXT_PUBLIC_BACKEND_URL`
- `NEXT_PUBLIC_PLAY_STORE_VERIFY_URL` (optional)

**Android BuildConfig fields (from `android/app/build.gradle.kts`):**
- `IPFS_GATEWAY` (default: `https://ipfs.io/ipfs/`)
- `IPFS_GATEWAYS` (comma-separated fallback list)
- `API_BASE_URL` (default: `https://api.raventag.com`)
- `ADMIN_KEY` (default: empty string; set for brand flavor)
- `IS_BRAND` (Boolean, true for brand flavor)

## CI/CD

**GitHub Actions workflows (`.github/workflows/`):**
- `qwen-invoke.yml`, `qwen-scheduled-triage.yml`, `qwen-review.yml`, `qwen-triage.yml`, `qwen-dispatch.yml` - Issue triage and review automation

**Docker:**
- `docker-compose.yml` - Orchestrates `backend` and `backup` services
- No frontend container in compose (frontend deployed separately, e.g., Vercel)
- Backup service: `alpine:3.19`, daily AES-256-CBC encrypted SQLite snapshots, 7-backup retention
