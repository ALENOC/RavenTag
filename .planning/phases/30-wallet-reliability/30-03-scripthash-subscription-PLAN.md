---
id: 30-03-scripthash-subscription
phase: 30
plan: 03
type: execute
wave: 1
depends_on:
  - 30-01-wave0-test-scaffolding
files_modified:
  - android/app/src/main/java/io/raventag/app/wallet/TofuTrustManager.kt
  - android/app/src/main/java/io/raventag/app/wallet/RavencoinPublicNode.kt
  - android/app/src/main/java/io/raventag/app/wallet/subscription/ScripthashEvent.kt
  - android/app/src/main/java/io/raventag/app/wallet/subscription/SubscriptionParser.kt
  - android/app/src/main/java/io/raventag/app/wallet/subscription/SubscriptionManager.kt
autonomous: true
requirements:
  - WALLET-RECV
  - WALLET-BAL
threat_refs:
  - T-30-RECV
  - T-30-NET

must_haves:
  truths:
    - "A long-lived TLS socket (separate from RPC) per foreground WalletScreen session subscribes to each wallet scripthash and delivers notifications as a Kotlin Flow (D-05)"
    - "Subscription notifications and RPC responses are correctly routed on the single socket (Pitfall 1: id-matching)"
    - "The subscription uses the SAME TOFU trust manager as RPC sockets (no second security implementation)"
    - "A 60s `server.ping` heartbeat detects zombie mobile-network sockets (Pitfall 2)"
    - "`blockchain.scripthash.subscribe` and `blockchain.estimatefee` RPC entries are reachable from RavencoinPublicNode"
  artifacts:
    - path: "android/app/src/main/java/io/raventag/app/wallet/TofuTrustManager.kt"
      provides: "promoted, internal-visibility TofuTrustManager extracted from RavencoinPublicNode.kt for reuse by SubscriptionManager"
      exports: ["TofuTrustManager"]
    - path: "android/app/src/main/java/io/raventag/app/wallet/subscription/ScripthashEvent.kt"
      provides: "sealed class for subscription events"
      exports: ["ScripthashEvent"]
    - path: "android/app/src/main/java/io/raventag/app/wallet/subscription/SubscriptionParser.kt"
      provides: "pure JSON-RPC line parser (response vs notification routing)"
      exports: ["SubscriptionParser"]
    - path: "android/app/src/main/java/io/raventag/app/wallet/subscription/SubscriptionManager.kt"
      provides: "singleton Flow<ScripthashEvent> source; start(addresses)/stop() lifecycle"
      exports: ["SubscriptionManager"]
  key_links:
    - from: "SubscriptionManager.start"
      to: "TofuTrustManager"
      via: "shared SSLContext init"
      pattern: "TofuTrustManager"
    - from: "RavencoinPublicNode"
      to: "blockchain.scripthash.subscribe + blockchain.estimatefee RPC"
      via: "call() entry extension"
      pattern: "blockchain\\.(scripthash\\.subscribe|estimatefee)"
---

<objective>
Add ElectrumX scripthash subscription (D-05) and fee estimation (D-22) RPC entry points to the existing ElectrumX client, while extracting the `TofuTrustManager` so it can be shared between one-shot RPC calls and the new long-lived subscription socket.

Purpose: enable real-time incoming-tx detection (WALLET-RECV) without a second TLS implementation, and unblock plan 30-04 (FeeEstimator wiring) and plan 30-07 (NodeHealthMonitor).

Output: TofuTrustManager promoted to its own `internal class` file; two new RPC wrappers on `RavencoinPublicNode`; a sealed `ScripthashEvent` class; a pure `SubscriptionParser`; and a `SubscriptionManager` with `Flow<ScripthashEvent>` API, 60s heartbeat, id-matched response routing, and per-server failover via `retryWithBackoff`.
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
@android/app/src/main/java/io/raventag/app/wallet/RavencoinPublicNode.kt
@android/app/src/main/java/io/raventag/app/utils/RetryUtils.kt
@android/app/src/test/java/io/raventag/app/wallet/subscription/SubscriptionParserTest.kt

<interfaces>
**Existing RavencoinPublicNode internals to reuse** (read at implementation time):
- `data class ElectrumServer(val host: String, val port: Int)` (~line 39)
- `private val SERVERS = listOf( ElectrumServer("rvn4lyfe.com", 50002), ElectrumServer("rvn-dashboard.com", 50002), ElectrumServer("162.19.153.65", 50002), ElectrumServer("51.222.139.25", 50002) )` (lines 172-177)
- `private const val CONNECT_TIMEOUT_MS = 10_000` (~line 158) — D-10 matches
- `private const val READ_TIMEOUT_MS = 15_000` (~line 161) — plan 30-07 will raise to 20_000 to match D-10; for subscription use 20_000 directly
- `private val idCounter = AtomicInteger(1)` (~line 185) — reuse
- `private val gson = Gson()` (~line 188) — reuse
- `private class TofuTrustManager(...)` at line 1612-1652 — EXTRACT to own file with `internal` visibility
- `call()` method at ~line 1557 — single-request raw-socket TLS; pattern to study, not modify
- `callWithFailover(method, params)` — the JSONElement-returning helper; add `estimatefee` wrapper on top of this
- `addressToScripthash(address: String): String` — already exists at ~line 290 (per pattern used in getBalance)

**Phase 20 utility** (already imported across the codebase):
```kotlin
package io.raventag.app.utils
suspend fun <T> retryWithBackoff(
    maxAttempts: Int = 5,
    initialDelayMs: Long = 1000L,
    backoffMultiplier: Double = 2.0,
    block: suspend () -> T
): T
```

**Wave 0 test contract for SubscriptionParser** (honor exactly):
```kotlin
object SubscriptionParser {
    sealed class Parsed {
        data class Response(val id: Int, val result: com.google.gson.JsonElement?) : Parsed()
        data class Notification(val scripthash: String, val status: String?) : Parsed()
        data class Unknown(val raw: String) : Parsed()
    }
    fun parseLine(line: String): Parsed
}
```
</interfaces>
</context>

<tasks>

<task type="auto" tdd="true">
  <name>Task 1: Extract TofuTrustManager + add subscribe/estimatefee RPC wrappers to RavencoinPublicNode + create ScripthashEvent + SubscriptionParser</name>
  <files>
    android/app/src/main/java/io/raventag/app/wallet/TofuTrustManager.kt,
    android/app/src/main/java/io/raventag/app/wallet/RavencoinPublicNode.kt,
    android/app/src/main/java/io/raventag/app/wallet/subscription/ScripthashEvent.kt,
    android/app/src/main/java/io/raventag/app/wallet/subscription/SubscriptionParser.kt
  </files>
  <read_first>
    @.planning/phases/30-wallet-reliability/30-RESEARCH.md#L255-L302,
    @.planning/phases/30-wallet-reliability/30-RESEARCH.md#L540-L590,
    @.planning/phases/30-wallet-reliability/30-PATTERNS.md#L117-L163,
    @.planning/phases/30-wallet-reliability/30-PATTERNS.md#L322-L342,
    @android/app/src/main/java/io/raventag/app/wallet/RavencoinPublicNode.kt
  </read_first>
  <behavior>
    - `SubscriptionParser.parseLine` must return:
      - `Parsed.Response(id, result)` for any JSON with an integer `id` field (result may be `JsonNull` or a real element)
      - `Parsed.Notification(scripthash, status)` for `{"method":"blockchain.scripthash.subscribe","params":[scripthash, status]}` where `status` is `null` when `params[1]` is `JsonNull`
      - `Parsed.Unknown(raw)` for any other structure OR malformed JSON (catch-and-return, do NOT throw — the behavior stub `runCatching { ... }` expects either branch)
    - `TofuTrustManager` must be byte-identical in logic to the existing `private class` — only visibility changes (private→internal), file location changes, and imports are new.
    - `RavencoinPublicNode.subscribeScripthashRpc(address: String): String?` — wrapper around `callWithFailover("blockchain.scripthash.subscribe", listOf(addressToScripthash(address)))`. Returns the status hash (or null). Used only for the *one-shot* subscribe on the foreground polling path or the WorkManager worker; the long-lived socket in `SubscriptionManager` uses its own path. Purpose: give `WalletPollingWorker` (plan 30-08) a way to capture the current status for background diff.
    - `RavencoinPublicNode.estimateFeeRvnPerKb(targetBlocks: Int): Double` — wrapper around `callWithFailover("blockchain.estimatefee", listOf(targetBlocks))`. Returns the JSON number as Double. Returns -1.0 on `JsonNull`. Throws the underlying exception on RPC error so `FeeEstimator` (plan 30-04) can catch it and fall back.
  </behavior>
  <action>
    **Step 1 — Extract TofuTrustManager**:
    Read `android/app/src/main/java/io/raventag/app/wallet/RavencoinPublicNode.kt` lines 1612-1652. Copy the entire `TofuTrustManager` class (including companion object if any) into a new file:

    ```kotlin
    // android/app/src/main/java/io/raventag/app/wallet/TofuTrustManager.kt
    package io.raventag.app.wallet

    import android.content.Context
    import io.raventag.app.security.TofuFingerprintDao
    import java.security.MessageDigest
    import java.security.cert.CertificateException
    import java.security.cert.X509Certificate
    import javax.net.ssl.X509TrustManager

    internal class TofuTrustManager(
        private val context: Context,
        private val host: String
    ) : X509TrustManager {
        // ... paste body from RavencoinPublicNode.kt:1612-1652 ...
    }
    ```

    Use the Read tool to get the exact source at lines 1612-1652 plus any companion/helper it references, then paste into the new file.

    **Step 2 — Edit RavencoinPublicNode.kt**:
    - Delete the `private class TofuTrustManager(...)` declaration at the original location.
    - If there was a companion-level constant or helper only used by TofuTrustManager, move it to the new file (or keep it and change the import). Minimize blast radius.
    - At the top of `RavencoinPublicNode.kt` imports, add `import io.raventag.app.wallet.TofuTrustManager` (if it's in the same package, no import needed; the new file IS in `io.raventag.app.wallet`, so no import is needed — just reference `TofuTrustManager` directly).
    - Verify every existing `TofuTrustManager(...)` instantiation still compiles.

    **Step 3 — Add RPC wrappers**:
    Append two new suspend functions to `RavencoinPublicNode.kt`, inside the class body (NOT the companion), next to the existing `getBalance` / `getUtxos` methods so readers find them together:

    ```kotlin
    /**
     * D-05 support — subscribes to a scripthash and returns the current status hash.
     * Uses the one-shot RPC socket; the foreground-session long-lived socket lives in
     * [io.raventag.app.wallet.subscription.SubscriptionManager].
     */
    fun subscribeScripthashRpc(address: String): String? {
        val scripthash = addressToScripthash(address)
        val result = callWithFailover("blockchain.scripthash.subscribe", listOf(scripthash))
        return if (result.isJsonNull) null else result.asString
    }

    /**
     * D-22 support — calls blockchain.estimatefee with a block target and returns
     * the raw RVN/kB number. Returns -1.0 when the server returns null. Callers
     * (FeeEstimator) are responsible for the static-fallback policy.
     */
    fun estimateFeeRvnPerKb(targetBlocks: Int): Double {
        val result = callWithFailover("blockchain.estimatefee", listOf(targetBlocks))
        return if (result.isJsonNull) -1.0 else result.asDouble
    }
    ```

    Note: `callWithFailover` already exists as a `private` helper. If it returns `JsonElement`, above signatures are correct. If it is synchronous (not suspend), keep the wrappers synchronous too — the existing pattern in `getBalance` is the authoritative template. Do NOT suspend-ify if not already suspend.

    **Step 4 — ScripthashEvent.kt**:
    ```kotlin
    // android/app/src/main/java/io/raventag/app/wallet/subscription/ScripthashEvent.kt
    package io.raventag.app.wallet.subscription

    sealed class ScripthashEvent {
        /**
         * ElectrumX pushed a status-hash change for [scripthash]. [newStatus] may be null
         * when the server reports "no history". Caller MUST re-fetch balance/utxo/history
         * per RESEARCH.md §Architecture Pattern 1: subscription only says "something changed".
         */
        data class StatusChanged(val scripthash: String, val newStatus: String?) : ScripthashEvent()
        /** The session socket died (network transition, server reset). */
        data object ConnectionLost : ScripthashEvent()
        /** All fallback servers refused connection. D-12 red pill. */
        data object AllNodesDown : ScripthashEvent()
        /** Ping did not return within 60s — socket is a zombie (Pitfall 2). */
        data object PingTimeout : ScripthashEvent()
    }
    ```

    **Step 5 — SubscriptionParser.kt**:
    ```kotlin
    // android/app/src/main/java/io/raventag/app/wallet/subscription/SubscriptionParser.kt
    package io.raventag.app.wallet.subscription

    import com.google.gson.JsonElement
    import com.google.gson.JsonNull
    import com.google.gson.JsonParser
    import com.google.gson.JsonSyntaxException

    object SubscriptionParser {
        sealed class Parsed {
            data class Response(val id: Int, val result: JsonElement?) : Parsed()
            data class Notification(val scripthash: String, val status: String?) : Parsed()
            data class Unknown(val raw: String) : Parsed()
        }

        fun parseLine(line: String): Parsed {
            if (line.isBlank()) return Parsed.Unknown(line)
            val obj = try {
                JsonParser.parseString(line).asJsonObject
            } catch (_: JsonSyntaxException) { return Parsed.Unknown(line) }
            catch (_: IllegalStateException) { return Parsed.Unknown(line) }

            // id present → response
            val idEl = obj.get("id")
            if (idEl != null && !idEl.isJsonNull) {
                val id = try { idEl.asInt } catch (_: Exception) { return Parsed.Unknown(line) }
                val result: JsonElement? = obj.get("result").takeUnless { it == null || it.isJsonNull }
                return Parsed.Response(id = id, result = result)
            }

            // server notification
            val method = obj.get("method")?.takeUnless { it.isJsonNull }?.asString ?: return Parsed.Unknown(line)
            if (method == "blockchain.scripthash.subscribe") {
                val params = obj.getAsJsonArray("params") ?: return Parsed.Unknown(line)
                if (params.size() < 1) return Parsed.Unknown(line)
                val sh = params.get(0).takeUnless { it.isJsonNull }?.asString ?: return Parsed.Unknown(line)
                val status = if (params.size() >= 2 && !params.get(1).isJsonNull) params.get(1).asString else null
                return Parsed.Notification(scripthash = sh, status = status)
            }
            return Parsed.Unknown(line)
        }
    }
    ```

    Em-dash audit: `! grep -P '\u2014' android/app/src/main/java/io/raventag/app/wallet/TofuTrustManager.kt android/app/src/main/java/io/raventag/app/wallet/subscription/ScripthashEvent.kt android/app/src/main/java/io/raventag/app/wallet/subscription/SubscriptionParser.kt`. Also audit the RavencoinPublicNode.kt diff hunk (visual inspection + `git diff -- android/app/src/main/java/io/raventag/app/wallet/RavencoinPublicNode.kt | grep -P '\u2014'` returns empty).
  </action>
  <verify>
    <automated>cd android && ./gradlew :app:testConsumerDebugUnitTest --tests "io.raventag.app.wallet.subscription.SubscriptionParserTest" -i 2>&1 | tail -30</automated>
  </verify>
  <acceptance_criteria>
    - `test -f android/app/src/main/java/io/raventag/app/wallet/TofuTrustManager.kt`
    - `grep -q "internal class TofuTrustManager" android/app/src/main/java/io/raventag/app/wallet/TofuTrustManager.kt`
    - `! grep -q "private class TofuTrustManager" android/app/src/main/java/io/raventag/app/wallet/RavencoinPublicNode.kt` (old private class removed)
    - `grep -q "fun subscribeScripthashRpc" android/app/src/main/java/io/raventag/app/wallet/RavencoinPublicNode.kt`
    - `grep -q "fun estimateFeeRvnPerKb" android/app/src/main/java/io/raventag/app/wallet/RavencoinPublicNode.kt`
    - `grep -q "blockchain\\.scripthash\\.subscribe" android/app/src/main/java/io/raventag/app/wallet/RavencoinPublicNode.kt`
    - `grep -q "blockchain\\.estimatefee" android/app/src/main/java/io/raventag/app/wallet/RavencoinPublicNode.kt`
    - `test -f android/app/src/main/java/io/raventag/app/wallet/subscription/ScripthashEvent.kt`
    - `grep -q "sealed class ScripthashEvent" android/app/src/main/java/io/raventag/app/wallet/subscription/ScripthashEvent.kt`
    - `grep -q "data class StatusChanged" android/app/src/main/java/io/raventag/app/wallet/subscription/ScripthashEvent.kt`
    - `grep -q "data object ConnectionLost" android/app/src/main/java/io/raventag/app/wallet/subscription/ScripthashEvent.kt`
    - `grep -q "data object AllNodesDown" android/app/src/main/java/io/raventag/app/wallet/subscription/ScripthashEvent.kt`
    - `test -f android/app/src/main/java/io/raventag/app/wallet/subscription/SubscriptionParser.kt`
    - `grep -q "object SubscriptionParser" android/app/src/main/java/io/raventag/app/wallet/subscription/SubscriptionParser.kt`
    - `grep -q "sealed class Parsed" android/app/src/main/java/io/raventag/app/wallet/subscription/SubscriptionParser.kt`
    - `! grep -P '\u2014' android/app/src/main/java/io/raventag/app/wallet/TofuTrustManager.kt android/app/src/main/java/io/raventag/app/wallet/subscription/ScripthashEvent.kt android/app/src/main/java/io/raventag/app/wallet/subscription/SubscriptionParser.kt`
    - `cd android && ./gradlew :app:testConsumerDebugUnitTest --tests "*SubscriptionParserTest*"` exits 0 (all parser tests GREEN).
    - `cd android && ./gradlew :app:assembleConsumerDebug` exits 0.
  </acceptance_criteria>
  <done>TofuTrustManager extracted and shared. Subscribe/estimatefee RPC wrappers exist. Sealed event class exists. Parser passes all Wave 0 tests.</done>
</task>

<task type="auto" tdd="true">
  <name>Task 2: Create SubscriptionManager with persistent TLS socket, id-matched response routing, 60s ping heartbeat, and Flow API</name>
  <files>
    android/app/src/main/java/io/raventag/app/wallet/subscription/SubscriptionManager.kt
  </files>
  <read_first>
    @.planning/phases/30-wallet-reliability/30-RESEARCH.md#L255-L302,
    @.planning/phases/30-wallet-reliability/30-RESEARCH.md#L467-L485,
    @.planning/phases/30-wallet-reliability/30-RESEARCH.md#L540-L590,
    @.planning/phases/30-wallet-reliability/30-PATTERNS.md#L117-L163,
    @android/app/src/main/java/io/raventag/app/wallet/RavencoinPublicNode.kt,
    @android/app/src/main/java/io/raventag/app/wallet/TofuTrustManager.kt,
    @android/app/src/main/java/io/raventag/app/wallet/subscription/SubscriptionParser.kt,
    @android/app/src/main/java/io/raventag/app/utils/RetryUtils.kt
  </read_first>
  <behavior>
    - `start(addresses: List<String>)` opens a single TLS socket to the first reachable server; performs `server.version` handshake; issues `blockchain.scripthash.subscribe` for each address (converted to scripthash); launches the reader coroutine that routes frames via `SubscriptionParser`; launches a 60-second `server.ping` heartbeat coroutine. On ALL servers failing, emits `ScripthashEvent.AllNodesDown` and does not retry until `start()` is called again.
    - On read error (socket exception, `readLine()` returns null, ping timeout): emits `ConnectionLost`; closes socket; caller decides whether to restart (plan 30-07 NodeHealthMonitor + plan 30-08 UI wire both do).
    - `stop()` cancels the scope, closes socket, clears session state.
    - `eventsFlow(): SharedFlow<ScripthashEvent>` — public read-only flow.
    - Thread-safety: `start()`/`stop()` synchronized on the manager instance; reader loop runs on `Dispatchers.IO`.
    - No mnemonic or seed ever touches this class; only address strings (→ scripthashes).
  </behavior>
  <action>
    Create `android/app/src/main/java/io/raventag/app/wallet/subscription/SubscriptionManager.kt`:

    ```kotlin
    package io.raventag.app.wallet.subscription

    import android.content.Context
    import com.google.gson.Gson
    import io.raventag.app.utils.retryWithBackoff
    import io.raventag.app.wallet.TofuTrustManager
    import kotlinx.coroutines.CoroutineScope
    import kotlinx.coroutines.Dispatchers
    import kotlinx.coroutines.Job
    import kotlinx.coroutines.SupervisorJob
    import kotlinx.coroutines.cancel
    import kotlinx.coroutines.delay
    import kotlinx.coroutines.flow.MutableSharedFlow
    import kotlinx.coroutines.flow.SharedFlow
    import kotlinx.coroutines.flow.asSharedFlow
    import kotlinx.coroutines.launch
    import kotlinx.coroutines.withContext
    import kotlinx.coroutines.withTimeoutOrNull
    import java.io.BufferedReader
    import java.io.InputStreamReader
    import java.io.PrintWriter
    import java.net.InetSocketAddress
    import java.net.Socket
    import java.security.MessageDigest
    import java.security.SecureRandom
    import java.util.concurrent.ConcurrentHashMap
    import java.util.concurrent.atomic.AtomicInteger
    import javax.net.ssl.SSLContext
    import javax.net.ssl.SSLSocket
    import kotlin.coroutines.coroutineContext

    /**
     * D-05: long-lived TLS socket per WalletScreen foreground session. Emits
     * [ScripthashEvent] for each blockchain.scripthash.subscribe notification.
     *
     * SEPARATE SOCKET from RavencoinPublicNode.call() (Pitfall 1):
     * asynchronous notifications cannot share a synchronous read path.
     */
    class SubscriptionManager(
        private val context: Context,
        private val servers: List<Pair<String, Int>> = DEFAULT_SERVERS,
        private val connectTimeoutMs: Int = 10_000,
        private val readTimeoutMs: Int = 20_000,
        private val pingIntervalMs: Long = 60_000L
    ) {
        private val events = MutableSharedFlow<ScripthashEvent>(extraBufferCapacity = 64)
        private val gson = Gson()
        private val idCounter = AtomicInteger(1)
        private val pending = ConcurrentHashMap<Int, (com.google.gson.JsonElement?) -> Unit>()
        private var scope: CoroutineScope? = null
        private var session: Session? = null
        private val lifecycleLock = Any()

        fun eventsFlow(): SharedFlow<ScripthashEvent> = events.asSharedFlow()

        suspend fun start(addresses: List<String>): Unit = withContext(Dispatchers.IO) {
            synchronized(lifecycleLock) {
                if (session != null) return@withContext // already running
                scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            }

            var opened: Session? = null
            for ((host, port) in servers) {
                try {
                    opened = openSession(host, port)
                    break
                } catch (e: Exception) { /* try next */ }
            }
            if (opened == null) {
                events.emit(ScripthashEvent.AllNodesDown)
                synchronized(lifecycleLock) { scope?.cancel(); scope = null }
                return@withContext
            }
            synchronized(lifecycleLock) { session = opened }

            // Handshake
            opened.rpc("server.version", listOf("RavenTag/1.0", "1.4"))

            // Subscribe per address
            for (addr in addresses) {
                val sh = addressToScripthash(addr)
                try { opened.rpc("blockchain.scripthash.subscribe", listOf(sh)) }
                catch (_: Exception) { /* log and continue; readLoop may already deliver */ }
            }

            // Reader loop
            scope!!.launch { readLoop(opened) }
            // Heartbeat loop
            scope!!.launch { heartbeatLoop(opened) }
        }

        suspend fun stop() = withContext(Dispatchers.IO) {
            synchronized(lifecycleLock) {
                scope?.cancel(); scope = null
                try { session?.socket?.close() } catch (_: Exception) {}
                session = null
                pending.clear()
            }
        }

        // --- internal helpers ---

        private data class Session(
            val host: String,
            val socket: SSLSocket,
            val writer: PrintWriter,
            val reader: BufferedReader
        ) {
            suspend fun rpc(
                method: String,
                params: List<Any?>
            ): com.google.gson.JsonElement? {
                // Handled in SubscriptionManager via id-callback; see sendAndAwait
                return null
            }
        }

        private fun openSession(host: String, port: Int): Session {
            val ctx = SSLContext.getInstance("TLS")
            ctx.init(null, arrayOf(TofuTrustManager(context, host)), SecureRandom())
            val raw = Socket()
            raw.connect(InetSocketAddress(host, port), connectTimeoutMs)
            val ssl = ctx.socketFactory.createSocket(raw, host, port, true) as SSLSocket
            ssl.soTimeout = readTimeoutMs
            ssl.keepAlive = true
            val writer = PrintWriter(ssl.outputStream, true)
            val reader = BufferedReader(InputStreamReader(ssl.inputStream))
            return Session(host, ssl, writer, reader)
        }

        private suspend fun readLoop(s: Session) {
            try {
                while (coroutineContext[Job]?.isActive == true) {
                    val line = withContext(Dispatchers.IO) { s.reader.readLine() }
                    if (line == null) {
                        events.emit(ScripthashEvent.ConnectionLost); return
                    }
                    when (val parsed = SubscriptionParser.parseLine(line)) {
                        is SubscriptionParser.Parsed.Response -> {
                            pending.remove(parsed.id)?.invoke(parsed.result)
                        }
                        is SubscriptionParser.Parsed.Notification -> {
                            events.emit(ScripthashEvent.StatusChanged(parsed.scripthash, parsed.status))
                        }
                        is SubscriptionParser.Parsed.Unknown -> { /* ignore */ }
                    }
                }
            } catch (_: Exception) {
                events.emit(ScripthashEvent.ConnectionLost)
            }
        }

        private suspend fun heartbeatLoop(s: Session) {
            try {
                while (coroutineContext[Job]?.isActive == true) {
                    delay(pingIntervalMs)
                    val result = withTimeoutOrNull(pingIntervalMs) {
                        sendAndAwait(s, "server.ping", emptyList<Any?>())
                    }
                    if (result == null) {
                        events.emit(ScripthashEvent.PingTimeout); return
                    }
                }
            } catch (_: Exception) { events.emit(ScripthashEvent.ConnectionLost) }
        }

        private suspend fun sendAndAwait(
            s: Session,
            method: String,
            params: List<Any?>
        ): com.google.gson.JsonElement? {
            val id = idCounter.getAndIncrement()
            val deferred = kotlinx.coroutines.CompletableDeferred<com.google.gson.JsonElement?>()
            pending[id] = { deferred.complete(it) }
            val payload = gson.toJson(mapOf("id" to id, "method" to method, "params" to params))
            withContext(Dispatchers.IO) { s.writer.println(payload) }
            return deferred.await()
        }

        /** Bitcoin-style scripthash: SHA256 of scriptPubKey, reversed. We accept the caller to supply the P2PKH address and derive here via the standard formula. */
        private fun addressToScripthash(address: String): String {
            // Use RavencoinPublicNode.addressToScripthash via reflection-free path: re-implement or route through the node.
            // Simplest: require the caller to pass scripthashes. Keep this signature internal and do the conversion upstream.
            // For this class we take the address and use the same algorithm as RavencoinPublicNode.
            val node = io.raventag.app.wallet.RavencoinPublicNode(context)
            // addressToScripthash is private/internal in RavencoinPublicNode. Prefer: add a public helper
            // `fun addressToScripthash(address: String): String` to RavencoinPublicNode as part of this task
            // (already present per getBalance usage; verify at implementation time and promote to `fun` / remove `private` if needed).
            return node.addressToScripthash(address)
        }

        companion object {
            val DEFAULT_SERVERS: List<Pair<String, Int>> = listOf(
                "rvn4lyfe.com" to 50002,
                "rvn-dashboard.com" to 50002,
                "162.19.153.65" to 50002,
                "51.222.139.25" to 50002
            )
        }
    }
    ```

    **Implementation note**: `RavencoinPublicNode.addressToScripthash` is currently private. At implementation time, verify this. If private, promote it to `internal` (or public `fun`) visibility in `RavencoinPublicNode.kt`. The change is a one-line visibility swap; add an acceptance grep below.

    Wrap the `start()` body's `for ((host, port) in servers)` connect loop in `retryWithBackoff(maxAttempts = 2, initialDelayMs = 500L, backoffMultiplier = 2.0) { ... }` for a single server — but the outer loop already provides failover. The retry is ONLY for transient connection errors on a single server before moving to the next. Prefer the simpler outer-loop-only approach; add retry only if the per-server open regularly flakes.

    Em-dash audit: `! grep -P '\u2014' android/app/src/main/java/io/raventag/app/wallet/subscription/SubscriptionManager.kt`.
  </action>
  <verify>
    <automated>cd android && ./gradlew :app:assembleConsumerDebug -q 2>&1 | tail -15</automated>
  </verify>
  <acceptance_criteria>
    - `test -f android/app/src/main/java/io/raventag/app/wallet/subscription/SubscriptionManager.kt`
    - `grep -q "class SubscriptionManager" android/app/src/main/java/io/raventag/app/wallet/subscription/SubscriptionManager.kt`
    - `grep -q "fun eventsFlow" android/app/src/main/java/io/raventag/app/wallet/subscription/SubscriptionManager.kt`
    - `grep -q "suspend fun start" android/app/src/main/java/io/raventag/app/wallet/subscription/SubscriptionManager.kt`
    - `grep -q "suspend fun stop" android/app/src/main/java/io/raventag/app/wallet/subscription/SubscriptionManager.kt`
    - `grep -q "TofuTrustManager" android/app/src/main/java/io/raventag/app/wallet/subscription/SubscriptionManager.kt`
    - `grep -q "keepAlive = true" android/app/src/main/java/io/raventag/app/wallet/subscription/SubscriptionManager.kt`
    - `grep -q "server.ping\|heartbeatLoop" android/app/src/main/java/io/raventag/app/wallet/subscription/SubscriptionManager.kt`
    - `grep -q "PingTimeout\|ConnectionLost\|AllNodesDown" android/app/src/main/java/io/raventag/app/wallet/subscription/SubscriptionManager.kt`
    - `grep -q "SubscriptionParser.parseLine" android/app/src/main/java/io/raventag/app/wallet/subscription/SubscriptionManager.kt`
    - `grep -q "internal fun addressToScripthash\|fun addressToScripthash" android/app/src/main/java/io/raventag/app/wallet/RavencoinPublicNode.kt` (verify promotion if needed)
    - `! grep -P '\u2014' android/app/src/main/java/io/raventag/app/wallet/subscription/SubscriptionManager.kt`
    - `cd android && ./gradlew :app:assembleConsumerDebug` exits 0.
    - `cd android && ./gradlew :app:testConsumerDebugUnitTest --tests "io.raventag.app.wallet.subscription.*"` exits 0.
  </acceptance_criteria>
  <done>
    SubscriptionManager class compiles, uses shared TofuTrustManager, routes frames via SubscriptionParser, has a 60s ping heartbeat with read timeout, emits the four ScripthashEvent variants, and keeps the existing RavencoinPublicNode RPC socket semantics untouched. No em dashes.
  </done>
</task>

</tasks>

<threat_model>
## Trust Boundaries

| Boundary | Description |
|----------|-------------|
| app → public ElectrumX nodes (TLS) | untrusted server; TOFU-pinned per D-11 |
| subscription socket ↔ RPC socket | same trust boundary but ISOLATED framing contexts (Pitfall 1) |

## STRIDE Threat Register

| Threat ID | Category | Component | Disposition | Mitigation Plan |
|-----------|----------|-----------|-------------|-----------------|
| T-30-RECV-01 | Spoofing | Malicious server pushes forged `scripthash.subscribe` notification | mitigate | Notification only triggers a re-fetch via RavencoinPublicNode RPC; the RPC result is authoritative (RESEARCH.md §Pattern 1 invariants). No balance comes from the notification directly. |
| T-30-RECV-02 | Tampering | MITM on subscription socket on reconnect | mitigate | Shared TofuTrustManager — same TLS fingerprint pinning as Phase 10 RPC path. Mismatch → disconnect + plan 30-07 quarantine. |
| T-30-NET-02 | Denial of Service | Zombie mobile socket (WiFi→LTE) (Pitfall 2) | mitigate | TCP keepAlive + 60s application-level `server.ping` heartbeat with `withTimeoutOrNull`; emits PingTimeout → UI triggers reconnect. |
| T-30-RECV-03 | Spoofing | Attacker sends response without id to steal subscribe ACK (Pitfall 1) | mitigate | SubscriptionParser routes by presence of `id`; responses without id fall into `Notification`/`Unknown` and cannot satisfy `pending[id]`. |
| T-30-NET-03 | Information Disclosure | Address list leaked to server | accept | ElectrumX servers MUST see scripthashes to deliver subscriptions. Standard protocol. User-level fix is Tor (deferred). ASVS V9.2. |

ASVS controls: V6.2.5 (standard TLS), V9.1 (TLS), V9.2.2 (no weak ciphers — inherits from platform default). V5.1 (input validation) enforced by `SubscriptionParser`.
</threat_model>

<verification>
- `cd android && ./gradlew :app:assembleConsumerDebug` exits 0.
- `cd android && ./gradlew :app:testConsumerDebugUnitTest --tests "io.raventag.app.wallet.subscription.SubscriptionParserTest"` exits 0 (all 6 parser tests GREEN).
- `grep -r "internal class TofuTrustManager" android/app/src/main/java/io/raventag/app/wallet/` returns exactly one hit (new file).
- `! grep -r "private class TofuTrustManager" android/app/src/main/java/io/raventag/app/wallet/RavencoinPublicNode.kt`
- `grep -r "addressToScripthash" android/app/src/main/java/io/raventag/app/wallet/RavencoinPublicNode.kt` shows the function is callable from outside the class (either internal or public).
- No em dashes in any file touched by this plan.
</verification>

<success_criteria>
- TofuTrustManager is a single `internal class` in its own file, referenced from both RavencoinPublicNode and SubscriptionManager.
- RavencoinPublicNode gains `subscribeScripthashRpc` and `estimateFeeRvnPerKb` wrappers over `callWithFailover`.
- ScripthashEvent sealed class has four variants: StatusChanged, ConnectionLost, AllNodesDown, PingTimeout.
- SubscriptionParser unit tests all GREEN.
- SubscriptionManager compiles, owns its own socket, uses the shared TofuTrustManager, routes frames correctly, has heartbeat.
- No em dashes.
</success_criteria>

<output>
Create `.planning/phases/30-wallet-reliability/30-03-SUMMARY.md` with:
- Exact line ranges extracted from RavencoinPublicNode.kt → TofuTrustManager.kt.
- Final signatures of subscribeScripthashRpc + estimateFeeRvnPerKb.
- List of emitted `ScripthashEvent` variants + when each fires.
- Hand-off note to plan 30-07: `NodeHealthMonitor` should subscribe to `SubscriptionManager.eventsFlow()` and map `PingTimeout`/`AllNodesDown`/`ConnectionLost` to the yellow/red connection-pill state.
- Hand-off note to plan 30-08: WalletScreen ViewModel should observe `SubscriptionManager.eventsFlow()` while foreground and call start/stop on lifecycle.
</output>
