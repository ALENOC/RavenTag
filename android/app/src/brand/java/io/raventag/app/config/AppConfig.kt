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
}
