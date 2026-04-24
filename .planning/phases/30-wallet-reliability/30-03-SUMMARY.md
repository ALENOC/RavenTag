---
phase: 30-wallet-reliability
plan: 03
subsystem: scripthash-subscription
tags: [electrumx, subscription, tofu, tls, flow, parser, wallet-recv, fee-estimation]
dependency_graph:
  requires: [30-01, 30-02]
  provides: [TofuTrustManager, SubscriptionParser, ScripthashEvent, SubscriptionManager, electrum-rpc-wrappers]
  affects: [30-04, 30-07, 30-08]
tech_stack:
  added: [SharedFlow, persistent TLS socket, ElectrumX subscribe RPC, ElectrumX estimatefee RPC]
  patterns: [shared-tofu-trust-manager, id-matched-json-rpc-routing, heartbeat-ping-loop]
key_files:
  created:
    - android/app/src/main/java/io/raventag/app/wallet/TofuTrustManager.kt
    - android/app/src/main/java/io/raventag/app/wallet/subscription/ScripthashEvent.kt
    - android/app/src/main/java/io/raventag/app/wallet/subscription/SubscriptionManager.kt
  modified:
    - android/app/src/main/java/io/raventag/app/wallet/RavencoinPublicNode.kt
    - android/app/src/main/java/io/raventag/app/wallet/subscription/SubscriptionParser.kt
decisions:
  - Shared TOFU implementation extracted to a dedicated internal class instead of duplicating TLS trust logic in the subscription path
  - Subscription socket kept separate from RavencoinPublicNode one-shot RPC sockets to avoid mixing sync responses with async notifications
  - addressToScripthash promoted to internal so SubscriptionManager can reuse the canonical conversion
  - SubscriptionManager exposes SharedFlow<ScripthashEvent> and leaves reconnect policy to downstream plans/UI
metrics:
  duration: split across 2 commits
  completed: 2026-04-21
  tasks: 2
  files: 5
---

# Phase 30 Plan 03: Scripthash Subscription Summary

Shared TOFU TLS trust, ElectrumX subscribe/estimatefee entry points, a pure JSON-RPC subscription parser, and a persistent subscription manager for real-time wallet change detection.

## Performance

- **Completed:** 2026-04-21
- **Commits:** 2
- **Files created:** 3
- **Files modified:** 2

## Accomplishments

- Extracted `TofuTrustManager` from `RavencoinPublicNode` into its own reusable internal class with the existing dual-layer fingerprint cache behavior intact
- Added `subscribeScripthashRpc(address)` and `estimateFeeRvnPerKb(targetBlocks)` wrappers to `RavencoinPublicNode`
- Promoted `addressToScripthash(address)` to `internal` visibility for subscription reuse
- Implemented `ScripthashEvent` as the event contract for subscription notifications and failure states
- Replaced the Wave 0 parser stub with a real `SubscriptionParser.parseLine()` implementation that routes response, notification, and unknown frames
- Added `SubscriptionManager` with persistent TLS socket lifecycle, per-request id matching, 60s ping heartbeat, and `SharedFlow<ScripthashEvent>` API

## Task Commits

1. **Task 1: Extract trust manager, add RPC wrappers, implement parser and event model** - `bd7ba0c` (`feat(30-03)`)
2. **Task 2: Add SubscriptionManager and fix coroutineContext/isActive compilation issue** - `0ad9de9` (`fix(30-03)`)

## Files Created/Modified

### Created

- `android/app/src/main/java/io/raventag/app/wallet/TofuTrustManager.kt` - shared internal TOFU trust manager reused by one-shot RPC and long-lived subscription sockets
- `android/app/src/main/java/io/raventag/app/wallet/subscription/ScripthashEvent.kt` - sealed event model: `StatusChanged`, `ConnectionLost`, `AllNodesDown`, `PingTimeout`
- `android/app/src/main/java/io/raventag/app/wallet/subscription/SubscriptionManager.kt` - persistent ElectrumX subscription session with reader loop, heartbeat loop, and `SharedFlow`

### Modified

- `android/app/src/main/java/io/raventag/app/wallet/RavencoinPublicNode.kt` - removed inline `TofuTrustManager`, added subscribe/estimatefee wrappers, exposed `addressToScripthash` as `internal`
- `android/app/src/main/java/io/raventag/app/wallet/subscription/SubscriptionParser.kt` - implemented Wave 0 contract for JSON-RPC frame routing

## Behavior Delivered

- `SubscriptionParser` now returns:
  - `Parsed.Response(id, result)` when a frame contains an integer `id`
  - `Parsed.Notification(scripthash, status)` for `blockchain.scripthash.subscribe` pushes
  - `Parsed.Unknown(raw)` for malformed or unsupported frames
- `SubscriptionManager.start(addresses)`:
  - opens one TLS socket to the first reachable ElectrumX server
  - performs `server.version` handshake
  - subscribes every wallet address after converting it to scripthash
  - starts a reader coroutine that routes responses via request id and notifications via `ScripthashEvent.StatusChanged`
  - starts a 60-second `server.ping` heartbeat to detect zombie sockets
- `SubscriptionManager.stop()` cancels the scope, closes the socket, and clears pending callbacks
- If every server fails on startup, the manager emits `AllNodesDown`
- If the socket dies or read loop fails, the manager emits `ConnectionLost`
- If the heartbeat times out, the manager emits `PingTimeout`

## Test / Validation Notes

- Commit `bd7ba0c` records that all 6 Wave 0 `SubscriptionParserTest` tests were GREEN after parser implementation
- `0ad9de9` fixed unresolved `coroutineContext` and `isActive` references in `SubscriptionManager`, which was required for downstream plan `30-04` compilation
- This summary was generated from repository state, planning docs, and commit history; Gradle tests were not re-run during summary generation

## Deviations from Plan

### Notable implementation detail

- `SubscriptionManager.kt` landed in the follow-up fix commit `0ad9de9` instead of the main feature commit `bd7ba0c`. The fix commit both introduced the file and corrected the coroutine imports needed for it to compile cleanly in downstream work.

## Downstream Readiness

- **Plan 30-04** can call `estimateFeeRvnPerKb()` through `FeeEstimator`
- **Plan 30-07** can build node-health and reconnect policy on top of `SubscriptionManager` events
- **Plan 30-08** can wire foreground wallet refresh and incoming-tx UX to `eventsFlow()`

## Self-Check

- All summary-referenced files exist in the current workspace
- Both phase commits are present in git history
- The summary matches the current `.planning` naming and metadata style used by adjacent Phase 30 summaries
