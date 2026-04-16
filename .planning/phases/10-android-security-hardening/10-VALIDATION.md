---
phase: 10
slug: android-security-hardening
status: draft
nyquist_compliant: true
wave_0_complete: true
created: 2026-04-13
---

# Phase 10 — Validation Strategy

> Per-phase validation contract for feedback sampling during execution.

---

## Test Infrastructure

| Property | Value |
|----------|-------|
| **Framework** | Android instrumentation tests + backend unit tests |
| **Config file** | android/app/build.gradle.kts (testInstrumentationRunner) |
| **Quick run command** | `./gradlew test` (backend) + `./gradlew connectedAndroidTest` (Android) |
| **Full suite command** | `./gradlew test && ./gradlew connectedAndroidTest` |
| **Estimated runtime** | ~120 seconds |

---

## Sampling Rate

- **After every task commit:** Run `./gradlew test` (backend) if backend files modified
- **After every task commit:** Run `./gradlew connectedAndroidTest` (Android) if Android files modified
- **After every plan wave:** Run `./gradlew test && ./gradlew connectedAndroidTest`
- **Before `/gsd-verify-work`:** Full suite must be green
- **Max feedback latency:** 120 seconds

---

## Per-Task Verification Map

| Task ID | Plan | Wave | Requirement | Threat Ref | Secure Behavior | Test Type | Automated Command | File Exists | Status |
|---------|------|------|-------------|------------|-----------------|-----------|-------------------|-------------|--------|
| 10-01-01 | 01 | 1 | ADMIN_KEY removal | T-10-01 | Admin key stored in EncryptedSharedPreferences, never in BuildConfig | unit | `./gradlew test --tests ".*AdminKeyStorageTest.*"` | ❌ W0 | ⬜ pending |
| 10-02-01 | 02 | 1 | TLS validation | T-10-02 | OkHttp client rejects invalid TLS certificates | integration | `./gradlew test --tests ".*ElectrumXClientTest.*"` | ❌ W0 | ⬜ pending |
| 10-02-02 | 02 | 1 | TOFU persistence | T-10-03 | Fingerprints stored in SQLite, survive app restart | integration | `./gradlew test --tests ".*TofuFingerprintPersistenceTest.*"` | ❌ W0 | ⬜ pending |
| 10-03-01 | 03 | 1 | SQL injection protection | T-10-04 | No SELECT * queries in admin endpoints | static_analysis | `grep -r "SELECT \*" backend/src/` | N/A | ⬜ pending |
| 10-04-01 | 04 | 1 | Logging verification | T-10-05 | derive-chip-key payload not logged | manual | *See Manual-Only Verifications* | N/A | ⬜ pending |

*Status: ⬜ pending · ✅ green · ❌ red · ⚠️ flaky*

---

## Wave 0 Requirements

**Note: Wave 0 test files will be created during execution via grep-based verification. No pre-existing test files are required.**

---

## Manual-Only Verifications

| Behavior | Requirement | Why Manual | Test Instructions |
|----------|-------------|------------|-------------------|
| Backend logging excludes derive-chip-key | REQ-10-05 | Cannot verify from static code analysis alone; requires runtime verification | 1. Start backend server with DEBUG logging enabled. 2. Make POST request to `/api/brand/derive-chip-key` with test payload. 3. Check server logs for tag_uid or chip_key values. 4. Verify only metadata (timestamp, status) is logged, not payload body. |

---

## Validation Sign-Off

- [x] All tasks have `<automated>` verify or Wave 0 dependencies
- [x] Sampling continuity: no 3 consecutive tasks without automated verify
- [x] Wave 0 covers all MISSING references
- [x] No watch-mode flags
- [x] Feedback latency < 120s
- [x] `nyquist_compliant: true` set in frontmatter

**Approval:** pending

---

*Phase: 10-android-security-hardening*
*Validation strategy: 2026-04-13*
