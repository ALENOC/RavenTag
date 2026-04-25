---
phase: 40
slug: asset-emission-ux
status: draft
nyquist_compliant: false
wave_0_complete: false
created: 2026-04-25
---

# Phase 40 — Validation Strategy

> Per-phase validation contract for feedback sampling during execution.

---

## Test Infrastructure

| Property | Value |
|----------|-------|
| **Framework** | JUnit 4 + Robolectric (Android project) |
| **Config file** | `android/app/build.gradle.kts` |
| **Quick run command** | `./gradlew :app:testDebugUnitTest -x lint` |
| **Full suite command** | `./gradlew :app:testDebugUnitTest` |
| **Estimated runtime** | ~60 seconds |

---

## Sampling Rate

- **After every task commit:** Run `./gradlew :app:testDebugUnitTest -x lint`
- **After every plan wave:** Run `./gradlew :app:testDebugUnitTest`
- **Before `/gsd-verify-work`:** Full suite must be green
- **Max feedback latency:** 120 seconds

---

## Per-Task Verification Map

| Task ID | Plan | Wave | Requirement | Threat Ref | Secure Behavior | Test Type | Automated Command | File Exists | Status |
|---------|------|------|-------------|------------|-----------------|-----------|-------------------|-------------|--------|
| 40-01-01 | 01 | 1 | D-01 | — | N/A | unit | `./gradlew :app:testDebugUnitTest --tests "*IssueErrorClassificationTest*"` | ❌ W0 | ⬜ pending |
| 40-01-02 | 01 | 1 | D-07 | — | N/A | unit | Reuse existing RetryUtils tests | ✅ | ⬜ pending |
| 40-01-03 | 01 | 1 | D-10 | — | N/A | unit | `./gradlew :app:testDebugUnitTest --tests "*ConfirmationTest*"` | ❌ W0 | ⬜ pending |

*Status: ⬜ pending · ✅ green · ❌ red · ⚠️ flaky*

---

## Wave 0 Requirements

- [ ] `android/app/src/test/java/io/raventag/app/IssueErrorClassificationTest.kt` — unit tests for `classifyIssuanceError` function (pure logic, no Android deps)
- [ ] `android/app/src/test/java/io/raventag/app/ConfirmationPollingTest.kt` — unit tests for confirmation tracking logic (pure logic)
- [ ] No UI test for composable step indicator — Jetpack Compose UI tests need Compose Test dependency, skip for Phase 40, manual verification only

---

## Manual-Only Verifications

| Behavior | Requirement | Why Manual | Test Instructions |
|----------|-------------|------------|-------------------|
| Multi-step progress indicator visual | D-05 | Compose UI test infrastructure not set up | Manual: submit each asset type, verify per-step progress shows correct labels and checkmarks |
| Tappable txid link | D-11 | Clickable link behavior needs device | Manual: after successful issuance, tap txid in result banner, verify browser opens at ravencoin.network/tx/{txid} |
| Combined Issue+Write NFC step | D-13 | NFC hardware required | Manual: issue unique token, verify NFC programming appears as distinct step with own progress |

---

## Validation Sign-Off

- [ ] All tasks have `<automated>` verify or Wave 0 dependencies
- [ ] Sampling continuity: no 3 consecutive tasks without automated verify
- [ ] Wave 0 covers all MISSING references
- [ ] No watch-mode flags
- [ ] Feedback latency < 120s
- [ ] `nyquist_compliant: true` set in frontmatter

**Approval:** pending
