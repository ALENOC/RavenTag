---
phase: 10-android-security-hardening
plan: 02
subsystem: security
tags: [sqlite, tls, tofu, certificate-pinning, android-keystore]

# Dependency graph
requires:
  - phase: 10-01
    provides: Android Keystore-protected storage patterns, Context access patterns
provides:
  - SQLite-based TOFU certificate fingerprint persistence
  - Dual-layer TOFU cache (L1 in-memory + L2 SQLite)
  - MITM protection across app restarts
affects: [wallet, ravencoin, rpc-client, polling-worker]

# Tech tracking
tech-stack:
  added: [SQLiteOpenHelper, ContentValues, X509Certificate]
  patterns: [dual-layer caching, synchronized initialization, TOFU certificate pinning]

key-files:
  created:
    - android/app/src/main/java/io/raventag/app/security/TofuFingerprintDao.kt
  modified:
    - android/app/src/main/java/io/raventag/app/wallet/RavencoinPublicNode.kt
    - android/app/src/main/java/io/raventag/app/MainActivity.kt
    - android/app/src/main/java/io/raventag/app/ravencoin/RpcClient.kt
    - android/app/src/main/java/io/raventag/app/wallet/WalletManager.kt
    - android/app/src/main/java/io/raventag/app/worker/WalletPollingWorker.kt

key-decisions:
  - "Dual-layer cache: L1 in-memory ConcurrentHashMap for fast access, L2 SQLite for persistence across restarts"
  - "Context parameter added to RavencoinPublicNode constructor to enable SQLite initialization"
  - "Certificate mismatch throws explicit Exception with expected/got fingerprint for forensic analysis"

patterns-established:
  - "Pattern: SQLiteOpenHelper with singleton object and synchronized lazy initialization"
  - "Pattern: Dual-layer caching with persistent fallback (L1 → L2)"
  - "Pattern: API-breaking constructor change (adding Context parameter) for security feature integration"

requirements-completed:
  - tls-tofu

# Metrics
duration: ~26min
completed: 2026-04-13
---

# Phase 10: TOFU Certificate Fingerprint Persistence Summary

**SQLite-based TOFU certificate fingerprint persistence with dual-layer cache (L1 in-memory + L2 SQLite), closing MITM attack window across app restarts**

## Performance

- **Duration:** ~26 minutes
- **Started:** 2026-04-13T14:46:54Z
- **Completed:** 2026-04-13T15:12:54Z
- **Tasks:** 3
- **Files modified:** 6

## Accomplishments

- Created TofuFingerprintDao.kt with SQLiteOpenHelper pattern for persistent certificate storage
- Implemented dual-layer TOFU cache: L1 (in-memory ConcurrentHashMap) + L2 (SQLite persistent)
- Updated TofuTrustManager to check SQLite-persisted fingerprints first, then in-memory cache
- Persist new fingerprints to SQLite database on first connection to ElectrumX host
- Updated all 15 RavencoinPublicNode instantiation sites across 5 files to pass Context parameter
- Verified TLS certificate validation enabled (no hostnameVerifier override, no trustAllCerts)

## Task Commits

Each task was committed atomically:

1. **Task 1: Create TofuFingerprintDao for SQLite persistence** - `40494a6` (feat)
2. **Task 2: Update TofuTrustManager to use SQLite persistence** - `d2d90fc` (feat)
3. **Task 3: Verify TLS rejectUnauthorized is enabled** - `d2d90fc` (feat, combined with Task 2)

**Plan metadata:** Plan created in phase 10 setup

## Files Created/Modified

- `android/app/src/main/java/io/raventag/app/security/TofuFingerprintDao.kt` - SQLite DAO for persistent TOFU certificate fingerprints with init, getFingerprint, pinFingerprint, and clearFingerprints methods
- `android/app/src/main/java/io/raventag/app/wallet/RavencoinPublicNode.kt` - Updated TofuTrustManager to initialize TofuFingerprintDao, implement dual-layer cache, and check SQLite-persisted fingerprints first
- `android/app/src/main/java/io/raventag/app/MainActivity.kt` - Updated RavencoinPublicNode instantiation to pass Context
- `android/app/src/main/java/io/raventag/app/ravencoin/RpcClient.kt` - Updated RavencoinPublicNode instantiation to pass Context
- `android/app/src/main/java/io/raventag/app/wallet/WalletManager.kt` - Updated RavencoinPublicNode instantiation to pass Context
- `android/app/src/main/java/io/raventag/app/worker/WalletPollingWorker.kt` - Updated RavencoinPublicNode instantiation to pass Context

## Decisions Made

- **Dual-layer cache design**: L1 in-memory ConcurrentHashMap provides fast access for ongoing connections, L2 SQLite ensures persistence across app restarts. This balances performance with security.
- **Context parameter for RavencoinPublicNode**: Required API-breaking change to enable SQLiteOpenHelper initialization. All 15 instantiation sites updated to pass Context.
- **Certificate mismatch handling**: Explicit Exception with expected vs got fingerprint for forensic analysis and user feedback.
- **CONFLICT_REPLACE on insert**: Simplifies certificate rotation scenario - new fingerprint replaces old one, allowing legitimate server cert updates after manual user intervention.

## Deviations from Plan

None - plan executed exactly as written. All 3 tasks completed successfully with no auto-fixes or issues encountered.

## Issues Encountered

None - implementation proceeded smoothly following the plan specifications.

## User Setup Required

None - no external service configuration required. SQLite database is created automatically on first app launch.

## Next Phase Readiness

- TOFU certificate fingerprint persistence complete and ready for Phase 20 (Android Performance Optimization)
- No blockers or concerns
- Certificate fingerprints now survive app restarts, closing the MITM attack window

---
*Phase: 10-android-security-hardening*
*Completed: 2026-04-13*
