package io.raventag.app.wallet.coretrust

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Core Trust evaluator regression suite.
 *
 * Implements the mandated case matrix (RT 1.0.8): a server that cannot be
 * verified must NEVER classify as TRUSTED; only positive badness evidence
 * classifies as UNSAFE; legacy servers stay UNKNOWN and fully usable.
 */
class CoreTrustEvaluatorTest {

    companion object {
        private const val CERTIFIED_COMMIT = "b60f50e04f1fba425b28804e61be2694faaf3469"
        private const val CERTIFIED_REPO = "2miners/Ravencoin"
        private const val HOST = "electrumx.raventag.com"
        private const val NOW = 1_787_000_000_000L
        private const val CHECKPOINT_HEADER =
            "00000020abcdef0123456789abcdef0123456789abcdef0123456789abcdef01234567"
        private const val TIP_HEADER =
            "11223344556677889900aabbccddeeff00112233445566778899aabbccddeeff006677"

        private fun certifiedPolicy(
            status: String = "KNOWN_SAFE",
            commit: String = CERTIFIED_COMMIT
        ) = CoreTrustPolicy.VerifiedPolicy(
            policyVersion = 2,
            expiresAtMs = NOW + 86_400_000L,
            safetyProfile = "rvn-consensus-2026-08-v1",
            releases = listOf(
                CoreTrustPolicy.PolicyRelease(
                    repository = CERTIFIED_REPO,
                    tag = "v4.8.0",
                    version = "4.8.0",
                    commit = commit,
                    status = status
                )
            )
        )

        private fun backendResponse(
            version: String = "4.8.0",
            versionNumber: Long = 4_080_000L,
            network: String = "main",
            coreSafe: Boolean = true,
            networkMatches: Boolean = true,
            backendSynchronized: Boolean = true,
            kawpowHeightValidation: Boolean = true,
            checkpoint4487775: Boolean = true,
            observedAt: Long = NOW - 60_000L,
            repository: String? = CERTIFIED_REPO,
            commit: String? = CERTIFIED_COMMIT,
            evidence: String = "BUILD_IDENTITY_VERIFIED"
        ): JsonObject {
            val identity = JsonObject()
            identity.addProperty("evidence", evidence)
            if (repository != null) identity.addProperty("sourceRepository", repository)
            if (commit != null) identity.addProperty("sourceCommit", commit)
            val backend = JsonObject()
            backend.addProperty("name", "Ravencoin Core")
            backend.addProperty("version", version)
            backend.addProperty("versionNumber", versionNumber)
            backend.addProperty("subversion", "/Ravencoin:$version/")
            backend.addProperty("network", network)
            backend.addProperty("blocks", 4_503_939)
            backend.addProperty("headers", 4_503_939)
            backend.addProperty("initialBlockDownload", false)
            backend.add("identity", identity)
            val compatibility = JsonObject()
            compatibility.addProperty("minimumSafeCore", "4.8.0")
            compatibility.addProperty("safetyProfile", "rvn-consensus-2026-08-v1")
            compatibility.addProperty("identityEvidence", evidence)
            compatibility.addProperty("coreSafe", coreSafe)
            compatibility.addProperty("networkMatches", networkMatches)
            compatibility.addProperty("backendSynchronized", backendSynchronized)
            compatibility.addProperty("kawpowHeightValidation", kawpowHeightValidation)
            compatibility.addProperty("checkpoint4487775", checkpoint4487775)
            val root = JsonObject()
            root.addProperty("server", "ElectrumX-RVN")
            root.addProperty("serverVersion", "ElectrumX-RVN 1.13.0")
            root.add("backend", backend)
            root.add("compatibility", compatibility)
            root.addProperty("observedAt", observedAt)
            return root
        }

        private fun defaultTipEvidence() = CoreTrustEvaluator.TipEvidence(
            tipHeight = 4_503_939L,
            tipHeaderHex = TIP_HEADER,
            corroboratedHeaderAtTipByHost = mapOf("electrum1.cipig.net" to TIP_HEADER)
        )

        private fun evaluate(
            backendResponse: JsonObject? = backendResponse(),
            capabilityAdvertised: Boolean? = true,
            rpcSupported: Boolean? = true,
            policy: CoreTrustPolicy.VerifiedPolicy? = certifiedPolicy(),
            checkpointHeaderHex: String? = CHECKPOINT_HEADER,
            corroboratedHeadersByHost: Map<String, String> =
                mapOf("electrum1.cipig.net" to CHECKPOINT_HEADER),
            serverHost: String = HOST,
            nowMs: Long = NOW,
            checkpointHeight: Long = CoreTrustEvaluator.DEFAULT_CHECKPOINT_HEIGHT,
            tipEvidence: CoreTrustEvaluator.TipEvidence? = defaultTipEvidence()
        ) = CoreTrustEvaluator.evaluate(
            backendResponse, capabilityAdvertised, rpcSupported, policy,
            checkpointHeaderHex, corroboratedHeadersByHost, serverHost, nowMs,
            checkpointHeight, tipEvidence
        )
    }

    // ── Case 1: ElectrumX 1.13 + valid trusted evidence → TRUSTED ────────────

    @Test
    fun `case1 full valid evidence is TRUSTED with corroborators listed`() {
        val result = evaluate()
        assertEquals(CoreTrustLevel.TRUSTED, result.level)
        assertEquals(CoreTrustReason.CERTIFIED_BUILD, result.reason)
        assertEquals("4.8.0", result.coreVersion)
        assertEquals(listOf("electrum1.cipig.net"), result.corroboratedBy)
    }

    // ── Case 2: legacy server without capability → UNKNOWN, not unsafe ──────

    @Test
    fun `case2 legacy server is UNKNOWN legacy and never unsafe`() {
        val result = evaluate(
            backendResponse = null,
            capabilityAdvertised = false,
            rpcSupported = false,
            policy = certifiedPolicy(),
            checkpointHeaderHex = null,
            corroboratedHeadersByHost = emptyMap()
        )
        assertEquals(CoreTrustLevel.UNKNOWN, result.level)
        assertEquals(CoreTrustReason.LEGACY_SERVER, result.reason)
    }

    @Test
    fun `case11 failover onto legacy server keeps core status UNKNOWN`() {
        // Wallet failed over to a Cipig mirror: server answers, method missing.
        val result = evaluate(
            backendResponse = null,
            capabilityAdvertised = null,
            rpcSupported = false,
            serverHost = "electrum1.cipig.net"
        )
        assertEquals(CoreTrustLevel.UNKNOWN, result.level)
        assertEquals(CoreTrustReason.LEGACY_SERVER, result.reason)
    }

    // ── Case 3: malformed trust response → UNKNOWN, never TRUSTED ───────────

    @Test
    fun `case3 malformed response is UNKNOWN`() {
        val malformed = JsonParser.parseString(
            """{"server":"ElectrumX-RVN","backend":{"version":"4.8.0"},"compatibility":{}}"""
        ).asJsonObject
        val result = evaluate(backendResponse = malformed)
        assertEquals(CoreTrustLevel.UNKNOWN, result.level)
        assertEquals(CoreTrustReason.MALFORMED_RESPONSE, result.reason)
    }

    @Test
    fun `case3b flags as strings instead of booleans are malformed`() {
        val response = backendResponse()
        response.getAsJsonObject("compatibility").addProperty("coreSafe", "true")
        val result = evaluate(backendResponse = response)
        assertEquals(CoreTrustReason.MALFORMED_RESPONSE, result.reason)
    }

    @Test
    fun `case3c version string disagreeing with versionNumber is malformed`() {
        val result = evaluate(
            backendResponse = backendResponse(version = "4.8.0", versionNumber = 4_090_000L)
        )
        assertEquals(CoreTrustReason.MALFORMED_RESPONSE, result.reason)
        assertEquals(CoreTrustLevel.UNKNOWN, result.level)
    }

    // ── Case 4: unusable policy → UNKNOWN (never TRUSTED, never UNSAFE) ─────

    @Test
    fun `case4 missing policy caps classification at UNKNOWN`() {
        val result = evaluate(policy = null)
        assertEquals(CoreTrustLevel.UNKNOWN, result.level)
        assertEquals(CoreTrustReason.POLICY_UNAVAILABLE, result.reason)
    }

    // ── Case 5: Core exactly 4.8.0, certified → TRUSTED ──────────────────────

    @Test
    fun `case5 core exactly 4_8_0 certified is TRUSTED`() {
        val result = evaluate(
            backendResponse = backendResponse(version = "4.8.0", versionNumber = 4_080_000L)
        )
        assertEquals(CoreTrustLevel.TRUSTED, result.level)
    }

    // ── Case 6: newer Core with no certified identity → UNKNOWN ──────────────

    @Test
    fun `case6 higher version string without certified identity is UNKNOWN`() {
        val result = evaluate(
            backendResponse = backendResponse(
                version = "4.9.0",
                versionNumber = 4_090_000L,
                commit = "1111111111111111111111111111111111111111"
            )
        )
        assertEquals(CoreTrustLevel.UNKNOWN, result.level)
        assertEquals(CoreTrustReason.NOT_CERTIFIED, result.reason)
    }

    @Test
    fun `case6b higher version with no identity at all is UNKNOWN`() {
        val result = evaluate(
            backendResponse = backendResponse(
                version = "4.9.0",
                versionNumber = 4_090_000L,
                repository = null,
                commit = null
            )
        )
        assertEquals(CoreTrustReason.IDENTITY_MISSING, result.reason)
    }

    // ── Case 7: Core below 4.8.0 → UNSAFE/INCOMPATIBLE ───────────────────────

    @Test
    fun `case7 core below 4_8_0 is UNSAFE incompatible`() {
        val result = evaluate(
            backendResponse = backendResponse(
                version = "4.7.3",
                versionNumber = 4_070_300L,
                commit = null,
                repository = null
            )
        )
        assertEquals(CoreTrustLevel.UNSAFE, result.level)
        assertEquals(CoreTrustReason.INCOMPATIBLE_CORE, result.reason)
    }

    @Test
    fun `case7b known incident version 4_7_0 is UNSAFE known-unsafe`() {
        val result = evaluate(
            backendResponse = backendResponse(
                version = "4.7.0",
                versionNumber = 4_070_000L,
                commit = null,
                repository = null
            )
        )
        assertEquals(CoreTrustLevel.UNSAFE, result.level)
        assertEquals(CoreTrustReason.KNOWN_UNSAFE_CORE, result.reason)
    }

    // ── Case 8: malicious fake version string without evidence → UNKNOWN ────

    @Test
    fun `case8 fake 99_0_0 claim without verifiable evidence is UNKNOWN`() {
        val result = evaluate(
            backendResponse = backendResponse(
                version = "99.0.0",
                versionNumber = 99_000_000L,
                repository = null,
                commit = null
            )
        )
        assertEquals(CoreTrustLevel.UNKNOWN, result.level)
        assertEquals(CoreTrustReason.IDENTITY_MISSING, result.reason)
    }

    // ── Case 9: unsupported trust RPC despite advertisement → UNKNOWN ───────

    @Test
    fun `case9 advertised capability but RPC failure is UNKNOWN verification-failed`() {
        val result = evaluate(
            backendResponse = null,
            capabilityAdvertised = true,
            rpcSupported = true
        )
        assertEquals(CoreTrustLevel.UNKNOWN, result.level)
        assertEquals(CoreTrustReason.VERIFICATION_FAILED, result.reason)
    }

    // ── Case 10: timeout during trust check → UNKNOWN ────────────────────────

    @Test
    fun `case10 timeout maps to UNKNOWN never TRUSTED`() {
        // Evaluator input for a timed-out probe: no response, no capability info.
        val result = evaluate(
            backendResponse = null,
            capabilityAdvertised = null,
            rpcSupported = null
        )
        assertEquals(CoreTrustLevel.UNKNOWN, result.level)
        assertEquals(CoreTrustReason.VERIFICATION_FAILED, result.reason)
    }

    // ── Case 12: stale cached evidence → UNKNOWN ─────────────────────────────

    @Test
    fun `case12 stale observedAt is UNKNOWN`() {
        val result = evaluate(
            backendResponse = backendResponse(observedAt = NOW - 11 * 60_000L)
        )
        assertEquals(CoreTrustLevel.UNKNOWN, result.level)
        assertEquals(CoreTrustReason.STALE_EVIDENCE, result.reason)
    }

    // ── Additional adversarial cases ──────────────────────────────────────────

    @Test
    fun `revoked certified release is UNSAFE`() {
        val result = evaluate(policy = certifiedPolicy(status = "REVOKED"))
        assertEquals(CoreTrustLevel.UNSAFE, result.level)
        assertEquals(CoreTrustReason.KNOWN_UNSAFE_CORE, result.reason)
    }

    @Test
    fun `policy marking the release KNOWN_UNSAFE is UNSAFE`() {
        val result = evaluate(policy = certifiedPolicy(status = "KNOWN_UNSAFE"))
        assertEquals(CoreTrustLevel.UNSAFE, result.level)
    }

    @Test
    fun `wrong network is UNSAFE`() {
        val result = evaluate(
            backendResponse = backendResponse(network = "test"),
            policy = certifiedPolicy()
        )
        assertEquals(CoreTrustLevel.UNSAFE, result.level)
        assertEquals(CoreTrustReason.WRONG_NETWORK, result.reason)
    }

    @Test
    fun `any compatibility flag false blocks TRUSTED`() {
        for (flag in listOf("coreSafe", "checkpoint4487775")) {
            val response = when (flag) {
                "coreSafe" -> backendResponse(coreSafe = false)
                else -> backendResponse(checkpoint4487775 = false)
            }
            val result = evaluate(backendResponse = response)
            assertEquals(CoreTrustLevel.UNKNOWN, result.level)
            assertEquals(CoreTrustReason.NOT_SYNCHRONIZED, result.reason)
        }
    }

    @Test
    fun `missing own checkpoint header blocks TRUSTED`() {
        val result = evaluate(checkpointHeaderHex = null)
        assertEquals(CoreTrustReason.NO_CHAIN_CORROBORATION, result.reason)
    }

    @Test
    fun `same-operator corroboration does not count`() {
        // Cipig mirrors corroborating each other are one operator group.
        val result = evaluate(
            serverHost = "electrum1.cipig.net",
            corroboratedHeadersByHost = mapOf(
                "electrum2.cipig.net" to CHECKPOINT_HEADER,
                "electrum3.cipig.net" to CHECKPOINT_HEADER
            )
        )
        assertEquals(CoreTrustLevel.UNKNOWN, result.level)
        assertEquals(CoreTrustReason.NO_CHAIN_CORROBORATION, result.reason)
    }

    @Test
    fun `different header from independent operator blocks TRUSTED`() {
        val result = evaluate(
            corroboratedHeadersByHost =
                mapOf("electrum1.cipig.net" to CHECKPOINT_HEADER.reversed())
        )
        assertEquals(CoreTrustReason.NO_CHAIN_CORROBORATION, result.reason)
    }

    // ── RT108-SEC-102: live-tip corroboration ─────────────────────────────────

    @Test
    fun `missing live tip evidence blocks TRUSTED`() {
        val result = evaluate(tipEvidence = null)
        assertEquals(CoreTrustLevel.UNKNOWN, result.level)
        assertEquals(CoreTrustReason.NO_CHAIN_CORROBORATION, result.reason)
    }

    @Test
    fun `forked tip header blocks TRUSTED`() {
        val forkedTip = defaultTipEvidence().copy(
            tipHeaderHex = TIP_HEADER.reversed()
        )
        val result = evaluate(tipEvidence = forkedTip)
        assertEquals(CoreTrustLevel.UNKNOWN, result.level)
        assertEquals(CoreTrustReason.NO_CHAIN_CORROBORATION, result.reason)
    }

    @Test
    fun `tip not past checkpoint blocks TRUSTED`() {
        val earlyTip = defaultTipEvidence().copy(tipHeight = 4_487_800L)
        val result = evaluate(tipEvidence = earlyTip)
        assertEquals(CoreTrustReason.NO_CHAIN_CORROBORATION, result.reason)
    }

    @Test
    fun `same-operator live tip corroboration does not count`() {
        val sameOperatorTip = defaultTipEvidence().copy(
            corroboratedHeaderAtTipByHost = mapOf(
                "electrum3.cipig.net" to TIP_HEADER
            )
        )
        val result = evaluate(
            serverHost = "electrum1.cipig.net",
            tipEvidence = sameOperatorTip
        )
        assertEquals(CoreTrustReason.NO_CHAIN_CORROBORATION, result.reason)
    }

    @Test
    fun `commit mismatch with same repository is not certified`() {
        val result = evaluate(
            backendResponse = backendResponse(
                commit = "9999999999999999999999999999999999999999"
            )
        )
        assertEquals(CoreTrustReason.NOT_CERTIFIED, result.reason)
        assertEquals(CoreTrustLevel.UNKNOWN, result.level)
    }

    @Test
    fun `operator grouping merges cipig mirrors and separates hosts`() {
        assertEquals("cipig", CoreTrustEvaluator.operatorGroup("electrum2.cipig.net"))
        assertEquals(
            CoreTrustEvaluator.operatorGroup("electrumx.raventag.com"),
            CoreTrustEvaluator.operatorGroup("electrumx.raventag.com")
        )
        assertTrue(
            CoreTrustEvaluator.operatorGroup("electrumx.raventag.com") !=
                CoreTrustEvaluator.operatorGroup("rvn4lyfe.com")
        )
    }

    @Test
    fun `version number decoding uses daemon divmod encoding`() {
        assertEquals(
            "[4, 8, 0, 0]",
            CoreTrustEvaluator.parseCoreVersionNumber(4_080_000L)!!.contentToString()
        )
        assertEquals(
            "[4, 7, 3, 5]",
            CoreTrustEvaluator.parseCoreVersionNumber(4_070_305L)!!.contentToString()
        )
    }

    @Test
    fun `features capability detection and checkpoint height parsing`() {
        val features = JsonParser.parseString(
            """{"ravencoin":{"backend_info":true,"assets":true,
               "checkpoint":{"height":4487775,"hash":"000000000002d645"}}}"""
        ).asJsonObject
        assertEquals(true, CoreTrustEvaluator.capabilityAdvertised(features))
        assertEquals(4_487_775L, CoreTrustEvaluator.checkpointHeightFromFeatures(features))
        // Legacy features (Cipig today): no ravencoin block at all.
        val legacy = JsonParser.parseString("""{"genesis_hash":"x","hosts":{}}""").asJsonObject
        assertEquals(null, CoreTrustEvaluator.capabilityAdvertised(legacy))
        assertEquals(
            CoreTrustEvaluator.DEFAULT_CHECKPOINT_HEIGHT,
            CoreTrustEvaluator.checkpointHeightFromFeatures(legacy)
        )
    }
}
