package io.raventag.app.wallet.coretrust

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer
import java.time.OffsetDateTime
import java.util.Base64

/**
 * Verified view of the Ed25519-signed safe-Core policy published by the
 * electrumx-ravencoin maintainers.
 *
 * Trust rules (mirroring the reference verifier in
 * electrumx-ravencoin `core-safety/scripts/policy.py`):
 *  - the signature is over a canonical serialization, so re-serializing the
 *    document cannot change what was signed;
 *  - `policyVersion` is monotonic: a policy older than the last accepted
 *    one is a rollback and is refused;
 *  - a release is identified by repository + exact commit. `version`/`tag`
 *    are metadata and never a trust input on their own;
 *  - only releases with status `KNOWN_SAFE` make a backend eligible for
 *    TRUSTED; `REVOKED`/`KNOWN_UNSAFE` are positive UNSAFE evidence.
 */
object CoreTrustPolicy {

    class PolicyException(message: String) : Exception(message)

    /** Static HTTPS distribution path of the maintained policy. */
    const val POLICY_URL =
        "https://raw.githubusercontent.com/ALENOC/electrumx-ravencoin/master/core-safety/production/safe-core-policy.json"

    /** Domain separator prefixed to the canonical bytes before signing/verifying. */
    private val SIGNATURE_DOMAIN = "ALENOC-RVN-CORE-POLICY-v1\u0000".toByteArray(Charsets.UTF_8)

    /**
     * Pinned Ed25519 verification keys, by key id. Key rotation on the
     * publisher's side requires shipping an app update — a policy document
     * can never introduce a new trust root by itself.
     */
    val TRUSTED_KEYS: Map<String, ByteArray> = mapOf(
        "a6b89849cec9eab7" to hexToBytes(
            "9fc91edbe763513490248a23ae97575a6b963101b644e01493a3860b99e35648"
        )
    )

    private const val SCHEMA_VERSION = 1L
    private val VALID_RELEASE_STATUSES = setOf("KNOWN_SAFE", "KNOWN_UNSAFE", "REVOKED")
    private val COMMIT_SHA256 = Regex("[0-9a-f]{40}")

    data class PolicyRelease(
        val repository: String,
        val tag: String?,
        val version: String,
        val commit: String,
        val status: String
    )

    data class VerifiedPolicy(
        val policyVersion: Long,
        val expiresAtMs: Long,
        val safetyProfile: String,
        val releases: List<PolicyRelease>
    ) {
        /** Exact-commit lookup used by the evaluator (version strings ignored). */
        fun findRelease(repository: String?, commit: String?): PolicyRelease? {
            if (repository == null || commit == null) return null
            val normalized = commit.lowercase()
            return releases.firstOrNull {
                it.repository == repository && it.commit == normalized
            }
        }
    }

    /**
     * Verifies a policy document (raw JSON text) and returns its validated
     * body, or throws [PolicyException]. Never returns a policy that is
     * expired or older than [minimumPolicyVersion].
     */
    fun verify(
        documentText: String,
        nowMs: Long = System.currentTimeMillis(),
        minimumPolicyVersion: Long = 0L
    ): VerifiedPolicy {
        val document = try {
            JsonParser.parseString(documentText)
        } catch (_: Exception) {
            throw PolicyException("policy is not valid JSON")
        }
        if (!document.isJsonObject) throw PolicyException("policy document must be an object")
        val root = document.asJsonObject
        val body = root.getAsJsonObject("policy") ?: throw PolicyException("policy body missing")
        val signature = root.getAsJsonObject("signature")
            ?: throw PolicyException("policy signature missing")

        if (signature.get("algorithm")?.takeIf { it.isJsonPrimitive }?.asString != "ed25519") {
            throw PolicyException("unsupported signature algorithm")
        }
        val keyId = signature.stringOrNull("keyId") ?: throw PolicyException("keyId missing")
        val publicKey = TRUSTED_KEYS[keyId]
            ?: throw PolicyException("policy signed by unknown key id")
        val signatureText = signature.stringOrNull("value")
            ?: throw PolicyException("signature value missing")
        val signatureBytes = try {
            Base64.getDecoder().decode(signatureText)
        } catch (_: Exception) {
            throw PolicyException("signature is not valid base64")
        }
        if (signatureBytes.size != 64) throw PolicyException("signature length invalid")

        val canonical = try {
            SIGNATURE_DOMAIN + CanonicalJson.serialize(body).toByteArray(Charsets.UTF_8)
        } catch (_: CanonicalJson.CanonicalJsonException) {
            throw PolicyException("policy body is not canonically serializable")
        }
        if (!verifyEd25519(publicKey, signatureBytes, canonical)) {
            throw PolicyException("policy signature does not verify")
        }

        // Schema validation — independent of the signature result above.
        if (body.longOrNull("schemaVersion") != SCHEMA_VERSION) {
            throw PolicyException("unsupported policy schemaVersion")
        }
        val policyVersion = body.longOrNull("policyVersion")
            ?: throw PolicyException("policyVersion missing")
        if (policyVersion < 1) throw PolicyException("policyVersion must be positive")
        if (policyVersion < minimumPolicyVersion) {
            throw PolicyException("policy rollback: $policyVersion < $minimumPolicyVersion")
        }
        val safetyProfile = body.stringOrNull("safetyProfile")
            ?: throw PolicyException("safetyProfile missing")
        val expiresText = body.stringOrNull("expiresAt")
        val expiresAtMs = try {
            OffsetDateTime.parse(expiresText).toInstant().toEpochMilli()
        } catch (_: Exception) {
            throw PolicyException("expiresAt is not a valid timestamp")
        }
        if (nowMs > expiresAtMs) throw PolicyException("policy has expired")

        val releasesArray = body.getAsJsonArray("releases")
            ?: throw PolicyException("releases missing")
        val releases = mutableListOf<PolicyRelease>()
        for (entry in releasesArray) {
            if (!entry.isJsonObject) throw PolicyException("release entry must be an object")
            val obj = entry.asJsonObject
            val repository = obj.stringOrNull("repository")
            val version = obj.stringOrNull("version")
            val commit = obj.stringOrNull("commit")
            val status = obj.stringOrNull("status")
            if (repository == null || version == null || commit == null || status == null) {
                throw PolicyException("release entry is missing required fields")
            }
            if (status !in VALID_RELEASE_STATUSES) {
                throw PolicyException("invalid release status $status")
            }
            val normalizedCommit = commit.lowercase()
            if (!COMMIT_SHA256.matches(normalizedCommit)) {
                throw PolicyException("release commit is malformed")
            }
            releases.add(
                PolicyRelease(
                    repository = repository,
                    tag = obj.stringOrNull("tag"),
                    version = version,
                    commit = normalizedCommit,
                    status = status
                )
            )
        }

        return VerifiedPolicy(
            policyVersion = policyVersion,
            expiresAtMs = expiresAtMs,
            safetyProfile = safetyProfile,
            releases = releases
        )
    }

    private fun verifyEd25519(publicKey32: ByteArray, signature64: ByteArray, data: ByteArray): Boolean {
        return try {
            val verifier = Ed25519Signer()
            verifier.init(false, Ed25519PublicKeyParameters(publicKey32, 0))
            verifier.update(data, 0, data.size)
            verifier.verifySignature(signature64)
        } catch (_: Exception) {
            false
        }
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

    private fun hexToBytes(hex: String): ByteArray =
        ByteArray(hex.length / 2) { i ->
            ((Character.digit(hex[2 * i], 16) shl 4) + Character.digit(hex[2 * i + 1], 16)).toByte()
        }
}
