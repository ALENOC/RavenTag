/**
 * IpfsResolver.kt
 *
 * Utility singleton that converts IPFS content references in any supported format
 * into one or more plain HTTPS URLs that can be fetched by an HTTP client.
 *
 * Supported input formats:
 *   - "ipfs://<CID>"            native IPFS URI scheme
 *   - "/ipfs/<CID>"             path-style IPFS reference
 *   - "<CID>"                   bare CID string (assumed to be an IPFS hash)
 *   - "http[s]://<url>"         already a full HTTP URL, returned unchanged
 *
 * The list of public or self-hosted gateways is read at runtime from
 * [BuildConfig.IPFS_GATEWAYS], a comma-separated string injected via the
 * app's Gradle build configuration (see build.gradle). Callers can try each
 * candidate URL in order and use the first one that responds successfully,
 * which provides resilience when a gateway is temporarily unavailable.
 */
package io.raventag.app.ipfs

import io.raventag.app.BuildConfig

/**
 * Resolves IPFS content references to HTTP gateway URLs.
 *
 * All methods are pure functions with no side-effects; the only mutable state is the
 * lazily-initialized [gateways] list which is computed once from [BuildConfig.IPFS_GATEWAYS].
 */
object IpfsResolver {

    /**
     * Ordered list of IPFS HTTP gateway base URLs read from [BuildConfig.IPFS_GATEWAYS].
     *
     * The build config value is a comma-separated string, e.g.:
     *   "https://ipfs.io/ipfs/,https://cloudflare-ipfs.com/ipfs/"
     *
     * Each entry is trimmed of surrounding whitespace and empty entries are discarded.
     * The list is computed lazily so that the build config is accessed only when first needed.
     */
    val gateways: List<String> by lazy {
        BuildConfig.IPFS_GATEWAYS
            .split(',')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
    }

    /**
     * Returns a list of candidate HTTP URLs for the given [ipfsRef].
     *
     * Supports several [ipfsRef] formats:
     *   - Bare CID (Qm..., bafy...)
     *   - native IPFS URI (ipfs://CID)
     *   - Path-style ( /ipfs/CID )
     *   - Full gateway URL ( https://gateway.com/ipfs/CID )
     *
     * If the input is a full URL, we extract the CID and re-resolve it against
     * ALL configured gateways. This provides resilience even if the brand
     * metadata pointed to a specific dead gateway.
     *
     * @param ipfsRef The IPFS reference to resolve.
     * @return Ordered list of candidate HTTP URLs; callers should try them in sequence.
     */
    fun candidateUrls(ipfsRef: String): List<String> {
        val normalized = ipfsRef.trim()
        if (normalized.isEmpty()) return emptyList()

        // Extract the raw CID from various known formats.
        val cid = when {
            normalized.startsWith("ipfs://") -> normalized.removePrefix("ipfs://")
            normalized.startsWith("/ipfs/") -> normalized.removePrefix("/ipfs/")
            normalized.contains("/ipfs/") -> normalized.substringAfter("/ipfs/").substringBefore("?")
            normalized.startsWith("http") && normalized.contains(".ipfs.") -> {
                // Handle subdomain-style gateways (e.g. bafy...ipfs.dweb.link)
                normalized.substringAfter("://").substringBefore(".ipfs.")
            }
            // Assume a bare string is a CID (e.g. Qm... or bafy...).
            else -> normalized
        }
        
        // Final sanity check: strip trailing slash or query params if any
        val cleanCid = cid.substringBefore("/").substringBefore("?")
        
        // Build one URL per gateway by appending the CID directly to the gateway base URL.
        // Gateways in BuildConfig already include the trailing "/ipfs/" path segment.
        return gateways.map { gateway -> gateway + cleanCid }
    }

    /**
     * Returns the first candidate HTTP URL for [ipfsRef], or null if no gateways are
     * configured or [ipfsRef] is blank.
     *
     * Convenience wrapper around [candidateUrls] for callers that only need a single URL
     * and do not implement gateway fallback logic.
     *
     * @param ipfsRef The IPFS reference to resolve.
     * @return The primary gateway URL, or null.
     */
    fun primaryUrl(ipfsRef: String): String? = candidateUrls(ipfsRef).firstOrNull()
}
