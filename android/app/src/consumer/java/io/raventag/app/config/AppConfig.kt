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
}
