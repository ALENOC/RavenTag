package io.raventag.app.wallet.health

import android.content.Context
import io.raventag.app.config.AppConfig
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * D-12: coarse-grained connection health used by the WalletScreen pill.
 *
 * - GREEN: at least one server reported success within the last 60 seconds
 *          and no recent failures.
 * - YELLOW: some servers have failed within the last 30 seconds but at least
 *           one non-quarantined fallback remains (reconnecting state).
 * - RED: every server in the pool is currently quarantined; Send/Receive
 *        must be disabled by the UI.
 */
enum class ConnectionHealth { GREEN, YELLOW, RED }

/**
 * Singleton, process-wide source of truth for ElectrumX node health
 * (D-11 quarantine enforcement, D-12 pill state).
 *
 * Both one-shot RPC ([io.raventag.app.wallet.RavencoinPublicNode]) and the
 * long-lived subscription socket
 * ([io.raventag.app.wallet.subscription.SubscriptionManager]) route through
 * [nextHealthyNode] before connecting and call [reportSuccess] /
 * [reportFailure] / [reportTofuMismatch] after the attempt.
 *
 * State is split across two layers:
 * - In-memory [ConcurrentHashMap]s track "recent failure / success" windows
 *   used to compute [stateFlow] (authoritative for sub-minute UX).
 * - [QuarantineDao] persists 1-hour TOFU-mismatch quarantines across process
 *   restarts (authoritative for long-lived bans).
 * - SharedPreferences persists the last known good host so cold starts skip
 *   the failover rotation and connect immediately to the previously working
 *   server.
 */
object NodeHealthMonitor {

    /** Diagnostic row surfaced to the WalletScreen bottom sheet (plan 30-08). */
    data class NodeDiagnostic(
        val host: String,
        val lastSuccessAt: Long?,
        val lastFailureAt: Long?,
        val lastError: String?,
        val quarantinedUntil: Long?
    )

    private const val QUARANTINE_DURATION_MS: Long = 3_600_000L       // D-11: 1 hour
    private const val TRANSIENT_COOLDOWN_MS: Long = 2_000L
    private const val YELLOW_FAILURE_WINDOW_MS: Long = 30_000L
    private const val GREEN_SUCCESS_WINDOW_MS: Long = 60_000L
    private const val PREFS_NAME = "node_health_prefs"
    private const val KEY_LAST_GOOD_HOST = "last_good_host"

    private val lastSuccessAt = ConcurrentHashMap<String, Long>()
    private val lastFailureAt = ConcurrentHashMap<String, Long>()
    private val lastError = ConcurrentHashMap<String, String>()

    private val _state = MutableStateFlow(ConnectionHealth.GREEN)
    val stateFlow: StateFlow<ConnectionHealth> = _state.asStateFlow()

    @Volatile private var initialized = false
    private val initLock = Any()
    private var appContext: Context? = null

    /** Idempotent init. Safe to call from MainActivity, workers and background paths. */
    fun init(context: Context) {
        if (initialized) return
        synchronized(initLock) {
            if (initialized) return
            appContext = context.applicationContext
            QuarantineDao.init(context)
            initialized = true
        }
    }

    /**
     * Returns the next host in "host:port" form that is NOT currently
     * quarantined (1h TOFU ban) and is outside the transient-failure cooldown.
     *
     * When ALL pool nodes are in transient cooldown, falls back to the
     * least-recently-failed node so the app never enters a dead state
     * where no RPC can be attempted. Without this, a network blip that
     * touches every node once quarantines the entire pool for 2 s.
     *
     * Tries the last known good host (persisted across restarts) first so
     * cold starts skip the failover rotation and connect immediately.
     */
    fun nextHealthyNode(): String? {
        val now = System.currentTimeMillis()
        val quarantinedHosts = activeQuarantineHosts(now)

        // Fast path: try the persisted last-good host first on cold start.
        val preferred = getPreferredHost()
        if (preferred != null && preferred !in quarantinedHosts) {
            val failedAt = lastFailureAt[preferred]
            if (failedAt == null || (now - failedAt) > TRANSIENT_COOLDOWN_MS) {
                recomputeState()
                return preferred
            }
        }

        // Standard rotation: non-quarantined nodes outside transient cooldown.
        val candidate = AppConfig.ELECTRUM_SERVERS.firstOrNull { (host, port) ->
            val key = "$host:$port"
            if (key in quarantinedHosts) return@firstOrNull false
            val failedAt = lastFailureAt[key]
            failedAt == null || (now - failedAt) > TRANSIENT_COOLDOWN_MS
        }?.let { (h, p) -> "$h:$p" }
        if (candidate != null) { recomputeState(); return candidate }

        // All nodes are in transient cooldown: fall back to the least recently
        // failed non-quarantined node so the app never deadlocks.
        val fallback = AppConfig.ELECTRUM_SERVERS
            .map { (h, p) -> "$h:$p" }
            .filter { it !in quarantinedHosts }
            .minByOrNull { lastFailureAt[it] ?: 0L }
        recomputeState()
        return fallback
    }

    fun reportSuccess(host: String) {
        val now = System.currentTimeMillis()
        lastSuccessAt[host] = now
        lastFailureAt.remove(host)
        lastError.remove(host)
        savePreferredHost(host)
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
        QuarantineDao.quarantine(
            host = host,
            durationMillis = QUARANTINE_DURATION_MS,
            reason = QuarantineDao.REASON_TOFU_MISMATCH
        )
        lastFailureAt[host] = now
        lastError[host] = QuarantineDao.REASON_TOFU_MISMATCH
        recomputeState()
    }

    /** Host with the most recent [reportSuccess] (falls back to persisted on cold start). */
    fun currentNode(): String? =
        lastSuccessAt.maxByOrNull { it.value }?.key ?: getPreferredHost()

    fun diagnostics(): List<NodeDiagnostic> {
        val now = System.currentTimeMillis()
        val active = QuarantineDao.all()
            .filter { it.quarantinedUntil > now }
            .associateBy { it.host }
        return AppConfig.ELECTRUM_SERVERS.map { (host, port) ->
            val key = "$host:$port"
            NodeDiagnostic(
                host = key,
                lastSuccessAt = lastSuccessAt[key],
                lastFailureAt = lastFailureAt[key],
                lastError = lastError[key],
                quarantinedUntil = active[key]?.quarantinedUntil
            )
        }
    }

    // --- internal ---

    private fun getPreferredHost(): String? {
        val ctx = appContext ?: return null
        return try {
            ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getString(KEY_LAST_GOOD_HOST, null)
        } catch (_: Throwable) { null }
    }

    private fun savePreferredHost(host: String) {
        val ctx = appContext ?: return
        try {
            ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit().putString(KEY_LAST_GOOD_HOST, host).apply()
        } catch (_: Throwable) {}
    }

    private fun activeQuarantineHosts(now: Long): Set<String> =
        try {
            QuarantineDao.all().asSequence()
                .filter { it.quarantinedUntil > now }
                .map { it.host }
                .toSet()
        } catch (_: Throwable) {
            // DB not initialized yet (e.g. called from a worker before init):
            // treat as "none quarantined" so we still pick a candidate.
            emptySet()
        }

    private fun recomputeState() {
        val now = System.currentTimeMillis()
        val total = AppConfig.ELECTRUM_SERVERS.size
        val quarantined = activeQuarantineHosts(now).size
        val hasAnyData = lastSuccessAt.isNotEmpty() || lastFailureAt.isNotEmpty()
        // GREEN takes precedence over YELLOW: once any host answers successfully in
        // the last 60s we are connected, regardless of transient failures on other hosts.
        val next = when {
            quarantined >= total -> ConnectionHealth.RED
            lastSuccessAt.values.any { (now - it) <= GREEN_SUCCESS_WINDOW_MS } ->
                ConnectionHealth.GREEN
            lastFailureAt.values.any { (now - it) <= YELLOW_FAILURE_WINDOW_MS } &&
                quarantined < total -> ConnectionHealth.YELLOW
            // Cold start: no RPC yet → stay optimistic GREEN so the UI does not
            // flash a yellow "reconnecting" pill before the first successful call.
            !hasAnyData -> ConnectionHealth.GREEN
            else -> ConnectionHealth.YELLOW
        }
        _state.value = next
    }
}
