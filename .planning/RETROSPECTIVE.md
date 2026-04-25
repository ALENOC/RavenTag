# Project Retrospective

*A living document updated after each milestone. Lessons feed forward into future planning.*

## Milestone: v1.0 — Security, Performance & Reliability

**Shipped:** 2026-04-26
**Phases:** 5 | **Plans:** 30 | **Sessions:** ~15

### What Was Built
- Android security hardening: admin key encryption, TLS+TOFU, SELECT * fix, log sanitization
- Android performance: suspend functions, parallel wallet restore (~3x), retry-with-backoff
- Wallet reliability: UTXO reservation, scripthash subscription, fee estimation, mnemonic safety
- Asset emission UX: 8-category error classification, multi-step progress, confirmation polling
- Backend stability: process-level error handlers, pagination, retention cleanup, backup .backup() API

### What Worked
- Wave-based execution (Wave 0 test scaffolding first) produced reliable interfaces before implementation
- Pure-function extraction for testability (FeeEstimator, TxHistoryMath) eliminated mocking pain
- Explicit dependency injection via constructor — no DI framework overhead, testable and traced
- Security-first phase ordering caught vulnerabilities before performance work built on insecure foundation

### What Was Inefficient
- Wave 0 test scaffolding sometimes over-produced stubs that evolved significantly in later waves
- Some plans (30-04, 30-05) had circular dependency discovery during execution — better pre-flight analysis needed
- Em-dash ban required post-hoc cleanup across 24 files — should be prevented at write time

### Patterns Established
- Lambda-injectable constructors for pure-function testability (no mocking framework)
- TOFU fingerprint persistence in SQLite with 1h quarantine window
- BiometricPrompt bound via CryptoObject, not boolean callback
- CharArray zero-fill with space (0x20) per project decision D-16
- Atomic git commits per plan with feat() Conventional Commits format

### Key Lessons
1. Security hardening before performance avoids building on insecure foundation
2. Pure function extraction at Wave 0 pays off in every subsequent wave — no mock churn
3. TOFU fingerprint SQLite persistence is essential for mobile (in-memory Map resets on restart)
4. suspendCancellableCoroutine is the right bridge for callback-based OkHttp → coroutines
5. Chunked Promise.allSettled prevents connection exhaustion in parallel asset hierarchy queries

### Cost Observations
- Model mix: ~60% sonnet, ~30% opus, ~10% haiku (estimated)
- Sessions: ~15 sessions across 31 days
- Notable: Phase 30 (10 plans) was largest and most complex; Wave 0 scaffolding saved significant debugging time

---

## Cross-Milestone Trends

### Process Evolution

| Milestone | Sessions | Phases | Key Change |
|-----------|----------|--------|------------|
| v1.0 | ~15 | 5 | First milestone — established patterns, wave execution, security-first ordering |

### Cumulative Quality

| Milestone | Tests | Zero-Dep Additions |
|-----------|-------|-------------------|
| v1.0 | ~60 (JUnit4 + behavior contracts) | 0 external deps added to Android |

### Top Lessons (Verified Across Milestones)

1. Wave 0 test scaffolding with behavior contracts catches interface problems before implementation
2. Security-first phase ordering reduces rework (fix foundation before building on it)
3. Pure function extraction for business logic eliminates Android framework coupling in tests
