package io.raventag.app.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.raventag.app.BuildConfig
import io.raventag.app.R
import io.raventag.app.ui.theme.*
import androidx.compose.runtime.CompositionLocalProvider
import java.util.Locale

/**
 * Data class representing a selectable language in the onboarding and settings language pickers.
 *
 * @param code BCP-47 language code (e.g. "en", "it", "fr") used for loading the correct strings.
 * @param label Display name in the language's own script (e.g. "Italiano", "中文").
 * @param flag Emoji flag for the country most associated with the language.
 */
data class AppLanguage(val code: String, val label: String, val flag: String)

/**
 * Master list of all languages supported by the app.
 * Rendered in a 3-column grid inside the language picker card.
 * Adding a new language requires adding an entry here AND providing a strings object in AppStrings.kt.
 */
val LANGUAGES = listOf(
    AppLanguage("en", "English", "🇬🇧"),
    AppLanguage("it", "Italiano", "🇮🇹"),
    AppLanguage("fr", "Français", "🇫🇷"),
    AppLanguage("de", "Deutsch", "🇩🇪"),
    AppLanguage("es", "Español", "🇪🇸"),
    AppLanguage("zh", "中文", "🇨🇳"),
    AppLanguage("ja", "日本語", "🇯🇵"),
    AppLanguage("ko", "한국어", "🇰🇷"),
    AppLanguage("ru", "Русский", "🇷🇺"),
)

/**
 * Onboarding screen shown once on first launch (before [SharedPreferences] key "onboarding_done"
 * is set). Introduces the RavenTag protocol and lets the user select their preferred language.
 *
 * The screen is split into a stateful wrapper ([OnboardingScreen]) and a stateless content
 * composable ([OnboardingContent]) so that the language switch can be previewed live: changing
 * the language re-creates the strings object and provides it via [CompositionLocalProvider],
 * which causes the entire content tree to re-compose in the new language without restarting.
 *
 * @param onComplete Callback invoked when the user taps "Get Started". Receives the selected
 *   BCP-47 language code. The caller (MainActivity) persists the choice and marks onboarding done.
 */
@Composable
fun OnboardingScreen(onComplete: (languageCode: String) -> Unit) {
    // Default to the device system language if supported, otherwise fall back to English.
    val supportedCodes = remember { LANGUAGES.map { it.code }.toSet() }
    val systemLang = remember { Locale.getDefault().language }
    var selectedLang by remember { mutableStateOf(if (systemLang in supportedCodes) systemLang else "en") }

    // Re-derive the strings object whenever the selected language changes.
    // This live-swaps all text without needing an Activity restart.
    val currentStrings = remember(selectedLang) { appStringsFor(selectedLang) }

    // Provide the newly derived strings to the entire subtree so all Text composables pick them up.
    CompositionLocalProvider(LocalStrings provides currentStrings) {
        OnboardingContent(selectedLang = selectedLang, onLangSelect = { selectedLang = it }, onComplete = onComplete)
    }
}

/**
 * Stateless content composable for the onboarding screen.
 *
 * Separated from [OnboardingScreen] so it can be recomposed independently when the language
 * changes, while [OnboardingScreen] retains the [selectedLang] state across recompositions.
 *
 * Layout (top to bottom, scrollable):
 *   1. RavenTag logo
 *   2. "Protocol RTP-1" badge
 *   3. Headline and description text
 *   4. Feature row cards (NTAG, sovereignty, Ravencoin, revocation, NFC writing)
 *   5. Language picker card (3-column grid)
 *   6. "Get Started" button
 *
 * @param selectedLang Currently highlighted language code.
 * @param onLangSelect Callback when the user taps a language chip.
 * @param onComplete Callback when the user confirms their choice and taps "Get Started".
 */
@Composable
private fun OnboardingContent(
    selectedLang: String,
    onLangSelect: (String) -> Unit,
    onComplete: (String) -> Unit
) {
    val s = LocalStrings.current
    var termsAccepted by remember { mutableStateOf(false) }
    var privacyAccepted by remember { mutableStateOf(false) }
    val uriHandler = LocalUriHandler.current
    val legalSuffix = if (selectedLang == "en") "" else "_${selectedLang.uppercase()}"
    val termsUrl = "https://github.com/ALENOC/RavenTag/blob/master/docs/legal/TERMS_OF_SERVICE${legalSuffix}.md"
    val privacyUrl = "https://github.com/ALENOC/RavenTag/blob/master/docs/legal/PRIVACY_POLICY${legalSuffix}.md"

    // Feature rows are built from localized strings so they update live with the language picker.
    val features = buildList {
        if (BuildConfig.IS_BRAND) {
            add(Triple(Icons.Default.Nfc, s.featureNtag, s.featureNtagDesc))
            add(Triple(Icons.Default.Shield, s.featureSov, s.featureSovDesc))
        } else {
            add(Triple(Icons.Default.Nfc, s.featureNtagConsumer, s.featureNtagDescConsumer))
            add(Triple(Icons.Default.Shield, s.featureSovConsumer, s.featureSovDescConsumer))
        }
        add(Triple(Icons.Default.Link, s.featureRvn, s.featureRvnDesc))
        add(Triple(Icons.Default.Block, s.featureRevoke, s.featureRevokeDesc))
        if (BuildConfig.IS_BRAND) {
            add(Triple(Icons.Default.Edit, s.featureWrite, s.featureWriteDesc))
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(RavenBg)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(modifier = Modifier.height(48.dp))

        // App logo from drawable resource (vector asset).
        Icon(
            painter = painterResource(id = R.drawable.raven_logo),
            contentDescription = "RavenTag",
            tint = Color.Unspecified,   // Preserve the original drawable colors
            modifier = Modifier
                .height(100.dp)
                .fillMaxWidth(0.7f)
        )

        Spacer(modifier = Modifier.height(12.dp))

        // Protocol version badge: small pill-shaped surface beneath the logo.
        Surface(
            color = RavenOrange.copy(alpha = 0.12f),
            shape = RoundedCornerShape(20.dp),
            border = BorderStroke(1.dp, RavenOrange.copy(alpha = 0.3f))
        ) {
            Text(
                if (BuildConfig.IS_BRAND) s.onboardingBadge else s.onboardingBadgeConsumer,
                style = MaterialTheme.typography.labelSmall,
                color = RavenOrange,
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
                letterSpacing = 0.5.sp
            )
        }

        Spacer(modifier = Modifier.height(20.dp))

        // Main headline
        Text(
            text = if (BuildConfig.IS_BRAND) s.onboardingTitle else s.onboardingTitleConsumer,
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            color = Color.White,
            textAlign = TextAlign.Center,
            lineHeight = 32.sp
        )

        Spacer(modifier = Modifier.height(8.dp))

        // Supporting description paragraph
        Text(
            text = if (BuildConfig.IS_BRAND) s.onboardingDesc else s.onboardingDescConsumer,
            style = MaterialTheme.typography.bodyMedium,
            color = RavenMuted,
            textAlign = TextAlign.Center,
            lineHeight = 22.sp
        )

        Spacer(modifier = Modifier.height(32.dp))

        // Feature rows: one card per key protocol feature.
        // Built dynamically from the features list so new entries only require list changes above.
        Column(verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
            features.forEach { (icon, title, desc) ->
                FeatureRow(icon = icon, title = title, desc = desc)
            }
        }

        Spacer(modifier = Modifier.height(36.dp))

        // Language selector card.
        // Languages are chunked into rows of 3. The last row may have fewer than 3 items;
        // invisible Spacer fillers maintain the column widths in that case.
        Card(
            colors = CardDefaults.cardColors(containerColor = RavenCard),
            border = BorderStroke(1.dp, RavenBorder),
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    s.onboardingLangTitle,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = Color.White,
                    modifier = Modifier.padding(bottom = 12.dp)
                )
                LANGUAGES.chunked(3).forEach { row ->
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        row.forEach { lang ->
                            val selected = selectedLang == lang.code
                            // Orange-tinted chip when selected; transparent when not.
                            Surface(
                                onClick = { onLangSelect(lang.code) },
                                shape = RoundedCornerShape(10.dp),
                                color = if (selected) RavenOrange.copy(alpha = 0.15f) else RavenBg,
                                border = BorderStroke(
                                    1.dp,
                                    if (selected) RavenOrange else RavenBorder
                                ),
                                modifier = Modifier.weight(1f)
                            ) {
                                Column(
                                    modifier = Modifier.padding(10.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally
                                ) {
                                    Text(lang.flag, fontSize = 22.sp)
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Text(
                                        lang.label,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = if (selected) RavenOrange else RavenMuted,
                                        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                                        textAlign = TextAlign.Center
                                    )
                                }
                            }
                        }
                        // Fill empty cells in the last row so column widths stay consistent.
                        repeat(3 - row.size) {
                            Spacer(modifier = Modifier.weight(1f))
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        // Legal acceptance card: Terms of Service and Privacy Policy checkboxes.
        // The "Get Started" button is disabled until both are checked.
        Card(
            colors = CardDefaults.cardColors(containerColor = RavenCard),
            border = BorderStroke(1.dp, RavenBorder),
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    s.onboardingLegalTitle,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = Color.White,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
                LegalCheckRow(
                    checked = termsAccepted,
                    onCheckedChange = { termsAccepted = it },
                    prefix = s.onboardingLegalPrefix,
                    linkText = s.onboardingLegalTerms,
                    onLinkClick = { uriHandler.openUri(termsUrl) }
                )
                LegalCheckRow(
                    checked = privacyAccepted,
                    onCheckedChange = { privacyAccepted = it },
                    prefix = s.onboardingLegalPrefix,
                    linkText = s.onboardingLegalPrivacy,
                    onLinkClick = { uriHandler.openUri(privacyUrl) }
                )
                if (BuildConfig.IS_BRAND) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        s.onboardingLegalRisk,
                        style = MaterialTheme.typography.bodySmall,
                        color = RavenMuted,
                        lineHeight = 17.sp
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // "Get Started" button: completes onboarding and passes the selected language code
        // to the caller, which persists it and navigates to the main app.
        Button(
            onClick = { onComplete(selectedLang) },
            enabled = termsAccepted && privacyAccepted,
            modifier = Modifier.fillMaxWidth().height(54.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = RavenOrange,
                disabledContainerColor = RavenOrange.copy(alpha = 0.3f)
            ),
            shape = RoundedCornerShape(16.dp)
        ) {
            Icon(Icons.Default.ArrowForward, contentDescription = null, modifier = Modifier.size(20.dp))
            Spacer(modifier = Modifier.width(10.dp))
            Text(s.onboardingGetStarted, fontWeight = FontWeight.Bold, fontSize = 16.sp)
        }

        Spacer(modifier = Modifier.height(32.dp))
    }
}

/**
 * A row with a checkbox and a text containing a tappable link.
 * Used in the legal acceptance section of the onboarding screen.
 */
@Composable
private fun LegalCheckRow(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    prefix: String,
    linkText: String,
    onLinkClick: () -> Unit
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onCheckedChange(!checked) }
    ) {
        Checkbox(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = CheckboxDefaults.colors(
                checkedColor = RavenOrange,
                uncheckedColor = RavenMuted,
                checkmarkColor = Color.White
            )
        )
        Column(modifier = Modifier.weight(1f).padding(start = 2.dp)) {
            Text(
                text = prefix,
                style = MaterialTheme.typography.bodySmall,
                color = RavenMuted,
                lineHeight = 16.sp
            )
            Text(
                text = linkText,
                style = MaterialTheme.typography.bodySmall,
                color = RavenOrange,
                fontWeight = FontWeight.SemiBold,
                textDecoration = TextDecoration.Underline,
                modifier = Modifier.clickable { onLinkClick() }
            )
        }
    }
}

/**
 * Single feature highlight card used in the onboarding feature list.
 *
 * Layout: 36x36 dp icon box on the left, title + description column on the right.
 * The icon box uses a subtle orange-tinted background to draw the eye without overpowering the text.
 *
 * @param icon Material icon vector representing the feature.
 * @param title Short bold headline for the feature (e.g. "NTAG 424 DNA").
 * @param desc One or two sentence description of the feature benefit.
 */
@Composable
private fun FeatureRow(icon: ImageVector, title: String, desc: String) {
    Card(
        colors = CardDefaults.cardColors(containerColor = RavenCard),
        border = BorderStroke(1.dp, RavenBorder),
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalAlignment = Alignment.Top
        ) {
            // Icon container: fixed 36dp square with rounded corners and orange tint.
            Surface(
                shape = RoundedCornerShape(8.dp),
                color = RavenOrange.copy(alpha = 0.12f),
                modifier = Modifier.size(36.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(icon, contentDescription = null, tint = RavenOrange, modifier = Modifier.size(18.dp))
                }
            }
            Column {
                Text(title, fontWeight = FontWeight.SemiBold, color = Color.White, style = MaterialTheme.typography.bodyMedium)
                Text(desc, style = MaterialTheme.typography.bodySmall, color = RavenMuted, modifier = Modifier.padding(top = 3.dp), lineHeight = 18.sp)
            }
        }
    }
}
