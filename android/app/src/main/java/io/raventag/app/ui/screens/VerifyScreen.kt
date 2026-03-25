package io.raventag.app.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.SubcomposeAsyncImage
import io.raventag.app.BuildConfig
import io.raventag.app.ipfs.IpfsResolver
import io.raventag.app.ravencoin.RaventagMetadata
import io.raventag.app.ui.theme.*

enum class VerifyStep { VERIFYING_SUN, CHECKING_BLOCKCHAIN, DONE, ERROR }

data class VerifyResult(
    val authentic: Boolean,
    val tagUidHex: String? = null,
    val counter: Int? = null,
    val nfcPubId: String? = null,
    val assetName: String? = null,
    val metadata: RaventagMetadata? = null,
    val error: String? = null,
    val revoked: Boolean = false,
    val revokedReason: String? = null
)

@Composable
fun VerifyScreen(
    step: VerifyStep,
    result: VerifyResult?,
    onScanAgain: () -> Unit
) {
    val s = LocalStrings.current
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(RavenBg)
            .padding(24.dp)
            .verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(modifier = Modifier.height(24.dp))

        Text(
            text = when (step) {
                VerifyStep.DONE, VerifyStep.ERROR -> when {
                    result?.revoked == true -> s.verifyRevoked
                    result?.authentic == true -> s.verifyAuthentic
                    else -> s.verifyNotAuthentic
                }
                else -> s.verifyingTitle
            },
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            color = when {
                step == VerifyStep.DONE && result?.authentic == true -> AuthenticGreen
                step == VerifyStep.ERROR || result?.authentic == false -> NotAuthenticRed
                else -> Color.White
            }
        )

        Spacer(modifier = Modifier.height(32.dp))

        // Progress steps
        if (step != VerifyStep.DONE && step != VerifyStep.ERROR) {
            VerifyProgress(step, s)
            Spacer(modifier = Modifier.height(32.dp))
        }

        // Result content
        if (result != null && (step == VerifyStep.DONE || step == VerifyStep.ERROR)) {
            AuthenticityBanner(result, s)
            Spacer(modifier = Modifier.height(20.dp))

            if (result.metadata != null) {
                AssetInfoCard(result, s)
                Spacer(modifier = Modifier.height(12.dp))
            }

            SecurityDetailsCard(result, s)
            Spacer(modifier = Modifier.height(24.dp))

            Button(
                onClick = onScanAgain,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.outlinedButtonColors(),
                border = BorderStroke(1.dp, RavenBorder)
            ) {
                Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text(s.verifyScanAgain)
            }
        }
    }
}

@Composable
private fun VerifyProgress(step: VerifyStep, s: AppStrings) {
    val steps = listOf(
        VerifyStep.VERIFYING_SUN to s.verifyNfcSig,
        VerifyStep.CHECKING_BLOCKCHAIN to s.verifyBlockchain
    )
    val currentIdx = steps.indexOfFirst { it.first == step }

    Column(
        verticalArrangement = Arrangement.spacedBy(16.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        steps.forEachIndexed { idx, (_, label) ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                when {
                    idx < currentIdx -> Icon(Icons.Default.CheckCircle, contentDescription = null, tint = AuthenticGreen, modifier = Modifier.size(20.dp))
                    idx == currentIdx -> CircularProgressIndicator(color = RavenOrange, modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                    else -> Icon(Icons.Default.RadioButtonUnchecked, contentDescription = null, tint = RavenMuted, modifier = Modifier.size(20.dp))
                }
                Text(
                    text = label,
                    color = when {
                        idx < currentIdx -> AuthenticGreen
                        idx == currentIdx -> Color.White
                        else -> RavenMuted
                    }
                )
            }
        }
    }
}

@Composable
private fun AuthenticityBanner(result: VerifyResult, s: AppStrings) {
    val bgColor = if (result.authentic) AuthenticGreenBg else NotAuthenticRedBg
    val borderColor = if (result.authentic) AuthenticGreen.copy(alpha = 0.4f) else NotAuthenticRed.copy(alpha = 0.4f)
    val iconColor = if (result.authentic) AuthenticGreen else NotAuthenticRed

    Card(
        colors = CardDefaults.cardColors(containerColor = bgColor),
        border = BorderStroke(1.dp, borderColor),
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(
                imageVector = if (result.authentic) Icons.Default.CheckCircle else Icons.Default.Cancel,
                contentDescription = null,
                tint = iconColor,
                modifier = Modifier.size(64.dp)
            )
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = when {
                    result.revoked -> s.verifyRevoked.uppercase()
                    result.authentic -> s.verifyAuthentic.uppercase()
                    else -> s.verifyNotAuthentic.uppercase()
                },
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = iconColor
            )
            if (result.revoked) {
                Spacer(modifier = Modifier.height(8.dp))
                Surface(color = Color(0xFF3D0000), shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp)) {
                    Row(modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Block, contentDescription = null, tint = NotAuthenticRed, modifier = Modifier.size(14.dp))
                        Text(s.verifyRevokedBy, style = MaterialTheme.typography.labelSmall, color = NotAuthenticRed, fontWeight = FontWeight.Bold)
                    }
                }
                result.revokedReason?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = NotAuthenticRed.copy(alpha = 0.7f), textAlign = TextAlign.Center, modifier = Modifier.padding(top = 4.dp))
                }
            } else if (!result.authentic && result.error != null) {
                Text(
                    text = result.error,
                    style = MaterialTheme.typography.bodySmall,
                    color = NotAuthenticRed.copy(alpha = 0.7f),
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = 8.dp)
                )
            }
        }
    }
}

@Composable
private fun AssetInfoCard(result: VerifyResult, s: AppStrings) {
    val meta = result.metadata ?: return
    val hierarchy = listOfNotNull(meta.parentAsset, meta.subAsset, meta.variantAsset).joinToString(" / ")

    val imageUrl = meta.image?.let { IpfsResolver.primaryUrl(it) }
    val description = meta.description ?: meta.brandInfo?.description

    Card(
        colors = CardDefaults.cardColors(containerColor = RavenCard),
        border = BorderStroke(1.dp, RavenBorder),
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Icons.Default.Shield, contentDescription = null, tint = RavenOrange, modifier = Modifier.size(18.dp))
                Text(s.verifyAssetInfo, fontWeight = FontWeight.SemiBold, color = Color.White)
            }

            if (imageUrl != null) {
                SubcomposeAsyncImage(
                    model = imageUrl,
                    contentDescription = "Token image",
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 220.dp)
                        .clip(RoundedCornerShape(12.dp)),
                    loading = {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(140.dp)
                                .background(RavenBorder, RoundedCornerShape(12.dp)),
                            contentAlignment = Alignment.Center
                        ) {
                            CircularProgressIndicator(color = RavenOrange, modifier = Modifier.size(28.dp), strokeWidth = 2.dp)
                        }
                    },
                    error = { /* silent: no image shown on load error */ }
                )
            }

            InfoRow(label = s.verifyAsset, value = hierarchy, monospace = true, valueColor = RavenOrange)

            description?.let {
                InfoRow(label = s.verifyDescription, value = it)
            }
            meta.brandInfo?.website?.let {
                InfoRow(label = s.verifyWebsite, value = it, valueColor = RavenOrange)
            }
        }
    }
}

@Composable
private fun SecurityDetailsCard(result: VerifyResult, s: AppStrings) {
    Card(
        colors = CardDefaults.cardColors(containerColor = RavenCard),
        border = BorderStroke(1.dp, RavenBorder),
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Icons.Default.Lock, contentDescription = null, tint = RavenOrange, modifier = Modifier.size(18.dp))
                Text(s.verifySecDetails, fontWeight = FontWeight.SemiBold, color = Color.White)
            }

            if (BuildConfig.IS_BRAND) {
                result.tagUidHex?.let {
                    InfoRow(label = s.verifyTagUid, value = it.uppercase(), monospace = true)
                }
            }
            result.counter?.let {
                InfoRow(label = s.verifyScanCount, value = it.toString())
            }
            if (BuildConfig.IS_BRAND) {
                result.nfcPubId?.let { id ->
                    InfoRow(label = s.verifyNfcPubId, value = "${id.take(16)}…${id.takeLast(8)}", monospace = true)
                }
                InfoRow(label = s.verifyCrypto, value = "NTAG 424 DNA · AES-128-CMAC · RTP-1")
            }
        }
    }
}

@Composable
private fun InfoRow(
    label: String,
    value: String,
    monospace: Boolean = false,
    valueColor: Color = MaterialTheme.colorScheme.onSurface
) {
    Column {
        Text(
            text = label.uppercase(),
            style = MaterialTheme.typography.labelSmall,
            color = RavenMuted,
            letterSpacing = androidx.compose.ui.unit.TextUnit(1.5f, androidx.compose.ui.unit.TextUnitType.Sp)
        )
        Spacer(modifier = Modifier.height(2.dp))
        Text(
            text = value,
            style = if (monospace)
                MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace)
            else MaterialTheme.typography.bodyMedium,
            color = valueColor,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
    }
}
