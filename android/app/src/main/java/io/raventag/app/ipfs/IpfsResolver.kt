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
     * If the input is already a direct HTTP URL that does NOT contain an IPFS
     * path segment, it is returned as the sole candidate (for non-IPFS images).
     *
     * @param ipfsRef The IPFS reference to resolve.
     * @return Ordered list of candidate HTTP URLs; callers should try them in sequence.
     */
    fun candidateUrls(ipfsRef: String): List<String> {
        val normalized = ipfsRef.trim()
        if (normalized.isEmpty()) return emptyList()
        return buildCandidateUrls(normalized)
    }

    private fun buildCandidateUrls(normalized: String): List<String> {

        // If it's already a direct URL (HTTP or local file) with no IPFS path, use as-is
        if (normalized.startsWith("file://")) return listOf(normalized)
        if (isHttpUrl(normalized) && !normalized.contains("/ipfs/", ignoreCase = true) && !normalized.contains(".ipfs.", ignoreCase = true)) {
            return listOf(normalized)
        }

        // Extract the IPFS reference from various known formats. Keep any path after
        // the CID because directory-style IPFS assets are commonly referenced as
        // ipfs://<CID>/image.png or https://gateway/ipfs/<CID>/image.png.
        val ipfsPath = when {
            normalized.startsWith("ipfs://") -> normalized.removePrefix("ipfs://")
            normalized.startsWith("/ipfs/", ignoreCase = true) -> normalized.drop("/ipfs/".length)
            normalized.contains("/ipfs/", ignoreCase = true) -> normalized.afterIpfsPathMarker()
            isHttpUrl(normalized) && normalized.contains(".ipfs.", ignoreCase = true) ->
                subdomainGatewayToIpfsPath(normalized)
            // Assume a bare string is a CID, optionally with a path after it.
            else -> normalized
        }.trimStart('/')

        if (ipfsPath.isEmpty()) return emptyList()

        // Build one URL per gateway by appending the IPFS reference directly to the gateway base URL.
        // Gateways in BuildConfig already include the trailing "/ipfs/" path segment.
        return gateways.map { gateway -> gateway.trimEnd('/') + "/" + ipfsPath }
    }

    private fun isHttpUrl(value: String): Boolean =
        value.startsWith("http://", ignoreCase = true) || value.startsWith("https://", ignoreCase = true)

    private fun String.afterIpfsPathMarker(): String {
        val marker = "/ipfs/"
        val index = indexOf(marker, ignoreCase = true)
        return if (index >= 0) substring(index + marker.length) else this
    }

    private fun subdomainGatewayToIpfsPath(url: String): String {
        return try {
            val uri = java.net.URI(url)
            val host = uri.host.orEmpty()
            val cid = host.substringBefore(".ipfs.")
            val path = uri.rawPath?.takeIf { it != "/" }.orEmpty()
            val query = uri.rawQuery?.let { "?$it" }.orEmpty()
            val fragment = uri.rawFragment?.let { "#$it" }.orEmpty()
            cid + path + query + fragment
        } catch (_: Exception) {
            val withoutScheme = url.substringAfter("://")
            val cid = withoutScheme.substringBefore(".ipfs.")
            val afterMarker = withoutScheme.substringAfter(".ipfs.", "")
            val pathStart = afterMarker.indexOf('/')
            if (pathStart >= 0) cid + afterMarker.substring(pathStart) else cid
        }
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
