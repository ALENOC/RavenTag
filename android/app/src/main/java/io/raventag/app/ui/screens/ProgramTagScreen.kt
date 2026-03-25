package io.raventag.app.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Nfc
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.raventag.app.ravencoin.AssetType
import io.raventag.app.ravencoin.OwnedAsset
import io.raventag.app.ui.theme.*

/**
 * Screen for selecting an asset and initiating the "Program NFC Tag" standalone flow.
 *
 * This is the brand flavor's standalone tag-programming entry point. It allows an operator to
 * program an already-issued NTAG 424 DNA chip with the correct AES keys and SUN URL without
 * having to issue a new asset. The typical use case is re-programming a tag that was accidentally
 * reset, or programming a pre-manufactured batch of tags in the field.
 *
 * The flow after tapping "Start Programming":
 *   1. [onStart] is called with the selected asset name, verify URL, and admin/operator key.
 *   2. The ViewModel sets [WriteTagStep.WAIT_TAG] and the foreground NFC dispatch takes over.
 *   3. When the user holds the phone over the chip, [MainActivity.onTagTapped] is invoked,
 *      which calls [MainViewModel.processStandaloneWrite] to derive keys and program the tag.
 *
 * Asset selection:
 *   - If the wallet owns assets, they are listed in a scrollable [LazyColumn] with a search bar.
 *   - Tapping an asset row selects it and displays a preview card with the full verify URL.
 *   - If no wallet assets are loaded (empty list), a manual text field is shown instead.
 *
 * @param verifyUrl The configured backend URL (e.g. "https://api.raventag.com"). Shown in the
 *   preview card so the operator can confirm the URL before programming the chip.
 * @param savedAdminKey The active key (admin or operator) stored in EncryptedSharedPreferences.
 *   Passed through to [onStart] and ultimately to [AssetManager.deriveChipKeys].
 * @param ownedAssets List of Ravencoin assets currently held by the brand wallet. May be empty
 *   if the wallet has not yet loaded or has no assets.
 * @param onBack Callback invoked when the user taps the back arrow.
 * @param onStart Callback invoked when the user confirms the selection and taps "Start Programming".
 *   Receives (assetName, verifyUrl, adminKey).
 */
@Composable
fun ProgramTagScreen(
    verifyUrl: String,
    savedAdminKey: String,
    ownedAssets: List<OwnedAsset>,
    onBack: () -> Unit,
    onStart: (assetName: String, verifyUrl: String, adminKey: String) -> Unit
) {
    val s = LocalStrings.current

    // Currently selected asset name; typed manually when ownedAssets is empty.
    var assetName by remember { mutableStateOf("") }

    // Search query used to filter the asset list. Compared case-insensitively against asset names.
    var searchQuery by remember { mutableStateOf("") }

    // The "Start" button is enabled as soon as the asset name is at least 3 characters.
    val isValid = assetName.length >= 3

    // Pre-computed filtered list, recomputed only when ownedAssets or searchQuery change.
    // Upper-casing the query avoids a separate toLower call on every asset name.
    val filteredAssets = remember(ownedAssets, searchQuery) {
        ownedAssets.filter { asset ->
            searchQuery.isBlank() || asset.name.contains(searchQuery.uppercase())
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(RavenBg)
    ) {
        // Header: back button + title + technology badge.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp)
                .statusBarsPadding(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.Default.ArrowBack, contentDescription = s.back, tint = Color.White)
            }
            Column {
                Text(s.brandProgramTag, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = Color.White)
                // Subtitle clarifies the chip type and that the chip will be auto-registered.
                Text("NTAG 424 DNA · AES-128 · Auto-register", style = MaterialTheme.typography.bodySmall, color = RavenOrange)
            }
        }

        Column(modifier = Modifier.padding(horizontal = 20.dp)) {
            Text(s.brandProgramTagDesc, style = MaterialTheme.typography.bodySmall, color = RavenMuted)
            Spacer(modifier = Modifier.height(16.dp))

            // Preview card: shown once an asset is selected. Displays the asset name and the
            // exact SUN URL that will be written into the chip's NDEF record.
            if (assetName.isNotBlank()) {
                Card(
                    colors = CardDefaults.cardColors(containerColor = RavenOrange.copy(alpha = 0.1f)),
                    border = BorderStroke(1.dp, RavenOrange.copy(alpha = 0.4f)),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text(
                            s.fieldAssetName.uppercase(),
                            style = MaterialTheme.typography.labelSmall, color = RavenMuted,
                            letterSpacing = androidx.compose.ui.unit.TextUnit(1f, androidx.compose.ui.unit.TextUnitType.Sp)
                        )
                        Text(
                            assetName,
                            style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                            color = RavenOrange,
                            fontWeight = FontWeight.SemiBold
                        )
                        // The SUN URL preview uses placeholder params (e=... m=...) to show the
                        // template; actual encrypted params are generated at programming time.
                        Text(
                            "URL: $verifyUrl/verify?asset=$assetName&e=…&m=…",
                            style = MaterialTheme.typography.labelSmall,
                            color = RavenMuted,
                            fontFamily = FontFamily.Monospace,
                            modifier = Modifier.padding(top = 2.dp)
                        )
                    }
                }
                Spacer(modifier = Modifier.height(12.dp))
            }

            // Search field: filters the asset list as the user types (forced uppercase).
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it.uppercase() },
                placeholder = { Text("Search asset…", color = RavenMuted, style = MaterialTheme.typography.bodySmall) },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null, tint = RavenMuted, modifier = Modifier.size(18.dp)) },
                modifier = Modifier.fillMaxWidth(),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = RavenOrange, unfocusedBorderColor = RavenBorder,
                    focusedTextColor = Color.White, unfocusedTextColor = Color.White,
                    cursorColor = RavenOrange, focusedContainerColor = RavenCard, unfocusedContainerColor = RavenCard
                ),
                shape = RoundedCornerShape(12.dp),
                singleLine = true
            )
        }

        Spacer(modifier = Modifier.height(8.dp))

        if (ownedAssets.isEmpty()) {
            // Fallback: no assets loaded from the wallet, so offer a plain text entry field.
            // This also covers the scenario where the operator wants to program a chip for an
            // asset held in a different wallet that is not connected to the app.
            Column(modifier = Modifier.padding(horizontal = 20.dp)) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(s.brandProgramTagAssetHint, style = MaterialTheme.typography.labelSmall, color = RavenMuted)
                Spacer(modifier = Modifier.height(6.dp))
                OutlinedTextField(
                    value = assetName,
                    onValueChange = { assetName = it.uppercase() },
                    placeholder = { Text("FASHIONX/BAG01#SN0001", color = RavenMuted, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace) },
                    modifier = Modifier.fillMaxWidth(),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = RavenOrange, unfocusedBorderColor = RavenBorder,
                        focusedTextColor = Color.White, unfocusedTextColor = Color.White,
                        cursorColor = RavenOrange, focusedContainerColor = RavenCard, unfocusedContainerColor = RavenCard
                    ),
                    shape = RoundedCornerShape(12.dp),
                    singleLine = true
                )
            }
        } else {
            // Scrollable asset list using LazyColumn for efficient rendering of large portfolios.
            LazyColumn(
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(horizontal = 20.dp, vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                items(filteredAssets) { asset ->
                    // Each asset type uses a distinct color: orange (root), blue (sub), green (unique).
                    val typeColor = when (asset.type) {
                        AssetType.ROOT -> RavenOrange
                        AssetType.SUB -> Color(0xFF60A5FA)
                        AssetType.UNIQUE -> AuthenticGreen
                    }
                    val typeLabel = when (asset.type) {
                        AssetType.ROOT -> s.walletAssetRoot
                        AssetType.SUB -> s.walletAssetSub
                        AssetType.UNIQUE -> s.walletAssetUnique
                    }
                    val selected = assetName == asset.name

                    // Tapping a row selects it and clears the search query so the preview card
                    // is visible without the keyboard covering part of the screen.
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = if (selected) typeColor.copy(alpha = 0.12f) else RavenCard
                        ),
                        border = BorderStroke(1.dp, if (selected) typeColor.copy(alpha = 0.5f) else RavenBorder),
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { assetName = asset.name; searchQuery = "" }
                    ) {
                        Row(
                            modifier = Modifier.padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            // Type badge (Root / Sub-Asset / Unique) with color-coded background.
                            Surface(color = typeColor.copy(alpha = 0.15f), shape = RoundedCornerShape(6.dp)) {
                                Text(typeLabel, style = MaterialTheme.typography.labelSmall, color = typeColor, modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp))
                            }
                            // Asset name in monospace; highlighted in the type color when selected.
                            Text(
                                asset.name,
                                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                                color = if (selected) typeColor else Color.White,
                                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                                modifier = Modifier.weight(1f)
                            )
                            // Balance shown on the right in muted color for reference.
                            Text(
                                formatBal(asset.balance),
                                style = MaterialTheme.typography.labelSmall,
                                color = RavenMuted
                            )
                        }
                    }
                }
            }
        }

        // Start Programming button: at the bottom so it is always reachable after selection.
        // Passes the active key (admin or operator) chosen by the caller.
        Column(modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp)) {
            Button(
                onClick = { onStart(assetName, verifyUrl, savedAdminKey) },
                enabled = isValid,
                modifier = Modifier.fillMaxWidth().height(52.dp),
                colors = ButtonDefaults.buttonColors(containerColor = RavenOrange, disabledContainerColor = RavenOrange.copy(alpha = 0.3f)),
                shape = RoundedCornerShape(14.dp)
            ) {
                Icon(Icons.Default.Nfc, contentDescription = null, modifier = Modifier.size(20.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text(s.brandProgramTagStart, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

/**
 * Formats a Ravencoin asset balance for display in the asset list row.
 *
 * If the balance is a whole number it is shown without decimals (e.g. "1").
 * Otherwise up to 4 decimal places are shown (e.g. "123.4567").
 */
private fun formatBal(b: Double): String = if (b == b.toLong().toDouble()) b.toLong().toString() else "%.4f".format(b)
