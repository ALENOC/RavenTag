---
phase: 50
slug: backend-stability
status: draft
nyquist_compliant: false
wave_0_complete: false
created: 2026-04-25
---

# Phase 50 — Validation Strategy

> Per-phase validation contract for feedback sampling during execution.

---

## Test Infrastructure

| Property | Value |
|----------|-------|
| **Framework** | none — backend has no test suite (deferred per CONTEXT.md) |
| **Config file** | none |
| **Quick run command** | `npm run build` (TypeScript compilation) |
| **Full suite command** | `npm run build` + manual API smoke tests |
| **Estimated runtime** | ~5 seconds (build only) |

---

## Sampling Rate

- **After every task commit:** Run `cd backend && npm run build`
- **After every plan wave:** Manual API smoke test (curl health, hierarchy, revocation)
- **Before `/gsd-verify-work`:** Build passes + manual smoke tests complete
- **Max feedback latency:** 10 seconds

---

## Per-Task Verification Map

| Task ID | Plan | Wave | Requirement | Threat Ref | Secure Behavior | Test Type | Automated Command | File Exists | Status |
|---------|------|------|-------------|------------|-----------------|-----------|-------------------|-------------|--------|
| 50-01-01 | 01 | 1 | unhandledRejection | N/A | Graceful shutdown on unhandled rejection | manual | `cd backend && npm run build` | ❌ W0 | ⬜ pending |
| 50-01-02 | 01 | 1 | parallel hierarchy | N/A | Partial results on branch failure | manual | `cd backend && npm run build` | ❌ W0 | ⬜ pending |
| 50-02-01 | 02 | 2 | listassets pagination | N/A | Backward-compatible envelope | manual | `cd backend && npm run build` | ❌ W0 | ⬜ pending |
| 50-03-01 | 03 | 3 | request_logs cleanup | N/A | nfc_counters NEVER cleaned | manual | `cd backend && npm run build` | ❌ W0 | ⬜ pending |
| 50-04-01 | 04 | 4 | SQLite backup | N/A | .backup() API under WAL | manual | `cd backend && npm run build` | ❌ W0 | ⬜ pending |
| 50-05-01 | 05 | 5 | CLI explorer | N/A | Read-only mode enforced | manual | `cd backend && npm run build` | ❌ W0 | ⬜ pending |

*Status: ⬜ pending · ✅ green · ❌ red · ⚠️ flaky*

---

## Wave 0 Requirements

- [ ] `backend/` TypeScript compilation succeeds (`npm run build`)
- [ ] No test framework installed — test suite is deferred (CONTEXT.md Deferred Ideas)

*Minimal Wave 0: build check only. Backend has no test suite.*

---

## Manual-Only Verifications

| Behavior | Requirement | Why Manual | Test Instructions |
|----------|-------------|------------|-------------------|
| Unhandled rejection graceful shutdown | unhandledRejection | Process-level handler — no test framework | Trigger `Promise.reject('test')` without catch, verify log output and exit code 1 |
| Partial hierarchy with errors | parallel hierarchy | Requires live/subset RPC or mock | Call hierarchy with known bad sub-asset, verify partial:true + errors array |
| Pagination envelope | listassets pagination | Requires >200 sub-assets or mock | Call hierarchy with limit=10&offset=0, verify hasMore and metadata |
| Cleanup preserves nfc_counters | request_logs cleanup | Anti-replay safety — must verify manually | Check nfc_counters row count before/after cleanup run |
| Backup consistency under writes | SQLite backup | Requires concurrent write simulation | Insert rows during backup, verify .enc file decrypts correctly |
| CLI read-only enforcement | CLI explorer | Safety check | Attempt `.assets` and verify no write operations available |

---

## Validation Sign-Off

- [ ] All tasks have `<automated>` verify or Wave 0 dependencies
- [ ] Sampling continuity: no 3 consecutive tasks without automated verify
- [ ] Wave 0 covers all MISSING references
- [ ] No watch-mode flags
- [ ] Feedback latency < 10s
- [ ] `nyquist_compliant: true` set in frontmatter

**Approval:** pending
