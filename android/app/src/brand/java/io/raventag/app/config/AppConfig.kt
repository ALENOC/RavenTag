package io.raventag.app.config

/**
 * Brand operator app configuration.
 * This flavor includes full Brand Dashboard, asset management, NFC programming.
 *
 * To customize for a specific brand, edit the values below before compiling.
 */
object AppConfig {
    /** Full brand management app , shows Brand tab and admin features */
    const val IS_BRAND_APP = true

    /** Brand name shown in the UI header and app name */
    const val BRAND_NAME = "RavenTag"

    /** Default verification server URL , overridable from Settings */
    const val DEFAULT_VERIFY_URL = "https://api.raventag.com"

    /** Primary accent color (ARGB hex). Default: Ravencoin orange */
    const val PRIMARY_COLOR_HEX = "#EF7536"

    /** Drawable resource name for in-app logo */
    const val LOGO_DRAWABLE = "raven_logo"

    /** Whether the Settings screen shows the admin/brand configuration fields */
    const val SHOW_BRAND_SETTINGS = true

    /**
     * D-09: Hardcoded public ElectrumX fallback pool. Round-robin via
     * [io.raventag.app.wallet.health.NodeHealthMonitor].
     *
     * The clearnet pool mirrors the current Electrum-Ravencoin client order:
     *   1. electrumx.raventag.com (RavenTag-operated primary)
     *   2. electrum1.cipig.net
     *   3. electrum2.cipig.net
     *   4. electrum3.cipig.net
     *   5. rvn4lyfe.com
     *
     * Cipig uses TLS port 20051 for Ravencoin; RavenTag and rvn4lyfe use 50002.
     * The Electrum client's onion entry is intentionally not included because
     * RavenTag does not provide an integrated Tor transport.
     *
     * Current count: 5.
     */
    val ELECTRUM_SERVERS: List<Pair<String, Int>> = listOf(
        "electrumx.raventag.com" to 50002,
        "electrum1.cipig.net" to 20051,
        "electrum2.cipig.net" to 20051,
        "electrum3.cipig.net" to 20051,
        "rvn4lyfe.com" to 50002,
    )

    /**
     * Block explorer URL prefix for Ravencoin transactions (D-19).
     * Appending a txid yields a browsable transaction page, e.g. `${EXPLORER_URL}<txid>`.
     * Verified 2026-05 against Ravencoin mainnet (Cryptoscope / Solus Explorer).
     */
    const val EXPLORER_URL: String = "https://rvn.cryptoscope.io/tx/?txid="
}
