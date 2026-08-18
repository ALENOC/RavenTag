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
 * Copy used for the separate B2B/Brand specific approval control.
 *
 * The checkbox deliberately approves the identified Terms sections, not "Article 1341/1342"
 * themselves. The statutory reference is shown only as an explanatory note.
 */
private data class SpecificApprovalCopy(
    val prefix: String,
    val clauses: String,
    val note: String
)

private fun specificApprovalCopy(languageCode: String): SpecificApprovalCopy = when (languageCode) {
    "it" -> SpecificApprovalCopy(
        prefix = "Approvo specificamente le Sezioni 8, 9 e 12 dei Termini:",
        clauses = "disponibilità/interruzione, limitazione di responsabilità e legge/foro applicabile",
        note = "Approvazione specifica ai sensi degli artt. 1341 e 1342 c.c., ove applicabili."
    )
    "fr" -> SpecificApprovalCopy(
        prefix = "J'approuve spécifiquement les Sections 8, 9 et 12 des Conditions :",
        clauses = "disponibilité/interruption, limitation de responsabilité et droit/juridiction applicables",
        note = "Approbation spécifique au titre des articles 1341 et 1342 du Code civil italien, lorsqu'ils sont applicables."
    )
    "de" -> SpecificApprovalCopy(
        prefix = "Ich genehmige ausdrücklich die Abschnitte 8, 9 und 12 der Bedingungen:",
        clauses = "Verfügbarkeit/Einstellung, Haftungsbegrenzung und anwendbares Recht/Gerichtsstand",
        note = "Besondere Genehmigung nach Art. 1341 und 1342 des italienischen Zivilgesetzbuchs, soweit anwendbar."
    )
    "es" -> SpecificApprovalCopy(
        prefix = "Apruebo específicamente las Secciones 8, 9 y 12 de los Términos:",
        clauses = "disponibilidad/interrupción, limitación de responsabilidad y ley/jurisdicción aplicables",
        note = "Aprobación específica conforme a los arts. 1341 y 1342 del Código Civil italiano, cuando resulten aplicables."
    )
    "zh" -> SpecificApprovalCopy(
        prefix = "我明确同意条款第 8、9 和 12 节：",
        clauses = "可用性/停止服务、责任限制以及适用法律/管辖权",
        note = "在适用情况下，根据意大利《民法典》第 1341 和 1342 条进行特别确认。"
    )
    "ja" -> SpecificApprovalCopy(
        prefix = "利用規約の第8条、第9条および第12条を個別に承認します：",
        clauses = "利用可能性・停止、責任制限、準拠法・管轄",
        note = "適用される場合、イタリア民法第1341条および第1342条に基づく個別承認です。"
    )
    "ko" -> SpecificApprovalCopy(
        prefix = "이용약관 제8조, 제9조 및 제12조를 개별적으로 승인합니다:",
        clauses = "서비스 가용성/중단, 책임 제한 및 준거법/관할",
        note = "해당되는 경우 이탈리아 민법 제1341조 및 제1342조에 따른 특별 승인입니다."
    )
    "ru" -> SpecificApprovalCopy(
        prefix = "Я отдельно одобряю разделы 8, 9 и 12 Условий:",
        clauses = "доступность/прекращение сервиса, ограничение ответственности и применимое право/юрисдикция",
        note = "Специальное одобрение по ст. 1341 и 1342 Гражданского кодекса Италии, когда они применимы."
    )
    else -> SpecificApprovalCopy(
        prefix = "I specifically approve Sections 8, 9 and 12 of the Terms:",
        clauses = "availability/discontinuation, limitation of liability, and governing law/jurisdiction",
        note = "Specific approval under Articles 1341 and 1342 of the Italian Civil Code, where applicable."
    )
}

/**
 * The legal documents describe RavenTag as source-available under RTSL-1.0 rather than OSI
 * open-source. Keep the onboarding badge consistent without requiring duplicated AppStrings keys.
 */
private fun sourceAvailableBadge(languageCode: String, consumer: Boolean): String {
    if (consumer) {
        return when (languageCode) {
            "it" -> "Codice disponibile · Ravencoin"
            "fr" -> "Code source disponible · Ravencoin"
            "de" -> "Source-Available · Ravencoin"
            "es" -> "Código fuente disponible · Ravencoin"
            "zh" -> "源代码可用 · Ravencoin"
            "ja" -> "ソース公開 · Ravencoin"
            "ko" -> "소스 공개 · Ravencoin"
            "ru" -> "Исходный код доступен · Ravencoin"
            else -> "Source-Available · Ravencoin"
        }
    }
    return when (languageCode) {
        "it" -> "Protocollo RTP-1 · Codice disponibile"
        "fr" -> "Protocole RTP-1 · Code source disponible"
        "de" -> "Protokoll RTP-1 · Source-Available"
        "es" -> "Protocolo RTP-1 · Código fuente disponible"
        "zh" -> "RTP-1 协议 · 源代码可用"
        "ja" -> "RTP-1 プロトコル · ソース公開"
        "ko" -> "RTP-1 프로토콜 · 소스 공개"
        "ru" -> "Протокол RTP-1 · Исходный код доступен"
        else -> "Protocol RTP-1 · Source-Available"
    }
}

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
 *   6. Legal acceptance card
 *   7. "Get Started" button
 *
 * Consumer builds require acceptance of Terms and Privacy only. Brand builds additionally show a
 * separate, human-readable approval of the specific B2B clauses potentially relevant under
 * Articles 1341/1342 of the Italian Civil Code. The user is never asked to "accept Article 1341".
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
    var specificTermsAccepted by remember { mutableStateOf(false) }
    val specificApproval = remember(selectedLang) { specificApprovalCopy(selectedLang) }
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

        // Protocol/license badge: keep wording consistent with RTSL-1.0 legal documents.
        Surface(
            color = RavenOrange.copy(alpha = 0.12f),
            shape = RoundedCornerShape(20.dp),
            border = BorderStroke(1.dp, RavenOrange.copy(alpha = 0.3f))
        ) {
            Text(
                sourceAvailableBadge(selectedLang, consumer = !BuildConfig.IS_BRAND),
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
        Column(verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
            features.forEach { (icon, title, desc) ->
                FeatureRow(icon = icon, title = title, desc = desc)
            }
        }

        Spacer(modifier = Modifier.height(36.dp))

        // Language selector card.
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
                        repeat(3 - row.size) {
                            Spacer(modifier = Modifier.weight(1f))
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        // Legal acceptance card.
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
                    HorizontalDivider(
                        modifier = Modifier.padding(vertical = 8.dp),
                        color = RavenBorder
                    )
                    LegalCheckRow(
                        checked = specificTermsAccepted,
                        onCheckedChange = { specificTermsAccepted = it },
                        prefix = specificApproval.prefix,
                        linkText = specificApproval.clauses,
                        onLinkClick = { uriHandler.openUri(termsUrl) }
                    )
                    Text(
                        specificApproval.note,
                        style = MaterialTheme.typography.labelSmall,
                        color = RavenMuted,
                        lineHeight = 15.sp,
                        modifier = Modifier.padding(start = 50.dp, top = 2.dp)
                    )
                    Spacer(modifier = Modifier.height(6.dp))
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

        // Consumer builds need Terms + Privacy. Brand/B2B builds additionally require the
        // separate approval of the identified clauses; the user is not asked to accept a statute.
        val legalAcceptanceComplete = termsAccepted && privacyAccepted &&
            (!BuildConfig.IS_BRAND || specificTermsAccepted)

        Button(
            onClick = { onComplete(selectedLang) },
            enabled = legalAcceptanceComplete,
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
 * A row with a checkbox and text containing a tappable link.
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
