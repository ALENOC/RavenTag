package io.raventag.app.wallet.coretrust

import android.content.Context
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Orchestrates core-trust evidence collection and exposes the result to the UI.
 *
 * Trust never comes from a server's own version string: this manager fetches
 * the Ed25519-signed safe-Core policy from the pinned distribution URL,
 * verifies it against an app-pinned key, keeps a last-verified cache plus an
 * embedded baseline so an outage never upgrades or downgrades trust, and
 * evaluates the CURRENT wallet server's 1.13 evidence against that policy
 * with independent cross-operator checkpoint corroboration.
 *
 * Fail-closed posture: any failure anywhere yields UNKNOWN, never TRUSTED
 * and (except for explicit positive badness evidence) never UNSAFE.
 */
object CoreTrustManager {

    private const val TAG = "CoreTrust"
    private const val PREFS = "core_trust_prefs"
    private const val KEY_CACHED_POLICY = "last_verified_policy_json"
    private const val KEY_HIGH_WATER = "policy_high_water_version"
    private const val KEY_LAST_RESULT_JSON = "last_result_json"

    /** Baseline policy shipped inside the APK (verified at build/test time). */
    private const val BASELINE_ASSET = "core_trust_policy_baseline.json"

    /** Hard cap on a policy download: the document is a few KB. */
    private const val MAX_POLICY_BYTES = 256L * 1024L

    /** Minimum spacing between forced refreshes triggered by the UI. */
    private const val MIN_FORCED_REFRESH_INTERVAL_MS = 60_000L

    /** Result older than this is considered stale for display purposes. */
    const val RESULT_STALE_AFTER_MS: Long = 10 * 60 * 1000L

    private val refreshing = AtomicBoolean(false)

    @Volatile
    private var lastForcedAt = 0L

    private val _stateFlow = MutableStateFlow<CoreTrustResult?>(null)

    /** Latest evaluation, if any has run or been restored. Null = never checked. */
    val stateFlow: StateFlow<CoreTrustResult?> = _stateFlow

    private val httpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .writeTimeout(10, TimeUnit.SECONDS)
            // The policy URL is a static, pinned HTTPS path; transparent
            // redirects would let an intermediary move the fetch elsewhere.
            .followRedirects(false)
            .followSslRedirects(false)
            .build()
    }

    /**
     * Loads the best available verified policy:
     *
     *  1. freshly fetched + signature-verified (also updates the cache and the
     *     anti-rollback high-water mark);
     *  2. last verified cache (still signature-checked, expiry-checked and
     *     rollback-checked on every load);
     *  3. the embedded baseline (same checks).
     *
     * Returns null when nothing verifies — callers treat that as
     * POLICY_UNAVAILABLE, which caps every classification at UNKNOWN.
     */
    @Synchronized
    fun loadVerifiedPolicy(context: Context): CoreTrustPolicy.VerifiedPolicy? {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        // RT108-SEC-115: the embedded baseline is the floor of the anti-rollback
        // high-water mark, so a local tamper of the pref cannot force acceptance
        // of an older signed policy (e.g. one that re-certifies a revoked build).
        val baseline = loadBaselinePolicy(context)
        val highWater = maxOf(
            prefs.getLong(KEY_HIGH_WATER, 0L),
            baseline?.policyVersion ?: 0L
        )
        val now = System.currentTimeMillis()

        // 1. Fresh fetch.
        val fetched = fetchPolicyDocument()
        if (fetched != null) {
            try {
                val verified = CoreTrustPolicy.verify(fetched, now, highWater)
                prefs.edit()
                    .putString(KEY_CACHED_POLICY, fetched)
                    .putLong(KEY_HIGH_WATER, verified.policyVersion)
                    .apply()
                return verified
            } catch (e: CoreTrustPolicy.PolicyException) {
                // A tampered or rolled-back distribution must not influence
                // trust: fall through to previously verified material.
                Log.w(TAG, "Fetched policy rejected: ${e.message}")
            }
        }

        // 2. Last verified cache.
        prefs.getString(KEY_CACHED_POLICY, null)?.let { cached ->
            try {
                return CoreTrustPolicy.verify(cached, now, highWater)
            } catch (e: CoreTrustPolicy.PolicyException) {
                Log.w(TAG, "Cached policy rejected: ${e.message}")
            }
        }

        // 3. Embedded baseline.
        return baseline
    }

    private fun loadBaselinePolicy(context: Context): CoreTrustPolicy.VerifiedPolicy? {
        return try {
            val baseline = context.assets.open(BASELINE_ASSET).use { stream ->
                stream.readBytes().toString(Charsets.UTF_8)
            }
            CoreTrustPolicy.verify(baseline, System.currentTimeMillis())
        } catch (e: CoreTrustPolicy.PolicyException) {
            Log.w(TAG, "Baseline policy rejected: ${e.message}")
            null
        } catch (e: IOException) {
            Log.w(TAG, "Baseline policy missing: ${e.message}")
            null
        }
    }

    private fun fetchPolicyDocument(): String? = try {
        val response = httpClient.newCall(
            Request.Builder().url(CoreTrustPolicy.POLICY_URL).get().build()
        ).execute()
        response.use { resp ->
            if (!resp.isSuccessful) return@use null
            val body = resp.body ?: return@use null
            if (body.contentLength() > MAX_POLICY_BYTES) return@use null
            val bytes = body.bytes()
            if (bytes.size > MAX_POLICY_BYTES) return@use null
            bytes.toString(Charsets.UTF_8)
        }
    } catch (_: Exception) {
        null
    }

    /**
     * Re-runs the evaluation for the current wallet server. Safe to call from
     * a periodic loop: concurrent invocations collapse into one, and a
     * non-forced run is skipped while the previous result is still fresh.
     *
     * @param force bypass [RESULT_STALE_AFTER_MS] (still rate-limited to one
     *        forced run per minute).
     * @return the evaluation result (also pushed to [stateFlow]).
     */
    fun refresh(context: Context, force: Boolean = false): CoreTrustResult {
        var last = _stateFlow.value
        if (last == null) {
            loadPersistedResult(context)?.let { restored ->
                _stateFlow.compareAndSet(null, restored)
            }
            last = _stateFlow.value
        }
        val now = System.currentTimeMillis()
        if (!force && last != null && now - last.evaluatedAt < RESULT_STALE_AFTER_MS) {
            return last
        }
        if (force && now - lastForcedAt < MIN_FORCED_REFRESH_INTERVAL_MS) {
            val result = last ?: CoreTrustResult(
                level = CoreTrustLevel.UNKNOWN,
                reason = CoreTrustReason.VERIFICATION_FAILED,
                evaluatedAt = now
            )
            _stateFlow.compareAndSet(null, result)
            return result
        }
        if (force) lastForcedAt = now
        if (!refreshing.compareAndSet(false, true)) {
            val result = last ?: CoreTrustResult(
                level = CoreTrustLevel.UNKNOWN,
                reason = CoreTrustReason.VERIFICATION_FAILED,
                evaluatedAt = now
            )
            _stateFlow.compareAndSet(null, result)
            return result
        }
        try {
            val result = try {
                evaluateCurrentNode(context)
            } catch (e: Exception) {
                Log.w(TAG, "Core trust evaluation failed", e)
                CoreTrustResult(
                    level = CoreTrustLevel.UNKNOWN,
                    reason = if (e is java.net.SocketTimeoutException ||
                        e.cause is java.net.SocketTimeoutException
                    ) CoreTrustReason.TIMEOUT else CoreTrustReason.VERIFICATION_FAILED,
                    evaluatedAt = System.currentTimeMillis()
                )
            }
            _stateFlow.value = result
            persistResult(context, result)
            return result
        } finally {
            refreshing.set(false)
        }
    }

    private fun evaluateCurrentNode(context: Context): CoreTrustResult {
        val now = System.currentTimeMillis()
        val nodeKey = io.raventag.app.wallet.health.NodeHealthMonitor.currentNode()
        val node = RavencoinPublicNodeHolder.node(context)
        if (nodeKey == null) {
            return CoreTrustResult(
                level = CoreTrustLevel.UNKNOWN,
                reason = CoreTrustReason.VERIFICATION_FAILED,
                evaluatedAt = now
            )
        }
        val host = nodeKey.substringBefore(":")
        val port = nodeKey.substringAfter(":").toIntOrNull()
            ?: AppConfigHolder.portFor(context, host)
        return evaluateServer(node, host, port, context, now)
    }

    private fun evaluateServer(
        node: io.raventag.app.wallet.RavencoinPublicNode,
        host: String,
        port: Int,
        context: Context,
        now: Long
    ): CoreTrustResult {
        val policy = loadVerifiedPolicy(context)

        val snapshot = node.coreEvidenceDirect(
            host = host,
            port = port,
            checkpointHeight = CoreTrustEvaluator.DEFAULT_CHECKPOINT_HEIGHT
        )
        val features = snapshot.features
        val capability = CoreTrustEvaluator.capabilityAdvertised(features)

        // Direct probe: the capability block in server.features is the
        // documented advertisement, but some deployments register the RPC
        // without the features entry, so the probe is authoritative for
        // "does this server answer the method at all".
        val backendOutcome = snapshot.backendOutcome
        val backendResponse = when (backendOutcome) {
            is io.raventag.app.wallet.RavencoinPublicNode.BackendInfoOutcome.Response ->
                backendOutcome.result
            else -> null
        }

        val checkpointHeight = CoreTrustEvaluator.checkpointHeightFromFeatures(features)
        val ownHeader = if (backendResponse != null) {
            if (checkpointHeight == CoreTrustEvaluator.DEFAULT_CHECKPOINT_HEIGHT) {
                snapshot.checkpointHeader
            } else {
                node.blockHeaderDirect(host, port, checkpointHeight)
            }
        } else null

        // Independent corroboration: fetch the same checkpoint header from
        // servers in other operator groups (legacy servers answer the
        // standard header method fine).
        val currentGroup = CoreTrustEvaluator.operatorGroup(host)
        val corroborated = mutableMapOf<String, String>()
        val ownTip = if (backendResponse != null) snapshot.tipHeader else null
        val headerAtTipByHost = mutableMapOf<String, String>()
        if (ownTip != null) {
            var attemptedGroups = 0
            val seenGroups = mutableSetOf<String>()
            for ((otherHost, otherPort) in node.poolHosts) {
                val group = CoreTrustEvaluator.operatorGroup(otherHost)
                if (group == currentGroup || !seenGroups.add(group)) continue
                // Bound a mobile refresh even when fallback operators are down.
                if (attemptedGroups++ >= 2) break
                val other = node.coreCorroborationDirect(
                    otherHost,
                    otherPort,
                    checkpointHeight,
                    ownTip.first
                ) ?: continue
                other.checkpointHeader?.let { corroborated[otherHost] = it }
                if ((other.serverTipHeight ?: 0L) + 6 >= ownTip.first) {
                    other.headerAtRequestedTip?.let { headerAtTipByHost[otherHost] = it }
                }
                if (corroborated[otherHost] == ownHeader &&
                    headerAtTipByHost[otherHost] == ownTip.second
                ) break
            }
        }
        val tipEvidence = ownTip?.let {
            CoreTrustEvaluator.TipEvidence(
                tipHeight = it.first,
                tipHeaderHex = it.second,
                corroboratedHeaderAtTipByHost = headerAtTipByHost
            )
        }

        val result = CoreTrustEvaluator.evaluate(
            backendResponse = backendResponse,
            capabilityAdvertised = capability,
            rpcSupported = backendOutcome !is
                io.raventag.app.wallet.RavencoinPublicNode.BackendInfoOutcome.NotSupported,
            policy = policy,
            checkpointHeaderHex = ownHeader,
            corroboratedHeadersByHost = corroborated,
            serverHost = host,
            nowMs = now,
            checkpointHeight = checkpointHeight,
            tipEvidence = tipEvidence
        )

        // Timeout wins over generic failure when the probe itself timed out.
        return if (backendOutcome is io.raventag.app.wallet.RavencoinPublicNode.BackendInfoOutcome.Timeout &&
            result.level == CoreTrustLevel.UNKNOWN &&
            result.reason == CoreTrustReason.VERIFICATION_FAILED
        ) {
            result.copy(reason = CoreTrustReason.TIMEOUT)
        } else {
            result
        }
    }

    private fun persistResult(context: Context, result: CoreTrustResult) {
        try {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .putString(
                    KEY_LAST_RESULT_JSON,
                    com.google.gson.Gson().toJson(
                        mapOf(
                            "level" to result.level.name,
                            "reason" to result.reason.name,
                            "coreVersion" to result.coreVersion,
                            "identityRepository" to result.identityRepository,
                            "identityCommit" to result.identityCommit,
                            "identityEvidence" to result.identityEvidence,
                            "corroboratedBy" to result.corroboratedBy,
                            "serverHost" to result.serverHost,
                            "evaluatedAt" to result.evaluatedAt
                        )
                    )
                )
                .apply()
        } catch (_: Exception) {
            // Diagnostics only; never affects the trust decision.
        }
    }

    /** Restore the last completed result so process restarts never show an endless check. */
    private fun loadPersistedResult(context: Context): CoreTrustResult? {
        return try {
            val json = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(KEY_LAST_RESULT_JSON, null)
                ?: return null
            val obj = com.google.gson.JsonParser.parseString(json).asJsonObject
            val level = CoreTrustLevel.valueOf(obj.get("level").asString)
            val reason = CoreTrustReason.valueOf(obj.get("reason").asString)
            val evaluatedAt = obj.get("evaluatedAt")?.asLong ?: return null
            fun optionalString(name: String): String? = obj.get(name)
                ?.takeUnless { it.isJsonNull }
                ?.asString
            val corroborated = obj.getAsJsonArray("corroboratedBy")
                ?.mapNotNull { value -> runCatching { value.asString }.getOrNull() }
                .orEmpty()
            CoreTrustResult(
                level = level,
                reason = reason,
                coreVersion = optionalString("coreVersion"),
                identityRepository = optionalString("identityRepository"),
                identityCommit = optionalString("identityCommit"),
                identityEvidence = optionalString("identityEvidence"),
                corroboratedBy = corroborated,
                serverHost = optionalString("serverHost"),
                evaluatedAt = evaluatedAt
            )
        } catch (e: Exception) {
            Log.w(TAG, "Persisted Core trust result rejected: ${e.message}")
            null
        }
    }

    /** Lazily-shared node client (cheap; reuse keeps the id counter shared). */
    private object RavencoinPublicNodeHolder {
        @Volatile
        private var instance: io.raventag.app.wallet.RavencoinPublicNode? = null
        fun node(context: Context): io.raventag.app.wallet.RavencoinPublicNode =
            instance ?: synchronized(this) {
                instance ?: io.raventag.app.wallet.RavencoinPublicNode(context.applicationContext)
                    .also { instance = it }
            }
    }

    /** Port lookup for a pool host, so a malformed node key still resolves. */
    private object AppConfigHolder {
        fun portFor(@Suppress("UNUSED_PARAMETER") context: Context, host: String): Int =
            io.raventag.app.config.AppConfig.ELECTRUM_SERVERS
                .firstOrNull { it.first == host }?.second ?: 50002
    }
}
