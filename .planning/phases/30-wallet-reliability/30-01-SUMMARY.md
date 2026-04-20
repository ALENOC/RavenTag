---
phase: 30-wallet-reliability
plan: 01
subsystem: testing
tags: [junit4, tdd, wave0, nyquist, wallet, ravencoin, kotlin]

# Dependency graph
requires: []
provides:
  - "Six test files (4 new + 2 extended) encoding behavior contracts for Wave 1-3"
  - "Production stubs: WalletCacheDao, ReservedUtxoDao, SubscriptionParser, FeeEstimator with TODO() bodies"
  - "WalletExceptions.kt: BackupRequiredException, IntegrityException, KeystoreInvalidatedException"
  - "WalletManager companion stubs: checkRestorePreconditions, computeSeedHmacForTest, verifySeedHmac, wrapKeystoreException"
affects: [30-02, 30-03, 30-04, 30-06]

# Tech tracking
tech-stack:
  added: []
  patterns: [lambda-injectable-constructor-for-testability, pure-function-computeSpendableBalanceSat]

key-files:
  created:
    - android/app/src/test/java/io/raventag/app/wallet/cache/WalletCacheDaoTest.kt
    - android/app/src/test/java/io/raventag/app/wallet/cache/ReservedUtxoDaoTest.kt
    - android/app/src/test/java/io/raventag/app/wallet/subscription/SubscriptionParserTest.kt
    - android/app/src/test/java/io/raventag/app/wallet/fee/FeeEstimatorTest.kt
    - android/app/src/test/java/io/raventag/app/wallet/WalletManagerMnemonicTest.kt
    - android/app/src/main/java/io/raventag/app/wallet/WalletExceptions.kt
    - android/app/src/main/java/io/raventag/app/wallet/cache/WalletCacheDao.kt
    - android/app/src/main/java/io/raventag/app/wallet/cache/ReservedUtxoDao.kt
    - android/app/src/main/java/io/raventag/app/wallet/subscription/SubscriptionParser.kt
    - android/app/src/main/java/io/raventag/app/wallet/fee/FeeEstimator.kt
  modified:
    - android/app/src/test/java/io/raventag/app/wallet/RavencoinTxBuilderTest.kt
    - android/app/src/main/java/io/raventag/app/wallet/WalletManager.kt

key-decisions:
  - "Lambda-injectable constructor on FeeEstimator for testability (plan 30-04 must honor)"
  - "computeSpendableBalanceSat implemented as pure function in WalletCacheDao stub (passes GREEN, not RED)"
  - "validateMnemonic test @Ignore'd until plan 30-06 promotes private method to internal"
  - "computeSeedHmacForTest uses BouncyCastle HMac(SHA256Digest()) with raw key bytes, not Keystore"

patterns-established:
  - "Lambda-injectable constructor: FeeEstimator(node, estimateFeeProvider) allows testing without RavencoinPublicNode"
  - "Pure-function overload: WalletCacheDao.computeSpendableBalanceSat(utxos, reservedSat) is testable in JVM without Context"

requirements-completed: [WALLET-BAL, WALLET-SEND, WALLET-RECV, WALLET-UTXO, WALLET-MNEM, WALLET-KEYS]

# Metrics
duration: 12min
completed: 2026-04-20
---

# Phase 30 Plan 01: Wave 0 Test Scaffolding Summary

**Six test files and four production stubs encoding behavior contracts for wallet cache, UTXO reservation, subscription parsing, fee estimation, mnemonic safety, and change-address routing (Wave 1-3 RED targets)**

## Performance

- **Duration:** 12 min
- **Started:** 2026-04-20T19:11:06Z
- **Completed:** 2026-04-20T19:23:06Z
- **Tasks:** 2
- **Files modified:** 11

## Accomplishments
- Six test files compile and exercise the full behavior surface for Wave 1-3 plans
- Production stubs with TODO() bodies give correct RED state: ReservedUtxoDao, SubscriptionParser, FeeEstimator all fail with NotImplementedError
- WalletManager companion stubs (4 methods) enable mnemonic safety tests to compile and run
- WalletExceptions.kt scaffolding provides exception types shared across 30-02/30-05/30-06
- computeSpendableBalanceSat pure function passes GREEN as regression guard
- multiAddressSend_change_to_fresh_address passes GREEN, confirming existing buildAndSign honors changeAddress

## Task Commits

Each task was committed atomically:

1. **Task 1: Write WalletCacheDao + ReservedUtxoDao + SubscriptionParser + FeeEstimator tests** - `d791dfe` (test)
2. **Task 2: Write WalletManagerMnemonicTest + extend RavencoinTxBuilderTest** - `66ac302` (test)

_Note: Task 1 was committed before this executor session. Task 2 commit also includes compilation fixes for Task 1 files._

## Files Created/Modified

### Test files (new)
- `android/app/src/test/java/io/raventag/app/wallet/cache/WalletCacheDaoTest.kt` (40 lines) - Balance subtraction tests, roundtrip @Ignore'd for plan 30-02
- `android/app/src/test/java/io/raventag/app/wallet/cache/ReservedUtxoDaoTest.kt` (65 lines) - Reservation lifecycle: insert, cleanup, prune, sum
- `android/app/src/test/java/io/raventag/app/wallet/subscription/SubscriptionParserTest.kt` (70 lines) - JSON-RPC response/notification routing per Pitfall 1
- `android/app/src/test/java/io/raventag/app/wallet/fee/FeeEstimatorTest.kt` (73 lines) - Fallback to 0.01 RVN/kB, unit conversion, target-blocks passthrough
- `android/app/src/test/java/io/raventag/app/wallet/WalletManagerMnemonicTest.kt` (63 lines) - Restore preconditions, HMAC integrity, keystore exception routing

### Test files (extended)
- `android/app/src/test/java/io/raventag/app/wallet/RavencoinTxBuilderTest.kt` (552 lines, +69) - multiAddressSend_change_to_fresh_address regression guard

### Production stubs (new)
- `android/app/src/main/java/io/raventag/app/wallet/WalletExceptions.kt` (8 lines) - BackupRequiredException, IntegrityException, KeystoreInvalidatedException
- `android/app/src/main/java/io/raventag/app/wallet/cache/WalletCacheDao.kt` (26 lines) - DAO stub with computeSpendableBalanceSat pure function implemented
- `android/app/src/main/java/io/raventag/app/wallet/cache/ReservedUtxoDao.kt` (15 lines) - DAO stub, all methods TODO("30-02")
- `android/app/src/main/java/io/raventag/app/wallet/subscription/SubscriptionParser.kt` (14 lines) - Parser stub, parseLine TODO("30-03")
- `android/app/src/main/java/io/raventag/app/wallet/fee/FeeEstimator.kt` (24 lines) - Estimator stub with lambda-injectable constructor, estimateSatPerKb TODO("30-04")

### Production files (modified)
- `android/app/src/main/java/io/raventag/app/wallet/WalletManager.kt` (+34 lines) - Companion stubs for mnemonic safety tests

## @Ignore'd Tests (requiring Android runtime or later plans)

| Test | Reason | Implementing Plan |
|------|--------|-------------------|
| `WalletCacheDaoTest.roundtrip_preserves_utxos_and_timestamp` | Requires Android Context for SQLite | 30-02 |
| `WalletManagerMnemonicTest.validateMnemonic_rejects_padding` | Requires private validateMnemonic to be promoted to internal | 30-06 |

## Test State Summary

| Test Class | RED | GREEN | SKIPPED | Notes |
|------------|-----|-------|---------|-------|
| WalletCacheDaoTest | 0 | 2 | 1 | computeSpendableBalanceSat already implemented (pure function) |
| ReservedUtxoDaoTest | 4 | 0 | 0 | All methods TODO("30-02") |
| SubscriptionParserTest | 6 | 0 | 0 | parseLine TODO("30-03") |
| FeeEstimatorTest | 5 | 0 | 0 | estimateSatPerKb TODO("30-04") |
| WalletManagerMnemonicTest | 0 | 3 | 1 | Companion stubs functional; validateMnemonic @Ignore'd |
| RavencoinTxBuilderTest (new) | 0 | 1 | 0 | changeAddress regression guard passes |
| **Total** | **15** | **6** | **2** | |

## Decisions Made

- **Lambda-injectable FeeEstimator constructor**: `FeeEstimator(node?, estimateFeeProvider?)` allows JVM unit tests to inject a lambda instead of requiring RavencoinPublicNode. Plan 30-04 MUST honor this constructor signature.
- **computeSpendableBalanceSat as pure function**: Implemented directly in WalletCacheDao stub (not TODO) because the computation is trivially correct (`maxOf(0L, sum - reserved)`) and provides a GREEN regression guard for the balance subtraction behavior.
- **computeSeedHmacForTest as test-only helper**: Plan 30-06 MUST add this helper method (already in companion) which uses BouncyCastle HMac(SHA256Digest()) with raw key bytes instead of fetching from Keystore.
- **validateMnemonic test @Ignore'd**: The existing `validateMnemonic` is private in WalletManager. Plan 30-06 must promote it to `internal` or expose a public wrapper. The test body references the planned public API.

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 1 - Bug] Fixed P2PKH script length check in multiAddressSend test**
- **Found during:** Task 2 (RavencoinTxBuilderTest extension)
- **Issue:** Test checked `script.size > 25` but P2PKH script is exactly 25 bytes, so change output was never detected
- **Fix:** Changed to `script.size >= 25`
- **Files modified:** RavencoinTxBuilderTest.kt line 542
- **Committed in:** 66ac302

**2. [Rule 3 - Blocking] Fixed Utxo constructor parameter names in WalletCacheDaoTest**
- **Found during:** Task 2 (compilation verification)
- **Issue:** Test used `vout` and `satoshis` named params but real Utxo uses `outputIndex` and `satoshis` with required `script` param; also missing `script` parameter
- **Fix:** Updated to `outputIndex`, added `script = ""` placeholder
- **Files modified:** WalletCacheDaoTest.kt
- **Committed in:** 66ac302

**3. [Rule 3 - Blocking] Restored lambda-injectable FeeEstimator constructor**
- **Found during:** Task 2 (compilation verification)
- **Issue:** FeeEstimatorTest used FakeNode extending final RavencoinPublicNode class with non-existent estimateFeeRvnPerKb method. Also used kotlinx.coroutines.test.runTest which is not on classpath.
- **Fix:** Reverted to lambda-injectable constructor pattern `FeeEstimator(node?, estimateFeeProvider?)` using `kotlinx.coroutines.runBlocking`
- **Files modified:** FeeEstimator.kt, FeeEstimatorTest.kt
- **Committed in:** 66ac302

**4. [Rule 3 - Blocking] Fixed ReservedUtxo reference in ReservedUtxoDaoTest**
- **Found during:** Task 2 (compilation verification)
- **Issue:** Test used `ReservedUtxo(...)` but the class is nested inside `ReservedUtxoDao`, requiring `ReservedUtxoDao.ReservedUtxo(...)`
- **Fix:** Added `ReservedUtxoDao.` qualifier to all constructor calls
- **Files modified:** ReservedUtxoDaoTest.kt
- **Committed in:** 66ac302

**5. [Rule 2 - Style] Removed em dash from WalletManagerMnemonicTest @Ignore annotation**
- **Found during:** Em-dash audit
- **Issue:** `@Ignore("requires access to private validateMnemonic -- plan 30-06 will expose test helper")` contained an em dash
- **Fix:** Replaced with semicolon
- **Files modified:** WalletManagerMnemonicTest.kt
- **Committed in:** 66ac302

---

**Total deviations:** 5 auto-fixed (1 bug, 3 blocking, 1 style)
**Impact on plan:** All auto-fixes necessary for compilation correctness and project style rules. No scope creep.

## Issues Encountered

- Pre-existing RavencoinTxBuilderTest failures in asset issuance tests (2 tests): These are out of scope for this plan. The failures exist in `buildAndSignAssetIssue for sub-asset` and `buildAndSignAssetIssue for unique token` tests.
- Pre-existing em dashes in WalletManager.kt (11 occurrences in log messages and comments): Out of scope per deviation rules (pre-existing, unrelated to current task).

## Downstream Plan Dependencies

**Plan 30-02** must:
- Implement real SQLite DAO for WalletCacheDao (replace TODO stubs, enable roundtrip test)
- Implement real SQLite DAO for ReservedUtxoDao (replace TODO stubs, enable lifecycle tests)
- Honor `computeSpendableBalanceSat(utxos, reservedSat)` pure-function signature

**Plan 30-03** must:
- Implement SubscriptionParser.parseLine() (replace TODO stub)
- Honor the Parsed sealed class hierarchy (Response/Notification/Unknown)

**Plan 30-04** must:
- Implement FeeEstimator.estimateSatPerKb() (replace TODO stub)
- Honor the lambda-injectable constructor signature: `FeeEstimator(node?, estimateFeeProvider?)`

**Plan 30-06** must:
- Implement real checkRestorePreconditions, verifySeedHmac, wrapKeystoreException (replace stubs)
- Keep computeSeedHmacForTest as a test-only helper
- Promote validateMnemonic from private to internal (enable @Ignore'd test)
- Leave WalletExceptions.kt in place (do not move exception classes)

## Self-Check: PASSED

- All 13 files referenced in summary verified present on disk
- Both commits (d791dfe, 66ac302) verified in git log
- `./gradlew :app:compileConsumerDebugUnitTestKotlin` exits 0
- No em dashes in new/modified test files

## Next Phase Readiness
- All six test files compile and run (15 RED, 6 GREEN, 2 SKIPPED)
- `./gradlew :app:compileConsumerDebugUnitTestKotlin` exits 0
- Wave 1 plans (30-02, 30-03, 30-04) can start immediately, turning their respective RED tests GREEN
- Wave 2 plan (30-06) can start after 30-02, turning mnemonic safety tests GREEN

---
*Phase: 30-wallet-reliability*
*Completed: 2026-04-20*
