package io.raventag.app.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// ============================================================
// Brand palette
// ============================================================

/** Primary Ravencoin orange. Used for interactive elements, active states, and accent highlights. */
val RavenOrange = Color(0xFFEF7536)

/** Slightly darker orange for pressed/ripple states (not used directly in Compose but available). */
val RavenOrangeDark = Color(0xFFC9622D)

/** Lighter orange shade for text-on-dark-surface scenarios and gradient endpoints. */
val RavenOrangeLight = Color(0xFFF2895A)

// ============================================================
// Background and surface colors
// ============================================================

/**
 * Primary background color. Pure black (0xFF000000) to perfectly match the raven logo background
 * and give the app a premium, OLED-friendly dark theme.
 */
val RavenBg = Color(0xFF000000)

/**
 * Card / surface background. Near-black (0xFF0F0F0F) provides a subtle depth separation
 * from the main background without introducing visible contrast on OLED displays.
 */
val RavenCard = Color(0xFF0F0F0F)

/**
 * Default border / divider color. Dark grey (0xFF2A2A2A) for card outlines, input field borders,
 * and horizontal dividers.
 */
val RavenBorder = Color(0xFF2A2A2A)

/**
 * Muted text / secondary icon color. Medium grey (0xFF6B7280) used for hints, labels, placeholders,
 * and less prominent UI text.
 */
val RavenMuted = Color(0xFF6B7280)

// ============================================================
// Status / semantic colors
// ============================================================

/**
 * Authentic / success green. Shown on the verify screen when a tag passes all checks,
 * on success banners, and on the "Saved" state of settings buttons.
 */
val AuthenticGreen = Color(0xFF4ADE80)

/** Dark green background used behind [AuthenticGreen] text in success banner cards. */
val AuthenticGreenBg = Color(0xFF052E16)

/**
 * Not-authentic / error red. Shown on the verify screen for failed tags, on error banners,
 * and as the color of destructive action buttons (e.g. Send RVN, screenshot toggle).
 */
val NotAuthenticRed = Color(0xFFF87171)

/** Dark red background used behind [NotAuthenticRed] text in error / revoked banner cards. */
val NotAuthenticRedBg = Color(0xFF2D0A0A)

// ============================================================
// Material 3 color scheme
// ============================================================

/**
 * Custom dark [darkColorScheme] built from the RavenTag palette.
 *
 * Maps Material 3 semantic color roles to the brand colors:
 *   - primary / onPrimary: orange CTA buttons and white text on them.
 *   - primaryContainer: deep orange tint for selected chip backgrounds.
 *   - secondary / onSecondary: muted grey for secondary text and icons.
 *   - background / onBackground: pure black with near-white text.
 *   - surface / onSurface: card background with near-white text.
 *   - surfaceVariant / onSurfaceVariant: border color with muted text.
 *   - outline: border color for outlined components.
 *   - error / onError: red for error states with white text.
 */
private val DarkColorScheme = darkColorScheme(
    primary = RavenOrange,
    onPrimary = Color.White,
    primaryContainer = Color(0xFF3A1C08),
    onPrimaryContainer = RavenOrangeLight,
    secondary = RavenMuted,
    onSecondary = Color.White,
    background = RavenBg,
    onBackground = Color(0xFFF1F5F9),
    surface = RavenCard,
    onSurface = Color(0xFFF1F5F9),
    surfaceVariant = RavenBorder,
    onSurfaceVariant = RavenMuted,
    outline = RavenBorder,
    error = NotAuthenticRed,
    onError = Color.White
)

/**
 * Top-level theme composable for the RavenTag app.
 *
 * Wraps [MaterialTheme] with the custom [DarkColorScheme]. All screens and components inside
 * this theme automatically inherit the brand colors for Material 3 tokens such as
 * [MaterialTheme.colorScheme.primary], [MaterialTheme.colorScheme.surface], etc.
 *
 * No custom typography or shapes are defined at this time; Material 3 defaults are used.
 *
 * Applied once at the root of the composition in [MainActivity.setContent]:
 *
 *     RavenTagTheme {
 *         Surface(modifier = Modifier.fillMaxSize(), color = RavenBg) { ... }
 *     }
 *
 * @param content The composable content to be displayed inside the theme.
 */
@Composable
fun RavenTagTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = DarkColorScheme,
        content = content
    )
}
