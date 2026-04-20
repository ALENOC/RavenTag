---
gsd_state_version: 1.0
milestone: v1.0
milestone_name: milestone
status: executing
stopped_at: Plan 30-01 complete
last_updated: "2026-04-20T19:23:06Z"
last_activity: 2026-04-20 -- Plan 30-01 (Wave 0 test scaffolding) executed
progress:
  total_phases: 5
  completed_phases: 2
  total_plans: 20
  completed_plans: 11
  percent: 55
---

# Project State

## Project Reference

**Building:** Framework open-source trustless (RTP-1) che collega tag NFC NTAG 424 DNA ad asset Ravencoin.

**Core Value:** La catena crittografica da chip NFC a blockchain deve essere sicura e reattiva.

**Current Milestone Focus:** Security hardening, Android performance, wallet reliability.

## Current Position

Phase: 30 (wallet-reliability) — EXECUTING
Plan: 1 of 10 complete
Status: Plan 30-01 complete, ready for 30-02
Last activity: 2026-04-20 -- Plan 30-01 (Wave 0 test scaffolding) executed

## Progress

`[██████████] 100%`: Phase 20 complete
`[█         ] 10%`: Phase 30 plan 1/10 complete

## Recent Decisions

| Decision | Outcome |
|----------|---------|
| Fix sicurezza prima di performance | Pending |
| Focus Android su suspend functions | Complete (20-01) |
| Persistere TOFU fingerprint in SQLite | Pending |
| Rimuovere BuildConfig.ADMIN_KEY | Pending |
| Lambda-injectable FeeEstimator constructor | Complete (30-01) |
| computeSpendableBalanceSat as pure function | Complete (30-01) |

## Pending Todos

- Execute plan 30-02 (Wallet Cache DB DAOs)
- Execute plan 30-03 (Scripthash Subscription)
- Execute plans 30-04 through 30-10

## Blockers / Concerns

- `consolidate_fix.kt` untracked file in project root (possible WIP)
- Pre-existing RavencoinTxBuilderTest failures in 2 asset issuance tests (out of scope)

## Session Continuity

Last session: 2026-04-20T19:23:06Z
Stopped at: Plan 30-01 complete
Resume file: .planning/phases/30-wallet-reliability/30-01-SUMMARY.md
Next action: Execute plan 30-02 (Wallet Cache DB DAOs)
