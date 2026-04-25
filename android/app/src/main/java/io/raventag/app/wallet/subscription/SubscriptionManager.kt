package io.raventag.app.wallet.subscription

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import io.raventag.app.wallet.TofuTrustManager
import io.raventag.app.wallet.RavencoinPublicNode
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
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.coroutineContext
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.PrintWriter
import java.net.InetSocketAddress
import java.net.Socket
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket

/**
 * D-05: long-lived TLS socket per WalletScreen foreground session. Emits
 * [ScripthashEvent] for each blockchain.scripthash.subscribe notification.
 *
 * SEPARATE SOCKET from RavencoinPublicNode.call() (Pitfall 1):
 * asynchronous notifications cannot share a synchronous read path.
 *
 * Lifecycle:
 * - [start] opens a single TLS socket to the first reachable server,
 *   performs server.version handshake, subscribes to each address scripthash,
 *   and launches the reader + heartbeat coroutines.
 * - [stop] cancels the scope, closes the socket, clears session state.
 * - [eventsFlow] exposes a read-only [SharedFlow] of [ScripthashEvent].
 */
class SubscriptionManager(
    private val context: Context,
    @Suppress("UNUSED_PARAMETER")
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

    companion object {
        private const val TAG = "SubscriptionManager"

        /**
         * Kept for binary/call-site compatibility; the runtime pool is now
         * sourced from [io.raventag.app.config.AppConfig.ELECTRUM_SERVERS]
         * via [io.raventag.app.wallet.health.NodeHealthMonitor].
         */
        val DEFAULT_SERVERS: List<Pair<String, Int>> =
            io.raventag.app.config.AppConfig.ELECTRUM_SERVERS
    }

    fun eventsFlow(): SharedFlow<ScripthashEvent> = events.asSharedFlow()

    /**
     * Opens a persistent TLS socket, subscribes to each [addresses] scripthash,
     * and starts the reader + heartbeat loops.
     *
     * On all servers failing, emits [ScripthashEvent.AllNodesDown] and returns.
     * Caller decides whether to retry later.
     */
    suspend fun start(addresses: List<String>): Unit = withContext(Dispatchers.IO) {
        synchronized(lifecycleLock) {
            if (session != null) return@withContext // already running
            scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        }

        io.raventag.app.wallet.health.NodeHealthMonitor.init(context)
        var opened: Session? = null
        val poolSize = io.raventag.app.config.AppConfig.ELECTRUM_SERVERS.size
        for (attempt in 0 until poolSize) {
            if (opened != null) break
            val candidate = io.raventag.app.wallet.health.NodeHealthMonitor.nextHealthyNode()
                ?: break
            val (host, portStr) = candidate.split(":", limit = 2)
            val port = portStr.toInt()
            try {
                opened = openSession(host, port)
                io.raventag.app.wallet.health.NodeHealthMonitor.reportSuccess(candidate)
            } catch (e: Exception) {
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
        if (opened == null) {
            events.emit(ScripthashEvent.AllNodesDown)
            synchronized(lifecycleLock) { scope?.cancel(); scope = null }
            return@withContext
        }
        synchronized(lifecycleLock) { session = opened }

        // Handshake
        try {
            sendAndAwait(opened, "server.version", listOf("RavenTag/1.0", "1.4"))
        } catch (_: Exception) {
            events.emit(ScripthashEvent.ConnectionLost)
            return@withContext
        }

        // Subscribe per address
        val node = RavencoinPublicNode(context)
        for (addr in addresses) {
            val sh = node.addressToScripthash(addr)
            try {
                sendAndAwait(opened, "blockchain.scripthash.subscribe", listOf(sh))
            } catch (_: Exception) {
                Log.w(TAG, "subscribe failed for $sh, readLoop may deliver status anyway")
            }
        }

        // Reader loop
        scope?.launch { readLoop(opened) }
        // Heartbeat loop
        scope?.launch { heartbeatLoop(opened) }
    }

    /**
     * Cancels the session scope, closes the socket, and clears all pending callbacks.
     */
    suspend fun stop() = withContext(Dispatchers.IO) {
        synchronized(lifecycleLock) {
            scope?.cancel()
            scope = null
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
    )

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
            while (coroutineContext.isActive) {
                val line = withContext(Dispatchers.IO) { s.reader.readLine() }
                if (line == null) {
                    io.raventag.app.wallet.health.NodeHealthMonitor.reportFailure(
                        sessionKey(s),
                        "socket_closed"
                    )
                    events.emit(ScripthashEvent.ConnectionLost)
                    return
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
        } catch (e: Exception) {
            if (isTofuMismatch(e)) {
                io.raventag.app.wallet.health.NodeHealthMonitor.reportTofuMismatch(sessionKey(s))
            } else {
                io.raventag.app.wallet.health.NodeHealthMonitor.reportFailure(
                    sessionKey(s),
                    e.javaClass.simpleName
                )
            }
            events.emit(ScripthashEvent.ConnectionLost)
        }
    }

    private suspend fun heartbeatLoop(s: Session) {
        try {
            while (coroutineContext.isActive) {
                delay(pingIntervalMs)
                val result = withTimeoutOrNull(pingIntervalMs) {
                    sendAndAwait(s, "server.ping", emptyList<Any?>())
                }
                if (result == null) {
                    io.raventag.app.wallet.health.NodeHealthMonitor.reportFailure(
                        sessionKey(s),
                        "ping_timeout"
                    )
                    events.emit(ScripthashEvent.PingTimeout)
                    return
                }
            }
        } catch (e: Exception) {
            if (isTofuMismatch(e)) {
                io.raventag.app.wallet.health.NodeHealthMonitor.reportTofuMismatch(sessionKey(s))
            } else {
                io.raventag.app.wallet.health.NodeHealthMonitor.reportFailure(
                    sessionKey(s),
                    e.javaClass.simpleName
                )
            }
            events.emit(ScripthashEvent.ConnectionLost)
        }
    }

    private fun sessionKey(s: Session): String = "${s.host}:${s.socket.port}"

    private fun isTofuMismatch(e: Throwable): Boolean {
        if (e is java.security.cert.CertificateException) return true
        val m = e.message ?: return false
        return m.contains("Certificate mismatch", ignoreCase = true) ||
            m.contains("fingerprint mismatch", ignoreCase = true) ||
            m.contains("TOFU", ignoreCase = true)
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
}
