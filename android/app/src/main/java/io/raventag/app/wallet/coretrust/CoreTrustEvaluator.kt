package io.raventag.app.wallet.coretrust

import com.google.gson.JsonObject

/**
 * Pure, side-effect-free classification of a server's core-trust evidence.
 *
 * The evaluator never performs I/O: it consumes the raw JSON collected by
 * [CoreTrustManager] plus a verified policy and produces a [CoreTrustResult].
 * This keeps every trust decision unit-testable and deterministic.
 *
 * Decision order (fail-closed everywhere):
 *
 *  1. no backend answer at all
 *     → capability known absent ⇒ UNKNOWN/LEGACY_SERVER (a legacy server is
 *       never punished: balances, history, broadcasts keep working);
 *     → otherwise UNKNOWN (timeout / verification failed). Never TRUSTED.
 *  2. structurally invalid answer ⇒ UNKNOWN/MALFORMED_RESPONSE. Never TRUSTED.
 *  3. POSITIVE evidence of badness ⇒ UNSAFE:
 *       - network ≠ main;
 *       - version string in the known-unsafe set (4.6.x / 4.7.0);
 *       - versionNumber < 4.8.0 (server admits an incompatible Core);
 *       - identity matches a policy release marked REVOKED or KNOWN_UNSAFE.
 *  4. TRUSTED requires ALL of:
 *       - every compatibility flag true (coreSafe, networkMatches,
 *         backendSynchronized, kawpowHeightValidation, checkpoint4487775);
 *       - fresh evidence (observedAt, normalized to milliseconds, within
 *         [MAX_EVIDENCE_AGE_MS]);
 *       - build identity (repository + commit) matching a KNOWN_SAFE release
 *         of a signature-verified, non-expired policy;
 *       - the checkpoint block header from this server byte-identical to the
 *         header reported by at least one server of a DIFFERENT operator
 *         group (independent chain corroboration).
 *  5. anything else ⇒ UNKNOWN with the most specific reason available.
 *
 * A self-reported version string alone — however high or "SAFE"-looking —
 * can never produce TRUSTED.
 */
object CoreTrustEvaluator {

    /** Ravencoin Core releases known to be involved in the 2026 consensus incident. */
    val KNOWN_UNSAFE_VERSIONS = setOf("4.6.0", "4.6.1", "4.6.1.1", "4.7.0")

    /** Safety floor from the signed policy ecosystem; a claim below it is UNSAFE. */
    private val MINIMUM_SAFE_CORE = intArrayOf(4, 8, 0, 0)

    /** Evidence older than this is not acted upon. */
    const val MAX_EVIDENCE_AGE_MS: Long = 10 * 60 * 1000L

    /**
     * Boundary between a Unix timestamp expressed in seconds and one expressed
     * in milliseconds. As milliseconds this is 1973-03-03, as seconds it is the
     * year 5138, so no real timestamp is ambiguous.
     */
    private const val OBSERVED_AT_MILLIS_THRESHOLD: Long = 100_000_000_000L

    /**
     * ElectrumX-RVN publishes `observedAt` as whole Unix seconds
     * (`int(time.time())` in `ravencoin_backend.py`), while every timestamp the
     * evaluator compares against is in milliseconds. A value inside the plausible
     * seconds range is scaled up; a value already in milliseconds is returned
     * unchanged. This corrects the unit only: the freshness window and the
     * future-timestamp guard are still applied to the normalized value.
     */
    fun normalizeObservedAtMs(rawObservedAt: Long): Long =
        if (rawObservedAt < OBSERVED_AT_MILLIS_THRESHOLD) rawObservedAt * 1000L else rawObservedAt

    private const val EXPECTED_NETWORK = "main"

    /** Incident checkpoint height exposed by ElectrumX-RVN 1.13 features. */
    const val DEFAULT_CHECKPOINT_HEIGHT: Long = 4_487_775L

    /** Sanity bounds for any checkpoint height accepted from server.features. */
    private val CHECKPOINT_HEIGHT_RANGE = 4_000_000L..10_000_000L

    /**
     * Operator grouping for chain corroboration. Multiple endpoints of the
     * same operator are not independent consensus, so only a server in a
     * different group can corroborate. The Cipig mirrors share one group;
     * every other host is its own group (an attacker can mint hostnames for
     * free, so unknown hostnames never merge into one group).
     */
    fun operatorGroup(host: String): String = when {
        host.endsWith(".cipig.net", ignoreCase = true) -> "cipig"
        else -> host.lowercase()
    }

    /**
     * Decodes Core's integer client version (e.g. 4080000 → [4,8,0,0]) using
     * the same divmod encoding as the daemon, never lexicographic comparison.
     */
    fun parseCoreVersionNumber(version: Long?): IntArray? {
        if (version == null || version < 0) return null
        var remainder = version
        val major = remainder / 1_000_000; remainder %= 1_000_000
        val minor = remainder / 10_000; remainder %= 10_000
        val patch = remainder / 100
        val build = remainder % 100
        return intArrayOf(major.toInt(), minor.toInt(), patch.toInt(), build.toInt())
    }

    private fun versionBelowMinimum(parts: IntArray): Boolean {
        for (i in MINIMUM_SAFE_CORE.indices) {
            if (parts[i] != MINIMUM_SAFE_CORE[i]) return parts[i] < MINIMUM_SAFE_CORE[i]
        }
        return false
    }

    /** Validated view over a `server.ravencoin_backend` result. */
    private data class BackendEvidence(
        val version: String,
        val versionNumber: Long?,
        val network: String,
        val observedAt: Long,
        val flags: Map<String, Boolean>,
        val repository: String?,
        val commit: String?,
        val evidenceLevel: String
    )

    /**
     * Live-tip chain evidence (RT108-SEC-102). The static checkpoint header is
     * public data any server can serve, so TRUSTED additionally requires the
     * server's CURRENT tip: an independent operator must serve the exact same
     * header at the server's own tip height, proving right now that the server
     * is on the canonical chain (a forked or replaying server cannot satisfy
     * this without following the true chain in real time).
     *
     * @property tipHeight the server's reported chain-tip height
     * @property tipHeaderHex the server's reported chain-tip header bytes
     * @property corroboratedHeaderAtTipByHost header bytes AT [tipHeight]
     *           reported by other hosts (manager fetches at this exact height)
     */
    data class TipEvidence(
        val tipHeight: Long,
        val tipHeaderHex: String,
        val corroboratedHeaderAtTipByHost: Map<String, String>
    )

    fun evaluate(
        backendResponse: JsonObject?,
        capabilityAdvertised: Boolean?,
        rpcSupported: Boolean?,
        policy: CoreTrustPolicy.VerifiedPolicy?,
        checkpointHeaderHex: String?,
        corroboratedHeadersByHost: Map<String, String>,
        serverHost: String,
        nowMs: Long = System.currentTimeMillis(),
        checkpointHeight: Long = DEFAULT_CHECKPOINT_HEIGHT,
        tipEvidence: TipEvidence? = null
    ): CoreTrustResult {
        val evaluatedAt = nowMs

        // 1. No evidence at all.
        if (backendResponse == null) {
            val reason = when {
                capabilityAdvertised == false || rpcSupported == false ->
                    CoreTrustReason.LEGACY_SERVER
                else -> CoreTrustReason.VERIFICATION_FAILED
            }
            return CoreTrustResult(
                level = CoreTrustLevel.UNKNOWN, reason = reason, serverHost = serverHost,
                evaluatedAt = evaluatedAt
            )
        }

        // 2. Structural validation.
        val evidence = parseEvidence(backendResponse)
            ?: return CoreTrustResult(
                level = CoreTrustLevel.UNKNOWN,
                reason = CoreTrustReason.MALFORMED_RESPONSE,
                serverHost = serverHost,
                evaluatedAt = evaluatedAt
            )

        val base = CoreTrustResult(
            level = CoreTrustLevel.UNKNOWN,
            reason = CoreTrustReason.VERIFICATION_FAILED,
            coreVersion = evidence.version,
            identityRepository = evidence.repository,
            identityCommit = evidence.commit,
            identityEvidence = evidence.evidenceLevel,
            serverHost = serverHost,
            evaluatedAt = evaluatedAt
        )

        // 3. Positive evidence of badness → UNSAFE. A server reporting a bad
        //    state convicts itself; these checks do not need the policy.
        if (evidence.network != EXPECTED_NETWORK) {
            return base.copy(level = CoreTrustLevel.UNSAFE, reason = CoreTrustReason.WRONG_NETWORK)
        }
        if (evidence.version in KNOWN_UNSAFE_VERSIONS) {
            return base.copy(level = CoreTrustLevel.UNSAFE, reason = CoreTrustReason.KNOWN_UNSAFE_CORE)
        }
        parseCoreVersionNumber(evidence.versionNumber)?.let { parts ->
            if (versionBelowMinimum(parts)) {
                return base.copy(
                    level = CoreTrustLevel.UNSAFE,
                    reason = CoreTrustReason.INCOMPATIBLE_CORE
                )
            }
        }
        if (policy != null) {
            val match = policy.findRelease(evidence.repository, evidence.commit)
            if (match != null && (match.status == "REVOKED" || match.status == "KNOWN_UNSAFE")) {
                return base.copy(
                    level = CoreTrustLevel.UNSAFE,
                    reason = CoreTrustReason.KNOWN_UNSAFE_CORE
                )
            }
        }

        // 4. Everything required for TRUSTED, checked in order, fail-closed.
        if (policy == null) {
            return base.copy(reason = CoreTrustReason.POLICY_UNAVAILABLE)
        }
        if (evidence.repository == null || evidence.commit == null) {
            return base.copy(reason = CoreTrustReason.IDENTITY_MISSING)
        }
        val certified = policy.findRelease(evidence.repository, evidence.commit)
        if (certified == null || certified.status != "KNOWN_SAFE") {
            return base.copy(reason = CoreTrustReason.NOT_CERTIFIED)
        }
        val flagsOk = listOf(
            "coreSafe", "networkMatches", "backendSynchronized",
            "kawpowHeightValidation", "checkpoint4487775"
        ).all { evidence.flags[it] == true }
        if (!flagsOk) {
            return base.copy(reason = CoreTrustReason.NOT_SYNCHRONIZED)
        }
        if (nowMs - evidence.observedAt > MAX_EVIDENCE_AGE_MS || evidence.observedAt > nowMs + 60_000L) {
            return base.copy(reason = CoreTrustReason.STALE_EVIDENCE)
        }
        if (checkpointHeaderHex.isNullOrEmpty()) {
            return base.copy(reason = CoreTrustReason.NO_CHAIN_CORROBORATION)
        }
        val currentGroup = operatorGroup(serverHost)
        val checkpointCorroborators = corroboratedHeadersByHost.entries
            .filter { (host, header) ->
                header == checkpointHeaderHex && operatorGroup(host) != currentGroup
            }
            .map { it.key }
            .sorted()
        if (checkpointCorroborators.isEmpty()) {
            return base.copy(reason = CoreTrustReason.NO_CHAIN_CORROBORATION)
        }
        // RT108-SEC-102: the checkpoint header alone is public data; require
        // live-tip agreement with an independent operator as well.
        val tip = tipEvidence
        if (tip == null ||
            tip.tipHeight !in CHECKPOINT_HEIGHT_RANGE ||
            tip.tipHeight < checkpointHeight + 100
        ) {
            return base.copy(reason = CoreTrustReason.NO_CHAIN_CORROBORATION)
        }
        val liveCorroborators = tip.corroboratedHeaderAtTipByHost.entries
            .filter { (host, header) ->
                header == tip.tipHeaderHex && operatorGroup(host) != currentGroup
            }
            .map { it.key }
            .sorted()
        if (liveCorroborators.isEmpty()) {
            return base.copy(reason = CoreTrustReason.NO_CHAIN_CORROBORATION)
        }
        return base.copy(
            level = CoreTrustLevel.TRUSTED,
            reason = CoreTrustReason.CERTIFIED_BUILD,
            corroboratedBy = (checkpointCorroborators + liveCorroborators).distinct().sorted()
        )
    }

    /**
     * Extracts and type-checks every field the trust decision consumes.
     * Returns null when the payload does not match the 1.13 evidence contract.
     */
    private fun parseEvidence(response: JsonObject): BackendEvidence? {
        val backend = response.getAsJsonObject("backend") ?: return null
        val compatibility = response.getAsJsonObject("compatibility") ?: return null

        val version = backend.stringOrNull("version") ?: return null
        if (version.isEmpty() || version.length > 32) return null
        val versionNumber = backend.longOrNull("versionNumber")
        if (versionNumber != null && parseCoreVersionNumber(versionNumber) == null) return null
        // Cross-check: a version string disagreeing with the encoded integer
        // means the payload cannot be trusted to describe itself.
        parseCoreVersionNumber(versionNumber)?.let { parts ->
            val canonical = buildString {
                append(parts[0]); append('.'); append(parts[1]); append('.'); append(parts[2])
                if (parts[3] != 0) { append('.'); append(parts[3]) }
            }
            if (canonical != version) return null
        }
        val network = backend.stringOrNull("network") ?: return null
        if (network.isEmpty() || network.length > 16) return null

        val rawObservedAt = response.longOrNull("observedAt") ?: return null
        if (rawObservedAt <= 0) return null
        val observedAt = normalizeObservedAtMs(rawObservedAt)

        val flags = mapOf(
            "coreSafe" to compatibility.booleanOrNull("coreSafe"),
            "networkMatches" to compatibility.booleanOrNull("networkMatches"),
            "backendSynchronized" to compatibility.booleanOrNull("backendSynchronized"),
            "kawpowHeightValidation" to compatibility.booleanOrNull("kawpowHeightValidation"),
            "checkpoint4487775" to compatibility.booleanOrNull("checkpoint4487775")
        )
        if (flags.values.any { it == null }) return null

        val identity = backend.getAsJsonObject("identity")
        val repository = identity?.stringOrNull("sourceRepository")?.takeIf { it.isNotEmpty() }
        val commit = identity?.stringOrNull("sourceCommit")?.takeIf { it.isNotEmpty() }
        if (commit != null && !commit.matches(Regex("[0-9a-fA-F]{40}"))) return null
        val evidenceLevel = identity?.stringOrNull("evidence") ?: "UNKNOWN"

        return BackendEvidence(
            version = version,
            versionNumber = versionNumber,
            network = network,
            observedAt = observedAt,
            flags = mapNotNullValues(flags),
            repository = repository,
            commit = commit?.lowercase(),
            evidenceLevel = evidenceLevel
        )
    }

    private fun mapNotNullValues(flags: Map<String, Boolean?>): Map<String, Boolean> =
        flags.mapValues { it.value ?: false }

    /** Checkpoint height advertised by this server's features, if sane. */
    fun checkpointHeightFromFeatures(features: JsonObject?): Long {
        val height = features
            ?.getAsJsonObject("ravencoin")
            ?.getAsJsonObject("checkpoint")
            ?.longOrNull("height")
            ?: return DEFAULT_CHECKPOINT_HEIGHT
        return if (height in CHECKPOINT_HEIGHT_RANGE) height else DEFAULT_CHECKPOINT_HEIGHT
    }

    /** Capability detection: `server.features` → `ravencoin.backend_info`. */
    fun capabilityAdvertised(features: JsonObject?): Boolean? {
        val ravencoin = features?.getAsJsonObject("ravencoin") ?: return null
        return ravencoin.booleanOrNull("backend_info")
    }

    private fun JsonObject.stringOrNull(key: String): String? {
        val element = get(key) ?: return null
        if (!element.isJsonPrimitive) return null
        return try {
            element.asString
        } catch (_: Exception) {
            null
        }
    }

    private fun JsonObject.longOrNull(key: String): Long? {
        val element = get(key) ?: return null
        if (!element.isJsonPrimitive) return null
        val primitive = element.asJsonPrimitive
        if (!primitive.isNumber) return null
        val literal = primitive.asNumber.toString()
        if (!literal.matches(Regex("-?\\d+"))) return null
        return literal.toLongOrNull()
    }

    private fun JsonObject.booleanOrNull(key: String): Boolean? {
        val element = get(key) ?: return null
        if (!element.isJsonPrimitive) return null
        val primitive = element.asJsonPrimitive
        // Gson's isBoolean distinguishes real booleans from strings/numbers.
        return if (primitive.isBoolean) primitive.asBoolean else null
    }
}
