/**
 * IssueAssetScreen.kt
 *
 * Multi-mode form screen for all asset lifecycle operations performed by the
 * brand operator. A single composable handles all modes, switching its content
 * via the [IssueMode] enum so the parent activity only needs one back-stack
 * destination rather than one per operation.
 *
 * Supported modes:
 *   - [IssueMode.ROOT_ASSET]    Issue a root (parent) Ravencoin asset. Costs 500 RVN.
 *   - [IssueMode.SUB_ASSET]     Issue a sub-asset under an existing root. Costs 100 RVN.
 *   - [IssueMode.UNIQUE_TOKEN]  Issue a unique token (qty=1, non-reissuable). Costs 5 RVN.
 *   - [IssueMode.REVOKE]        Mark an asset as counterfeit in the backend SQLite store,
 *                               with an optional on-chain burn to the Ravencoin burn address.
 *   - [IssueMode.UNREVOKE]      Remove a prior revocation entry.
 *   - Other modes (REGISTER_CHIP, TRANSFER, etc.) are handled by dedicated screens; this
 *     composable renders nothing for them (the `else` branch is a no-op).
 *
 * Autocomplete:
 *   The parent / sub-asset fields derive suggestion lists from [ownedAssets] in real time
 *   so the brand operator can quickly select an existing asset without typing the full name.
 */
package io.raventag.app.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import io.raventag.app.ravencoin.AssetType
import io.raventag.app.ravencoin.OwnedAsset
import io.raventag.app.ui.theme.*

/**
 * Defines which operation the [IssueAssetScreen] should present.
 *
 * The screen's header title, subtitle, form fields, and submit button all change
 * according to the active mode. Modes not handled by this screen (REGISTER_CHIP,
 * TRANSFER, TRANSFER_ROOT, TRANSFER_SUB) have dedicated composables elsewhere.
 */
enum class IssueMode { ROOT_ASSET, SUB_ASSET, UNIQUE_TOKEN, REVOKE, UNREVOKE, REGISTER_CHIP, TRANSFER, TRANSFER_ROOT, TRANSFER_SUB }

/**
 * Multi-purpose asset management form for the brand operator.
 *
 * @param mode                    Which operation to display.
 * @param isLoading               When true, the submit button shows a spinner and is disabled.
 * @param resultMessage           Feedback string shown in the result banner after a submit attempt.
 * @param resultSuccess           Nullable: null = no result yet, true = success, false = failure.
 * @param prefilledAddress        Pre-populated "Send to" address (e.g. from wallet manager).
 * @param ownedAssets             Brand's asset list used to populate autocomplete suggestions.
 * @param savedAdminKey           Admin API key stored in settings, forwarded to the IPFS uploader.
 * @param savedPinataJwt          Pinata JWT stored in settings, used for IPFS pinning.
 * @param savedKuboNodeUrl        Kubo (IPFS) node URL stored in settings, used as an alternative pin target.
 * @param pinataJwtValidated      Whether the Pinata JWT has been validated in Settings.
 * @param kuboNodeValidated       Whether the Kubo node URL has been validated in Settings.
 * @param onBack                  Navigate back without submitting.
 * @param onIssueRoot             Callback for ROOT_ASSET submission.
 * @param onIssueSub              Callback for SUB_ASSET submission.
 * @param onIssueUnique           Callback for UNIQUE_TOKEN submission (issue only, no NFC write).
 * @param onRevoke                Callback for REVOKE submission.
 * @param onUnrevoke              Callback for UNREVOKE submission.
 * @param onIssueUniqueAndWriteTag If non-null, replaces [onIssueUnique] with a combined
 *                                 issue-and-program-chip flow, shown with a different button label.
 */
@Composable
fun IssueAssetScreen(
    mode: IssueMode,
    isLoading: Boolean,
    resultMessage: String?,
    resultSuccess: Boolean?,
    prefilledAddress: String = "",
    ownedAssets: List<OwnedAsset> = emptyList(),
    savedAdminKey: String = "",
    savedPinataJwt: String = "",
    savedKuboNodeUrl: String = "",
    pinataJwtValidated: Boolean = false,
    kuboNodeValidated: Boolean = false,
    onBack: () -> Unit,
    onIssueRoot: (name: String, qty: Long, toAddress: String, ipfsHash: String?, reissuable: Boolean) -> Unit,
    onIssueSub: (parent: String, child: String, qty: Long, toAddress: String, ipfsHash: String?, reissuable: Boolean) -> Unit,
    onIssueUnique: (parentSub: String, serial: String, toAddress: String, ipfsHash: String?, description: String?) -> Unit,
    onRevoke: (assetName: String, reason: String, adminKey: String) -> Unit,
    onUnrevoke: (assetName: String, adminKey: String) -> Unit = { _, _ -> },
    onIssueUniqueAndWriteTag: ((parentSub: String, serial: String, toAddress: String, ipfsHash: String?, description: String?) -> Unit)? = null
) {
    val s = LocalStrings.current
    // Read API base URL from BuildConfig so the IPFS uploader can reach the backend.
    val apiBaseUrl = io.raventag.app.BuildConfig.API_BASE_URL

    // ---- Local form state ----
    // Each field is independent mutable state; resetting is done by navigating away.
    var assetName by remember { mutableStateOf("") }
    var parentAsset by remember { mutableStateOf("") }
    var childName by remember { mutableStateOf("") }
    var subAsset by remember { mutableStateOf("") }
    var serial by remember { mutableStateOf("") }
    var qty by remember { mutableStateOf("1") }
    // toAddress is pre-seeded from the wallet's receive address when available.
    var toAddress by remember(prefilledAddress) { mutableStateOf(prefilledAddress) }
    var description by remember { mutableStateOf("") }
    // imageState holds both the local Uri and the resulting IPFS CID after upload.
    val imageState = rememberImagePickerState()
    // reissuable defaults to true for ROOT and SUB; unique tokens are always non-reissuable.
    var reissuable by remember(mode) { mutableStateOf(mode == IssueMode.ROOT_ASSET || mode == IssueMode.SUB_ASSET) }
    var revokeReason by remember { mutableStateOf("") }
    // Three-field asset selector used in REVOKE and UNREVOKE modes.
    var revokeMain by remember { mutableStateOf("") }
    var revokeSub by remember { mutableStateOf("") }
    var revokeToken by remember { mutableStateOf("") }

    // ---- Autocomplete suggestion derivation ----
    // Trim and uppercase the live field values so prefix matching is case-insensitive.
    val parentAssetQuery = parentAsset.trim().uppercase()
    val subAssetQuery = subAsset.trim().uppercase()
    val revokeMainQuery = revokeMain.trim().uppercase()
    val revokeSubQuery = revokeSub.trim().uppercase()
    val revokeTokenQuery = revokeToken.trim().uppercase()

    // Filter owned ROOT assets whose name starts with the current parentAsset field value.
    // Unique-token admin assets (ending with "!") are excluded as they cannot be parents.
    val rootAssetSuggestions = remember(ownedAssets, parentAsset) {
        ownedAssets
            .filter { it.type == AssetType.ROOT && !it.name.endsWith("!") }
            .map { it.name }
            .filter { parentAssetQuery.isNotBlank() && it.startsWith(parentAssetQuery) }
            .distinct()
            .sorted()
            .take(5) // Cap at 5 to keep the dropdown compact.
    }

    // Same prefix filter applied to owned SUB assets for the UNIQUE_TOKEN parentSub field.
    val subAssetSuggestions = remember(ownedAssets, subAsset) {
        ownedAssets
            .filter { it.type == AssetType.SUB && !it.name.endsWith("!") }
            .map { it.name }
            .filter { subAssetQuery.isNotBlank() && it.startsWith(subAssetQuery) }
            .distinct()
            .sorted()
            .take(5)
    }

    // Suggestions for the REVOKE/UNREVOKE three-field selector.
    // Main: ROOT assets matching the typed prefix.
    val revokeMainSuggestions = remember(ownedAssets, revokeMain) {
        ownedAssets
            .filter { it.type == AssetType.ROOT && !it.name.endsWith("!") }
            .map { it.name }
            .filter { revokeMainQuery.isNotBlank() && it.startsWith(revokeMainQuery) }
            .distinct().sorted().take(5)
    }
    // Sub: segment after "/" for SUB assets whose name starts with the selected main asset.
    val revokeSubSuggestions = remember(ownedAssets, revokeMain, revokeSub) {
        val prefix = if (revokeMainQuery.isNotBlank()) "$revokeMainQuery/" else ""
        ownedAssets
            .filter { it.type == AssetType.SUB && !it.name.endsWith("!") && (prefix.isBlank() || it.name.startsWith(prefix)) }
            .map { it.name.substringAfter("/") }
            .filter { revokeSubQuery.isNotBlank() && it.startsWith(revokeSubQuery) }
            .distinct().sorted().take(5)
    }
    // Token: serial part after "#" for UNIQUE assets matching MAIN/SUB#.
    val revokeTokenSuggestions = remember(ownedAssets, revokeMain, revokeSub, revokeToken) {
        val prefix = buildString {
            if (revokeMainQuery.isNotBlank()) {
                append("$revokeMainQuery/")
                if (revokeSubQuery.isNotBlank()) append("$revokeSubQuery#")
            }
        }
        ownedAssets
            .filter { it.type == AssetType.UNIQUE && (prefix.isBlank() || it.name.startsWith(prefix)) }
            .map { it.name.substringAfterLast("#") }
            .filter { revokeTokenQuery.isNotBlank() && it.startsWith(revokeTokenQuery) }
            .distinct().sorted().take(5)
    }
    // Full assembled asset path from the three separate fields.
    val fullRevokeAsset = buildString {
        if (revokeMain.isNotBlank()) {
            append(revokeMain.uppercase())
            if (revokeSub.isNotBlank()) {
                append("/")
                append(revokeSub.uppercase())
                if (revokeToken.isNotBlank()) {
                    append("#")
                    append(revokeToken.uppercase())
                }
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(RavenBg)
            .statusBarsPadding()
            .padding(horizontal = 20.dp)
            .verticalScroll(rememberScrollState())
    ) {
        Spacer(modifier = Modifier.height(24.dp))

        // Header: back button + title/subtitle that change per mode.
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            IconButton(onClick = onBack) {
                @Suppress("DEPRECATION")
                Icon(
                    imageVector = Icons.Default.ArrowBack,
                    contentDescription = s.back,
                    tint = Color.White
                )
            }
            Column {
                Text(
                    text = when (mode) {
                        IssueMode.ROOT_ASSET -> s.issueRootTitle
                        IssueMode.SUB_ASSET -> s.issueSubTitle
                        IssueMode.UNIQUE_TOKEN -> s.issueUniqueTitle
                        IssueMode.REVOKE -> s.issueRevokeTitle
                        IssueMode.UNREVOKE -> s.issueUnrevokeTitle
                        else -> ""
                    },
                    style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = Color.White
                )
                Text(
                    text = when (mode) {
                        IssueMode.ROOT_ASSET -> s.issue500
                        IssueMode.SUB_ASSET -> s.issue100
                        IssueMode.UNIQUE_TOKEN -> s.issueUniqueSub
                        IssueMode.REVOKE -> s.issueIrreversibleSub
                        IssueMode.UNREVOKE -> s.issueUnrevokeSub
                        else -> ""
                    },
                    style = MaterialTheme.typography.bodySmall,
                    // Revoke subtitle is shown in red to reinforce the destructive nature of the action.
                    color = if (mode == IssueMode.REVOKE) NotAuthenticRed else RavenMuted
                )
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        // Result banner: shown only after the user has submitted the form at least once.
        // Color and icon switch between success green and error red based on the outcome.
        resultSuccess?.let { success ->
            Card(
                colors = CardDefaults.cardColors(containerColor = if (success) AuthenticGreenBg else NotAuthenticRedBg),
                border = BorderStroke(1.dp, if (success) AuthenticGreen.copy(0.4f) else NotAuthenticRed.copy(0.4f)),
                shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth()
            ) {
                Row(modifier = Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(if (success) Icons.Default.CheckCircle else Icons.Default.Error, contentDescription = null,
                        tint = if (success) AuthenticGreen else NotAuthenticRed, modifier = Modifier.size(20.dp))
                    Text(resultMessage ?: "", color = if (success) AuthenticGreen else NotAuthenticRed, style = MaterialTheme.typography.bodySmall)
                }
            }
            Spacer(modifier = Modifier.height(16.dp))
        }

        // Form fields: each branch shows only the inputs relevant to its mode.
        when (mode) {
            IssueMode.ROOT_ASSET -> {
                // Asset name is auto-uppercased (Ravencoin protocol requires uppercase asset names).
                RavenTextField(s.fieldAssetName, assetName, { assetName = it.uppercase() }, "MYASSET", s.assetNameHint)
                Spacer(Modifier.height(12.dp))
                RavenTextField(s.fieldQty, qty, { qty = it }, "1", keyboardType = KeyboardType.Number)
                Spacer(Modifier.height(12.dp))
                RavenTextField(s.fieldSendTo, toAddress, { toAddress = it }, "RXxxxxxxxxxxxxxxxxxxxx", s.fieldSendToHint)
                Spacer(Modifier.height(12.dp))
                // Brand logo image picker , goes to IPFS, CID used as asset image
                ImagePickerButton(
                    state = imageState,
                    adminKey = savedAdminKey,
                    apiBaseUrl = apiBaseUrl,
                    pinataJwt = savedPinataJwt,
                    kuboNodeUrl = savedKuboNodeUrl,
                    pinataValidated = pinataJwtValidated,
                    kuboValidated = kuboNodeValidated
                )
                Spacer(Modifier.height(12.dp))
                RavenSwitch(s.fieldReissuable, reissuable, { reissuable = it })
                Spacer(Modifier.height(24.dp))
                // Capture IPFS CID and reissuable into local vals so the lambda captures
                // the values at the time of the click, not stale state from a future recomposition.
                val effectiveIpfs = imageState.value.ipfsCid
                val currentReissuable = reissuable
                // Minimum validation: asset name at least 3 chars and a plausible RVN address length.
                SubmitButton(s.btnIssueRoot, isLoading, assetName.length >= 3 && toAddress.length >= 26, RavenOrange) {
                    onIssueRoot(assetName, qty.toLongOrNull() ?: 1, toAddress, effectiveIpfs, currentReissuable)
                }
            }

            IssueMode.SUB_ASSET -> {
                RavenTextField(s.fieldParent, parentAsset, { parentAsset = it.uppercase() }, "MYASSET")
                // Inline autocomplete dropdown for owned root assets.
                AssetAutocompleteSuggestions(
                    suggestions = rootAssetSuggestions,
                    currentValue = parentAsset,
                    onSelect = { parentAsset = it }
                )
                Spacer(Modifier.height(12.dp))
                // Live preview hint shows the final asset path as the user types (e.g. "PARENT/CHILD").
                RavenTextField(s.fieldChild, childName, { childName = it.uppercase() }, "ITEM01",
                    hint = "→ ${parentAsset.ifBlank { "PARENT" }}/${childName.ifBlank { "CHILD" }}")
                Spacer(Modifier.height(12.dp))
                RavenTextField(s.fieldQty, qty, { qty = it }, "1", keyboardType = KeyboardType.Number)
                Spacer(Modifier.height(12.dp))
                RavenTextField(s.fieldSendTo, toAddress, { toAddress = it }, "RXxxxxxxxxxxxxxxxxxxxx", s.fieldSendToHint)
                Spacer(Modifier.height(12.dp))
                // Product photo , e.g. photo of the bag model
                ImagePickerButton(
                    state = imageState,
                    adminKey = savedAdminKey,
                    apiBaseUrl = apiBaseUrl,
                    pinataJwt = savedPinataJwt,
                    kuboNodeUrl = savedKuboNodeUrl,
                    pinataValidated = pinataJwtValidated,
                    kuboValidated = kuboNodeValidated
                )
                Spacer(Modifier.height(12.dp))
                RavenSwitch(s.fieldReissuable, reissuable, { reissuable = it })
                Spacer(Modifier.height(24.dp))

                // Capture mutable state into stable locals before the lambda to avoid
                // accidentally reading stale values during an async operation.
                val currentReissuable = reissuable
                val subEffectiveIpfs = imageState.value.ipfsCid
                val subEnabled = parentAsset.length >= 3 && childName.length >= 1 && toAddress.length >= 26

                SubmitButton(s.btnIssueSub, isLoading, subEnabled, RavenOrange) {
                    onIssueSub(parentAsset, childName, qty.toLongOrNull() ?: 1, toAddress, subEffectiveIpfs, currentReissuable)
                }
            }

            IssueMode.UNIQUE_TOKEN -> {
                // Info card: explains the unique token spec (qty=1, non-reissuable, 5 RVN fee).
                Card(
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF0A1A0A)),
                    border = BorderStroke(1.dp, AuthenticGreen.copy(alpha = 0.25f)),
                    shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth()
                ) {
                    Row(modifier = Modifier.padding(14.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Icon(Icons.Default.Token, contentDescription = null, tint = AuthenticGreen, modifier = Modifier.size(16.dp))
                        Text(
                            "5 RVN · PARENT/SUB#SERIAL · qty=1 · non-reissuable",
                            style = MaterialTheme.typography.bodySmall, color = RavenMuted
                        )
                    }
                }
                Spacer(Modifier.height(16.dp))
                // The parentSub field accepts a sub-asset (e.g. "FASHIONX/BAG01") under which
                // the unique token "#SERIAL" is minted, forming "FASHIONX/BAG01#SERIAL".
                RavenTextField(s.fieldSubAsset, subAsset, { subAsset = it.uppercase() }, "FASHIONX/BAG01", s.fieldSubAssetHint)
                // Autocomplete list of owned sub-assets narrows as the user types.
                AssetAutocompleteSuggestions(
                    suggestions = subAssetSuggestions,
                    currentValue = subAsset,
                    onSelect = { subAsset = it }
                )
                Spacer(Modifier.height(12.dp))
                RavenTextField(
                    s.fieldSerial, serial,
                    // Serial is sanitized: uppercase, alphanumeric only (Ravencoin unique token rule).
                    { serial = it.uppercase().filter { c -> c.isLetterOrDigit() } },
                    "SN0001",
                    // Live hint assembles the full unique token name (e.g. "FASHIONX/BAG01#SN0001").
                    hint = if (subAsset.isNotBlank() && serial.isNotBlank()) "→ $subAsset#$serial" else s.fieldSerialHint
                )
                Spacer(Modifier.height(12.dp))
                RavenTextField(s.fieldSendTo, toAddress, { toAddress = it }, "RXxxxxxxxxxxxxxxxxxxxx", s.fieldSendToHint)
                Spacer(Modifier.height(12.dp))
                RavenTextField("Description (optional)", description, { description = it }, "e.g. Limited edition red, batch A")
                Spacer(Modifier.height(12.dp))
                // Serial number photo , e.g. photo of the tag / label
                ImagePickerButton(
                    state = imageState,
                    adminKey = savedAdminKey,
                    apiBaseUrl = apiBaseUrl,
                    pinataJwt = savedPinataJwt,
                    kuboNodeUrl = savedKuboNodeUrl,
                    pinataValidated = pinataJwtValidated,
                    kuboValidated = kuboNodeValidated
                )
                Spacer(Modifier.height(24.dp))

                val uniqueEnabled = subAsset.length >= 3 && serial.length >= 1 && toAddress.length >= 26
                val uniqueEffectiveIpfs = imageState.value.ipfsCid
                // Convert blank description to null so the backend knows no description was given.
                val uniqueDescription = description.ifBlank { null }

                // If the caller provides a combined issue-and-write callback, use that button instead.
                // This path is taken when the screen is launched from the "Issue + Write Tag" flow,
                // allowing the asset issuance and NFC programming to happen in a single user action.
                if (onIssueUniqueAndWriteTag != null) {
                    SubmitButton(s.btnIssueAndWrite, isLoading, uniqueEnabled, AuthenticGreen) {
                        onIssueUniqueAndWriteTag(subAsset, serial, toAddress, uniqueEffectiveIpfs, uniqueDescription)
                    }
                } else {
                    SubmitButton(s.btnIssueUnique, isLoading, uniqueEnabled, AuthenticGreen) {
                        onIssueUnique(subAsset, serial, toAddress, uniqueEffectiveIpfs, uniqueDescription)
                    }
                }
            }

            IssueMode.REVOKE -> {
                // Warning card: revocation flags the asset in the backend's SQLite revoked_assets table.
                Card(
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF2D0A0A)),
                    border = BorderStroke(1.dp, NotAuthenticRed.copy(alpha = 0.5f)),
                    shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth()
                ) {
                    Row(modifier = Modifier.padding(14.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Icon(Icons.Default.Warning, contentDescription = null, tint = NotAuthenticRed, modifier = Modifier.size(18.dp))
                        Text(s.issueRevokeWarning, style = MaterialTheme.typography.bodySmall, color = NotAuthenticRed.copy(alpha = 0.9f))
                    }
                }
                Spacer(Modifier.height(16.dp))
                // Main asset (root): e.g. RAVENTAG
                RavenTextField(s.fieldMainAsset, revokeMain,
                    { revokeMain = it.uppercase().filter { c -> c.isLetterOrDigit() || c == '_' } },
                    "e.g. RAVENTAG")
                AssetAutocompleteSuggestions(revokeMainSuggestions, revokeMain.uppercase()) { revokeMain = it }
                Spacer(Modifier.height(12.dp))
                // Sub-asset segment (optional): e.g. RAVENTAG_TOKEN
                RavenTextField("${s.fieldSubAsset} (opt.)", revokeSub,
                    { revokeSub = it.uppercase().filter { c -> c.isLetterOrDigit() || c == '_' || c == '.' } },
                    "e.g. RAVENTAG_TOKEN")
                AssetAutocompleteSuggestions(revokeSubSuggestions, revokeSub.uppercase()) { revokeSub = it.substringAfterLast("/") }
                Spacer(Modifier.height(12.dp))
                // Unique token serial (optional): e.g. SN0001 -> builds MAIN/SUB#SERIAL
                RavenTextField("${s.fieldSerial} (opt.)", revokeToken,
                    { revokeToken = it.uppercase().filter { c -> c.isLetterOrDigit() || c == '_' || c == '.' } },
                    "e.g. SN0001")
                AssetAutocompleteSuggestions(revokeTokenSuggestions, revokeToken.uppercase()) { revokeToken = it }
                // Live preview of the assembled asset path.
                if (fullRevokeAsset.isNotBlank()) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        fullRevokeAsset,
                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                        color = NotAuthenticRed
                    )
                }
                Spacer(Modifier.height(12.dp))
                Text(s.fieldReason, style = MaterialTheme.typography.labelSmall, color = RavenMuted)
                Spacer(Modifier.height(6.dp))
                RevokeReasonChips(
                    options = listOf(s.revokeReasonCounterfeit, s.revokeReasonStolen, s.revokeReasonDamaged, s.revokeReasonRecalled, s.revokeReasonUnauthorized, s.revokeReasonExpired),
                    selected = revokeReason,
                    onSelect = { revokeReason = it }
                )
                Spacer(Modifier.height(24.dp))
                SubmitButton(s.btnRevoke, isLoading, fullRevokeAsset.length >= 3, NotAuthenticRed) {
                    onRevoke(fullRevokeAsset, revokeReason.ifBlank { s.revokeReasonRecalled }, savedAdminKey)
                }
            }

            IssueMode.UNREVOKE -> {
                Card(
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF0A1A0A)),
                    border = BorderStroke(1.dp, AuthenticGreen.copy(alpha = 0.3f)),
                    shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth()
                ) {
                    Row(modifier = Modifier.padding(14.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Icon(Icons.Default.Info, contentDescription = null, tint = AuthenticGreen, modifier = Modifier.size(16.dp))
                        Text(s.unrevokeNote, style = MaterialTheme.typography.bodySmall, color = RavenMuted)
                    }
                }
                Spacer(Modifier.height(16.dp))
                // Main asset (root)
                RavenTextField(s.fieldMainAsset, revokeMain,
                    { revokeMain = it.uppercase().filter { c -> c.isLetterOrDigit() || c == '_' } },
                    "e.g. RAVENTAG")
                AssetAutocompleteSuggestions(revokeMainSuggestions, revokeMain.uppercase()) { revokeMain = it }
                Spacer(Modifier.height(12.dp))
                // Sub-asset segment (optional)
                RavenTextField("${s.fieldSubAsset} (opt.)", revokeSub,
                    { revokeSub = it.uppercase().filter { c -> c.isLetterOrDigit() || c == '_' || c == '.' } },
                    "e.g. RAVENTAG_TOKEN")
                AssetAutocompleteSuggestions(revokeSubSuggestions, revokeSub.uppercase()) { revokeSub = it.substringAfterLast("/") }
                Spacer(Modifier.height(12.dp))
                // Unique token serial (optional)
                RavenTextField("${s.fieldSerial} (opt.)", revokeToken,
                    { revokeToken = it.uppercase().filter { c -> c.isLetterOrDigit() || c == '_' || c == '.' } },
                    "e.g. SN0001")
                AssetAutocompleteSuggestions(revokeTokenSuggestions, revokeToken.uppercase()) { revokeToken = it }
                // Live preview of the assembled asset path.
                if (fullRevokeAsset.isNotBlank()) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        fullRevokeAsset,
                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                        color = AuthenticGreen
                    )
                }
                Spacer(Modifier.height(24.dp))
                SubmitButton(s.btnUnrevoke, isLoading, fullRevokeAsset.length >= 3, AuthenticGreen) {
                    onUnrevoke(fullRevokeAsset, savedAdminKey)
                }
            }

            else -> { /* handled by dedicated screens */ }
        }

        Spacer(modifier = Modifier.height(32.dp))
    }
}

/**
 * Branded text field with a small uppercase label above and an optional hint below.
 *
 * The hint is rendered in [RavenOrange] and is used for live path previews (e.g.
 * "PARENT/CHILD") or static guidance text. When [minLines] > 1 the field becomes
 * multi-line (used for the revoke reason textarea).
 *
 * @param label         Field label, rendered in small caps above the input.
 * @param value         Current field value.
 * @param onValueChange Called on every keystroke with the new value.
 * @param placeholder   Ghost text shown when the field is empty.
 * @param hint          Optional helper text shown below the field.
 * @param keyboardType  Keyboard type hint forwarded to the IME (default: Text).
 * @param minLines      Minimum number of visible text lines (default: 1, i.e. single-line).
 */
@Composable
private fun RavenTextField(
    label: String, value: String, onValueChange: (String) -> Unit,
    placeholder: String = "", hint: String? = null,
    keyboardType: KeyboardType = KeyboardType.Text, minLines: Int = 1
) {
    Column {
        // Label is always uppercase with a small letter-spacing for a compact, branded look.
        Text(label.uppercase(), style = MaterialTheme.typography.labelSmall, color = RavenMuted,
            letterSpacing = androidx.compose.ui.unit.TextUnit(1f, androidx.compose.ui.unit.TextUnitType.Sp))
        Spacer(modifier = Modifier.height(4.dp))
        OutlinedTextField(
            value = value, onValueChange = onValueChange,
            placeholder = { Text(placeholder, color = RavenMuted, style = MaterialTheme.typography.bodySmall) },
            modifier = Modifier.fillMaxWidth(),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = RavenOrange, unfocusedBorderColor = RavenBorder,
                focusedTextColor = Color.White, unfocusedTextColor = Color.White,
                cursorColor = RavenOrange, focusedContainerColor = RavenCard, unfocusedContainerColor = RavenCard
            ),
            shape = RoundedCornerShape(12.dp),
            keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
            // singleLine and minLines are mutually exclusive; enforce single-line only when minLines == 1.
            minLines = minLines, singleLine = minLines == 1
        )
        hint?.let {
            Spacer(modifier = Modifier.height(4.dp))
            Text(it, style = MaterialTheme.typography.labelSmall, color = RavenOrange)
        }
    }
}

/**
 * Dropdown list of asset name suggestions that appears below an asset name field
 * while the user is typing. The list hides itself if [suggestions] only contains
 * the value already in the field (i.e. the user has already selected a suggestion).
 *
 * @param suggestions  Pre-filtered list of candidate asset names (max 5 from the parent).
 * @param currentValue Current typed value; suggestions equal to this are hidden to avoid
 *                     a redundant entry for a value already chosen.
 * @param onSelect     Called with the chosen name when the user taps a suggestion row.
 */
@Composable
private fun AssetAutocompleteSuggestions(
    suggestions: List<String>,
    currentValue: String,
    onSelect: (String) -> Unit
) {
    // Do not show the dropdown if the only suggestion equals the current input.
    val visibleSuggestions = suggestions.filter { it != currentValue }
    if (visibleSuggestions.isEmpty()) return

    Spacer(modifier = Modifier.height(6.dp))
    Card(
        colors = CardDefaults.cardColors(containerColor = RavenCard),
        border = BorderStroke(1.dp, RavenBorder),
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(vertical = 6.dp)) {
            visibleSuggestions.forEachIndexed { index, suggestion ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onSelect(suggestion) }
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Default.Search, contentDescription = null, tint = RavenMuted, modifier = Modifier.size(14.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    // Monospace font makes the asset path easier to scan visually.
                    Text(
                        suggestion,
                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                        color = Color.White
                    )
                }
                // Divider between rows, but not after the last one.
                if (index != visibleSuggestions.lastIndex) {
                    HorizontalDivider(color = RavenBorder.copy(alpha = 0.5f))
                }
            }
        }
    }
}

/**
 * Row of selectable chips for the revocation reason field.
 * The user taps a chip to select it; tapping again deselects it.
 * The selected chip is highlighted in [NotAuthenticRed] with a filled background.
 *
 * @param options   List of predefined reason strings to display.
 * @param selected  Currently selected reason, or empty string for no selection.
 * @param onSelect  Called with the reason string when a chip is tapped (empty string to deselect).
 */
@Composable
private fun RevokeReasonChips(
    options: List<String>,
    selected: String,
    onSelect: (String) -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        options.chunked(2).forEach { row ->
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { option ->
                    val isSelected = option == selected
                    val bgColor = if (isSelected) NotAuthenticRed.copy(alpha = 0.18f) else Color.Transparent
                    val borderColor = if (isSelected) NotAuthenticRed else RavenBorder
                    val textColor = if (isSelected) NotAuthenticRed else RavenMuted
                    Card(
                        colors = CardDefaults.cardColors(containerColor = bgColor),
                        border = BorderStroke(1.dp, borderColor),
                        shape = RoundedCornerShape(20.dp),
                        modifier = Modifier
                            .weight(1f)
                            .clickable { onSelect(if (isSelected) "" else option) }
                    ) {
                        Text(
                            option,
                            style = MaterialTheme.typography.labelMedium,
                            color = textColor,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp)
                        )
                    }
                }
                // Pad the last row if odd number of options.
                if (row.size == 1) Spacer(modifier = Modifier.weight(1f))
            }
        }
    }
}

/**
 * Labeled toggle switch with a configurable label color.
 *
 * The label color can be changed to red for destructive toggles (e.g. "Burn on chain")
 * to add an extra visual cue alongside the warning text.
 *
 * @param label           Text shown to the left of the switch.
 * @param checked         Current switch state.
 * @param onCheckedChange Invoked with the new state when the user toggles the switch.
 * @param labelColor      Color of the label text (default: white).
 */
@Composable
private fun RavenSwitch(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit, labelColor: Color = Color.White) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = labelColor, style = MaterialTheme.typography.bodyMedium)
        Switch(checked = checked, onCheckedChange = onCheckedChange,
            // Track color matches RavenOrange when on to stay consistent with the brand palette.
            colors = SwitchDefaults.colors(checkedThumbColor = Color.White, checkedTrackColor = RavenOrange))
    }
}

/**
 * Full-width submit button shared across all form modes.
 *
 * Shows a [CircularProgressIndicator] in place of the label text while [loading] is
 * true. The button is automatically disabled when [enabled] is false or [loading] is
 * true to prevent double submission. The background color is dimmed to 30% opacity
 * when disabled to preserve the visual intent of the mode's accent color.
 *
 * @param text    Button label shown in the normal (non-loading) state.
 * @param loading Whether to show the spinner instead of the label.
 * @param enabled Whether the form is in a valid state for submission.
 * @param color   Accent color for this mode (orange for issue, green for unique, red for revoke).
 * @param onClick Invoked when the button is tapped in the enabled, non-loading state.
 */
@Composable
private fun SubmitButton(text: String, loading: Boolean, enabled: Boolean, color: Color, onClick: () -> Unit) {
    Button(
        onClick = onClick, enabled = enabled && !loading,
        modifier = Modifier.fillMaxWidth().height(52.dp),
        colors = ButtonDefaults.buttonColors(containerColor = color, disabledContainerColor = color.copy(alpha = 0.3f)),
        shape = RoundedCornerShape(14.dp)
    ) {
        if (loading) {
            CircularProgressIndicator(color = Color.White, modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
        } else {
            Text(text, fontWeight = FontWeight.SemiBold)
        }
    }
}
