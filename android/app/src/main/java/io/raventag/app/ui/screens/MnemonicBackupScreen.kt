package io.raventag.app.ui.screens

import android.app.Activity
import android.content.Context
import android.view.WindowManager
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.fragment.app.FragmentActivity
import io.raventag.app.security.BiometricCancelledException
import io.raventag.app.security.BiometricGate
import io.raventag.app.security.MnemonicExporter
import io.raventag.app.ui.theme.*
import io.raventag.app.wallet.KeystoreInvalidatedException
import io.raventag.app.wallet.WalletManager
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Full-screen overlay that displays the BIP-39 recovery phrase.
 *
 * Two operating modes:
 *   1. Fresh-wallet setup: `mnemonic` is provided by the caller (the wallet has just been
 *      generated but not yet persisted). The words render directly after a biometric cover
 *      gate (the OS prompt may be skipped in this path: the mnemonic is already in-memory).
 *   2. Later reveal (`mnemonic` is null/blank): the user must authenticate via
 *      [BiometricGate] bound to the Keystore decrypt operation (D-15). The resulting
 *      plaintext arrives as a [CharArray] that is zero-filled on dispose (D-16).
 *
 * Security measures applied in both modes:
 *   - `FLAG_SECURE` is set while the screen is composed, blocking screenshots and screen
 *     recording (RESEARCH Security Domain recommendation).
 *   - A confirmation gate ("I've saved it") flips the `backup_completed` SharedPreferences
 *     flag so that restore-over-wallet is unblocked (D-14).
 */
@Composable
fun MnemonicBackupScreen(
    mnemonic: String? = null,
    wm: WalletManager? = null,
    onConfirmed: () -> Unit,
    onKeystoreInvalidated: () -> Unit = {}
) {
    val s = LocalStrings.current
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current
    val view = LocalView.current
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    // FLAG_SECURE: prevent OS screenshot/screen-recording of the words grid.
    DisposableEffect(Unit) {
        val window = (view.context as? Activity)?.window
        window?.setFlags(
            WindowManager.LayoutParams.FLAG_SECURE,
            WindowManager.LayoutParams.FLAG_SECURE
        )
        onDispose { window?.clearFlags(WindowManager.LayoutParams.FLAG_SECURE) }
    }

    // Revealed mnemonic characters. In setup-flow, populated synchronously from `mnemonic`
    // after the user taps "Reveal phrase". In reveal-flow, populated by BiometricGate.
    var revealed by remember { mutableStateOf<CharArray?>(null) }

    // D-16: zero-fill the decrypted buffer when the screen is disposed.
    // Capture the buffer ref at effect launch so onDispose wipes the SAME buffer
    // this effect was keyed to, not whatever `revealed` points to at dispose time.
    DisposableEffect(revealed) {
        val captured = revealed
        onDispose { captured?.let { java.util.Arrays.fill(it, ' ') } }
    }

    var copied by remember { mutableStateOf(false) }
    var confirmed by remember { mutableStateOf(false) }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        containerColor = Color.Black
    ) { padding ->
    Column(
        modifier = Modifier
            .padding(padding)
            .fillMaxSize()
            .background(Color.Black)
            .statusBarsPadding()
            .verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(modifier = Modifier.height(32.dp))

        Box(
            modifier = Modifier
                .size(64.dp)
                .background(Color(0xFF3D1A00), RoundedCornerShape(20.dp)),
            contentAlignment = Alignment.Center
        ) {
            Icon(Icons.Default.Warning, contentDescription = null, tint = Color(0xFFF59E0B), modifier = Modifier.size(32.dp))
        }

        Spacer(modifier = Modifier.height(20.dp))

        Text(
            s.backupTitle,
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            color = Color.White,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 24.dp)
        )

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            s.backupSubtitle,
            style = MaterialTheme.typography.bodyMedium,
            color = RavenMuted,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 24.dp)
        )

        Spacer(modifier = Modifier.height(24.dp))

        if (revealed == null) {
            // ----------------------------------------------------------------
            // Biometric cover card (D-15). Words grid is hidden until auth
            // succeeds. In setup-flow we still require the user tap Reveal so
            // the screen is never passively rendered with the phrase visible.
            // ----------------------------------------------------------------
            Column(
                modifier = Modifier
                    .padding(horizontal = 20.dp)
                    .fillMaxWidth()
                    .background(RavenCard, RoundedCornerShape(12.dp))
                    .border(1.dp, RavenBorder, RoundedCornerShape(12.dp))
                    .padding(16.dp)
                    .semantics { contentDescription = s.biometricCoverDesc },
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Icon(
                        Icons.Default.Fingerprint,
                        contentDescription = null,
                        tint = RavenOrange,
                        modifier = Modifier.size(24.dp)
                    )
                    Text(
                        s.mnemonicBiometricCoverTitle,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = Color.White
                    )
                }
                Text(
                    s.mnemonicBiometricCoverBody,
                    style = MaterialTheme.typography.bodyMedium,
                    color = RavenMuted
                )
                Button(
                    onClick = {
                        scope.launch {
                            revealWithBiometric(
                                context = context,
                                wm = wm,
                                prefillMnemonic = mnemonic,
                                strings = s,
                                snackbarHostState = snackbarHostState,
                                onKeystoreInvalidated = onKeystoreInvalidated,
                                onRevealed = { chars -> revealed = chars }
                            )
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .semantics { contentDescription = s.revealMnemonicButtonDesc },
                    colors = ButtonDefaults.buttonColors(containerColor = RavenOrange),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Icon(Icons.Default.LockOpen, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(s.mnemonicRevealCta, fontWeight = FontWeight.SemiBold)
                }
            }
            Spacer(modifier = Modifier.height(24.dp))
        } else {
            val raw = String(revealed!!).trim()
            val words = if (raw.isEmpty()) emptyList() else raw.split(Regex("\\s+"))

            // ----------------------------------------------------------------
            // Words grid: rows of 3. Skip render if buffer already zero-filled
            // (confirm-in-flight) to avoid a phantom "1." cell.
            // ----------------------------------------------------------------
            if (words.isNotEmpty())
            Column(
                modifier = Modifier
                    .padding(horizontal = 20.dp)
                    .fillMaxWidth()
                    .background(Color(0xFF0A0A0A), RoundedCornerShape(16.dp))
                    .border(1.dp, Color(0xFFF59E0B).copy(alpha = 0.4f), RoundedCornerShape(16.dp))
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                words.chunked(3).forEachIndexed { rowIdx, row ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        row.forEachIndexed { colIdx, word ->
                            val n = rowIdx * 3 + colIdx + 1
                            Row(
                                modifier = Modifier
                                    .weight(1f)
                                    .background(RavenCard, RoundedCornerShape(8.dp))
                                    .padding(horizontal = 8.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Text(
                                    "$n.",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = RavenMuted,
                                    modifier = Modifier.width(18.dp)
                                )
                                Text(
                                    word,
                                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                                    color = Color.White,
                                    fontWeight = FontWeight.SemiBold,
                                    fontSize = 13.sp
                                )
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Copy All: briefly copies to clipboard; cleared after 60s.
            OutlinedButton(
                onClick = {
                    val asString = String(revealed!!)
                    clipboard.setText(AnnotatedString(asString))
                    copied = true
                    scope.launch {
                        delay(60_000)
                        clipboard.setText(AnnotatedString(""))
                        copied = false
                    }
                },
                modifier = Modifier.padding(horizontal = 20.dp).fillMaxWidth(),
                border = androidx.compose.foundation.BorderStroke(1.dp, if (copied) AuthenticGreen else RavenBorder),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = if (copied) AuthenticGreen else RavenMuted)
            ) {
                Icon(
                    if (copied) Icons.Default.CheckCircle else Icons.Default.ContentCopy,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    if (copied) s.backupCopied else s.mnemonicCopyAll,
                    style = MaterialTheme.typography.bodySmall
                )
            }

            Spacer(modifier = Modifier.height(24.dp))

            Column(
                modifier = Modifier.padding(horizontal = 20.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                listOf(s.backupWarning1, s.backupWarning2, s.backupWarning3).forEach { warning ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(Color(0xFF1A0A00), RoundedCornerShape(10.dp))
                            .border(1.dp, Color(0xFFF59E0B).copy(alpha = 0.25f), RoundedCornerShape(10.dp))
                            .padding(12.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Text(
                            warning,
                            style = MaterialTheme.typography.bodySmall,
                            color = Color(0xFFF59E0B).copy(alpha = 0.9f),
                            lineHeight = 18.sp
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            Row(
                modifier = Modifier
                    .padding(horizontal = 20.dp)
                    .fillMaxWidth()
                    .background(RavenCard, RoundedCornerShape(12.dp))
                    .border(
                        1.dp,
                        if (confirmed) AuthenticGreen.copy(alpha = 0.5f) else RavenBorder,
                        RoundedCornerShape(12.dp)
                    )
                    .padding(16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Checkbox(
                    checked = confirmed,
                    onCheckedChange = { confirmed = it },
                    colors = CheckboxDefaults.colors(
                        checkedColor = AuthenticGreen,
                        uncheckedColor = RavenMuted
                    )
                )
                Text(
                    s.backupConfirmCheck,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (confirmed) Color.White else RavenMuted,
                    lineHeight = 18.sp
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            Button(
                onClick = {
                    // D-14: flip the backup-completed gate so restore-over-wallet is allowed.
                    context.getSharedPreferences("raventag_wallet", Context.MODE_PRIVATE)
                        .edit().putBoolean("backup_completed", true).apply()
                    // Zero-fill before handing control back so the buffer cannot linger.
                    revealed?.let { java.util.Arrays.fill(it, ' ') }
                    revealed = null
                    onConfirmed()
                },
                enabled = confirmed,
                modifier = Modifier
                    .padding(horizontal = 20.dp)
                    .fillMaxWidth()
                    .height(52.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = AuthenticGreen,
                    disabledContainerColor = RavenCard
                ),
                shape = RoundedCornerShape(14.dp)
            ) {
                Icon(Icons.Default.CheckCircle, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text(s.mnemonicSavedIt, fontWeight = FontWeight.Bold)
            }
        }

        Spacer(modifier = Modifier.height(32.dp))
    }
    }
}

/**
 * Runs the biometric reveal flow. In fresh-setup mode (`prefillMnemonic` non-null/blank)
 * we skip the Keystore round-trip because the mnemonic is already in memory and no
 * ciphertext exists yet. In later-reveal mode we delegate to [MnemonicExporter].
 */
private suspend fun revealWithBiometric(
    context: Context,
    wm: WalletManager?,
    prefillMnemonic: String?,
    strings: AppStrings,
    snackbarHostState: SnackbarHostState,
    onKeystoreInvalidated: () -> Unit,
    onRevealed: (CharArray) -> Unit
) {
    if (!prefillMnemonic.isNullOrBlank()) {
        // Setup flow: the wallet has been generated but not yet persisted; the biometric
        // cover card acts as a tap-through confirmation (D-15 CryptoObject cannot bind
        // to a ciphertext that does not yet exist).
        onRevealed(prefillMnemonic.toCharArray())
        return
    }
    if (wm == null) {
        snackbarHostState.showSnackbar(strings.mnemonicRevealFailed)
        return
    }
    val activity = context as? FragmentActivity
    if (activity == null) {
        snackbarHostState.showSnackbar(strings.mnemonicRevealFailed)
        return
    }
    val gate = BiometricGate(activity)
    val result = MnemonicExporter.revealMnemonic(gate, wm)
    result.onSuccess { chars -> onRevealed(chars) }
    result.onFailure { t ->
        when (t) {
            is BiometricCancelledException ->
                snackbarHostState.showSnackbar(strings.authCanceledSnackbar)
            is KeystoreInvalidatedException ->
                onKeystoreInvalidated()
            else -> snackbarHostState.showSnackbar(strings.mnemonicRevealFailed)
        }
    }
}
