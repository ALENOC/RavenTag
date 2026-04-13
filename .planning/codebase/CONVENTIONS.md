# Coding Conventions

**Analysis Date:** 2026-04-13

## Naming Patterns

**Files:**
- TypeScript backend: `camelCase.ts` (e.g., `ntag424.ts`, `ravencoin.ts`, `cache.ts`)
- TypeScript frontend components: `PascalCase.tsx` (e.g., `CookieBanner.tsx`, `LanguageSelector.tsx`)
- Next.js pages: `page.tsx` in directory-named routes (App Router pattern)
- Kotlin Android: `PascalCase.kt` (e.g., `SunVerifier.kt`, `WalletManager.kt`, `NfcReader.kt`)
- Test files (Android): `PascalCaseTest.kt` co-located under `src/test/` mirroring `src/main/` package path

**Functions:**
- TypeScript: `camelCase` for all exported and internal functions (`computeNfcPubId`, `verifySunMessage`, `requireAdminKey`, `isAssetRevoked`)
- Kotlin: `camelCase` methods inside `object` singletons or classes; top-level helpers also `camelCase`
- React components: `PascalCase` default exports matching the filename (`export default function HomePage()`, `export default function AdminPage()`)

**Variables:**
- TypeScript/JS: `camelCase` (`tagUid`, `sdmMacKey`, `adminKey`, `sessionMacKey`)
- Kotlin: `camelCase` (`sdmEncKey`, `sdmMacKey`, `tagUid`, `testPrivKey`)
- Constants: `SCREAMING_SNAKE_CASE` for module-level config values (`BURN_ROOT_SAT`, `DB_PATH`, `API`, `LIMIT`)

**Types / Interfaces:**
- TypeScript: `PascalCase` interfaces and type aliases (`SunVerifyResult`, `RegisteredBrand`, `RevokedAsset`)
- Exported schemas from Zod: `camelCaseSchema` naming (`assetNameSchema`, `sunVerifyRequestSchema`)
- Inferred TypeScript types from Zod: `PascalCase` (`type AssetName = z.infer<typeof assetNameSchema>`)
- Kotlin data classes: `PascalCase` (`SunVerifyResult`, `Utxo`)

## Code Style

**Formatting:**
- No Prettier config present; formatting is implicit from TypeScript compiler strictness
- Indentation: 2 spaces in TypeScript/TSX throughout backend and frontend
- Indentation: 4 spaces in Kotlin
- Single quotes for string literals in TypeScript; double quotes in Kotlin and JSX attributes
- Trailing commas used in multi-line TypeScript object/function argument lists

**Linting:**
- Backend: ESLint via `"lint": "eslint src --ext .ts"` in `backend/package.json`; no config file detected (uses defaults)
- Frontend: Next.js built-in ESLint via `"lint": "next lint"`
- TypeScript strict mode enabled in `backend/tsconfig.json` (`"strict": true`)
- `esModuleInterop: true`, `forceConsistentCasingInFileNames: true`, `skipLibCheck: true`

## Import Organization

**TypeScript backend order (observed pattern):**
1. Node built-ins (`crypto`, `path`)
2. Third-party packages (`express`, `better-sqlite3`, `zod`, `axios`)
3. Internal utils (`../utils/crypto.js`, `../utils/validation.js`)
4. Internal services (`../services/ntag424.js`, `../services/ravencoin.js`)
5. Internal middleware (`../middleware/cache.js`, `../middleware/auth.js`)

Note: Imports use `.js` extension on internal modules even for `.ts` source files (TypeScript `module: commonjs` + `esModuleInterop` convention).

**TypeScript frontend order (observed pattern):**
1. Next.js/React imports (`next/navigation`, `react`, `next/image`, `next/link`)
2. Third-party UI (`lucide-react`)
3. Internal lib (`@/lib/i18n`, `@/lib/ravencoin`)
4. Internal components (`@/components/CookieBanner`, `@/components/GooglePlayBadge`)

**Path Aliases (frontend):**
- `@/` maps to `src/` (standard Next.js alias)

## Error Handling

**Backend pattern:**
- Express route handlers use Zod `safeParse` for input validation; on failure return HTTP 400 with Zod issue details
- Crypto operations that can fail (wrong key, bad format) throw `Error` with descriptive messages; callers wrap in `try/catch` and return `{ valid: false, error: message }` rather than propagating throws
- Middleware returns early with `res.status(N).json({ error, code })` and `return` to stop execution; no `next(err)` used
- Services return typed result objects (`SunVerifyResult`) with `valid` flag instead of throwing on expected failures

**Frontend pattern:**
- `fetch` calls are wrapped in `.then(r => r.ok ? r.json() : null).catch(() => {})` for non-critical UI data
- State for user-facing messages uses `{ ok: boolean; text: string } | null` pattern (e.g., `revokeMsg`, `brandMsg`)

**Android pattern:**
- Kotlin functions return `SunVerifyResult` with `valid: Boolean` and optional `error: String?`; no exceptions thrown to UI layer
- `IllegalArgumentException` thrown for programmer errors (bad address checksum, insufficient funds) that the caller must guard against

## Logging

**Framework:** `console` (no structured logger)

**Patterns:**
- No logging calls observed in reviewed source files
- Security-sensitive paths (auth checks, crypto) rely on HTTP status codes and response bodies rather than server logs
- Android: no logging framework calls seen in reviewed files

## Comments

**When to Comment:**
- File-level JSDoc block on every module explaining purpose, cryptographic context, and security notes (all reviewed files follow this pattern)
- Function-level JSDoc on every exported function with `@param`, `@returns`, and inline security/protocol notes
- Inline comments explain non-obvious algorithmic steps (e.g., RFC 4493 CMAC steps, NXP AN12196 table references)
- No commented-out code observed

**JSDoc/TSDoc:**
- All exported TypeScript functions have full JSDoc (`/** ... */`) with `@param`, `@returns`
- Internal (non-exported) helper functions have shorter docstrings or inline comments
- Kotlin: KDoc (`/** ... */`) on exported `object` methods and `data class` properties

## Function Design

**Size:** Functions are small and single-purpose; multi-step pipelines (e.g., `verifySunMessage`) delegate to focused helpers (`decryptSunData`, `deriveSessionMacKey`, `verifySunMac`)

**Parameters:** Prefer explicit named parameters over option objects for pure functions; Kotlin named arguments used in test call sites for clarity

**Return Values:**
- Pure crypto functions return `Buffer` (Node.js) or `ByteArray` (Kotlin)
- Verification functions return typed result objects (`SunVerifyResult`) with `valid` boolean
- Express middleware returns `void`, communicates via `res.json()` + `return`
- Never return `null` from functions that have a meaningful failure mode; use the result-object pattern instead

## Module Design

**Exports (TypeScript backend):**
- Named exports only (`export function`, `export interface`, `export const`)
- No default exports in backend; all imports are destructured
- Re-exports used deliberately to provide a clean service API: `ntag424.ts` re-exports `deriveTagKey`/`deriveTagKeys` from `crypto.ts` so routes only import from the service layer

**Exports (TypeScript frontend):**
- React pages: single `export default function PascalCasePage()`
- Library files (`lib/`): named exports
- Components: named exports for sub-components, default export for the primary component

**Barrel Files:**
- Not used; each file is imported directly by its consumers

## Security Conventions

- All key comparisons use constant-time equality (`timingSafeEqual` in Node.js, XOR-accumulate loop in crypto utils and Kotlin)
- Secrets are read from environment variables only; never hardcoded
- AES-128-CBC called with `setAutoPadding(false)`; callers are responsible for correct block alignment
- Zod schemas are the single validation gate for all API inputs; no manual string-parsing of untrusted input

---

*Convention analysis: 2026-04-13*
