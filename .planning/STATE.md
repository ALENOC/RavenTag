---
gsd_state_version: 1.0
milestone: v1.0
milestone_name: milestone
status: executing
stopped_at: Completed 30-04-fee-estimation
last_updated: "2026-04-21T05:36:02.447Z"
last_activity: 2026-04-21
progress:
  total_phases: 5
  completed_phases: 2
  total_plans: 20
  completed_plans: 13
  percent: 65
---

# Project State

## Project Reference

**Building:** Framework open-source trustless (RTP-1) che collega tag NFC NTAG 424 DNA ad asset Ravencoin.

**Core Value:** La catena crittografica da chip NFC a blockchain deve essere sicura e reattiva.

**Current Milestone Focus:** Security hardening, Android performance, wallet reliability.

## Current Position

Phase: 30 (wallet-reliability) — EXECUTING
Plan: 3 of 10 complete
Status: Ready to execute
Last activity: 2026-04-21

## Progress

`[██████████] 100%`: Phase 20 complete
`[██        ] 20%`: Phase 30 plan 2/10 complete

## Recent Decisions

| Decision | Outcome |
|----------|---------|
| Fix sicurezza prima di performance | Pending |
| Focus Android su suspend functions | Complete (20-01) |
| Persistere TOFU fingerprint in SQLite | Pending |
| Rimuovere BuildConfig.ADMIN_KEY | Pending |
| Lambda-injectable FeeEstimator constructor | Complete (30-01) |
| computeSpendableBalanceSat as pure function | Complete (30-01) |
| All five tables co-located in wallet_reliability.db | Complete (30-02) |
| Context-dependent DAO tests @Ignore until Robolectric | Complete (30-02) |
| reserved_utxos.value_sat added for direct sum | Complete (30-02) |

## Pending Todos

- Execute plan 30-03 (Scripthash Subscription)
- Execute plans 30-04 through 30-10

## Blockers / Concerns

- `consolidate_fix.kt` untracked file in project root (possible WIP)
- Pre-existing RavencoinTxBuilderTest failures in 2 asset issuance tests (out of scope)

## Session Continuity

Last session: 2026-04-21T05:36:02.443Z
Stopped at: Completed 30-04-fee-estimation
Resume file: None
Next action: Execute plan 30-03 (Scripthash Subscription)
