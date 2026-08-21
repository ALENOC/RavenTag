package io.raventag.app.wallet.coretrust

import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.security.MessageDigest

/**
 * Core Trust policy verification tests.
 *
 * The primary vector is the REAL signed safe-Core policy published by the
 * electrumx-ravencoin maintainers (policyVersion 2, keyId a6b89849cec9eab7).
 * Its signature was independently verified against the reference Python
 * verifier before being embedded here, so these tests prove the Kotlin
 * canonical-JSON + Ed25519 path is byte-compatible with the signer.
 */
class CoreTrustPolicyTest {

    companion object {
        /** Exact bytes published at core-safety/production/safe-core-policy.json (v1.13.0). */
        val REAL_POLICY_JSON = """
        {
          "policy": {
            "expiresAt": "2026-11-13T19:02:14+00:00",
            "generatedAt": "2026-08-15T19:02:14+00:00",
            "policyVersion": 2,
            "releases": [
              {
                "certification": {
                  "finishedAt": 1786820050,
                  "harnessVersion": "1.0.0",
                  "profile": "rvn-consensus-2026-08-v1",
                  "profileRevision": 1,
                  "profileSha256": "1342d079f2eef7ae0803a247d2908c4b031ee4a542b0f837210f92ba36ae27b2",
                  "result": "PASS"
                },
                "commit": "b60f50e04f1fba425b28804e61be2694faaf3469",
                "publishedAt": "2026-08-11T08:16:45Z",
                "reportDigest": "7a7e04b7c38a651896a5126a99fd9e8a8858c771c0cce5366b13e993f8205e7f",
                "repository": "2miners/Ravencoin",
                "status": "KNOWN_SAFE",
                "tag": "v4.8.0",
                "version": "4.8.0"
              }
            ],
            "safetyProfile": "rvn-consensus-2026-08-v1",
            "schemaVersion": 1
          },
          "signature": {
            "algorithm": "ed25519",
            "keyId": "a6b89849cec9eab7",
            "value": "32lQq8C5XaAAFnL7YackYJpfersYas0zBRQN8ej1Yz1YDM7EeNvhNCRmQTO1A5fMw/P+SN0J+9Ek3ZIn2vBQDQ=="
          }
        }
        """.trimIndent()

        /** sha256(domain || canonical(body)) computed with the reference Python signer. */
        private const val EXPECTED_CANONICAL_SHA256 =
            "6c6d78f22ee316e0cbc8234de88638d77a6d6bf25e5e4b4322c577007f002ba6"

        /** Inside the validity window of the real policy. */
        private val NOW = 1_787_000_000_000L // 2026-09-13T09:33:20Z
    }

    @Test
    fun `real policy verifies and exposes the certified release`() {
        val verified = CoreTrustPolicy.verify(REAL_POLICY_JSON, nowMs = NOW)
        assertEquals(2L, verified.policyVersion)
        assertEquals("rvn-consensus-2026-08-v1", verified.safetyProfile)
        assertEquals(1, verified.releases.size)
        val release = verified.releases[0]
        assertEquals("2miners/Ravencoin", release.repository)
        assertEquals("b60f50e04f1fba425b28804e61be2694faaf3469", release.commit)
        assertEquals("KNOWN_SAFE", release.status)
        // Exact-commit lookup works case-insensitively on the commit hex.
        assertNotNull(
            verified.findRelease("2miners/Ravencoin", "B60F50E04F1FBA425B28804E61BE2694FAAF3469")
        )
    }

    @Test
    fun `canonical serialization is byte-compatible with the reference signer`() {
        val body = JsonParser.parseString(REAL_POLICY_JSON).asJsonObject["policy"]
        val canonical = CanonicalJson.serialize(body)
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(("ALENOC-RVN-CORE-POLICY-v1\u0000" + canonical).toByteArray(Charsets.UTF_8))
        assertEquals(EXPECTED_CANONICAL_SHA256, digest.joinToString("") { "%02x".format(it) })
    }

    @Test
    fun `embedded baseline asset is the real signed policy and verifies`() {
        val asset = File("src/main/assets/core_trust_policy_baseline.json")
        assertTrue("baseline asset must exist in the repo", asset.isFile)
        val verified = CoreTrustPolicy.verify(asset.readText(), nowMs = NOW)
        assertEquals(2L, verified.policyVersion)
        assertEquals(
            JsonParser.parseString(REAL_POLICY_JSON),
            JsonParser.parseString(asset.readText())
        )
    }

    @Test
    fun `tampered policy body fails the signature`() {
        val tampered = REAL_POLICY_JSON.replace(
            "\"status\": \"KNOWN_SAFE\"",
            "\"status\": \"REVOKED\""
        )
        val e = assertThrows(CoreTrustPolicy.PolicyException::class.java) {
            CoreTrustPolicy.verify(tampered, nowMs = NOW)
        }
        assertTrue(e.message!!.contains("signature"))
    }

    @Test
    fun `unknown signing key id is rejected`() {
        val tampered = REAL_POLICY_JSON.replace("a6b89849cec9eab7", "ffffffffffffffff")
        assertThrows(CoreTrustPolicy.PolicyException::class.java) {
            CoreTrustPolicy.verify(tampered, nowMs = NOW)
        }
    }

    @Test
    fun `invalid base64 signature is rejected`() {
        val tampered = REAL_POLICY_JSON.replace(
            "32lQq8C5XaAAFnL7YackYJpfersYas0zBRQN8ej1Yz1YDM7EeNvhNCRmQTO1A5fMw/P+SN0J+9Ek3ZIn2vBQDQ==",
            "not!!valid!!base64!!"
        )
        assertThrows(CoreTrustPolicy.PolicyException::class.java) {
            CoreTrustPolicy.verify(tampered, nowMs = NOW)
        }
    }

    @Test
    fun `expired policy is rejected`() {
        val afterExpiry = 1_800_000_000_000L // well past 2026-11-13
        val e = assertThrows(CoreTrustPolicy.PolicyException::class.java) {
            CoreTrustPolicy.verify(REAL_POLICY_JSON, nowMs = afterExpiry)
        }
        assertTrue(e.message!!.contains("expired"))
    }

    @Test
    fun `policy older than the accepted high-water mark is a refused rollback`() {
        val e = assertThrows(CoreTrustPolicy.PolicyException::class.java) {
            CoreTrustPolicy.verify(REAL_POLICY_JSON, nowMs = NOW, minimumPolicyVersion = 3L)
        }
        assertTrue(e.message!!.contains("rollback"))
    }

    @Test
    fun `non-json and non-object documents are rejected`() {
        assertThrows(CoreTrustPolicy.PolicyException::class.java) {
            CoreTrustPolicy.verify("not json at all", nowMs = NOW)
        }
        assertThrows(CoreTrustPolicy.PolicyException::class.java) {
            CoreTrustPolicy.verify("[1,2,3]", nowMs = NOW)
        }
    }

    @Test
    fun `canonical json escapes match python ensure_ascii`() {
        // Sorted keys, no whitespace, control-char and non-ASCII escaping.
        val element = JsonParser.parseString(
            """{"b":1,"a":"q\"\\é\n€😀","c":{"z":true,"y":null}}"""
        )
        assertEquals(
            """{"a":"q\"\\é\n€😀","b":1,"c":{"y":null,"z":true}}"""
                .replace("é", "\\u00e9")
                .replace("€", "\\u20ac")
                .replace("😀", "\\ud83d\\ude00"),
            CanonicalJson.serialize(element)
        )
    }

    @Test
    fun `canonical json rejects non-integer number literals`() {
        val element = JsonParser.parseString("""{"x":1.5}""")
        assertThrows(CanonicalJson.CanonicalJsonException::class.java) {
            CanonicalJson.serialize(element)
        }
    }
}
