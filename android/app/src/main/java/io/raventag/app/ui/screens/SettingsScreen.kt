package io.raventag.app.ui.screens

import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.raventag.app.BuildConfig
import io.raventag.app.MainViewModel
import io.raventag.app.ui.theme.*

/**
 * Settings screen for the RavenTag app.
 *
 * Rendered inside the bottom-nav Scaffold (Settings tab). Scrollable, so all sections are always
 * accessible regardless of screen height.
 *
 * Sections displayed:
 *   - Brand name (brand flavor only)
 *   - Verification server URL with live connectivity status
 *   - Pinata JWT (brand flavor only, for IPFS image uploads via Pinata)
 *   - Kubo node URL (brand flavor only, for self-hosted IPFS uploads)
 *   - Language selector (all flavors)
 *   - Security section (biometric auth toggle + screenshot lock toggle, wallet only)
 *   - About section with version, links and donate button
 *
 * Each editable field maintains two pieces of local state: the current text input and a boolean
 * flag that tracks whether the current value has been saved. The flag resets to false when the
 * user modifies the field, and turns true after tapping the save button, which gives the button
 * a green "Saved" visual feedback.
 *
 * @param currentLang BCP-47 language code currently active ("en", "it", etc.).
 * @param currentVerifyUrl The backend URL stored in prefs, pre-fills the URL field.
 * @param currentInitialMasterKey Hex-encoded 16-byte NTAG 424 initial master key (optional).
 * @param currentPinataJwt Pinata.cloud JWT for direct image uploads.
 * @param currentKuboNodeUrl URL of a local or remote IPFS Kubo node (e.g. http://10.0.2.2:5001).
 * @param serverStatus Live connectivity status of the configured backend server.
 * @param pinataJwtStatus Whether the saved Pinata JWT successfully authenticates.
 * @param kuboNodeStatus Whether the saved Kubo node URL is reachable.
 * @param requireAuthOnStart When true, biometric/PIN lock is requested on each app launch.
 * @param hasLockScreen Whether the device has any enrolled biometric/PIN credential.
 * @param allowScreenshots When true, FLAG_SECURE is cleared so screenshots are permitted.
 */
@Composable
fun SettingsScreen(
    currentLang: String,
    currentVerifyUrl: String,
    currentInitialMasterKey: String = "",
    currentPinataJwt: String = "",
    currentKuboNodeUrl: String = "",
    onPinataJwtSave: (String) -> Unit = {},
    onKuboNodeUrlSave: (String) -> Unit = {},
    pinataJwtStatus: MainViewModel.AdminKeyStatus = MainViewModel.AdminKeyStatus.UNKNOWN,
    kuboNodeStatus: MainViewModel.AdminKeyStatus = MainViewModel.AdminKeyStatus.UNKNOWN,
    serverStatus: MainViewModel.ServerStatus,
    onLangChange: (String) -> Unit,
    onVerifyUrlSave: (String) -> Unit,
    onInitialMasterKeySave: (String) -> Unit = {},
    onDonate: () -> Unit,
    walletBalance: Double,
    hasWallet: Boolean,
    requireAuthOnStart: Boolean = true,
    onRequireAuthChange: (Boolean) -> Unit = {},
    hasLockScreen: Boolean = true,
    allowScreenshots: Boolean = false,
    onAllowScreenshotsChange: (Boolean) -> Unit = {},
    notificationsEnabled: Boolean = true,
    onNotificationsEnabledChange: (Boolean) -> Unit = {},
    modifier: Modifier = Modifier
) {
    val s = LocalStrings.current

    // Whether brand-specific settings (admin key, operator key, IPFS, etc.) are visible.
    // Controlled by AppConfig.SHOW_BRAND_SETTINGS (true in brand flavor, false in consumer).
    val showBrandSettings = io.raventag.app.config.AppConfig.SHOW_BRAND_SETTINGS

    // Controls visibility of the screenshot-enable confirmation dialog.
    var showScreenshotWarning by remember { mutableStateOf(false) }

    // Local mutable copies of each setting. Uses the current* parameter as the initial key so
    // that the field resets correctly if the parent recomposes with a new value (e.g. after save).
    var selectedLang by remember(currentLang) { mutableStateOf(currentLang) }

    var verifyUrlInput by remember(currentVerifyUrl) { mutableStateOf(currentVerifyUrl) }
    var verifyUrlSaved by remember { mutableStateOf(false) }

    var initialMasterKeyInput by remember(currentInitialMasterKey) { mutableStateOf(currentInitialMasterKey) }
    var initialMasterKeySaved by remember { mutableStateOf(false) }

    var pinataJwtInput by remember(currentPinataJwt) { mutableStateOf(currentPinataJwt) }
    var pinataJwtSaved by remember { mutableStateOf(false) }

    var kuboNodeUrlInput by remember(currentKuboNodeUrl) { mutableStateOf(currentKuboNodeUrl) }
    var kuboNodeUrlSaved by remember { mutableStateOf(false) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(RavenBg)
            .padding(horizontal = 20.dp)
            .verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(modifier = Modifier.height(24.dp))

        Text(
            s.settingsTitle,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            color = Color.White,
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(modifier = Modifier.height(28.dp))

        // Verification server URL: available in both flavors. The section label shows a live
        // status chip (Online/Offline/Checking) via SectionLabelWithStatus.
        SectionLabelWithStatus(
            label = if (BuildConfig.IS_BRAND) s.settingsVerifyUrl else s.settingsVerifyUrlConsumer,
            status = serverStatus,
            onlineLabel = s.settingsServerOnline,
            offlineLabel = s.settingsServerOffline,
            checkingLabel = s.settingsServerChecking
        )
        Spacer(modifier = Modifier.height(10.dp))
        SettingsCard {
            SettingsTextField(
                if (BuildConfig.IS_BRAND) s.settingsVerifyUrl else s.settingsVerifyUrlConsumer,
                if (BuildConfig.IS_BRAND) s.settingsVerifyUrlHint else s.settingsVerifyUrlHintConsumer,
                verifyUrlInput, { verifyUrlInput = it; verifyUrlSaved = false },
                "https://api.raventag.com"
            )
            SettingsSaveButton(verifyUrlSaved, s) {
                // Strip trailing slash to avoid double-slash in constructed API paths.
                onVerifyUrlSave(verifyUrlInput.trim().trimEnd('/'))
                verifyUrlSaved = true
            }
        }
        Spacer(modifier = Modifier.height(24.dp))

        if (showBrandSettings) {
            // Pinata JWT: enables direct IPFS uploads from the app without routing through the
            // backend. Validated independently of server connectivity (serverOnline = true).
            SectionLabelWithAdminStatus(
                label = s.pinataJwtLabel,
                status = pinataJwtStatus,
                serverOnline = true,
                s = s,
                validLabel = s.pinataJwtValid,
                invalidLabel = s.pinataJwtInvalid,
                checkingLabel = s.pinataJwtChecking
            )
            Spacer(modifier = Modifier.height(10.dp))
            SettingsCard {
                SettingsTextField(
                    s.pinataJwtLabel, s.pinataJwtHint,
                    pinataJwtInput,
                    { pinataJwtInput = it; pinataJwtSaved = false },
                    "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9...",
                    password = true
                )
                SettingsSaveButton(pinataJwtSaved, s) {
                    onPinataJwtSave(pinataJwtInput.trim())
                    pinataJwtSaved = true
                }
            }
            Spacer(modifier = Modifier.height(24.dp))

            // Kubo node URL: alternative IPFS upload path via a self-hosted node.
            // Also validated independently of the backend server (serverOnline = true).
            SectionLabelWithAdminStatus(
                label = s.kuboNodeLabel,
                status = kuboNodeStatus,
                serverOnline = true,
                s = s,
                validLabel = s.kuboNodeValid,
                invalidLabel = s.kuboNodeInvalid,
                checkingLabel = s.kuboNodeChecking
            )
            Spacer(modifier = Modifier.height(10.dp))
            SettingsCard {
                SettingsTextField(
                    s.kuboNodeLabel, s.kuboNodeHint,
                    kuboNodeUrlInput,
                    { kuboNodeUrlInput = it; kuboNodeUrlSaved = false },
                    "http://10.0.2.2:5001"
                )
                SettingsSaveButton(kuboNodeUrlSaved, s) {
                    onKuboNodeUrlSave(kuboNodeUrlInput.trim())
                    kuboNodeUrlSaved = true
                }
            }
            Spacer(modifier = Modifier.height(24.dp))
        }

        // Language picker: renders languages in a 3-column grid of tappable chips.
        // Immediately calls onLangChange so the UI hot-swaps strings without needing a restart.
        SectionLabel(s.onboardingLangTitle)
        Spacer(modifier = Modifier.height(10.dp))
        Card(
            colors = CardDefaults.cardColors(containerColor = RavenCard),
            border = BorderStroke(1.dp, RavenBorder),
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                // Split the LANGUAGES list into rows of 3 for even grid layout.
                LANGUAGES.chunked(3).forEach { row ->
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        row.forEach { lang ->
                            val selected = selectedLang == lang.code
                            // Each chip highlights with orange tint when selected.
                            Surface(
                                onClick = { selectedLang = lang.code; onLangChange(lang.code) },
                                shape = RoundedCornerShape(10.dp),
                                color = if (selected) RavenOrange.copy(alpha = 0.15f) else RavenBg,
                                border = BorderStroke(1.dp, if (selected) RavenOrange else RavenBorder),
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
                        // If the last row has fewer than 3 items, fill remaining cells with
                        // invisible spacers to maintain consistent column widths.
                        repeat(3 - row.size) { Spacer(modifier = Modifier.weight(1f)) }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        // Security section: only displayed when a wallet exists (no sensitive options to protect
        // otherwise). Contains biometric auth toggle and screenshot lock toggle.
        if (hasWallet) {
            SectionLabel("Security")
            Spacer(modifier = Modifier.height(10.dp))
            SettingsCard {
                // Biometric / PIN lock on every app launch.
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
                        Text(s.settingsRequireAuth, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, color = Color.White)
                        Text(s.settingsRequireAuthDesc, style = MaterialTheme.typography.bodySmall, color = RavenMuted, modifier = Modifier.padding(top = 2.dp))
                    }
                    Switch(
                        checked = requireAuthOnStart,
                        onCheckedChange = onRequireAuthChange,
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = Color.White,
                            checkedTrackColor = RavenOrange,
                            uncheckedThumbColor = RavenMuted,
                            uncheckedTrackColor = RavenBorder
                        )
                    )
                }

                // Red warning banner shown when auth is disabled, highlighting the security risk.
                if (!requireAuthOnStart) {
                    Spacer(modifier = Modifier.height(10.dp))
                    Card(
                        colors = CardDefaults.cardColors(containerColor = io.raventag.app.ui.theme.NotAuthenticRedBg),
                        border = BorderStroke(1.dp, io.raventag.app.ui.theme.NotAuthenticRed.copy(alpha = 0.4f)),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(modifier = Modifier.padding(10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Warning, contentDescription = null, tint = io.raventag.app.ui.theme.NotAuthenticRed, modifier = Modifier.size(16.dp))
                            Text(s.settingsRequireAuthRisk, style = MaterialTheme.typography.bodySmall, color = io.raventag.app.ui.theme.NotAuthenticRed)
                        }
                    }
                }

                // Orange warning banner shown when auth is enabled but no lock screen is enrolled.
                // The auth would be silently skipped in that case, so the user is notified.
                if (requireAuthOnStart && !hasLockScreen) {
                    Spacer(modifier = Modifier.height(10.dp))
                    Card(
                        colors = CardDefaults.cardColors(containerColor = RavenOrange.copy(alpha = 0.08f)),
                        border = BorderStroke(1.dp, RavenOrange.copy(alpha = 0.4f)),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(modifier = Modifier.padding(10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Warning, contentDescription = null, tint = RavenOrange, modifier = Modifier.size(16.dp))
                            Text(s.settingsNoLockScreen, style = MaterialTheme.typography.bodySmall, color = RavenOrange)
                        }
                    }
                }

                HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp), color = RavenBorder)

                // Screenshot lock toggle. Enabling requires confirmation via a dialog because
                // screenshots could expose the mnemonic or AES keys visible on screen.
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
                        Text(s.settingsAllowScreenshots, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, color = Color.White)
                        Text(s.settingsAllowScreenshotsDesc, style = MaterialTheme.typography.bodySmall, color = RavenMuted, modifier = Modifier.padding(top = 2.dp))
                    }
                    Switch(
                        checked = allowScreenshots,
                        onCheckedChange = { enabled ->
                            // Turning screenshots ON requires confirmation; turning them OFF is immediate.
                            if (enabled) showScreenshotWarning = true else onAllowScreenshotsChange(false)
                        },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = Color.White,
                            checkedTrackColor = NotAuthenticRed,   // Red track signals danger state
                            uncheckedThumbColor = RavenMuted,
                            uncheckedTrackColor = RavenBorder
                        )
                    )
                }

                // Red warning banner displayed while screenshots are allowed.
                if (allowScreenshots) {
                    Spacer(modifier = Modifier.height(10.dp))
                    Card(
                        colors = CardDefaults.cardColors(containerColor = NotAuthenticRedBg),
                        border = BorderStroke(1.dp, NotAuthenticRed.copy(alpha = 0.4f)),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(modifier = Modifier.padding(10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Warning, contentDescription = null, tint = NotAuthenticRed, modifier = Modifier.size(16.dp))
                            Text(s.settingsAllowScreenshotsWarning, style = MaterialTheme.typography.bodySmall, color = NotAuthenticRed)
                        }
                    }
                }

                HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp), color = RavenBorder)

                // Notifications toggle: enable/disable incoming transfer push notifications.
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
                        Text(s.settingsNotifications, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, color = Color.White)
                        Text(s.settingsNotificationsDesc, style = MaterialTheme.typography.bodySmall, color = RavenMuted, modifier = Modifier.padding(top = 2.dp))
                    }
                    Switch(
                        checked = notificationsEnabled,
                        onCheckedChange = onNotificationsEnabledChange,
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = Color.White,
                            checkedTrackColor = RavenOrange,
                            uncheckedThumbColor = RavenMuted,
                            uncheckedTrackColor = RavenBorder
                        )
                    )
                }
            }
            Spacer(modifier = Modifier.height(24.dp))
        }

        // Confirmation dialog: shown before enabling screenshots to give the user a last chance
        // to reconsider. Confirming calls onAllowScreenshotsChange(true) which clears FLAG_SECURE.
        if (showScreenshotWarning) {
            AlertDialog(
                onDismissRequest = { showScreenshotWarning = false },
                icon = { Icon(Icons.Default.Warning, contentDescription = null, tint = NotAuthenticRed, modifier = Modifier.size(28.dp)) },
                title = { Text(s.settingsAllowScreenshotsDialogTitle, color = Color.White, fontWeight = FontWeight.Bold) },
                text = { Text(s.settingsAllowScreenshotsDialogBody, color = RavenMuted, style = MaterialTheme.typography.bodySmall) },
                confirmButton = {
                    Button(
                        onClick = { showScreenshotWarning = false; onAllowScreenshotsChange(true) },
                        colors = ButtonDefaults.buttonColors(containerColor = NotAuthenticRed)
                    ) { Text(s.settingsAllowScreenshotsConfirm, style = MaterialTheme.typography.bodySmall) }
                },
                dismissButton = {
                    TextButton(onClick = { showScreenshotWarning = false }) {
                        Text(s.walletCancelBtn, color = RavenMuted)
                    }
                },
                containerColor = RavenCard,
                shape = RoundedCornerShape(16.dp)
            )
        }

        // About section: static info rows plus clickable links to website and GitHub.
        // The donate button navigates to SendRvnScreen pre-filled with the donation address.
        SectionLabel(s.settingsAbout)
        Spacer(modifier = Modifier.height(10.dp))
        Card(
            colors = CardDefaults.cardColors(containerColor = RavenCard),
            border = BorderStroke(1.dp, RavenBorder),
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                AboutRow(label = "RavenTag", value = "Protocol RTP-1")
                // Strip flavor suffixes so the displayed version is clean (e.g. "1.2.9").
                val appVersion = io.raventag.app.BuildConfig.VERSION_NAME.replace("-brand", "").replace("-consumer", "")
                AboutRow(label = s.settingsVersion, value = appVersion)
                AboutLinkRow(label = "Website", value = "raventag.com", url = "https://raventag.com")
                AboutLinkRow(label = "GitHub", value = "github.com/ALENOC/RavenTag", url = "https://github.com/ALENOC/RavenTag")
                AboutLinkRow(label = "License", value = "RTSL-1.0", url = "https://github.com/ALENOC/RavenTag/blob/master/LICENSE")
                val legalSuffix = if (currentLang == "en") "" else "_${currentLang.uppercase()}"
                AboutLinkRow(
                    label = s.onboardingLegalTerms,
                    value = "TERMS_OF_SERVICE${legalSuffix}",
                    url = "https://github.com/ALENOC/RavenTag/blob/master/docs/legal/TERMS_OF_SERVICE${legalSuffix}.md"
                )
                AboutLinkRow(
                    label = s.onboardingLegalPrivacy,
                    value = "PRIVACY_POLICY${legalSuffix}",
                    url = "https://github.com/ALENOC/RavenTag/blob/master/docs/legal/PRIVACY_POLICY${legalSuffix}.md"
                )
                HorizontalDivider(color = RavenBorder)
                // Donate button only enabled when the wallet exists and can broadcast a transaction.
                Button(
                    onClick = { if (hasWallet) onDonate() },
                    enabled = hasWallet,
                    modifier = Modifier.fillMaxWidth().height(44.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = RavenOrange.copy(alpha = 0.15f)
                    ),
                    shape = RoundedCornerShape(10.dp),
                    border = ButtonDefaults.outlinedButtonBorder.copy(width = 1.dp)
                ) {
                    Icon(
                        Icons.Default.Favorite, contentDescription = null,
                        tint = RavenOrange, modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        s.settingsDonateBtn, color = RavenOrange,
                        fontWeight = FontWeight.SemiBold,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(32.dp))
    }
}

// ============================================================
// Section label helpers
// ============================================================

/**
 * Section label that pairs a text header with a live server connectivity chip.
 *
 * The status chip changes appearance based on the [status] value:
 *   - UNKNOWN: no chip shown
 *   - CHECKING: small spinner
 *   - ONLINE: pulsing green dot
 *   - OFFLINE: static red dot
 */
@Composable
private fun SectionLabelWithStatus(
    label: String,
    status: MainViewModel.ServerStatus,
    onlineLabel: String,
    offlineLabel: String,
    checkingLabel: String
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            label.uppercase(),
            style = MaterialTheme.typography.labelSmall,
            color = RavenMuted,
            letterSpacing = androidx.compose.ui.unit.TextUnit(
                1.5f, androidx.compose.ui.unit.TextUnitType.Sp
            )
        )
        when (status) {
            MainViewModel.ServerStatus.UNKNOWN -> {}
            MainViewModel.ServerStatus.CHECKING -> StatusChip(
                label = checkingLabel,
                dotColor = null      // null dot triggers spinner display
            )
            MainViewModel.ServerStatus.ONLINE -> StatusChip(
                label = onlineLabel,
                dotColor = AuthenticGreen,
                pulse = true         // animated pulse to visually confirm live connection
            )
            MainViewModel.ServerStatus.OFFLINE -> StatusChip(
                label = offlineLabel,
                dotColor = NotAuthenticRed
            )
        }
    }
}

/**
 * Section label that pairs a text header with an API key validity chip.
 *
 * Accepts custom label overrides for valid/invalid/checking/wrong-type states so the same
 * component can be reused for the admin key, operator key, Pinata JWT, and Kubo node sections.
 *
 * When the server is offline, the label is dimmed to signal that the key cannot be validated.
 *
 * The WRONG_TYPE state is special: it means the user saved a key of the wrong role (e.g. an
 * operator key in the admin key field) and is displayed as an inline warning below the header.
 *
 * @param serverOnline Pass true to bypass the dimming for fields that are independent of the
 *   backend (e.g. Pinata JWT and Kubo node URL are validated directly, not via the backend).
 */
@Composable
private fun SectionLabelWithAdminStatus(
    label: String,
    status: MainViewModel.AdminKeyStatus,
    serverOnline: Boolean,
    s: AppStrings,
    validLabel: String = s.settingsAdminKeyValid,
    invalidLabel: String = s.settingsAdminKeyInvalid,
    checkingLabel: String = s.settingsAdminKeyChecking,
    wrongTypeLabel: String = s.settingsAdminKeyWrongType
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                label.uppercase(),
                style = MaterialTheme.typography.labelSmall,
                // Dim label when server is offline to indicate the key cannot be checked.
                color = if (serverOnline) RavenMuted else RavenMuted.copy(alpha = 0.5f),
                letterSpacing = androidx.compose.ui.unit.TextUnit(
                    1.5f, androidx.compose.ui.unit.TextUnitType.Sp
                )
            )
            when (status) {
                MainViewModel.AdminKeyStatus.UNKNOWN -> {}
                MainViewModel.AdminKeyStatus.CHECKING -> StatusChip(
                    label = checkingLabel,
                    dotColor = null
                )
                MainViewModel.AdminKeyStatus.VALID -> StatusChip(
                    label = validLabel,
                    dotColor = AuthenticGreen,
                    pulse = true
                )
                MainViewModel.AdminKeyStatus.INVALID -> StatusChip(
                    label = invalidLabel,
                    dotColor = NotAuthenticRed
                )
                // WRONG_TYPE hides the inline chip; a separate text row below is shown instead.
                MainViewModel.AdminKeyStatus.WRONG_TYPE -> {}
            }
        }
        // Wrong-type warning shown as a separate sub-label row below the header.
        if (status == MainViewModel.AdminKeyStatus.WRONG_TYPE) {
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                wrongTypeLabel,
                style = MaterialTheme.typography.labelSmall,
                color = RavenOrange,
                fontSize = 10.sp
            )
        }
    }
}

/**
 * Small inline status chip combining a colored dot (or spinner) with a label.
 *
 * When [dotColor] is null a [CircularProgressIndicator] is shown instead of the dot, indicating
 * an in-progress check.
 *
 * When [pulse] is true the dot animates between 85% and 115% scale on an infinite transition,
 * giving a heartbeat effect for the "Online / Valid" states.
 */
@Composable
private fun StatusChip(label: String, dotColor: Color?, pulse: Boolean = false) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp)
    ) {
        if (dotColor != null) {
            // Animated scale for the "alive" pulse effect; 1f means no animation.
            val scale = if (pulse) {
                val infiniteTransition = rememberInfiniteTransition(label = "pulse")
                infiniteTransition.animateFloat(
                    initialValue = 0.85f, targetValue = 1.15f,
                    animationSpec = infiniteRepeatable(
                        animation = tween(900, easing = FastOutSlowInEasing),
                        repeatMode = RepeatMode.Reverse
                    ), label = "dot_pulse"
                ).value
            } else 1f
            Box(
                modifier = Modifier
                    .size(7.dp)
                    .scale(scale)
                    .background(dotColor, CircleShape)
            )
        } else {
            // No color means an ongoing async check: show spinner instead of dot.
            CircularProgressIndicator(
                modifier = Modifier.size(8.dp),
                color = RavenMuted,
                strokeWidth = 1.5.dp
            )
        }
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = dotColor ?: RavenMuted,
            fontSize = 10.sp
        )
    }
}

// ============================================================
// Reusable card and field components (private to this file)
// ============================================================

/**
 * Styled card container used by every settings section. Provides consistent background,
 * border, corner radius, and padding for all settings content blocks.
 */
@Composable
private fun SettingsCard(content: @Composable ColumnScope.() -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = RavenCard),
        border = BorderStroke(1.dp, RavenBorder),
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth()
    ) { Column(modifier = Modifier.padding(16.dp), content = content) }
}

/**
 * Labeled text input field used within a [SettingsCard].
 *
 * Renders the field label in bold, a descriptive hint below it, then an [OutlinedTextField].
 * When [password] is true the value is obscured with a bullet transformation.
 * When [enabled] is false the field becomes read-only and all colors are dimmed, visually
 * matching the locked state shown when the backend is offline.
 */
@Composable
private fun ColumnScope.SettingsTextField(
    label: String,
    hint: String,
    value: String,
    onChange: (String) -> Unit,
    placeholder: String,
    password: Boolean = false,
    enabled: Boolean = true
) {
    Text(
        label,
        style = MaterialTheme.typography.bodyMedium,
        fontWeight = FontWeight.SemiBold,
        color = if (enabled) Color.White else Color.White.copy(alpha = 0.4f)
    )
    Text(
        hint,
        style = MaterialTheme.typography.bodySmall,
        color = if (enabled) RavenMuted else RavenMuted.copy(alpha = 0.4f),
        modifier = Modifier.padding(top = 2.dp, bottom = 12.dp)
    )
    OutlinedTextField(
        value = value,
        // When the field is disabled, swallow all onChange events to prevent accidental edits.
        onValueChange = if (enabled) onChange else { _ -> },
        enabled = enabled,
        placeholder = {
            Text(placeholder, color = RavenMuted, style = MaterialTheme.typography.bodySmall)
        },
        modifier = Modifier.fillMaxWidth(),
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = RavenOrange,
            unfocusedBorderColor = if (enabled) RavenBorder else RavenBorder.copy(alpha = 0.3f),
            focusedTextColor = Color.White,
            unfocusedTextColor = Color.White,
            disabledTextColor = Color.White.copy(alpha = 0.3f),
            disabledBorderColor = RavenBorder.copy(alpha = 0.3f),
            cursorColor = RavenOrange,
            focusedContainerColor = RavenBg,
            unfocusedContainerColor = RavenBg,
            disabledContainerColor = RavenBg.copy(alpha = 0.5f)
        ),
        shape = RoundedCornerShape(10.dp),
        singleLine = true,
        visualTransformation = if (password)
            androidx.compose.ui.text.input.PasswordVisualTransformation()
        else
            androidx.compose.ui.text.input.VisualTransformation.None
    )
    Spacer(modifier = Modifier.height(10.dp))
}

/**
 * Save button placed at the bottom of each settings card.
 *
 * Turns green with a checkmark icon when [saved] is true (value persisted), and reverts to
 * the default orange "Save" style when the user modifies the input again (saved = false).
 * Disabled state is shown when [enabled] is false (e.g. server offline for key fields).
 */
@Composable
private fun ColumnScope.SettingsSaveButton(
    saved: Boolean,
    s: AppStrings,
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.fillMaxWidth().height(44.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = if (saved) AuthenticGreen else RavenOrange,
            disabledContainerColor = RavenOrange.copy(alpha = 0.2f)
        ),
        shape = RoundedCornerShape(10.dp)
    ) {
        if (saved) {
            Icon(
                Icons.Default.CheckCircle, contentDescription = null,
                modifier = Modifier.size(16.dp)
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(s.settingsSaved, fontWeight = FontWeight.SemiBold)
        } else {
            Text(s.settingsSave, fontWeight = FontWeight.SemiBold)
        }
    }
}

/**
 * Simple uppercase section header label with increased letter-spacing.
 * Used above each card to identify the settings group below it.
 */
@Composable
private fun SectionLabel(text: String) {
    Text(
        text.uppercase(),
        style = MaterialTheme.typography.labelSmall,
        color = RavenMuted,
        letterSpacing = androidx.compose.ui.unit.TextUnit(
            1.5f, androidx.compose.ui.unit.TextUnitType.Sp
        ),
        modifier = Modifier.fillMaxWidth()
    )
}

/**
 * A single key-value row inside the About card. Left-aligns the label in muted color and
 * right-aligns the value in white.
 */
@Composable
private fun AboutRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = RavenMuted)
        Text(value, style = MaterialTheme.typography.bodySmall, color = Color.White)
    }
}

/**
 * Key-value row where the value is a tappable hyperlink.
 *
 * Opens [url] in the device's default browser via [Intent.ACTION_VIEW].
 * The value text is underlined and colored orange to indicate it is tappable.
 */
@Composable
private fun AboutLinkRow(label: String, value: String, url: String) {
    val context = LocalContext.current
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = RavenMuted)
        Text(
            value,
            style = MaterialTheme.typography.bodySmall.copy(textDecoration = TextDecoration.Underline),
            color = RavenOrange,
            modifier = Modifier.clickable {
                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
            }
        )
    }
}
