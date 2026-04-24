---
phase: 30-wallet-reliability
plan: 07
subsystem: networking-reliability
tags: [electrumx, tofu, quarantine, connection-health, stateflow, android, kotlin]

# Dependency graph
requires:
  - phase: 30-02
    provides: QuarantineDao (wallet/health/QuarantineDao.kt)
  - phase: 30-03
    provides: SubscriptionManager skeleton with TofuTrustManager
provides:
  - NodeHealthMonitor singleton with ConnectionHealth StateFlow (GREEN/YELLOW/RED)
  - NodeHealthMonitor.nextHealthyNode / reportSuccess / reportFailure / reportTofuMismatch
  - NodeHealthMonitor.diagnostics / currentNode for plan 30-08 bottom sheet
  - AllNodesUnreachableException (signal for plan 30-08 RED snackbar)
  - AppConfig.ELECTRUM_SERVERS centralized pool (consumer + brand flavors)
  - NetworkModule: single D-10 timeout pair (10s connect / 20s read / 20s write)
affects: [30-08, 30-09, 30-10]

# Tech tracking
tech-stack:
  added: [kotlinx.coroutines.flow.StateFlow for connection UX]
  patterns: [singleton health monitor, in-memory recent-window + SQLite persistent quarantine split]

key-files:
  created:
    - android/app/src/main/java/io/raventag/app/wallet/health/NodeHealthMonitor.kt
  modified:
    - android/app/src/main/java/io/raventag/app/wallet/RavencoinPublicNode.kt
    - android/app/src/main/java/io/raventag/app/wallet/subscription/SubscriptionManager.kt
    - android/app/src/main/java/io/raventag/app/wallet/WalletExceptions.kt
    - android/app/src/main/java/io/raventag/app/network/NetworkModule.kt
    - android/app/src/main/java/io/raventag/app/MainActivity.kt
    - android/app/src/main/java/io/raventag/app/worker/WalletPollingWorker.kt
    - android/app/src/main/java/io/raventag/app/worker/RebroadcastWorker.kt
    - android/app/src/consumer/java/io/raventag/app/config/AppConfig.kt
    - android/app/src/brand/java/io/raventag/app/config/AppConfig.kt

key-decisions:
  - "NodeHealthMonitor reads AppConfig.ELECTRUM_SERVERS as List<Pair<String, Int>> rather than introducing a shared ElectrumServer data class, to avoid leaking the private ElectrumServer type out of RavencoinPublicNode"
  - "ELECTRUM_SERVERS duplicated across consumer + brand AppConfig (flavor-scoped object) rather than moved to main/; keeps flavor customization boundary intact"
  - "activeQuarantineHosts() swallows DB-not-initialized errors so background paths before init() still pick a candidate (defensive)"
  - "Transient failure cooldown (30s) lives only in-memory; persistence is reserved for TOFU mismatches (1h) per D-11"
  - "Pre-existing em dashes in RavencoinPublicNode.kt left untouched (scope boundary); tracked in Deferred Issues"

patterns-established:
  - "Connection-health singleton pattern: RPC and subscription paths share a single nextHealthyNode() + reportX() contract"
  - "Defensive init in every doWork() entry point so workers spawned cold still have QuarantineDao available"

requirements-completed: [WALLET-BAL, WALLET-RECV]

# Metrics
duration: 18min
completed-date: 2026-04-23
commits:
  - b0169a7 feat(30-07): create NodeHealthMonitor with quarantine policy + ConnectionHealth StateFlow
  - f9067e6 feat(30-07): wire RavencoinPublicNode and SubscriptionManager through NodeHealthMonitor
  - 46623aa fix(30-07): remove NetworkModule duplicate timeouts and wire NodeHealthMonitor.init
---

# Phase 30 Plan 07: Node Reliability Summary

NodeHealthMonitor is now the single source of truth for ElectrumX quarantine (D-11) and connection health (D-12); both one-shot RPC and the long-lived subscription socket route through it, NetworkModule has the single D-10 timeout pair, and AppConfig.ELECTRUM_SERVERS is the centralized pool.

## What Was Built

### Task 1: NodeHealthMonitor singleton + StateFlow (commit b0169a7)

Created `android/app/src/main/java/io/raventag/app/wallet/health/NodeHealthMonitor.kt`:

- `enum class ConnectionHealth { GREEN, YELLOW, RED }` (D-12 semantics).
- `object NodeHealthMonitor`:
  - `init(context)` idempotent gate (double-checked synchronized lock).
  - `nextHealthyNode()`: prunes recent failures by 30s cooldown + skips any host currently in `QuarantineDao.all()` with `quarantinedUntil > now`.
  - `reportSuccess(host)` / `reportFailure(host, reason)` / `reportTofuMismatch(host)` update in-memory maps and recompute `_state`.
  - `reportTofuMismatch` writes a 1h quarantine row via `QuarantineDao.quarantine(host, 3_600_000L, REASON_TOFU_MISMATCH)`.
  - `stateFlow: StateFlow<ConnectionHealth>` (read-only) for plan 30-08 pill.
  - `diagnostics()` returns per-host `NodeDiagnostic` list for plan 30-08 bottom sheet.
  - `currentNode()` returns the most-recently-successful host.
- Recompute rule:
  - quarantined == total → RED
  - any failure within 30s + at least one fallback free → YELLOW
  - any success within 60s → GREEN
  - else (cold start / long idle) → YELLOW, promoting to GREEN on first success.

Also added `val ELECTRUM_SERVERS: List<Pair<String, Int>>` with provenance KDoc to both `consumer` and `brand` `AppConfig` objects (flavor-scoped). The `main/` variant was removed to avoid duplicate-class errors.

### Task 2: RPC + subscription paths routed through NodeHealthMonitor (commit f9067e6)

`RavencoinPublicNode.kt`:

- `callWithFailover(method, params)`: replaced the naive `for (server in SERVERS)` loop with a health-aware `repeat(SERVERS.size)` loop that calls `NodeHealthMonitor.nextHealthyNode()` before each attempt, reports success/failure to the monitor, classifies TOFU mismatches via a local `isTofuMismatch(e)` helper, and throws `AllNodesUnreachableException` when `nextHealthyNode()` returns null.
- `callWithFailoverBatch(requests)`: same treatment; returns `List(requests.size) { null }` on all-quarantined (preserves existing caller contract).
- `SERVERS` field now initialized from `AppConfig.ELECTRUM_SERVERS.map { (h, p) -> ElectrumServer(h, p) }` so there is one canonical pool.

`SubscriptionManager.kt`:

- `start(addresses)` consults `NodeHealthMonitor.nextHealthyNode()` per attempt, reports outcomes, and emits `ScripthashEvent.AllNodesDown` when all candidates fail.
- `readLoop` and `heartbeatLoop` now report failure (TOFU mismatch or named exception) to `NodeHealthMonitor` before emitting `ConnectionLost` / `PingTimeout`.
- Added `sessionKey(s)` to compute `"host:port"` form used as the monitor key.
- `DEFAULT_SERVERS` companion constant repointed to `AppConfig.ELECTRUM_SERVERS` so any legacy callers share the same pool.
- The existing 60s `pingIntervalMs` already covers D-10 zombie-socket detection (no new heartbeat added).

`WalletExceptions.kt`:

- Added `class AllNodesUnreachableException(msg: String = "all ElectrumX nodes quarantined") : RuntimeException(msg)`.

### Task 3: NetworkModule timeout fix + MainActivity + worker init (commit 46623aa)

`NetworkModule.kt`:

- Removed the duplicate `connectTimeout(15, SECONDS)` / `readTimeout(30, SECONDS)` / `writeTimeout(30, SECONDS)` trio that shadowed the intended D-10 values.
- Canonical chain now has exactly one of each (verified: `grep -c 'connectTimeout' == 1`, `grep -c 'readTimeout' == 1`): `connectTimeout(10, SECONDS)`, `readTimeout(20, SECONDS)`, `writeTimeout(20, SECONDS)`.
- Before vs after:
  - before: two pairs (lines 69-71 at 10/15/15s + duplicate at 82-84 at 15/30/30s)
  - after: single pair at lines 71-73 matching D-10 (10/20/20s)

`MainActivity.kt`:

- Added `io.raventag.app.wallet.health.NodeHealthMonitor.init(this)` immediately after `WalletReliabilityDb.init(this)` + `ReservedUtxoDao.pruneOlderThan(...)` (block around line 2460).

`WalletPollingWorker.kt` + `RebroadcastWorker.kt`:

- First line of each `doWork()` now calls `NodeHealthMonitor.init(applicationContext)` defensively so a worker spawned before MainActivity has run still has `QuarantineDao` available.

## Hand-off to Plan 30-08 (WalletScreen UI)

- `NodeHealthMonitor.stateFlow: StateFlow<ConnectionHealth>` is the single StateFlow source for the D-12 connection pill (collect via `ViewModel.collectAsState()`).
- `NodeHealthMonitor.diagnostics()` feeds the "Fallback node list" in the tap-to-open bottom sheet.
- `NodeHealthMonitor.currentNode()` drives the "Current server" row in the bottom sheet.
- `AllNodesUnreachableException` is the thrown signal that plan 30-08 should catch to show the "Offline, all nodes unreachable" snackbar + disable Send/Receive.
- No Compose code was added in this plan (explicit scope boundary).

## TOFU mismatch detection (identified at execution time)

`TofuTrustManager.kt` throws a plain `Exception` with message `"Certificate mismatch for <host>: expected <fp_old>, got <fp_new>"`. Both `RavencoinPublicNode` and `SubscriptionManager` classify this via:

```kotlin
private fun isTofuMismatch(e: Throwable): Boolean {
    if (e is java.security.cert.CertificateException) return true
    val m = e.message ?: return false
    return m.contains("Certificate mismatch", ignoreCase = true) ||
        m.contains("fingerprint mismatch", ignoreCase = true) ||
        m.contains("TOFU", ignoreCase = true)
}
```

The substring `"Certificate mismatch"` is the actual match in today's code; the other two are forward-compat for potential stack wrappings.

## QuarantineDao API reconciliation

Plan text assumed a `QuarantineDao.QuarantinedNode` data class with `upsert / activeAt / pruneExpired`. Reality (per plan 30-02 implementation in `wallet/health/QuarantineDao.kt`):
- `data class Quarantine(val host, val quarantinedUntil, val reason)`
- `fun quarantine(host, durationMillis, reason)` (inserts with `quarantined_until = now + durationMillis`)
- `fun isQuarantined(host)` / `fun clear(host)` / `fun all()`
- No explicit `pruneExpired` (rows age out naturally by the `quarantined_until > now` predicate).

`NodeHealthMonitor.activeQuarantineHosts(now)` therefore calls `QuarantineDao.all().filter { it.quarantinedUntil > now }` in-memory. With a max of ~4 rows in the pool this is effectively free and matches the monitor's existing SQLite-lazy semantics.

## Node list policy (RESEARCH Pitfall 8)

All 4 original hosts retained (`rvn4lyfe.com`, `rvn-dashboard.com`, `162.19.153.65`, `51.222.139.25`). Provenance KDoc added to both flavor `AppConfig` files. `rvn-dashboard.com` is kept despite RESEARCH flagging it LOW confidence: quarantine handles staleness silently without user impact.

## Flavor considerations

Because `AppConfig` is a flavor-scoped object (separate files in `src/consumer/` and `src/brand/`), `ELECTRUM_SERVERS` was added to BOTH. Build verified against `:app:assembleConsumerDebug`; the brand variant will pick up the symmetric definition on its next build.

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 3 - Blocker] AppConfig package did not exist in `main/`**
- **Found during:** Task 1
- **Issue:** Plan assumed `io.raventag.app.config.AppConfig` was a single `main/` source; actual project has per-flavor AppConfig files in `src/consumer/` and `src/brand/` with different booleans per flavor. Adding a `main/` version caused a duplicate-class build failure.
- **Fix:** Removed `main/` variant, extended both flavor AppConfig files with the same `ELECTRUM_SERVERS` constant.
- **Commit:** b0169a7

**2. [Rule 3 - Blocker] QuarantineDao real API differs from plan interfaces**
- **Found during:** Task 1
- **Issue:** Plan assumed `QuarantinedNode / upsert / activeAt / pruneExpired`; actual API is `Quarantine / quarantine / all / isQuarantined / clear`.
- **Fix:** NodeHealthMonitor filters `QuarantineDao.all()` by `quarantinedUntil > now` in-memory; calls `QuarantineDao.quarantine(host, 3_600_000L, REASON_TOFU_MISMATCH)` for 1h quarantine. No behavior change vs plan intent.
- **Commit:** b0169a7

**3. [Rule 2 - Correctness] Workers missing NodeHealthMonitor.init**
- **Found during:** Task 3
- **Issue:** Plan flagged this as a defensive need; confirmed neither worker had any DAO init at doWork entry, risking NPE on cold-start.
- **Fix:** Added `NodeHealthMonitor.init(applicationContext)` as first line of both `WalletPollingWorker.doWork()` and `RebroadcastWorker.doWork()`.
- **Commit:** 46623aa

## Deferred Issues

- **Pre-existing em dashes in RavencoinPublicNode.kt** (lines 283, 287, 329 before edits). Out of scope (SCOPE BOUNDARY rule); tracked for future janitorial pass. No new em dashes introduced by this plan's diff (`git diff HEAD~3..HEAD` inspected manually, no additions containing U+2014).
- **Pre-existing unused-param warnings** in `RavencoinPublicNode.kt:245` and `MainActivity.kt:2915/3204`. Out of scope.
- **No runtime connectivity check on ELECTRUM_SERVERS** (RESEARCH Pitfall 8 approach `a`). Deferred per plan; approach `b` (documentary KDoc + quarantine-handled staleness) adopted.

## Verification

- [x] `./gradlew :app:assembleConsumerDebug` exits 0
- [x] `NodeHealthMonitor` exports `StateFlow<ConnectionHealth>` + 1h quarantine constant matches D-11 (`QUARANTINE_DURATION_MS = 3_600_000L`)
- [x] `RavencoinPublicNode.callWithFailover` + `callWithFailoverBatch` consult `NodeHealthMonitor.nextHealthyNode()` and report outcomes
- [x] `SubscriptionManager.start` + `readLoop` + `heartbeatLoop` consult + report to `NodeHealthMonitor`
- [x] `NetworkModule.kt`: `grep -c 'connectTimeout' == 1`, `grep -c 'readTimeout' == 1` (single D-10 pair)
- [x] `AppConfig.ELECTRUM_SERVERS` present in both `consumer` + `brand` flavor files
- [x] `MainActivity.onCreate` calls `NodeHealthMonitor.init(this)`
- [x] Both workers call `NodeHealthMonitor.init(applicationContext)` at top of `doWork`
- [x] No new em dashes introduced by this plan

Manual verification (per 30-VALIDATION.md Manual-Only row #3) deferred to integration testing: tamper `electrum_certificates.db`, restart, confirm YELLOW/RED pill propagation through plan 30-08.

## Known Stubs

None. This plan wires real SQLite-persisted quarantine + in-memory cooldown through both production RPC paths.

## Self-Check: PASSED

- FOUND: `android/app/src/main/java/io/raventag/app/wallet/health/NodeHealthMonitor.kt`
- FOUND: commit `b0169a7` (Task 1)
- FOUND: commit `f9067e6` (Task 2)
- FOUND: commit `46623aa` (Task 3)
- FOUND: `AllNodesUnreachableException` in `WalletExceptions.kt`
- FOUND: `ELECTRUM_SERVERS` in both `consumer` and `brand` `AppConfig.kt`
- FOUND: `NodeHealthMonitor.init(this)` in `MainActivity.kt`
- FOUND: `NodeHealthMonitor.init(applicationContext)` in both workers
- VERIFIED: `NetworkModule.kt` has single `connectTimeout` + `readTimeout` line each
