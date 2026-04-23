---
id: 30-07-node-reliability
phase: 30
plan: 07
type: execute
wave: 2
depends_on:
  - 30-02-wallet-cache-db-daos
  - 30-03-scripthash-subscription
files_modified:
  - android/app/src/main/java/io/raventag/app/wallet/health/NodeHealthMonitor.kt
  - android/app/src/main/java/io/raventag/app/wallet/RavencoinPublicNode.kt
  - android/app/src/main/java/io/raventag/app/wallet/subscription/SubscriptionManager.kt
  - android/app/src/main/java/io/raventag/app/network/NetworkModule.kt
  - android/app/src/main/java/io/raventag/app/config/AppConfig.kt
  - android/app/src/main/java/io/raventag/app/MainActivity.kt
autonomous: true
requirements:
  - WALLET-BAL
  - WALLET-RECV
threat_refs:
  - T-30-NET
  - T-30-RECV
ui_spec_refs:
  - "UI-SPEC §Color, Connection status pill (D-12) — green/yellow/red semantics"
  - "UI-SPEC §Key Visual Patterns, Connection status pill (D-12) — tap-to-open bottom sheet fields"

must_haves:
  truths:
    - "Before each ElectrumX RPC call, RavencoinPublicNode consults NodeHealthMonitor.nextHealthyNode() which skips quarantined hosts (D-11)"
    - "TOFU fingerprint mismatch (Certificate mismatch) on either one-shot RPC or SubscriptionManager socket reports the host to NodeHealthMonitor, which writes a 1-hour quarantine row into QuarantineDao (D-11)"
    - "NodeHealthMonitor exposes a StateFlow<ConnectionHealth> emitting Green/Yellow/Red per D-12 semantics, consumed by WalletScreen in plan 30-08"
    - "NetworkModule duplicate connectTimeout/readTimeout lines are removed (CONCERNS.md)"
    - "AppConfig public ElectrumX fallback list is verified / extended to cover D-09 ~3-5 node target (RESEARCH Pitfall 8)"
    - "Transient RPC flakiness does NOT change the pill color; only actual per-node failures do"
  artifacts:
    - path: "android/app/src/main/java/io/raventag/app/wallet/health/NodeHealthMonitor.kt"
      provides: "singleton health state + quarantine policy + connection StateFlow"
      exports: ["NodeHealthMonitor", "ConnectionHealth"]
    - path: "android/app/src/main/java/io/raventag/app/wallet/RavencoinPublicNode.kt"
      provides: "extended call() / callWithFailover() to consult NodeHealthMonitor + report success/failure/TOFU mismatch"
    - path: "android/app/src/main/java/io/raventag/app/wallet/subscription/SubscriptionManager.kt"
      provides: "extended start() to report TOFU mismatch to NodeHealthMonitor + consult nextHealthyNode before each connection attempt"
    - path: "android/app/src/main/java/io/raventag/app/network/NetworkModule.kt"
      provides: "single connectTimeout(10, SECONDS) + readTimeout(20, SECONDS) pair (D-10; duplicate lines removed)"
    - path: "android/app/src/main/java/io/raventag/app/config/AppConfig.kt"
      provides: "documented/extended public ElectrumX node list"
  key_links:
    - from: "RavencoinPublicNode.callWithFailover"
      to: "NodeHealthMonitor.nextHealthyNode / reportSuccess / reportFailure / reportTofuMismatch"
      via: "inlined pre-call filter + post-call status callback"
      pattern: "NodeHealthMonitor"
    - from: "SubscriptionManager.start"
      to: "NodeHealthMonitor.nextHealthyNode + reportTofuMismatch"
      via: "per-server retry loop"
      pattern: "NodeHealthMonitor"
    - from: "WalletScreen (plan 30-08) pill / bottom sheet"
      to: "NodeHealthMonitor.stateFlow"
      via: "ViewModel.collectAsState()"
      pattern: "ConnectionHealth"
---

<objective>
Wire ElectrumX failover reliability: introduce `NodeHealthMonitor` as the single source of truth for node quarantine and connection health; route both one-shot RPC (`RavencoinPublicNode`) and the long-lived subscription socket (`SubscriptionManager`) through it; fix the existing `NetworkModule` duplicate-timeout bug flagged in CONCERNS.md; and validate / extend the public ElectrumX node list per RESEARCH Pitfall 8.

Purpose: D-11 quarantine enforcement (1h per-host on TOFU mismatch), D-12 degraded-UX state source (Green/Yellow/Red pill), D-10 timeout normalization, WALLET-BAL / WALLET-RECV resilience.

Output: one new class file (`NodeHealthMonitor.kt`), surgical edits to three existing files, one config edit.

**Explicit scope boundary:** This plan provides the DATA SOURCE (StateFlow<ConnectionHealth>) for the connection pill. The VISUAL rendering of the pill and the tap-to-open bottom sheet live in plan 30-08 (WalletScreen UI refresh). Do not add any Compose code in this plan.
</objective>

<execution_context>
@$HOME/.claude/get-shit-done/workflows/execute-plan.md
@$HOME/.claude/get-shit-done/templates/summary.md
</execution_context>

<context>
@.planning/phases/30-wallet-reliability/30-CONTEXT.md
@.planning/phases/30-wallet-reliability/30-RESEARCH.md
@.planning/phases/30-wallet-reliability/30-PATTERNS.md
@.planning/phases/30-wallet-reliability/30-VALIDATION.md
@.planning/codebase/CONCERNS.md
@android/app/src/main/java/io/raventag/app/wallet/RavencoinPublicNode.kt
@android/app/src/main/java/io/raventag/app/wallet/TofuTrustManager.kt
@android/app/src/main/java/io/raventag/app/wallet/subscription/SubscriptionManager.kt
@android/app/src/main/java/io/raventag/app/wallet/cache/QuarantineDao.kt
@android/app/src/main/java/io/raventag/app/network/NetworkModule.kt
@android/app/src/main/java/io/raventag/app/config/AppConfig.kt
@android/app/src/main/java/io/raventag/app/utils/RetryUtils.kt

<interfaces>
From plan 30-02:
```kotlin
object QuarantineDao {
    fun init(context: android.content.Context)
    data class QuarantinedNode(val host: String, val quarantinedUntil: Long, val reason: String)
    fun upsert(node: QuarantinedNode)
    fun all(): List<QuarantinedNode>
    /** Returns entries whose quarantinedUntil > now. */
    fun activeAt(nowMillis: Long): List<QuarantinedNode>
    /** Remove rows whose quarantinedUntil <= now. */
    fun pruneExpired(nowMillis: Long)
}
```

From plan 30-03 (existing):
```kotlin
package io.raventag.app.wallet

internal class TofuTrustManager(context: android.content.Context, val host: String) : javax.net.ssl.X509TrustManager { /* ... */ }

// On fingerprint mismatch, throws an exception. The exact class — as of Phase 10 — extends
// java.security.cert.CertificateException with message containing "Certificate mismatch" or
// similar. Identify the exact class at execution time (search TofuTrustManager.kt for
// `throw` statements). Report-to-health logic below uses instanceof checks + message
// heuristics to route correctly.

data class ElectrumServer(val host: String, val port: Int)
```

From `RavencoinPublicNode.kt`:
```kotlin
// Existing pool, to be extended through AppConfig and consulted via NodeHealthMonitor:
private val SERVERS = listOf(
    ElectrumServer("rvn4lyfe.com", 50002),
    ElectrumServer("rvn-dashboard.com", 50002),
    ElectrumServer("162.19.153.65", 50002),
    ElectrumServer("51.222.139.25", 50002)
)
private const val CONNECT_TIMEOUT_MS = 10_000
private const val READ_TIMEOUT_MS = 20_000 // Plan 30-03 already raised this to 20s per D-10
```

**New contract introduced by this plan** (consumed by plan 30-08):
```kotlin
package io.raventag.app.wallet.health

enum class ConnectionHealth { GREEN, YELLOW, RED }

object NodeHealthMonitor {
    fun init(context: android.content.Context)
    /** Returns the next host string in `host:port` form that is NOT quarantined. Null if all quarantined. */
    fun nextHealthyNode(): String?
    fun reportSuccess(host: String)
    fun reportFailure(host: String, reason: String)
    fun reportTofuMismatch(host: String)
    val stateFlow: kotlinx.coroutines.flow.StateFlow<ConnectionHealth>
    /** For the bottom sheet in plan 30-08. */
    data class NodeDiagnostic(
        val host: String,
        val lastSuccessAt: Long?,
        val lastFailureAt: Long?,
        val lastError: String?,
        val quarantinedUntil: Long?
    )
    fun diagnostics(): List<NodeDiagnostic>
    /** Current active node (last successful), for the bottom sheet. */
    fun currentNode(): String?
}
```
</interfaces>
</context>

<tasks>

<task type="auto" tdd="true">
  <name>Task 1: Create NodeHealthMonitor.kt (singleton, StateFlow, QuarantineDao integration)</name>
  <files>
    android/app/src/main/java/io/raventag/app/wallet/health/NodeHealthMonitor.kt
  </files>
  <read_first>
    @.planning/phases/30-wallet-reliability/30-CONTEXT.md#L26-L35,
    @.planning/phases/30-wallet-reliability/30-RESEARCH.md#L396-L404,
    @.planning/phases/30-wallet-reliability/30-RESEARCH.md#L531-L537,
    @.planning/phases/30-wallet-reliability/30-PATTERNS.md#L345-L364,
    @android/app/src/main/java/io/raventag/app/wallet/cache/QuarantineDao.kt,
    @android/app/src/main/java/io/raventag/app/config/AppConfig.kt
  </read_first>
  <behavior>
    Singleton `object NodeHealthMonitor` with:
    - Internal per-host in-memory state: `val lastSuccessAt: MutableMap<String, Long>`, `val lastFailureAt: MutableMap<String, Long>`, `val lastError: MutableMap<String, String?>` — all `ConcurrentHashMap` for thread safety (RPC and subscription coroutines write concurrently).
    - `private val _state = MutableStateFlow(ConnectionHealth.GREEN)`; `val stateFlow: StateFlow<ConnectionHealth> = _state.asStateFlow()`.
    - `fun init(context: Context)` — idempotent; calls `QuarantineDao.init(context)` + reads the initial node list from `AppConfig.ELECTRUM_SERVERS` (see Task 4). Uses a `synchronized(lock)` init gate.

    `fun nextHealthyNode(): String?`:
    1. Prune expired quarantines: `QuarantineDao.pruneExpired(System.currentTimeMillis())`.
    2. Load active quarantine hosts: `val quarantinedHosts = QuarantineDao.activeAt(now).map { it.host }.toSet()`.
    3. Select the first host from `AppConfig.ELECTRUM_SERVERS` whose `host:port` string is NOT in `quarantinedHosts` AND whose `lastFailureAt` is either null or older than 30 seconds (transient-failure cooldown).
    4. Return `"$host:$port"` or null if all are quarantined / in cooldown.
    5. After computing, update the state flow (see recomputeState below).

    `fun reportSuccess(host: String)`:
    - `lastSuccessAt[host] = System.currentTimeMillis()`; clear `lastError[host]` and `lastFailureAt[host]`.
    - `recomputeState()`.

    `fun reportFailure(host: String, reason: String)`:
    - `lastFailureAt[host] = now`; `lastError[host] = reason`.
    - Do NOT insert a quarantine row here — transient failures quarantine only after repeated attempts (handled by caller's `retryWithBackoff`). NodeHealthMonitor merely tracks state.
    - `recomputeState()`.

    `fun reportTofuMismatch(host: String)`:
    - `QuarantineDao.upsert(QuarantinedNode(host, quarantinedUntil = now + 3600_000L, reason = "TOFU_MISMATCH"))`.
    - `lastFailureAt[host] = now; lastError[host] = "TOFU_MISMATCH"`.
    - `recomputeState()`.

    `private fun recomputeState()`:
    - Let `total = AppConfig.ELECTRUM_SERVERS.size`, `quarantined = QuarantineDao.activeAt(now).size`.
    - If `quarantined >= total` → `_state.value = RED` (UI disables Send/Receive per D-12).
    - Else if ANY host in the pool has a `lastFailureAt` within the last 30 seconds AND at least one healthy host remains → `_state.value = YELLOW` (reconnecting; still some fallback available).
    - Else if ANY host has a `lastSuccessAt` within the last 60 seconds → `_state.value = GREEN`.
    - Else → `_state.value = YELLOW` (cold start or long idle; promotes to GREEN on first success).

    `fun currentNode(): String?` — the host with the most recent `lastSuccessAt` (null if none). Used by the bottom sheet in plan 30-08.

    `fun diagnostics(): List<NodeDiagnostic>` — for each host in `AppConfig.ELECTRUM_SERVERS`, emit a `NodeDiagnostic` with the latest in-memory stats + quarantine status. Used by plan 30-08 bottom sheet "Fallback node list".
  </behavior>
  <action>
    Create `android/app/src/main/java/io/raventag/app/wallet/health/NodeHealthMonitor.kt`:

    ```kotlin
    package io.raventag.app.wallet.health

    import android.content.Context
    import io.raventag.app.config.AppConfig
    import io.raventag.app.wallet.cache.QuarantineDao
    import java.util.concurrent.ConcurrentHashMap
    import kotlinx.coroutines.flow.MutableStateFlow
    import kotlinx.coroutines.flow.StateFlow
    import kotlinx.coroutines.flow.asStateFlow

    enum class ConnectionHealth { GREEN, YELLOW, RED }

    object NodeHealthMonitor {

        data class NodeDiagnostic(
            val host: String,
            val lastSuccessAt: Long?,
            val lastFailureAt: Long?,
            val lastError: String?,
            val quarantinedUntil: Long?
        )

        private const val QUARANTINE_DURATION_MS: Long = 3_600_000L       // D-11: 1 hour
        private const val TRANSIENT_COOLDOWN_MS: Long = 30_000L
        private const val YELLOW_FAILURE_WINDOW_MS: Long = 30_000L
        private const val GREEN_SUCCESS_WINDOW_MS: Long = 60_000L

        private val lastSuccessAt = ConcurrentHashMap<String, Long>()
        private val lastFailureAt = ConcurrentHashMap<String, Long>()
        private val lastError = ConcurrentHashMap<String, String>()

        private val _state = MutableStateFlow(ConnectionHealth.GREEN)
        val stateFlow: StateFlow<ConnectionHealth> = _state.asStateFlow()

        @Volatile private var initialized = false
        private val initLock = Any()

        fun init(context: Context) {
            synchronized(initLock) {
                if (initialized) return
                QuarantineDao.init(context)
                initialized = true
            }
        }

        fun nextHealthyNode(): String? {
            val now = System.currentTimeMillis()
            QuarantineDao.pruneExpired(now)
            val quarantinedHosts = QuarantineDao.activeAt(now).map { it.host }.toSet()
            val candidate = AppConfig.ELECTRUM_SERVERS.firstOrNull { srv ->
                val host = "${srv.host}:${srv.port}"
                if (host in quarantinedHosts) return@firstOrNull false
                val failedAt = lastFailureAt[host]
                failedAt == null || (now - failedAt) > TRANSIENT_COOLDOWN_MS
            }?.let { "${it.host}:${it.port}" }
            recomputeState()
            return candidate
        }

        fun reportSuccess(host: String) {
            val now = System.currentTimeMillis()
            lastSuccessAt[host] = now
            lastFailureAt.remove(host)
            lastError.remove(host)
            recomputeState()
        }

        fun reportFailure(host: String, reason: String) {
            val now = System.currentTimeMillis()
            lastFailureAt[host] = now
            lastError[host] = reason
            recomputeState()
        }

        fun reportTofuMismatch(host: String) {
            val now = System.currentTimeMillis()
            QuarantineDao.upsert(
                QuarantineDao.QuarantinedNode(
                    host = host,
                    quarantinedUntil = now + QUARANTINE_DURATION_MS,
                    reason = "TOFU_MISMATCH"
                )
            )
            lastFailureAt[host] = now
            lastError[host] = "TOFU_MISMATCH"
            recomputeState()
        }

        private fun recomputeState() {
            val now = System.currentTimeMillis()
            val total = AppConfig.ELECTRUM_SERVERS.size
            val quarantined = QuarantineDao.activeAt(now).size
            val next = when {
                quarantined >= total -> ConnectionHealth.RED
                lastFailureAt.values.any { (now - it) <= YELLOW_FAILURE_WINDOW_MS } &&
                    quarantined < total -> ConnectionHealth.YELLOW
                lastSuccessAt.values.any { (now - it) <= GREEN_SUCCESS_WINDOW_MS } ->
                    ConnectionHealth.GREEN
                else -> ConnectionHealth.YELLOW
            }
            _state.value = next
        }

        fun currentNode(): String? =
            lastSuccessAt.maxByOrNull { it.value }?.key

        fun diagnostics(): List<NodeDiagnostic> {
            val now = System.currentTimeMillis()
            val active = QuarantineDao.activeAt(now).associateBy { it.host }
            return AppConfig.ELECTRUM_SERVERS.map { srv ->
                val host = "${srv.host}:${srv.port}"
                NodeDiagnostic(
                    host = host,
                    lastSuccessAt = lastSuccessAt[host],
                    lastFailureAt = lastFailureAt[host],
                    lastError = lastError[host],
                    quarantinedUntil = active[host]?.quarantinedUntil
                )
            }
        }
    }
    ```

    Em-dash audit: `! grep -P '\u2014' android/app/src/main/java/io/raventag/app/wallet/health/NodeHealthMonitor.kt`.
  </action>
  <verify>
    <automated>cd android && ./gradlew :app:compileConsumerDebugKotlin -q 2>&1 | tail -20</automated>
  </verify>
  <acceptance_criteria>
    - `test -f android/app/src/main/java/io/raventag/app/wallet/health/NodeHealthMonitor.kt`
    - `grep -q "enum class ConnectionHealth" android/app/src/main/java/io/raventag/app/wallet/health/NodeHealthMonitor.kt`
    - `grep -q "object NodeHealthMonitor" android/app/src/main/java/io/raventag/app/wallet/health/NodeHealthMonitor.kt`
    - `grep -q "QUARANTINE_DURATION_MS: Long = 3_600_000L" android/app/src/main/java/io/raventag/app/wallet/health/NodeHealthMonitor.kt`
    - `grep -q "fun nextHealthyNode" android/app/src/main/java/io/raventag/app/wallet/health/NodeHealthMonitor.kt`
    - `grep -q "fun reportTofuMismatch" android/app/src/main/java/io/raventag/app/wallet/health/NodeHealthMonitor.kt`
    - `grep -q "StateFlow<ConnectionHealth>" android/app/src/main/java/io/raventag/app/wallet/health/NodeHealthMonitor.kt`
    - `grep -q "diagnostics()" android/app/src/main/java/io/raventag/app/wallet/health/NodeHealthMonitor.kt`
    - `! grep -P '\u2014' android/app/src/main/java/io/raventag/app/wallet/health/NodeHealthMonitor.kt`
    - `cd android && ./gradlew :app:compileConsumerDebugKotlin` exits 0.
  </acceptance_criteria>
  <done>NodeHealthMonitor compiles, exposes StateFlow<ConnectionHealth>, 1h quarantine constant matches D-11.</done>
</task>

<task type="auto" tdd="true">
  <name>Task 2: Integrate NodeHealthMonitor into RavencoinPublicNode + SubscriptionManager</name>
  <files>
    android/app/src/main/java/io/raventag/app/wallet/RavencoinPublicNode.kt,
    android/app/src/main/java/io/raventag/app/wallet/subscription/SubscriptionManager.kt
  </files>
  <read_first>
    @.planning/phases/30-wallet-reliability/30-CONTEXT.md#L30-L35,
    @.planning/phases/30-wallet-reliability/30-PATTERNS.md#L115-L162,
    @.planning/phases/30-wallet-reliability/30-RESEARCH.md#L475-L485,
    @android/app/src/main/java/io/raventag/app/wallet/RavencoinPublicNode.kt,
    @android/app/src/main/java/io/raventag/app/wallet/subscription/SubscriptionManager.kt,
    @android/app/src/main/java/io/raventag/app/wallet/TofuTrustManager.kt
  </read_first>
  <behavior>
    **RavencoinPublicNode.kt:**
    - `callWithFailover(method, params)` currently iterates `SERVERS` and catches exceptions. Replace the iteration with a health-aware loop:
      1. Read `val candidate = NodeHealthMonitor.nextHealthyNode()` at the top of each attempt.
      2. If `candidate == null` (all quarantined), throw `AllNodesUnreachableException("all ElectrumX nodes quarantined")` (new exception class in the same file OR in `wallet/WalletExceptions.kt` — prefer the latter; add the class to `WalletExceptions.kt` in this plan since it's a shared reliability exception).
      3. Split `candidate` into host + port; invoke the existing `call(ElectrumServer(host, port), method, params)`.
      4. On success: `NodeHealthMonitor.reportSuccess(candidate)`; return the result.
      5. On TLS / TOFU mismatch exception (detected via `e is javax.net.ssl.SSLException && e.message?.contains("Certificate") == true`, OR `e is java.security.cert.CertificateException`, OR the project's specific TOFU mismatch exception class — inspect TofuTrustManager.kt to confirm the class): `NodeHealthMonitor.reportTofuMismatch(candidate)`; continue to next iteration.
      6. On any other failure: `NodeHealthMonitor.reportFailure(candidate, e.javaClass.simpleName)`; continue.
      7. Retry the loop up to `SERVERS.size` attempts. If all fail, throw the last exception (or `AllNodesUnreachableException`).

    - Preserve existing behavior when `NodeHealthMonitor` has not been `init()`ed yet (defensive — `nextHealthyNode()` returns the first server when `QuarantineDao` is uninitialized? Per plan 30-02, `QuarantineDao.init` is called from `MainActivity.onCreate`. During background worker startup before the Activity runs, `QuarantineDao.init` MUST also be called — ensure the `WalletPollingWorker` / `RebroadcastWorker` invokes `NodeHealthMonitor.init(applicationContext)` at the top of `doWork()`). Add this call to both workers (already covered in plan 30-05 for RebroadcastWorker? Not explicitly — add here defensively in `RavencoinPublicNode.call*` via a one-time `NodeHealthMonitor.init(context)` guarded by `@Volatile private var hmInitialized = false`).

    - Add `class AllNodesUnreachableException(msg: String) : RuntimeException(msg)` to `android/app/src/main/java/io/raventag/app/wallet/WalletExceptions.kt`.

    **SubscriptionManager.kt:**
    - In `start(addresses: List<String>)`, the existing per-server retry loop (RESEARCH §Pattern 1 Example 1) tries `for (server in SERVERS) { try { openSession(server); ... } catch { } }`. Replace with:
      1. For `attempt in 1..SERVERS.size`:
         a. `val host = NodeHealthMonitor.nextHealthyNode() ?: run { events.emit(ScripthashEvent.AllNodesDown); return@withContext }`
         b. Try to open session to that host; subscribe; launch reader loop.
         c. On success: `NodeHealthMonitor.reportSuccess(host)`; break out of the retry loop.
         d. On TLS/TOFU mismatch: `NodeHealthMonitor.reportTofuMismatch(host)`; loop to next attempt.
         e. On any other failure: `NodeHealthMonitor.reportFailure(host, e.javaClass.simpleName)`; loop.
    - In the reader loop coroutine, wrap `reader.readLine()` failures with a TOFU / network-error check and call the corresponding `NodeHealthMonitor.report*` method before emitting `ScripthashEvent.ConnectionLost`.
    - In the 60s heartbeat (Pitfall 2 — introduced by plan 30-03), on ping failure call `NodeHealthMonitor.reportFailure(host, "ping_timeout")` then attempt reconnect via the regular `start()` path.
  </behavior>
  <action>
    **WalletExceptions.kt** — add:
    ```kotlin
    class AllNodesUnreachableException(msg: String = "all ElectrumX nodes quarantined") : RuntimeException(msg)
    ```

    **RavencoinPublicNode.kt** — locate `callWithFailover` (the method wrapping `call()` per-server iteration — identify via grep at execution time; the existing signature is `internal fun callWithFailover(method: String, params: List<Any?>): com.google.gson.JsonElement`). Replace the loop body:
    ```kotlin
    internal fun callWithFailover(method: String, params: List<Any?>): com.google.gson.JsonElement {
        io.raventag.app.wallet.health.NodeHealthMonitor.init(context)
        var lastError: Throwable? = null
        repeat(SERVERS.size) {
            val candidate = io.raventag.app.wallet.health.NodeHealthMonitor.nextHealthyNode()
                ?: throw AllNodesUnreachableException()
            val (host, portStr) = candidate.split(":", limit = 2)
            val port = portStr.toInt()
            val server = ElectrumServer(host, port)
            try {
                val result = call(server, method, params)
                io.raventag.app.wallet.health.NodeHealthMonitor.reportSuccess(candidate)
                return result
            } catch (e: Throwable) {
                lastError = e
                if (isTofuMismatch(e)) {
                    io.raventag.app.wallet.health.NodeHealthMonitor.reportTofuMismatch(candidate)
                } else {
                    io.raventag.app.wallet.health.NodeHealthMonitor.reportFailure(
                        candidate,
                        e.javaClass.simpleName
                    )
                }
            }
        }
        throw lastError ?: AllNodesUnreachableException()
    }

    private fun isTofuMismatch(e: Throwable): Boolean {
        if (e is java.security.cert.CertificateException) return true
        val m = e.message ?: return false
        return m.contains("Certificate mismatch", ignoreCase = true) ||
            m.contains("fingerprint mismatch", ignoreCase = true) ||
            m.contains("TOFU", ignoreCase = true)
    }
    ```
    The `call(server, method, params)` signature already exists; if the private visibility requires, change it to `private` or `internal` as needed to remain accessible from within the file.

    **SubscriptionManager.kt** — locate the `start(addresses: List<String>)` retry loop. Replace:
    ```kotlin
    suspend fun start(addresses: List<String>) = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        stop()
        io.raventag.app.wallet.health.NodeHealthMonitor.init(context)
        var lastError: Throwable? = null
        repeat(SERVERS.size) {
            val candidate = io.raventag.app.wallet.health.NodeHealthMonitor.nextHealthyNode()
                ?: run { events.emit(ScripthashEvent.AllNodesDown); return@withContext }
            val (host, portStr) = candidate.split(":", limit = 2)
            val port = portStr.toInt()
            try {
                session = openSession(io.raventag.app.wallet.ElectrumServer(host, port))
                for (addr in addresses) session!!.subscribe(scriptHashOf(addr))
                scope.launch { session!!.readLoop(events, onError = { err ->
                    if (isTofuMismatch(err)) {
                        io.raventag.app.wallet.health.NodeHealthMonitor.reportTofuMismatch(candidate)
                    } else {
                        io.raventag.app.wallet.health.NodeHealthMonitor.reportFailure(
                            candidate, err.javaClass.simpleName
                        )
                    }
                }) }
                scope.launch { heartbeatLoop(candidate) }
                io.raventag.app.wallet.health.NodeHealthMonitor.reportSuccess(candidate)
                return@withContext
            } catch (e: Throwable) {
                lastError = e
                if (isTofuMismatch(e)) {
                    io.raventag.app.wallet.health.NodeHealthMonitor.reportTofuMismatch(candidate)
                } else {
                    io.raventag.app.wallet.health.NodeHealthMonitor.reportFailure(
                        candidate, e.javaClass.simpleName
                    )
                }
            }
        }
        events.emit(ScripthashEvent.AllNodesDown)
    }

    private suspend fun heartbeatLoop(candidate: String) {
        while (kotlin.coroutines.coroutineContext[kotlinx.coroutines.Job]?.isActive == true) {
            kotlinx.coroutines.delay(60_000L)
            try {
                session?.ping()
            } catch (e: Throwable) {
                io.raventag.app.wallet.health.NodeHealthMonitor.reportFailure(candidate, "ping_timeout")
                events.emit(ScripthashEvent.ConnectionLost)
                break
            }
        }
    }

    private fun isTofuMismatch(e: Throwable): Boolean {
        if (e is java.security.cert.CertificateException) return true
        val m = e.message ?: return false
        return m.contains("Certificate mismatch", ignoreCase = true) ||
            m.contains("fingerprint mismatch", ignoreCase = true) ||
            m.contains("TOFU", ignoreCase = true)
    }
    ```
    If plan 30-03 used a different signature for `readLoop` (without the `onError` callback), either extend it (preferred) or inline the error branch into the existing `readLoop` implementation. Identify the exact signature at execution time.

    Em-dash audit: `! grep -P '\u2014' android/app/src/main/java/io/raventag/app/wallet/RavencoinPublicNode.kt android/app/src/main/java/io/raventag/app/wallet/subscription/SubscriptionManager.kt android/app/src/main/java/io/raventag/app/wallet/WalletExceptions.kt`.
  </action>
  <verify>
    <automated>cd android && ./gradlew :app:assembleConsumerDebug -q 2>&1 | tail -20</automated>
  </verify>
  <acceptance_criteria>
    - `grep -q "NodeHealthMonitor.nextHealthyNode" android/app/src/main/java/io/raventag/app/wallet/RavencoinPublicNode.kt`
    - `grep -q "NodeHealthMonitor.reportSuccess" android/app/src/main/java/io/raventag/app/wallet/RavencoinPublicNode.kt`
    - `grep -q "NodeHealthMonitor.reportTofuMismatch" android/app/src/main/java/io/raventag/app/wallet/RavencoinPublicNode.kt`
    - `grep -q "AllNodesUnreachableException" android/app/src/main/java/io/raventag/app/wallet/RavencoinPublicNode.kt`
    - `grep -q "isTofuMismatch" android/app/src/main/java/io/raventag/app/wallet/RavencoinPublicNode.kt`
    - `grep -q "class AllNodesUnreachableException" android/app/src/main/java/io/raventag/app/wallet/WalletExceptions.kt`
    - `grep -q "NodeHealthMonitor.nextHealthyNode" android/app/src/main/java/io/raventag/app/wallet/subscription/SubscriptionManager.kt`
    - `grep -q "NodeHealthMonitor.reportTofuMismatch" android/app/src/main/java/io/raventag/app/wallet/subscription/SubscriptionManager.kt`
    - `grep -q "heartbeatLoop\\|delay(60_000L)" android/app/src/main/java/io/raventag/app/wallet/subscription/SubscriptionManager.kt`
    - `! grep -P '\u2014' android/app/src/main/java/io/raventag/app/wallet/RavencoinPublicNode.kt`
    - `! grep -P '\u2014' android/app/src/main/java/io/raventag/app/wallet/subscription/SubscriptionManager.kt`
    - `! grep -P '\u2014' android/app/src/main/java/io/raventag/app/wallet/WalletExceptions.kt`
    - `cd android && ./gradlew :app:assembleConsumerDebug` exits 0.
  </acceptance_criteria>
  <done>Both RPC and subscription paths consult NodeHealthMonitor before connecting and report outcome after. TOFU mismatch quarantines 1h. 60s heartbeat detects zombie sockets. All-nodes-down emits ScripthashEvent.AllNodesDown.</done>
</task>

<task type="auto" tdd="true">
  <name>Task 3: Fix NetworkModule duplicate timeouts + extend AppConfig ElectrumX node list</name>
  <files>
    android/app/src/main/java/io/raventag/app/network/NetworkModule.kt,
    android/app/src/main/java/io/raventag/app/config/AppConfig.kt,
    android/app/src/main/java/io/raventag/app/MainActivity.kt
  </files>
  <read_first>
    @.planning/codebase/CONCERNS.md,
    @.planning/phases/30-wallet-reliability/30-CONTEXT.md#L104-L107,
    @.planning/phases/30-wallet-reliability/30-RESEARCH.md#L531-L537,
    @.planning/phases/30-wallet-reliability/30-RESEARCH.md#L723-L731,
    @android/app/src/main/java/io/raventag/app/network/NetworkModule.kt,
    @android/app/src/main/java/io/raventag/app/config/AppConfig.kt,
    @android/app/src/main/java/io/raventag/app/MainActivity.kt
  </read_first>
  <behavior>
    **NetworkModule.kt:**
    The existing file per CONCERNS.md has DUPLICATE `connectTimeout` / `readTimeout` calls around lines 82-84. Inspect the OkHttpClient builder chain and remove the duplicate lines. Keep exactly one pair matching D-10:
    ```kotlin
    .connectTimeout(10, TimeUnit.SECONDS)
    .readTimeout(20, TimeUnit.SECONDS)
    ```
    If the file uses a `Duration.ofSeconds(...)` API, normalize to `TimeUnit.SECONDS` for consistency with the rest of the codebase (unless the existing style uses Duration — then keep Duration).

    **AppConfig.kt:**
    Promote the ElectrumX node list from `RavencoinPublicNode.kt` private field to a top-level `const val` / `val` in `AppConfig`. Keep the existing in-file list in sync (or replace the in-file `SERVERS` with `AppConfig.ELECTRUM_SERVERS`).

    Current 4 hosts per RESEARCH A3:
    - `rvn4lyfe.com:50002`
    - `rvn-dashboard.com:50002` (flagged LOW confidence — may no longer be SSL-enabled in 2026)
    - `162.19.153.65:50002`
    - `51.222.139.25:50002`

    Per RESEARCH Pitfall 8 + Open Question 4, the goal is 3-5 VERIFIED-LIVE servers. Two options at execution time:
    (a) Runtime connectivity check (preferred but out-of-scope for this plan since the implementer would need to run the app on a live network).
    (b) Documentary approach: keep the current 4, add inline KDoc explaining:
        - List was researched in 2026-04; contains 4 public servers.
        - `rvn-dashboard.com` MAY be stale; quarantine will handle silently.
        - Future phase: add user-configurable list (Deferred).
        - If community confirms additional live hosts (e.g. via `github.com/Electrum-RVN-SIG/electrum-ravencoin/blob/master/electrum/servers.json`), add them here.

    Take approach (b) — documentary KDoc + keep all 4 hosts. Do NOT remove any; quarantine handles staleness.

    **MainActivity.kt:**
    Add `NodeHealthMonitor.init(this)` in `onCreate` right after `QuarantineDao.init(this)` (which plan 30-02 placed immediately after `WalletReliabilityDb.init(this)`). Sequencing matters: WalletReliabilityDb → QuarantineDao is auto-init'd-inside but we call it explicitly for startup; then NodeHealthMonitor.
  </behavior>
  <action>
    **NetworkModule.kt** — read the file, find the OkHttpClient builder. The CONCERNS.md flags duplicate calls at lines 82-84. Remove the duplicate pair. Final builder chain must contain EXACTLY one `connectTimeout(10, TimeUnit.SECONDS)` and one `readTimeout(20, TimeUnit.SECONDS)` call. Verify with grep:
    - `grep -c "connectTimeout" android/app/src/main/java/io/raventag/app/network/NetworkModule.kt` returns 1
    - `grep -c "readTimeout" android/app/src/main/java/io/raventag/app/network/NetworkModule.kt` returns 1

    **AppConfig.kt** — add (or relocate from RavencoinPublicNode.kt):
    ```kotlin
    /**
     * D-09: Hardcoded public ElectrumX fallback pool. Round-robin via NodeHealthMonitor.
     *
     * Researched 2026-04 from:
     *   - github.com/Electrum-RVN-SIG/electrum-ravencoin servers.json (3 hosts)
     *   - rvn4lyfe.com operator-hosted (confirms 4th host 51.222.139.25)
     *
     * Note: `rvn-dashboard.com` may have rotated off SSL — quarantine handles silently
     * (D-11 1h quarantine on TOFU mismatch). If future community list expands, add hosts
     * here (no user-configurable list in v1, deferred to a later "power user" phase).
     *
     * Current count: 4 (marginal per RESEARCH Pitfall 8; a single cert rotation leaves 3
     * operational which is acceptable for D-09).
     */
    val ELECTRUM_SERVERS: List<io.raventag.app.wallet.ElectrumServer> = listOf(
        io.raventag.app.wallet.ElectrumServer("rvn4lyfe.com", 50002),
        io.raventag.app.wallet.ElectrumServer("rvn-dashboard.com", 50002),
        io.raventag.app.wallet.ElectrumServer("162.19.153.65", 50002),
        io.raventag.app.wallet.ElectrumServer("51.222.139.25", 50002),
    )
    ```
    Handle cyclic import: `ElectrumServer` lives in the `wallet` package. If AppConfig currently does not import from `wallet`, add the import. Verify build passes.

    In `RavencoinPublicNode.kt`, replace the private `SERVERS` field with `private val SERVERS get() = AppConfig.ELECTRUM_SERVERS` (a lazy indirection so existing in-file iterations keep working). Alternative acceptable: keep `private val SERVERS = AppConfig.ELECTRUM_SERVERS` evaluated once at class init.

    **MainActivity.kt** — locate the init sequence (plan 30-02 added `WalletReliabilityDb.init(this)`; plan 30-05 added `ReservedUtxoDao.pruneOlderThan(...)`). Add `io.raventag.app.wallet.health.NodeHealthMonitor.init(this)` immediately after those calls. Example final sequence:
    ```kotlin
    io.raventag.app.wallet.cache.WalletReliabilityDb.init(this)
    io.raventag.app.wallet.cache.ReservedUtxoDao.pruneOlderThan(
        System.currentTimeMillis() - 48L * 3600_000L
    )
    io.raventag.app.wallet.health.NodeHealthMonitor.init(this)
    ```

    Em-dash audit on all three files.
  </action>
  <verify>
    <automated>cd android && ./gradlew :app:assembleConsumerDebug -q 2>&1 | tail -20</automated>
  </verify>
  <acceptance_criteria>
    - `[ $(grep -c 'connectTimeout' android/app/src/main/java/io/raventag/app/network/NetworkModule.kt) -eq 1 ]`
    - `[ $(grep -c 'readTimeout' android/app/src/main/java/io/raventag/app/network/NetworkModule.kt) -eq 1 ]`
    - `grep -q "connectTimeout(10" android/app/src/main/java/io/raventag/app/network/NetworkModule.kt`
    - `grep -q "readTimeout(20" android/app/src/main/java/io/raventag/app/network/NetworkModule.kt`
    - `grep -q "ELECTRUM_SERVERS" android/app/src/main/java/io/raventag/app/config/AppConfig.kt`
    - `grep -q "rvn4lyfe.com" android/app/src/main/java/io/raventag/app/config/AppConfig.kt`
    - `grep -q "51.222.139.25" android/app/src/main/java/io/raventag/app/config/AppConfig.kt`
    - `grep -q "NodeHealthMonitor.init(this)" android/app/src/main/java/io/raventag/app/MainActivity.kt`
    - `! grep -P '\u2014' android/app/src/main/java/io/raventag/app/network/NetworkModule.kt`
    - `! grep -P '\u2014' android/app/src/main/java/io/raventag/app/config/AppConfig.kt`
    - `! grep -P '\u2014' android/app/src/main/java/io/raventag/app/MainActivity.kt`
    - `cd android && ./gradlew :app:assembleConsumerDebug` exits 0.
  </acceptance_criteria>
  <done>NetworkModule has exactly one timeout pair matching D-10. AppConfig exports ELECTRUM_SERVERS with KDoc noting freshness and quarantine handling. MainActivity wires NodeHealthMonitor.init. Build passes.</done>
</task>

</tasks>

<threat_model>
## Trust Boundaries

| Boundary | Description |
|----------|-------------|
| app → ElectrumX (TLS) | TOFU pin (Phase 10 SQLite) + 1h quarantine on mismatch (D-11). Rotation indistinguishable from MITM; fail-closed. |
| NodeHealthMonitor → QuarantineDao | Singleton in-memory cache is authoritative for "recent failure" signals (<60s); SQLite is authoritative for long-lived quarantine rows. |
| UI → NodeHealthMonitor.stateFlow | read-only reactive source of D-12 pill color. |

## STRIDE Threat Register

| Threat ID | Category | Component | Disposition | Mitigation Plan |
|-----------|----------|-----------|-------------|-----------------|
| T-30-NET-01 | Spoofing | MITM on ElectrumX TLS first connection | mitigate | TOFU pin from Phase 10 SQLite (unchanged). Subscription socket now shares the same TofuTrustManager per plan 30-03. |
| T-30-NET-02 | Spoofing | Cert rotation indistinguishable from MITM | accept | D-11 1h quarantine. If ALL hosts quarantined → UI shows RED pill + disables Send/Receive. User guidance is deferred to a later phase; silent per D-11. |
| T-30-NET-03 | Denial of Service | All public nodes offline | mitigate | NodeHealthMonitor.stateFlow emits RED → UI disables Send/Receive (plan 30-08). StateFlow drives UX without faking a connection. |
| T-30-NET-04 | Tampering | Attacker steers user to a malicious node via DNS hijack | mitigate | TLS + TOFU blocks. Quarantine persists across reboots (SQLite). |
| T-30-NET-05 | Denial of Service | Flapping (success/failure churn) causes pill color thrash | mitigate | Cooldown windows (30s transient, 60s green-recency). State transitions use recent-time windows, not per-call flips. |
| T-30-RECV-01 | Spoofing | Subscription socket reconnects to attacker after network change | mitigate | Reconnect still goes through TofuTrustManager + QuarantineDao; TOFU pin blocks different cert. |
| T-30-RECV-02 | Spoofing | Malicious notification on compromised socket | mitigate | Notifications are status hashes only (not balances). Any status change triggers a re-fetch via trusted TLS + TOFU. Plan 30-08 never writes balance from subscription payload. |

ASVS V9 Communications (TLS + TOFU), V7 Error Handling (typed exception for all-nodes-down), V14 Configuration (node list in code, no runtime mutability in v1).
</threat_model>

<verification>
- `cd android && ./gradlew :app:assembleConsumerDebug` exits 0.
- NetworkModule has exactly ONE connectTimeout + readTimeout pair (grep count == 1).
- `grep -rn "NodeHealthMonitor" android/app/src/main/java/io/raventag/app/wallet/` returns at least one line in `RavencoinPublicNode.kt` and one in `subscription/SubscriptionManager.kt`.
- `grep -n "ELECTRUM_SERVERS" android/app/src/main/java/io/raventag/app/config/AppConfig.kt` returns the list definition.
- Manual verification (per 30-VALIDATION.md Manual-Only row #3):
  1. Tamper `electrum_certificates.db` entry (swap fingerprint for rvn4lyfe.com).
  2. Restart app.
  3. Expect quarantine logged + YELLOW pill if fallbacks remain / RED if all quarantined.
  4. Advance system clock 1h, expect auto-retry.
- No em dashes anywhere in touched files.
</verification>

<success_criteria>
- NodeHealthMonitor.kt compiles with ConnectionHealth StateFlow and 1h quarantine constant.
- Both RavencoinPublicNode.callWithFailover and SubscriptionManager.start consult nextHealthyNode() and report success/failure/TOFU mismatch.
- NetworkModule single timeout pair per D-10.
- AppConfig.ELECTRUM_SERVERS sourced with documented provenance.
- MainActivity wires NodeHealthMonitor.init at startup.
- Transient RPC failures (<30s) do not flip pill to YELLOW; all-nodes-quarantined flips to RED.
- No em dashes anywhere in touched files.
</success_criteria>

<output>
Create `.planning/phases/30-wallet-reliability/30-07-SUMMARY.md`:
- Exact line numbers of removed duplicate-timeout calls in NetworkModule.kt (before → after).
- Identification of the exact TOFU-mismatch exception class / message pattern emitted by TofuTrustManager (found at execution time).
- Confirmation that the node list was NOT trimmed (all 4 hosts retained with KDoc).
- Confirmation that both workers (WalletPollingWorker, RebroadcastWorker) call `NodeHealthMonitor.init(applicationContext)` at the top of `doWork` — or note if additional wiring is needed in a later plan.
- Hand-off to plan 30-08: `NodeHealthMonitor.stateFlow` is the single StateFlow source for the connection pill and `NodeHealthMonitor.diagnostics()` feeds the bottom sheet.
- Hand-off to plan 30-08: `AllNodesUnreachableException` is the signal for the "Offline · all nodes unreachable" disabled-state snackbar.
</output>
