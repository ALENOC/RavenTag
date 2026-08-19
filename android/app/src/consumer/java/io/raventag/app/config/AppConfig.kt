package io.raventag.app.config

/**
 * Consumer (end-user) app configuration.
 * This flavor shows only NFC scanning and the Ravencoin wallet.
 * No brand management, no admin features.
 *
 * HOW TO CUSTOMIZE FOR YOUR BRAND:
 * 1. Set BRAND_NAME to your brand name (shown in the app)
 * 2. Set DEFAULT_VERIFY_URL to your backend server URL
 * 3. Set PRIMARY_COLOR_HEX to your brand primary color (ARGB hex)
 * 4. Replace consumer/res/drawable/raven_logo.png with your logo
 * 5. Replace consumer mipmap icons with your app icon
 * 6. Run: ./gradlew assembleConsumerRelease
 *
 */
object AppConfig {
    /** Consumer verification app , no Brand tab, no admin features */
    const val IS_BRAND_APP = false

    /** Your brand name , shown in app header and onboarding */
    const val BRAND_NAME = "RavenTag"

    /**
     * Your backend verification server URL.
     * Set to your own server: https://verify.yourbrand.com
     */
    const val DEFAULT_VERIFY_URL = "https://api.raventag.com"

    /** Brand primary accent color (ARGB hex). Change to your brand color. */
    const val PRIMARY_COLOR_HEX = "#EF7536"

    /** Drawable resource name for in-app logo */
    const val LOGO_DRAWABLE = "raven_logo"

    /** Consumer app does not show brand/admin configuration in Settings */
    const val SHOW_BRAND_SETTINGS = false

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
     * If the explorer rotates in the future, update here: no runtime override is
     * exposed in v1 (deferred to a later "power user" phase).
     */
    const val EXPLORER_URL: String = "https://rvn.cryptoscope.io/tx/?txid="
}
