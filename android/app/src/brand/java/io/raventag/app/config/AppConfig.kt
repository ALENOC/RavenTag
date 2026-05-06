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
     * Researched 2026-04 from:
     *   - github.com/Electrum-RVN-SIG/electrum-ravencoin servers.json (3 hosts)
     *   - rvn4lyfe.com operator-hosted (confirms 4th host 51.222.139.25)
     *
     * Note: "rvn-dashboard.com" may rotate off SSL in the future; quarantine
     * handles silently (D-11, 1h quarantine on TOFU mismatch). If a future
     * community list expands coverage, add hosts here (no user-configurable
     * list in v1, deferred to a later "power user" phase).
     *
     * Current count: 4 (marginal per RESEARCH Pitfall 8; a single cert
     * rotation leaves 3 operational which is acceptable for D-09).
     */
    val ELECTRUM_SERVERS: List<Pair<String, Int>> = listOf(
        // Verified live (probed via server.version + headers.subscribe). The bare
        // IPs that used to live here pointed to the same hosts as the rvn4lyfe.com /
        // rvn-dashboard.com domains and were dropped after the cert rotation. Cipig
        // (KomodoPlatform) operates the three "rvn.electrumN.cipig.net" mirrors
        // and they are listed in coins_config.json.
        "rvn4lyfe.com" to 50002,
        "rvn-dashboard.com" to 50002,
        "rvn.electrum1.cipig.net" to 20051,
        "rvn.electrum2.cipig.net" to 20051,
        "rvn.electrum3.cipig.net" to 20051,
    )

    /**
     * Block explorer URL prefix for Ravencoin transactions (D-19).
     * Appending a txid yields a browsable transaction page, e.g. `${EXPLORER_URL}<txid>`.
     * Verified 2026-05 against Ravencoin mainnet (Cryptoscope / Solus Explorer).
     */
    const val EXPLORER_URL: String = "https://rvn.cryptoscope.io/tx/?txid="
}
