package io.raventag.app.wallet.server

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import io.raventag.app.wallet.coretrust.CanonicalJson
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer
import java.security.MessageDigest
import java.time.OffsetDateTime
import java.util.Base64

/** Pure verifier for the signed ElectrumX server registry. */
object ServerRegistry {
    class RegistryException(message: String) : Exception(message)

    const val REGISTRY_URL =
        "https://raw.githubusercontent.com/ALENOC/electrum-ravencoin/master/electrum/servers.signed.json"
    private const val SCHEMA_VERSION = 1L
    private const val MAX_SERVERS = 512
    private val SIGNATURE_DOMAIN =
        "ALENOC-RVN-SERVER-REGISTRY-v1\u0000".toByteArray(Charsets.UTF_8)

    private val TRUSTED_KEYS = mapOf(
        "d7a50f481a496f3e" to hexToBytes(
            "f15e00d3e5edb0d9db31f81171a5e8716e247b53bc0e120655f665ba54b0d0c0"
        )
    )

    enum class Source { SIGNED, BASELINE, USER }

    data class Server(
        val host: String,
        val port: Int,
        val operatorGroup: String?,
        val source: Source
    ) {
        val canCorroborate: Boolean
            get() = source == Source.SIGNED && operatorGroup != null
    }

    data class VerifiedRegistry(
        val registryVersion: Long,
        val expiresAtMs: Long,
        val servers: List<Server>,
        val canonicalDigest: String
    )

    fun verify(
        documentJson: String,
        nowMs: Long = System.currentTimeMillis(),
        minimumRegistryVersion: Long = 0L,
        acceptedDigestAtMinimum: String? = null
    ): VerifiedRegistry {
        val root = try {
            JsonParser.parseString(documentJson).asJsonObject
        } catch (_: Exception) {
            throw RegistryException("signed registry is not valid JSON")
        }
        val body = root.objectOrNull("registry")
            ?: throw RegistryException("signed registry has no registry body")
        val signature = root.objectOrNull("signature")
            ?: throw RegistryException("signed registry has no signature")
        if (signature.stringOrNull("algorithm") != "ed25519") {
            throw RegistryException("unsupported registry signature algorithm")
        }
        val keyId = signature.stringOrNull("keyId")
            ?: throw RegistryException("registry signature has no key id")
        val publicKey = TRUSTED_KEYS[keyId]
            ?: throw RegistryException("registry signed by an unknown key")
        val signatureBytes = try {
            Base64.getDecoder().decode(signature.stringOrNull("value") ?: "")
        } catch (_: IllegalArgumentException) {
            throw RegistryException("registry signature is not valid base64")
        }
        if (signatureBytes.size != 64) {
            throw RegistryException("registry signature has the wrong length")
        }
        val canonicalBody = try {
            CanonicalJson.serialize(body).toByteArray(Charsets.UTF_8)
        } catch (_: Exception) {
            throw RegistryException("registry body is not canonically serializable")
        }
        val signedBytes = SIGNATURE_DOMAIN + canonicalBody
        if (!verifyEd25519(publicKey, signatureBytes, signedBytes)) {
            throw RegistryException("registry signature does not verify")
        }

        if (body.longOrNull("schemaVersion") != SCHEMA_VERSION) {
            throw RegistryException("unsupported registry schema version")
        }
        val version = body.longOrNull("registryVersion")
            ?.takeIf { it > 0L }
            ?: throw RegistryException("registry version must be a positive integer")
        val generatedAt = parseTimestamp(body.stringOrNull("generatedAt"), "generatedAt")
        val expiresAt = parseTimestamp(body.stringOrNull("expiresAt"), "expiresAt")
        if (expiresAt <= generatedAt) {
            throw RegistryException("registry expiry must follow generation time")
        }
        if (nowMs > expiresAt) {
            throw RegistryException("signed registry has expired")
        }
        if (version < minimumRegistryVersion) {
            throw RegistryException("registry rollback refused")
        }

        val digest = MessageDigest.getInstance("SHA-256")
            .digest(signedBytes)
            .joinToString("") { "%02x".format(it) }
        if (version == minimumRegistryVersion &&
            acceptedDigestAtMinimum != null &&
            digest != acceptedDigestAtMinimum
        ) {
            throw RegistryException("same registry version has different contents")
        }

        val serverObject = body.objectOrNull("servers")
            ?: throw RegistryException("registry server list is missing")
        if (serverObject.size() !in 1..MAX_SERVERS) {
            throw RegistryException("registry server count is invalid")
        }
        val servers = serverObject.entrySet().mapNotNull { (host, element) ->
            validateHost(host)
            val entry = try {
                element.asJsonObject
            } catch (_: Exception) {
                throw RegistryException("server entry must be an object")
            }
            val tlsPort = if (entry.has("s")) parsePort(entry.requiredString("s"), host) else null
            val tcpPort = if (entry.has("t")) parsePort(entry.requiredString("t"), host) else null
            if (tlsPort == null && tcpPort == null) {
                throw RegistryException("server entry has no supported port")
            }
            listOf("version" to 64, "pruning" to 32, "backend_policy" to 384)
                .forEach { (field, maxLength) ->
                    if (entry.has(field)) validateText(entry.requiredString(field), field, maxLength)
                }
            val operatorGroup = if (entry.has("operatorGroup")) {
                entry.requiredString("operatorGroup").also {
                validateText(it, "operatorGroup", 128)
                if (tlsPort == null) {
                    throw RegistryException("operator group requires a TLS endpoint")
                }
                }
            } else null
            // RavenTag has no Tor transport and uses TLS only.
            if (tlsPort == null || host.endsWith(".onion", ignoreCase = true)) null
            else Server(host.lowercase(), tlsPort, operatorGroup, Source.SIGNED)
        }
        if (servers.isEmpty()) {
            throw RegistryException("registry has no usable TLS servers")
        }
        return VerifiedRegistry(version, expiresAt, servers, digest)
    }

    private fun validateHost(host: String) {
        if (host.isEmpty() || host.length > 255 ||
            host.any { it.isWhitespace() || it.code < 32 || it.code == 127 } ||
            "://" in host || ':' in host || '/' in host || '\\' in host
        ) {
            throw RegistryException("invalid server hostname")
        }
    }

    private fun parsePort(value: String, host: String): Int {
        if (value.isEmpty() || value.any { !it.isDigit() }) {
            throw RegistryException("invalid port for $host")
        }
        return value.toIntOrNull()?.takeIf { it in 1..65535 }
            ?: throw RegistryException("invalid port for $host")
    }

    private fun validateText(value: String, field: String, maxLength: Int) {
        if (value.isEmpty() || value.length > maxLength ||
            value.any { it.code < 32 || it.code == 127 }
        ) {
            throw RegistryException("invalid $field field")
        }
    }

    private fun parseTimestamp(value: String?, field: String): Long = try {
        OffsetDateTime.parse(value ?: throw IllegalArgumentException()).toInstant().toEpochMilli()
    } catch (_: Exception) {
        throw RegistryException("$field is not a valid timestamp")
    }

    private fun verifyEd25519(key: ByteArray, signature: ByteArray, data: ByteArray): Boolean {
        return try {
            val verifier = Ed25519Signer()
            verifier.init(false, Ed25519PublicKeyParameters(key, 0))
            verifier.update(data, 0, data.size)
            verifier.verifySignature(signature)
        } catch (_: Exception) {
            false
        }
    }

    private fun hexToBytes(hex: String): ByteArray = ByteArray(hex.length / 2) { index ->
        hex.substring(index * 2, index * 2 + 2).toInt(16).toByte()
    }

    private fun JsonObject.objectOrNull(name: String): JsonObject? = try {
        get(name)?.takeIf { it.isJsonObject }?.asJsonObject
    } catch (_: Exception) {
        null
    }

    private fun JsonObject.stringOrNull(name: String): String? = try {
        get(name)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString
    } catch (_: Exception) {
        null
    }

    private fun JsonObject.requiredString(name: String): String =
        stringOrNull(name) ?: throw RegistryException("$name must be a string")

    private fun JsonObject.longOrNull(name: String): Long? = try {
        get(name)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }
            ?.asJsonPrimitive?.asString
            ?.takeIf { it.matches(Regex("-?\\d+")) }
            ?.toLong()
    } catch (_: Exception) {
        null
    }
}
