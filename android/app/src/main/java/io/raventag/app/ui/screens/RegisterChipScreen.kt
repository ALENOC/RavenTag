/**
 * RegisterChipScreen.kt
 *
 * Manual chip registration form for the brand operator. This screen covers the case
 * where a chip has already been programmed (keys written, NFC configured) but its
 * nfc_pub_id has not yet been registered on the backend, for example when the automated
 * write flow was completed without an Admin Key, or when the operator needs to re-register
 * a chip after a backend migration.
 *
 * Registration process:
 *   1. Operator enters the full Ravencoin unique token asset name (e.g. "FASHIONX/BAG01#SN0001").
 *   2. Operator enters the raw 7-byte tag UID (14 hex chars) read from the chip's NDEF message
 *      or retrieved from the WriteTagScreen success display.
 *   3. Operator enters the Admin API key (sent as "X-Admin-Key" header; never stored locally).
 *   4. On submit, the ViewModel calls POST /api/chips with these values. The backend derives
 *      nfc_pub_id = SHA-256(uid || BRAND_SALT) server-side and stores the record.
 *
 * The resulting nfc_pub_id is shown in the success banner so the operator can verify it
 * matches the value from the write step.
 */
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
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.raventag.app.ui.theme.*

/**
 * Manual chip registration form.
 *
 * @param isLoading      When true, the submit button shows a spinner and all fields are
 *                       effectively disabled (the button is the only interactive element).
 * @param resultMessage  Feedback string for the result banner shown after a submit attempt.
 * @param resultSuccess  Nullable: null = no result yet, true = registered OK, false = error.
 * @param nfcPubId       The nfc_pub_id returned by the backend on success; shown in the banner
 *                       so the operator can compare it with their records from the write step.
 * @param onBack         Navigate back without submitting.
 * @param onRegister     Callback invoked with (assetName, tagUid, adminKey) when the operator
 *                       taps the submit button. The ViewModel performs the HTTP call.
 */
@Composable
fun RegisterChipScreen(
    isLoading: Boolean,
    resultMessage: String?,
    resultSuccess: Boolean?,
    nfcPubId: String?,
    onBack: () -> Unit,
    onRegister: (assetName: String, tagUid: String, adminKey: String) -> Unit
) {
    // ---- Local form state ----
    var assetName by remember { mutableStateOf("") }
    var tagUid by remember { mutableStateOf("") }
    // Admin key is entered fresh each time; it is never persisted to SharedPreferences here.
    var adminKey by remember { mutableStateOf("") }

    // UID validation: NTAG 424 DNA uses a 7-byte UID = 14 hex characters, alphanumeric only.
    val isValidUid = tagUid.length == 14 && tagUid.all { it.isLetterOrDigit() }
    // Combined form validity guard: all three fields must meet their minimum requirements.
    val isValid = assetName.length >= 3 && isValidUid && adminKey.isNotEmpty()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(RavenBg)
            .statusBarsPadding()
            .padding(horizontal = 20.dp)
            .verticalScroll(rememberScrollState())
    ) {
        Spacer(modifier = Modifier.height(24.dp))

        // Header: back button + title describing the action.
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            IconButton(onClick = onBack) {
                Icon(Icons.Default.ArrowBack, contentDescription = "Back", tint = Color.White)
            }
            Column {
                Text("Register NFC Chip", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = Color.White)
                Text("Link chip UID to a Ravencoin asset", style = MaterialTheme.typography.bodySmall, color = RavenMuted)
            }
        }

        Spacer(modifier = Modifier.height(20.dp))

        // Info card: explains why the raw UID stays private.
        // The backend never stores the raw UID; only the derived nfc_pub_id is published.
        // This hides the chip's hardware identity from public IPFS metadata.
        Card(
            colors = CardDefaults.cardColors(containerColor = Color(0xFF0A1020)),
            border = BorderStroke(1.dp, RavenOrange.copy(alpha = 0.25f)),
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(modifier = Modifier.padding(14.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Icon(Icons.Default.Info, contentDescription = null, tint = RavenOrange, modifier = Modifier.size(16.dp).padding(top = 2.dp))
                Text(
                    "The server uses BRAND_SALT to compute nfc_pub_id = SHA-256(uid ∥ salt). The raw UID stays private; only nfc_pub_id is published in IPFS metadata.",
                    style = MaterialTheme.typography.bodySmall,
                    color = RavenMuted
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Result banner: visible only after the first submit attempt.
        // On success it also shows the nfc_pub_id returned by the backend.
        resultSuccess?.let { success ->
            Card(
                colors = CardDefaults.cardColors(containerColor = if (success) AuthenticGreenBg else NotAuthenticRedBg),
                border = BorderStroke(1.dp, if (success) AuthenticGreen.copy(0.4f) else NotAuthenticRed.copy(0.4f)),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            if (success) Icons.Default.CheckCircle else Icons.Default.Error,
                            contentDescription = null,
                            tint = if (success) AuthenticGreen else NotAuthenticRed,
                            modifier = Modifier.size(20.dp)
                        )
                        Text(resultMessage ?: "", color = if (success) AuthenticGreen else NotAuthenticRed, style = MaterialTheme.typography.bodySmall)
                    }
                    // Show the nfc_pub_id below the status message on success.
                    // The operator can cross-check this against their write-step records.
                    if (success && nfcPubId != null) {
                        Spacer(modifier = Modifier.height(10.dp))
                        Text("NFC PUBLIC ID", style = MaterialTheme.typography.labelSmall, color = RavenMuted)
                        Spacer(modifier = Modifier.height(4.dp))
                        // Monospace makes the 64-char hex string easier to compare visually.
                        Text(nfcPubId, style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace), color = AuthenticGreen)
                    }
                }
            }
            Spacer(modifier = Modifier.height(16.dp))
        }

        // Asset name field: accepts any Ravencoin asset depth (ROOT, SUB, or unique token).
        // Input is uppercased on every keystroke to match Ravencoin naming rules.
        RavenFormField(label = "Asset Name", hint = "e.g. FASHIONX/BAG001#SN0001") {
            OutlinedTextField(
                value = assetName,
                onValueChange = { assetName = it.uppercase() },
                placeholder = { Text("FASHIONX/BAG001#SN0001", color = RavenMuted, style = MaterialTheme.typography.bodySmall) },
                modifier = Modifier.fillMaxWidth(),
                colors = ravenTextFieldColors(),
                shape = RoundedCornerShape(12.dp),
                singleLine = true
            )
        }

        Spacer(modifier = Modifier.height(12.dp))

        // Tag UID field: input is uppercased and stripped of non-alphanumeric characters,
        // then capped at 14 characters to match the 7-byte NTAG 424 DNA UID length.
        // A live validation hint shows the current character count when the input is invalid.
        RavenFormField(
            label = "Tag UID",
            hint = if (tagUid.isNotEmpty() && !isValidUid)
                "Must be exactly 14 hex characters (7 bytes). Current: ${tagUid.length}"
            else "14 hex characters = 7 bytes, e.g. 04A1B2C3D4E5F6"
        ) {
            OutlinedTextField(
                value = tagUid,
                onValueChange = { tagUid = it.uppercase().filter { c -> c.isLetterOrDigit() }.take(14) },
                placeholder = { Text("04A1B2C3D4E5F6", color = RavenMuted, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace) },
                modifier = Modifier.fillMaxWidth(),
                // Border turns red while the UID has been typed but is not yet 14 chars.
                colors = ravenTextFieldColors(isError = tagUid.isNotEmpty() && !isValidUid),
                shape = RoundedCornerShape(12.dp),
                singleLine = true,
                isError = tagUid.isNotEmpty() && !isValidUid
            )
        }

        Spacer(modifier = Modifier.height(12.dp))

        // Admin API key field: rendered as a password field so the key is not visible on screen.
        // The hint makes clear the key is not saved anywhere in the app.
        RavenFormField(label = "Admin API Key", hint = "X-Admin-Key, never stored in app") {
            OutlinedTextField(
                value = adminKey,
                onValueChange = { adminKey = it },
                placeholder = { Text("your-secret-admin-key", color = RavenMuted, style = MaterialTheme.typography.bodySmall) },
                modifier = Modifier.fillMaxWidth(),
                colors = ravenTextFieldColors(),
                shape = RoundedCornerShape(12.dp),
                singleLine = true,
                // PasswordVisualTransformation replaces each character with a bullet dot.
                visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation()
            )
        }

        Spacer(modifier = Modifier.height(24.dp))

        // Submit button: enabled only when all three fields pass validation and no request is in flight.
        Button(
            onClick = { onRegister(assetName, tagUid, adminKey) },
            enabled = isValid && !isLoading,
            modifier = Modifier.fillMaxWidth().height(52.dp),
            colors = ButtonDefaults.buttonColors(containerColor = RavenOrange, disabledContainerColor = RavenOrange.copy(alpha = 0.3f)),
            shape = RoundedCornerShape(14.dp)
        ) {
            if (isLoading) {
                // Spinner replaces button content while the HTTP request is in flight.
                CircularProgressIndicator(color = Color.White, modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
            } else {
                Icon(Icons.Default.Memory, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text("Register Chip", fontWeight = FontWeight.SemiBold)
            }
        }

        Spacer(modifier = Modifier.height(32.dp))
    }
}

/**
 * Reusable labeled form field wrapper used throughout this screen.
 *
 * Renders a small uppercase label above the [content] slot and an optional
 * orange hint below it. This keeps field labeling consistent without repeating
 * the label/hint layout in every field definition.
 *
 * @param label   Field label text; rendered uppercase with a small letter spacing.
 * @param hint    Optional helper or validation message shown below the field in [RavenOrange].
 * @param content Composable content slot for the actual input widget.
 */
@Composable
private fun RavenFormField(label: String, hint: String? = null, content: @Composable () -> Unit) {
    Column {
        Text(label.uppercase(), style = MaterialTheme.typography.labelSmall, color = RavenMuted,
            letterSpacing = androidx.compose.ui.unit.TextUnit(1f, androidx.compose.ui.unit.TextUnitType.Sp))
        Spacer(modifier = Modifier.height(4.dp))
        content()
        hint?.let {
            Spacer(modifier = Modifier.height(4.dp))
            Text(it, style = MaterialTheme.typography.labelSmall, color = RavenOrange)
        }
    }
}

/**
 * Shared [OutlinedTextFieldDefaults.colors] configuration used by all text fields on this screen.
 *
 * Centralizing the color set ensures visual consistency and makes future theme changes easy.
 * When [isError] is true, the border switches from [RavenOrange] / [RavenBorder] to shades
 * of [NotAuthenticRed] to signal invalid input.
 *
 * @param isError When true, border colors switch to the error palette.
 */
@Composable
private fun ravenTextFieldColors(isError: Boolean = false) = OutlinedTextFieldDefaults.colors(
    focusedBorderColor = if (isError) NotAuthenticRed else RavenOrange,
    unfocusedBorderColor = if (isError) NotAuthenticRed.copy(0.6f) else RavenBorder,
    focusedTextColor = Color.White,
    unfocusedTextColor = Color.White,
    cursorColor = RavenOrange,
    focusedContainerColor = RavenCard,
    unfocusedContainerColor = RavenCard
)
